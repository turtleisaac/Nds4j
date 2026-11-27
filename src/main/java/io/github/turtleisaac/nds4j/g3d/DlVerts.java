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
        final SbcMachine.Bind[] binds;

        private Out(float[] positions, SbcMachine.Bind[] binds)
        {
            this.positions = positions;
            this.binds = binds;
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
        SbcMachine.Bind[] binds = new SbcMachine.Bind[count];

        Cursor c = new Cursor(draw, bind);
        Vec3 vtx = Vec3.zero();
        int n = 0;

        MemBuf.MemBufReader commands = MemBuf.create(dl).reader();
        while (commands.getPosition() + 4 <= dl.length && n < count)
        {
            DlWord word = DlWord.read(commands);
            for (int i = 0; i < 4 && n < count; i++)
            {
                GeometryCommand cmd = GeometryCommand.from(word.at(i));
                vtx = step(commands, word.at(i), cmd, c, machine, vtx, dest, binds, n);
                if (cmd != null && cmd.emitsVertex)
                    n++;
            }
        }

        return new Out(dest, binds);
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
    private static Vec3 step(MemBuf.MemBufReader commands, int op, GeometryCommand cmd, Cursor c, SbcMachine machine,
            Vec3 vtx, float[] dest, SbcMachine.Bind[] binds, int n)
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
                    vtx = vertex(commands, cmd, c, vtx, dest, binds, n);
                else
                    skip(commands, cmd, op);
                break;
        }

        return vtx;
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
    private static Vec3 vertex(MemBuf.MemBufReader commands, GeometryCommand cmd, Cursor c, Vec3 vtx, float[] dest,
            SbcMachine.Bind[] binds, int n)
    {
        // The posed vertex and the binding that produced it are stored together.
        vtx = readVertex(commands, cmd, vtx);
        NitroMtx.apply(c.draw, vtx.x, vtx.y, vtx.z, dest, n * 3);
        binds[n] = c.bind;

        return vtx;
    }

    /**
     * @param commands display-list reader, on the vertex parameters
     * @param cmd vertex command
     * @param vtx running vertex, updated in place
     * @see "GBATEK DS 3D Video, vertex command parameter widths"
     */
    private static Vec3 readVertex(MemBuf.MemBufReader commands, GeometryCommand cmd, Vec3 vtx)
    {
        if (cmd == GeometryCommand.VTX_16)
            return Fx.vtx16(commands.readInt(), commands.readInt());

        if (cmd == GeometryCommand.VTX_10)
            return Fx.vtx10(commands.readInt(), 64.0);

        if (cmd == GeometryCommand.VTX_DIFF)
            return vtx.add(Fx.vtx10(commands.readInt(), 4096.0));

        return Fx.vertexPair(commands.readInt(), cmd, vtx);
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
        // MTX_SCALE and MTX_TRANS store fx32 components.
        return Fx.readFx32(commands);
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
