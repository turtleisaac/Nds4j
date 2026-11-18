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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Screen-block placement for text BGs. Synthetic NSCRs only.
 * @see <a href="https://problemkaputt.de/gbatek.htm#lcdiobgcontrol">GBATEK LCD I/O BG Control</a>
 */
@DisplayName("NSCR text screen blocks")
public class TextScreenBlockTest
{
    /**
     * Checks corner-tile file indexes against the text BG screen layout.
     * @see <a href="https://problemkaputt.de/gbatek.htm#lcdiobgcontrol">GBATEK LCD I/O BG Control</a>
     */
    @Test
    @DisplayName("screen-block indices match the GBATEK layouts")
    void referenceIndicesMatchTheHardwareLayouts()
    {
        assertThat(referenceIndex(0, 1, 32, 32)).isEqualTo(32);
        assertThat(referenceIndex(31, 31, 32, 32)).isEqualTo(1023);

        assertThat(referenceIndex(0, 1, 64, 32)).isEqualTo(32);
        assertThat(referenceIndex(32, 0, 64, 32)).isEqualTo(1024);
        assertThat(referenceIndex(63, 31, 64, 32)).isEqualTo(2047);

        assertThat(referenceIndex(0, 1, 32, 64)).isEqualTo(32);
        assertThat(referenceIndex(0, 32, 32, 64)).isEqualTo(1024);
        assertThat(referenceIndex(31, 63, 32, 64)).isEqualTo(2047);

        assertThat(referenceIndex(32, 0, 64, 64)).isEqualTo(1024);
        assertThat(referenceIndex(0, 32, 64, 64)).isEqualTo(2048);
        assertThat(referenceIndex(32, 32, 64, 64)).isEqualTo(3072);
        assertThat(referenceIndex(63, 63, 64, 64)).isEqualTo(4095);

        assertThat(referenceIndex(0, 1, 16, 8)).isEqualTo(16);
        assertThat(referenceIndex(32, 0, 44, 24)).isEqualTo(768);
        assertThat(referenceIndex(43, 23, 44, 24)).isEqualTo(1055);
        assertThat(referenceIndex(0, 32, 32, 40)).isEqualTo(1024);
        assertThat(referenceIndex(0, 64, 12, 66)).isEqualTo(768);
        assertThat(referenceIndex(32, 0, 33, 2)).isEqualTo(64);
    }

    /**
     * Draws each listed map and checks that every tile lands on its screen-block pixel.
     */
    @Test
    @DisplayName("pixels land on the screen block for each text BG size")
    void pixelsFollowScreenBlocks()
    {
        IndexedImage sheet = tileSheet();
        Palette palette = ramp();
        int[][] sizes = {
                { 256, 256 },
                { 128, 64 },
                { 512, 256 },
                { 256, 512 },
                { 512, 512 },
                { 352, 192 },
                { 256, 320 },
                { 96, 528 },
                { 264, 16 }
        };
        for (int[] size : sizes)
            assertPlacement(sheet, palette, size[0], size[1]);
    }

    /**
     * Checks that the second screen block is drawn beside the first and survives a reload.
     */
    @Test
    @DisplayName("the second screen block of a 512-wide map is the right half")
    void wideMapPutsTheSecondBlockOnTheRight()
    {
        IndexedImage sheet = tileSheet();
        Screen screen = new Screen(512, 256, 0);
        short[] entries = new short[64 * 32];

        // Index 32 is row 1 of SC0. Index 1024 is the origin of SC1.
        entries[32] = 3;
        entries[1024] = 4;
        screen.setEntries(entries);

        BufferedImage image = screen.getImage(sheet, ramp());
        assertThat(shownTile(image, 0, 1)).isEqualTo(3);
        assertThat(shownTile(image, 32, 0)).isEqualTo(4);

        byte[] saved = screen.save();
        Screen loaded = new Screen(saved);
        assertThat(loaded.save()).isEqualTo(saved);
        assertThat(shownTile(loaded.getImage(sheet, ramp()), 32, 0)).isEqualTo(4);
    }

    /**
     * Checks that a one-block map draws in row-major order.
     */
    @Test
    @DisplayName("a 256x256 map stays one row-major grid")
    void squareMapStaysRowMajor()
    {
        IndexedImage sheet = tileSheet();
        Screen screen = new Screen(256, 256, 0);
        short[] entries = new short[32 * 32];
        entries[1] = 2;
        entries[32] = 5;
        screen.setEntries(entries);

        BufferedImage image = screen.getImage(sheet, ramp());
        assertThat(shownTile(image, 1, 0)).isEqualTo(2);
        assertThat(shownTile(image, 0, 1)).isEqualTo(5);

        BufferedImage transparent = screen.getTransparentImage(sheet, ramp());
        assertThat(transparent.getRGB(8, 0) >>> 24).isEqualTo(255);
        assertThat(transparent.getRGB(0, 0) >>> 24).isEqualTo(0);
    }

    /**
     * Writes pixels through the screen-block entry, including flips, and leaves the NSCR bytes unchanged.
     */
    @Test
    @DisplayName("setSourcePixel writes the screen-block entry and leaves the NSCR bytes alone")
    void setSourcePixelUsesTheSameBlocks()
    {
        Screen screen = new Screen(512, 256, 0);
        short[] entries = new short[64 * 32];

        // Index 32 is SC0's second row. Index 1024 is SC1's origin. Index 1025 is flipped both ways.
        entries[32] = 6;
        entries[1024] = 5;
        entries[1025] = 7;
        screen.setEntries(entries);
        screen.setHorizontalFlip(1025, true);
        screen.setVerticalFlip(1025, true);
        byte[] before = screen.save();

        IndexedImage sheet = new IndexedImage(16 * 8, 8, 8, ramp());
        screen.setSourcePixel(sheet, 256, 0, 4);
        screen.setSourcePixel(sheet, 0, 8, 3);
        screen.setSourcePixel(sheet, 264, 0, 9);
        screen.setSourcePixel(sheet, 271, 7, 2);

        assertThat(sheet.getPixelValue(0, 5 * 8)).isEqualTo(4);
        assertThat(sheet.getPixelValue(0, 6 * 8)).isEqualTo(3);
        assertThat(sheet.getPixelValue(7, 7 * 8 + 7)).isEqualTo(9);
        assertThat(sheet.getPixelValue(0, 7 * 8)).isEqualTo(2);
        assertThat(screen.save()).isEqualTo(before);
    }

    /**
     * Rejects a pixel on each side of a 16x16 screen.
     */
    @Test
    @DisplayName("setSourcePixel rejects a pixel outside the screen")
    void setSourcePixelRejectsOutsidePixels()
    {
        Screen screen = new Screen(16, 16, 0);
        IndexedImage sheet = new IndexedImage(8, 8, 4, ramp());
        assertThatThrownBy(() -> screen.setSourcePixel(sheet, -1, 0, 1))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> screen.setSourcePixel(sheet, 16, 0, 1))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> screen.setSourcePixel(sheet, 0, -1, 1))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> screen.setSourcePixel(sheet, 0, 16, 1))
                .isInstanceOf(RuntimeException.class);
    }

    /**
     * Imports a wide two-row image and checks the second block's file index.
     */
    @Test
    @DisplayName("applyImage stores cells in screen-block order and the bytes round-trip")
    void applyImageWritesScreenBlocks()
    {
        Palette palette = ramp();
        IndexedImage template = new IndexedImage(8, 8, 8, palette);

        // Two tile rows. The right block follows the left block's 2x32 entries.
        BufferedImage image = new BufferedImage(512, 16, BufferedImage.TYPE_INT_RGB);
        fill(image, palette, 1);
        paint(image, palette, 32, 0, 2);
        paint(image, palette, 0, 1, 3);

        Screen screen = new Screen(8, 8, 0);
        Screen.ImportResult result = screen.applyImage(image, template, palette, false);
        assertThat(result.unmatchedPixels).isEqualTo(0);
        assertThat(screen.getTileIndex(32)).isEqualTo(2);
        assertThat(screen.getTileIndex(64)).isEqualTo(1);
        assertThat(screen.getWidth()).isEqualTo(512);
        assertThat(screen.getHeight()).isEqualTo(16);

        byte[] saved = screen.save();
        Screen loaded = new Screen(saved);
        assertThat(loaded.save()).isEqualTo(saved);
        assertThat(loaded.getTileIndex(64)).isEqualTo(1);

        BufferedImage rendered = loaded.getImage(result.ncgr, palette);
        assertThat(rendered.getRGB(256, 0) & 0xFFFFFF).isEqualTo(palette.getColor(2).getRGB() & 0xFFFFFF);
        assertThat(rendered.getRGB(0, 8) & 0xFFFFFF).isEqualTo(palette.getColor(3).getRGB() & 0xFFFFFF);
    }

    /**
     * Imports a four-block map and checks each block origin's file index.
     */
    @Test
    @DisplayName("applyImage on a 512x512 map keeps each quadrant in its screen block")
    void applyImageWritesAllFourBlocks()
    {
        Palette palette = ramp();
        IndexedImage template = new IndexedImage(8, 8, 8, palette);
        BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        fill(image, palette, 1);
        paint(image, palette, 32, 0, 2);
        paint(image, palette, 0, 32, 3);
        paint(image, palette, 32, 32, 4);

        Screen screen = new Screen(512, 512, 0);
        screen.applyImage(image, template, palette, false);
        assertThat(screen.getTileIndex(0)).isEqualTo(0);
        assertThat(screen.getTileIndex(32)).isEqualTo(0);
        assertThat(screen.getTileIndex(1024)).isEqualTo(1);
        assertThat(screen.getTileIndex(2048)).isEqualTo(2);
        assertThat(screen.getTileIndex(3072)).isEqualTo(3);

        byte[] saved = screen.save();
        assertThat(new Screen(saved).save()).isEqualTo(saved);
    }

    /**
     * Imports a tall map one block wide and checks the lower block's file index.
     */
    @Test
    @DisplayName("a 256x512 import keeps the lower block in file order")
    void tallMapImportStaysInOrder()
    {
        Palette palette = ramp();
        IndexedImage template = new IndexedImage(8, 8, 8, palette);
        BufferedImage image = new BufferedImage(256, 512, BufferedImage.TYPE_INT_RGB);
        fill(image, palette, 1);
        paint(image, palette, 0, 32, 2);

        Screen screen = new Screen(256, 256, 0);
        screen.applyImage(image, template, palette, true);
        assertThat(screen.getTileIndex(32)).isEqualTo(0);
        assertThat(screen.getTileIndex(1024)).isEqualTo(1);

        byte[] saved = screen.save();
        assertThat(new Screen(saved).save()).isEqualTo(saved);
    }

    /**
     * Rejects a bit depth other than 4 or 8, a size that is not a tile grid, and more than 1024 tiles.
     */
    @Test
    @DisplayName("applyImage rejects a bad template depth, a bad size, and more than 1024 tiles")
    void applyImageRejectsUnusableInput()
    {
        Palette palette = ramp();
        IndexedImage template = new IndexedImage(8, 8, 4, palette);
        Screen screen = new Screen(8, 8, 0);
        template.setBitDepth(2);
        assertThatThrownBy(() -> screen.applyImage(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), template, palette, false))
                .isInstanceOf(RuntimeException.class);

        template.setBitDepth(4);
        assertThatThrownBy(() -> screen.applyImage(new BufferedImage(10, 8, BufferedImage.TYPE_INT_RGB), template, palette, false))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> screen.applyImage(new BufferedImage(8, 10, BufferedImage.TYPE_INT_RGB), template, palette, false))
                .isInstanceOf(RuntimeException.class);

        IndexedImage wide = new IndexedImage(8, 8, 8, palette);
        BufferedImage tooMany = new BufferedImage(264, 256, BufferedImage.TYPE_INT_RGB);
        for (int tile = 0; tile < 33 * 32; tile++)
        {
            int tileX = tile % 33;
            int tileY = tile / 33;
            tooMany.setRGB(tileX * 8, tileY * 8, palette.getColor(tile & 0xFF).getRGB());
            tooMany.setRGB(tileX * 8 + 1, tileY * 8, palette.getColor((tile >> 8) & 0xFF).getRGB());
        }

        assertThatThrownBy(() -> screen.applyImage(tooMany, wide, palette, false))
                .isInstanceOf(RuntimeException.class);
    }

    /**
     * Counts every pixel when the image color is absent from the palette.
     */
    @Test
    @DisplayName("a nearest-color miss is counted")
    void unmatchedPixelsAreCounted()
    {
        Color[] colors = { Color.BLACK, Color.WHITE };
        Palette palette = new Palette(colors);
        IndexedImage template = new IndexedImage(8, 8, 4, palette);
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < 64; i++)
            image.setRGB(i % 8, i / 8, Color.RED.getRGB());

        Screen screen = new Screen(8, 8, 0);
        Screen.ImportResult result = screen.applyImage(image, template, palette, false);
        assertThat(result.unmatchedPixels).isEqualTo(64);
    }

    /**
     * Fills a screen in screen-block order and checks that a reload draws each tile in place.
     */
    private static void assertPlacement(IndexedImage sheet, Palette palette, int pixelWidth, int pixelHeight)
    {
        int tilesWide = pixelWidth / 8;
        int tilesHigh = pixelHeight / 8;
        Screen screen = new Screen(pixelWidth, pixelHeight, 0);
        short[] entries = new short[tilesWide * tilesHigh];

        // The stored value is the tile's coordinate inside its 32x32 block.
        for (int tileY = 0; tileY < tilesHigh; tileY++)
        {
            for (int tileX = 0; tileX < tilesWide; tileX++)
            {
                int local = (tileY % 32) * 32 + (tileX % 32);
                entries[referenceIndex(tileX, tileY, tilesWide, tilesHigh)] = (short) local;
            }
        }

        screen.setEntries(entries);
        byte[] saved = screen.save();
        Screen loaded = new Screen(saved);
        assertThat(loaded.save())
                .as("%dx%d", pixelWidth, pixelHeight)
                .isEqualTo(saved);

        BufferedImage image = loaded.getImage(sheet, palette);
        for (int tileY = 0; tileY < tilesHigh; tileY++)
        {
            for (int tileX = 0; tileX < tilesWide; tileX++)
            {
                int local = (tileY % 32) * 32 + (tileX % 32);
                assertThat(shownTile(image, tileX, tileY))
                        .as("%dx%d tile (%d,%d)", pixelWidth, pixelHeight, tileX, tileY)
                        .isEqualTo(local);
            }
        }
    }

    /**
     * Builds an 8bpp sheet where tile {@code t} stores its index in the first two pixels.
     */
    private static IndexedImage tileSheet()
    {
        IndexedImage sheet = new IndexedImage(1024 * 8, 8, 8, ramp());
        for (int tile = 0; tile < 1024; tile++)
        {
            sheet.setPixelValue(0, tile * 8, tile & 0xFF);
            sheet.setPixelValue(1, tile * 8, (tile >> 8) & 0xFF);
        }

        return sheet;
    }

    /**
     * Reads the tile index drawn at ({@code tileX}, {@code tileY}) from the sheet's two marker pixels.
     */
    private static int shownTile(BufferedImage image, int tileX, int tileY)
    {
        int x = tileX * 8;
        int y = tileY * 8;
        int low = (image.getRGB(x, y) >> 16) & 0xFF;
        int high = (image.getRGB(x + 1, y) >> 16) & 0xFF;
        return low | (high << 8);
    }

    /**
     * Fills {@code image} with one palette color.
     */
    private static void fill(BufferedImage image, Palette palette, int colorIndex)
    {
        int rgb = palette.getColor(colorIndex).getRGB();
        for (int y = 0; y < image.getHeight(); y++)
        {
            for (int x = 0; x < image.getWidth(); x++)
                image.setRGB(x, y, rgb);
        }
    }

    /**
     * Paints the 8x8 cell at ({@code tileX}, {@code tileY}) with one palette color.
     */
    private static void paint(BufferedImage image, Palette palette, int tileX, int tileY, int colorIndex)
    {
        int rgb = palette.getColor(colorIndex).getRGB();
        for (int y = 0; y < 8; y++)
        {
            for (int x = 0; x < 8; x++)
                image.setRGB(tileX * 8 + x, tileY * 8 + y, rgb);
        }
    }

    /**
     * Returns a 256-color palette whose red channel is the color index.
     */
    private static Palette ramp()
    {
        Color[] colors = new Color[256];
        for (int i = 0; i < colors.length; i++)
            colors[i] = new Color(i, 0, 0);

        return new Palette(colors);
    }

    /**
     * Returns the file index of a tile in a text BG of {@code tilesWide} by {@code tilesHigh} tiles.
     * @see <a href="https://problemkaputt.de/gbatek.htm#lcdiobgcontrol">GBATEK LCD I/O BG Control</a>
     */
    private static int referenceIndex(int tileX, int tileY, int tilesWide, int tilesHigh)
    {
        int blockColumn = tileX / 32;
        int blockRow = tileY / 32;
        int index = 0;
        for (int row = 0; row < blockRow; row++)
            index += blockSpan(tilesHigh, row) * tilesWide;

        int rowHeight = blockSpan(tilesHigh, blockRow);
        for (int column = 0; column < blockColumn; column++)
            index += rowHeight * blockSpan(tilesWide, column);

        return index + (tileY % 32) * blockSpan(tilesWide, blockColumn) + (tileX % 32);
    }

    /**
     * Returns the tile count of screen block {@code block} along a {@code tiles}-long edge.
     */
    private static int blockSpan(int tiles, int block)
    {
        return Math.min(32, tiles - block * 32);
    }
}
