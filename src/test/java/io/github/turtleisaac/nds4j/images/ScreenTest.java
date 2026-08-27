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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link Screen} (NSCR). Exercised against every RCSN file in a retail ROM.
 */
@DisplayName("NSCR (Screen)")
public class ScreenTest
{
    private static List<byte[]> nscrFiles;

    @BeforeAll
    static void loadFixtures()
    {
        NintendoDsRom rom = TestRoms.require("HeartGold.nds");
        nscrFiles = NtrFixtures.collect(rom, "RCSN");
        Assumptions.assumeFalse(nscrFiles.isEmpty(), "no RCSN files found in the test ROM");
    }

    @Test
    @DisplayName("save() reproduces every RCSN file byte-for-byte")
    void writtenScreenEqualsOriginalBytes()
    {
        // Includes the screens that carry alignment padding the declared data size does not account
        // for, which is exactly the case a naive writer drops.
        for (int i = 0; i < nscrFiles.size(); i++)
        {
            byte[] original = nscrFiles.get(i);
            byte[] written = new Screen(original).save();
            assertThat(written)
                    .as("RCSN file #%d must round-trip byte-for-byte", i)
                    .isEqualTo(original);
        }
    }

    @Test
    @DisplayName("a saved screen re-reads equal to the original object")
    void writtenScreenEqualsOriginalObject()
    {
        for (int i = 0; i < nscrFiles.size(); i++)
        {
            Screen original = new Screen(nscrFiles.get(i));
            Screen reloaded = new Screen(original.save());
            assertThat(reloaded)
                    .as("RCSN file #%d must equal itself after a save/load cycle", i)
                    .isEqualTo(original);
        }
    }

    @Test
    @DisplayName("dimensions and entry count are consistent")
    void dimensionsAreCoherent()
    {
        Screen screen = new Screen(nscrFiles.get(0));
        assertThat(screen.getWidth()).isGreaterThan(0);
        assertThat(screen.getHeight()).isGreaterThan(0);
        assertThat(screen.getNumEntries()).isEqualTo(screen.getEntries().length);
    }

    @Test
    @DisplayName("entry bitfield accessors compose into the raw entry")
    void entryBitfieldsCompose()
    {
        Screen screen = new Screen(nscrFiles.get(0));

        // Every accessor must read back the pieces the raw 16-bit entry is built from.
        for (int i = 0; i < screen.getNumEntries(); i++)
        {
            int raw = screen.getEntries()[i] & 0xFFFF;
            int recomposed = (screen.getTileIndex(i) & 0x3FF)
                    | (screen.isHorizontalFlip(i) ? 0x400 : 0)
                    | (screen.isVerticalFlip(i) ? 0x800 : 0)
                    | ((screen.getPaletteIndex(i) & 0xF) << 12);
            assertThat(recomposed).as("entry %d decomposes and recomposes losslessly", i).isEqualTo(raw);
        }
    }

    @Test
    @DisplayName("edited entry fields survive a save/load cycle")
    void editedEntriesPersist()
    {
        Screen screen = new Screen(nscrFiles.get(0));
        screen.setTileIndex(0, 42);
        screen.setPaletteIndex(0, 5);
        screen.setHorizontalFlip(0, true);
        screen.setVerticalFlip(0, false);

        Screen reloaded = new Screen(screen.save());
        assertThat(reloaded.getTileIndex(0)).isEqualTo(42);
        assertThat(reloaded.getPaletteIndex(0)).isEqualTo(5);
        assertThat(reloaded.isHorizontalFlip(0)).isTrue();
        assertThat(reloaded.isVerticalFlip(0)).isFalse();
    }

    @Test
    @DisplayName("a non-NSCR input is rejected")
    void rejectsNonNscr()
    {
        byte[] notNscr = new byte[0x24];
        notNscr[0] = 'J'; notNscr[1] = 'U'; notNscr[2] = 'N'; notNscr[3] = 'K';
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Screen(notNscr))
                .isInstanceOf(RuntimeException.class);
    }
}
