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

import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * A synthetic one-triangle NSBMD exercised on both the rigid path and the Maya stack path. No ROM data.
 */
@DisplayName("SBC stack on an authored model")
class StackModelTest
{
    /**
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SCALINGRULE_MAYA"
     */
    @Test
    @DisplayName("Maya pose keeps the raw vertex and an animated translation moves it")
    void mayaPoseUsesTheStack()
    {
        Model rigid = load(false);
        Model maya = load(true);

        assertThat(rigid.usesStackPose()).isFalse();
        assertThat(maya.usesStackPose()).isTrue();
        assertThat(maxCoord(rigid)).isCloseTo(8, within(1e-3));
        assertThat(maxCoord(maya)).isCloseTo(4, within(1e-3));

        SkeletalAnimationSet.Animation shift = shiftBy(5);
        float[] posed = maya.poseFrame(shift, 0).positions[0];

        assertThat(maxCoord(posed)).isCloseTo(9, within(1e-3));
        assertThat(maxCoord(maya.poseFrame(emptyClip(), 0).positions[0])).isCloseTo(4, within(1e-3));
        assertThat(maxCoord(rigid.pose(shift, 0).get(0))).isCloseTo(13, within(1e-3));

        String skin = GltfExporter.toGltf(maya, null, Collections.singletonList(shift), null);
        assertThat(skin).contains("skins").contains("JOINTS_0");

        String tree = GltfExporter.toGltf(rigid, null, Collections.singletonList(shift), null);
        assertThat(tree).contains("animations");
    }

    /**
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_BBY"
     */
    @Test
    @DisplayName("opcodes above MTX advance the SBC cursor and a BBY marks the node")
    void highOpcodesAdvanceTheCursor()
    {
        Model model = load(false);
        int[] cur = { 0, -1 };
        int[] stack = new int[32];
        byte[] both = { 0, 1, 2 };
        int bothFlags = SbcFlag.NNS_G3D_SBCFLG_001.mask | SbcFlag.NNS_G3D_SBCFLG_010.mask;

        assertThat(model.sbcHigh(both, 0, SbcCommand.NNS_G3D_SBC_BBY.code, bothFlags, cur, stack, 1)).isEqualTo(3);
        assertThat(model.isBillboardNode(0)).isTrue();
        assertThat(model.isYaxisBillboard(0)).isTrue();
        assertThat(stack[1]).isZero();

        assertThat(model.sbcHigh(new byte[] { 9 }, 0, SbcCommand.NNS_G3D_SBC_BBY.code, 0, cur, stack, 1)).isEqualTo(1);
        assertThat(model.sbcHigh(new byte[] { 0, 2 }, 0, SbcCommand.NNS_G3D_SBC_NODEMIX.code, 0, cur, stack, 1))
                .isEqualTo(8);
        assertThat(model.sbcHigh(new byte[8], 0, SbcCommand.NNS_G3D_SBC_CALLDL.code, 0, cur, stack, 1)).isEqualTo(8);
        assertThat(model.sbcHigh(new byte[1], 0, SbcCommand.NNS_G3D_SBC_POSSCALE.code, 0, cur, stack, 1)).isZero();
        assertThat(model.sbcHigh(new byte[2], 0, SbcCommand.NNS_G3D_SBC_ENVMAP.code, 0, cur, stack, 1)).isEqualTo(2);
        assertThat(model.sbcHigh(new byte[2], 0, SbcCommand.NNS_G3D_SBC_PRJMAP.code, 0, cur, stack, 1)).isEqualTo(2);
        assertThat(model.sbcHigh(new byte[1], 0, 0x1E, 0, cur, stack, 1)).isEqualTo(-1);
    }

    /**
     * @param maya true to set the scaling rule to Maya
     * @return the authored triangle model
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNSG3dResMdlInfo"
     */
    private static Model load(boolean maya)
    {
        byte[] file = ModelBuilder.buildUntextured("model0",
                new float[] { 0, 0, 0, 8, 0, 0, 0, 8, 0 }, new int[] { 0, 1, 2 });

        if (maya)
            file[scalingRule(file)] = 1;

        return new ModelSet(file).getModels().get(0);
    }

    /**
     * @param file an NSBMD
     * @return the file offset of the scaling-rule byte
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNSG3dResMdlInfo"
     */
    private static int scalingRule(byte[] file)
    {
        int mdl = 0;
        while (mdl < file.length - 4 && !(file[mdl] == 'M' && file[mdl + 1] == 'D' && file[mdl + 2] == 'L'
                && file[mdl + 3] == '0'))
            mdl++;

        MemBuf.MemBufReader reader = MemBuf.create(file).reader();
        reader.setPosition(mdl + 8);
        int modelStart = word(new G3dDictionary(reader).getRecord(0));

        return mdl + modelStart + 0x15;
    }

    /**
     * @param d four little-endian bytes
     * @return the unsigned word
     * @see "NitroSDK fx.h FX32"
     */
    private static int word(byte[] d)
    {
        return (d[0] & 0xFF) | ((d[1] & 0xFF) << 8) | ((d[2] & 0xFF) << 16) | ((d[3] & 0xFF) << 24);
    }

    /**
     * @param x translation added on X
     * @return a one-node clip with a constant translation and identity rotation and scale
     * @see "NitroSystem g3d/src/anm/nsbca.c getMdlScale_"
     */
    private static SkeletalAnimationSet.Animation shiftBy(double x)
    {
        byte[] d = new byte[38];
        put16(d, 4, 1);
        put16(d, 6, 1);
        put16(d, 20, 22);
        put32(d, 22, 0x278);
        put32(d, 26, (int) Math.round(x * 4096));

        return new SkeletalAnimationSet.Animation(d, 0, "shift");
    }

    /**
     * @return a clip with a frame and no node tracks
     * @see "NitroSystem g3d/src/anm/nsbca.c getMdlScale_"
     */
    private static SkeletalAnimationSet.Animation emptyClip()
    {
        byte[] d = new byte[20];
        put16(d, 4, 1);
        return new SkeletalAnimationSet.Animation(d, 0, "empty");
    }

    /**
     * @param positions x,y,z triples
     * @return the largest coordinate
     * @see "NitroSDK fx_mtx43.c MTX_MultVec43"
     */
    private static double maxCoord(float[] positions)
    {
        double max = 0;
        for (float v : positions)
            max = Math.max(max, v);

        return max;
    }

    /**
     * @param model a decoded model
     * @return the largest coordinate of its first mesh
     * @see "NitroSDK fx_mtx43.c MTX_MultVec43"
     */
    private static double maxCoord(Model model)
    {
        return maxCoord(model.getMeshes().get(0).getPositions());
    }

    /**
     * @param d destination
     * @param o offset
     * @param v little-endian half-word
     * @see "NitroSDK fx.h FX16"
     */
    private static void put16(byte[] d, int o, int v)
    {
        d[o] = (byte) v;
        d[o + 1] = (byte) (v >> 8);
    }

    /**
     * @param d destination
     * @param o offset
     * @param v little-endian word
     * @see "NitroSDK fx.h FX32"
     */
    private static void put32(byte[] d, int o, int v)
    {
        d[o] = (byte) v;
        d[o + 1] = (byte) (v >> 8);
        d[o + 2] = (byte) (v >> 16);
        d[o + 3] = (byte) (v >> 24);
    }
}
