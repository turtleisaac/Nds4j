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

/**
 * Plays an SBC stream on an {@link SbcMachine} and poses each shape's display list.
 */
final class StackPose
{
    /** One posed frame: mesh positions, joint worlds, and the binding captured with each vertex. */
    static final class Frame
    {
        final float[][] positions;
        final double[][] joints;
        final int[][][] jointIndex;
        final double[][][] weight;

        private Frame(float[][] positions, double[][] joints, int[][][] jointIndex, double[][][] weight)
        {
            this.positions = positions;
            this.joints = joints;
            this.jointIndex = jointIndex;
            this.weight = weight;
        }
    }

    private final byte[] sbc;
    private final boolean maya;
    private final double posScale;
    private final double invPosScale;
    private final double[][] trans;
    private final double[][] rot;
    private final double[][] scale;
    private final double[][] invBind;
    private final byte[][] dls;
    private final float[][] raw;
    private final SbcMachine machine;
    private final float[][] positions;
    private final int[][][] jointIndex;
    private final double[][][] weight;

    private StackPose(byte[] sbc, boolean maya, double posScale, double invPosScale,
            double[][] trans, double[][] rot, double[][] scale, double[][] invBind,
            byte[][] dls, float[][] raw)
    {
        this.sbc = sbc;
        this.maya = maya;
        this.posScale = posScale;
        this.invPosScale = invPosScale;
        this.trans = trans;
        this.rot = rot;
        this.scale = scale;
        this.invBind = invBind;
        this.dls = dls;
        this.raw = raw;

        this.machine = new SbcMachine(trans.length);
        this.positions = new float[raw.length][];
        this.jointIndex = new int[raw.length][][];
        this.weight = new double[raw.length][][];
    }

    /**
     * @param sbc SBC bytes, starting at the first command
     * @param maya true for {@code NNS_G3D_SCALINGRULE_MAYA}
     * @param posScale model position scale
     * @param invPosScale inverse position scale from the model header
     * @param trans per-node translation
     * @param rot per-node rotation
     * @param scale per-node scale
     * @param invBind per-node inverse bind, or null when the model has no envelope
     * @param dls one display list per shape
     * @param raw pre-scale vertices per shape
     * @return posed positions, joint matrices and vertex bindings
     * @see "NitroSystem g3d/src/sbc.c NNS_G3dDraw"
     */
    static Frame play(byte[] sbc, boolean maya, double posScale, double invPosScale,
            double[][] trans, double[][] rot, double[][] scale, double[][] invBind,
            byte[][] dls, float[][] raw)
    {
        StackPose pose = new StackPose(sbc, maya, posScale, invPosScale, trans, rot, scale, invBind, dls, raw);
        pose.walk();

        double[][] joints = new double[trans.length][];
        for (int i = 0; i < joints.length; i++)
            joints[i] = pose.machine.jointWorld(i);

        return new Frame(pose.positions, joints, pose.jointIndex, pose.weight);
    }

    /**
     * @see "NitroSystem g3d/src/sbc.c G3dDrawInternal_"
     */
    private void walk()
    {
        MemBuf.MemBufReader commands = MemBuf.create(sbc).reader();
        while (commands.getPosition() < sbc.length)
        {
            int b = commands.readByte();

            SbcCommand cmd = SbcCommand.from(b);
            if (cmd == SbcCommand.NNS_G3D_SBC_RET)
                return;

            if (cmd != null && cmd.code < SbcCommand.NNS_G3D_SBC_BBY.code)
                low(commands, cmd, b);
            else
                high(commands, cmd, b);
        }
    }

    /**
     * @param commands SBC reader, on the operand
     * @param cmd opcode below {@link SbcCommand#NNS_G3D_SBC_BBY}
     * @param raw the command byte, including flag bits
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_NOP through NNS_G3D_SBC_BB"
     */
    private void low(MemBuf.MemBufReader commands, SbcCommand cmd, int raw)
    {
        switch (cmd)
        {
            case NNS_G3D_SBC_NODE:
                commands.skip(2);
                break;
            case NNS_G3D_SBC_MTX:
                restore(commands);
                break;
            case NNS_G3D_SBC_MAT:
                commands.skip(1);
                break;
            case NNS_G3D_SBC_SHP:
                shape(commands);
                break;
            case NNS_G3D_SBC_NODEDESC:
                node(commands, raw);
                break;
            case NNS_G3D_SBC_BB:
                billboard(commands, raw);
                break;
            default:
                break;
        }
    }

    /**
     * @param commands SBC reader, on the operand
     * @param cmd opcode of {@link SbcCommand#NNS_G3D_SBC_BBY} or higher, or null when it is not defined
     * @param raw the command byte, including flag bits
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_BBY through NNS_G3D_SBC_PRJMAP"
     */
    private void high(MemBuf.MemBufReader commands, SbcCommand cmd, int raw)
    {
        if (cmd == SbcCommand.NNS_G3D_SBC_BBY)
            billboard(commands, raw);
        else if (cmd == SbcCommand.NNS_G3D_SBC_NODEMIX)
            mix(commands);
        else if (cmd == SbcCommand.NNS_G3D_SBC_CALLDL)
            commands.skip(8);
        else if (cmd == SbcCommand.NNS_G3D_SBC_POSSCALE)
            possScale(raw);
        else if (cmd == SbcCommand.NNS_G3D_SBC_ENVMAP || cmd == SbcCommand.NNS_G3D_SBC_PRJMAP)
            commands.skip(2);
        else
            commands.setPosition(sbc.length);
    }

    /**
     * @param commands SBC reader, on the stack index
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_MTX"
     */
    private void restore(MemBuf.MemBufReader commands)
    {
        machine.restore(commands.readByte() & 31);
    }

    /**
     * @param commands SBC reader, on the shape index
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_SHP"
     */
    private void shape(MemBuf.MemBufReader commands)
    {
        int shp = commands.readByte();
        if (shp < dls.length && dls[shp] != null)
        {
            DlVerts.Out out = DlVerts.transform(dls[shp], raw[shp], machine.current(), machine.binding(), machine);

            positions[shp] = out.positions;
            jointIndex[shp] = out.joints;
            weight[shp] = out.weights;
        }
    }

    /**
     * @param commands SBC reader, on the node id
     * @param flags store and restore bits
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_NODEDESC"
     */
    private void node(MemBuf.MemBufReader commands, int flags)
    {
        int node = commands.readByte();
        int parent = commands.readByte();
        int opt = commands.readByte();
        int[] picked = slots(commands, flags);
        int n = node < trans.length ? node : 0;

        machine.nodeDesc(n, parent, opt, picked[0], picked[1], trans[n], rot[n], scale[n], maya);
    }

    /**
     * @param commands SBC reader, on the node id
     * @param flags store and restore bits
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_BB"
     */
    private void billboard(MemBuf.MemBufReader commands, int flags)
    {
        commands.readByte();
        int[] picked = slots(commands, flags);

        if (picked[0] >= 0)
            machine.restore(picked[0]);

        if (picked[1] >= 0)
            machine.store(picked[1]);
    }

    /**
     * @param commands SBC reader, on the first optional slot byte
     * @param flags SBC flag bits
     * @return {@code {src, dst}} with -1 for an absent slot
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBCFLG_001 and NNS_G3D_SBCFLG_010"
     */
    private int[] slots(MemBuf.MemBufReader commands, int flags)
    {
        int dst = -1;
        int src = -1;

        if ((flags & SbcFlag.NNS_G3D_SBCFLG_001.mask) != 0)
            dst = commands.readByte() & 31;
        if ((flags & SbcFlag.NNS_G3D_SBCFLG_010.mask) != 0)
            src = commands.readByte() & 31;

        return new int[] { src, dst };
    }

    /**
     * @param commands SBC reader, on idxMtxDest
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_NODEMIX"
     */
    private void mix(MemBuf.MemBufReader commands)
    {
        int dest = commands.readByte() & 31;
        int terms = commands.readByte();

        int[] stackSlots = new int[terms];
        int[] joints = new int[terms];
        double[] weights = new double[terms];
        for (int i = 0; i < terms; i++)
        {
            stackSlots[i] = commands.readByte() & 31;
            joints[i] = commands.readByte();
            weights[i] = (commands.readByte() << 4) / 4096.0;
        }

        machine.nodeMix(dest, stackSlots, joints, weights, invBind);
    }

    /**
     * @param flags command byte; {@link SbcFlag#NNS_G3D_SBCFLG_001} selects the inverse scale
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_POSSCALE"
     */
    private void possScale(int flags)
    {
        machine.posScale((flags & SbcFlag.NNS_G3D_SBCFLG_001.mask) != 0 ? invPosScale : posScale);
    }
}
