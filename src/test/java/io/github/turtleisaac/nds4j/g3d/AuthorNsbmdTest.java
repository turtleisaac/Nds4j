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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The capstone for source&rarr;NSB* conversion: this authors a complete <b>NSBMD</b> model from scratch
 * &mdash; geometry via {@link DisplayList#encode}, every resource dictionary (model / node / shape /
 * material) via {@link G3dDictionary#build}, the MDL0 model laid out by hand, and the container by
 * {@link G3dFile#assembleContainer} &mdash; then reads it with the production {@link ModelSet} and
 * confirms the decoded mesh is the exact triangle that was authored (and the file round-trips its own
 * bytes). Nothing is parsed from a pre-existing model; the whole NSBMD is built.
 */
@DisplayName("author an NSBMD from scratch (end-to-end conversion)")
public class AuthorNsbmdTest
{
    @Test
    @DisplayName("a from-scratch NSBMD decodes to the authored geometry and round-trips")
    void authorAndReadBack()
    {
        float[] pos = {0, 0, 0,  1, 0, 0,  0, 1, 0};
        float[] uv = {0, 0,  16, 0,  0, 16};
        int[] tris = {0, 1, 2};

        byte[] file = buildModel(pos, uv, tris);

        ModelSet ms = new ModelSet(file);
        assertThat(ms.getModels()).hasSize(1);
        Model m = ms.getModels().get(0);
        assertThat(m.getName()).isEqualTo("model0");
        assertThat(m.getNodeCount()).isEqualTo(1);
        assertThat(m.getMeshes()).hasSize(1);

        Model.Mesh mesh = m.getMeshes().get(0);
        assertThat(mesh.getName()).isEqualTo("shape0");
        assertThat(mesh.getTriangleCount()).isEqualTo(1);
        int[] t = mesh.getTriangleIndices();
        float[] p = mesh.getPositions();
        for (int k = 0; k < 3; k++)
            for (int c = 0; c < 3; c++)
                assertThat(p[t[k] * 3 + c]).as("authored vertex survives NSBMD author->read")
                        .isCloseTo(pos[tris[k] * 3 + c], org.assertj.core.data.Offset.offset(1f / 4096));

        assertThat(ms.save()).as("the authored NSBMD round-trips its own bytes").isEqualTo(file);
    }

    private static byte[] serialize(G3dDictionary d)
    {
        MemBuf b = MemBuf.create();
        d.write(b.writer());
        return b.reader().getBuffer();
    }

    private static byte[] rec4(long v) { byte[] r = new byte[4]; u32(r, 0, v); return r; }

    // Assembles a single-node, single-shape, untextured NSBMD around the given geometry.
    private static byte[] buildModel(float[] pos, float[] uv, int[] tris)
    {
        byte[] dl = DisplayList.encode(pos, uv, tris);

        byte[] nodeDict = serialize(G3dDictionary.build(List.of("node0"), List.of(rec4(40)), 4));
        byte[] nodeData = {0x07, 0x00, 0x00, 0x10}; // flags 7 (skip t/r/s), rotation[0][0] = 1.0 → identity
        int nodeSetLen = nodeDict.length + nodeData.length;

        byte[] sbc = {0x06, 0x00, 0x00, 0x00,  0x05, 0x00,  0x01}; // NODEDESC(0,0,0), SHP(0), RET

        byte[] matDict = serialize(G3dDictionary.build(List.of(), List.of(), 4));
        byte[] texDict = serialize(G3dDictionary.build(List.of(), List.of(), 4));
        byte[] plttDict = serialize(G3dDictionary.build(List.of(), List.of(), 4));
        int ofsTexDict = 4 + matDict.length, ofsPltDict = ofsTexDict + texDict.length;
        int matSetLen = ofsPltDict + plttDict.length;
        byte[] matSet = new byte[matSetLen];
        u16(matSet, 0, ofsTexDict);
        u16(matSet, 2, ofsPltDict);
        System.arraycopy(matDict, 0, matSet, 4, matDict.length);
        System.arraycopy(texDict, 0, matSet, ofsTexDict, texDict.length);
        System.arraycopy(plttDict, 0, matSet, ofsPltDict, plttDict.length);

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
        model[0x14 + 3] = 1;                // numNode
        model[0x14 + 5] = 1;                // shapeCount
        u32(model, 0x14 + 8, 4096);         // posScale = 1.0
        u16(model, 0x14 + 16, 3);           // numVertex
        u16(model, 0x14 + 20, 1);           // numTriangle
        u16(model, 0x32, 4096);             // box dim x = 1
        u16(model, 0x34, 4096);             // box dim y = 1
        u32(model, 0x38, 4096);             // boxPosScale
        System.arraycopy(nodeDict, 0, model, headerLen, nodeDict.length);
        System.arraycopy(nodeData, 0, model, headerLen + nodeDict.length, nodeData.length);
        System.arraycopy(sbc, 0, model, ofsSbc, sbc.length);
        System.arraycopy(matSet, 0, model, ofsMat, matSet.length);
        System.arraycopy(shapeDict, 0, model, ofsShp, shapeDict.length);
        System.arraycopy(shapeStruct, 0, model, ofsShp + shapeDict.length, shapeStruct.length);
        System.arraycopy(dl, 0, model, ofsShp + shapeDict.length + shapeStruct.length, dl.length);

        int modelStart = 8 + serialize(G3dDictionary.build(List.of("model0"), List.of(rec4(0)), 4)).length;
        byte[] modelDict = serialize(G3dDictionary.build(List.of("model0"), List.of(rec4(modelStart)), 4));
        int mdl0Len = (modelStart + model.length + 3) & ~3;
        byte[] mdl0 = new byte[mdl0Len];
        System.arraycopy("MDL0".getBytes(StandardCharsets.US_ASCII), 0, mdl0, 0, 4);
        u32(mdl0, 4, mdl0Len);
        System.arraycopy(modelDict, 0, mdl0, 8, modelDict.length);
        System.arraycopy(model, 0, mdl0, modelStart, model.length);

        return G3dFile.assembleContainer("BMD0", 2, mdl0);
    }

    private static void u16(byte[] d, int o, int v) { d[o] = (byte) v; d[o + 1] = (byte) (v >> 8); }
    private static void u32(byte[] d, int o, long v)
    {
        d[o] = (byte) v; d[o + 1] = (byte) (v >> 8); d[o + 2] = (byte) (v >> 16); d[o + 3] = (byte) (v >> 24);
    }
}
