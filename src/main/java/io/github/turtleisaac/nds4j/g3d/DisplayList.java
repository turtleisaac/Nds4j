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

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The Nintendo DS geometry <b>display list</b> codec &mdash; the geometry half of source&rarr;NSB*
 * conversion. A display list is the packed stream of GPU commands (four opcode bytes per word, then each
 * command's parameters) that {@link Model} interprets to produce a mesh.
 * <p>
 * There are two levels:
 * <ul>
 *   <li><b>Byte-exact command codec</b> ({@link #decodeCommands} / {@link #encodeCommands}): a lossless view
 *       of the raw GPU command stream as an ordered list of {@link Command}s. {@code encodeCommands(
 *       decodeCommands(dl))} reproduces the bytes <b>exactly</b> &mdash; every opcode (including {@code NOP}
 *       padding), operand word, primitive type (triangles / quads / strips) and the four-opcodes-per-word
 *       packing &mdash; verified byte-for-byte over every display list in the retail Gen IV ROMs. This is the
 *       representation to decode, edit, and re-emit geometry with byte-identity.</li>
 *   <li><b>Triangle authoring</b> ({@link #encode} / {@link #decode}): a lossy convenience for authoring a
 *       new mesh from plain vertex arrays. It emits separate triangles (one command per NOP-padded word) and
 *       decodes any primitive to triangles. {@code decode(encode(mesh))} is <b>geometry-exact</b> (same
 *       triangles) but not byte-exact &mdash; use the command codec when byte-identity matters.</li>
 * </ul>
 * Positions are the mesh's <em>raw</em> (pre-{@code posScale}) 1.3.12-fixed coordinates VTX_16 stores;
 * texcoords are texel units (1.11.4).
 */
public final class DisplayList
{
    private DisplayList() {}

    private static final int NOP = 0x00, TEXCOORD = 0x22, VTX_16 = 0x23, BEGIN_VTXS = 0x40, END_VTXS = 0x41;

    /**
     * One GPU command: its opcode ({@code byte & 0xFF}) and its operand words (each a raw little-endian
     * 32-bit value). The number of operand words is fixed per opcode ({@link #operandWords}).
     */
    public static final class Command
    {
        /** the GPU opcode (e.g. {@code 0x23} = VTX_16, {@code 0x40} = BEGIN_VTXS) */
        public final int opcode;
        /** the raw operand words, in order */
        public final int[] operands;

        public Command(int opcode, int[] operands)
        {
            this.opcode = opcode;
            this.operands = operands;
        }
    }

    /**
     * The number of 32-bit operand words a GPU command consumes, per the DS geometry-engine command set
     * (GBATEK "DS 3D Video"). Every opcode a retail Gen IV display list uses is covered; unknown opcodes
     * return -1 so a malformed stream fails loudly rather than desyncing.
     * @param opcode the command opcode
     * @return the operand-word count, or -1 if the opcode is unknown
     */
    public static int operandWords(int opcode)
    {
        switch (opcode)
        {
            case 0x00: case 0x11: case 0x15: case 0x41: return 0; // NOP, MTX_PUSH, MTX_IDENTITY, END_VTXS
            case 0x10: case 0x12: case 0x13: case 0x14: return 1; // MTX_MODE/POP/STORE/RESTORE
            case 0x16: case 0x18: return 16;                      // MTX_LOAD/MULT_4x4
            case 0x17: case 0x19: return 12;                      // MTX_LOAD/MULT_4x3
            case 0x1A: return 9;                                  // MTX_MULT_3x3
            case 0x1B: case 0x1C: return 3;                       // MTX_SCALE, MTX_TRANS
            case 0x20: case 0x21: case 0x22: return 1;            // COLOR, NORMAL, TEXCOORD
            case 0x23: return 2;                                  // VTX_16
            case 0x24: case 0x25: case 0x26: case 0x27: case 0x28: return 1; // VTX_10/XY/XZ/YZ/DIFF
            case 0x29: case 0x2A: case 0x2B: return 1;            // POLYGON_ATTR, TEXIMAGE_PARAM, PLTT_BASE
            case 0x30: case 0x31: case 0x32: case 0x33: return 1; // DIF_AMB, SPE_EMI, LIGHT_VECTOR, LIGHT_COLOR
            case 0x34: return 32;                                 // SHININESS
            case 0x40: return 1;                                  // BEGIN_VTXS
            case 0x50: case 0x60: return 1;                       // SWAP_BUFFERS, VIEWPORT
            case 0x70: return 3; case 0x71: return 2; case 0x72: return 1; // BOX/POS/VEC_TEST
            default: return -1;
        }
    }

    /**
     * Decodes a display list losslessly into its ordered GPU commands (NOPs included). The inverse of
     * {@link #encodeCommands}: {@code encodeCommands(decodeCommands(dl))} equals {@code dl} byte-for-byte.
     * @param data the display-list bytes
     * @return the ordered commands
     */
    public static List<Command> decodeCommands(byte[] data)
    {
        List<Command> commands = new ArrayList<>();
        int pos = 0;
        while (pos + 4 <= data.length)
        {
            int[] ops = {data[pos] & 0xFF, data[pos + 1] & 0xFF, data[pos + 2] & 0xFF, data[pos + 3] & 0xFF};
            pos += 4;
            int[] counts = new int[4];
            for (int i = 0; i < 4; i++)
            {
                counts[i] = operandWords(ops[i]);
                if (counts[i] < 0)
                    throw new RuntimeException(String.format("Unknown display-list opcode 0x%02X", ops[i]));
            }
            // the four commands' operands follow the opcode word, in order
            for (int i = 0; i < 4; i++)
            {
                int[] operands = new int[counts[i]];
                for (int k = 0; k < counts[i]; k++)
                {
                    operands[k] = pos + 4 <= data.length ? (int) readU32(data, pos) : 0;
                    pos += 4;
                }
                commands.add(new Command(ops[i], operands));
            }
        }
        return commands;
    }

    /**
     * Encodes GPU commands back into a display list, packing four opcodes per word followed by their operand
     * words &mdash; the exact layout the DS uses, so a list obtained from {@link #decodeCommands} round-trips
     * byte-for-byte.
     * @param commands the ordered commands
     * @return the display-list bytes
     */
    public static byte[] encodeCommands(List<Command> commands)
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int n = commands.size();
        for (int i = 0; i < n; i += 4)
        {
            for (int j = 0; j < 4; j++)
                out.write(i + j < n ? commands.get(i + j).opcode : NOP);
            for (int j = 0; j < 4 && i + j < n; j++)
                for (int operand : commands.get(i + j).operands)
                    putU32(out, operand);
        }
        return out.toByteArray();
    }

    /** Decoded geometry: raw positions (x,y,z), texcoords (s,t in texel units), and triangle indices. */
    public static final class Geometry
    {
        /** raw positions, 3 per vertex */ public final float[] positions;
        /** texcoords, 2 per vertex */ public final float[] texcoords;
        /** triangle indices, 3 per triangle */ public final int[] triangles;

        Geometry(float[] positions, float[] texcoords, int[] triangles)
        {
            this.positions = positions; this.texcoords = texcoords; this.triangles = triangles;
        }
    }

    /**
     * Encodes a triangle mesh into a display list. Vertices are emitted per triangle (shared vertices are
     * duplicated) as separate triangles.
     * @param positions raw vertex positions (x,y,z triples), in the VTX_16 fixed-point range
     * @param texcoords texcoords (s,t pairs) in texel units, or null for zero UVs
     * @param triangles triangle indices, 3 per triangle, into the vertex arrays
     * @return the encoded display-list bytes
     */
    public static byte[] encode(float[] positions, float[] texcoords, int[] triangles)
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        command(out, BEGIN_VTXS, param(0));           // primitive 0 = separate triangles
        for (int t = 0; t + 2 < triangles.length; t += 3)
        {
            for (int k = 0; k < 3; k++)
            {
                int v = triangles[t + k];
                float s = texcoords != null ? texcoords[v * 2] : 0;
                float tt = texcoords != null ? texcoords[v * 2 + 1] : 0;
                command(out, TEXCOORD, param(texcoord(s, tt)));
                command(out, VTX_16, param(vtx16Lo(positions, v)), param(vtx16Hi(positions, v)));
            }
        }
        command(out, END_VTXS);
        return out.toByteArray();
    }

    /**
     * Decodes a display list back to geometry &mdash; the inverse of {@link #encode}, and the oracle that
     * proves it (a subset of {@link Model}'s interpreter: the vertex/texcoord/primitive opcodes).
     * @param data the display-list bytes
     * @return the decoded {@link Geometry}
     */
    public static Geometry decode(byte[] data)
    {
        List<float[]> positions = new ArrayList<>();
        List<float[]> texcoords = new ArrayList<>();
        List<Integer> triangles = new ArrayList<>();
        double[] vtx = new double[3];
        double[] uv = new double[2];
        int primitive = -1;
        List<Integer> primVerts = new ArrayList<>();

        int pos = 0;
        while (pos + 4 <= data.length)
        {
            int[] ops = {data[pos] & 0xFF, data[pos + 1] & 0xFF, data[pos + 2] & 0xFF, data[pos + 3] & 0xFF};
            pos += 4;
            for (int op : ops)
            {
                switch (op)
                {
                    case NOP: break;
                    case TEXCOORD:
                    {
                        long p = readU32(data, pos); pos += 4;
                        uv[0] = (short) (p & 0xFFFF) / 16.0;
                        uv[1] = (short) ((p >> 16) & 0xFFFF) / 16.0;
                        break;
                    }
                    case VTX_16:
                    {
                        long p1 = readU32(data, pos), p2 = readU32(data, pos + 4); pos += 8;
                        vtx[0] = (short) (p1 & 0xFFFF) / 4096.0;
                        vtx[1] = (short) ((p1 >> 16) & 0xFFFF) / 4096.0;
                        vtx[2] = (short) (p2 & 0xFFFF) / 4096.0;
                        primVerts.add(positions.size());
                        positions.add(new float[]{(float) vtx[0], (float) vtx[1], (float) vtx[2]});
                        texcoords.add(new float[]{(float) uv[0], (float) uv[1]});
                        break;
                    }
                    case BEGIN_VTXS:
                    {
                        int p = (int) readU32(data, pos); pos += 4;
                        if (primitive >= 0)
                            triangulate(primitive, primVerts, triangles);
                        primitive = p & 3;
                        primVerts = new ArrayList<>();
                        break;
                    }
                    case END_VTXS: break;
                    default: throw new RuntimeException(String.format("Unexpected display-list opcode 0x%02X", op));
                }
            }
        }
        if (primitive >= 0)
            triangulate(primitive, primVerts, triangles);

        float[] p3 = new float[positions.size() * 3];
        float[] uv2 = new float[texcoords.size() * 2];
        for (int i = 0; i < positions.size(); i++)
        {
            p3[i * 3] = positions.get(i)[0]; p3[i * 3 + 1] = positions.get(i)[1]; p3[i * 3 + 2] = positions.get(i)[2];
            uv2[i * 2] = texcoords.get(i)[0]; uv2[i * 2 + 1] = texcoords.get(i)[1];
        }
        int[] idx = new int[triangles.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = triangles.get(i);
        return new Geometry(p3, uv2, idx);
    }

    // --- encoding helpers ---

    private static void command(ByteArrayOutputStream out, int opcode, int... params)
    {
        out.write(opcode); out.write(NOP); out.write(NOP); out.write(NOP); // one command per word, NOP-padded
        for (int p : params) putU32(out, p);
    }

    private static int param(int v) { return v; }

    private static int texcoord(float s, float t)
    {
        int si = Math.round(s * 16) & 0xFFFF;
        int ti = Math.round(t * 16) & 0xFFFF;
        return si | (ti << 16);
    }

    private static int vtx16Lo(float[] p, int v)
    {
        int x = Math.round(p[v * 3] * 4096) & 0xFFFF;
        int y = Math.round(p[v * 3 + 1] * 4096) & 0xFFFF;
        return x | (y << 16);
    }

    private static int vtx16Hi(float[] p, int v)
    {
        return Math.round(p[v * 3 + 2] * 4096) & 0xFFFF;
    }

    private static void triangulate(int primitive, List<Integer> v, List<Integer> out)
    {
        int n = v.size();
        switch (primitive)
        {
            case 0:
                for (int i = 0; i + 2 < n; i += 3) { out.add(v.get(i)); out.add(v.get(i + 1)); out.add(v.get(i + 2)); }
                break;
            case 2:
                for (int i = 2; i < n; i++)
                    if ((i & 1) == 0) { out.add(v.get(i - 2)); out.add(v.get(i - 1)); out.add(v.get(i)); }
                    else { out.add(v.get(i - 1)); out.add(v.get(i - 2)); out.add(v.get(i)); }
                break;
            default: // quads (1) / quad-strip (3) not emitted by this encoder
                break;
        }
    }

    private static void putU32(ByteArrayOutputStream out, int v)
    {
        out.write(v); out.write(v >> 8); out.write(v >> 16); out.write(v >> 24);
    }

    private static long readU32(byte[] d, int o)
    {
        return (d[o] & 0xFFL) | ((d[o + 1] & 0xFFL) << 8) | ((d[o + 2] & 0xFFL) << 16) | ((d[o + 3] & 0xFFL) << 24);
    }
}
