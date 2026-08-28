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

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Authors a complete <b>NSBMD</b> model file from a plain triangle mesh &mdash; the reusable, library-level
 * front door to the source&rarr;NSB* pipeline. It composes the geometry encoder ({@link DisplayList#encode}),
 * the NNS dictionary builder ({@link G3dDictionary#build}) and the container assembler
 * ({@link G3dFile#assembleContainer}) into a single call, and it does the two things a raw
 * {@code DisplayList.encode} caller still has to get right on their own: it picks a power-of-two
 * {@code posScale} so arbitrarily-sized geometry fits the VTX_16 fixed-point range, and it computes the
 * header bounding box the placement oracle checks. The result is read back by the production
 * {@link ModelSet}.
 * <p>
 * This builds a single-node, single-shape, untextured model &mdash; the geometry backbone shared by every
 * imported mesh (see {@link ObjImporter}). Multi-node / multi-material / textured authoring layers on top of
 * the same primitives.
 */
public final class ModelBuilder
{
    private ModelBuilder() {}

    /**
     * Authors an untextured NSBMD around a triangle mesh.
     * @param modelName the model's dictionary name (e.g. {@code "model0"})
     * @param positions vertex positions (x,y,z triples), in model units
     * @param triangles triangle indices (3 per triangle) into {@code positions}
     * @return the NSBMD file bytes, ready for {@link ModelSet}
     */
    public static byte[] buildUntextured(String modelName, float[] positions, int[] triangles)
    {
        if (triangles.length < 3 || triangles.length % 3 != 0)
            throw new IllegalArgumentException("triangles must be a non-empty multiple of 3");

        // Choose a power-of-two posScale so the largest coordinate fits the signed VTX_16 range (|raw| < 8),
        // then feed the encoder pre-scaled ("raw") positions; the decoder multiplies posScale back in.
        double maxAbs = 0;
        for (float v : positions) maxAbs = Math.max(maxAbs, Math.abs(v));
        int posScale = powerOfTwoScale(maxAbs);
        float[] raw = new float[positions.length];
        for (int i = 0; i < positions.length; i++) raw[i] = (float) (positions[i] / posScale);

        byte[] dl = DisplayList.encode(raw, null, triangles);
        int numTriangle = triangles.length / 3;
        int numVertex = numTriangle * 3;   // the encoder emits one vertex per triangle corner

        // Header bounding box (min corner + dimensions), stored 1.3.12 scaled by a power-of-two boxPosScale.
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < positions.length; i += 3)
            for (int c = 0; c < 3; c++)
            {
                min[c] = Math.min(min[c], positions[i + c]);
                max[c] = Math.max(max[c], positions[i + c]);
            }
        double boxMag = 0;
        for (int c = 0; c < 3; c++) boxMag = Math.max(boxMag, Math.max(Math.abs(min[c]), Math.abs(max[c] - min[c])));
        int boxPosScale = powerOfTwoScale(boxMag);

        return assemble(modelName, dl, numVertex, numTriangle, posScale, min, max, boxPosScale);
    }

    /**
     * Authors a <b>textured</b> NSBMD around a triangle mesh: a single node, single shape, one material
     * bound to a single direct-colour texture embedded as a {@code TEX0} block in the same file (as most
     * retail models do). The production {@link ModelSet} decodes it and {@link ModelSet#getEmbeddedTextures}
     * returns the texture; the mesh's material resolves to it by name.
     * @param modelName the model's dictionary name
     * @param positions vertex positions (x,y,z triples), in model units
     * @param texelUV texcoords in <em>texel</em> units (see {@link ObjImporter#texcoordsInTexels}), one
     *                (u,v) per vertex
     * @param triangles triangle indices (3 per triangle) into {@code positions}
     * @param texture the texture image to embed (encoded as direct BGR555)
     * @return the NSBMD file bytes (MDL0 + TEX0), ready for {@link ModelSet}
     */
    public static byte[] buildTextured(String modelName, float[] positions, float[] texelUV, int[] triangles,
                                       BufferedImage texture)
    {
        if (triangles.length < 3 || triangles.length % 3 != 0)
            throw new IllegalArgumentException("triangles must be a non-empty multiple of 3");

        double maxAbs = 0;
        for (float v : positions) maxAbs = Math.max(maxAbs, Math.abs(v));
        int posScale = powerOfTwoScale(maxAbs);
        float[] raw = new float[positions.length];
        for (int i = 0; i < positions.length; i++) raw[i] = (float) (positions[i] / posScale);

        byte[] dl = DisplayList.encode(raw, texelUV, triangles);
        int numTriangle = triangles.length / 3;
        int numVertex = numTriangle * 3;

        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < positions.length; i += 3)
            for (int c = 0; c < 3; c++)
            {
                min[c] = Math.min(min[c], positions[i + c]);
                max[c] = Math.max(max[c], positions[i + c]);
            }
        double boxMag = 0;
        for (int c = 0; c < 3; c++) boxMag = Math.max(boxMag, Math.max(Math.abs(min[c]), Math.abs(max[c] - min[c])));
        int boxPosScale = powerOfTwoScale(boxMag);

        byte[] tex0 = buildDirectTex0("tex0", texture);
        byte[] matSet = buildTexturedMaterialSet("mat0", "tex0", texture.getWidth(), texture.getHeight());
        // SBC: NODEDESC(0,0,0), MAT(0), SHP(0), RET
        byte[] sbc = {0x06, 0x00, 0x00, 0x00,  0x04, 0x00,  0x05, 0x00,  0x01};

        byte[] mdl0 = assembleMdl0(modelName, dl, numVertex, numTriangle, posScale, min, max, boxPosScale, matSet, sbc);
        return G3dFile.assembleContainer("BMD0", 1, mdl0, tex0);
    }

    /** One textured shape of a multi-part model: its own geometry, texel-unit UVs and texture. */
    public static final class Part
    {
        final String name;
        final float[] positions;
        final float[] texelUV;
        final int[] triangles;
        final BufferedImage texture;

        /**
         * @param name the shape/material/texture base name (must be unique within the model)
         * @param positions vertex positions (x,y,z triples), in model units
         * @param texelUV texcoords in texel units (see {@link ObjImporter#texcoordsInTexels})
         * @param triangles triangle indices (3 per triangle)
         * @param texture the part's texture (embedded as direct BGR555)
         */
        public Part(String name, float[] positions, float[] texelUV, int[] triangles, BufferedImage texture)
        {
            this.name = name; this.positions = positions; this.texelUV = texelUV;
            this.triangles = triangles; this.texture = texture;
        }
    }

    /**
     * Authors a <b>multi-shape, multi-material, multi-texture</b> NSBMD: each {@link Part} becomes its own
     * shape drawn under a shared identity node, with its own material bound to its own texture, all embedded
     * in one {@code TEX0} block &mdash; the shape of a real, richer model. The production {@link ModelSet}
     * reads back every shape, material and texture.
     * @param modelName the model's dictionary name
     * @param parts the textured parts (each part's names must be unique)
     * @return the NSBMD file bytes (MDL0 + TEX0)
     */
    public static byte[] buildMultiTextured(String modelName, List<Part> parts)
    {
        if (parts.isEmpty()) throw new IllegalArgumentException("need at least one part");

        // Global posScale and bounding box over every part's geometry.
        double maxAbs = 0;
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (Part p : parts)
            for (int i = 0; i < p.positions.length; i += 3)
                for (int c = 0; c < 3; c++)
                {
                    float v = p.positions[i + c];
                    maxAbs = Math.max(maxAbs, Math.abs(v));
                    min[c] = Math.min(min[c], v);
                    max[c] = Math.max(max[c], v);
                }
        int posScale = powerOfTwoScale(maxAbs);
        double boxMag = 0;
        for (int c = 0; c < 3; c++) boxMag = Math.max(boxMag, Math.max(Math.abs(min[c]), Math.abs(max[c] - min[c])));
        int boxPosScale = powerOfTwoScale(boxMag);

        List<byte[]> dls = new java.util.ArrayList<>();
        List<String> shapeNames = new java.util.ArrayList<>();
        List<String> matNames = new java.util.ArrayList<>();
        List<String> texNames = new java.util.ArrayList<>();
        List<BufferedImage> textures = new java.util.ArrayList<>();
        int totalV = 0, totalT = 0;
        ByteArrayOutputStream sbc = new ByteArrayOutputStream();
        sbc.write(0x06); sbc.write(0); sbc.write(0); sbc.write(0);   // NODEDESC(0,0,0)
        for (int i = 0; i < parts.size(); i++)
        {
            Part p = parts.get(i);
            float[] raw = new float[p.positions.length];
            for (int k = 0; k < raw.length; k++) raw[k] = (float) (p.positions[k] / posScale);
            dls.add(DisplayList.encode(raw, p.texelUV, p.triangles));
            shapeNames.add("shp_" + p.name);
            matNames.add("mat_" + p.name);
            texNames.add("tex_" + p.name);
            textures.add(p.texture);
            totalT += p.triangles.length / 3;
            totalV += p.triangles.length; // 3 emitted vertices per triangle
            sbc.write(0x04); sbc.write(i);   // MAT(i)
            sbc.write(0x05); sbc.write(i);   // SHP(i)
        }
        sbc.write(0x01); // RET

        byte[] tex0 = buildMultiDirectTex0(texNames, textures);
        byte[] matSet = buildMultiMaterialSet(matNames, texNames, textures);
        byte[] mdl0 = assembleMdl0(modelName, dls, shapeNames, parts.size(), totalV, totalT,
                posScale, min, max, boxPosScale, matSet, sbc.toByteArray());
        return G3dFile.assembleContainer("BMD0", 1, mdl0, tex0);
    }

    // Smallest power of two P such that maxAbs / P < 8 (so a coord fits the signed 1.3.12 VTX_16 range).
    private static int powerOfTwoScale(double maxAbs)
    {
        int p = 1;
        while (maxAbs / p >= 7.999) p <<= 1;
        return p;
    }

    // Builds the empty (untextured) material set: no materials, empty tex/pltt dictionaries.
    private static byte[] emptyMaterialSet()
    {
        byte[] matDict = serialize(G3dDictionary.build(List.of(), List.of(), 4));
        byte[] texDict = serialize(G3dDictionary.build(List.of(), List.of(), 4));
        byte[] plttDict = serialize(G3dDictionary.build(List.of(), List.of(), 4));
        int ofsTexDict = 4 + matDict.length, ofsPltDict = ofsTexDict + texDict.length;
        byte[] matSet = new byte[ofsPltDict + plttDict.length];
        u16(matSet, 0, ofsTexDict);
        u16(matSet, 2, ofsPltDict);
        System.arraycopy(matDict, 0, matSet, 4, matDict.length);
        System.arraycopy(texDict, 0, matSet, ofsTexDict, texDict.length);
        System.arraycopy(plttDict, 0, matSet, ofsPltDict, plttDict.length);
        return matSet;
    }

    private static byte[] assemble(String modelName, byte[] dl, int numVertex, int numTriangle,
                                   int posScale, float[] boxMin, float[] boxMax, int boxPosScale)
    {
        byte[] sbc = {0x06, 0x00, 0x00, 0x00,  0x05, 0x00,  0x01}; // NODEDESC(0,0,0), SHP(0), RET
        byte[] mdl0 = assembleMdl0(modelName, dl, numVertex, numTriangle, posScale, boxMin, boxMax,
                boxPosScale, emptyMaterialSet(), sbc);
        return G3dFile.assembleContainer("BMD0", 2, mdl0);
    }

    // Single-shape MDL0 (one material count = 0 or 1, one shape).
    private static byte[] assembleMdl0(String modelName, byte[] dl, int numVertex, int numTriangle,
                                       int posScale, float[] boxMin, float[] boxMax, int boxPosScale,
                                       byte[] matSet, byte[] sbc)
    {
        return assembleMdl0(modelName, List.of(dl), List.of("shape0"), countMaterials(matSet), numVertex,
                numTriangle, posScale, boxMin, boxMax, boxPosScale, matSet, sbc);
    }

    // The material count is the size of the material set's material dictionary (at matSet+4).
    private static int countMaterials(byte[] matSet)
    {
        MemBuf b = MemBuf.create(matSet);
        MemBuf.MemBufReader r = b.reader();
        r.setPosition(4);
        return new G3dDictionary(r).size();
    }

    // Lays out one MDL0 block around N shape display lists, a caller-supplied material set and SBC stream.
    private static byte[] assembleMdl0(String modelName, List<byte[]> dls, List<String> shapeNames,
                                       int matCount, int numVertex, int numTriangle,
                                       int posScale, float[] boxMin, float[] boxMax, int boxPosScale,
                                       byte[] matSet, byte[] sbc)
    {
        int shapeCount = dls.size();
        byte[] nodeDict = serialize(G3dDictionary.build(List.of("node0"), List.of(rec4(40)), 4));
        byte[] nodeData = {0x07, 0x00, 0x00, 0x10}; // flags 7 (identity node): skip t/r/s, rotation[0][0]=1.0
        int nodeSetLen = nodeDict.length + nodeData.length;
        int matSetLen = matSet.length;

        // Shape set: dictionary + N 16-byte structs + N display lists. Each struct's dlOffset is relative
        // to that struct; measure the dictionary length first so all offsets are exact.
        List<byte[]> dummyRecords = new java.util.ArrayList<>();
        for (int i = 0; i < shapeCount; i++) dummyRecords.add(rec4(0));
        int shapeDictLen = serialize(G3dDictionary.build(shapeNames, dummyRecords, 4)).length;

        List<byte[]> shapeRecords = new java.util.ArrayList<>();
        int structsBase = shapeDictLen;
        int dlBase = shapeDictLen + shapeCount * 16;
        int dlCursor = dlBase;
        byte[][] structs = new byte[shapeCount][16];
        for (int i = 0; i < shapeCount; i++)
        {
            int structOfs = structsBase + i * 16;
            shapeRecords.add(rec4(structOfs));
            u32(structs[i], 8, dlCursor - structOfs);   // dlOffset (relative to the struct)
            u32(structs[i], 12, dls.get(i).length);     // dlSize
            dlCursor += dls.get(i).length;
        }
        byte[] shapeDict = serialize(G3dDictionary.build(shapeNames, shapeRecords, 4));
        int shapeSetLen = dlCursor;

        int headerLen = 0x40;
        int ofsSbc = headerLen + nodeSetLen;
        int ofsMat = ofsSbc + sbc.length;
        int ofsShp = ofsMat + matSetLen;
        int modelLen = ofsShp + shapeSetLen;
        byte[] model = new byte[modelLen];
        u32(model, 0, modelLen);
        u32(model, 4, ofsSbc);
        u32(model, 8, ofsMat);
        u32(model, 12, ofsShp);
        model[0x14 + 3] = 1;                    // numNode
        model[0x14 + 4] = (byte) matCount;      // matCount
        model[0x14 + 5] = (byte) shapeCount;    // shapeCount
        u32(model, 0x14 + 8, (long) posScale * 4096); // posScale (fx32)
        u16(model, 0x14 + 16, numVertex);
        u16(model, 0x14 + 20, numTriangle);
        for (int c = 0; c < 3; c++)
        {
            u16(model, 0x14 + 0x18 + c * 2, fx12(boxMin[c] / boxPosScale));            // box min corner
            u16(model, 0x14 + 0x1E + c * 2, fx12((boxMax[c] - boxMin[c]) / boxPosScale)); // box dimensions
        }
        u32(model, 0x14 + 0x24, (long) boxPosScale * 4096); // boxPosScale (fx32)
        System.arraycopy(nodeDict, 0, model, headerLen, nodeDict.length);
        System.arraycopy(nodeData, 0, model, headerLen + nodeDict.length, nodeData.length);
        System.arraycopy(sbc, 0, model, ofsSbc, sbc.length);
        System.arraycopy(matSet, 0, model, ofsMat, matSet.length);
        System.arraycopy(shapeDict, 0, model, ofsShp, shapeDict.length);
        for (int i = 0; i < shapeCount; i++)
            System.arraycopy(structs[i], 0, model, ofsShp + structsBase + i * 16, 16);
        int dlPos = ofsShp + dlBase;
        for (byte[] d : dls) { System.arraycopy(d, 0, model, dlPos, d.length); dlPos += d.length; }

        int modelStart = 8 + serialize(G3dDictionary.build(List.of(modelName), List.of(rec4(0)), 4)).length;
        byte[] modelDict = serialize(G3dDictionary.build(List.of(modelName), List.of(rec4(modelStart)), 4));
        int mdl0Len = (modelStart + model.length + 3) & ~3;
        byte[] mdl0 = new byte[mdl0Len];
        System.arraycopy("MDL0".getBytes(StandardCharsets.US_ASCII), 0, mdl0, 0, 4);
        u32(mdl0, 4, mdl0Len);
        System.arraycopy(modelDict, 0, mdl0, 8, modelDict.length);
        System.arraycopy(model, 0, mdl0, modelStart, model.length);

        return mdl0;
    }

    // Builds a TEX0 block holding one direct-colour (BGR555) texture, named `name`.
    private static byte[] buildDirectTex0(String name, BufferedImage img)
    {
        int w = img.getWidth(), h = img.getHeight();
        byte[] data = new byte[w * h * 2];
        for (int p = 0; p < w * h; p++)
        {
            int argb = img.getRGB(p % w, p / w);
            int r = ((argb >> 16) & 0xFF) >> 3, g = ((argb >> 8) & 0xFF) >> 3, b = (argb & 0xFF) >> 3;
            int v = r | (g << 5) | (b << 10) | 0x8000; // top bit = opaque
            data[p * 2] = (byte) v;
            data[p * 2 + 1] = (byte) (v >> 8);
        }
        int widthSel = Integer.numberOfTrailingZeros(w / 8);
        int heightSel = Integer.numberOfTrailingZeros(h / 8);
        long texImageParam = ((long) 7 << 26) | ((long) widthSel << 20) | ((long) heightSel << 23);
        byte[] texRecord = new byte[8];
        u32(texRecord, 0, texImageParam);
        byte[] texDict = serialize(G3dDictionary.build(List.of(name), List.of(texRecord), 8));
        byte[] plttDict = serialize(G3dDictionary.build(List.of(), List.of(), 4));

        int headerSize = 0x3C;
        int texOfsDict = headerSize;
        int plttOfsDict = texOfsDict + texDict.length;
        int texDataOfs = plttOfsDict + plttDict.length;
        int blockSize = (texDataOfs + data.length + 3) & ~3;

        byte[] block = new byte[blockSize];
        System.arraycopy("TEX0".getBytes(StandardCharsets.US_ASCII), 0, block, 0, 4);
        u32(block, 4, blockSize);
        u16(block, 12, data.length >> 3);   // sizeTex (8-byte units)
        u16(block, 14, texOfsDict);         // ofsDict
        u32(block, 20, texDataOfs);         // ofsData
        u16(block, 30, texOfsDict);         // Tex4x4Info dict (points at tex dict; no 4x4 data)
        u16(block, 52, plttOfsDict);        // PlttInfo dict
        u32(block, 56, texDataOfs);
        System.arraycopy(texDict, 0, block, texOfsDict, texDict.length);
        System.arraycopy(plttDict, 0, block, plttOfsDict, plttDict.length);
        System.arraycopy(data, 0, block, texDataOfs, data.length);
        return block;
    }

    // Builds a TEX0 block holding N direct-colour textures, packed sequentially into the texture data
    // section; each texture's texImageParam data-offset points at its texels (in 8-byte units).
    private static byte[] buildMultiDirectTex0(List<String> names, List<BufferedImage> imgs)
    {
        ByteArrayOutputStream texData = new ByteArrayOutputStream();
        List<byte[]> records = new java.util.ArrayList<>();
        for (int i = 0; i < imgs.size(); i++)
        {
            BufferedImage img = imgs.get(i);
            int w = img.getWidth(), h = img.getHeight();
            int dataOfsUnits = texData.size() >> 3;   // 8-byte units within the data section
            for (int p = 0; p < w * h; p++)
            {
                int argb = img.getRGB(p % w, p / w);
                int r = ((argb >> 16) & 0xFF) >> 3, g = ((argb >> 8) & 0xFF) >> 3, b = (argb & 0xFF) >> 3;
                int v = r | (g << 5) | (b << 10) | 0x8000;
                texData.write(v & 0xFF); texData.write((v >> 8) & 0xFF);
            }
            int widthSel = Integer.numberOfTrailingZeros(w / 8);
            int heightSel = Integer.numberOfTrailingZeros(h / 8);
            long texImageParam = (dataOfsUnits & 0xFFFFL) | ((long) 7 << 26)
                    | ((long) widthSel << 20) | ((long) heightSel << 23);
            byte[] rec = new byte[8];
            u32(rec, 0, texImageParam);
            records.add(rec);
        }
        byte[] data = texData.toByteArray();
        byte[] texDict = serialize(G3dDictionary.build(names, records, 8));
        byte[] plttDict = serialize(G3dDictionary.build(List.of(), List.of(), 4));

        int headerSize = 0x3C;
        int texOfsDict = headerSize;
        int plttOfsDict = texOfsDict + texDict.length;
        int texDataOfs = plttOfsDict + plttDict.length;
        int blockSize = (texDataOfs + data.length + 3) & ~3;

        byte[] block = new byte[blockSize];
        System.arraycopy("TEX0".getBytes(StandardCharsets.US_ASCII), 0, block, 0, 4);
        u32(block, 4, blockSize);
        u16(block, 12, data.length >> 3);
        u16(block, 14, texOfsDict);
        u32(block, 20, texDataOfs);
        u16(block, 30, texOfsDict);
        u16(block, 52, plttOfsDict);
        u32(block, 56, texDataOfs);
        System.arraycopy(texDict, 0, block, texOfsDict, texDict.length);
        System.arraycopy(plttDict, 0, block, plttOfsDict, plttDict.length);
        System.arraycopy(data, 0, block, texDataOfs, data.length);
        return block;
    }

    // Builds a material set with N materials, material i bound (by name) to texture i.
    private static byte[] buildMultiMaterialSet(List<String> matNames, List<String> texNames, List<BufferedImage> textures)
    {
        int n = matNames.size();
        List<byte[]> dummy = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) dummy.add(rec4(0));
        int matDictLen = serialize(G3dDictionary.build(matNames, dummy, 4)).length;
        int texDictLen = serialize(G3dDictionary.build(texNames, dummy, 4)).length;
        int plttDictLen = serialize(G3dDictionary.build(List.of(), List.of(), 4)).length;

        int structsBase = 4 + matDictLen;
        int matStructLen = 0x18;
        int ofsTexDict = structsBase + n * matStructLen;
        int ofsPltDict = ofsTexDict + texDictLen;
        int indexListBase = ofsPltDict + plttDictLen; // one byte per material: matIdx
        int matSetLen = (indexListBase + n + 3) & ~3;

        byte[] matSet = new byte[matSetLen];
        u16(matSet, 0, ofsTexDict);
        u16(matSet, 2, ofsPltDict);

        List<byte[]> matRecords = new java.util.ArrayList<>();
        List<byte[]> texRecords = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++)
        {
            int structOfs = structsBase + i * matStructLen;
            matRecords.add(rec4(structOfs));
            BufferedImage tex = textures.get(i);
            int widthSel = Integer.numberOfTrailingZeros(Math.max(8, tex.getWidth()) / 8);
            int heightSel = Integer.numberOfTrailingZeros(Math.max(8, tex.getHeight()) / 8);
            long texImageParam = ((long) 7 << 26) | ((long) widthSel << 20) | ((long) heightSel << 23)
                    | (1L << 16) | (1L << 17);
            u32(matSet, structOfs + 0x14, texImageParam);
            // texture i -> material i: index list is one byte [i] at indexListBase + i
            texRecords.add(rec4(((indexListBase + i) & 0xFFFFL) | (1L << 16)));
            matSet[indexListBase + i] = (byte) i;
        }
        System.arraycopy(serialize(G3dDictionary.build(matNames, matRecords, 4)), 0, matSet, 4, matDictLen);
        System.arraycopy(serialize(G3dDictionary.build(texNames, texRecords, 4)), 0, matSet, ofsTexDict, texDictLen);
        System.arraycopy(serialize(G3dDictionary.build(List.of(), List.of(), 4)), 0, matSet, ofsPltDict, plttDictLen);
        return matSet;
    }

    // Builds a material set with one material bound (by name) to one texture. bindNames() reads the
    // texture->material dictionary to name each material's texture; the material struct only needs its
    // texImageParam wrap/flip bits (the renderer takes the texture's size from the TEX0).
    private static byte[] buildTexturedMaterialSet(String matName, String texName, int texW, int texH)
    {
        // Measure dictionary sizes first (fixed by their record counts) so the offsets are exact.
        int matDictLen = serialize(G3dDictionary.build(List.of(matName), List.of(rec4(0)), 4)).length;
        int texDictLen = serialize(G3dDictionary.build(List.of(texName), List.of(rec4(0)), 4)).length;
        int plttDictLen = serialize(G3dDictionary.build(List.of(), List.of(), 4)).length;

        int structOfs = 4 + matDictLen;
        int matStructLen = 0x18;                 // through texImageParam at +0x14
        int ofsTexDict = structOfs + matStructLen;
        int ofsPltDict = ofsTexDict + texDictLen;
        int indexListOfs = ofsPltDict + plttDictLen;
        int matSetLen = (indexListOfs + 1 + 3) & ~3;

        byte[] matSet = new byte[matSetLen];
        u16(matSet, 0, ofsTexDict);
        u16(matSet, 2, ofsPltDict);

        byte[] matDict = serialize(G3dDictionary.build(List.of(matName), List.of(rec4(structOfs)), 4));
        System.arraycopy(matDict, 0, matSet, 4, matDict.length);

        // material struct: repeat S/T so UVs in [0,texSize] tile; format/size mirror the texture (ignored
        // by the decoder, which takes size from the TEX0, but kept consistent).
        int widthSel = Integer.numberOfTrailingZeros(Math.max(8, texW) / 8);
        int heightSel = Integer.numberOfTrailingZeros(Math.max(8, texH) / 8);
        long texImageParam = ((long) 7 << 26) | ((long) widthSel << 20) | ((long) heightSel << 23)
                | (1L << 16) | (1L << 17); // repeatS | repeatT
        u32(matSet, structOfs + 0x14, texImageParam);

        // texture->material binding: entry = listOffset (bits 0-15) | listLen (bits 16-23); list = [matIdx].
        long texEntry = (indexListOfs & 0xFFFFL) | (1L << 16);
        byte[] texDict = serialize(G3dDictionary.build(List.of(texName), List.of(rec4(texEntry)), 4));
        System.arraycopy(texDict, 0, matSet, ofsTexDict, texDict.length);
        byte[] plttDict = serialize(G3dDictionary.build(List.of(), List.of(), 4));
        System.arraycopy(plttDict, 0, matSet, ofsPltDict, plttDict.length);
        matSet[indexListOfs] = 0; // material index 0

        return matSet;
    }

    private static byte[] serialize(G3dDictionary d)
    {
        MemBuf b = MemBuf.create();
        d.write(b.writer());
        return b.reader().getBuffer();
    }

    private static byte[] rec4(long v) { byte[] r = new byte[4]; u32(r, 0, v); return r; }
    private static int fx12(double v) { return (int) Math.round(v * 4096) & 0xFFFF; }
    private static void u16(byte[] d, int o, int v) { d[o] = (byte) v; d[o + 1] = (byte) (v >> 8); }
    private static void u32(byte[] d, int o, long v)
    {
        d[o] = (byte) v; d[o + 1] = (byte) (v >> 8); d[o + 2] = (byte) (v >> 16); d[o + 3] = (byte) (v >> 24);
    }
}
