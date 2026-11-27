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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Fixed-point reads, vectors and packed display-list words. No ROM data.
 */
@DisplayName("g3d values")
class G3dTypesTest
{
    /**
     * A little-endian fx32 triple becomes a vector.
     * @see "NitroSDK include/nitro/fx/fx.h fx32"
     */
    @Test
    @DisplayName("three fx32 words read as a vector")
    void fx32Vector()
    {
        MemBuf buf = MemBuf.create();
        buf.writer().writeInt(4096);
        buf.writer().writeInt(-8192);
        buf.writer().writeInt(0);

        Vec3 v = Fx.readVec3(buf.reader());

        assertThat(v.x).isEqualTo(1);
        assertThat(v.y).isEqualTo(-2);
        assertThat(v.z).isEqualTo(0);
    }

    /**
     * VTX_16 packs X and Y in one word and Z in the low half of the next.
     * @see "GBATEK DS 3D Video, VTX_16"
     */
    @Test
    @DisplayName("VTX_16 unpacks three fx16 components")
    void vtx16Unpacks()
    {
        Vec3 v = Fx.vtx16(4096 | (8192 << 16), 12288);

        assertThat(v.x).isEqualTo(1);
        assertThat(v.y).isEqualTo(2);
        assertThat(v.z).isEqualTo(3);
    }

    /**
     * Four opcodes are stored low byte first.
     * @see "GBATEK DS 3D Video, geometry command packing"
     */
    @Test
    @DisplayName("a display-list word holds four opcodes")
    void displayListWord()
    {
        MemBuf buf = MemBuf.create();
        buf.writer().writeByte((byte) GeometryCommand.VTX_16.code);
        buf.writer().writeByte((byte) GeometryCommand.TEXCOORD.code);
        buf.writer().writeByte((byte) GeometryCommand.NOP.code);
        buf.writer().writeByte((byte) GeometryCommand.END_VTXS.code);

        DlWord word = DlWord.read(buf.reader());

        assertThat(word.at(0)).isEqualTo(GeometryCommand.VTX_16.code);
        assertThat(word.at(1)).isEqualTo(GeometryCommand.TEXCOORD.code);
        assertThat(word.at(2)).isEqualTo(GeometryCommand.NOP.code);
        assertThat(word.at(3)).isEqualTo(GeometryCommand.END_VTXS.code);
        assertThat(DisplayList.operandWords(GeometryCommand.VTX_16.code)).isEqualTo(2);
        assertThat(DisplayList.operandWords(0x7F)).isEqualTo(-1);
    }

    /**
     * A rigid bind is one joint. A mix keeps each joint with its weight.
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_NODEMIX"
     */
    @Test
    @DisplayName("envelope terms keep the joint and the weight together")
    void envelopeWeight()
    {
        SbcMachine.Bind rigid = SbcMachine.Bind.joint(3);
        SbcMachine.Bind mix = SbcMachine.Bind.mix(new EnvelopeWeight[] {
                EnvelopeWeight.of(1, 0.25), EnvelopeWeight.of(2, 0.75) });

        assertThat(rigid.mixed).isFalse();
        assertThat(rigid.terms[0].joint).isEqualTo(3);
        assertThat(mix.mixed).isTrue();
        assertThat(mix.terms[1].weight).isEqualTo(0.75);
    }

    /**
     * Texture SRT is scale and rotation, then translation.
     * @see "NitroSystem g3d/src/anm/nsbta.c NNS_G3dAnmObjCalcTexSRT"
     */
    @Test
    @DisplayName("a texture matrix scales S and translates")
    void textureMatrix()
    {
        TexAffine m = TexAffine.of(2, 3, 0, 0.5f, -1);

        assertThat(m.a).isEqualTo(2);
        assertThat(m.d).isEqualTo(3);
        assertThat(m.e).isEqualTo(0.5f);
        assertThat(m.f).isEqualTo(-1);
    }

    /**
     * Identity and a 90 degree yaw become the glTF quaternions.
     * @see "Khronos glTF 2.0, node.rotation"
     */
    @Test
    @DisplayName("a row-vector rotation becomes a column-vector quaternion")
    void quaternionFromRotation()
    {
        float[] identity = GltfExporter.matrixToQuat(Mat3.identity());
        float[] yaw = GltfExporter.matrixToQuat(Mat3.of(0, 1, 0, -1, 0, 0, 0, 0, 1));

        assertThat(identity[0]).isEqualTo(0);
        assertThat(identity[1]).isEqualTo(0);
        assertThat(identity[2]).isEqualTo(0);
        assertThat(identity[3]).isEqualTo(1);
        assertThat(yaw[3]).isCloseTo((float) Math.sqrt(0.5), within(1e-5f));
    }
}
