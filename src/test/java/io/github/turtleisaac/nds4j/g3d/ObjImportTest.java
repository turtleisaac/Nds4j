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

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the source-format front end: {@link ObjImporter} (Wavefront OBJ &rarr; flat vertex arrays) composed
 * with {@link ModelBuilder} (arrays &rarr; a full NSBMD), read back by the production {@link ModelSet}. This
 * is the OBJ&rarr;NSB* converter proven end-to-end &mdash; import geometry, author a real model, decode it.
 */
@DisplayName("OBJ import -> NSBMD authoring (source->NSB* front end)")
public class ObjImportTest
{
    @Test
    @DisplayName("OBJ faces (quads, negative indices, v/vt corners) parse to deduplicated arrays")
    void parsesObjGeometry()
    {
        // A unit quad as two representations: one explicit triangle-pair face split and a 4-gon face.
        String obj = String.join("\n",
                "v 0 0 0", "v 1 0 0", "v 1 1 0", "v 0 1 0",
                "vt 0 0", "vt 1 0", "vt 1 1", "vt 0 1",
                "f 1/1 2/2 3/3 4/4",          // a quad -> fan-triangulated into 2 triangles
                "f -4/1 -1/4 -2/3");           // negative (relative) indices
        ObjImporter imp = ObjImporter.parse(obj);

        assertThat(imp.hasTexcoords()).isTrue();
        assertThat(imp.getPositions()).hasSize(4 * 3);   // 4 unique v/vt corners
        // quad -> 2 tris, plus 1 explicit tri = 3 triangles = 9 indices
        assertThat(imp.getTriangles()).hasSize(9);
        for (int idx : imp.getTriangles())
            assertThat(idx).isBetween(0, 3);

        // V is flipped for the DS (top-left origin): OBJ vt v=1 -> texel row 0.
        float[] texels = imp.texcoordsInTexels(16, 16);
        // corner 2 was "vt 1 1" -> (16, 0) after flip
        assertThat(texels[2 * 2]).isEqualTo(16f);
        assertThat(texels[2 * 2 + 1]).isEqualTo(0f);
    }

    @Test
    @DisplayName("an imported OBJ authors an NSBMD the production reader decodes to the same geometry")
    void importAuthorsReadableNsbmd()
    {
        // An octahedron centred at the origin (6 verts, 8 triangular faces).
        String obj = String.join("\n",
                "v 2 0 0", "v -2 0 0", "v 0 2 0", "v 0 -2 0", "v 0 0 2", "v 0 0 -2",
                "f 1 3 5", "f 3 2 5", "f 2 4 5", "f 4 1 5",
                "f 3 1 6", "f 2 3 6", "f 4 2 6", "f 1 4 6");
        ObjImporter imp = ObjImporter.parse(obj);
        assertThat(imp.getTriangles()).hasSize(8 * 3);

        byte[] nsbmd = ModelBuilder.buildUntextured("octa", imp.getPositions(), imp.getTriangles());
        ModelSet ms = new ModelSet(nsbmd);
        assertThat(ms.getModels()).hasSize(1);
        Model m = ms.getModels().get(0);

        assertThat(m.getName()).isEqualTo("octa");
        assertThat(m.getMeshes().get(0).getTriangleCount()).isEqualTo(8);
        // vertex-count oracle: the display list emits exactly what the header declares (3 per triangle)
        assertThat(m.getVertexCount()).isEqualTo(m.getExpectedVertexCount());
        assertThat(m.getVertexCount()).isEqualTo(8 * 3);

        // Decoded geometry reproduces the authored octahedron extents (±2 on each axis).
        float[][] box = m.getHeaderBoundingBox();
        for (int c = 0; c < 3; c++)
        {
            assertThat(box[0][c]).as("box min axis %d", c).isCloseTo(-2f, Offset.offset(0.01f));
            assertThat(box[1][c]).as("box max axis %d", c).isCloseTo(2f, Offset.offset(0.01f));
        }
        // Every decoded vertex is one of the six authored octahedron poles.
        float[] p = m.getMeshes().get(0).getPositions();
        for (int i = 0; i < p.length; i += 3)
        {
            float sum = Math.abs(p[i]) + Math.abs(p[i + 1]) + Math.abs(p[i + 2]);
            assertThat(sum).as("vertex lies on the |x|+|y|+|z|=2 octahedron").isCloseTo(2f, Offset.offset(0.01f));
        }

        assertThat(ms.save()).as("authored NSBMD round-trips its own bytes").isEqualTo(nsbmd);
    }

    @Test
    @DisplayName("ModelBuilder picks a posScale so large geometry survives the fixed-point range")
    void largeGeometryGetsPosScale()
    {
        // A big triangle (coords up to 100) must not overflow VTX_16: the builder scales it down and the
        // decoder scales it back, so the decoded geometry still matches within fixed-point precision.
        float[] pos = {0, 0, 0,  100, 0, 0,  0, 80, 0};
        int[] tris = {0, 1, 2};
        byte[] nsbmd = ModelBuilder.buildUntextured("big", pos, tris);
        Model m = new ModelSet(nsbmd).getModels().get(0);

        float[] p = m.getMeshes().get(0).getPositions();
        int[] t = m.getMeshes().get(0).getTriangleIndices();
        for (int k = 0; k < 3; k++)
            for (int c = 0; c < 3; c++)
                assertThat(p[t[k] * 3 + c]).as("large vertex survives posScale round-trip")
                        .isCloseTo(pos[tris[k] * 3 + c], Offset.offset(0.05f));
    }
}
