/*
 * Copyright (c) 2026 Turtleisaac.
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

import io.github.turtleisaac.nds4j.framework.MemBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("8bpp OAM character names")
public class EightBppCellTileTest
{
    @Test
    @DisplayName("a 32-byte boundary names the second 8bpp tile as character 2")
    void boundary32NamesSecondTileAsTwo()
    {
        IndexedImage sheet = sheet(8);
        CellBank bank = new CellBank(ncer(0, 2, 0x20));
        bank.setParentImage(sheet);

        bank.getNcerImage(0);

        assertThat(bank.getCellImages(0)[0].getPixelValue(3, 3))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a 64-byte boundary names the second 8bpp tile as character 1")
    void boundary64NamesSecondTileAsOne()
    {
        IndexedImage sheet = sheet(8);
        CellBank bank = new CellBank(ncer(1, 1, 0x20));
        bank.setParentImage(sheet);

        bank.getNcerImage(0);

        assertThat(bank.getCellImages(0)[0].getPixelValue(3, 3))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("saving an 8bpp OAM writes back at the character-name boundary")
    void saveWritesAtCharacterBoundary()
    {
        IndexedImage sheet = sheet(8);
        CellBank bank = new CellBank(ncer(0, 2, 0x20));
        bank.setParentImage(sheet);
        CellBank.Cell.OAM.OamImage oam = bank.getCellImages(0)[0];
        oam.setPixelValue(1, 1, 7);
        oam.save();

        assertThat(sheet.getPixelValue(1, 9))
                .isEqualTo(7);
        assertThat(sheet.getPixelValue(1, 17))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("a 4bpp character name still addresses 32-byte tiles")
    void fourBppNameIsUnchanged()
    {
        IndexedImage sheet = new IndexedImage(8, 16, 4, palette(16));
        sheet.setPixelValue(1, 1, 1);
        sheet.setPixelValue(9, 1, 2);
        CellBank bank = new CellBank(ncer(0, 1, 0));
        bank.setParentImage(sheet);

        assertThat(bank.getCellImages(0)[0].getPixelValue(1, 1))
                .isEqualTo(2);
    }

    /** Three stacked 8x8 tiles filled with indices 1, 2 and 3. */
    private static IndexedImage sheet(int depth)
    {
        IndexedImage image = new IndexedImage(24, 8, depth, palette(256));
        for (int tile = 0; tile < 3; tile++)
        {
            for (int y = 0; y < 8; y++)
            {
                for (int x = 0; x < 8; x++)
                    image.setPixelValue(x, tile * 8 + y, tile + 1);
            }
        }

        return image;
    }

    private static Palette palette(int colors)
    {
        Color[] entries = new Color[colors];
        for (int i = 0; i < entries.length; i++)
            entries[i] = Color.BLACK;

        return new Palette(entries);
    }

    /** One square 8x8 OAM. attr0High bit 5 is the 256-color flag. */
    private static byte[] ncer(int mapping, int tile, int attr0High)
    {
        int sectionSize = 0x20 + 8 + 6;
        MemBuf buf = MemBuf.create();
        buf.writer()
                .writeString("RECN")
                .writeShort((short) 0xFEFF)
                .writeShort((short) 0x0100)
                .writeInt(0x10 + sectionSize)
                .writeShort((short) 0x10)
                .writeShort((short) 1);
        buf.writer()
                .writeString("KBEC")
                .writeInt(sectionSize)
                .writeShort((short) 1)
                .writeShort((short) 0)
                .writeInt(0x18)
                .writeInt(mapping)
                .writeInt(0)
                .writeInt(0)
                .writeInt(0);
        buf.writer()
                .writeShort((short) 1)
                .writeShort((short) 0)
                .writeInt(0);
        buf.writer()
                .writeByte((byte) 0)
                .writeByte((byte) attr0High)
                .writeShort((short) 0)
                .writeShort((short) tile);
        return buf.reader().getBuffer();
    }
}
