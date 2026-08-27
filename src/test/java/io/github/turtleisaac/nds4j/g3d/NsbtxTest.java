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

import io.github.turtleisaac.nds4j.Narc;
import io.github.turtleisaac.nds4j.NintendoDsRom;
import io.github.turtleisaac.nds4j.TestRoms;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link Nsbtx} (NSBTX / {@code BTX0}) &mdash; the first of the Nitro 3D formats. Exercised
 * against every BTX0 file in a retail ROM: the reader/writer must round-trip byte-for-byte, and every
 * texture must decode to an image of the size its texImageParam declares.
 */
@DisplayName("NSBTX (3D texture archive)")
public class NsbtxTest
{
    private static List<byte[]> nsbtxFiles;

    @BeforeAll
    static void loadFixtures()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        nsbtxFiles = collect(rom, "BTX0");
        Assumptions.assumeFalse(nsbtxFiles.isEmpty(), "no BTX0 files found in the test ROM");
    }

    private static String magic(byte[] d)
    {
        return d == null || d.length < 4 ? "" : new String(d, 0, 4, StandardCharsets.ISO_8859_1);
    }

    private static List<byte[]> collect(NintendoDsRom rom, String want)
    {
        List<byte[]> found = new ArrayList<>();
        for (int i = 0; i < rom.getNumFiles(); i++)
        {
            byte[] f = rom.getFile(i);
            if (!magic(f).equals("NARC"))
                continue;
            Narc narc;
            try { narc = new Narc(f); }
            catch (RuntimeException e) { continue; }
            for (int j = 0; j < narc.getNumFiles(); j++)
                if (magic(narc.getFile(j)).equals(want))
                    found.add(narc.getFile(j));
        }
        return found;
    }

    @Test
    @DisplayName("save() reproduces every BTX0 file byte-for-byte")
    void writtenNsbtxEqualsOriginalBytes()
    {
        for (int i = 0; i < nsbtxFiles.size(); i++)
        {
            byte[] original = nsbtxFiles.get(i);
            byte[] written = new Nsbtx(original).save();
            assertThat(written).as("BTX0 file #%d must round-trip byte-for-byte", i).isEqualTo(original);
        }
    }

    @Test
    @DisplayName("every texture decodes to an image of its declared size")
    void everyTextureDecodes()
    {
        int totalTextures = 0;
        for (byte[] file : nsbtxFiles)
        {
            Nsbtx nsbtx = new Nsbtx(file);
            for (Nsbtx.Texture t : nsbtx.getTextures())
            {
                BufferedImage img = nsbtx.getImage(t);
                assertThat(img.getWidth()).as("%s width", t.getName()).isEqualTo(t.getWidth());
                assertThat(img.getHeight()).as("%s height", t.getName()).isEqualTo(t.getHeight());
                assertThat(t.getWidth()).isBetween(8, 1024);
                assertThat(t.getHeight()).isBetween(8, 1024);
                assertThat(t.getFormat()).isBetween(1, 7);
                totalTextures++;
            }
        }
        assertThat(totalTextures).as("the corpus should contain textures to decode").isGreaterThan(0);
    }

    @Test
    @DisplayName("named lookup returns the same texture as iteration")
    void namedLookupWorks()
    {
        Nsbtx nsbtx = nsbtxFiles.stream().map(Nsbtx::new)
                .filter(n -> !n.getTextures().isEmpty()).findFirst().orElse(null);
        Assumptions.assumeTrue(nsbtx != null, "need an NSBTX with a texture");

        Nsbtx.Texture first = nsbtx.getTextures().get(0);
        assertThat(nsbtx.getTexture(first.getName())).isSameAs(first);
        assertThat(nsbtx.getImage(first.getName())).isNotNull();
        assertThatThrownBy(() -> nsbtx.getImage("definitely-not-a-texture"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a non-NSBTX input is rejected")
    void rejectsNonNsbtx()
    {
        byte[] junk = new byte[0x20];
        junk[0] = 'J'; junk[1] = 'U'; junk[2] = 'N'; junk[3] = 'K';
        assertThatThrownBy(() -> new Nsbtx(junk)).isInstanceOf(RuntimeException.class);
    }
}
