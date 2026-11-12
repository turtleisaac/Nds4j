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
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CellBank.applyImage on a hand-built NCER")
public class CellBankApplyImageTest
{
    private static final Color GREY = new Color(80, 80, 80);

    /** Two 16-color blocks, each repeating grey: slots 2 and 4, then 19 and 21. */
    private static Palette palette()
    {
        Color[] colors = new Color[32];
        for (int i = 0; i < colors.length; i++)
            colors[i] = Color.BLACK;

        colors[0] = Color.MAGENTA;
        colors[1] = Color.RED;
        colors[2] = GREY;
        colors[3] = Color.GREEN;
        colors[4] = GREY;
        colors[16] = Color.MAGENTA;
        colors[17] = Color.BLUE;
        colors[19] = GREY;
        colors[21] = GREY;
        return new Palette(colors);
    }

    /** A 16x8 4bpp NCGR: tile 0 uses block 0 indices 0, 1, 2, 4 and tile 1 uses block 1 indices 0, 1, 3, 5. */
    private static IndexedImage ncgr()
    {
        IndexedImage ncgr = new IndexedImage(8, 16, 4, palette());
        int[] left = { 0, 1, 2, 4 };
        int[] right = { 0, 1, 3, 5 };
        for (int y = 0; y < 8; y++)
        {
            for (int x = 0; x < 8; x++)
            {
                ncgr.setPixelValue(x, y, left[(x + y) % 4]);
                ncgr.setPixelValue(x + 8, y, right[(x + 2 * y) % 4]);
            }
        }

        return ncgr;
    }

    /** One cell: an 8x8 OAM on tile 0 with palette 0, one on tile 1 with palette 1 to its right, and a disabled OAM. */
    private static byte[] ncer()
    {
        int sectionSize = 0x20 + 8 + 3 * 6;
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
                .writeInt(0)
                .writeInt(0)
                .writeInt(0)
                .writeInt(0);
        buf.writer()
                .writeShort((short) 3)
                .writeShort((short) 0)
                .writeInt(0);

        writeOam(buf, 0, 0, 0, 0, 0);
        writeOam(buf, 0, 0, 8, 1, 1);
        writeOam(buf, 0, 2, -8, 0, 0);
        return buf.reader().getBuffer();
    }

    /** Writes a square 8x8 OAM's three attributes. */
    private static void writeOam(MemBuf buf, int y, int attr0, int x, int tile, int palette)
    {
        buf.writer()
                .writeByte((byte) y)
                .writeByte((byte) attr0)
                .writeShort((short) (x & 0x1FF))
                .writeShort((short) (tile | palette << 12));
    }

    /** The NCGR's index grid, row by row. */
    private static int[][] indices(IndexedImage ncgr)
    {
        int[][] grid = new int[ncgr.getHeight()][ncgr.getWidth()];
        for (int y = 0; y < grid.length; y++)
        {
            for (int x = 0; x < grid[y].length; x++)
                grid[y][x] = ncgr.getPixelValue(x, y);
        }

        return grid;
    }

    @Test
    @DisplayName("an unchanged render re-imports to the same indices, repeated greys included")
    void unchangedRenderKeepsIndices()
    {
        IndexedImage ncgr = ncgr();
        CellBank bank = new CellBank(ncer());
        bank.setParentImage(ncgr);
        int[][] before = indices(ncgr);

        BufferedImage render = bank.getTransparentNcerImage(0);
        CellBank.ImportResult result = bank.applyImage(0, render, ncgr, ncgr.getPalette());

        assertThat(render.getWidth())
                .isEqualTo(16);
        assertThat(result.unmatchedPixels)
                .isEqualTo(0);
        assertThat(indices(ncgr))
                .isEqualTo(before);
    }

    @Test
    @DisplayName("repainted pixels take the nearest color in their OAM's block and misses are counted")
    void repaintedPixels()
    {
        IndexedImage ncgr = ncgr();
        CellBank bank = new CellBank(ncer());
        bank.setParentImage(ncgr);
        BufferedImage render = bank.getTransparentNcerImage(0);

        render.setRGB(1, 0, GREY.getRGB());
        render.setRGB(2, 0, 0);
        render.setRGB(8, 0, new Color(5, 5, 250).getRGB());
        render.setRGB(9, 0, GREY.getRGB());
        CellBank.ImportResult result = bank.applyImage(0, render, ncgr, ncgr.getPalette());

        assertThat(result.unmatchedPixels)
                .isEqualTo(1);
        assertThat(ncgr.getPixelValue(1, 0))
                .as("red repainted grey takes the first grey")
                .isEqualTo(2);
        assertThat(ncgr.getPixelValue(2, 0))
                .as("transparent")
                .isEqualTo(0);
        assertThat(ncgr.getPixelValue(8, 0))
                .as("near-blue in block 1")
                .isEqualTo(1);
        assertThat(ncgr.getPixelValue(9, 0))
                .as("block 1's first grey")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("an image of the wrong size is refused")
    void wrongSize()
    {
        IndexedImage ncgr = ncgr();
        CellBank bank = new CellBank(ncer());

        assertThatThrownBy(() -> bank.applyImage(0, new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB), ncgr, ncgr.getPalette()))
                .hasMessageContaining("composes to 16x8");
        assertThatThrownBy(() -> CellBank.requireCellSize(new BufferedImage(16, 9, BufferedImage.TYPE_INT_ARGB), new Rectangle(0, 0, 16, 8), 0))
                .hasMessageContaining("16x9");
    }

    @Test
    @DisplayName("pixelIndex is -1 off the image and on transparent pixels")
    void pixelIndexEdges()
    {
        Color[] colors = palette().getColors();
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 1, GREY.getRGB());

        assertThat(CellBank.pixelIndex(image, -1, 1, colors, 0, 16, 0))
                .isEqualTo(-1);
        assertThat(CellBank.pixelIndex(image, 1, -1, colors, 0, 16, 0))
                .isEqualTo(-1);
        assertThat(CellBank.pixelIndex(image, 4, 1, colors, 0, 16, 0))
                .isEqualTo(-1);
        assertThat(CellBank.pixelIndex(image, 1, 4, colors, 0, 16, 0))
                .isEqualTo(-1);
        assertThat(CellBank.pixelIndex(image, 0, 0, colors, 0, 16, 0))
                .isEqualTo(-1);
        assertThat(CellBank.pixelIndex(image, 1, 1, colors, 0, 16, 4))
                .isEqualTo(4);
        assertThat(CellBank.pixelIndex(image, 1, 1, colors, 16, 16, 0))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("subPaletteBase picks the OAM's block at 4bpp only when the palette has it")
    void subPaletteBase()
    {
        CellBank bank = new CellBank(ncer());
        CellBank.Cell.OAM oam = bank.getCell(0).oams[1];

        assertThat(CellBank.subPaletteBase(oam, 4, 32))
                .isEqualTo(16);
        assertThat(CellBank.subPaletteBase(oam, 4, 16))
                .isEqualTo(0);
        assertThat(CellBank.subPaletteBase(oam, 8, 256))
                .isEqualTo(0);

        oam.palette = -1;
        assertThat(CellBank.subPaletteBase(oam, 4, 32))
                .isEqualTo(0);
    }
}
