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
    private static final NintendoDsRom rom = NintendoDsRom.fromFile("HeartGold.nds");

    // this contains the party icons in HGSS
    private static final Narc a020 = new Narc(rom.getFileByName("a/0/2/0"));

    // this contains the battle sprites in HGSS
    private static final Narc a004 = new Narc(rom.getFileByName("a/0/0/4"));

    // party icon palette
    private static final Palette partyPalette = new Palette(a020.files.get(0), 0);

    // bulbasaur battle sprite regular palette
    private static final Palette bulbasaurPalette = new Palette(a004.files.get(10), 0);

    // infernape party sprite in HGSS
    private static final IndexedImage tiled = new IndexedImage(a020.files.get(399), 4, 0, 1, 1, true);

    // bulbasaur battle sprite in HGSS
    private static final IndexedImage scanned = new IndexedImage(a004.files.get(6), 0, 0, 1, 1, true);

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
        Palette partyPaletteDuplicate = new Palette(a020.files.get(0), 0);
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
}
