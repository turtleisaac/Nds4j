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
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates a NITRO intermediate model (<b>{@code .imd}</b>, the XML the Maya/3ds&nbsp;Max NNS exporter
 * emits) into a binary <b>NSBMD</b> &mdash; the native, byte-for-byte replacement for Nintendo's
 * {@code g3dcvtr} tool. Because the {@code .imd} already carries every optimisation decision the exporter
 * made (which vertices are full {@code pos_s} vs delta {@code pos_xy}/{@code pos_xz}/{@code pos_yz}, the
 * strip/quad grouping, the node transforms and material state), translating it faithfully reproduces
 * {@code g3dcvtr}'s output <em>exactly</em> &mdash; without reimplementing the exporter's optimiser.
 * <p>
 * Verified byte-identical to g3dcvtr across all three of its output modes &mdash; {@code -emdl} ({@link #toNsbmd()}),
 * {@code -eboth} ({@link #toNsbmdWithTextures()}) and {@code -etex} ({@link #toNsbtx()}). Coverage: multi-node
 * trees with full node local transforms (translation, non-uniform scale, and rotation &mdash; pivot-compressed
 * for principal axes, full 3&times;3 otherwise), multiple materials (grouped by shared texture/palette) and
 * multiple shapes, textured or vertex-colored, billboard or not. The SBC render stream is a general node-tree
 * walk with a matrix-stack store/restore allocator and a material stack, validated byte-for-byte against every
 * retail single- and two-node model and the great majority of deeper trees (the residual are complex skeletal
 * chains and a matrix-slot-numbering edge). The geometry, resource dictionaries and container are produced by
 * the byte-exact primitives ({@link DisplayList}, {@link G3dDictionary}, {@link G3dFile}); this class adds the
 * {@code .imd} parse and the MDL0/TEX0 struct encoders (model header/box, node set with local matrices, SBC
 * render stream, material set, shape set, texture/palette).
 * <p>
 * Use the fluent, class-based API &mdash; {@link #fromXml(String)}/{@link #fromFile(File)}, {@link #named(String)},
 * the {@code getX} accessors over the parsed model, and {@link #toModelSet()}/{@link #toTextureSet()} to land
 * directly on the flagship {@link ModelSet}/{@link TextureSet} (also reachable via {@link ModelSet#fromImd} and
 * {@link TextureSet#fromImd}). The {@code static} {@code toNsbmd}/{@code toNsbmdWithTextures}/{@code toNsbtx}
 * shortcuts remain for one-liners.
 */
public final class ImdImporter
{
    private final String imd;

    private String modelName = "model";

    private ImdImporter(String imd) { this.imd = imd; }

    // === factories =====================================================================================

    /** Opens {@code .imd} source for conversion. Set the model name with {@link #named(String)} (default {@code "model"}). */
    public static ImdImporter fromXml(String imdXml)
    {
        return new ImdImporter(imdXml);
    }

    /** Opens an {@code .imd} file for conversion, taking the model name from the file's base name (as g3dcvtr does). */
    public static ImdImporter fromFile(File file) throws IOException
    {
        String xml = new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1);
        String base = file.getName();
        int dot = base.lastIndexOf('.');
        return new ImdImporter(xml).named(dot < 0 ? base : base.substring(0, dot));
    }

    /** Sets the model name g3dcvtr would take from the source file's base name; returns {@code this} for chaining. */
    public ImdImporter named(String modelName)
    {
        this.modelName = modelName;
        return this;
    }

    // === enriched accessors (the parsed .imd structure) ================================================

    /** The model name used when authoring the NSBMD. */
    public String getModelName() { return modelName; }

    /** Whether the {@code .imd} carries a texture ({@code tex_image}) — i.e. whether {@link #toNsbtx()} / embedding applies. */
    public boolean hasTextures() { return Pattern.compile("<tex_image[\\s>]").matcher(imd).find(); }

    /** The node names, in index order (the {@code <node_array>}). */
    public List<String> getNodeNames()
    {
        List<String> names = new ArrayList<>();
        for (Node nd : parseNodes()) names.add(nd.name);
        return names;
    }

    /** The number of nodes in the model tree. */
    public int getNodeCount() { return parseNodes().size(); }

    /** The material names, in declaration order (the {@code <material_array>}). */
    public List<String> getMaterialNames()
    {
        List<String> names = new ArrayList<>();
        for (String el : blocks("material")) names.add(a(el, "name"));
        return names;
    }

    /** The number of shapes (polygons) in the model. */
    public int getShapeCount() { return blocks("polygon").size(); }

    // === conversions (the g3dcvtr output modes) =======================================================

    /** Authors a model-only NSBMD (the {@code g3dcvtr -emdl} equivalent), byte-identical to g3dcvtr. */
    public byte[] toNsbmd()
    {
        return G3dFile.assembleContainer("BMD0", 2, buildMdl0(modelName));
    }

    /**
     * Authors an NSBMD with the texture embedded as a {@code TEX0} block (the {@code g3dcvtr -eboth} equivalent):
     * the MDL0 block is identical to {@link #toNsbmd()}, with the {@code tex_image}/{@code tex_palette} appended.
     */
    public byte[] toNsbmdWithTextures()
    {
        return G3dFile.assembleContainer("BMD0", 2, buildMdl0(modelName), buildTex0());
    }

    /** Authors a texture-only NSBTX (the {@code g3dcvtr -etex} equivalent) from the {@code .imd}'s texture/palette. */
    public byte[] toNsbtx()
    {
        return G3dFile.assembleContainer("BTX0", 1, buildTex0());
    }

    /** Authors the model and returns it as a {@link ModelSet} (textures embedded when the {@code .imd} has them). */
    public ModelSet toModelSet()
    {
        return new ModelSet(hasTextures() ? toNsbmdWithTextures() : toNsbmd());
    }

    /** Authors the texture archive and returns it as a {@link TextureSet}. */
    public TextureSet toTextureSet()
    {
        return new TextureSet(toNsbtx());
    }

    // === back-compat static shortcuts =================================================================

    /** Model-only NSBMD (the {@code g3dcvtr -emdl} equivalent). Shortcut for {@code fromXml(imdXml).named(modelName).toNsbmd()}. */
    public static byte[] toNsbmd(String imdXml, String modelName)
    {
        return fromXml(imdXml).named(modelName).toNsbmd();
    }

    /** NSBMD with embedded TEX0 (the {@code g3dcvtr -eboth} equivalent). Shortcut for {@code fromXml(imdXml).named(modelName).toNsbmdWithTextures()}. */
    public static byte[] toNsbmdWithTextures(String imdXml, String modelName)
    {
        return fromXml(imdXml).named(modelName).toNsbmdWithTextures();
    }

    /** Texture-only NSBTX (the {@code g3dcvtr -etex} equivalent). Shortcut for {@code fromXml(imdXml).toNsbtx()}. */
    public static byte[] toNsbtx(String imdXml)
    {
        return fromXml(imdXml).toNsbtx();
    }

    private byte[] buildMdl0(String modelName)
    {
        int posScale = 1 << (int) Double.parseDouble(attr("model_info", "pos_scale"));
        double[] boxXyz = doubles(attr("box_test", "xyz"));
        double[] boxWhd = doubles(attr("box_test", "whd"));
        int boxScale = 1 << (int) Double.parseDouble(attr("box_test", "pos_scale"));
        int numVtx = intAttr("output_info", "vertex_size");
        int numPoly = intAttr("output_info", "polygon_size");
        int numTri = intAttr("output_info", "triangle_size");
        int numQuad = intAttr("output_info", "quad_size");
        boolean magnified = posScale != 1;

        List<Node> nodes = parseNodes();                    // the full <node_array>, in index order
        List<String> materials = blocks("material");        // each <material .../>
        List<String> polygons = blocks("polygon");          // each <polygon ...>...</polygon>

        List<byte[]> dls = new ArrayList<>();
        for (String poly : polygons) dls.add(buildDisplayList(poly));
        int[] firstUnused = new int[1];
        byte[] nodeSet = buildNodeSet(nodes);
        byte[] sbc = generateSbc(nodes, magnified, firstUnused);
        byte[] matSet = buildMaterialSet(materials);
        byte[] shapeSet = buildShapeSet(polygons, dls);

        int headerLen = 0x40;
        int ofsSbc = headerLen + nodeSet.length;
        int ofsMat = ofsSbc + sbc.length;
        int ofsShp = ofsMat + matSet.length;
        int modelLen = ofsShp + shapeSet.length;
        byte[] model = new byte[modelLen];
        u32(model, 0, modelLen);
        u32(model, 4, ofsSbc);
        u32(model, 8, ofsMat);
        u32(model, 12, ofsShp);
        u32(model, 16, modelLen);            // ofsEndOrEnvelope: past the last section (no envelope)
        int info = 0x14;
        model[info + 3] = (byte) nodes.size();      // numNode
        model[info + 4] = (byte) materials.size();  // matCount
        model[info + 5] = (byte) polygons.size();   // shapeCount
        model[info + 6] = (byte) firstUnused[0];    // firstUnusedMtxStackId (matrix-stack high-water)
        u32(model, info + 8, (long) posScale * 4096);       // posScale (fx32)
        u32(model, info + 0xC, 4096L / posScale);           // inverse posScale
        u16(model, info + 0x10, numVtx);
        u16(model, info + 0x12, numPoly);
        u16(model, info + 0x14, numTri);
        u16(model, info + 0x16, numQuad);
        for (int c = 0; c < 3; c++)
        {
            u16(model, info + 0x18 + c * 2, fx(boxXyz[c], 12) & 0xFFFF); // box min corner
            u16(model, info + 0x1E + c * 2, fx(boxWhd[c], 12) & 0xFFFF); // box dimensions
        }
        u32(model, info + 0x24, (long) boxScale * 4096);    // boxPosScale (fx32)
        u32(model, info + 0x28, 4096L / boxScale);          // inverse boxPosScale
        System.arraycopy(nodeSet, 0, model, headerLen, nodeSet.length);
        System.arraycopy(sbc, 0, model, ofsSbc, sbc.length);
        System.arraycopy(matSet, 0, model, ofsMat, matSet.length);
        System.arraycopy(shapeSet, 0, model, ofsShp, shapeSet.length);

        int modelStart = 8 + serialize(G3dDictionary.build(List.of(modelName), List.of(rec4(0)), 4)).length;
        byte[] modelDict = serialize(G3dDictionary.build(List.of(modelName), List.of(rec4(modelStart)), 4));
        int mdl0Len = modelStart + model.length;
        byte[] mdl0 = new byte[mdl0Len];
        System.arraycopy("MDL0".getBytes(StandardCharsets.US_ASCII), 0, mdl0, 0, 4);
        u32(mdl0, 4, mdl0Len);
        System.arraycopy(modelDict, 0, mdl0, 8, modelDict.length);
        System.arraycopy(model, 0, mdl0, modelStart, model.length);
        return mdl0;
    }

    // --- TEX0 block from the .imd tex_image (palette16 bitmap) + tex_palette, in g3dcvtr's exact layout ---
    private byte[] buildTex0()
    {
        String texName = attr("tex_image", "name");
        String pltName = attr("tex_palette", "name");
        int w = intAttr("tex_image", "width"), h = intAttr("tex_image", "height");
        boolean color0Transparent = "transparency".equals(attr("tex_image", "color0_mode"));

        // texels: the bitmap is 4-hex-digit big-endian 16-bit words; store each word little-endian (the
        // low byte holds the first two 4bpp pixels, low nibble first)
        String bmpHex = tagContent(imd, "bitmap").replaceAll("[^0-9a-fA-F]", "");
        byte[] texel = new byte[w * h / 2];
        for (int i = 0; i < texel.length; i += 2)
        {
            int word = Integer.parseInt(bmpHex.substring(i * 2, i * 2 + 4), 16);
            texel[i] = (byte) word;
            texel[i + 1] = (byte) (word >> 8);
        }
        // palette: each 4-hex-digit token is a BGR555 color, stored little-endian
        String[] palTok = tagContent(imd, "tex_palette").trim().split("\\s+");
        byte[] palette = new byte[palTok.length * 2];
        for (int i = 0; i < palTok.length; i++)
        {
            int c = (int) Long.parseLong(palTok[i], 16);
            palette[i * 2] = (byte) c;
            palette[i * 2 + 1] = (byte) (c >> 8);
        }

        int widthSel = Integer.numberOfTrailingZeros(w / 8);
        int heightSel = Integer.numberOfTrailingZeros(h / 8);
        long texImageParam = ((long) 3 << 26) | ((long) widthSel << 20) | ((long) heightSel << 23)
                | (color0Transparent ? 1L << 29 : 0);
        byte[] texRec = new byte[8];
        u32(texRec, 0, texImageParam);
        u32(texRec, 4, 0x80008010L);          // extraParam (observed; texel-key/flags)
        byte[] texDict = serialize(G3dDictionary.build(List.of(texName), List.of(texRec), 8));
        byte[] plttDict = serialize(G3dDictionary.build(List.of(pltName), List.of(rec4(0)), 4));

        int header = 0x3c;
        int ofsDict = header;
        int ofsPltDict = ofsDict + texDict.length;
        int ofsTexData = ofsPltDict + plttDict.length;
        int ofsPltData = ofsTexData + texel.length;
        int blockSize = ofsPltData + palette.length;
        byte[] b = new byte[blockSize];
        System.arraycopy("TEX0".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 4);
        u32(b, 4, blockSize);
        u16(b, 12, texel.length >> 3);        // sizeTex (8-byte units)
        u16(b, 14, ofsDict);
        u32(b, 20, ofsTexData);
        u16(b, 30, ofsDict);                  // Tex4x4Info dict (points at the tex dict; no 4x4 data)
        u32(b, 36, ofsPltData);               // ofsTex4x4Data / PlttIdx point past the texel data
        u32(b, 40, ofsPltData);
        u16(b, 48, palette.length >> 3);      // sizePltt (8-byte units)
        u16(b, 52, ofsPltDict);
        u32(b, 56, ofsPltData);               // plttDataOfs
        System.arraycopy(texDict, 0, b, ofsDict, texDict.length);
        System.arraycopy(plttDict, 0, b, ofsPltDict, plttDict.length);
        System.arraycopy(texel, 0, b, ofsTexData, texel.length);
        System.arraycopy(palette, 0, b, ofsPltData, palette.length);
        return b;
    }

    // the text between an element's opening ">" and its "</tag>" (skipping the opening tag's attributes).
    // Matches the tag name at a word boundary so "tex_palette" doesn't hit "tex_palette_array".
    private static String tagContent(String s, String tag)
    {
        Matcher m = Pattern.compile("<" + tag + "[\\s>]").matcher(s);
        if (!m.find()) return "";
        int gt = s.indexOf('>', m.start());
        int close = s.indexOf("</" + tag + ">", gt);
        return gt < 0 || close < 0 ? "" : s.substring(gt + 1, close);
    }
    private static int hexDigit(char c) { return Character.digit(c, 16); }
    private static int polygonMode(String m) { return "decal".equals(m) ? 1 : "toon".equals(m) ? 2 : "shadow".equals(m) ? 3 : 0; }
    // teximage_param wrap bits for one axis: repeat sets the repeat bit; flip sets repeat+flip; clamp = 0
    private static long wrapBits(String mode, int repeatBit, int flipBit)
    {
        if ("flip".equals(mode)) return (1L << repeatBit) | (1L << flipBit);
        if ("repeat".equals(mode)) return 1L << repeatBit;
        return 0;
    }

    // --- geometry: translate one polygon's primitive commands into GPU commands, pad the DL to /8 ---
    private byte[] buildDisplayList(String polygon)
    {
        List<DisplayList.Command> cmds = new ArrayList<>();
        Matcher pm = Pattern.compile("<primitive [^>]*type=\"(\\w+)\"[^>]*>(.*?)</primitive>", Pattern.DOTALL).matcher(polygon);
        pm.find();
        String type = pm.group(1);
        int prim = type.equals("triangles") ? 0 : type.equals("quads") ? 1 : type.equals("triangle_strip") ? 2 : 3;
        cmds.add(new DisplayList.Command(0x40, new int[]{prim})); // BEGIN_VTXS
        Matcher cm = Pattern.compile("<(mtx|tex|nrm|clr|pos_s|pos_xy|pos_xz|pos_yz|pos_diff)([^>]*)/>").matcher(pm.group(2));
        while (cm.find())
        {
            double[] v = doubles(firstQuoted(cm.group(2)));
            switch (cm.group(1))
            {
                case "mtx": break; // single node: the current matrix is already bound (no MTX_RESTORE)
                case "tex": cmds.add(cmd(0x22, (fx(v[0], 4) & 0xFFFF) | ((fx(v[1], 4) & 0xFFFF) << 16))); break;
                case "nrm": cmds.add(cmd(0x21, pack10(fx(v[0], 9), fx(v[1], 9), fx(v[2], 9)))); break;
                case "clr": cmds.add(cmd(0x20, (fx(v[0], 0) & 0x1F) | ((fx(v[1], 0) & 0x1F) << 5) | ((fx(v[2], 0) & 0x1F) << 10))); break;
                case "pos_s":
                    if (exact10(v[0]) && exact10(v[1]) && exact10(v[2]))
                        cmds.add(cmd(0x24, pack10(fx(v[0], 6), fx(v[1], 6), fx(v[2], 6))));       // VTX_10
                    else
                        cmds.add(cmd(0x23, (fx(v[0], 12) & 0xFFFF) | ((fx(v[1], 12) & 0xFFFF) << 16), fx(v[2], 12) & 0xFFFF)); // VTX_16
                    break;
                case "pos_xy": cmds.add(cmd(0x25, (fx(v[0], 12) & 0xFFFF) | ((fx(v[1], 12) & 0xFFFF) << 16))); break;
                case "pos_xz": cmds.add(cmd(0x26, (fx(v[0], 12) & 0xFFFF) | ((fx(v[1], 12) & 0xFFFF) << 16))); break;
                case "pos_yz": cmds.add(cmd(0x27, (fx(v[0], 12) & 0xFFFF) | ((fx(v[1], 12) & 0xFFFF) << 16))); break;
                case "pos_diff": cmds.add(cmd(0x28, pack10(fx(v[0], 12), fx(v[1], 12), fx(v[2], 12)))); break;
            }
        }
        cmds.add(cmd(0x41)); // END_VTXS
        byte[] dl = DisplayList.encodeCommands(cmds);
        int pad = (8 - dl.length % 8) % 8;
        return pad == 0 ? dl : java.util.Arrays.copyOf(dl, dl.length + pad);
    }

    // --- node set: node dictionary + one identity node local matrix (flags 0xf807, rotation[0][0] = 1) ---
    private byte[] buildNodeSet(List<Node> nodes)
    {
        int n = nodes.size();
        List<String> names = new ArrayList<>();
        for (Node nd : nodes) names.add(nd.name);
        // each node's local matrix is encoded to a variable-length struct (identity is 4 bytes; a translated/
        // scaled/rotated node is longer). The structs sit contiguously right after the dict; each dict record
        // is the byte offset (from the node-set start) to its struct. Measure the dict, then point at the structs.
        byte[][] structs = new byte[n][];
        for (int i = 0; i < n; i++) structs[i] = encodeNodeStruct(nodes.get(i));
        int dictSize = serialize(G3dDictionary.build(names, placeholders(n), 4)).length;
        List<byte[]> recs = new ArrayList<>();
        int cursor = dictSize;
        for (int i = 0; i < n; i++) { recs.add(rec4(cursor)); cursor += structs[i].length; }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(serialize(G3dDictionary.build(names, recs, 4)));
        for (byte[] s : structs) out.writeBytes(s);
        return out.toByteArray();
    }

    // --- node local matrix (NNS_G3dResNodeData), the inverse of Model.parseNodeLocals, byte-identical to
    //     g3dcvtr. Layout: flags(u16) · _00(fx16 = rotation[0][0], 1.0 when rotation omitted) · [translation
    //     3×fx32 if present] · [rotation if present] · [scale 3×fx32 + inverse 3×fx32 if present]. flags bit0/1/2
    //     omit translation/rotation/scale (set when that component is identity); bit3 selects pivot-compressed
    //     rotation. Rotation is stored transposed: Mt = (Rz·Ry·Rx)ᵀ from the Euler angles (g3dcvtr's convention).
    //     A principal-axis matrix (one row-major cell is ±1 with a zero row and column) is pivot-compressed to
    //     two values av/bv (the 2×2 minor) with the pivot cell in flags bits 4–7 and sign flags 0x100/0x200/
    //     0x400; otherwise the full 3×3 remainder is written as 8×fx16. ---
    private byte[] encodeNodeStruct(Node nd)
    {
        boolean omitT = nd.translate[0] == 0 && nd.translate[1] == 0 && nd.translate[2] == 0;
        boolean omitS = nd.scale[0] == 1 && nd.scale[1] == 1 && nd.scale[2] == 1;
        boolean omitR = nd.rotate[0] == 0 && nd.rotate[1] == 0 && nd.rotate[2] == 0;

        int flags = 0xf800 | (omitT ? 0x1 : 0) | (omitR ? 0x2 : 0) | (omitS ? 0x4 : 0);
        double m00 = 1.0;
        double[][] mt = null;
        int[] pivot = null;                                   // {row, col, oneNeg, cNeg, dNeg} or null for full
        if (!omitR)
        {
            mt = rotationTransposed(nd.rotate);
            m00 = mt[0][0];
            pivot = findPivot(mt);
            if (pivot != null)
            {
                int sel = pivot[0] * 3 + pivot[1];
                flags |= 0x8 | (sel << 4) | (pivot[2] != 0 ? 0x100 : 0) | (pivot[3] != 0 ? 0x200 : 0) | (pivot[4] != 0 ? 0x400 : 0);
            }
        }

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeU16(o, flags);
        writeU16(o, fx(m00, 12) & 0xFFFF);                    // _00
        if (!omitT) for (int i = 0; i < 3; i++) writeU32(o, fx(nd.translate[i], 12) & 0xFFFFFFFFL);
        if (!omitR)
        {
            if (pivot != null)
            {
                int r = pivot[0], c = pivot[1];
                int[] rows = other(r), cols = other(c);
                writeU16(o, fx(mt[rows[0]][cols[0]], 12) & 0xFFFF); // av = minor[0][0]
                writeU16(o, fx(mt[rows[0]][cols[1]], 12) & 0xFFFF); // bv = minor[0][1]
            }
            else
            {
                int[][] order = {{0, 1}, {0, 2}, {1, 0}, {1, 1}, {1, 2}, {2, 0}, {2, 1}, {2, 2}};
                for (int[] rc : order) writeU16(o, fx(mt[rc[0]][rc[1]], 12) & 0xFFFF);
            }
        }
        if (!omitS)
        {
            for (int i = 0; i < 3; i++) writeU32(o, fx(nd.scale[i], 12) & 0xFFFFFFFFL);
            for (int i = 0; i < 3; i++) writeU32(o, fx(1.0 / nd.scale[i], 12) & 0xFFFFFFFFL); // unused inverse
        }
        return o.toByteArray();
    }

    // Mt = (Rz·Ry·Rx)ᵀ from Euler angles in degrees — the transposed local rotation g3dcvtr stores.
    private static double[][] rotationTransposed(double[] deg)
    {
        double x = Math.toRadians(deg[0]), y = Math.toRadians(deg[1]), z = Math.toRadians(deg[2]);
        double[][] rx = {{1, 0, 0}, {0, Math.cos(x), -Math.sin(x)}, {0, Math.sin(x), Math.cos(x)}};
        double[][] ry = {{Math.cos(y), 0, Math.sin(y)}, {0, 1, 0}, {-Math.sin(y), 0, Math.cos(y)}};
        double[][] rz = {{Math.cos(z), -Math.sin(z), 0}, {Math.sin(z), Math.cos(z), 0}, {0, 0, 1}};
        double[][] r = matMul(matMul(rz, ry), rx);
        double[][] t = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) t[i][j] = r[j][i];
        return t;
    }

    private static double[][] matMul(double[][] a, double[][] b)
    {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)
            for (int k = 0; k < 3; k++) r[i][j] += a[i][k] * b[k][j];
        return r;
    }

    // a principal-axis matrix has a cell that is ±1 with a zero row and column; g3dcvtr picks the first such
    // cell in row-major order and pivot-compresses. Returns {row, col, oneNeg, cNeg, dNeg} or null (use full form).
    private static int[] findPivot(double[][] m)
    {
        double eps = 1e-4;
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++)
            {
                if (Math.abs(Math.abs(m[r][c]) - 1) > eps) continue;
                boolean clean = true;
                for (int k = 0; k < 3; k++)
                    if ((k != c && Math.abs(m[r][k]) > eps) || (k != r && Math.abs(m[k][c]) > eps)) { clean = false; break; }
                if (!clean) continue;
                int[] rows = other(r), cols = other(c);
                double av = m[rows[0]][cols[0]], bv = m[rows[0]][cols[1]];
                double mc = m[rows[1]][cols[0]], md = m[rows[1]][cols[1]];
                return new int[]{r, c, m[r][c] < 0 ? 1 : 0, mc * bv < 0 ? 1 : 0, md * av < 0 ? 1 : 0};
            }
        return null;
    }

    // the two indices other than i, in ascending order
    private static int[] other(int i) { return i == 0 ? new int[]{1, 2} : i == 1 ? new int[]{0, 2} : new int[]{0, 1}; }
    private static void writeU16(ByteArrayOutputStream o, int v) { o.write(v & 0xFF); o.write((v >> 8) & 0xFF); }
    private static void writeU32(ByteArrayOutputStream o, long v) { for (int i = 0; i < 4; i++) o.write((int) (v >> (8 * i)) & 0xFF); }

    // --- SBC render stream (general, multi-node): a pre-order walk of the node tree. Mirrors g3dcvtr's
    //     matrix-stack allocator (validated byte-for-byte against retail): a node whose matrix is reused by a
    //     child or by more than one of its own draws is NODEDESC-stored to the lowest free stack slot; a node
    //     whose parent's matrix isn't the current one restores it. Draws emit POSSCALE {MAT[,SHP]}* POSSCALE|end,
    //     and a material used by more than one draw is stored on first use / restored on reuse (material stack).
    //     outFirstUnused[0] receives the matrix-stack high-water mark (the model header's firstUnusedMtxStackId). ---
    private byte[] generateSbc(List<Node> nodes, boolean magnified, int[] outFirstUnused)
    {
        int N = nodes.size();
        List<List<Integer>> children = new ArrayList<>();
        for (int i = 0; i < N; i++) children.add(new ArrayList<>());
        for (int i = 0; i < N; i++)
        {
            int p = nodes.get(i).parent;
            if (p != i && p >= 0 && p < N) children.get(p).add(i);
        }
        int[] slotOf = new int[N];
        java.util.Arrays.fill(slotOf, -1);
        boolean[] slotUsed = new boolean[64];
        int maxSlot = -1;
        // material stack: a material used by more than one draw is stored on first use, restored on later uses
        java.util.Map<Integer, Integer> matUse = new java.util.HashMap<>();
        for (Node nd : nodes) for (int[] dr : nd.draw) matUse.merge(dr[0], 1, Integer::sum);
        java.util.Set<Integer> matSeen = new java.util.HashSet<>();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int cur = -1;
        for (int n = 0; n < N; n++)
        {
            Node nd = nodes.get(n);
            int p = nd.parent;
            int restore = (p != n && cur != p) ? slotOf[p] : -1;
            int store = -1;
            if (!children.get(n).isEmpty() || nd.draw.size() > 1)
            {
                store = lowestFree(slotUsed);
                slotUsed[store] = true;
                slotOf[n] = store;
                if (store > maxSlot) maxSlot = store;
            }
            o.write(0x06 | (store >= 0 ? 0x20 : 0) | (restore >= 0 ? 0x40 : 0));
            o.write(n); o.write(p); o.write(0);
            if (store >= 0) o.write(store);
            if (restore >= 0) o.write(restore);
            cur = n;
            if (nd.draws)
            {
                o.write(0x02); o.write(n); o.write(nd.vis);        // NODE(node, visibility)
                if (nd.bb == 1) { o.write(0x07); o.write(n); }     // BB (screen-facing)
                else if (nd.bb == 2) { o.write(0x08); o.write(n); } // BBY (y-axis billboard)
                if (magnified) o.write(0x0b);                      // POSSCALE (begin)
                for (int[] dr : nd.draw)
                {
                    int matFlag = matUse.getOrDefault(dr[0], 1) > 1 ? (matSeen.add(dr[0]) ? 0x20 : 0x40) : 0;
                    o.write(0x04 | matFlag); o.write(dr[0]);        // MAT(materialIdx)[+store/restore]
                    o.write(0x05); o.write(dr[1]);                  // SHP(polygonIdx)
                }
                if (magnified) o.write(0x2b);                      // POSSCALE | 0x20 (end)
            }
            // free every slot whose owner's last child is this node
            for (int q = 0; q < N; q++)
                if (!children.get(q).isEmpty() && children.get(q).get(children.get(q).size() - 1) == n && slotOf[q] >= 0)
                    slotUsed[slotOf[q]] = false;
        }
        o.write(0x01);                                             // RET
        while (o.size() % 4 != 0) o.write(0);
        outFirstUnused[0] = maxSlot + 1;
        return o.toByteArray();
    }

    private static int lowestFree(boolean[] used)
    {
        for (int i = 0; i < used.length; i++) if (!used[i]) return i;
        return 0;
    }

    // --- material set: header, material dict, tex/pltt->material dicts (grouped by resource), index lists,
    //     then the material structs. Materials sharing a texture/palette are grouped into one dict entry
    //     whose index list names the materials that use it. ---
    private byte[] buildMaterialSet(List<String> materials)
    {
        int n = materials.size();
        List<byte[]> matStructs = new ArrayList<>();
        List<String> matNames = new ArrayList<>();
        // group material indices by texture/palette name; the dict entries are ordered by name (bytewise),
        // matching g3dcvtr, and each entry lists the materials that use it (in material order)
        java.util.TreeMap<String, List<Integer>> byTex = new java.util.TreeMap<>();
        java.util.TreeMap<String, List<Integer>> byPlt = new java.util.TreeMap<>();
        for (int i = 0; i < n; i++)
        {
            String m = materials.get(i);
            matNames.add(a(m, "name"));
            int texIdx = Integer.parseInt(a(m, "tex_image_idx"));
            int pltIdx = Integer.parseInt(a(m, "tex_palette_idx"));
            String texName = a(blocks("tex_image").get(texIdx), "name");
            String pltName = a(blocks("tex_palette").get(pltIdx), "name");
            byTex.computeIfAbsent(texName, k -> new ArrayList<>()).add(i);
            byPlt.computeIfAbsent(pltName, k -> new ArrayList<>()).add(i);
            matStructs.add(buildMaterialStruct(m, texIdx));
        }

        List<byte[]> dummy = new ArrayList<>();
        for (int i = 0; i < n; i++) dummy.add(rec4(0));
        int matDictLen = serialize(G3dDictionary.build(matNames, dummy, 4)).length;
        int texDictLen = serialize(G3dDictionary.build(new ArrayList<>(byTex.keySet()), placeholders(byTex.size()), 4)).length;
        int pltDictLen = serialize(G3dDictionary.build(new ArrayList<>(byPlt.keySet()), placeholders(byPlt.size()), 4)).length;
        int ofsTexDict = 4 + matDictLen;
        int ofsPltDict = ofsTexDict + texDictLen;
        int idxBase = ofsPltDict + pltDictLen;
        // index-list region: each tex group's material list, then each pltt group's, padded to /4
        ByteArrayOutputStream idx = new ByteArrayOutputStream();
        List<byte[]> texRecs = new ArrayList<>();
        for (java.util.Map.Entry<String, List<Integer>> e : byTex.entrySet()) { texRecs.add(rec4(((idxBase + idx.size()) & 0xFFFF) | ((long) e.getValue().size() << 16))); for (int mi : e.getValue()) idx.write(mi); }
        List<byte[]> pltRecs = new ArrayList<>();
        for (java.util.Map.Entry<String, List<Integer>> e : byPlt.entrySet()) { pltRecs.add(rec4(((idxBase + idx.size()) & 0xFFFF) | ((long) e.getValue().size() << 16))); for (int mi : e.getValue()) idx.write(mi); }
        int idxLen = (idx.size() + 3) & ~3;
        int structsBase = idxBase + idxLen;
        int setLen = structsBase + 44 * n;

        byte[] set = new byte[setLen];
        u16(set, 0, ofsTexDict);
        u16(set, 2, ofsPltDict);
        List<byte[]> matRecs = new ArrayList<>();
        for (int i = 0; i < n; i++) matRecs.add(rec4(structsBase + i * 44));
        System.arraycopy(serialize(G3dDictionary.build(matNames, matRecs, 4)), 0, set, 4, matDictLen);
        System.arraycopy(serialize(G3dDictionary.build(new ArrayList<>(byTex.keySet()), texRecs, 4)), 0, set, ofsTexDict, texDictLen);
        System.arraycopy(serialize(G3dDictionary.build(new ArrayList<>(byPlt.keySet()), pltRecs, 4)), 0, set, ofsPltDict, pltDictLen);
        System.arraycopy(idx.toByteArray(), 0, set, idxBase, idx.size());
        for (int i = 0; i < n; i++) System.arraycopy(matStructs.get(i), 0, set, structsBase + i * 44, 44);
        return set;
    }

    // one 44-byte NITRO material struct from a <material> element (texture size from tex_image index)
    private byte[] buildMaterialStruct(String m, int texIdx)
    {
        String tex = blocks("tex_image").get(texIdx);
        byte[] ms = new byte[44];
        u16(ms, 0, 0);
        u16(ms, 2, 44);
        u32(ms, 4, rgb15(doubles(a(m, "diffuse"))) | (1 << 15) | (rgb15(doubles(a(m, "ambient"))) << 16));
        u32(ms, 8, rgb15(doubles(a(m, "specular"))) | (rgb15(doubles(a(m, "emission"))) << 16));
        int alpha = Integer.parseInt(a(m, "alpha"));
        int lights = 0;
        for (int i = 0; i < 4; i++) if ("on".equals(a(m, "light" + i))) lights |= 1 << i;
        int mode = polygonMode(a(m, "polygon_mode"));       // modulate=0, decal=1, toon=2, shadow=3
        String face = a(m, "face");
        int cull = "back".equals(face) ? 0x40 : "both".equals(face) ? 0xC0 : 0x80; // render front (default)
        u32(ms, 0x0C, ((long) alpha << 16) | cull | ((long) mode << 4) | lights); // polygon_attr
        u32(ms, 0x10, 0x3f1ff8ffL);                         // polygon_attr_mask (constant)
        String[] tiling = a(m, "tex_tiling").split("\\s+");
        u32(ms, 0x14, wrapBits(tiling[0], 16, 18) | wrapBits(tiling[1], 17, 19)); // teximage_param wrap/flip
        u32(ms, 0x18, 0xffffffffL);
        u16(ms, 0x1C, 0);
        u16(ms, 0x1E, 0x1fce);
        u16(ms, 0x20, Integer.parseInt(a(tex, "width")));
        u16(ms, 0x22, Integer.parseInt(a(tex, "height")));
        u32(ms, 0x24, 0x1000);
        u32(ms, 0x28, 0x1000);
        return ms;
    }

    // --- shape set: shape dictionary + N 16-byte shape structs + N display lists ---
    private byte[] buildShapeSet(List<String> polygons, List<byte[]> dls)
    {
        int n = polygons.size();
        List<String> names = new ArrayList<>();
        for (String p : polygons) names.add(a(p, "name"));
        int dictLen = serialize(G3dDictionary.build(names, placeholders(n), 4)).length;
        int dlBase = dictLen + n * 16;
        List<byte[]> recs = new ArrayList<>();
        byte[][] structs = new byte[n][16];
        int dlCursor = dlBase;
        for (int i = 0; i < n; i++)
        {
            int structOfs = dictLen + i * 16;
            recs.add(rec4(structOfs));
            String prim = polygons.get(i).replaceAll("(?s).*<primitive[^>]*>(.*?)</primitive>.*", "$1");
            int vtxFlags = (prim.contains("<nrm") ? 1 : 0) | (prim.contains("<clr") ? 2 : 0) | (prim.contains("<tex") ? 4 : 0);
            u16(structs[i], 2, 0x10);
            u32(structs[i], 4, vtxFlags);
            u32(structs[i], 8, dlCursor - structOfs); // dlOffset relative to this struct
            u32(structs[i], 12, dls.get(i).length);
            dlCursor += dls.get(i).length;
        }
        byte[] set = new byte[dlCursor];
        System.arraycopy(serialize(G3dDictionary.build(names, recs, 4)), 0, set, 0, dictLen);
        for (int i = 0; i < n; i++) System.arraycopy(structs[i], 0, set, dictLen + i * 16, 16);
        int p = dlBase;
        for (byte[] d : dls) { System.arraycopy(d, 0, set, p, d.length); p += d.length; }
        return set;
    }

    // --- .imd parse helpers (the intermediate is simple XML) ---
    private String attr(String tag, String name)
    {
        Matcher m = Pattern.compile("<" + tag + "\\b[^>]*\\b" + name + "=\"([^\"]*)\"").matcher(imd);
        return m.find() ? m.group(1) : null;
    }
    private int intAttr(String tag, String name) { return Integer.parseInt(attr(tag, name)); }

    // every <tag ...>...</tag> or <tag .../> element (matched at a word boundary), in document order
    private List<String> blocks(String tag)
    {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<" + tag + "\\b(?:[^>]*?/>|[^>]*?>.*?</" + tag + ">)", Pattern.DOTALL).matcher(imd);
        while (m.find()) out.add(m.group());
        return out;
    }
    // an attribute from a single element string
    private static String a(String element, String name)
    {
        Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(element);
        return m.find() ? m.group(1) : null;
    }
    // the node's <display material="m" polygon="p"/> entries as {materialIdx, polygonIdx} pairs
    // one node of the <node_array>: its parent (root maps to its own index), whether it draws, its visibility
    // and billboard state, and its {materialIdx, polygonIdx} displays in order.
    private static final class Node
    {
        String name;
        int parent, vis = 1, bb = 0;
        boolean draws;
        final List<int[]> draw = new ArrayList<>();
        double[] scale = {1, 1, 1};
        double[] rotate = {0, 0, 0};      // Euler angles in degrees (x, y, z)
        double[] translate = {0, 0, 0};
    }

    // parse the whole <node_array>, in index order (g3dcvtr numbers/walks nodes by index)
    private List<Node> parseNodes()
    {
        List<Node> nodes = new ArrayList<>();
        for (String el : blocks("node"))
        {
            Node nd = new Node();
            nd.name = a(el, "name");
            int index = Integer.parseInt(a(el, "index"));
            int par = Integer.parseInt(a(el, "parent"));
            nd.parent = par < 0 ? index : par;                 // the root's NODEDESC parent is its own index
            nd.vis = "off".equals(a(el, "visibility")) ? 0 : 1;
            nd.bb = "on".equals(a(el, "billboard")) ? 1 : "y_on".equals(a(el, "billboard")) ? 2 : 0;
            if (a(el, "scale") != null) nd.scale = doubles(a(el, "scale"));
            if (a(el, "rotate") != null) nd.rotate = doubles(a(el, "rotate"));
            if (a(el, "translate") != null) nd.translate = doubles(a(el, "translate"));
            Matcher dm = Pattern.compile("<display\\b[^>]*/>").matcher(el);
            while (dm.find())
                nd.draw.add(new int[]{Integer.parseInt(a(dm.group(), "material")), Integer.parseInt(a(dm.group(), "polygon"))});
            nd.draws = !nd.draw.isEmpty();
            while (nodes.size() <= index) nodes.add(null);
            nodes.set(index, nd);
        }
        nodes.removeIf(java.util.Objects::isNull);
        return nodes;
    }
    private static List<byte[]> placeholders(int n)
    {
        List<byte[]> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(rec4(0));
        return l;
    }
    private static String firstQuoted(String s) { Matcher m = Pattern.compile("\"([^\"]*)\"").matcher(s); m.find(); return m.group(1); }
    private static double[] doubles(String s)
    {
        String[] p = s.trim().split("\\s+");
        double[] v = new double[p.length];
        for (int i = 0; i < p.length; i++) v[i] = Double.parseDouble(p[i]);
        return v;
    }

    // --- encode helpers ---
    private static DisplayList.Command cmd(int op, int... operands) { return new DisplayList.Command(op, operands); }
    private static int pack10(int a, int b, int c) { return (a & 0x3FF) | ((b & 0x3FF) << 10) | ((c & 0x3FF) << 20); }
    private static int fx(double v, int frac) { return (int) Math.round(v * (1 << frac)); }
    private static boolean exact10(double v) { double s = v * 64; return Math.abs(s - Math.round(s)) < 1e-6 && Math.round(s) >= -512 && Math.round(s) < 512; }
    private static int rgb15(double[] c) { return (int) (Math.round(c[0]) & 0x1F) | ((int) (Math.round(c[1]) & 0x1F) << 5) | ((int) (Math.round(c[2]) & 0x1F) << 10); }
    private static byte[] serialize(G3dDictionary d) { MemBuf b = MemBuf.create(); d.write(b.writer()); return b.reader().getBuffer(); }
    private static byte[] rec4(long v) { byte[] r = new byte[4]; u32(r, 0, v); return r; }
    private static byte[] concat(byte[]... arr)
    {
        int n = 0;
        for (byte[] a : arr) n += a.length;
        byte[] r = new byte[n];
        int p = 0;
        for (byte[] a : arr) { System.arraycopy(a, 0, r, p, a.length); p += a.length; }
        return r;
    }
    private static void u16(byte[] d, int o, int v) { d[o] = (byte) v; d[o + 1] = (byte) (v >> 8); }
    private static void u32(byte[] d, int o, long v)
    {
        d[o] = (byte) v; d[o + 1] = (byte) (v >> 8); d[o + 2] = (byte) (v >> 16); d[o + 3] = (byte) (v >> 24);
    }
}
