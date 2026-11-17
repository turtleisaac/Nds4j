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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("2D OAM character names")
public class TwoDimensionalCellTileTest
{
    @Test
    @DisplayName("a 16x16 object takes its second row from the next sheet row")
    void secondSpriteRowUsesTheSheetRow()
    {
        IndexedImage sheet = filled(4, 2, 4);
        CellBank bank = bank(4, 0, 1, 0, sheet);

        bank.getNcerImage(0);

        CellBank.Cell.OAM.OamImage oam = bank.getCellImages(0)[0];
        assertThat(oam.getPixelValue(0, 0))
                .isEqualTo(1);
        assertThat(oam.getPixelValue(8, 0))
                .isEqualTo(2);
        assertThat(oam.getPixelValue(0, 8))
                .isEqualTo(5);
        assertThat(oam.getPixelValue(8, 8))
                .isEqualTo(6);
    }

    @Test
    @DisplayName("character 8 is column 8 of the sheet")
    void characterNameSelectsAColumn()
    {
        IndexedImage sheet = filled(12, 1, 4);
        CellBank bank = bank(4, 8, 0, 0, sheet);

        bank.getNcerImage(0);

        assertThat(bank.getCellImages(0)[0].getPixelValue(0, 0))
                .isEqualTo(9);
    }

    @Test
    @DisplayName("character 32 is the first tile of the next hardware row")
    void hardwareRowIsThirtyTwoNames()
    {
        IndexedImage sheet = filled(4, 2, 4);
        CellBank bank = bank(4, 32, 0, 0, sheet);

        bank.getNcerImage(0);

        assertThat(bank.getCellImages(0)[0].getPixelValue(0, 0))
                .isEqualTo(5);
    }

    @Test
    @DisplayName("an 8bpp 2D name ignores its low bit")
    void eightBppNameIgnoresLowBit()
    {
        IndexedImage sheet = filled(4, 2, 8);
        CellBank bank = bank(4, 33, 0, 0x20, sheet);

        bank.getNcerImage(0);

        assertThat(bank.getCellImages(0)[0].getPixelValue(0, 0))
                .isEqualTo(5);
    }

    @Test
    @DisplayName("a 16x8 on the last two columns of a 32-wide sheet draws")
    void lastColumnsOfTheGridFit()
    {
        IndexedImage sheet = filled(32, 1, 4);
        CellBank bank = bank(4, 30, 0, 0x40, sheet);

        bank.getNcerImage(0);

        CellBank.Cell.OAM.OamImage oam = bank.getCellImages(0)[0];
        assertThat(oam.getPixelValue(0, 0))
                .isEqualTo(1);
        assertThat(oam.getPixelValue(8, 0))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a 16x8 that runs past the sheet is rejected")
    void objectPastTheSheetIsRejected()
    {
        IndexedImage sheet = filled(32, 1, 4);
        CellBank bank = bank(4, 31, 0, 0x40, sheet);

        assertThatThrownBy(() -> bank.getNcerImage(0))
                .isInstanceOf(IncompatibleCompanionException.class)
                .extracting(ex -> ((IncompatibleCompanionException) ex).getKind())
                .isEqualTo(IncompatibleCompanionException.Kind.NCGR);
    }

    @Test
    @DisplayName("saving a 2D object writes the second row onto the sheet row")
    void saveWritesTheSheetRow()
    {
        IndexedImage sheet = filled(4, 2, 4);
        CellBank bank = bank(4, 0, 1, 0, sheet);
        CellBank.Cell.OAM.OamImage oam = bank.getCellImages(0)[0];
        oam.setPixelValue(0, 8, 9);
        oam.save();

        assertThat(sheet.getPixelValue(0, 8))
                .isEqualTo(9);
        assertThat(sheet.getPixelValue(16, 0))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("a 1D 16x16 object reads four consecutive tiles")
    void oneDimensionalSpriteReadsConsecutiveTiles()
    {
        IndexedImage sheet = filled(4, 2, 4);
        CellBank bank = bank(0, 0, 1, 0, sheet);

        bank.getNcerImage(0);

        assertThat(bank.getCellImages(0)[0].getPixelValue(0, 8))
                .isEqualTo(3);
    }

    /** Each 8x8 tile is filled with a 4bpp-safe index derived from its file index. */
    private static IndexedImage filled(int tilesWide, int tilesHigh, int depth)
    {
        int colors = depth == 8 ? 256 : 16;
        IndexedImage image = new IndexedImage(tilesHigh * 8, tilesWide * 8, depth, palette(colors));
        for (int tile = 0; tile < tilesWide * tilesHigh; tile++)
        {
            int col = tile % tilesWide;
            int row = tile / tilesWide;
            fillTile(image, col, row, tile + 1);
        }

        return image;
    }

    /** Fills one 8x8 tile with a palette index. */
    private static void fillTile(IndexedImage image, int col, int row, int index)
    {
        for (int y = 0; y < 8; y++)
        {
            for (int x = 0; x < 8; x++)
                image.setPixelValue(col * 8 + x, row * 8 + y, ((index - 1) % 15) + 1);
        }
    }

    /** Builds a black palette of the given length. */
    private static Palette palette(int colors)
    {
        Color[] entries = new Color[colors];
        for (int i = 0; i < entries.length; i++)
            entries[i] = Color.BLACK;

        return new Palette(entries);
    }

    /** Loads a one-cell bank and pairs it with the sheet. */
    private static CellBank bank(int mapping, int tile, int size, int attr0High, IndexedImage sheet)
    {
        CellBank bank = new CellBank(ncer(mapping, tile, size, attr0High));
        bank.setParentImage(sheet);
        return bank;
    }

    /** Builds a one-cell NCER. attr0High bit 5 selects 256 colors and bits 6-7 select the shape. */
    private static byte[] ncer(int mapping, int tile, int size, int attr0High)
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
                .writeShort((short) (size << 14))
                .writeShort((short) tile);
        return buf.reader().getBuffer();
    }
}
