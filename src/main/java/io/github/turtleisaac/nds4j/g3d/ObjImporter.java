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

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a Wavefront <b>OBJ</b> mesh into the flat {@code positions}/{@code texcoords}/{@code triangles}
 * arrays the NSB* encoders take &mdash; the source-format front end that was the last missing piece of the
 * converter. Feed the result to {@link ModelBuilder} to author an NSBMD, or to {@link DisplayList#encode}
 * directly.
 * <p>
 * Supports the geometry subset Gen IV models need: {@code v} positions, {@code vt} texcoords and {@code f}
 * faces (triangles and larger polygons, fan-triangulated; negative/relative indices; {@code v/vt/vn} corner
 * syntax). Normals, groups, smoothing and materials are ignored (the DS lights per-face). Each distinct
 * {@code v/vt} corner becomes one output vertex, so texcoords travel with positions.
 */
public final class ObjImporter
{
    private final float[] positions;
    private final float[] texcoords;
    private final int[] triangles;
    private final boolean hasTexcoords;

    private ObjImporter(float[] positions, float[] texcoords, int[] triangles, boolean hasTexcoords)
    {
        this.positions = positions;
        this.texcoords = texcoords;
        this.triangles = triangles;
        this.hasTexcoords = hasTexcoords;
    }

    /** @return vertex positions (x,y,z triples) */
    public float[] getPositions() { return positions; }
    /** @return normalised texcoords (u,v pairs), one per position; all zero if the OBJ had none */
    public float[] getTexcoords() { return texcoords; }
    /** @return whether the OBJ actually carried texcoords */
    public boolean hasTexcoords() { return hasTexcoords; }
    /** @return triangle indices (3 per triangle) into the vertex arrays */
    public int[] getTriangles() { return triangles; }

    /**
     * Scales texcoords from OBJ's normalised {@code [0,1]} range into texel units for a given texture size,
     * flipping V (OBJ's origin is bottom-left, the DS's is top-left).
     * @param texWidth the bound texture's width in texels
     * @param texHeight the bound texture's height in texels
     * @return texel-unit texcoords ready for {@link DisplayList#encode}
     */
    public float[] texcoordsInTexels(int texWidth, int texHeight)
    {
        float[] out = new float[texcoords.length];
        for (int i = 0; i < texcoords.length; i += 2)
        {
            out[i] = texcoords[i] * texWidth;
            out[i + 1] = (1 - texcoords[i + 1]) * texHeight;
        }
        return out;
    }

    /**
     * Parses OBJ source text.
     * @param obj the full contents of an .obj file
     * @return an importer exposing the parsed arrays
     */
    public static ObjImporter parse(String obj)
    {
        List<float[]> v = new ArrayList<>();     // positions
        List<float[]> vt = new ArrayList<>();    // texcoords
        List<int[]> corners = new ArrayList<>(); // each unique {vIndex, vtIndex} -> an output vertex
        java.util.Map<Long, Integer> cornerMap = new java.util.HashMap<>();
        List<Integer> tris = new ArrayList<>();
        boolean anyVt = false;

        for (String rawLine : obj.split("\n"))
        {
            String line = rawLine.trim();
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] tok = line.split("\\s+");
            switch (tok[0])
            {
                case "v":
                    v.add(new float[]{parse(tok, 1), parse(tok, 2), parse(tok, 3)});
                    break;
                case "vt":
                    vt.add(new float[]{parse(tok, 1), parse(tok, 2)});
                    anyVt = true;
                    break;
                case "f":
                {
                    // Resolve each face corner to a unique output-vertex index, then fan-triangulate.
                    int[] faceVerts = new int[tok.length - 1];
                    for (int i = 1; i < tok.length; i++)
                        faceVerts[i - 1] = resolveCorner(tok[i], v.size(), vt.size(), corners, cornerMap);
                    for (int i = 1; i + 1 < faceVerts.length; i++)
                    {
                        tris.add(faceVerts[0]);
                        tris.add(faceVerts[i]);
                        tris.add(faceVerts[i + 1]);
                    }
                    break;
                }
                default:
                    break; // vn, groups, smoothing, materials: ignored
            }
        }

        float[] positions = new float[corners.size() * 3];
        float[] texcoords = new float[corners.size() * 2];
        for (int i = 0; i < corners.size(); i++)
        {
            int[] c = corners.get(i);
            float[] p = v.get(c[0]);
            positions[i * 3] = p[0];
            positions[i * 3 + 1] = p[1];
            positions[i * 3 + 2] = p[2];
            if (c[1] >= 0 && c[1] < vt.size())
            {
                float[] t = vt.get(c[1]);
                texcoords[i * 2] = t[0];
                texcoords[i * 2 + 1] = t[1];
            }
        }
        int[] triangles = new int[tris.size()];
        for (int i = 0; i < tris.size(); i++) triangles[i] = tris.get(i);
        return new ObjImporter(positions, texcoords, triangles, anyVt);
    }

    // Maps an OBJ "v", "v/vt" or "v/vt/vn" corner token to a stable output-vertex index (deduplicated).
    private static int resolveCorner(String token, int numV, int numVt, List<int[]> corners,
                                     java.util.Map<Long, Integer> cornerMap)
    {
        String[] parts = token.split("/");
        int vi = objIndex(parts[0], numV);
        int ti = parts.length > 1 && !parts[1].isEmpty() ? objIndex(parts[1], numVt) : -1;
        long key = ((long) vi << 32) | (ti & 0xFFFFFFFFL);
        Integer existing = cornerMap.get(key);
        if (existing != null) return existing;
        int idx = corners.size();
        corners.add(new int[]{vi, ti});
        cornerMap.put(key, idx);
        return idx;
    }

    // OBJ indices are 1-based; negative indices count back from the current end of the list.
    private static int objIndex(String s, int count)
    {
        int i = Integer.parseInt(s.trim());
        return i < 0 ? count + i : i - 1;
    }

    private static float parse(String[] tok, int i)
    {
        return i < tok.length ? Float.parseFloat(tok[i]) : 0f;
    }
}
