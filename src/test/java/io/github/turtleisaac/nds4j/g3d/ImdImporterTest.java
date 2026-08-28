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

        // and the output is a real model the production decoder reads
        ModelSet ms = new ModelSet(actual);
        assertThat(ms.getModels()).hasSize(1);
        Model m = ms.getModels().get(0);
        assertThat(m.getName()).isEqualTo(base);
        assertThat(m.getMeshes()).hasSize(1);
        assertThat(m.getVertexCount()).isEqualTo(m.getExpectedVertexCount());
    }

    @Test
    @DisplayName("a billboard, hardware-lit textured model matches g3dcvtr byte-for-byte")
    void rockMatchesG3dcvtr() { assertByteExact("rock"); }

    @Test
    @DisplayName("a non-billboard, vertex-coloured textured model matches g3dcvtr byte-for-byte")
    void bookMatchesG3dcvtr() { assertByteExact("book"); }
}
