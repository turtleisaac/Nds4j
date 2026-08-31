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

import io.github.turtleisaac.nds4j.Narc;
import io.github.turtleisaac.nds4j.NintendoDsRom;
import org.junit.jupiter.api.Test;

import java.awt.*;

import static org.assertj.core.api.Assertions.assertThat;

public class PaletteTest
{
    // rom
    private static NintendoDsRom rom;

    // this contains the party icons in HGSS
    private static Narc a020;

    // this contains the battle sprites in HGSS
    private static Narc a004;

    // party icon palette
    private static Palette partyPalette;

    // bulbasaur battle sprite regular palette
    private static Palette bulbasaurPalette;

    // infernape party sprite in HGSS
    private static IndexedImage tiled;

    // bulbasaur battle sprite in HGSS
    private static IndexedImage scanned;

    @org.junit.jupiter.api.BeforeAll
    static void loadFixtures()
    {
        rom = io.github.turtleisaac.nds4j.TestRoms.require("HeartGold.nds");
        a020 = new Narc(rom.getFileByName("a/0/2/0"));
        a004 = new Narc(rom.getFileByName("a/0/0/4"));
        partyPalette = new Palette(a020.getFile(0), 0);
        bulbasaurPalette = new Palette(a004.getFile(10), 0);
        tiled = new IndexedImage(a020.getFile(399), 4, 0, 1, 1, true);
        scanned = new IndexedImage(a004.getFile(6), 0, 0, 1, 1, true);
    }

    @Test
    void length()
    {
        assertThat(partyPalette.getNumColors())
                .isEqualTo(256);
    }

    @Test
    void bitDepth()
    {
        assertThat(partyPalette.getBitDepth())
                .isEqualTo(4);
    }

    @Test
    void setColor()
    {
        Palette partyPaletteDuplicate = new Palette(a020.getFile(0), 0);
        partyPaletteDuplicate.setColor(0, Color.MAGENTA);

        assertThat(partyPaletteDuplicate.getColor(0))
                .isEqualTo(Color.MAGENTA);
    }

    @Test
    void writtenMultiPaletteEquals()
    {
        assertThat(new Palette(partyPalette.save(), 0))
                .isEqualTo(partyPalette);
    }

    @Test
    void writtenSinglePaletteEquals()
    {
        assertThat(new Palette(bulbasaurPalette.save(), 0))
                .isEqualTo(bulbasaurPalette);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("save() reproduces every NCLR in the ROM byte-for-byte")
    void writtenNclrRoundTripsByteExactAcrossRom()
    {
        // The equals()-based tests above don't compare the raw bytes, so they missed several NCLR
        // header/section quirks (the 256-color cap, the 0x1C word, a trailing PMCP block, and the
        // over-/under-declared palette-length word). A byte-level round-trip over the whole ROM is
        // what actually pins them down.
        java.util.List<byte[]> nclrFiles = NtrFixtures.collect(rom, "RLCN");
        org.junit.jupiter.api.Assumptions.assumeFalse(nclrFiles.isEmpty(), "no RLCN files found in the test ROM");
        for (int i = 0; i < nclrFiles.size(); i++)
        {
            byte[] original = nclrFiles.get(i);
            byte[] written = new Palette(original, 0).save();
            assertThat(written).as("RLCN file #%d must round-trip byte-for-byte", i).isEqualTo(original);
        }
    }
}
