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
 * Applies a shape display list's position-matrix commands while emitting vertices.
 * Matrix mode starts in position-vector, which is the mode NODEDESC leaves the geometry engine in.
 */
final class DlVerts
{
    /** Posed vertices plus the joint binding that was current for each one. */
    static final class Out
    {
        final float[] positions;
        final int[][] joints;
        final double[][] weights;

        private Out(float[] positions, int[][] joints, double[][] weights)
        {
            this.positions = positions;
            this.joints = joints;
            this.weights = weights;
        }
    }

    private DlVerts()
    {
    }

    /**
     * @param dl raw display list
     * @param raw pre-{@code posScale} vertices in emit order; only the count is used
     * @param draw position matrix at the SHP command
     * @param bind joint binding of {@code draw}
     * @param machine stack the display list restores from
     * @return one posed vertex per raw vertex
     * @see "GBATEK DS 3D Video, geometry commands MTX_MODE, MTX_RESTORE and MTX_SCALE"
     */
    static Out transform(byte[] dl, float[] raw, double[] draw, SbcMachine.Bind bind, SbcMachine machine)
    {
        int count = raw.length / 3;
        float[] dest = new float[raw.length];
        int[][] joints = new int[count][];
        double[][] weights = new double[count][];

        Cursor c = new Cursor(draw, bind);
        double[] vtx = new double[3];
        int n = 0;

        MemBuf.MemBufReader commands = MemBuf.create(dl).reader();
        while (commands.getPosition() + 4 <= dl.length && n < count)
        {
            int[] ops = { commands.readByte(), commands.readByte(), commands.readByte(), commands.readByte() };
            for (int op : ops)
            {
                if (n >= count)
                    break;

                GeometryCommand cmd = GeometryCommand.from(op);
                step(commands, op, cmd, c, machine, vtx, dest, joints, weights, n);
                if (cmd != null && cmd.emitsVertex)
                    n++;
            }
        }

        return new Out(dest, joints, weights);
    }

    /**
     * @param commands display-list reader, on this opcode's parameters
     * @param op opcode, kept for the exception text
     * @param cmd the command, or null when the opcode is not defined
     * @param c draw matrix and binding
     * @param machine matrix stack
     * @param vtx running vertex
     * @param dest posed output
     * @param joints per-vertex joint indices
     * @param weights per-vertex weights
     * @param n vertices already emitted
     * @see "GBATEK DS 3D Video"
     */
    private static void step(MemBuf.MemBufReader commands, int op, GeometryCommand cmd, Cursor c, SbcMachine machine,
            double[] vtx, float[] dest, int[][] joints, double[][] weights, int n)
    {
        if (cmd == null)
            throw unknown(op);

        switch (cmd)
        {
            case MTX_MODE:
                mode(commands, c);
                break;
            case MTX_RESTORE:
                restore(commands, c, machine);
                break;
            case MTX_SCALE:
                scale(commands, c);
                break;
            case MTX_IDENTITY:
                identity(c);
                break;
            case MTX_TRANS:
                trans(commands, c);
                break;
            default:
                if (cmd.emitsVertex)
                    vertex(commands, cmd, c, vtx, dest, joints, weights, n);
                else
                    skip(commands, cmd, op);
                break;
        }
    }

    /**
     * @param commands display-list reader, on the mode word
     * @param c cursor whose matrix mode is updated
     * @see "GBATEK DS 3D Video, MTX_MODE"
     */
    private static void mode(MemBuf.MemBufReader commands, Cursor c)
    {
        c.mode = GxMtxMode.from(commands.readInt());
    }

    /**
     * @param commands display-list reader, on the slot word
     * @param c draw cursor
     * @param machine stack
     * @see "GBATEK DS 3D Video, MTX_RESTORE"
     */
    private static void restore(MemBuf.MemBufReader commands, Cursor c, SbcMachine machine)
    {
        int slot = commands.readInt() & 31;
        if (c.positions())
        {
            c.draw = machine.slot(slot);
            SbcMachine.Bind stored = machine.slotBind(slot);
            if (stored != null)
                c.bind = stored;
        }
    }

    /**
     * @param commands display-list reader, on the three scale words
     * @param c draw cursor
     * @see "GBATEK DS 3D Video, MTX_SCALE"
     */
    private static void scale(MemBuf.MemBufReader commands, Cursor c)
    {
        double x = fx(commands);
        double y = fx(commands);
        double z = fx(commands);

        if (c.positions())
            c.draw = NitroMtx.mul(NitroMtx.scale(x, y, z), c.draw);
    }

    /**
     * @param commands display-list reader, on the vertex parameters
     * @param cmd vertex command
     * @param c draw cursor
     * @param vtx running vertex
     * @param dest posed output
     * @param joints per-vertex joints
     * @param weights per-vertex weights
     * @param n vertex index
     * @see "GBATEK DS 3D Video, VTX_16, VTX_10, VTX_XY, VTX_XZ, VTX_YZ and VTX_DIFF"
     */
    private static void vertex(MemBuf.MemBufReader commands, GeometryCommand cmd, Cursor c, double[] vtx, float[] dest,
            int[][] joints, double[][] weights, int n)
    {
        readVertex(commands, cmd, vtx);
        NitroMtx.apply(c.draw, vtx[0], vtx[1], vtx[2], dest, n * 3);
        joints[n] = c.bind.joints;
        weights[n] = c.bind.weights;
    }

    /**
     * @param commands display-list reader, on the vertex parameters
     * @param cmd vertex command
     * @param vtx running vertex, updated in place
     * @see "GBATEK DS 3D Video, vertex command parameter widths"
     */
    private static void readVertex(MemBuf.MemBufReader commands, GeometryCommand cmd, double[] vtx)
    {
        switch (cmd)
        {
            case VTX_16:
                vtx16(commands, vtx);
                break;
            case VTX_10:
            case VTX_DIFF:
                vtx10(commands, cmd, vtx);
                break;
            default:
                vtxPair(commands, cmd, vtx);
                break;
        }
    }

    /**
     * @param commands display-list reader, on the two parameter words
     * @param vtx running vertex
     * @see "GBATEK DS 3D Video, VTX_16"
     */
    private static void vtx16(MemBuf.MemBufReader commands, double[] vtx)
    {
        int p1 = commands.readInt();
        int p2 = commands.readInt();

        vtx[0] = (short) (p1 & 0xFFFF) / 4096.0;
        vtx[1] = (short) ((p1 >> 16) & 0xFFFF) / 4096.0;
        vtx[2] = (short) (p2 & 0xFFFF) / 4096.0;
    }

    /**
     * @param commands display-list reader, on the parameter word
     * @param cmd {@link GeometryCommand#VTX_10} replaces the vertex, {@link GeometryCommand#VTX_DIFF} adds a delta
     * @param vtx running vertex
     * @see "GBATEK DS 3D Video, VTX_10 and VTX_DIFF"
     */
    private static void vtx10(MemBuf.MemBufReader commands, GeometryCommand cmd, double[] vtx)
    {
        int w = commands.readInt();
        double k = cmd == GeometryCommand.VTX_10 ? 64.0 : 4096.0;
        double x = s10(w) / k;
        double y = s10(w >> 10) / k;
        double z = s10(w >> 20) / k;

        if (cmd == GeometryCommand.VTX_10)
        {
            vtx[0] = x;
            vtx[1] = y;
            vtx[2] = z;
        }
        else
        {
            vtx[0] += x;
            vtx[1] += y;
            vtx[2] += z;
        }
    }

    /**
     * @param commands display-list reader, on the parameter word
     * @param cmd {@link GeometryCommand#VTX_XY}, {@link GeometryCommand#VTX_XZ} or {@link GeometryCommand#VTX_YZ}
     * @param vtx running vertex
     * @see "GBATEK DS 3D Video, VTX_XY, VTX_XZ and VTX_YZ"
     */
    private static void vtxPair(MemBuf.MemBufReader commands, GeometryCommand cmd, double[] vtx)
    {
        int w = commands.readInt();
        double a = (short) (w & 0xFFFF) / 4096.0;
        double b = (short) ((w >> 16) & 0xFFFF) / 4096.0;

        switch (cmd)
        {
            case VTX_XY:
                vtx[0] = a;
                vtx[1] = b;
                break;
            case VTX_XZ:
                vtx[0] = a;
                vtx[2] = b;
                break;
            default:
                vtx[1] = a;
                vtx[2] = b;
                break;
        }
    }

    /**
     * @param c draw cursor
     * @see "GBATEK DS 3D Video, MTX_IDENTITY"
     */
    private static void identity(Cursor c)
    {
        if (c.positions())
            c.draw = NitroMtx.identity();
    }

    /**
     * @param commands display-list reader, on the three translation words
     * @param c draw cursor
     * @see "GBATEK DS 3D Video, MTX_TRANS"
     */
    private static void trans(MemBuf.MemBufReader commands, Cursor c)
    {
        double[] t = NitroMtx.identity();
        t[9] = fx(commands);
        t[10] = fx(commands);
        t[11] = fx(commands);

        if (c.positions())
            c.draw = NitroMtx.mul(t, c.draw);
    }

    /**
     * @param commands display-list reader, on the parameters
     * @param cmd command whose parameters are skipped
     * @param op opcode, kept for the exception text
     * @see "GBATEK DS 3D Video, command parameter words"
     */
    private static void skip(MemBuf.MemBufReader commands, GeometryCommand cmd, int op)
    {
        if (cmd == GeometryCommand.NOP || cmd == GeometryCommand.END_VTXS)
            return;

        if (cmd.parameterWord)
        {
            commands.skip(4);
            return;
        }

        throw unknown(op);
    }

    /**
     * @param op geometry opcode that this pose does not consume
     * @return the rejection
     * @see "GBATEK DS 3D Video, command parameter words"
     */
    private static IllegalArgumentException unknown(int op)
    {
        return new IllegalArgumentException(String.format("Unhandled display-list opcode 0x%02X", op));
    }

    /**
     * @param commands display-list reader, on a fixed-point word
     * @return the word as 1.19.12
     * @see "NitroSDK fx.h fx32"
     */
    private static double fx(MemBuf.MemBufReader commands)
    {
        return commands.readInt() / 4096.0;
    }

    /**
     * @param v low 10 bits
     * @return the signed 10-bit value
     * @see "GBATEK DS 3D Video, VTX_10 component packing"
     */
    private static int s10(int v)
    {
        int bits = v & 0x3FF;
        if ((bits & 0x200) == 0)
            return bits;

        return bits - 0x400;
    }

    /** Draw matrix, the binding it came from, and the geometry-engine matrix mode. */
    private static final class Cursor
    {
        double[] draw;
        SbcMachine.Bind bind;
        GxMtxMode mode = GxMtxMode.GX_MTXMODE_POSITION_VECTOR;

        private Cursor(double[] draw, SbcMachine.Bind bind)
        {
            this.draw = draw;
            this.bind = bind;
        }

        /**
         * @return true when MTX_RESTORE and MTX_SCALE touch the position matrix
         * @see "NitroSDK gx g3.h GX_MTXMODE_POSITION and GX_MTXMODE_POSITION_VECTOR"
         */
        boolean positions()
        {
            return mode == GxMtxMode.GX_MTXMODE_POSITION || mode == GxMtxMode.GX_MTXMODE_POSITION_VECTOR;
        }
    }
}
