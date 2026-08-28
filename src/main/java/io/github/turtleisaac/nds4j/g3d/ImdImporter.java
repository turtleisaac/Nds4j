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
 * Verified byte-identical to {@code g3dcvtr -emdl} on its own sample models. Current coverage is the
 * single-node / single-material / single-shape textured model (billboard or not, hardware-lit or
 * vertex-coloured); multi-node/material/shape models extend the same section encoders. The geometry,
 * resource dictionaries and container are produced by the byte-exact primitives ({@link DisplayList},
 * {@link G3dDictionary}, {@link G3dFile}); this class adds the {@code .imd} parse and the MDL0 struct
 * encoders (model header/box, node, SBC render stream, material).
 */
public final class ImdImporter
{
    private final String imd;

    private ImdImporter(String imd) { this.imd = imd; }

    /**
     * Translates {@code .imd} source into a model-only NSBMD (the {@code g3dcvtr -emdl} equivalent).
     * @param imdXml the full contents of an {@code .imd} file
     * @param modelName the model's name (g3dcvtr uses the source file's base name)
     * @return the NSBMD file bytes, byte-identical to g3dcvtr for the supported model class
     */
    public static byte[] toNsbmd(String imdXml, String modelName)
    {
        return new ImdImporter(imdXml).build(modelName);
    }

    private byte[] build(String modelName)
    {
        int posScale = 1 << (int) Double.parseDouble(attr("model_info", "pos_scale"));
        double[] boxXyz = doubles(attr("box_test", "xyz"));
        double[] boxWhd = doubles(attr("box_test", "whd"));
        int boxScale = 1 << (int) Double.parseDouble(attr("box_test", "pos_scale"));
        int numVtx = intAttr("output_info", "vertex_size");
        int numPoly = intAttr("output_info", "polygon_size");
        int numTri = intAttr("output_info", "triangle_size");
        int numQuad = intAttr("output_info", "quad_size");
        String nodeName = attr("node", "name");
        String matName = attr("material", "name");
        String polyName = attr("polygon", "name");

        byte[] dl = buildDisplayList();
        byte[] nodeSet = buildNodeSet(nodeName);
        byte[] sbc = buildSbc();
        byte[] matSet = buildMaterialSet(matName);
        byte[] shapeSet = buildShapeSet(polyName, dl);

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
        model[info + 3] = 1;                 // numNode
        model[info + 4] = 1;                 // matCount
        model[info + 5] = 1;                 // shapeCount
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
        return G3dFile.assembleContainer("BMD0", 2, mdl0);
    }

    // --- geometry: translate the polygon's primitive commands into GPU commands, pad the DL to /8 ---
    private byte[] buildDisplayList()
    {
        List<DisplayList.Command> cmds = new ArrayList<>();
        Matcher pm = Pattern.compile("<primitive [^>]*type=\"(\\w+)\"[^>]*>(.*?)</primitive>", Pattern.DOTALL).matcher(imd);
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
    private byte[] buildNodeSet(String nodeName)
    {
        byte[] dict = serialize(G3dDictionary.build(List.of(nodeName), List.of(rec4(40)), 4));
        byte[] nodeStruct = {0x07, (byte) 0xf8, 0x00, 0x10}; // identity T/R/S; rot00 = 1.0
        return concat(dict, nodeStruct);
    }

    // --- SBC render stream: NODEDESC, NODE, [BB if billboard], POSSCALE, MAT, SHP, POSSCALE|end, RET ---
    private byte[] buildSbc()
    {
        boolean billboard = "on".equals(attr("node", "billboard"));
        int vis = "off".equals(attr("node", "visibility")) ? 0 : 1;
        ByteArrayOutputStream sb = new ByteArrayOutputStream();
        sb.write(0x06); sb.write(0); sb.write(0); sb.write(0); // NODEDESC(node 0, parent 0, opt 0)
        sb.write(0x02); sb.write(0); sb.write(vis);            // NODE(node 0, visibility)
        if (billboard) { sb.write(0x07); sb.write(0); }        // BB(node 0)
        sb.write(0x0b);                                        // POSSCALE (begin)
        sb.write(0x04); sb.write(0);                           // MAT(material 0)
        sb.write(0x05); sb.write(0);                           // SHP(shape 0)
        sb.write(0x2b);                                        // POSSCALE | 0x20 (end)
        sb.write(0x01);                                        // RET
        while (sb.size() % 4 != 0) sb.write(0);
        return sb.toByteArray();
    }

    // --- material set: header, material dict, tex/pltt->material dicts, index lists, material struct ---
    private byte[] buildMaterialSet(String matName)
    {
        byte[] ms = new byte[44];
        u16(ms, 0, 0);
        u16(ms, 2, 44);                                       // sizeof
        u32(ms, 4, rgb15(doubles(attr("material", "diffuse"))) | (1 << 15) | (rgb15(doubles(attr("material", "ambient"))) << 16));
        u32(ms, 8, rgb15(doubles(attr("material", "specular"))) | (rgb15(doubles(attr("material", "emission"))) << 16));
        int alpha = intAttr("material", "alpha");
        int lights = 0;
        for (int i = 0; i < 4; i++) if ("on".equals(attr("material", "light" + i))) lights |= 1 << i;
        u32(ms, 0x0C, ((long) alpha << 16) | 0x80 | lights);  // polygon_attr (modulate, render front)
        u32(ms, 0x10, 0x3f1ff8ffL);                           // polygon_attr_mask
        u32(ms, 0x14, (1L << 16) | (1L << 17));               // teximage_param: repeat S/T
        u32(ms, 0x18, 0xffffffffL);                           // unknown3
        u16(ms, 0x1C, 0);                                     // pltt_base
        u16(ms, 0x1E, 0x1fce);                                // misc (no texture matrix)
        u16(ms, 0x20, intAttr("tex_image", "width"));
        u16(ms, 0x22, intAttr("tex_image", "height"));
        u32(ms, 0x24, 0x1000);                                // unknown5 = 1.0
        u32(ms, 0x28, 0x1000);                                // unknown6 = 1.0

        String texName = attr("tex_image", "name");
        String pltName = attr("tex_palette", "name");
        int matDictLen = serialize(G3dDictionary.build(List.of(matName), List.of(rec4(0)), 4)).length;
        int texDictLen = serialize(G3dDictionary.build(List.of(texName), List.of(rec4(0)), 4)).length;
        int pltDictLen = serialize(G3dDictionary.build(List.of(pltName), List.of(rec4(0)), 4)).length;
        int ofsTexDict = 4 + matDictLen;
        int ofsPltDict = ofsTexDict + texDictLen;
        int idxBase = ofsPltDict + pltDictLen;                // 4-byte index-list region
        int structOfs = idxBase + 4;
        byte[] set = new byte[structOfs + 44];
        u16(set, 0, ofsTexDict);
        u16(set, 2, ofsPltDict);
        System.arraycopy(serialize(G3dDictionary.build(List.of(matName), List.of(rec4(structOfs)), 4)), 0, set, 4, matDictLen);
        System.arraycopy(serialize(G3dDictionary.build(List.of(texName), List.of(rec4((idxBase & 0xFFFF) | (1 << 16))), 4)), 0, set, ofsTexDict, texDictLen);
        System.arraycopy(serialize(G3dDictionary.build(List.of(pltName), List.of(rec4(((idxBase + 1) & 0xFFFF) | (1 << 16))), 4)), 0, set, ofsPltDict, pltDictLen);
        set[idxBase] = 0;     // texture -> material 0
        set[idxBase + 1] = 0; // palette -> material 0
        System.arraycopy(ms, 0, set, structOfs, 44);
        return set;
    }

    // --- shape set: shape dictionary + 16-byte shape struct (vtx-attribute mask + DL pointer) + the DL ---
    private byte[] buildShapeSet(String polyName, byte[] dl)
    {
        byte[] dict = serialize(G3dDictionary.build(List.of(polyName), List.of(rec4(40)), 4));
        byte[] shapeStruct = new byte[16];
        String prim = imd.replaceAll("(?s).*<primitive index=\"0\"[^>]*>(.*?)</primitive>.*", "$1");
        int vtxFlags = (prim.contains("<nrm") ? 1 : 0) | (prim.contains("<clr") ? 2 : 0) | (prim.contains("<tex") ? 4 : 0);
        u16(shapeStruct, 0, 0);
        u16(shapeStruct, 2, 0x10);       // sizeof
        u32(shapeStruct, 4, vtxFlags);   // vertex-attribute mask: normal(1) | color(2) | texcoord(4)
        u32(shapeStruct, 8, 16);         // dlOffset (relative to the shape struct)
        u32(shapeStruct, 12, dl.length); // dlSize
        return concat(dict, shapeStruct, dl);
    }

    // --- .imd parse helpers (the intermediate is simple XML) ---
    private String attr(String tag, String name)
    {
        Matcher m = Pattern.compile("<" + tag + "\\b[^>]*\\b" + name + "=\"([^\"]*)\"").matcher(imd);
        return m.find() ? m.group(1) : null;
    }
    private int intAttr(String tag, String name) { return Integer.parseInt(attr(tag, name)); }
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
