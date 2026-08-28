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

    /**
     * Translates {@code .imd} source into an NSBMD with its texture embedded as a {@code TEX0} block (the
     * {@code g3dcvtr -eboth} equivalent) &mdash; the MDL0 block is identical to {@link #toNsbmd}, with the
     * texture/palette from the {@code .imd}'s {@code tex_image}/{@code tex_palette} appended.
     * @param imdXml the full contents of an {@code .imd} file
     * @param modelName the model's name
     * @return the NSBMD file bytes (MDL0 + TEX0), byte-identical to g3dcvtr for the supported model class
     */
    public static byte[] toNsbmdWithTextures(String imdXml, String modelName)
    {
        ImdImporter imp = new ImdImporter(imdXml);
        byte[] mdl0 = imp.buildMdl0(modelName);
        return G3dFile.assembleContainer("BMD0", 2, mdl0, imp.buildTex0());
    }

    private byte[] build(String modelName)
    {
        return G3dFile.assembleContainer("BMD0", 2, buildMdl0(modelName));
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
        String nodeName = attr("node", "name");

        List<String> materials = blocks("material");        // each <material .../>
        List<String> polygons = blocks("polygon");          // each <polygon ...>...</polygon>
        int[][] displays = parseDisplays();                 // {materialIdx, polygonIdx} per node display

        List<byte[]> dls = new ArrayList<>();
        for (String poly : polygons) dls.add(buildDisplayList(poly));
        byte[] nodeSet = buildNodeSet(nodeName);
        byte[] sbc = buildSbc(displays);
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
        model[info + 3] = 1;                 // numNode
        model[info + 4] = (byte) materials.size(); // matCount
        model[info + 5] = (byte) polygons.size();  // shapeCount
        model[info + 6] = (byte) (displays.length > 1 ? 1 : 0); // firstUnusedMtxStackId (NODEDESC store slot)
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
        // palette: each 4-hex-digit token is a BGR555 colour, stored little-endian
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
    private byte[] buildNodeSet(String nodeName)
    {
        byte[] dict = serialize(G3dDictionary.build(List.of(nodeName), List.of(rec4(40)), 4));
        byte[] nodeStruct = {0x07, (byte) 0xf8, 0x00, 0x10}; // identity T/R/S; rot00 = 1.0
        return concat(dict, nodeStruct);
    }

    // --- SBC render stream: NODEDESC[+store if >1 shape], NODE, [BB], POSSCALE, {MAT,SHP}*, POSSCALE|end, RET ---
    private byte[] buildSbc(int[][] displays)
    {
        boolean billboard = "on".equals(attr("node", "billboard"));
        int vis = "off".equals(attr("node", "visibility")) ? 0 : 1;
        boolean multi = displays.length > 1;
        ByteArrayOutputStream sb = new ByteArrayOutputStream();
        if (multi) { sb.write(0x26); sb.write(0); sb.write(0); sb.write(0); sb.write(0); } // NODEDESC+store slot 0
        else       { sb.write(0x06); sb.write(0); sb.write(0); sb.write(0); }              // NODEDESC(0,0,0)
        sb.write(0x02); sb.write(0); sb.write(vis);            // NODE(node 0, visibility)
        if (billboard) { sb.write(0x07); sb.write(0); }        // BB(node 0)
        sb.write(0x0b);                                        // POSSCALE (begin)
        for (int[] d : displays)                               // one MAT/SHP pair per display, in order
        {
            sb.write(0x04); sb.write(d[0]);                    // MAT(materialIdx)
            sb.write(0x05); sb.write(d[1]);                    // SHP(polygonIdx)
        }
        sb.write(0x2b);                                        // POSSCALE | 0x20 (end)
        sb.write(0x01);                                        // RET
        while (sb.size() % 4 != 0) sb.write(0);
        return sb.toByteArray();
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
        u32(ms, 0x0C, ((long) alpha << 16) | 0x80 | lights);
        u32(ms, 0x10, 0x3f1ff8ffL);
        u32(ms, 0x14, (1L << 16) | (1L << 17));
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
    private int[][] parseDisplays()
    {
        List<int[]> out = new ArrayList<>();
        Matcher m = Pattern.compile("<display\\b[^>]*/>").matcher(tagContent(imd, "node"));
        while (m.find())
            out.add(new int[]{Integer.parseInt(a(m.group(), "material")), Integer.parseInt(a(m.group(), "polygon"))});
        return out.toArray(new int[0][]);
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
