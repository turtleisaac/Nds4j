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

package io.github.turtleisaac.nds4j.images;

import io.github.turtleisaac.nds4j.NintendoDsRom;
import io.github.turtleisaac.nds4j.TestRoms;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link NitroFont} (NFTR). The reader/writer is exercised against every RTFN file in a retail
 * ROM, which is the population it was reverse-engineered from. The Pok&eacute;mon Gen IV ROMs don't ship
 * NFTR fonts, so the fixtures come from <b>White2</b> (Gen V), whose {@code a/0/2/3} NARC holds them.
 */
@DisplayName("NFTR (NitroFont)")
public class NitroFontTest
{
    private static List<byte[]> nftrFiles;

    @BeforeAll
    static void loadFixtures()
    {
        NintendoDsRom rom = TestRoms.require("White2.nds");
        nftrFiles = NtrFixtures.collect(rom, "RTFN");
        Assumptions.assumeFalse(nftrFiles.isEmpty(), "no RTFN files found in the test ROM");
    }

    @Test
    @DisplayName("save() reproduces every RTFN file byte-for-byte")
    void writtenNftrEqualsOriginalBytes()
    {
        // The strongest correctness statement available for a reader/writer: the bytes it emits for a file
        // it just read are identical. Run over the whole ROM so no block layout, unaligned-block gap, or
        // odd header fileSize goes unexercised.
        for (int i = 0; i < nftrFiles.size(); i++)
        {
            byte[] original = nftrFiles.get(i);
            byte[] written = new NitroFont(original).save();
            assertThat(written)
                    .as("RTFN file #%d must round-trip byte-for-byte", i)
                    .isEqualTo(original);
        }
    }

    @Test
    @DisplayName("a re-saved NFTR is stable across a second save/load cycle")
    void reSaveIsStable()
    {
        for (int i = 0; i < nftrFiles.size(); i++)
        {
            byte[] once = new NitroFont(nftrFiles.get(i)).save();
            byte[] twice = new NitroFont(once).save();
            assertThat(twice)
                    .as("RTFN file #%d must be stable across a re-save", i)
                    .isEqualTo(once);
        }
    }

    @Test
    @DisplayName("FINF and CGLP decode to coherent font metrics")
    void fontMetricsAreCoherent()
    {
        for (byte[] data : nftrFiles)
        {
            NitroFont font = new NitroFont(data);
            NitroFont.FontInfo finf = font.getFontInfo();
            assertThat(finf).isNotNull();
            assertThat(finf.getLineFeed()).as("a font has a positive line feed").isGreaterThan(0);
            assertThat(finf.getEncoding()).isBetween(0, 3);

            NitroFont.GlyphData cglp = font.getGlyphData();
            assertThat(cglp).isNotNull();
            assertThat(cglp.getCellWidth()).isGreaterThan(0);
            assertThat(cglp.getCellHeight()).isGreaterThan(0);
            assertThat(cglp.getBpp()).isIn(1, 2, 3);
            assertThat(font.getNumGlyphs()).as("a font has at least one glyph").isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("the CMAP chain maps ASCII to consecutive, in-range glyphs")
    void cmapMapsAsciiCoherently()
    {
        // Use the first (largest) font, which covers full ASCII. 'A'..'Z' should map to a run of glyph
        // indices increasing by one, and every mapped index must be a real glyph.
        NitroFont font = new NitroFont(nftrFiles.get(0));
        int prev = font.getGlyphIndex('A');
        assertThat(prev).as("'A' must be mapped").isGreaterThanOrEqualTo(0);
        for (char c = 'B'; c <= 'Z'; c++)
        {
            int idx = font.getGlyphIndex(c);
            assertThat(idx).as("'%c' must be mapped to a real glyph", c)
                    .isBetween(0, font.getNumGlyphs() - 1);
            assertThat(idx).as("uppercase letters map to consecutive glyphs").isEqualTo(prev + 1);
            prev = idx;
        }
    }

    @Test
    @DisplayName("a mapped glyph renders non-empty ink")
    void mappedGlyphHasInk()
    {
        NitroFont font = new NitroFont(nftrFiles.get(0));
        int glyph = font.getGlyphIndex('A');
        BufferedImage img = font.getGlyphImage(glyph);
        assertThat(img.getWidth()).isEqualTo(font.getGlyphData().getCellWidth());
        assertThat(img.getHeight()).isEqualTo(font.getGlyphData().getCellHeight());

        boolean anyInk = false;
        for (int y = 0; y < img.getHeight() && !anyInk; y++)
            for (int x = 0; x < img.getWidth(); x++)
                if ((img.getRGB(x, y) >>> 24) != 0) { anyInk = true; break; }
        assertThat(anyInk).as("the glyph for 'A' must draw at least one non-transparent pixel").isTrue();
    }

    @Test
    @DisplayName("rendering a string produces a correctly sized, non-empty image")
    void renderStringWorks()
    {
        NitroFont font = new NitroFont(nftrFiles.get(0));
        BufferedImage img = font.renderString("Pokemon Black 2", 1);
        assertThat(img.getWidth()).isGreaterThan(0);
        assertThat(img.getHeight()).isEqualTo(font.getGlyphData().getCellHeight());

        long inkPixels = 0;
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++)
                if ((img.getRGB(x, y) >>> 24) != 0) inkPixels++;
        assertThat(inkPixels).as("rendered text must contain ink").isGreaterThan(0);
    }

    @Test
    @DisplayName("a non-NFTR input is rejected")
    void rejectsNonNftr()
    {
        byte[] notNftr = new byte[0x20];
        notNftr[0] = 'J'; notNftr[1] = 'U'; notNftr[2] = 'N'; notNftr[3] = 'K';
        assertThatThrownBy(() -> new NitroFont(notNftr)).isInstanceOf(RuntimeException.class);
    }
}
