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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link ImdImporter} &mdash; the native {@code .imd} &rarr; NSBMD translator, the byte-for-byte
 * replacement for Nintendo's {@code g3dcvtr}. The fixtures are real {@code .imd} models and the exact
 * NSBMD bytes {@code g3dcvtr -emdl} produced from them (checked in so the test needs neither the tool nor
 * wine): the translator must reproduce those bytes <b>byte-identically</b>, and the result must decode.
 */
@DisplayName("ImdImporter (.imd -> NSBMD, byte-identical to g3dcvtr)")
public class ImdImporterTest
{
    private static byte[] resource(String name)
    {
        try (InputStream in = ImdImporterTest.class.getResourceAsStream("/imd/" + name))
        {
            if (in == null) throw new IllegalStateException("missing test resource /imd/" + name);
            return in.readAllBytes();
        }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private void assertByteExact(String base)
    {
        String imd = new String(resource(base + ".imd"), StandardCharsets.ISO_8859_1);
        byte[] expected = resource(base + ".nsbmd");
        byte[] actual = ImdImporter.toNsbmd(imd, base);
        assertThat(actual)
                .as("%s.imd translates byte-for-byte to g3dcvtr's NSBMD", base)
                .isEqualTo(expected);

        // and the output is a real model the production decoder reads (vertex-count oracle)
        ModelSet ms = new ModelSet(actual);
        assertThat(ms.getModels()).hasSize(1);
        Model m = ms.getModels().get(0);
        assertThat(m.getName()).isEqualTo(base);
        assertThat(m.getMeshes()).isNotEmpty();
        assertThat(m.getVertexCount()).isEqualTo(m.getExpectedVertexCount());
    }

    // -eboth: the model with its texture embedded as a TEX0 block, vs g3dcvtr -eboth.
    private void assertByteExactWithTextures(String base)
    {
        String imd = new String(resource(base + ".imd"), StandardCharsets.ISO_8859_1);
        byte[] expected = resource(base + "_both.nsbmd");
        byte[] actual = ImdImporter.toNsbmdWithTextures(imd, base);
        assertThat(actual)
                .as("%s.imd translates (with textures) byte-for-byte to g3dcvtr's NSBMD", base)
                .isEqualTo(expected);

        // the embedded texture decodes to the authored palette16 image
        ModelSet ms = new ModelSet(actual);
        assertThat(ms.hasEmbeddedTextures()).isTrue();
        TextureSet tex = ms.getEmbeddedTextures();
        assertThat(tex.getTextures()).hasSize(1);
        TextureSet.Texture t = tex.getTextures().get(0);
        assertThat(t.getWidth()).isEqualTo(16);
        assertThat(t.getHeight()).isEqualTo(16);
        assertThat(ms.getModels().get(0).getMeshes().get(0).getMaterial().getTextureName()).isEqualTo(t.getName());
    }

    @Test
    @DisplayName("a billboard, hardware-lit textured model matches g3dcvtr byte-for-byte")
    void rockMatchesG3dcvtr() { assertByteExact("rock"); }

    @Test
    @DisplayName("a non-billboard, vertex-coloured textured model matches g3dcvtr byte-for-byte")
    void bookMatchesG3dcvtr() { assertByteExact("book"); }

    @Test
    @DisplayName("with the texture embedded (-eboth), the whole MDL0+TEX0 matches g3dcvtr byte-for-byte")
    void embeddedTexturesMatchG3dcvtr() { assertByteExactWithTextures("rock"); assertByteExactWithTextures("book"); }

    @Test
    @DisplayName("a two-material, two-shape model (shared texture) matches g3dcvtr byte-for-byte")
    void multiShapeSharedTexture()
    {
        assertByteExact("two");
        Model m = new ModelSet(ImdImporter.toNsbmd(new String(resource("two.imd"), StandardCharsets.ISO_8859_1), "two")).getModels().get(0);
        assertThat(m.getMeshes()).hasSize(2);
        assertThat(m.getMaterials()).hasSize(2);
    }

    @Test
    @DisplayName("a two-material, two-shape model with two distinct textures matches g3dcvtr byte-for-byte")
    void multiShapeDistinctTextures() { assertByteExact("twotex"); }
}
