/*
 * Copyright (c) 2023 Turtleisaac.
 *
 * This file is part of Nds4j.
 *
 * Nds4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Nds4j is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Nds4j. If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.turtleisaac.nds4j.g3d;

import io.github.turtleisaac.nds4j.framework.MemBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * A single 3D model decoded from an {@link ModelSet} (NSBMD). A model is a set of named {@link Mesh}es
 * (its "shapes"/polygons); each mesh's triangles are produced by interpreting that shape's Nintendo DS
 * geometry command stream (its "display list"). Vertex positions are decoded from their fixed-point
 * form and multiplied by the model's position scale, so they are already in model space.
 * <p>
 * This is the meaningful, exportable representation of the geometry (see {@link #toObj()}); the raw
 * bytes still round-trip byte-for-byte through {@link ModelSet}. Materials and textures are layered on
 * in later work.
 * <p>
 * <b>Skeleton / placement:</b> each shape is drawn under a node (bone) transform. The decoder parses
 * every node's local matrix (translation, rotation &mdash; full or pivot-compressed &mdash; and scale)
 * and walks the model's SBC render-command stream to learn which node each shape binds to and each
 * node's parent, then composes world matrices down the hierarchy and applies them to the geometry. As
 * a self-check, a model's decoded bounding box should match the box its header declares:
 * {@link #getDecodedBoundingBox()} / {@link #getHeaderBoundingBox()} expose both, and
 * {@link #isSingleNode()} reports whether a model even has a multi-node skeleton. This lands ~96% of
 * all retail models exactly. Maya and envelope models are placed by the SBC matrix stack
 * ({@code sbc.c}); billboard nodes are flagged for a viewer and are not turned toward a camera here.
 */
public class Model
{
    private final String name;
    private final double posScale;
    private final double invPosScale;
    private final boolean maya;
    // Vertex/triangle/quad totals the model header declares. Used to validate the interpreter: the
    // number of vertices emitted while walking the display lists must equal expectedVertexCount.
    private final int expectedVertexCount;
    private final int expectedTriangleCount;
    private final int expectedQuadCount;
    private final int nodeCount;
    private final float[] headerBoxMin = new float[3];
    private final float[] headerBoxMax = new float[3];
    private final List<Mesh> meshes = new ArrayList<>();
    private final List<Material> materials = new ArrayList<>();
    // Bind-pose skeleton, retained so an animation can re-pose the model: each node's local transform,
    // its parent (for hierarchy composition), and the raw (pre-placement) vertices per mesh.
    private Srt[] nodeLocals;
    private int[] nodeParent;
    // True if the SBC uses billboard (BB/BBY) or skinning (NODEMIX) commands: the final pose is
    // camera- or blend-dependent, so a static bind-pose decode legitimately can't reproduce it.
    private boolean dynamicPose;
    boolean usesMtxScale; // set if any shape's display list contains an MTX_SCALE command
    // Per-node billboard flag (BB/BBY), for inspection/rendering. A billboard node's geometry faces the
    // camera at runtime; a static decode leaves it at its authored orientation.
    private boolean[] billboardNode;
    private boolean[] yBillboard;
    private byte[] sbcBytes;
    private boolean envelopeCmd;
    private boolean stackPose;
    private double[][] invBind;
    private StackPose.Frame bindFrame;
    // Bind-pose world translation/scale per node (the billboard pivot and its accumulated scale).
    private double[][] bindWorldTranslation;
    private double[][] bindWorldScale;

    Model(byte[] mdl0, int modelStart, String name)
    {
        this.name = name;

        MemBuf.MemBufReader model = MemBuf.create(mdl0).reader();
        int ofsSbc = (int) readU32(mdl0, modelStart + 4);
        int ofsMat = (int) readU32(mdl0, modelStart + 8);
        int ofsShp = (int) readU32(mdl0, modelStart + 12);
        model.setPosition(modelStart);
        int modelSize = model.readInt();
        model.setPosition(modelStart + 16);
        int ofsEvp = model.readInt();

        // model header (NNS_G3dModelInfo)
        int info = modelStart + 0x14;
        nodeCount = mdl0[info + 3] & 0xFF;
        int matCount = mdl0[info + 4] & 0xFF;
        int shapeCountHeader = mdl0[info + 5] & 0xFF;
        posScale = readU32(mdl0, info + 8) / 4096.0;
        model.setPosition(info + 12);
        invPosScale = model.readInt() / 4096.0;
        model.setPosition(info + 1);
        maya = model.readByte() == 1;

        if (ofsEvp != 0)
            invBind = readInvBind(model, modelStart + ofsEvp, modelStart + modelSize, nodeCount, mdl0.length);

        expectedVertexCount = readU16(mdl0, info + 16);
        expectedTriangleCount = readU16(mdl0, info + 20);
        expectedQuadCount = readU16(mdl0, info + 22);

        // header bounding box: min corner (x,y,z) then dimensions (w,h,d) as 1.3.12 fixed at info+0x18,
        // scaled by boxPosScale (1.19.12 fixed at info+0x24). This is the placement oracle.
        double boxScale = readU32(mdl0, info + 0x24) / 4096.0;
        for (int c = 0; c < 3; c++)
        {
            double lo = (short) readU16(mdl0, info + 0x18 + c * 2) / 4096.0 * boxScale;
            double dim = (short) readU16(mdl0, info + 0x1E + c * 2) / 4096.0 * boxScale;
            headerBoxMin[c] = (float) lo;
            headerBoxMax[c] = (float) (lo + dim);
        }

        // Materials: name -> texture/palette + texture size, so each shape's UVs can be normalised and
        // pointed at the right texture in the embedded (or sibling) TEX0.
        parseMaterials(mdl0, modelStart + ofsMat, matCount);

        // Per-node local transforms and, by walking the render commands, which node's world matrix each
        // shape is drawn with (and which material is bound). This is what positions a multi-node model's
        // parts correctly and ties each shape to its texture.
        nodeLocals = parseNodeLocals(mdl0, modelStart + 0x40);
        int[] shapeNode = new int[shapeCountHeader];
        int[] shapeMaterial = new int[shapeCountHeader];
        java.util.Arrays.fill(shapeMaterial, -1);
        Srt[] nodeWorld = walkSbc(mdl0, modelStart + ofsSbc, nodeLocals, shapeNode, shapeMaterial, shapeCountHeader);

        // Retain each node's bind-pose world translation/scale so a billboard node can be re-oriented to
        // face the camera at render time (its authored rotation is discarded for BB/BBY).
        bindWorldTranslation = new double[nodeWorld.length][];
        bindWorldScale = new double[nodeWorld.length][];
        for (int n = 0; n < nodeWorld.length; n++)
        {
            Srt w = nodeWorld[n] != null ? nodeWorld[n] : Srt.identity();
            bindWorldTranslation[n] = w.t.clone();
            bindWorldScale[n] = w.s.clone();
        }

        // shape set: a dictionary of shapes, each record a byte offset (relative to the shape set) to a
        // 16-byte shape struct that points at its display list. The dictionary is authoritative.
        int shapeSet = modelStart + ofsShp;
        model.setPosition(shapeSet);
        G3dDictionary shapeDict = new G3dDictionary(model);

        for (int i = 0; i < shapeDict.size(); i++)
        {
            int shapeStruct = shapeSet + (int) readU32(shapeDict.getRecord(i), 0);
            model.setPosition(shapeStruct + 4);
            if ((model.readUInt32() & 8) != 0)
                envelopeCmd = true;

            int dlOffset = (int) readU32(mdl0, shapeStruct + 8); // relative to the shape struct
            int dlSize = (int) readU32(mdl0, shapeStruct + 12);
            Mesh mesh = interpretDisplayList(mdl0, shapeStruct + dlOffset, dlSize, shapeDict.getName(i));

            int node = (i < shapeNode.length) ? shapeNode[i] : 0;
            mesh.nodeIndex = node;
            mesh.rawPositions = mesh.positions.clone(); // pre-placement vertices, kept for re-posing
            Srt world = (node >= 0 && node < nodeWorld.length) ? nodeWorld[node] : null;
            transformInPlace(mesh.positions, world, posScale);
            mesh.material = (i < shapeMaterial.length && shapeMaterial[i] >= 0 && shapeMaterial[i] < materials.size())
                    ? materials.get(shapeMaterial[i])
                    : null;
            meshes.add(mesh);
        }

        stackPose = maya || envelopeCmd;
        if (stackPose)
            bindFrame = playStack(null, 0, true);
    }

    // A node's local placement as the NNS renderer keeps it: translation, per-axis scale and a 3x3
    // rotation, held <b>separately</b> (never baked into one matrix). Composition down the skeleton and
    // the final vertex transform both depend on keeping scale apart from rotation &mdash; see
    // {@link #compose} and {@link #transformInPlace}. Row-major rotation, row-vector convention
    // ({@code v' = v * r}), matching the reference {@code renderer.TransfMatrix}.
    private static final class Srt
    {
        final double[] t; // translation (x,y,z), model units
        final double[] s; // per-axis scale
        final double[] r; // 3x3 rotation, row-major

        Srt(double[] t, double[] s, double[] r)
        {
            this.t = t;
            this.s = s;
            this.r = r;
        }

        static Srt identity()
        {
            return new Srt(new double[] { 0, 0, 0 }, new double[] { 1, 1, 1 }, IDENT.clone());
        }
    }

    private static final double[] IDENT = { 1, 0, 0, 0, 1, 0, 0, 0, 1 };

    // Parses each node's local transform (NNS node data): u16 flags, fx16 rotation[0][0], then optional
    // translation (3x fx32), rotation remainder (8x fx16 for a full 3x3, or a compressed pivot form),
    // and scale (3x fx32, followed by an unused 3x fx32 inverse the renderer also discards). Returns the
    // node's translation/scale/rotation kept separate (an {@link Srt}), not a baked matrix: cascading a
    // parent's scale through a baked matrix shears scaled children, which is exactly what the NNS
    // renderer avoids by composing scale separately (verified against renderer.TransfMatrix).
    private Srt[] parseNodeLocals(byte[] d, int nodeSet)
    {
        int count = d[nodeSet + 1] & 0xFF;
        int recordsOffset = nodeSet + 2 + 10 + count * 4 + 4; // dict header + patricia + elemSize/ofsData
        Srt[] local = new Srt[count];

        for (int n = 0; n < count; n++)
        {
            int p = nodeSet + (int) readU32(d, recordsOffset + n * 4);
            int flags = readU16(d, p);
            p += 2;
            double r00 = (short) readU16(d, p) / 4096.0;
            p += 2;

            double[] t = { 0, 0, 0 };
            double[] r = { r00, 0, 0, 0, 1, 0, 0, 0, 1 };
            double[] s = { 1, 1, 1 };

            p = readVec3(d, p, (flags & NodeSrtFlag.NNS_G3D_SRTFLAG_TRANS_ZERO.mask) != 0, t);
            p = readRotation(d, p, flags, r);
            readVec3(d, p, (flags & NodeSrtFlag.NNS_G3D_SRTFLAG_SCALE_ONE.mask) != 0, s);

            local[n] = new Srt(t, s, r);
        }

        return local;
    }

    /**
     * @param d model bytes
     * @param p cursor on the optional vector
     * @param omit true when the record stores nothing because the vector is the identity
     * @param dest translation or scale, written when it is stored
     * @return cursor after the vector
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData"
     */
    private static int readVec3(byte[] d, int p, boolean omit, double[] dest)
    {
        if (omit)
            return p;

        dest[0] = readFx32(d, p);
        dest[1] = readFx32(d, p + 4);
        dest[2] = readFx32(d, p + 8);

        return p + 12;
    }

    /**
     * @param d model bytes
     * @param p cursor on the optional rotation
     * @param flags node flag word
     * @param r rotation, with {@code r[0]} already read
     * @return cursor after the rotation
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData"
     */
    private static int readRotation(byte[] d, int p, int flags, double[] r)
    {
        if ((flags & NodeSrtFlag.NNS_G3D_SRTFLAG_ROT_ZERO.mask) != 0)
            return p;

        if ((flags & NodeSrtFlag.NNS_G3D_SRTFLAG_PIVOT_EXIST.mask) != 0)
            return readPivot(d, p, flags, r);

        r[1] = readFx16(d, p);
        r[2] = readFx16(d, p + 2);
        r[3] = readFx16(d, p + 4);
        r[4] = readFx16(d, p + 6);
        r[5] = readFx16(d, p + 8);
        r[6] = readFx16(d, p + 10);
        r[7] = readFx16(d, p + 12);
        r[8] = readFx16(d, p + 14);

        return p + 16;
    }

    /**
     * Pivot-compressed rotation: two values fill a 2x2 minor with a +/-1 pivot cell. The pivot index is
     * flag bits 4-7.
     * @param d model bytes
     * @param p cursor on the two pivot values
     * @param flags node flag word
     * @param r rotation to fill
     * @return cursor after the two values
     * @see "NitroSystem g3d/src/sbc.c getPivotMatrix"
     */
    private static int readPivot(byte[] d, int p, int flags, double[] r)
    {
        double av = readFx16(d, p);
        double bv = readFx16(d, p + 2);
        double c = (flags & NodeSrtFlag.NNS_G3D_SRTFLAG_SIGN_REVC.mask) != 0 ? -bv : bv;
        double dd = (flags & NodeSrtFlag.NNS_G3D_SRTFLAG_SIGN_REVD.mask) != 0 ? -av : av;
        double one = (flags & NodeSrtFlag.NNS_G3D_SRTFLAG_PIVOT_MINUS.mask) != 0 ? -1.0 : 1.0;
        int sel = (flags >> 4) & 0xF;

        if (sel <= 8)
        {
            int row = sel / 3;
            int col = sel % 3;
            double[] arr = { av, bv, c, dd };
            int k = 0;

            for (int ii = 0; ii < 3; ii++)
            {
                for (int jj = 0; jj < 3; jj++)
                {
                    r[ii * 3 + jj] = (jj == col || ii == row) ? 0 : arr[k++];
                }
            }

            r[row * 3 + col] = one;
        }

        return p + 4;
    }

    // Walks the SBC render-command stream to record each node's parent (for the world-matrix hierarchy)
    // and which node is current when each shape is drawn, then resolves node world matrices. Commands:
    // opcode = byte & 0x1F, store/restore flags in the high bits; every command's operand length is
    // consumed so the walk stays in sync. Operand widths follow NNS exactly: NODEDESC is nodeId, parentId,
    // opt, then +1 if NNS_G3D_SBCFLG_001 (store) is set and +1 if NNS_G3D_SBCFLG_010 (restore) is set; BB/BBY are
    // nodeId + the same optional store/restore bytes; NODEMIX is 2 + 3*count; CALLDL is 8; etc.
    // Parses the material set (NNS_G3dResMat): a dictionary of named materials, each pointing at a
    // material struct, plus two dictionaries mapping texture names and palette names to the materials
    // that use them (the material struct itself doesn't name its texture). We keep, per material, its
    // texture/palette name and the texel size (from the struct's texImageParam), which is what UV
    // normalisation and texturing need. Layout verified against nitroreader.nsbmd.Model.readMaterialSet.
    private void parseMaterials(byte[] d, int matSet, int matCount)
    {
        int ofsTexDict = readU16(d, matSet);
        int ofsPltDict = readU16(d, matSet + 2);

        MemBuf buf = MemBuf.create(d);
        MemBuf.MemBufReader r = buf.reader();
        r.setPosition(matSet + 4);
        G3dDictionary matDict = new G3dDictionary(r);

        for (int i = 0; i < matDict.size(); i++)
        {
            // The material struct's texImageParam carries the wrap/flip bits, but its size field is left
            // zero here (the NNS renderer takes the texture's true size from the TEX0 texture at bind
            // time, not the material). So only the wrap/flip flags are read; UV normalisation uses the
            // bound texture's own dimensions.
            int structOfs = matSet + (int) readU32(matDict.getRecord(i), 0);
            long texImageParam = readU32(d, structOfs + 0x14);

            boolean repeatS = (texImageParam & (1 << 16)) != 0;
            boolean repeatT = (texImageParam & (1 << 17)) != 0;
            boolean flipS = (texImageParam & (1 << 18)) != 0;
            boolean flipT = (texImageParam & (1 << 19)) != 0;

            materials.add(new Material(matDict.getName(i), repeatS, repeatT, flipS, flipT));
        }

        bindNames(d, matSet, matSet + ofsTexDict, true);
        bindNames(d, matSet, matSet + ofsPltDict, false);
    }

    // Applies a texture-to-material (or palette-to-material) dictionary: each entry names a resource and
    // points at a list of material indices that use it. Entry data (4 bytes): bits 0-15 = offset to the
    // index list (from the material set), bits 16-23 = list length, bit 24 = "no binding" flag.
    private void bindNames(byte[] d, int matSet, int dictStart, boolean texture)
    {
        MemBuf buf = MemBuf.create(d);
        MemBuf.MemBufReader r = buf.reader();
        r.setPosition(dictStart);
        G3dDictionary dict = new G3dDictionary(r);

        for (int i = 0; i < dict.size(); i++)
        {
            long entry = readU32(dict.getRecord(i), 0);
            if ((entry & 0x01000000L) != 0)
                continue;

            int listOfs = (int) (entry & 0xFFFF);
            int listLen = (int) ((entry >> 16) & 0xFF);

            for (int k = 0; k < listLen; k++)
            {
                int matIdx = d[matSet + listOfs + k] & 0xFF;
                if (matIdx < materials.size())
                {
                    if (texture)
                        materials.get(matIdx).textureName = dict.getName(i);
                    else
                        materials.get(matIdx).paletteName = dict.getName(i);
                }
            }
        }
    }

    private Srt[] walkSbc(byte[] d, int sbc, Srt[] nodeLocal, int[] shapeNode, int[] shapeMaterial, int shapeCount)
    {
        int count = nodeLocal.length;
        int[] parent = new int[count];
        java.util.Arrays.fill(parent, -1);
        billboardNode = new boolean[count];
        yBillboard = new boolean[count];
        int[] stackNode = new int[64];
        int[] cur = new int[] { 0, -1 };

        MemBuf.MemBufReader commands = MemBuf.create(d).reader();
        commands.setPosition(sbc);
        while (commands.getPosition() < d.length)
        {
            int b = commands.readByte();

            SbcCommand cmd = SbcCommand.from(b);
            if (cmd == SbcCommand.NNS_G3D_SBC_RET)
                break;

            int next = cmd != null && cmd.code < SbcCommand.NNS_G3D_SBC_BBY.code
                    ? sbcLow(commands, cmd, b, cur, parent, stackNode, count, shapeNode, shapeMaterial, shapeCount)
                    : sbcHigh(commands, cmd, b, cur, stackNode, count);
            if (next < 0)
                break;

            commands.setPosition(next);
        }

        int p = commands.getPosition();
        sbcBytes = java.util.Arrays.copyOfRange(d, sbc, Math.min(p, d.length));
        nodeParent = parent; // retained so an animation can recompose the hierarchy

        Srt[] world = new Srt[count];
        for (int n = 0; n < count; n++)
            world[n] = resolveWorld(n, parent, nodeLocal, world);

        return world;
    }

    /**
     * @param commands SBC reader, on the operand
     * @param cmd opcode below {@link SbcCommand#NNS_G3D_SBC_BBY}
     * @param raw the command byte, including flag bits
     * @param cur {@code {node, material}}
     * @param parent parent index per node
     * @param stackNode node stored in each stack slot
     * @param count node count
     * @param shapeNode shape-to-node map
     * @param shapeMaterial shape-to-material map
     * @param shapeCount shape count
     * @return the next opcode cursor
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_NODE"
     */
    private int sbcLow(MemBuf.MemBufReader commands, SbcCommand cmd, int raw, int[] cur, int[] parent, int[] stackNode,
            int count, int[] shapeNode, int[] shapeMaterial, int shapeCount)
    {
        switch (cmd)
        {
            case NNS_G3D_SBC_NODE:
                return storeByte(commands, cur, 0, 2);
            case NNS_G3D_SBC_MTX:
                return sbcMtx(commands, cur, stackNode);
            case NNS_G3D_SBC_MAT:
                return storeByte(commands, cur, 1, 1);
            case NNS_G3D_SBC_SHP:
                return sbcShape(commands, cur, shapeNode, shapeMaterial, shapeCount);
            case NNS_G3D_SBC_NODEDESC:
                return sbcNodeDesc(commands, raw, cur, parent, stackNode, count);
            case NNS_G3D_SBC_BB:
                return sbcBillboard(commands, raw, cur, stackNode, count, false);
            default:
                return commands.getPosition();
        }
    }

    /**
     * @param d SBC bytes
     * @param p operand cursor
     * @param op opcode of {@link SbcCommand#NNS_G3D_SBC_BBY} or higher
     * @param flags SBC flag bits
     * @param cur {@code {node, material}}
     * @param stackNode node stored in each stack slot
     * @param count node count
     * @return the next opcode cursor, or -1 to stop
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_BBY"
     */
    int sbcHigh(byte[] d, int p, int op, int flags, int[] cur, int[] stackNode, int count)
    {
        MemBuf.MemBufReader commands = MemBuf.create(d).reader();
        commands.setPosition(p);
        return sbcHigh(commands, SbcCommand.from(op), flags, cur, stackNode, count);
    }

    /**
     * @param commands SBC reader, on the operand
     * @param cmd opcode of {@link SbcCommand#NNS_G3D_SBC_BBY} or higher, or null when it is not defined
     * @param flags SBC flag bits
     * @param cur {@code {node, material}}
     * @param stackNode node stored in each stack slot
     * @param count node count
     * @return the next opcode cursor, or -1 to stop
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_BBY"
     */
    private int sbcHigh(MemBuf.MemBufReader commands, SbcCommand cmd, int flags, int[] cur, int[] stackNode, int count)
    {
        if (cmd == SbcCommand.NNS_G3D_SBC_BBY)
            return sbcBillboard(commands, flags, cur, stackNode, count, true);

        if (cmd == SbcCommand.NNS_G3D_SBC_NODEMIX)
            return sbcMix(commands);

        if (cmd == SbcCommand.NNS_G3D_SBC_CALLDL)
            return skip(commands, 8);

        if (cmd == SbcCommand.NNS_G3D_SBC_POSSCALE)
            return commands.getPosition();

        if (cmd == SbcCommand.NNS_G3D_SBC_ENVMAP || cmd == SbcCommand.NNS_G3D_SBC_PRJMAP)
            return skip(commands, 2);

        return -1;
    }

    /**
     * @param commands SBC reader
     * @param n operand bytes that this command does not decode
     * @return the cursor after those bytes
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_PRJMAP"
     */
    private static int skip(MemBuf.MemBufReader commands, int n)
    {
        commands.skip(n);
        return commands.getPosition();
    }

    /**
     * @param commands SBC reader, on the id
     * @param cur node and material slots
     * @param slot which entry of {@code cur} to write
     * @param skip bytes to advance, including the id
     * @return the cursor after the id
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_NODE"
     */
    private static int storeByte(MemBuf.MemBufReader commands, int[] cur, int slot, int skip)
    {
        cur[slot] = commands.readByte();

        if (skip > 1)
            commands.skip(skip - 1);

        return commands.getPosition();
    }

    /**
     * @param commands SBC reader, on the stack slot
     * @param cur current node slot
     * @param stackNode stack slots
     * @return cursor after the slot byte
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_MTX"
     */
    private static int sbcMtx(MemBuf.MemBufReader commands, int[] cur, int[] stackNode)
    {
        cur[0] = stackNode[commands.readByte() & 0x1F];
        return commands.getPosition();
    }

    /**
     * @param commands SBC reader, on the shape id
     * @param cur current node and material
     * @param shapeNode shape-to-node map
     * @param shapeMaterial shape-to-material map
     * @param shapeCount shape count
     * @return cursor after the shape id
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_SHP"
     */
    private static int sbcShape(MemBuf.MemBufReader commands, int[] cur, int[] shapeNode, int[] shapeMaterial,
            int shapeCount)
    {
        int shp = commands.readByte();
        if (shp < shapeCount)
        {
            shapeNode[shp] = cur[0];
            shapeMaterial[shp] = cur[1];
        }

        return commands.getPosition();
    }

    /**
     * @param commands SBC reader, on the node id
     * @param flags store and restore bits
     * @param cur current node slot
     * @param parent parent index per node
     * @param stackNode stack slots
     * @param count node count
     * @return cursor after the NODEDESC operands
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_NODEDESC"
     */
    private static int sbcNodeDesc(MemBuf.MemBufReader commands, int flags, int[] cur, int[] parent, int[] stackNode,
            int count)
    {
        int nid = commands.readByte();
        int par = commands.readByte();
        commands.readByte();

        if (nid < count)
        {
            parent[nid] = par < count ? par : -1;
            cur[0] = nid;
        }

        if ((flags & SbcFlag.NNS_G3D_SBCFLG_001.mask) != 0)
            stackNode[commands.readByte() & 0x1F] = nid;
        if ((flags & SbcFlag.NNS_G3D_SBCFLG_010.mask) != 0)
            commands.readByte();

        return commands.getPosition();
    }

    /**
     * @param commands SBC reader, on the node id
     * @param flags store and restore bits
     * @param cur current node slot
     * @param stackNode stack slots
     * @param count node count
     * @param yAxis true for {@code BBY}
     * @return cursor after the billboard operands
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_BB"
     */
    private int sbcBillboard(MemBuf.MemBufReader commands, int flags, int[] cur, int[] stackNode, int count,
            boolean yAxis)
    {
        dynamicPose = true;
        int nid = commands.readByte();
        cur[0] = nid;

        if (nid < count)
        {
            billboardNode[nid] = true;
            if (yAxis)
                yBillboard[nid] = true;
        }

        if ((flags & SbcFlag.NNS_G3D_SBCFLG_001.mask) != 0)
            stackNode[commands.readByte() & 0x1F] = nid;
        if ((flags & SbcFlag.NNS_G3D_SBCFLG_010.mask) != 0)
            commands.readByte();

        return commands.getPosition();
    }

    /**
     * @param commands SBC reader, on idxMtxDest
     * @return cursor after the NODEMIX term list
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_NODEMIX"
     */
    private int sbcMix(MemBuf.MemBufReader commands)
    {
        dynamicPose = true;
        envelopeCmd = true;

        commands.readByte();
        int terms = commands.readByte();
        return commands.getPosition() + terms * 3;
    }

    private Srt resolveWorld(int n, int[] parent, Srt[] local, Srt[] world)
    {
        if (world[n] != null)
            return world[n];

        world[n] = local[n]; // guard against cycles
        if (parent[n] < 0 || parent[n] == n)
            return local[n];

        Srt pw = resolveWorld(parent[n], parent, local, world);
        world[n] = compose(pw, local[n]);
        return world[n];
    }

    // Composes a child's local placement under its parent's world placement, exactly as the NNS renderer
    // does (renderer.TransfMatrix.applyParentTransf). Scale is <b>not</b> baked into rotation: it
    // accumulates as a separate factor, so a scaled parent scales a child's axes without shearing its
    // rotated geometry. With row-vector convention the composed rotation is child*parent, the child's
    // translation is first scaled by the parent's scale then rotated into the parent's frame and offset,
    // and the scales multiply componentwise.
    private static Srt compose(Srt parent, Srt child)
    {
        double[] s = { child.s[0] * parent.s[0], child.s[1] * parent.s[1], child.s[2] * parent.s[2] };
        double[] ts = { child.t[0] * parent.s[0], child.t[1] * parent.s[1], child.t[2] * parent.s[2] };

        double[] tr = rowMul(parent.r, ts);
        double[] t = { parent.t[0] + tr[0], parent.t[1] + tr[1], parent.t[2] + tr[2] };

        return new Srt(t, s, matMul(child.r, parent.r));
    }

    // Row-vector times matrix: out = v * r (out[i] = sum_j v[j] * r[j][i]), r row-major.
    private static double[] rowMul(double[] r, double[] v)
    {
        return new double[] {
                v[0] * r[0] + v[1] * r[3] + v[2] * r[6],
                v[0] * r[1] + v[1] * r[4] + v[2] * r[7],
                v[0] * r[2] + v[1] * r[5] + v[2] * r[8] };
    }

    // 3x3 * 3x3 (row-major): out[i][j] = sum_k a[i][k] * b[k][j].
    private static double[] matMul(double[] a, double[] b)
    {
        double[] r = new double[9];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
            {
                double s = 0;
                for (int k = 0; k < 3; k++)
                    s += a[i * 3 + k] * b[k * 3 + j];

                r[i * 3 + j] = s;
            }

        return r;
    }

    // Applies a node's world placement to raw (pre-posScale) vertices, matching the NNS vertex path
    // (renderer.nsbmd.gpucommands.VTX): scale the vertex, rotate it (row-vector), apply the model's
    // posScale, then offset by the node's world translation. A null placement means the identity node,
    // so only posScale is applied.
    private static void transformInPlace(float[] positions, Srt m, double posScale)
    {
        Srt w = (m == null) ? Srt.identity() : m;

        for (int i = 0; i < positions.length; i += 3)
        {
            double x = positions[i] * w.s[0], y = positions[i + 1] * w.s[1], z = positions[i + 2] * w.s[2];
            double rx = x * w.r[0] + y * w.r[3] + z * w.r[6];
            double ry = x * w.r[1] + y * w.r[4] + z * w.r[7];
            double rz = x * w.r[2] + y * w.r[5] + z * w.r[8];

            positions[i] = (float) (rx * posScale + w.t[0]);
            positions[i + 1] = (float) (ry * posScale + w.t[1]);
            positions[i + 2] = (float) (rz * posScale + w.t[2]);
        }
    }

    // Walks a shape's display list (a packed Nintendo DS geometry command stream) and assembles its
    // primitives into a triangle mesh. Commands are packed four opcode bytes per word, followed by
    // each command's parameters in order.
    private Mesh interpretDisplayList(byte[] d, int dlStart, int dlSize, String meshName)
    {
        List<float[]> positions = new ArrayList<>();
        List<float[]> texcoords = new ArrayList<>();
        List<Integer> triangles = new ArrayList<>();

        double[] vtx = new double[3];
        double[] uv = new double[2];
        int[] primitive = { -1 };
        List<Integer> primVerts = new ArrayList<>();

        int pos = dlStart;
        int end = dlStart + dlSize;
        while (pos + 4 <= end)
        {
            int[] ops = { d[pos] & 0xFF, d[pos + 1] & 0xFF, d[pos + 2] & 0xFF, d[pos + 3] & 0xFF };
            pos += 4;

            for (int op : ops)
            {
                pos = applyCommand(d, pos, op, vtx, uv, primitive, primVerts, positions, texcoords, triangles, meshName);
            }
        }

        if (primitive[0] >= 0)
            triangulate(primitive[0], primVerts, triangles);

        // Raw (pre-posScale) positions: posScale is applied together with the node placement in
        // transformInPlace, so scale/rotation/translation and posScale compose in the NNS vertex order.
        float[] pos3 = new float[positions.size() * 3];
        float[] uv2 = new float[texcoords.size() * 2];
        for (int i = 0; i < positions.size(); i++)
        {
            pos3[i * 3] = positions.get(i)[0];
            pos3[i * 3 + 1] = positions.get(i)[1];
            pos3[i * 3 + 2] = positions.get(i)[2];
            uv2[i * 2] = texcoords.get(i)[0];
            uv2[i * 2 + 1] = texcoords.get(i)[1];
        }

        int[] idx = new int[triangles.size()];
        for (int i = 0; i < idx.length; i++)
            idx[i] = triangles.get(i);

        Mesh mesh = new Mesh(meshName, pos3, uv2, idx);
        mesh.rawDisplayList = java.util.Arrays.copyOfRange(d, dlStart, dlStart + dlSize);
        return mesh;
    }

    /**
     * @param d display-list bytes
     * @param pos cursor on this command's parameters
     * @param op geometry opcode
     * @param vtx running vertex
     * @param uv running texcoord
     * @param primitive current primitive type, or -1
     * @param primVerts vertices of the open primitive
     * @param positions emitted positions
     * @param texcoords emitted texcoords
     * @param triangles triangle indices
     * @param meshName mesh name used when the opcode is unknown
     * @return the cursor after the command
     * @see "GBATEK DS 3D Video"
     */
    private int applyCommand(byte[] d, int pos, int op, double[] vtx, double[] uv, int[] primitive,
            List<Integer> primVerts, List<float[]> positions, List<float[]> texcoords, List<Integer> triangles,
            String meshName)
    {
        GeometryCommand cmd = GeometryCommand.from(op);
        if (cmd == null || !cmd.inMesh)
            throw unknownCommand(op, pos, meshName);

        if (cmd.emitsVertex)
            return readMeshVertex(cmd, d, pos, vtx, uv, primVerts, positions, texcoords);

        return readMeshState(cmd, d, pos, uv, primitive, primVerts, triangles);
    }

    /**
     * @param cmd a command that does not emit a vertex
     * @param d display-list bytes
     * @param pos cursor on the parameters
     * @param uv running texcoord
     * @param primitive current primitive type, or -1
     * @param primVerts vertices of the open primitive
     * @param triangles triangle indices
     * @return the cursor after the command
     * @see "GBATEK DS 3D Video, MTX_SCALE"
     */
    private int readMeshState(GeometryCommand cmd, byte[] d, int pos, double[] uv, int[] primitive,
            List<Integer> primVerts, List<Integer> triangles)
    {
        if (cmd == GeometryCommand.MTX_SCALE)
            return readMeshScale(pos);

        if (cmd == GeometryCommand.TEXCOORD)
            return readMeshTexcoord(d, pos, uv);

        if (cmd == GeometryCommand.BEGIN_VTXS)
            return readMeshBegin(d, pos, primitive, primVerts, triangles);

        if (cmd == GeometryCommand.NOP || cmd == GeometryCommand.END_VTXS)
            return pos;

        return pos + 4;
    }

    /**
     * MTX_SCALE is consumed and not applied. The reference jar's MTX_SCALE.execute() is a no-op, and
     * multiplying the retail MTX_SCALE models' vertices by it regresses them, because the display-list
     * magnify repeats the header posScale applied in transformInPlace.
     * @param pos cursor on the three scale words
     * @return the cursor after those words
     * @see "GBATEK DS 3D Video, MTX_SCALE"
     */
    private int readMeshScale(int pos)
    {
        usesMtxScale = true;
        return pos + 12;
    }

    /**
     * @param d display-list bytes
     * @param pos cursor on the texcoord word
     * @param uv running texcoord
     * @return the cursor after the word
     * @see "GBATEK DS 3D Video, TEXCOORD"
     */
    private static int readMeshTexcoord(byte[] d, int pos, double[] uv)
    {
        long p = readU32(d, pos);

        uv[0] = (short) (p & 0xFFFF) / 16.0;
        uv[1] = (short) ((p >> 16) & 0xFFFF) / 16.0;
        return pos + 4;
    }

    /**
     * @param d display-list bytes
     * @param pos cursor on the primitive word
     * @param primitive current primitive type, or -1
     * @param primVerts vertices of the open primitive
     * @param triangles triangle indices
     * @return the cursor after the word
     * @see "GBATEK DS 3D Video, BEGIN_VTXS"
     */
    private static int readMeshBegin(byte[] d, int pos, int[] primitive, List<Integer> primVerts, List<Integer> triangles)
    {
        int p = (int) readU32(d, pos);

        if (primitive[0] >= 0)
            triangulate(primitive[0], primVerts, triangles);

        primitive[0] = p & 3;
        primVerts.clear();
        return pos + 4;
    }

    /**
     * @param cmd a vertex command
     * @param d display-list bytes
     * @param pos cursor on the vertex parameters
     * @param vtx running vertex
     * @param uv running texcoord
     * @param primVerts vertices of the open primitive
     * @param positions emitted positions
     * @param texcoords emitted texcoords
     * @return the cursor after the vertex
     * @see "GBATEK DS 3D Video, VTX_16"
     */
    private static int readMeshVertex(GeometryCommand cmd, byte[] d, int pos, double[] vtx, double[] uv,
            List<Integer> primVerts, List<float[]> positions, List<float[]> texcoords)
    {
        switch (cmd)
        {
            case VTX_16:
                return readVtx16(d, pos, vtx, uv, primVerts, positions, texcoords);
            case VTX_10:
                return readVtx10(d, pos, vtx, false, uv, primVerts, positions, texcoords);
            case VTX_DIFF:
                return readVtx10(d, pos, vtx, true, uv, primVerts, positions, texcoords);
            case VTX_XY:
                return readVtxPair(d, pos, vtx, 0, 1, uv, primVerts, positions, texcoords);
            case VTX_XZ:
                return readVtxPair(d, pos, vtx, 0, 2, uv, primVerts, positions, texcoords);
            default:
                return readVtxPair(d, pos, vtx, 1, 2, uv, primVerts, positions, texcoords);
        }
    }

    /**
     * @param d display-list bytes
     * @param pos cursor on the two vertex words
     * @param vtx running vertex
     * @param uv running texcoord
     * @param primVerts vertices of the open primitive
     * @param positions emitted positions
     * @param texcoords emitted texcoords
     * @return the cursor after the vertex
     * @see "GBATEK DS 3D Video, VTX_16"
     */
    private static int readVtx16(byte[] d, int pos, double[] vtx, double[] uv, List<Integer> primVerts,
            List<float[]> positions, List<float[]> texcoords)
    {
        long p1 = readU32(d, pos);
        long p2 = readU32(d, pos + 4);

        vtx[0] = (short) (p1 & 0xFFFF) / 4096.0;
        vtx[1] = (short) ((p1 >> 16) & 0xFFFF) / 4096.0;
        vtx[2] = (short) (p2 & 0xFFFF) / 4096.0;

        emit(vtx, uv, positions, texcoords, primVerts);
        return pos + 8;
    }

    /**
     * @param d display-list bytes
     * @param pos cursor on the packed word
     * @param vtx running vertex
     * @param delta true for {@link GeometryCommand#VTX_DIFF}, which adds to the running vertex
     * @param uv running texcoord
     * @param primVerts vertices of the open primitive
     * @param positions emitted positions
     * @param texcoords emitted texcoords
     * @return the cursor after the word
     * @see "GBATEK DS 3D Video, VTX_10 and VTX_DIFF"
     */
    private static int readVtx10(byte[] d, int pos, double[] vtx, boolean delta, double[] uv, List<Integer> primVerts,
            List<float[]> positions, List<float[]> texcoords)
    {
        long p = readU32(d, pos);
        double k = delta ? 4096.0 : 64.0;
        double x = signed10((int) p) / k;
        double y = signed10((int) (p >> 10)) / k;
        double z = signed10((int) (p >> 20)) / k;

        if (delta)
        {
            vtx[0] += x;
            vtx[1] += y;
            vtx[2] += z;
        }
        else
        {
            vtx[0] = x;
            vtx[1] = y;
            vtx[2] = z;
        }

        emit(vtx, uv, positions, texcoords, primVerts);
        return pos + 4;
    }

    /**
     * @param d display-list bytes
     * @param pos cursor on the packed word
     * @param vtx running vertex
     * @param first component index written from the low half
     * @param second component index written from the high half
     * @param uv running texcoord
     * @param primVerts vertices of the open primitive
     * @param positions emitted positions
     * @param texcoords emitted texcoords
     * @return the cursor after the word
     * @see "GBATEK DS 3D Video, VTX_XY, VTX_XZ and VTX_YZ"
     */
    private static int readVtxPair(byte[] d, int pos, double[] vtx, int first, int second, double[] uv,
            List<Integer> primVerts, List<float[]> positions, List<float[]> texcoords)
    {
        long p = readU32(d, pos);

        vtx[first] = (short) (p & 0xFFFF) / 4096.0;
        vtx[second] = (short) ((p >> 16) & 0xFFFF) / 4096.0;

        emit(vtx, uv, positions, texcoords, primVerts);
        return pos + 4;
    }

    /**
     * @param op geometry opcode
     * @param pos cursor just after the parameters already consumed
     * @param meshName mesh that held the opcode
     * @return the rejection
     * @see "GBATEK DS 3D Video"
     */
    private static RuntimeException unknownCommand(int op, int pos, String meshName)
    {
        return new RuntimeException(String.format(
                "Unhandled display-list opcode 0x%02X at offset 0x%X in mesh %s", op, pos - 4, meshName));
    }

    private static void emit(double[] vtx, double[] uv, List<float[]> positions, List<float[]> texcoords, List<Integer> primVerts)
    {
        primVerts.add(positions.size());
        positions.add(new float[] { (float) vtx[0], (float) vtx[1], (float) vtx[2] });
        texcoords.add(new float[] { (float) uv[0], (float) uv[1] });
    }

    // Converts a primitive's ordered vertices into triangle index triples. 0=separate triangles,
    // 1=separate quads, 2=triangle strip, 3=quad strip.
    private static void triangulate(int primitive, List<Integer> v, List<Integer> out)
    {
        int n = v.size();
        switch (primitive)
        {
            case 0:
                for (int i = 0; i + 2 < n; i += 3)
                    addTri(out, v.get(i), v.get(i + 1), v.get(i + 2));
                break;
            case 1:
                for (int i = 0; i + 3 < n; i += 4)
                {
                    addTri(out, v.get(i), v.get(i + 1), v.get(i + 2));
                    addTri(out, v.get(i), v.get(i + 2), v.get(i + 3));
                }
                break;
            case 2:
                for (int i = 2; i < n; i++)
                    if ((i & 1) == 0)
                        addTri(out, v.get(i - 2), v.get(i - 1), v.get(i));
                    else
                        addTri(out, v.get(i - 1), v.get(i - 2), v.get(i));
                break;
            case 3:
                for (int i = 0; i + 3 < n; i += 2)
                {
                    addTri(out, v.get(i), v.get(i + 1), v.get(i + 3));
                    addTri(out, v.get(i), v.get(i + 3), v.get(i + 2));
                }
                break;
        }
    }

    private static void addTri(List<Integer> out, int a, int b, int c)
    {
        out.add(a);
        out.add(b);
        out.add(c);
    }

    /** @return this model's name (from the model dictionary) */
    public String getName()
    {
        return name;
    }

    /** @return the model's meshes, one per shape */
    public List<Mesh> getMeshes()
    {
        return meshes;
    }

    /** @return the model's materials (texture/palette bindings), in material-index order */
    public List<Material> getMaterials()
    {
        return materials;
    }

    /**
     * @param animation skeletal animation, or null for the bind pose
     * @param frame frame index
     * @param writeMeshes true to replace the cached mesh positions
     * @return the stack frame
     * @see "NitroSystem g3d/src/sbc.c NNS_G3dDraw"
     */
    private StackPose.Frame playStack(SkeletalAnimationSet.Animation animation, int frame, boolean writeMeshes)
    {
        double[][][] local = sampleLocals(animation, frame);

        byte[][] dls = new byte[meshes.size()][];
        float[][] raw = new float[meshes.size()][];
        for (int i = 0; i < meshes.size(); i++)
        {
            dls[i] = meshes.get(i).rawDisplayList;
            raw[i] = meshes.get(i).rawPositions;
        }

        StackPose.Frame played = StackPose.play(sbcBytes, maya, posScale, invPosScale, local[0], local[1], local[2],
                invBind, dls, raw);

        if (writeMeshes)
            writePosed(played);

        return played;
    }

    /**
     * @param animation skeletal animation, or null for the bind locals
     * @param frame frame index
     * @return {@code {translation, rotation, scale}} per node
     * @see "NitroSystem g3d/src/anm/nsbca.c getMdlScale_"
     */
    private double[][][] sampleLocals(SkeletalAnimationSet.Animation animation, int frame)
    {
        int n = nodeLocals.length;
        double[][] t = new double[n][];
        double[][] r = new double[n][];
        double[][] s = new double[n][];
        List<SkeletalAnimationSet.NodeAnim> animNodes = animation == null ? null : animation.getNodes();

        for (int i = 0; i < n; i++)
            copyLocal(i, frame, animNodes, t, r, s);

        return new double[][][] { t, r, s };
    }

    /**
     * @param i node index
     * @param frame frame index
     * @param animNodes animation nodes, or null
     * @param t translation out
     * @param r rotation out
     * @param s scale out
     * @see "NitroSystem g3d/src/anm/nsbca.c getMdlScale_"
     */
    private void copyLocal(int i, int frame, List<SkeletalAnimationSet.NodeAnim> animNodes,
            double[][] t, double[][] r, double[][] s)
    {
        Srt bind = nodeLocals[i];
        SkeletalAnimationSet.NodeAnim na = animNode(animNodes, i);

        t[i] = orBind(channel(na, frame, 0), bind.t);
        r[i] = orBind(channel(na, frame, 1), bind.r);
        s[i] = orBind(channel(na, frame, 2), bind.s);
    }

    /**
     * @param animNodes animation nodes, or null
     * @param i node index
     * @return that node's animation, or null
     * @see "NitroSystem g3d/src/anm/nsbca.c getMdlScale_"
     */
    private static SkeletalAnimationSet.NodeAnim animNode(List<SkeletalAnimationSet.NodeAnim> animNodes, int i)
    {
        if (animNodes == null || i >= animNodes.size())
            return null;

        return animNodes.get(i);
    }

    /**
     * @param na a node animation, or null
     * @param frame frame index
     * @param kind 0 translation, 1 rotation, 2 scale
     * @return the sampled channel, or null to keep the bind value
     * @see "NitroSystem g3d/src/anm/nsbca.c getMdlScale_"
     */
    private static double[] channel(SkeletalAnimationSet.NodeAnim na, int frame, int kind)
    {
        if (na == null)
            return null;

        return sample(na, frame, kind);
    }

    /**
     * @param na a node animation
     * @param frame frame index
     * @param kind 0 translation, 1 rotation, 2 scale
     * @return the sampled channel
     * @see "NitroSystem g3d/src/anm/nsbca.c getMdlScale_"
     */
    private static double[] sample(SkeletalAnimationSet.NodeAnim na, int frame, int kind)
    {
        if (kind == 0)
            return na.translationAt(frame);

        return later(na, frame, kind);
    }

    /**
     * @param na a node animation
     * @param frame frame index
     * @param kind 1 rotation or 2 scale
     * @return the sampled channel
     * @see "NitroSystem g3d/src/anm/nsbca.c getMdlScale_"
     */
    private static double[] later(SkeletalAnimationSet.NodeAnim na, int frame, int kind)
    {
        return kind == 1 ? na.rotationAt(frame) : na.scaleAt(frame);
    }

    /**
     * @param sampled an animated channel, or null
     * @param bind the bind-pose value
     * @return {@code sampled} when present, otherwise {@code bind}
     * @see "NitroSystem g3d/src/anm/nsbca.c getMdlScale_"
     */
    private static double[] orBind(double[] sampled, double[] bind)
    {
        return sampled != null ? sampled : bind;
    }

    /**
     * @param played stack frame whose positions replace the mesh cache
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_SHP"
     */
    private void writePosed(StackPose.Frame played)
    {
        for (int i = 0; i < meshes.size(); i++)
            if (played.positions[i] != null)
                meshes.get(i).positions = played.positions[i];
    }

    /**
     * @param played stack frame
     * @return one position array per mesh
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_SHP"
     */
    private List<float[]> stackList(StackPose.Frame played)
    {
        List<float[]> out = new ArrayList<>(meshes.size());
        for (int i = 0; i < meshes.size(); i++)
            out.add(orRaw(played.positions[i], i));

        return out;
    }

    /**
     * @param posed a posed mesh, or null when the shape was not drawn
     * @param mesh mesh index
     * @return {@code posed}, or a copy of the raw vertices
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_SHP"
     */
    private float[] orRaw(float[] posed, int mesh)
    {
        return posed != null ? posed : meshes.get(mesh).rawPositions.clone();
    }

    /**
     * @param model model reader
     * @param evp envelope-matrix offset
     * @param end first byte past the model
     * @param nodes node count
     * @param length model byte length
     * @return each node's inverse bind, 12 doubles, stride 84. Joints past the array stay identity.
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNSG3dResEvpMtx"
     */
    private static double[][] readInvBind(MemBuf.MemBufReader model, int evp, int end, int nodes, int length)
    {
        double[][] inv = new double[nodes][];
        int count = Math.max(0, (end - evp) / 84);

        for (int n = 0; n < nodes; n++)
            inv[n] = n < count && evp + (n + 1) * 84 <= length ? readM43(model, evp + n * 84) : NitroMtx.identity();

        return inv;
    }

    /**
     * @param model model reader
     * @param o matrix offset
     * @return twelve 1.19.12 values
     * @see "NitroSDK fx_mtx43.h MtxFx43"
     */
    private static double[] readM43(MemBuf.MemBufReader model, int o)
    {
        model.setPosition(o);

        double[] m = new double[12];
        for (int i = 0; i < 12; i++)
            m[i] = model.readInt() / 4096.0;

        return m;
    }

    /**
     * Poses this model by a skeletal animation and returns the vertex positions for each mesh at the
     * given frame &mdash; the same list order as {@link #getMeshes()}. Maya and envelope models are
     * placed by the SBC matrix stack; other models keep the separate scale/rotation composition.
     * The model's own meshes stay at their bind pose.
     * @param animation the animation to apply (its nodes correspond to this model's nodes, in order)
     * @param frame the frame index to sample
     * @return one {@code float[]} of {@code x,y,z} triples per mesh, in {@link #getMeshes()} order
     * @see "NitroSystem g3d/src/sbc.c NNS_G3dDraw"
     */
    public List<float[]> pose(SkeletalAnimationSet.Animation animation, int frame)
    {
        if (stackPose)
            return stackList(playStack(animation, frame, false));

        return rigidPose(animation, frame);
    }

    /**
     * @param animation skeletal animation
     * @param frame frame index
     * @return one position array per mesh, from the separate scale and rotation composition
     * @see "NitroSystem g3d/src/sbc.c NNS_G3dDraw"
     */
    private List<float[]> rigidPose(SkeletalAnimationSet.Animation animation, int frame)
    {
        Srt[] world = poseWorld(animation, frame);

        List<float[]> posed = new ArrayList<>(meshes.size());
        for (Mesh mesh : meshes)
            posed.add(place(mesh, world));

        return posed;
    }

    /**
     * @param mesh a shape
     * @param world posed world transforms
     * @return the shape's vertices placed by its node
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_SHP"
     */
    private float[] place(Mesh mesh, Srt[] world)
    {
        float[] out = mesh.rawPositions.clone();
        transformInPlace(out, worldOf(mesh, world), posScale);
        return out;
    }

    /**
     * @param mesh a shape
     * @param world posed world transforms
     * @return the node's world transform, or null
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_NODEDESC"
     */
    private static Srt worldOf(Mesh mesh, Srt[] world)
    {
        if (mesh.nodeIndex < 0 || mesh.nodeIndex >= world.length)
            return null;

        return world[mesh.nodeIndex];
    }

    // Composes each node's posed world transform for an animation frame (the shared core of pose() and the
    // posed billboard pivot): apply the animation's per-node SRT over the bind-pose local, then resolve the
    // hierarchy.
    private Srt[] poseWorld(SkeletalAnimationSet.Animation animation, int frame)
    {
        int count = nodeLocals.length;
        List<SkeletalAnimationSet.NodeAnim> animNodes = animation.getNodes();
        Srt[] local = new Srt[count];

        for (int n = 0; n < count; n++)
        {
            Srt bind = nodeLocals[n];
            SkeletalAnimationSet.NodeAnim na = n < animNodes.size() ? animNodes.get(n) : null;

            double[] t = na != null ? na.translationAt(frame) : null;
            double[] s = na != null ? na.scaleAt(frame) : null;
            double[] r = na != null ? na.rotationAt(frame) : null;

            local[n] = new Srt(t != null ? t : bind.t, s != null ? s : bind.s, r != null ? r : bind.r);
        }

        Srt[] world = new Srt[count];
        for (int n = 0; n < count; n++)
            world[n] = resolveWorld(n, nodeParent, local, world);

        return world;
    }

    /**
     * Composes each node's <em>posed</em> world translation for a skeletal-animation frame. This is the
     * billboard pivot a {@code BB}/{@code BBY} node should face-track around when the model is also being
     * skeletally posed &mdash; unlike {@link #getNodeWorldTranslation} (the bind-pose pivot), it follows the
     * animation. Parallel to the model's nodes.
     * @param animation the skeletal animation
     * @param frame the frame index
     * @return each node's posed world translation (x,y,z)
     */
    public double[][] poseNodeWorldTranslations(SkeletalAnimationSet.Animation animation, int frame)
    {
        Srt[] world = poseWorld(animation, frame);

        double[][] out = new double[world.length][];
        for (int n = 0; n < world.length; n++)
        {
            Srt w = world[n] != null ? world[n] : Srt.identity();
            out[n] = w.t.clone();
        }

        return out;
    }

    /**
     * @return the number of vertices the model header declares (equals the number the display-list
     *         interpreter emits when it decodes correctly)
     */
    public int getExpectedVertexCount()
    {
        return expectedVertexCount;
    }

    /** @return the total number of vertices decoded across all meshes */
    public int getVertexCount()
    {
        int total = 0;
        for (Mesh m : meshes)
            total += m.positions.length / 3;

        return total;
    }

    /**
     * Exports this model's geometry to Wavefront OBJ text &mdash; a universally supported,
     * zero-dependency interchange format. Each mesh becomes an OBJ group with its vertices and triangle
     * faces. Texture coordinates are decoded (see {@link Mesh#getTexcoords()}) but not written yet:
     * without materials/textures they carry no usable mapping, so they are omitted rather than emitted
     * as misleading data. They will be added with {@code usemtl}/{@code mtllib} when texturing lands.
     * @return the OBJ document as a <code>String</code>
     */
    public String toObj()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(name).append(" exported by Nds4j\n");
        int vBase = 1;

        for (Mesh mesh : meshes)
        {
            sb.append("g ").append(mesh.name).append('\n');
            int vertexCount = mesh.positions.length / 3;

            for (int i = 0; i < vertexCount; i++)
                sb.append("v ").append(mesh.positions[i * 3]).append(' ')
                        .append(mesh.positions[i * 3 + 1]).append(' ').append(mesh.positions[i * 3 + 2]).append('\n');

            for (int i = 0; i + 2 < mesh.triangleIndices.length; i += 3)
                sb.append("f ").append(vBase + mesh.triangleIndices[i]).append(' ')
                        .append(vBase + mesh.triangleIndices[i + 1]).append(' ')
                        .append(vBase + mesh.triangleIndices[i + 2]).append('\n');

            vBase += vertexCount;
        }

        return sb.toString();
    }

    /** @return the number of nodes (bones) this model declares */
    public int getNodeCount()
    {
        return nodeCount;
    }

    /**
     * @return the model's global position scale (a magnify factor applied to every decoded vertex). The
     *         decoded {@link Mesh#getPositions()} already include it; {@link Mesh#getRawPositions()} do
     *         not.
     */
    public double getPositionScale()
    {
        return posScale;
    }

    /**
     * @param node a node index
     * @return the index of that node's parent in the skeleton, or -1 for a root node
     */
    public int getNodeParent(int node)
    {
        return (nodeParent != null && node >= 0 && node < nodeParent.length) ? nodeParent[node] : -1;
    }

    /**
     * Computes each node's <em>local</em> transform (translation, per-axis scale, 3&times;3 rotation),
     * either at the bind pose or as re-posed by a skeletal animation &mdash; the building block a
     * hierarchical exporter (e.g. {@link GltfExporter}) needs to emit an animated node tree. Unlike
     * {@link #pose}, these are the per-node <em>locals</em> (not composed world matrices, and not applied
     * to geometry), so a consumer can hand them to a scene graph that composes the hierarchy itself.
     * @param animation the animation to sample, or null for the bind pose
     * @param frame the frame to sample (ignored when {@code animation} is null)
     * @return one {@link NodeTransform} per node, in node order
     */
    public NodeTransform[] localTransforms(SkeletalAnimationSet.Animation animation, int frame)
    {
        int count = nodeLocals.length;
        List<SkeletalAnimationSet.NodeAnim> animNodes = animation != null ? animation.getNodes() : null;
        NodeTransform[] out = new NodeTransform[count];

        for (int n = 0; n < count; n++)
        {
            Srt bind = nodeLocals[n];
            double[] t = bind.t, s = bind.s, r = bind.r;

            if (animNodes != null)
            {
                SkeletalAnimationSet.NodeAnim na = n < animNodes.size() ? animNodes.get(n) : null;

                double[] at = na != null ? na.translationAt(frame) : null;
                double[] as = na != null ? na.scaleAt(frame) : null;
                double[] ar = na != null ? na.rotationAt(frame) : null;

                if (at != null)
                    t = at;
                if (as != null)
                    s = as;
                if (ar != null)
                    r = ar;
            }

            out[n] = new NodeTransform(t.clone(), s.clone(), r.clone());
        }

        return out;
    }

    /**
     * A node's local transform as separate translation, per-axis scale and a 3&times;3 rotation
     * (row-major, NNS row-vector convention {@code v' = v * r}). The scale is kept apart from the
     * rotation deliberately (see the class docs); a consumer that composes it into a single matrix per
     * the standard {@code T*R*S} rule will shear non-uniformly-scaled children exactly as a generic
     * scene graph does &mdash; the reference NNS behaviour is reproduced faithfully only by
     * {@link SoftwareRenderer}.
     */
    public static final class NodeTransform
    {
        private final double[] translation;
        private final double[] scale;
        private final double[] rotation;

        NodeTransform(double[] translation, double[] scale, double[] rotation)
        {
            this.translation = translation;
            this.scale = scale;
            this.rotation = rotation;
        }

        /** @return translation (x,y,z), model units (not scaled by the model's position scale) */
        public double[] getTranslation()
        {
            return translation;
        }

        /** @return per-axis scale (x,y,z) */
        public double[] getScale()
        {
            return scale;
        }

        /** @return the 3&times;3 rotation, row-major, row-vector convention */
        public double[] getRotation()
        {
            return rotation;
        }
    }

    /**
     * @return true if this model has a single (identity) node, the case where the decoded geometry is
     *         already positionally correct (multi-node placement needs node transforms, still to come)
     */
    public boolean isSingleNode()
    {
        return nodeCount == 1;
    }

    /**
     * @return true if this model draws with billboard (camera-facing) or skinning (blended) nodes, whose
     *         final on-screen pose depends on the camera or a vertex blend. A static bind-pose decode
     *         cannot reproduce that pose, so such a model's decoded bounding box need not match the
     *         header box (it is excluded from the placement self-check).
     */
    public boolean hasDynamicPose()
    {
        return dynamicPose;
    }

    /**
     * @return true if any of this model's display lists contains an {@code MTX_SCALE} command.
     *         Stack-posed models apply it to the position matrix. Other models only consume the operand
     *         words, because that scale repeats the header {@code posScale}.
     * @see "GBATEK DS 3D Video, MTX_SCALE"
     */
    public boolean usesMtxScale()
    {
        return usesMtxScale;
    }

    /**
     * @param node a node index
     * @return true if that node is drawn as a billboard (BB/BBY) &mdash; camera-facing at runtime. A
     *         static decode leaves it at its authored orientation.
     */
    public boolean isBillboardNode(int node)
    {
        return billboardNode != null && node >= 0 && node < billboardNode.length && billboardNode[node];
    }

    /**
     * @param node a node index
     * @return true when SBC {@code BBY} draws that node. {@link #isBillboardNode(int)} is also true.
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_BBY"
     */
    public boolean isYaxisBillboard(int node)
    {
        return marked(yBillboard, node);
    }

    /**
     * @param flags per-node flags, or null
     * @param node a node index
     * @return whether that flag is set
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_BBY"
     */
    private static boolean marked(boolean[] flags, int node)
    {
        if (flags == null)
            return false;

        return bit(flags, node);
    }

    /**
     * @param flags per-node flags
     * @param node a node index
     * @return whether that flag is set
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_BBY"
     */
    private static boolean bit(boolean[] flags, int node)
    {
        if (outside(node, flags.length))
            return false;

        return flags[node];
    }

    /**
     * @param node a node index
     * @param length flag array length
     * @return true when {@code node} is outside the array
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_BBY"
     */
    private static boolean outside(int node, int length)
    {
        return node < 0 || node >= length;
    }

    /**
     * @return true when bind pose and {@link #pose} use the SBC matrix stack
     * @see "NitroSystem g3d/src/sbc.c NNS_G3dDraw"
     */
    public boolean usesStackPose()
    {
        return stackPose;
    }

    /**
     * @return the bind-pose stack frame, or null when this model does not use the stack
     * @see "NitroSystem g3d/src/sbc.c NNS_G3dDraw"
     */
    StackPose.Frame bindSkin()
    {
        return bindFrame;
    }

    /**
     * @param animation skeletal animation, or null for the bind pose
     * @param frame frame index
     * @return the stack frame for that sample
     * @see "NitroSystem g3d/src/sbc.c NNS_G3dDraw"
     */
    StackPose.Frame poseFrame(SkeletalAnimationSet.Animation animation, int frame)
    {
        return playStack(animation, frame, false);
    }

    /**
     * @param node a node index
     * @return the node's bind-pose world translation (x,y,z) &mdash; the pivot a billboard's camera-facing
     *         geometry is placed at &mdash; or {@code {0,0,0}} if unknown
     */
    public double[] getNodeWorldTranslation(int node)
    {
        return (bindWorldTranslation != null && node >= 0 && node < bindWorldTranslation.length)
                ? bindWorldTranslation[node].clone()
                : new double[] { 0, 0, 0 };
    }

    /**
     * @param node a node index
     * @return the node's bind-pose world (accumulated) scale (x,y,z), or {@code {1,1,1}} if unknown
     */
    public double[] getNodeWorldScale(int node)
    {
        return (bindWorldScale != null && node >= 0 && node < bindWorldScale.length)
                ? bindWorldScale[node].clone()
                : new double[] { 1, 1, 1 };
    }

    /**
     * Gets the model's axis-aligned bounding box as declared in its header: a 2x3 array
     * {@code {{minX,minY,minZ},{maxX,maxY,maxZ}}} in model space.
     * @return a <code>float[2][3]</code>
     */
    public float[][] getHeaderBoundingBox()
    {
        return new float[][] { headerBoxMin.clone(), headerBoxMax.clone() };
    }

    /**
     * Computes the axis-aligned bounding box of the decoded geometry, in model space. For a correctly
     * placed model this matches {@link #getHeaderBoundingBox()}.
     * @return a <code>float[2][3]</code> {@code {{minX,minY,minZ},{maxX,maxY,maxZ}}}, or a zero box if
     *         there are no vertices
     */
    public float[][] getDecodedBoundingBox()
    {
        float[] min = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY };
        float[] max = { Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
        boolean any = false;

        for (Mesh mesh : meshes)
        {
            for (int i = 0; i < mesh.positions.length; i += 3)
            {
                any = true;

                for (int c = 0; c < 3; c++)
                {
                    min[c] = Math.min(min[c], mesh.positions[i + c]);
                    max[c] = Math.max(max[c], mesh.positions[i + c]);
                }
            }
        }

        if (!any)
            return new float[][] { { 0, 0, 0 }, { 0, 0, 0 } };

        return new float[][] { min, max };
    }

    @Override
    public String toString()
    {
        return String.format("Model[%s, %d meshes, %d vertices]", name, meshes.size(), getVertexCount());
    }

    private static int signed10(int v)
    {
        v &= 0x3FF;
        return v >= 0x200 ? v - 0x400 : v;
    }

    private static int readU16(byte[] d, int o)
    {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    private static long readU32(byte[] d, int o)
    {
        return (d[o] & 0xFFL) | ((d[o + 1] & 0xFFL) << 8) | ((d[o + 2] & 0xFFL) << 16) | ((d[o + 3] & 0xFFL) << 24);
    }

    private static double readFx16(byte[] d, int o)
    {
        return (short) readU16(d, o) / 4096.0;
    }

    private static double readFx32(byte[] d, int o)
    {
        return (int) readU32(d, o) / 4096.0;
    }

    /** A single drawable mesh: interleaved-free position and texcoord arrays plus triangle indices. */
    public static class Mesh
    {
        private final String name;
        private float[] positions; // 3 floats per vertex (model space); replaced by a stack pose
        private final float[] texcoords; // 2 floats per vertex, in texel units (divide by the bound
                                         // texture's size to normalise)
        private final int[] triangleIndices; // 3 indices per triangle
        private Material material; // the bound material (texture/palette), or null
        float[] rawPositions; // pre-placement vertices (for re-posing by an animation)
        int nodeIndex; // the skeleton node this mesh is drawn under
        byte[] rawDisplayList; // the shape's verbatim GPU command stream (for byte-exact re-emit)

        Mesh(String name, float[] positions, float[] texcoords, int[] triangleIndices)
        {
            this.name = name;
            this.positions = positions;
            this.texcoords = texcoords;
            this.triangleIndices = triangleIndices;
        }

        /** @return this mesh's name (its shape name) */
        public String getName()
        {
            return name;
        }

        /** @return the vertex positions, 3 floats (x,y,z) per vertex, in model space */
        public float[] getPositions()
        {
            return positions;
        }

        /**
         * @return the raw pre-placement vertex positions, 3 floats (x,y,z) per vertex, in the mesh's own
         *         node-local space &mdash; before the node world transform and before the model's
         *         position scale. This is what a hierarchical exporter places under the mesh's skeleton
         *         node (see {@link #getNodeIndex()}).
         */
        public float[] getRawPositions()
        {
            return rawPositions;
        }

        /** @return the texture coordinates, 2 floats (s,t) per vertex, in texel units */
        public float[] getTexcoords()
        {
            return texcoords;
        }

        /** @return the triangle indices, 3 per triangle, into the vertex arrays */
        public int[] getTriangleIndices()
        {
            return triangleIndices;
        }

        /**
         * @return this shape's verbatim GPU command stream (the raw display-list bytes it was decoded from).
         *         Feeding it to {@link DisplayList#decodeCommands} yields the exact commands (primitive type,
         *         vertex formats, strips) that a triangle view loses, so geometry can be re-emitted
         *         byte-for-byte. The array is a copy; mutating it does not affect the model.
         */
        public byte[] getRawDisplayList()
        {
            return rawDisplayList != null ? rawDisplayList.clone() : null;
        }

        /** @return the number of vertices */
        public int getVertexCount()
        {
            return positions.length / 3;
        }

        /** @return the number of triangles */
        public int getTriangleCount()
        {
            return triangleIndices.length / 3;
        }

        /** @return the material bound to this mesh, or null if it draws untextured */
        public Material getMaterial()
        {
            return material;
        }

        /** @return the skeleton node (bone) index this mesh is drawn under (for visibility animation) */
        public int getNodeIndex()
        {
            return nodeIndex;
        }
    }

    /**
     * A material bound to one or more shapes: the names of the texture and palette it samples (looked up
     * in the model's embedded or a sibling {@code TEX0}) plus the texture's texel size and wrap/flip
     * flags. Color/lighting/polygon attributes are present in the file but not exposed until they are
     * needed. Names are {@code null} when the material draws untextured.
     */
    public static class Material
    {
        private final String name;
        private final boolean repeatS, repeatT, flipS, flipT;
        private String textureName;
        private String paletteName;

        Material(String name, boolean repeatS, boolean repeatT, boolean flipS, boolean flipT)
        {
            this.name = name;
            this.repeatS = repeatS;
            this.repeatT = repeatT;
            this.flipS = flipS;
            this.flipT = flipT;
        }

        /** @return this material's name */
        public String getName()
        {
            return name;
        }

        /** @return the name of the texture this material samples, or null */
        public String getTextureName()
        {
            return textureName;
        }

        /** @return the name of the palette this material samples, or null */
        public String getPaletteName()
        {
            return paletteName;
        }

        /** @return whether the texture repeats (tiles) along S; else it clamps */
        public boolean isRepeatS()
        {
            return repeatS;
        }

        /** @return whether the texture repeats (tiles) along T; else it clamps */
        public boolean isRepeatT()
        {
            return repeatT;
        }

        /** @return whether the texture mirrors on every other S tile */
        public boolean isFlipS()
        {
            return flipS;
        }

        /** @return whether the texture mirrors on every other T tile */
        public boolean isFlipT()
        {
            return flipT;
        }
    }
}
