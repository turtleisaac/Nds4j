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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Synthetic checks for the SBC matrix stack: Maya joint order, store/restore, NODEMIX and a one-vertex
 * display list. No ROM data.
 */
@DisplayName("SBC matrix stack")
class SbcStackTest
{
    private static final double[] UNIT = { 1, 1, 1 };
    private static final double[] IDENT_R = { 1, 0, 0, 0, 1, 0, 0, 0, 1 };

    /**
     * @return a translation matrix
     * @see "NitroSDK fx_mtx43.c MTX_TransApply43"
     */
    private static double[] translation(double x, double y, double z)
    {
        double[] m = NitroMtx.identity();
        m[9] = x;
        m[10] = y;
        m[11] = z;

        return m;
    }

    /**
     * @see "NitroSystem g3d/src/cgtool/maya.c NNSi_G3dSendJointSRTMaya"
     */
    @Test
    @DisplayName("a Maya joint is scale, then rotation, then inverse parent scale, then translation")
    void mayaJointMatchesManualOrder()
    {
        double[] t = { 10, 20, 30 };
        double[] r = { 0, 1, 0, -1, 0, 0, 0, 0, 1 };
        double[] s = { 2, 3, 4 };
        double[] invParent = { 0.5, 1, 1 };

        double[] joint = NitroMtx.mayaJoint(t, r, s, invParent);
        float[] out = new float[3];
        NitroMtx.apply(joint, 1, 0, 0, out, 0);

        near(out[0], 10);
        near(out[1], 22);
        near(out[2], 30);
    }

    /**
     * @see "NitroSystem g3d/src/cgtool/maya.c NNSi_G3dSendJointSRTMaya"
     */
    @Test
    @DisplayName("decomposing a Maya joint recovers its translation, rotation and scale")
    void decomposeRecoversJoint()
    {
        double[] t = { 10, 20, 30 };
        double[] r = { 0, 1, 0, -1, 0, 0, 0, 0, 1 };
        double[] s = { 2, 3, 4 };

        double[] joint = NitroMtx.mayaJoint(t, r, s, UNIT);
        double[][] parts = NitroMtx.decompose(joint);

        assertThat(parts[0]).containsExactly(t);
        assertThat(parts[2][0]).isCloseTo(2, within(1e-9));
        assertThat(parts[2][1]).isCloseTo(3, within(1e-9));
        assertThat(parts[2][2]).isCloseTo(4, within(1e-9));

        double[] back = NitroMtx.mayaJoint(parts[0], parts[1], parts[2], UNIT);
        float[] out = new float[3];
        NitroMtx.apply(back, 1, 2, 3, out, 0);
        float[] expect = new float[3];
        NitroMtx.apply(joint, 1, 2, 3, expect, 0);

        near(out[0], expect[0]);
        near(out[1], expect[1]);
        near(out[2], expect[2]);
    }

    /**
     * @see "NitroSDK fx_mtx43.c MTX_Inverse43"
     */
    @Test
    @DisplayName("inverting a translation or a scale returns the vertex")
    void inverseRoundTrips()
    {
        float[] out = new float[3];

        double[] moved = NitroMtx.mul(NitroMtx.invert(translation(3, -4, 5)), translation(3, -4, 5));
        NitroMtx.apply(moved, 1, 2, 3, out, 0);
        assertThat(out).containsExactly(1f, 2f, 3f);

        double[] scaled = NitroMtx.mul(NitroMtx.invert(NitroMtx.scale(2, 4, 8)), NitroMtx.scale(2, 4, 8));
        NitroMtx.apply(scaled, 1, 1, 1, out, 0);
        near(out[0], 1);
        near(out[1], 1);
        near(out[2], 1);
    }

    /**
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_NODEDESC"
     */
    @Test
    @DisplayName("NODEDESC stores the joint and a later restore brings that matrix back")
    void storeThenRestore()
    {
        SbcMachine machine = new SbcMachine(1);
        machine.nodeDesc(0, 0, 0, -1, 2, new double[] { 4, 0, 0 }, IDENT_R, UNIT, false);
        machine.nodeDesc(0, 0, 0, -1, -1, new double[] { 0, 9, 0 }, IDENT_R, UNIT, false);
        machine.restore(2);

        float[] out = new float[3];
        NitroMtx.apply(machine.current(), 1, 0, 0, out, 0);

        near(out[0], 5);
        near(out[1], 0);
    }

    /**
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_NODEMIX"
     */
    @Test
    @DisplayName("a 50/50 NODEMIX lands a vertex halfway between the two joints")
    void nodeMixHalfway()
    {
        SbcMachine machine = new SbcMachine(2);
        machine.putSlot(0, NitroMtx.identity(), SbcMachine.Bind.joint(0));
        machine.putSlot(1, translation(0, 10, 0), SbcMachine.Bind.joint(1));
        double[][] inv = { NitroMtx.identity(), NitroMtx.identity() };
        machine.nodeMix(2, new int[] { 0, 1 }, new int[] { 0, 1 }, new double[] { 0.5, 0.5 }, inv);

        float[] out = new float[3];
        NitroMtx.apply(machine.current(), 0, 0, 0, out, 0);

        near(out[0], 0);
        near(out[1], 5);
        near(out[2], 0);
    }

    /**
     * @see "NitroSystem g3d/src/cgtool/maya.c NNSi_G3dGetJointScaleMaya"
     */
    @Test
    @DisplayName("Maya SSC cancels a parent's scale on the child")
    void mayaSscCancelsParentScale()
    {
        double[][] trans = { { 0, 0, 0 }, { 0, 0, 0 } };
        double[][] rot = { IDENT_R, IDENT_R };
        double[][] scale = { { 2, 2, 2 }, UNIT };

        byte[] sbc = {
                cmd(SbcCommand.NNS_G3D_SBC_NODEDESC, SbcFlag.NNS_G3D_SBCFLG_001), 0, 0,
                (byte) NodeDescFlag.NNS_G3D_SBC_NODEDESC_FLAG_MAYASSC_PARENT.mask, 0,
                cmd(SbcCommand.NNS_G3D_SBC_NODEDESC, SbcFlag.NNS_G3D_SBCFLG_010), 1, 0,
                (byte) NodeDescFlag.NNS_G3D_SBC_NODEDESC_FLAG_MAYASSC_APPLY.mask, 0,
                cmd(SbcCommand.NNS_G3D_SBC_SHP), 0,
                cmd(SbcCommand.NNS_G3D_SBC_RET)
        };

        byte[] dl = vtx16(1, 0, 0);
        StackPose.Frame frame = StackPose.play(sbc, true, 1, 1, trans, rot, scale, null,
                new byte[][] { dl }, new float[][] { { 0, 0, 0 } });

        near(frame.positions[0][0], 1);
        near(frame.positions[0][1], 0);
        near(frame.positions[0][2], 0);
    }

    /**
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_NODEMIX"
     */
    @Test
    @DisplayName("an envelope display list scales the raw vertex and blends the restored joints")
    void envelopeDisplayList()
    {
        double[][] trans = { { 0, 0, 0 }, { 0, 10, 0 } };
        double[][] rot = { IDENT_R, IDENT_R };
        double[][] scale = { UNIT, UNIT };
        double[][] inv = { NitroMtx.identity(), NitroMtx.identity() };

        byte[] sbc = {
                cmd(SbcCommand.NNS_G3D_SBC_NODEDESC, SbcFlag.NNS_G3D_SBCFLG_001), 0, 0, 0, 0,
                cmd(SbcCommand.NNS_G3D_SBC_NODEDESC, SbcFlag.NNS_G3D_SBCFLG_001), 1, 0, 0, 1,
                cmd(SbcCommand.NNS_G3D_SBC_NODEMIX), 2, 2, 0, 0, (byte) 128, 1, 1, (byte) 128,
                cmd(SbcCommand.NNS_G3D_SBC_SHP), 0,
                cmd(SbcCommand.NNS_G3D_SBC_RET)
        };

        int scaleFx = 2 * 4096;
        byte[] dl = new byte[36];
        dl[0] = (byte) GeometryCommand.MTX_RESTORE.code;
        put32(dl, 4, 2);
        dl[8] = (byte) GeometryCommand.MTX_SCALE.code;
        put32(dl, 12, scaleFx);
        put32(dl, 16, scaleFx);
        put32(dl, 20, scaleFx);
        dl[24] = (byte) GeometryCommand.VTX_16.code;
        put32(dl, 28, 4096);
        put32(dl, 32, 0);

        StackPose.Frame frame = StackPose.play(sbc, false, 2, 0.5, trans, rot, scale, inv,
                new byte[][] { dl }, new float[][] { { 1, 0, 0 } });

        near(frame.positions[0][0], 2);
        near(frame.positions[0][1], 5);
        near(frame.positions[0][2], 0);
        assertThat(frame.jointIndex[0][0]).containsExactly(0, 1);
        assertThat(frame.weight[0][0][0]).isCloseTo(0.5, within(1e-9));
    }

    /**
     * @see "GBATEK DS 3D Video, VTX_10, VTX_DIFF, VTX_XY, VTX_XZ and VTX_YZ"
     */
    @Test
    @DisplayName("packed vertex commands replace or add the components they name")
    void packedVertexCommands()
    {
        byte[] dl = new byte[4 + 16 + 4 + 8];
        dl[0] = (byte) GeometryCommand.VTX_10.code;
        dl[1] = (byte) GeometryCommand.VTX_DIFF.code;
        dl[2] = (byte) GeometryCommand.VTX_XY.code;
        dl[3] = (byte) GeometryCommand.VTX_XZ.code;
        put32(dl, 4, 64);
        put32(dl, 8, 0x3C0);
        put32(dl, 12, 4096 | (8192 << 16));
        put32(dl, 16, 12288 | (16384 << 16));
        dl[20] = (byte) GeometryCommand.VTX_YZ.code;
        dl[21] = (byte) GeometryCommand.COLOR.code;
        dl[22] = (byte) GeometryCommand.NOP.code;
        dl[23] = (byte) GeometryCommand.END_VTXS.code;
        put32(dl, 24, 20480 | (24576 << 16));
        put32(dl, 28, 0);

        DlVerts.Out out = DlVerts.transform(dl, new float[15], NitroMtx.identity(),
                SbcMachine.Bind.joint(0), new SbcMachine(1));

        near(out.positions[0], 1);
        near(out.positions[1], 0);
        near(out.positions[2], 0);
        near(out.positions[3], 1 - 64 / 4096.0);
        near(out.positions[4], 0);
        near(out.positions[5], 0);
        near(out.positions[6], 1);
        near(out.positions[7], 2);
        near(out.positions[8], 0);
        near(out.positions[9], 3);
        near(out.positions[10], 2);
        near(out.positions[11], 4);
        near(out.positions[12], 3);
        near(out.positions[13], 5);
        near(out.positions[14], 6);
    }

    /**
     * @see "GBATEK DS 3D Video, command parameter words"
     */
    @Test
    @DisplayName("an opcode the geometry engine does not pose is rejected")
    void unknownOpcodeIsRejected()
    {
        // 0x01 is not a geometry command this pose consumes.
        byte[] dl = { 0x01, 0, 0, 0 };

        assertThatThrownBy(() -> DlVerts.transform(dl, new float[3], NitroMtx.identity(),
                SbcMachine.Bind.joint(0), new SbcMachine(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_NOP"
     */
    @Test
    @DisplayName("the player walks every SBC opcode and a unit Maya scale stays cached")
    void everySbcOpcodeAndUnitScale()
    {
        double[][] trans = { { 3, 0, 0 }, { 0, 0, 0 } };
        double[][] rot = { IDENT_R, IDENT_R };
        double[][] scale = { UNIT, UNIT };
        double[][] inv = { NitroMtx.identity(), NitroMtx.identity() };

        byte[] sbc = {
                cmd(SbcCommand.NNS_G3D_SBC_NOP),
                cmd(SbcCommand.NNS_G3D_SBC_NODE), 0, 0,
                cmd(SbcCommand.NNS_G3D_SBC_NODEDESC), 0, 0, (byte) NodeDescFlag.NNS_G3D_SBC_NODEDESC_FLAG_MAYASSC_PARENT.mask,
                cmd(SbcCommand.NNS_G3D_SBC_SHP), 0,
                cmd(SbcCommand.NNS_G3D_SBC_NODEDESC), 5, 0, 0,
                cmd(SbcCommand.NNS_G3D_SBC_CALLDL), 0, 0, 0, 0, 0, 0, 0, 0,
                cmd(SbcCommand.NNS_G3D_SBC_POSSCALE, SbcFlag.NNS_G3D_SBCFLG_001),
                cmd(SbcCommand.NNS_G3D_SBC_POSSCALE),
                cmd(SbcCommand.NNS_G3D_SBC_ENVMAP), 0, 0,
                cmd(SbcCommand.NNS_G3D_SBC_PRJMAP), 0, 0,
                cmd(SbcCommand.NNS_G3D_SBC_BB), 0,
                cmd(SbcCommand.NNS_G3D_SBC_BB, SbcFlag.NNS_G3D_SBCFLG_001), 0, 1,
                cmd(SbcCommand.NNS_G3D_SBC_BB, SbcFlag.NNS_G3D_SBCFLG_010), 0, 2,
                cmd(SbcCommand.NNS_G3D_SBC_BBY), 1,
                cmd(SbcCommand.NNS_G3D_SBC_BBY, SbcFlag.NNS_G3D_SBCFLG_001, SbcFlag.NNS_G3D_SBCFLG_010), 0, 1, 2,
                cmd(SbcCommand.NNS_G3D_SBC_NODEMIX), 0, 1, 0, 0, (byte) 128,
                0x1F
        };

        StackPose.Frame frame = StackPose.play(sbc, true, 4, 0.25, trans, rot, scale, inv,
                new byte[][] { vtx16(1, 0, 0) }, new float[][] { { 1, 0, 0 } });

        near(frame.positions[0][0], 4);
        near(frame.positions[0][1], 0);
        near(frame.positions[0][2], 0);

        SbcMachine machine = new SbcMachine(1);
        int parent = NodeDescFlag.NNS_G3D_SBC_NODEDESC_FLAG_MAYASSC_PARENT.mask;
        machine.nodeDesc(-1, -1, parent, -1, -1, new double[] { 0, 0, 0 }, IDENT_R, UNIT, true);
        machine.nodeDesc(5, 0, parent, -1, -1, new double[] { 0, 0, 0 }, IDENT_R, UNIT, true);
    }

    /**
     * @see "GBATEK DS 3D Video, MTX_MODE"
     */
    @Test
    @DisplayName("MTX_MODE applies identity only for the position matrices")
    void matrixModeChoosesWhichMatrixMoves()
    {
        double[] draw = translation(5, 0, 0);

        near(posed(modeList(GxMtxMode.GX_MTXMODE_PROJECTION), draw)[0], 6);
        near(posed(modeList(GxMtxMode.GX_MTXMODE_TEXTURE), draw)[0], 6);

        near(posed(modeList(GxMtxMode.GX_MTXMODE_POSITION), draw)[0], 1);
        near(posed(modeList(GxMtxMode.GX_MTXMODE_POSITION_VECTOR), draw)[0], 1);
    }

    /**
     * @param dl display list
     * @param draw position matrix at the start of the list
     * @return the posed vertex
     * @see "GBATEK DS 3D Video, MTX_IDENTITY"
     */
    private static float[] posed(byte[] dl, double[] draw)
    {
        return DlVerts.transform(dl, new float[] { 1, 0, 0 }, draw, SbcMachine.Bind.joint(0), new SbcMachine(1)).positions;
    }

    /**
     * @param mode matrix selected by MTX_MODE
     * @return a list that sets that mode, replaces the matrix with the identity, and emits (1, 0, 0)
     * @see "GBATEK DS 3D Video, MTX_MODE"
     */
    private static byte[] modeList(GxMtxMode mode)
    {
        byte[] dl = new byte[16];
        dl[0] = (byte) GeometryCommand.MTX_MODE.code;
        dl[1] = (byte) GeometryCommand.MTX_IDENTITY.code;
        dl[2] = (byte) GeometryCommand.VTX_16.code;
        dl[3] = (byte) GeometryCommand.NOP.code;

        put32(dl, 4, mode.code);
        put32(dl, 8, 4096);
        put32(dl, 12, 0);

        return dl;
    }

    /**
     * @param actual a posed coordinate
     * @param expected the coordinate the cited formula produces
     * @see "NitroSDK fx_mtx43.c MTX_MultVec43"
     */
    private static void near(float actual, double expected)
    {
        assertThat((double) actual).isCloseTo(expected, within(1e-4));
    }

    /**
     * @param x vertex X in model units
     * @param y vertex Y
     * @param z vertex Z
     * @return a one-vertex display list
     * @see "GBATEK DS 3D Video, VTX_16"
     */
    private static byte[] vtx16(double x, double y, double z)
    {
        byte[] dl = new byte[12];
        dl[0] = (byte) GeometryCommand.VTX_16.code;

        int xFx = (int) Math.round(x * 4096);
        int yFx = (int) Math.round(y * 4096);
        int zFx = (int) Math.round(z * 4096);

        put32(dl, 4, (xFx & 0xFFFF) | (yFx << 16));
        put32(dl, 8, zFx & 0xFFFF);

        return dl;
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

    /**
     * @param command SBC opcode
     * @param flags flag bits packed into the command byte
     * @return the command byte
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_NOP"
     */
    private static byte cmd(SbcCommand command, SbcFlag... flags)
    {
        int b = command.code;
        for (SbcFlag flag : flags)
        {
            b |= flag.mask;
        }

        return (byte) b;
    }
}
