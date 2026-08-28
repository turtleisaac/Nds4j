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
 * command's parameters) that {@link Model} interprets to produce a mesh; this class {@link #encode}s a
 * triangle mesh back into a valid stream and {@link #decode}s one, so a converter can author geometry.
 * <p>
 * The encoder emits a straightforward, correct stream: each command as its own opcode word (padded with
 * {@code NOP}s, which consume no parameters) followed by its parameters, drawing separate triangles with
 * {@code TEXCOORD}/{@code VTX_16} per vertex. Positions are the mesh's <em>raw</em> (pre-{@code posScale})
 * coordinates &mdash; the 1.3.12 fixed range VTX_16 stores; texcoords are texel units (1.11.4). It does
 * not reproduce a retail list's exact stripping/compression byte-for-byte (the DS packs geometry many
 * valid ways), but the round-trip is <b>geometry-exact</b>: {@code decode(encode(mesh))} reproduces the
 * same triangles. Retail files still round-trip their bytes verbatim through {@link Model}/{@link ModelSet}.
 */
public final class DisplayList
{
    private DisplayList() {}

    private static final int NOP = 0x00, TEXCOORD = 0x22, VTX_16 = 0x23, BEGIN_VTXS = 0x40, END_VTXS = 0x41;

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
