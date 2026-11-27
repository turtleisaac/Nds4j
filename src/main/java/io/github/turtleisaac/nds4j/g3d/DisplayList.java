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
    private DisplayList()
    {
    }

    private static final int NOP = GeometryCommand.NOP.code;
    private static final int TEXCOORD = GeometryCommand.TEXCOORD.code;
    private static final int VTX_16 = GeometryCommand.VTX_16.code;
    private static final int BEGIN_VTXS = GeometryCommand.BEGIN_VTXS.code;
    private static final int END_VTXS = GeometryCommand.END_VTXS.code;

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
        // Every geometry opcode's parameter width lives on the command.
        GeometryCommand cmd = GeometryCommand.from(opcode);
        if (cmd == null)
            return -1;

        return cmd.words;
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
        MemBuf.MemBufReader reader = MemBuf.create(data).reader();
        while (reader.getPosition() + 4 <= data.length)
            readPacked(reader, data.length, commands);

        return commands;
    }

    /**
     * @param reader a reader on an opcode word
     * @param length display-list length
     * @param commands commands decoded so far
     * @see "GBATEK DS 3D Video, geometry command packing"
     */
    private static void readPacked(MemBuf.MemBufReader reader, int length, List<Command> commands)
    {
        // The four opcodes are consumed before any of their parameters.
        DlWord word = DlWord.read(reader);
        int[] counts = counts(word);
        for (int i = 0; i < 4; i++)
            commands.add(new Command(word.at(i), operands(reader, length, counts[i])));
    }

    /**
     * @param word four opcodes
     * @return the parameter-word count of each
     * @see "GBATEK DS 3D Video"
     */
    private static int[] counts(DlWord word)
    {
        int[] counts = new int[4];
        for (int i = 0; i < 4; i++)
            counts[i] = countAt(word, i);

        return counts;
    }

    /**
     * @param word four opcodes
     * @param i which opcode
     * @return that opcode's parameter-word count
     * @see "GBATEK DS 3D Video"
     */
    private static int countAt(DlWord word, int i)
    {
        int count = operandWords(word.at(i));
        if (count < 0)
            throw new RuntimeException(String.format("Unknown display-list opcode 0x%02X", word.at(i)));

        return count;
    }

    /**
     * @param reader a reader on the first parameter word
     * @param length display-list length
     * @param count how many words this opcode takes
     * @return those words, zero when the list ends early
     * @see "GBATEK DS 3D Video"
     */
    private static int[] operands(MemBuf.MemBufReader reader, int length, int count)
    {
        int[] operands = new int[count];
        for (int k = 0; k < count; k++)
            operands[k] = readWord(reader, length);

        return operands;
    }

    /**
     * @param reader a reader on a parameter word
     * @param length display-list length
     * @return the word, or zero when the list has ended
     * @see "GBATEK DS 3D Video"
     */
    private static int readWord(MemBuf.MemBufReader reader, int length)
    {
        if (reader.getPosition() + 4 > length)
            return 0;

        return reader.readInt();
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
        /** raw positions, 3 per vertex */
        public final float[] positions;
        /** texcoords, 2 per vertex */
        public final float[] texcoords;
        /** triangle indices, 3 per triangle */
        public final int[] triangles;

        Geometry(float[] positions, float[] texcoords, int[] triangles)
        {
            this.positions = positions;
            this.texcoords = texcoords;
            this.triangles = triangles;
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
        command(out, BEGIN_VTXS, param(0)); // primitive 0 = separate triangles
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
        int primitive = -1;
        List<Integer> primVerts = new ArrayList<>();

        MemBuf.MemBufReader reader = MemBuf.create(data).reader();
        Walk walk = new Walk();
        while (reader.getPosition() + 4 <= data.length)
            readWord(reader, walk, positions, texcoords, primVerts, triangles);

        primitive = walk.primitive;
        if (primitive >= 0)
            triangulate(primitive, primVerts, triangles);

        float[] p3 = new float[positions.size() * 3];
        float[] uv2 = new float[texcoords.size() * 2];
        for (int i = 0; i < positions.size(); i++)
        {
            p3[i * 3] = positions.get(i)[0];
            p3[i * 3 + 1] = positions.get(i)[1];
            p3[i * 3 + 2] = positions.get(i)[2];
            uv2[i * 2] = texcoords.get(i)[0];
            uv2[i * 2 + 1] = texcoords.get(i)[1];
        }
        int[] idx = new int[triangles.size()];
        for (int i = 0; i < idx.length; i++)
            idx[i] = triangles.get(i);
        return new Geometry(p3, uv2, idx);
    }

    /** Vertex, texcoord and primitive while {@link #decode} walks a list. */
    private static final class Walk
    {
        private Vec3 vtx = Vec3.zero();
        private Uv uv = Uv.zero();
        private int primitive = -1;
    }

    /**
     * @param reader a reader on an opcode word
     * @param walk the running vertex and primitive
     * @param positions emitted positions
     * @param texcoords emitted texcoords
     * @param primVerts vertices of the open primitive
     * @param triangles triangle indices
     * @see "GBATEK DS 3D Video, geometry command packing"
     */
    private static void readWord(MemBuf.MemBufReader reader, Walk walk, List<float[]> positions, List<float[]> texcoords,
            List<Integer> primVerts, List<Integer> triangles)
    {
        // Apply the four opcodes before their parameters are mixed with the next word.
        DlWord word = DlWord.read(reader);
        for (int i = 0; i < 4; i++)
            dispatch(reader, walk, word.at(i), positions, texcoords, primVerts, triangles);
    }

    /**
     * @param reader a reader on this opcode's parameters
     * @param walk the running vertex and primitive
     * @param op geometry opcode
     * @param positions emitted positions
     * @param texcoords emitted texcoords
     * @param primVerts vertices of the open primitive
     * @param triangles triangle indices
     * @see "GBATEK DS 3D Video, TEXCOORD"
     */
    private static void dispatch(MemBuf.MemBufReader reader, Walk walk, int op, List<float[]> positions,
            List<float[]> texcoords, List<Integer> primVerts, List<Integer> triangles)
    {
        if (op == TEXCOORD)
            walk.uv = Fx.texcoord(reader.readInt());
        else
            dispatchVertex(reader, walk, op, positions, texcoords, primVerts, triangles);
    }

    /**
     * @param reader a reader on this opcode's parameters
     * @param walk the running vertex and primitive
     * @param op geometry opcode other than texcoord
     * @param positions emitted positions
     * @param texcoords emitted texcoords
     * @param primVerts vertices of the open primitive
     * @param triangles triangle indices
     * @see "GBATEK DS 3D Video, VTX_16"
     */
    private static void dispatchVertex(MemBuf.MemBufReader reader, Walk walk, int op, List<float[]> positions,
            List<float[]> texcoords, List<Integer> primVerts, List<Integer> triangles)
    {
        if (op == VTX_16)
            walk.vtx = emitVtx(reader, walk.vtx, walk.uv, positions, texcoords, primVerts);
        else
            dispatchBegin(reader, walk, op, primVerts, triangles);
    }

    /**
     * @param reader a reader on this opcode's parameters
     * @param walk the running primitive
     * @param op geometry opcode other than a vertex or texcoord
     * @param primVerts vertices of the open primitive
     * @param triangles triangle indices
     * @see "GBATEK DS 3D Video, BEGIN_VTXS"
     */
    private static void dispatchBegin(MemBuf.MemBufReader reader, Walk walk, int op, List<Integer> primVerts,
            List<Integer> triangles)
    {
        if (op == BEGIN_VTXS)
            walk.primitive = begin(reader, walk.primitive, primVerts, triangles);
        else
            reject(op);
    }

    /**
     * @param op an opcode this decoder does not emit
     * @see "GBATEK DS 3D Video"
     */
    private static void reject(int op)
    {
        if (op == NOP)
            return;

        rejectEnd(op);
    }

    /**
     * @param op an opcode other than NOP
     * @see "GBATEK DS 3D Video, END_VTXS"
     */
    private static void rejectEnd(int op)
    {
        if (op != END_VTXS)
            throw new RuntimeException(String.format("Unexpected display-list opcode 0x%02X", op));
    }

    /**
     * @param reader a reader on the two vertex words
     * @param vtx the running vertex, replaced
     * @param uv the current texcoord
     * @param positions emitted positions
     * @param texcoords emitted texcoords
     * @param primVerts vertices of the open primitive
     * @return the vertex just read
     * @see "GBATEK DS 3D Video, VTX_16"
     */
    private static Vec3 emitVtx(MemBuf.MemBufReader reader, Vec3 vtx, Uv uv, List<float[]> positions,
            List<float[]> texcoords, List<Integer> primVerts)
    {
        // VTX_16 replaces the whole vertex, then the current texcoord is copied with it.
        vtx = Fx.vtx16(reader.readInt(), reader.readInt());
        primVerts.add(positions.size());
        positions.add(new float[] { (float) vtx.x, (float) vtx.y, (float) vtx.z });
        texcoords.add(new float[] { (float) uv.s, (float) uv.t });

        return vtx;
    }

    /**
     * @param reader a reader on the primitive word
     * @param primitive the open primitive, or -1
     * @param primVerts vertices of the open primitive
     * @param triangles triangle indices
     * @return the new primitive type
     * @see "GBATEK DS 3D Video, BEGIN_VTXS"
     */
    private static int begin(MemBuf.MemBufReader reader, int primitive, List<Integer> primVerts, List<Integer> triangles)
    {
        // Close the previous primitive before starting the next.
        int p = reader.readInt();
        if (primitive >= 0)
            triangulate(primitive, primVerts, triangles);

        primVerts.clear();

        return p & 3;
    }

    // --- encoding helpers ---

    private static void command(ByteArrayOutputStream out, int opcode, int... params)
    {
        out.write(opcode);
        out.write(NOP);
        out.write(NOP);
        out.write(NOP); // one command per word, NOP-padded
        for (int p : params)
            putU32(out, p);
    }

    private static int param(int v)
    {
        return v;
    }

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
                for (int i = 0; i + 2 < n; i += 3)
                {
                    out.add(v.get(i));
                    out.add(v.get(i + 1));
                    out.add(v.get(i + 2));
                }
                break;
            case 2:
                for (int i = 2; i < n; i++)
                    if ((i & 1) == 0)
                    {
                        out.add(v.get(i - 2));
                        out.add(v.get(i - 1));
                        out.add(v.get(i));
                    }
                    else
                    {
                        out.add(v.get(i - 1));
                        out.add(v.get(i - 2));
                        out.add(v.get(i));
                    }
                break;
            default: // quads (1) / quad-strip (3) not emitted by this encoder
                break;
        }
    }

    /**
     * @param out the display list being written
     * @param v a parameter word
     * @see "GBATEK DS 3D Video"
     */
    private static void putU32(ByteArrayOutputStream out, int v)
    {
        // The geometry engine reads parameter words little-endian.
        MemBuf buf = MemBuf.create();
        buf.writer().writeInt(v);
        byte[] word = buf.reader().getBuffer();

        // One byte per position, so this stays a straight-line write.
        out.write(word[0]);
        out.write(word[1]);
        out.write(word[2]);
        out.write(word[3]);
    }
}
