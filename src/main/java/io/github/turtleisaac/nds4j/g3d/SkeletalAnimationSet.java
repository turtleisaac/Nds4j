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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An object representation of an NSBCA file (a Nitro 3D <b>skeletal (joint) animation</b> set, magic
 * {@code BCA0}).
 * <p>
 * An NSBCA holds one {@code JNT0} block naming one or more {@link Animation}s. Each animation is a set
 * of per-{@link NodeAnim node} scale/rotation/translation (SRT) tracks that pose a {@link Model}'s
 * bind-pose skeleton over time: for a given frame each node yields an SRT that replaces (or, for
 * "base" tracks, keeps) its bind-pose local transform, and the model's node hierarchy is composed and
 * applied exactly as at bind pose &mdash; see {@link Model#pose(Animation, int)}.
 * <p>
 * The container round-trips byte-for-byte (the {@code JNT0} block is preserved verbatim by
 * {@link G3dFile}); the tracks are parsed as a read-only view over it. Tracks are stored compactly
 * (constant tracks inline; variable tracks as keyframe arrays sampled every 1/2/4 frames with linear
 * interpolation, rotations as pivot- or 5-value-compressed 3&times;3 matrices in shared pools). Layout
 * reverse-engineered from the reference {@code nitroreader.nsbca.*} decoder.
 * <p>
 * <b>Writer:</b> {@link #encode()} re-emits the file byte-for-byte from the parsed structure (verified over
 * every retail NSBCA), the fifth and last of the animation writers. It disassembles each animation into its
 * per-node blocks, the two shared rotation-matrix pools (pivot 6-byte and 5-value 10-byte entries, indexed by
 * {@code u16}, kept verbatim since they are index- not offset-referenced) and the keyframe arrays, then relays
 * them out: node blocks (offset fields repointed) &middot; rot3 pool &middot; rot5 pool &middot; keyframe arrays
 * grouped by section (rotation, translation, scale), each group 4-byte-aligned and its members element-aligned.
 */
public class SkeletalAnimationSet extends G3dFile
{
    private final List<Animation> animations = new ArrayList<>();

    /**
     * Generates an object representation of an NSBCA file.
     * @param data a <code>byte[]</code> representation of an NSBCA file
     */
    public SkeletalAnimationSet(byte[] data)
    {
        super("BCA0");
        readContainer(data);
        int jnt0 = indexOfBlock("JNT0");
        if (jnt0 < 0)
            throw new RuntimeException("Not a valid BCA0 file: missing JNT0 block.");
        parseJnt0(block(jnt0));
    }

    private void parseJnt0(byte[] d)
    {
        MemBuf buf = MemBuf.create(d);
        MemBuf.MemBufReader reader = buf.reader();
        reader.setPosition(8); // JNT0 magic + block size, then the animation dictionary
        G3dDictionary dict = new G3dDictionary(reader);
        int[] starts = new int[dict.size()];
        for (int i = 0; i < dict.size(); i++) starts[i] = (int) readU32(dict.getRecord(i), 0); // relative to JNT0
        for (int i = 0; i < dict.size(); i++)
        {
            int end = (i + 1 < dict.size()) ? starts[i + 1] : d.length;
            Animation a = new Animation(d, starts[i], dict.getName(i));
            a.payload = Arrays.copyOfRange(d, starts[i], end);   // retained for the byte-exact writer
            animations.add(a);
        }
    }

    /**
     * Re-emits this set's bytes from its parsed structure (the byte-exact writer path, verified to reproduce
     * every retail NSBCA). Distinct from the block-verbatim {@link G3dFile#save()}: it disassembles each
     * animation into its node blocks, the two shared rotation pools and the keyframe arrays, then relays them
     * out with recomputed offsets — the node blocks (offset fields repointed), then the rot3/rot5 pools
     * (index-referenced, so verbatim), then the keyframe arrays grouped by section (rotation, translation,
     * scale) with each group 4-byte-aligned and its members element-aligned within.
     * @return the NSBCA file bytes
     */
    public byte[] encode()
    {
        List<String> names = new ArrayList<>();
        for (Animation a : animations) names.add(a.name);
        int dictSize = serialize(G3dDictionary.build(names, placeholders(animations.size()), 4)).length;
        byte[][] blobs = new byte[animations.size()][];
        for (int i = 0; i < animations.size(); i++) blobs[i] = reassemble(animations.get(i).payload);

        List<byte[]> recs = new ArrayList<>();
        int cursor = 8 + dictSize;
        for (byte[] blob : blobs) { recs.add(rec4(cursor)); cursor += blob.length; }

        ByteArrayOutputStream jnt0 = new ByteArrayOutputStream();
        jnt0.writeBytes("JNT0".getBytes(StandardCharsets.US_ASCII));
        jnt0.writeBytes(rec4(cursor));
        jnt0.writeBytes(serialize(G3dDictionary.build(names, recs, 4)));
        for (byte[] blob : blobs) jnt0.writeBytes(blob);
        return G3dFile.assembleContainer("BCA0", version, jnt0.toByteArray());
    }

    // --- the byte-exact NSBCA payload writer (relays a decoded animation, offsets recomputed) ---

    private static final int I_ID = 0x1, I_TID = 0x2, I_TB = 0x4, I_TXC = 0x8, I_TYC = 0x10, I_TZC = 0x20,
            I_RID = 0x40, I_RB = 0x80, I_RC = 0x100, I_SID = 0x200, I_SB = 0x400, I_SXC = 0x800, I_SYC = 0x1000, I_SZC = 0x2000;

    // Parse a node block, appending each variable track's offset-field position (block-relative) and referenced
    // array to `fields`/`arrays`. Array record = {len, kind (0=R,1=T,2=S), elementSize}. Returns the end position.
    private static int parseNodeRaw(byte[] p0, int p, int nf, List<int[]> fields, Map<Integer, int[]> arrays, int start)
    {
        int info = (int) readU32(p0, p); p += 4;
        if ((info & I_ID) != 0) return p;
        if ((info & I_TID) == 0 && (info & I_TB) == 0)
        {
            int[] cb = {I_TXC, I_TYC, I_TZC};
            for (int k = 0; k < 3; k++)
                if ((info & cb[k]) != 0) p += 4;
                else { int ti = (int) readU32(p0, p), off = (int) readU32(p0, p + 4), el = (ti & TINFO_FX16) != 0 ? 2 : 4;
                    fields.add(new int[]{p + 4 - start, off}); arrays.put(off, new int[]{valueCount(nf, frameStep(ti)) * el, 1, el}); p += 8; }
        }
        if ((info & I_RID) == 0 && (info & I_RB) == 0)
        {
            if ((info & I_RC) != 0) p += 4;
            else { int ti = (int) readU32(p0, p), off = (int) readU32(p0, p + 4);
                fields.add(new int[]{p + 4 - start, off}); arrays.put(off, new int[]{valueCount(nf, frameStep(ti)) * 2, 0, 2}); p += 8; }
        }
        if ((info & I_SID) == 0 && (info & I_SB) == 0)
        {
            int[] cb = {I_SXC, I_SYC, I_SZC};
            for (int k = 0; k < 3; k++)
                if ((info & cb[k]) != 0) p += 8;
                else { int ti = (int) readU32(p0, p), off = (int) readU32(p0, p + 4), el = (ti & TINFO_FX16) != 0 ? 2 : 4;
                    fields.add(new int[]{p + 4 - start, off}); arrays.put(off, new int[]{valueCount(nf, frameStep(ti)) * el * 2, 2, el}); p += 8; }
        }
        return p;
    }

    private static byte[] reassemble(byte[] a)
    {
        int nf = readU16(a, 4), nn = readU16(a, 6);
        int r3 = (int) readU32(a, 12), r5 = (int) readU32(a, 16);
        int[] nodeOfs = new int[nn];
        for (int n = 0; n < nn; n++) nodeOfs[n] = readU16(a, 20 + n * 2);

        byte[][] nodeRaw = new byte[nn][];
        List<List<int[]>> nodeFields = new ArrayList<>();
        Map<Integer, int[]> arrays = new TreeMap<>();          // origOffset -> {len, kind, elementSize}
        for (int n = 0; n < nn; n++)
        {
            List<int[]> f = new ArrayList<>();
            parseNodeRaw(a, nodeOfs[n], nf, f, arrays, nodeOfs[n]);
            int end = (n + 1 < nn) ? nodeOfs[n + 1] : r3;       // exact block extent (captures any per-block padding)
            nodeRaw[n] = Arrays.copyOfRange(a, nodeOfs[n], end);
            nodeFields.add(f);
        }
        int firstArray = a.length;
        for (int off : arrays.keySet()) firstArray = Math.min(firstArray, off);
        byte[] rot3 = Arrays.copyOfRange(a, r3, r5);
        byte[] rot5 = Arrays.copyOfRange(a, r5, arrays.isEmpty() ? a.length : firstArray);

        int cur = (20 + nn * 2 + 3) & ~3;                       // header + node table, padded to 4
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int[] newNodeOfs = new int[nn];
        for (int n = 0; n < nn; n++) { newNodeOfs[n] = cur + body.size(); body.writeBytes(nodeRaw[n]); }
        int newR3 = cur + body.size(); body.writeBytes(rot3);
        int newR5 = cur + body.size(); body.writeBytes(rot5);
        Map<Integer, Integer> newArrOff = new HashMap<>();
        int prevKind = -1;
        for (Map.Entry<Integer, int[]> e : arrays.entrySet())
        {
            int kind = e.getValue()[1], el = e.getValue()[2];
            int align = (kind != prevKind) ? 4 : el;
            while ((cur + body.size()) % align != 0) body.write(0);
            newArrOff.put(e.getKey(), cur + body.size());
            body.writeBytes(Arrays.copyOfRange(a, e.getKey(), e.getKey() + e.getValue()[0]));
            prevKind = kind;
        }
        while ((cur + body.size()) % 4 != 0) body.write(0);

        byte[] out = new byte[cur + body.size()];
        System.arraycopy(a, 0, out, 0, 20);                     // tag, numFrames, numNodes, flag, pool-offset fields
        writeU32(out, 12, newR3); writeU32(out, 16, newR5);
        for (int n = 0; n < nn; n++)
        {
            byte[] nb = nodeRaw[n].clone();
            for (int[] fld : nodeFields.get(n)) writeU32(nb, fld[0], newArrOff.get(fld[1]));
            System.arraycopy(nb, 0, out, newNodeOfs[n], nb.length);
            writeU16(out, 20 + n * 2, newNodeOfs[n]);
        }
        System.arraycopy(rot3, 0, out, newR3, rot3.length);
        System.arraycopy(rot5, 0, out, newR5, rot5.length);
        for (Map.Entry<Integer, int[]> e : arrays.entrySet())
            System.arraycopy(a, e.getKey(), out, newArrOff.get(e.getKey()), e.getValue()[0]);
        return out;
    }

    private static List<byte[]> placeholders(int n)
    {
        List<byte[]> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(rec4(0));
        return l;
    }
    private static byte[] serialize(G3dDictionary d) { MemBuf b = MemBuf.create(); d.write(b.writer()); return b.reader().getBuffer(); }
    private static byte[] rec4(long v) { byte[] r = new byte[4]; writeU32(r, 0, v); return r; }
    private static void writeU16(byte[] d, int o, int v) { d[o] = (byte) v; d[o + 1] = (byte) (v >> 8); }
    private static void writeU32(byte[] d, int o, long v) { for (int i = 0; i < 4; i++) d[o + i] = (byte) (v >> (8 * i)); }

    /**
     * Gets the animations in this file.
     * @return a <code>List</code> of {@link Animation}
     */
    public List<Animation> getAnimations()
    {
        return animations;
    }

    // save(), equals(), hashCode() are inherited from G3dFile (block-level, byte-exact).

    @Override
    public String toString()
    {
        return String.format("SkeletalAnimationSet[%d animations]", animations.size());
    }

    /**
     * A single named skeletal animation ({@code NNS_G3dResJntAnm}): a frame count and one
     * {@link NodeAnim} per skeleton node, in node order.
     */
    public static class Animation
    {
        private final String name;
        private final int frameCount;
        private final List<NodeAnim> nodes = new ArrayList<>();
        byte[] payload;   // the raw animation block bytes, retained for the byte-exact writer

        Animation(byte[] d, int animStart, String name)
        {
            this.name = name;
            // header: char[4] tag, u16 numFrames, u16 numNodes, u32 flag, u32 ofsRot3Pool, u32 ofsRot5Pool
            frameCount = readU16(d, animStart + 4);
            int numNodes = readU16(d, animStart + 6);
            int rot3Pool = (int) readU32(d, animStart + 12); // pivot (6-byte) rotation pool, relative to animStart
            int rot5Pool = (int) readU32(d, animStart + 16); // 5-value (10-byte) rotation pool, relative to animStart
            for (int n = 0; n < numNodes; n++)
            {
                int nodeOfs = readU16(d, animStart + 20 + n * 2); // relative to animStart
                nodes.add(new NodeAnim(d, animStart, animStart + nodeOfs, rot3Pool, rot5Pool, frameCount));
            }
        }

        /** @return this animation's name */
        public String getName() { return name; }
        /** @return the number of frames this animation runs */
        public int getFrameCount() { return frameCount; }
        /** @return the per-node tracks, one per skeleton node in node order */
        public List<NodeAnim> getNodes() { return nodes; }

        @Override
        public String toString()
        {
            return String.format("Animation[%s, %d frames, %d nodes]", name, frameCount, nodes.size());
        }
    }

    /**
     * The animation tracks for one skeleton node: independent scale, rotation and translation, each of
     * which is <em>identity</em> (forced to the neutral value), <em>base</em> (keep the model's
     * bind-pose value), <em>constant</em>, or <em>variable</em> (keyframed). Sampling a track at a frame
     * yields the value to place on that node before composing the skeleton.
     */
    public static class NodeAnim
    {
        // NNS_G3D_JNTANM_SRTINFO flag bits (whole-node and per-section modes, per-axis "constant" bits).
        private static final int INFO_IDENTITY = 0x1;
        private static final int INFO_T_IDENTITY = 0x2, INFO_T_BASE = 0x4;
        private static final int INFO_TX_CONST = 0x8, INFO_TY_CONST = 0x10, INFO_TZ_CONST = 0x20;
        private static final int INFO_R_IDENTITY = 0x40, INFO_R_BASE = 0x80, INFO_R_CONST = 0x100;
        private static final int INFO_S_IDENTITY = 0x200, INFO_S_BASE = 0x400;
        private static final int INFO_SX_CONST = 0x800, INFO_SY_CONST = 0x1000, INFO_SZ_CONST = 0x2000;

        // Per-section mode: forced identity, keep bind pose, or "present" (constant/variable per track).
        private enum Mode { IDENTITY, BASE, PRESENT }

        private final int frameCount;
        private Mode transMode = Mode.BASE, rotMode = Mode.BASE, scaleMode = Mode.BASE;
        private Track tx, ty, tz, sx, sy, sz;   // when transMode/scaleMode == PRESENT
        private RotTrack rot;                    // when rotMode == PRESENT

        NodeAnim(byte[] d, int animStart, int p, int rot3Pool, int rot5Pool, int frameCount)
        {
            this.frameCount = frameCount;
            int info = (int) readU32(d, p);
            p += 4;
            if ((info & INFO_IDENTITY) != 0)
            {
                transMode = rotMode = scaleMode = Mode.IDENTITY;
                return;
            }

            // Translation: identity / base / (tx,ty,tz each constant or variable, read inline in order).
            if ((info & INFO_T_IDENTITY) != 0) transMode = Mode.IDENTITY;
            else if ((info & INFO_T_BASE) != 0) transMode = Mode.BASE;
            else
            {
                transMode = Mode.PRESENT;
                Track[] r = new Track[3];
                int[] constBits = {INFO_TX_CONST, INFO_TY_CONST, INFO_TZ_CONST};
                for (int k = 0; k < 3; k++)
                {
                    if ((info & constBits[k]) != 0) { r[k] = Track.constant(readFx32(d, p)); p += 4; }
                    else { r[k] = Track.variable((int) readU32(d, p), animStart + (int) readU32(d, p + 4)); p += 8; }
                }
                tx = r[0]; ty = r[1]; tz = r[2];
            }

            // Rotation: identity / base / constant / variable.
            if ((info & INFO_R_IDENTITY) != 0) rotMode = Mode.IDENTITY;
            else if ((info & INFO_R_BASE) != 0) rotMode = Mode.BASE;
            else
            {
                rotMode = Mode.PRESENT;
                if ((info & INFO_R_CONST) != 0) { rot = RotTrack.constant((int) readU32(d, p)); p += 4; }
                else { rot = RotTrack.variable((int) readU32(d, p), animStart + (int) readU32(d, p + 4)); p += 8; }
                rot.bind(d, animStart, rot3Pool, rot5Pool, frameCount);
            }

            // Scale: identity / base / (sx,sy,sz each constant or variable; each stores value+inverse).
            if ((info & INFO_S_IDENTITY) != 0) scaleMode = Mode.IDENTITY;
            else if ((info & INFO_S_BASE) != 0) scaleMode = Mode.BASE;
            else
            {
                scaleMode = Mode.PRESENT;
                Track[] r = new Track[3];
                int[] constBits = {INFO_SX_CONST, INFO_SY_CONST, INFO_SZ_CONST};
                for (int k = 0; k < 3; k++)
                {
                    if ((info & constBits[k]) != 0) { r[k] = Track.constant(readFx32(d, p)); p += 8; } // value + unused inverse
                    else { r[k] = Track.scaleVariable((int) readU32(d, p), animStart + (int) readU32(d, p + 4)); p += 8; }
                }
                sx = r[0]; sy = r[1]; sz = r[2];
                // variable scale tracks need the block to read their keyframes
                for (Track t : r) t.bind(d, frameCount);
            }
            for (Track t : new Track[]{tx, ty, tz})
                if (t != null) t.bind(d, frameCount);
        }

        /**
         * Samples this node's translation at {@code frame}.
         * @param frame the frame index
         * @return {@code {x,y,z}}, {@code {0,0,0}} if the track is identity, or {@code null} if it is a
         *         base track (the caller should keep the bind-pose translation)
         */
        public double[] translationAt(int frame)
        {
            if (transMode == Mode.BASE) return null;
            if (transMode == Mode.IDENTITY) return new double[]{0, 0, 0};
            return new double[]{tx.sample(frame), ty.sample(frame), tz.sample(frame)};
        }

        /**
         * Samples this node's scale at {@code frame}.
         * @param frame the frame index
         * @return {@code {x,y,z}}, {@code {1,1,1}} if identity, or {@code null} for a base track
         */
        public double[] scaleAt(int frame)
        {
            if (scaleMode == Mode.BASE) return null;
            if (scaleMode == Mode.IDENTITY) return new double[]{1, 1, 1};
            return new double[]{sx.sample(frame), sy.sample(frame), sz.sample(frame)};
        }

        /**
         * Samples this node's rotation at {@code frame}.
         * @param frame the frame index
         * @return a row-major 3&times;3 matrix (9 doubles), the identity if the track is identity, or
         *         {@code null} for a base track
         */
        public double[] rotationAt(int frame)
        {
            if (rotMode == Mode.BASE) return null;
            if (rotMode == Mode.IDENTITY) return new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
            return rot.sample(frame);
        }
    }

    // A scalar (translation/scale) track: constant, or a keyframe array sampled every `step` frames.
    private static final class Track
    {
        private final float constValue;
        private final boolean isConst;
        private final int info;       // variable-track info word (step + fx16 flag)
        private final int dataOffset; // variable-track keyframe array offset (relative to block)
        private final boolean scalePairs; // scale keyframes store value+inverse; read the value, skip the inverse
        private float[] keys;
        private int step;

        private Track(float c, boolean isConst, int info, int dataOffset, boolean scalePairs)
        {
            this.constValue = c; this.isConst = isConst; this.info = info;
            this.dataOffset = dataOffset; this.scalePairs = scalePairs;
        }

        static Track constant(float c) { return new Track(c, true, 0, 0, false); }
        static Track variable(int info, int offset) { return new Track(0, false, info, offset, false); }
        static Track scaleVariable(int info, int offset) { return new Track(0, false, info, offset, true); }

        void bind(byte[] d, int frameCount)
        {
            if (isConst)
                return;
            step = frameStep(info);
            boolean fx16 = (info & TINFO_FX16) != 0;
            int count = valueCount(frameCount, step);
            keys = new float[count];
            int stride = (fx16 ? 2 : 4) * (scalePairs ? 2 : 1);
            for (int i = 0; i < count; i++)
                keys[i] = fx16 ? readFx16(d, dataOffset + i * stride) : readFx32(d, dataOffset + i * stride);
        }

        float sample(int frame)
        {
            if (isConst)
                return constValue;
            int seg = frame / step;
            if (seg >= keys.length - 1)
                return keys[keys.length - 1];
            double frac = (frame - seg * step) / (double) step;
            return (float) (keys[seg] + (keys[seg + 1] - keys[seg]) * frac);
        }
    }

    // A rotation track: constant matrix, or keyframe matrices sampled every `step` frames. Each stored
    // value is a u16 index into one of two shared pools (pivot 6-byte, or 5-value 10-byte).
    private static final class RotTrack
    {
        private final boolean isConst;
        private final int constIndex; // constant-track pool index (-1 => identity)
        private final int info;
        private final int dataOffset;
        private int rot3Pool, rot5Pool, animStart;
        private double[][] keys; // each a row-major 3x3 (9)
        private int step;
        private int frameCount;

        private RotTrack(boolean isConst, int constIndex, int info, int dataOffset)
        {
            this.isConst = isConst; this.constIndex = constIndex; this.info = info; this.dataOffset = dataOffset;
        }

        static RotTrack constant(int index) { return new RotTrack(true, index, 0, 0); }
        static RotTrack variable(int info, int offset) { return new RotTrack(false, -1, info, offset); }

        void bind(byte[] d, int animStart, int rot3Pool, int rot5Pool, int frameCount)
        {
            this.animStart = animStart; this.rot3Pool = rot3Pool; this.rot5Pool = rot5Pool;
            this.frameCount = frameCount;
            if (isConst)
            {
                keys = new double[][]{constIndex == -1
                        ? new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1}
                        : readMatrix(d, animStart, rot3Pool, rot5Pool, constIndex)};
                step = 1;
                return;
            }
            step = frameStep(info);
            int count = valueCount(frameCount, step);
            keys = new double[count][];
            for (int i = 0; i < count; i++)
            {
                int raw = readU16(d, dataOffset + i * 2);
                keys[i] = readMatrix(d, animStart, rot3Pool, rot5Pool, raw);
            }
        }

        double[] sample(int frame)
        {
            if (isConst || keys.length == 1)
                return keys[0];
            int seg = frame / step;
            if (seg >= keys.length - 1)
                return keys[keys.length - 1];
            double frac = (frame - seg * step) / (double) step;
            double[] a = keys[seg], b = keys[seg + 1], out = new double[9];
            for (int i = 0; i < 9; i++)
                out[i] = a[i] + (b[i] - a[i]) * frac;
            return out;
        }
    }

    // --- rotation matrix pools ---

    // Reads a 3x3 rotation from the shared pools. Bit 15 of raw selects the pivot pool (6-byte entries);
    // otherwise the 5-value pool (10-byte entries). The low 15 bits index the entry.
    private static double[] readMatrix(byte[] d, int animStart, int rot3Pool, int rot5Pool, int raw)
    {
        boolean pivot = (raw & 0x8000) != 0;
        int idx = raw & 0x7FFF;
        return pivot ? readRot3(d, animStart + rot3Pool + 6 * idx)
                     : readRot5(d, animStart + rot5Pool + 10 * idx);
    }

    // Pivot-compressed rotation (same construction as a model node's pivot matrix): flags select which
    // cell is +/-1 and the sign of the two stored values fill a 2x2 minor.
    private static double[] readRot3(byte[] d, int p)
    {
        int flags = readU16(d, p);
        double a = readFx16(d, p + 2), b = readFx16(d, p + 4);
        double c = (flags & 0x20) != 0 ? -b : b;
        double dd = (flags & 0x40) != 0 ? -a : a;
        double one = (flags & 0x10) != 0 ? -1.0 : 1.0;
        int sel = flags & 0xF, row = sel / 3, col = sel % 3;
        double[] arr = {a, b, c, dd};
        double[] r = new double[9];
        int k = 0;
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                r[i * 3 + j] = (j == col || i == row) ? 0 : arr[k++];
        r[row * 3 + col] = one;
        return r;
    }

    // 5-value-compressed rotation: two rows are stored as 5 fx16 (each row value in the high 13 bits, its
    // low 3 bits packing a sixth value); the third row is their cross product, completing the basis.
    private static double[] readRot5(byte[] d, int p)
    {
        int v0 = (short) readU16(d, p), v1 = (short) readU16(d, p + 2), v2 = (short) readU16(d, p + 4);
        int v3 = (short) readU16(d, p + 6), v4 = (short) readU16(d, p + 8);
        int packed = (v3 & 7) | ((v2 & 7) << 3) | ((v1 & 7) << 6) | ((v0 & 7) << 9);
        if ((v4 & 1) != 0) packed |= 0xF000;
        double m00 = ((short) (v0 & 0xFFF8) >> 3) / 4096.0;
        double m01 = ((short) (v1 & 0xFFF8) >> 3) / 4096.0;
        double m02 = ((short) (v2 & 0xFFF8) >> 3) / 4096.0;
        double m10 = ((short) (v3 & 0xFFF8) >> 3) / 4096.0;
        double m11 = ((short) (v4 & 0xFFF8) >> 3) / 4096.0;
        double m12 = (short) packed / 4096.0;
        double m20 = m01 * m12 - m02 * m11;
        double m21 = m02 * m10 - m00 * m12;
        double m22 = m00 * m11 - m01 * m10;
        return new double[]{m00, m01, m02, m10, m11, m12, m20, m21, m22};
    }

    // --- keyframe / step helpers (NNS_G3D_JNTANM_?INFO) ---

    private static final int TINFO_STEP_2 = 0x40000000, TINFO_STEP_4 = 0x80000000, TINFO_FX16 = 0x20000000;

    private static int frameStep(int info)
    {
        if ((info & TINFO_STEP_4) == TINFO_STEP_4) return 4;
        if ((info & TINFO_STEP_2) == TINFO_STEP_2) return 2;
        return 1;
    }

    // The number of stored keyframes for a track: ceil(frames/step) + (frames-1) % step (NNS getValueCount).
    private static int valueCount(int frames, int step)
    {
        return (int) Math.ceil(frames / (double) step) + (frames - 1) % step;
    }

    private static int readU16(byte[] d, int o)
    {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    private static long readU32(byte[] d, int o)
    {
        return (d[o] & 0xFFL) | ((d[o + 1] & 0xFFL) << 8) | ((d[o + 2] & 0xFFL) << 16) | ((d[o + 3] & 0xFFL) << 24);
    }

    private static float readFx16(byte[] d, int o)
    {
        return (short) readU16(d, o) / 4096.0f;
    }

    private static float readFx32(byte[] d, int o)
    {
        return (int) readU32(d, o) / 4096.0f;
    }
}
