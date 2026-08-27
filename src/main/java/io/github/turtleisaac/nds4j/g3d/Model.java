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

import java.util.ArrayList;
import java.util.List;

/**
 * A single 3D model decoded from an {@link ModelSet} (NSBMD). A model is a set of named {@link Mesh}es
 * (its "shapes"/polygons); each mesh's triangles are produced by interpreting that shape's Nintendo DS
 * geometry command stream (its "display list"). Vertex positions are decoded from their fixed-point
 * form and multiplied by the model's position scale, so they are already in model space.
 * <p>
 * This is the meaningful, exportable representation of the geometry (see {@link #toObj()}); the raw
 * bytes still round-trip byte-for-byte through {@link ModelSet}. Materials, textures and the skeleton
 * are layered on in later work.
 */
public class Model
{
    private final String name;
    private final double posScale;
    // Vertex/triangle/quad totals the model header declares. Used to validate the interpreter: the
    // number of vertices emitted while walking the display lists must equal expectedVertexCount.
    private final int expectedVertexCount;
    private final int expectedTriangleCount;
    private final int expectedQuadCount;
    private final List<Mesh> meshes = new ArrayList<>();

    Model(byte[] mdl0, int modelStart, String name)
    {
        this.name = name;

        int ofsShp = (int) readU32(mdl0, modelStart + 12);

        // model header (NNS_G3dModelInfo)
        int info = modelStart + 0x14;
        int numShapes = mdl0[info + 5] & 0xFF;
        posScale = readU32(mdl0, info + 8) / 4096.0;
        expectedVertexCount = readU16(mdl0, info + 16);
        expectedTriangleCount = readU16(mdl0, info + 20);
        expectedQuadCount = readU16(mdl0, info + 22);

        // shape set: a dictionary of shapes, each record a byte offset (relative to the shape set) to a
        // 16-byte shape struct that points at its display list.
        int shapeSet = modelStart + ofsShp;
        MemBuf buf = MemBuf.create(mdl0);
        MemBuf.MemBufReader reader = buf.reader();
        reader.setPosition(shapeSet);
        G3dDictionary shapeDict = new G3dDictionary(reader);

        for (int i = 0; i < numShapes && i < shapeDict.size(); i++)
        {
            int shapeStruct = shapeSet + (int) readU32(shapeDict.getRecord(i), 0);
            int dlOffset = (int) readU32(mdl0, shapeStruct + 8);  // relative to the shape struct
            int dlSize = (int) readU32(mdl0, shapeStruct + 12);
            meshes.add(interpretDisplayList(mdl0, shapeStruct + dlOffset, dlSize, shapeDict.getName(i)));
        }
    }

    // Walks a shape's display list (a packed Nintendo DS geometry command stream) and assembles its
    // primitives into a triangle mesh. Commands are packed four opcode bytes per word, followed by
    // each command's parameters in order.
    private Mesh interpretDisplayList(byte[] d, int dlStart, int dlSize, String meshName)
    {
        List<float[]> positions = new ArrayList<>();
        List<float[]> texcoords = new ArrayList<>();
        List<Integer> triangles = new ArrayList<>();

        double[] vtx = new double[3];
        double[] uv = new double[2];
        int primitive = -1;
        List<Integer> primVerts = new ArrayList<>();

        int pos = dlStart;
        int end = dlStart + dlSize;
        while (pos + 4 <= end)
        {
            int[] ops = {d[pos] & 0xFF, d[pos + 1] & 0xFF, d[pos + 2] & 0xFF, d[pos + 3] & 0xFF};
            pos += 4;
            for (int op : ops)
            {
                switch (op)
                {
                    case 0x00: break;                                   // NOP
                    case 0x10: case 0x14: case 0x20: case 0x21:         // MTX_MODE / MTX_RESTORE / COLOR / NORMAL
                    case 0x29: case 0x2A: case 0x2B:                    // POLYGON_ATTR / TEXIMAGE_PARAM / PLTT_BASE
                        pos += 4; break;
                    case 0x1B: pos += 12; break;                         // MTX_SCALE (3 params)
                    case 0x22: {                                        // TEXCOORD (s,t as 1.11.4 fixed)
                        long p = readU32(d, pos); pos += 4;
                        uv[0] = (short) (p & 0xFFFF) / 16.0;
                        uv[1] = (short) ((p >> 16) & 0xFFFF) / 16.0;
                        break;
                    }
                    case 0x23: {                                        // VTX_16
                        long p1 = readU32(d, pos), p2 = readU32(d, pos + 4); pos += 8;
                        vtx[0] = (short) (p1 & 0xFFFF) / 4096.0;
                        vtx[1] = (short) ((p1 >> 16) & 0xFFFF) / 4096.0;
                        vtx[2] = (short) (p2 & 0xFFFF) / 4096.0;
                        emit(vtx, uv, positions, texcoords, primVerts);
                        break;
                    }
                    case 0x24: {                                        // VTX_10 (3x10-bit, 1.3.6 fixed)
                        long p = readU32(d, pos); pos += 4;
                        vtx[0] = signed10((int) p) / 64.0;
                        vtx[1] = signed10((int) (p >> 10)) / 64.0;
                        vtx[2] = signed10((int) (p >> 20)) / 64.0;
                        emit(vtx, uv, positions, texcoords, primVerts);
                        break;
                    }
                    case 0x25: {                                        // VTX_XY
                        long p = readU32(d, pos); pos += 4;
                        vtx[0] = (short) (p & 0xFFFF) / 4096.0;
                        vtx[1] = (short) ((p >> 16) & 0xFFFF) / 4096.0;
                        emit(vtx, uv, positions, texcoords, primVerts);
                        break;
                    }
                    case 0x26: {                                        // VTX_XZ
                        long p = readU32(d, pos); pos += 4;
                        vtx[0] = (short) (p & 0xFFFF) / 4096.0;
                        vtx[2] = (short) ((p >> 16) & 0xFFFF) / 4096.0;
                        emit(vtx, uv, positions, texcoords, primVerts);
                        break;
                    }
                    case 0x27: {                                        // VTX_YZ
                        long p = readU32(d, pos); pos += 4;
                        vtx[1] = (short) (p & 0xFFFF) / 4096.0;
                        vtx[2] = (short) ((p >> 16) & 0xFFFF) / 4096.0;
                        emit(vtx, uv, positions, texcoords, primVerts);
                        break;
                    }
                    case 0x28: {                                        // VTX_DIFF (3x10-bit deltas, 1.3.12 fixed)
                        long p = readU32(d, pos); pos += 4;
                        vtx[0] += signed10((int) p) / 4096.0;
                        vtx[1] += signed10((int) (p >> 10)) / 4096.0;
                        vtx[2] += signed10((int) (p >> 20)) / 4096.0;
                        emit(vtx, uv, positions, texcoords, primVerts);
                        break;
                    }
                    case 0x40: {                                        // BEGIN_VTXS
                        int p = (int) readU32(d, pos); pos += 4;
                        if (primitive >= 0)
                            triangulate(primitive, primVerts, triangles);
                        primitive = p & 3;
                        primVerts = new ArrayList<>();
                        break;
                    }
                    case 0x41: break;                                   // END_VTXS
                    default: break;
                }
            }
        }
        if (primitive >= 0)
            triangulate(primitive, primVerts, triangles);

        float[] pos3 = new float[positions.size() * 3];
        float[] uv2 = new float[texcoords.size() * 2];
        for (int i = 0; i < positions.size(); i++)
        {
            pos3[i * 3] = (float) (positions.get(i)[0] * posScale);
            pos3[i * 3 + 1] = (float) (positions.get(i)[1] * posScale);
            pos3[i * 3 + 2] = (float) (positions.get(i)[2] * posScale);
            uv2[i * 2] = texcoords.get(i)[0];
            uv2[i * 2 + 1] = texcoords.get(i)[1];
        }
        int[] idx = new int[triangles.size()];
        for (int i = 0; i < idx.length; i++)
            idx[i] = triangles.get(i);
        return new Mesh(meshName, pos3, uv2, idx);
    }

    private static void emit(double[] vtx, double[] uv, List<float[]> positions, List<float[]> texcoords, List<Integer> primVerts)
    {
        primVerts.add(positions.size());
        positions.add(new float[]{(float) vtx[0], (float) vtx[1], (float) vtx[2]});
        texcoords.add(new float[]{(float) uv[0], (float) uv[1]});
    }

    // Converts a primitive's ordered vertices into triangle index triples. 0=separate triangles,
    // 1=separate quads, 2=triangle strip, 3=quad strip.
    private static void triangulate(int primitive, List<Integer> v, List<Integer> out)
    {
        int n = v.size();
        switch (primitive)
        {
            case 0:
                for (int i = 0; i + 2 < n; i += 3)
                    addTri(out, v.get(i), v.get(i + 1), v.get(i + 2));
                break;
            case 1:
                for (int i = 0; i + 3 < n; i += 4)
                {
                    addTri(out, v.get(i), v.get(i + 1), v.get(i + 2));
                    addTri(out, v.get(i), v.get(i + 2), v.get(i + 3));
                }
                break;
            case 2:
                for (int i = 2; i < n; i++)
                    if ((i & 1) == 0) addTri(out, v.get(i - 2), v.get(i - 1), v.get(i));
                    else addTri(out, v.get(i - 1), v.get(i - 2), v.get(i));
                break;
            case 3:
                for (int i = 0; i + 3 < n; i += 2)
                {
                    addTri(out, v.get(i), v.get(i + 1), v.get(i + 3));
                    addTri(out, v.get(i), v.get(i + 3), v.get(i + 2));
                }
                break;
        }
    }

    private static void addTri(List<Integer> out, int a, int b, int c)
    {
        out.add(a); out.add(b); out.add(c);
    }

    /** @return this model's name (from the model dictionary) */
    public String getName()
    {
        return name;
    }

    /** @return the model's meshes, one per shape */
    public List<Mesh> getMeshes()
    {
        return meshes;
    }

    /**
     * @return the number of vertices the model header declares (equals the number the display-list
     *         interpreter emits when it decodes correctly)
     */
    public int getExpectedVertexCount()
    {
        return expectedVertexCount;
    }

    /** @return the total number of vertices decoded across all meshes */
    public int getVertexCount()
    {
        int total = 0;
        for (Mesh m : meshes)
            total += m.positions.length / 3;
        return total;
    }

    /**
     * Exports this model to Wavefront OBJ text &mdash; a universally supported, zero-dependency
     * interchange format. Each mesh becomes an OBJ group with its own vertices, texture coordinates and
     * triangle faces.
     * @return the OBJ document as a <code>String</code>
     */
    public String toObj()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(name).append(" exported by Nds4j\n");
        int vBase = 1;
        for (Mesh mesh : meshes)
        {
            sb.append("g ").append(mesh.name).append('\n');
            int vertexCount = mesh.positions.length / 3;
            for (int i = 0; i < vertexCount; i++)
                sb.append("v ").append(mesh.positions[i * 3]).append(' ')
                        .append(mesh.positions[i * 3 + 1]).append(' ').append(mesh.positions[i * 3 + 2]).append('\n');
            for (int i = 0; i < vertexCount; i++)
                sb.append("vt ").append(mesh.texcoords[i * 2]).append(' ').append(mesh.texcoords[i * 2 + 1]).append('\n');
            for (int i = 0; i + 2 < mesh.triangleIndices.length; i += 3)
            {
                int a = vBase + mesh.triangleIndices[i], b = vBase + mesh.triangleIndices[i + 1], c = vBase + mesh.triangleIndices[i + 2];
                sb.append("f ").append(a).append('/').append(a).append(' ')
                        .append(b).append('/').append(b).append(' ').append(c).append('/').append(c).append('\n');
            }
            vBase += vertexCount;
        }
        return sb.toString();
    }

    @Override
    public String toString()
    {
        return String.format("Model[%s, %d meshes, %d vertices]", name, meshes.size(), getVertexCount());
    }

    private static int signed10(int v)
    {
        v &= 0x3FF;
        return v >= 0x200 ? v - 0x400 : v;
    }

    private static int readU16(byte[] d, int o)
    {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    private static long readU32(byte[] d, int o)
    {
        return (d[o] & 0xFFL) | ((d[o + 1] & 0xFFL) << 8) | ((d[o + 2] & 0xFFL) << 16) | ((d[o + 3] & 0xFFL) << 24);
    }

    /** A single drawable mesh: interleaved-free position and texcoord arrays plus triangle indices. */
    public static class Mesh
    {
        private final String name;
        private final float[] positions;   // 3 floats per vertex (model space)
        private final float[] texcoords;    // 2 floats per vertex
        private final int[] triangleIndices; // 3 indices per triangle

        Mesh(String name, float[] positions, float[] texcoords, int[] triangleIndices)
        {
            this.name = name;
            this.positions = positions;
            this.texcoords = texcoords;
            this.triangleIndices = triangleIndices;
        }

        /** @return this mesh's name (its shape name) */
        public String getName() { return name; }
        /** @return the vertex positions, 3 floats (x,y,z) per vertex, in model space */
        public float[] getPositions() { return positions; }
        /** @return the texture coordinates, 2 floats (s,t) per vertex */
        public float[] getTexcoords() { return texcoords; }
        /** @return the triangle indices, 3 per triangle, into the vertex arrays */
        public int[] getTriangleIndices() { return triangleIndices; }
        /** @return the number of vertices */
        public int getVertexCount() { return positions.length / 3; }
        /** @return the number of triangles */
        public int getTriangleCount() { return triangleIndices.length / 3; }
    }
}
