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

import java.awt.image.BufferedImage;

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
    @DisplayName("a textured OBJ authors an NSBMD with a bound, embedded, pixel-exact texture")
    void importAuthorsTexturedNsbmd()
    {
        // A single textured quad (two triangles) mapping the whole texture.
        String obj = String.join("\n",
                "v -1 -1 0", "v 1 -1 0", "v 1 1 0", "v -1 1 0",
                "vt 0 0", "vt 1 0", "vt 1 1", "vt 0 1",
                "f 1/1 2/2 3/3 4/4");
        ObjImporter imp = ObjImporter.parse(obj);
        assertThat(imp.hasTexcoords()).isTrue();

        int tw = 16, th = 16;
        BufferedImage tex = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < th; y++)
            for (int x = 0; x < tw; x++)
            {
                // 5-bit-aligned channels so direct BGR555 is lossless (pixel-exact readback)
                int r = (x * 8) & 0xF8, g = (y * 8) & 0xF8, b = (((x + y) * 4) & 0xF8);
                tex.setRGB(x, y, 0xFF000000 | (r << 16) | (g << 8) | b);
            }

        float[] uv = imp.texcoordsInTexels(tw, th);
        byte[] nsbmd = ModelBuilder.buildTextured("quad", imp.getPositions(), uv, imp.getTriangles(), tex);

        ModelSet ms = new ModelSet(nsbmd);
        Model m = ms.getModels().get(0);
        assertThat(ms.hasEmbeddedTextures()).isTrue();

        // the material binds the embedded texture by name, and the mesh binds the material
        assertThat(m.getMaterials()).hasSize(1);
        assertThat(m.getMaterials().get(0).getTextureName()).isEqualTo("tex0");
        assertThat(m.getMeshes().get(0).getMaterial()).isNotNull();
        assertThat(m.getMeshes().get(0).getMaterial().getName()).isEqualTo("mat0");

        // the embedded texture decodes to the exact authored image
        TextureSet embedded = ms.getEmbeddedTextures();
        assertThat(embedded.getTextures()).hasSize(1);
        TextureSet.Texture t = embedded.getTextures().get(0);
        assertThat(t.getName()).isEqualTo("tex0");
        assertThat(t.getWidth()).isEqualTo(tw);
        assertThat(t.getHeight()).isEqualTo(th);
        BufferedImage decoded = embedded.getImage(t);
        for (int y = 0; y < th; y++)
            for (int x = 0; x < tw; x++)
                assertThat(decoded.getRGB(x, y) & 0xFFFFFF)
                        .as("embedded pixel (%d,%d) survives author->read", x, y)
                        .isEqualTo(tex.getRGB(x, y) & 0xFFFFFF);

        assertThat(ms.save()).as("authored textured NSBMD round-trips its own bytes").isEqualTo(nsbmd);
    }

    @Test
    @DisplayName("a multi-part model authors N shapes, each bound to its own material and texture")
    void authorsMultiMaterialModel()
    {
        // Three quads at different heights, each with a distinct solid texture.
        java.util.List<ModelBuilder.Part> parts = new java.util.ArrayList<>();
        int[] colors = {0xF80000, 0x00F800, 0x0000F8};
        for (int i = 0; i < 3; i++)
        {
            float y0 = -3 + i * 2, y1 = y0 + 2;
            float[] pos = {-2, y0, 0,  2, y0, 0,  2, y1, 0,  -2, y1, 0};
            int[] tris = {0, 1, 2, 0, 2, 3};
            BufferedImage tex = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) tex.setRGB(x, y, 0xFF000000 | colors[i]);
            float[] uv = {0, 8,  8, 8,  8, 0,  0, 0};
            parts.add(new ModelBuilder.Part("p" + i, pos, uv, tris, tex));
        }

        byte[] nsbmd = ModelBuilder.buildMultiTextured("bands", parts);
        ModelSet ms = new ModelSet(nsbmd);
        Model m = ms.getModels().get(0);

        assertThat(m.getMeshes()).hasSize(3);
        assertThat(m.getMaterials()).hasSize(3);
        assertThat(ms.getEmbeddedTextures().getTextures()).hasSize(3);
        // the whole geometry decodes (vertex-count oracle over all three shapes)
        assertThat(m.getVertexCount()).isEqualTo(m.getExpectedVertexCount());
        assertThat(m.getVertexCount()).isEqualTo(3 * 6); // 3 quads * 2 tris * 3 verts

        // each shape binds its own material -> its own texture, and each texture is the authored solid color
        for (int i = 0; i < 3; i++)
        {
            Model.Mesh mesh = m.getMeshes().get(i);
            assertThat(mesh.getMaterial()).as("mesh %d has a material", i).isNotNull();
            String texName = mesh.getMaterial().getTextureName();
            assertThat(texName).isNotNull();
            assertThat(ms.getEmbeddedTextures().getImage(texName).getRGB(0, 0) & 0xFFFFFF)
                    .as("shape %s samples its own texture color", mesh.getName())
                    .isEqualTo(colors[Integer.parseInt(mesh.getName().substring(mesh.getName().length() - 1))]);
        }

        assertThat(ms.save()).as("authored multi-material NSBMD round-trips its own bytes").isEqualTo(nsbmd);
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
