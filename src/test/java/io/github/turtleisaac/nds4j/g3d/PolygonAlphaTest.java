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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Polygon alpha 0 is the NNS transparent skip ({@link PolygonDraw}), including when the wireframe flag is set.
 */
@DisplayName("polygon alpha 0")
public class PolygonAlphaTest
{
    /** Alpha 0 leaves the gradient; alpha 31 paints the quad. */
    @Test
    @DisplayName("alpha 0 draws no fragments and alpha 31 draws the quad")
    void alphaZeroDrawsNothing()
    {
        BufferedImage hidden = render(0, false);
        BufferedImage solid = render(31, false);

        assertThat(ink(hidden)).isZero();
        assertThat(ink(solid)).isGreaterThan(200);
    }

    /** Wireframe edges are drawn only when the submitted alpha is non-zero. */
    @Test
    @DisplayName("a non-zero wireframe material draws edges, and alpha 0 still draws nothing")
    void wireframeFollowsSubmittedAlpha()
    {
        int solid = ink(render(31, false));
        int edges = ink(render(31, true));

        assertThat(edges).isGreaterThan(0);
        assertThat(edges).isLessThan(solid / 4);
        assertThat(ink(render(0, true))).isZero();
    }

    /** The material keeps the 5-bit alpha and the wireframe flag from the struct. */
    @Test
    @DisplayName("the parsed material keeps polyAttr alpha and the wireframe flag")
    void materialExposesPolyAttr()
    {
        Model.Material clear = material(0, false);
        Model.Material edges = material(31, true);

        assertThat(clear.getPolygonAlpha()).isZero();
        assertThat(clear.isWireframe()).isFalse();
        assertThat(edges.getPolygonAlpha()).isEqualTo(31);
        assertThat(edges.isWireframe()).isTrue();
        assertThat(PolygonDraw.submitsShape(0)).isFalse();
        assertThat(PolygonDraw.wireframeEdges(0, true)).isFalse();
        assertThat(PolygonDraw.wireframeEdges(31, true)).isTrue();
    }

    /** The export keeps the mesh and records polygon alpha for the viewer blend. */
    @Test
    @DisplayName("glTF keeps an alpha-0 mesh and records the alpha the viewer blends")
    void gltfKeepsTransparentMesh()
    {
        String hidden = GltfExporter.toGltf(model(0, false), textures(0, false));
        String solid = GltfExporter.toGltf(model(31, true), textures(31, true));

        assertThat(hidden).contains("\"meshes\"");
        assertThat(hidden).contains("\"polygonAlpha\":0");
        assertThat(hidden).contains("\"wireframe\":false");
        assertThat(hidden).contains("\"name\":\"mat0\"");
        assertThat(solid).contains("\"polygonAlpha\":31");
        assertThat(solid).contains("\"wireframe\":true");
    }

    /** A mesh with no material still draws, and its glTF carries no polygon alpha. */
    @Test
    @DisplayName("a mesh with no material still draws, and its glTF has no polygon alpha")
    void unboundMeshStillDraws()
    {
        float[] positions = { -1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0 };
        int[] triangles = { 0, 1, 2, 0, 2, 3 };
        ModelSet set = new ModelSet(ModelBuilder.buildUntextured("plain", positions, triangles));
        Model model = set.getModels().get(0);

        assertThat(model.getMeshes().get(0).getMaterial()).isNull();
        assertThat(ink(SoftwareRenderer.render(model, null, 96, 96, 0, 0))).isGreaterThan(50);
        assertThat(GltfExporter.toGltf(model, null)).doesNotContain("polygonAlpha");
    }

    /**
     * @param polygonAlpha 5-bit polygon alpha
     * @param wireframe whether the material flag is set
     * @return a headless render of a camera-facing quad
     */
    private static BufferedImage render(int polygonAlpha, boolean wireframe)
    {
        Model model = model(polygonAlpha, wireframe);
        return SoftwareRenderer.render(model, textures(polygonAlpha, wireframe), 96, 96, 0, 0);
    }

    /**
     * @param polygonAlpha 5-bit polygon alpha
     * @param wireframe whether the material flag is set
     * @return the authored model
     */
    private static Model model(int polygonAlpha, boolean wireframe)
    {
        return new ModelSet(bytes(polygonAlpha, wireframe)).getModels().get(0);
    }

    /**
     * @param polygonAlpha 5-bit polygon alpha
     * @param wireframe whether the material flag is set
     * @return the embedded textures
     */
    private static TextureSet textures(int polygonAlpha, boolean wireframe)
    {
        return new ModelSet(bytes(polygonAlpha, wireframe)).getEmbeddedTextures();
    }

    /**
     * @param polygonAlpha 5-bit polygon alpha
     * @param wireframe whether the material flag is set
     * @return the single material
     */
    private static Model.Material material(int polygonAlpha, boolean wireframe)
    {
        return model(polygonAlpha, wireframe).getMaterials().get(0);
    }

    /**
     * @param polygonAlpha 5-bit polygon alpha
     * @param wireframe whether the material flag is set
     * @return NSBMD bytes for one textured quad
     */
    private static byte[] bytes(int polygonAlpha, boolean wireframe)
    {
        float[] positions = { -1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0 };
        float[] uv = { 0, 0, 8, 0, 8, 8, 0, 8 };
        int[] triangles = { 0, 1, 2, 0, 2, 3 };
        BufferedImage tex = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++)
                tex.setRGB(x, y, 0xFFFF0000);
        return ModelBuilder.buildTextured("quad", positions, uv, triangles, tex, polygonAlpha, wireframe);
    }

    /**
     * @param img a preview image
     * @return how many pixels differ from the grey gradient
     */
    private static int ink(BufferedImage img)
    {
        int count = 0;
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++)
                if (img.getRGB(x, y) != background(y, img.getHeight()))
                    count++;
        return count;
    }

    /**
     * @param y the row
     * @param height the image height
     * @return the gradient pixel the preview paints on that row
     */
    private static int background(int y, int height)
    {
        int g = 248 - (int) (26.0 * y / height);
        return 0xFF000000 | (g << 16) | (g << 8) | Math.min(255, g + 6);
    }
}
