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

    // Lays out one MDL0 block around the geometry, a caller-supplied material set and SBC render stream.
    private static byte[] assembleMdl0(String modelName, byte[] dl, int numVertex, int numTriangle,
                                       int posScale, float[] boxMin, float[] boxMax, int boxPosScale,
                                       byte[] matSet, byte[] sbc)
    {
        byte[] nodeDict = serialize(G3dDictionary.build(List.of("node0"), List.of(rec4(40)), 4));
        byte[] nodeData = {0x07, 0x00, 0x00, 0x10}; // flags 7 (identity node): skip t/r/s, rotation[0][0]=1.0
        int nodeSetLen = nodeDict.length + nodeData.length;
        int matSetLen = matSet.length;

        byte[] shapeDict = serialize(G3dDictionary.build(List.of("shape0"), List.of(rec4(40)), 4));
        byte[] shapeStruct = new byte[16];
        u32(shapeStruct, 8, 16);           // dlOffset (relative to the shape struct)
        u32(shapeStruct, 12, dl.length);   // dlSize
        int shapeSetLen = shapeDict.length + shapeStruct.length + dl.length;

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
        model[0x14 + 3] = 1;               // numNode
        model[0x14 + 5] = 1;               // shapeCount
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
        System.arraycopy(shapeStruct, 0, model, ofsShp + shapeDict.length, shapeStruct.length);
        System.arraycopy(dl, 0, model, ofsShp + shapeDict.length + shapeStruct.length, dl.length);

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
