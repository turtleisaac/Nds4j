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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link Screen#applyImage} — decomposing a background image back into an NCGR tileset + NSCR
 * tilemap. Self-contained (no ROM): it paints an image whose cells are a known tile, its mirrors, and
 * a second tile in a second sub-palette, then asserts the decomposition dedups correctly and re-renders
 * pixel-for-pixel identically.
 */
@DisplayName("NSCR back-write (applyImage)")
public class ScreenBackWriteTest
{
    // A 32-color palette split into two 16-color sub-palettes; colors are distinct by red channel
    // (0,8,16,… for indices 0..31), so a cell's sub-palette is unambiguous and matches are exact.
    private static Palette palette()
    {
        Color[] colors = new Color[32];
        for (int i = 0; i < 32; i++)
            colors[i] = new Color((i * 8) & 0xFF, (i * 5) & 0xFF, (i * 13) & 0xFF);
        return new Palette(colors);
    }

    // An asymmetric 8x8 tile of raw 4bpp values (0..15) — asymmetric so H- and V-mirrors are distinct.
    private static int[][] tileA()
    {
        int[][] t = new int[8][8];
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++)
                t[y][x] = (x + 2 * y) % 16;
        return t;
    }

    private static int[][] tileB()
    {
        int[][] t = new int[8][8];
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++)
                t[y][x] = (3 * x + y) % 16;
        return t;
    }

    // Paint an 8x8 cell into img at (px,py): pixel value v is drawn as palette color subPal*16 + v.
    private static void paintCell(BufferedImage img, Color[] colors, int px, int py, int[][] tile, int subPal, boolean hFlip, boolean vFlip)
    {
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++)
            {
                int v = tile[vFlip ? 7 - y : y][hFlip ? 7 - x : x];
                img.setRGB(px + x, py + y, colors[subPal * 16 + v].getRGB());
            }
    }

    // A 16x16 image: (0,0)=A, (8,0)=A h-flipped, (0,8)=A v-flipped [all sub-palette 0], (8,8)=B [sub-palette 1].
    private static BufferedImage sampleImage(Color[] colors)
    {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        paintCell(img, colors, 0, 0, tileA(), 0, false, false);
        paintCell(img, colors, 8, 0, tileA(), 0, true, false);
        paintCell(img, colors, 0, 8, tileA(), 0, false, true);
        paintCell(img, colors, 8, 8, tileB(), 1, false, false);
        return img;
    }

    @Test
    @DisplayName("dedups mirrored tiles and re-renders pixel-for-pixel identically")
    void roundTripsWithFlipDedup()
    {
        Palette pal = palette();
        BufferedImage img = sampleImage(pal.getColors());
        IndexedImage template = new IndexedImage(8, 8, 4, pal); // 4bpp, 1 tile per row

        Screen screen = new Screen(16, 16, 0);
        Screen.ImportResult result = screen.applyImage(img, template, pal, true);

        // A + B only — the two mirrors of A share A's tile via flip flags.
        assertThat(result.uniqueTiles).isEqualTo(2);
        assertThat(result.unmatchedPixels).isEqualTo(0);
        assertThat(result.ncgr.getNumTiles()).isEqualTo(2);

        // Re-rendering the rebuilt screen + tileset must reproduce the original image exactly.
        BufferedImage rendered = screen.getImage(result.ncgr, pal);
        assertThat(rendered.getWidth()).isEqualTo(16);
        assertThat(rendered.getHeight()).isEqualTo(16);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
                assertThat(rendered.getRGB(x, y) & 0xFFFFFF)
                        .as("pixel (%d,%d)", x, y)
                        .isEqualTo(img.getRGB(x, y) & 0xFFFFFF);
    }

    @Test
    @DisplayName("without flip dedup, each orientation is its own tile (still round-trips)")
    void roundTripsWithoutFlipDedup()
    {
        Palette pal = palette();
        BufferedImage img = sampleImage(pal.getColors());
        IndexedImage template = new IndexedImage(8, 8, 4, pal);

        Screen screen = new Screen(16, 16, 0);
        Screen.ImportResult result = screen.applyImage(img, template, pal, false);

        assertThat(result.uniqueTiles).isEqualTo(4); // A, A-hflip, A-vflip, B — no sharing
        assertThat(result.unmatchedPixels).isEqualTo(0);

        BufferedImage rendered = screen.getImage(result.ncgr, pal);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
                assertThat(rendered.getRGB(x, y) & 0xFFFFFF).isEqualTo(img.getRGB(x, y) & 0xFFFFFF);
    }

    // Fill an 8x8 cell at (px,py) so all 16 of the given colors are used (each 4 times).
    private static void paintCell16(BufferedImage img, Color[] sub16, int px, int py)
    {
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++)
                img.setRGB(px + x, py + y, sub16[(x + y * 8) % 16].getRGB());
    }

    @Test
    @DisplayName("rebuilds a NEW multi-sub-palette NCLR (4bpp) and returns it, round-tripping exactly")
    void rebuildsMultiSubPalette()
    {
        // Cell 0 uses 16 reds, cell 1 uses 16 greens — 32 distinct colors needing two 16-color sub-palettes.
        Color[] reds = new Color[16];
        Color[] greens = new Color[16];
        for (int i = 0; i < 16; i++)
        {
            reds[i] = new Color(i * 16, 0, 0);
            greens[i] = new Color(0, i * 16 + 8, 0);
        }
        BufferedImage img = new BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB);
        paintCell16(img, reds, 0, 0);
        paintCell16(img, greens, 8, 0);

        // The template palette is irrelevant here — the point is that a NEW palette is built from the image.
        IndexedImage template = new IndexedImage(8, 8, 4, palette());
        Screen screen = new Screen(16, 8, 0);
        Screen.ImportResult result = screen.applyImageRebuildingPalette(img, template, 2, true);

        assertThat(result.palette.getNumColors()).isEqualTo(32); // two sub-palettes built
        assertThat(result.unmatchedPixels).isEqualTo(0);         // exact fit, no color loss
        assertThat(screen.getPaletteIndex(0)).isEqualTo(0);      // cell 0 -> sub-palette 0 (reds)
        assertThat(screen.getPaletteIndex(1)).isEqualTo(1);      // cell 1 -> sub-palette 1 (greens)

        BufferedImage rendered = screen.getImage(result.ncgr, result.palette);
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 16; x++)
                assertThat(rendered.getRGB(x, y) & 0xFFFFFF)
                        .as("pixel (%d,%d)", x, y)
                        .isEqualTo(img.getRGB(x, y) & 0xFFFFFF);
    }

    @Test
    @DisplayName("rebuilds a single 256-color NCLR for 8bpp and round-trips exactly")
    void rebuilds8bppPalette()
    {
        // 24 distinct colors across three cells — comfortably under 256.
        BufferedImage img = new BufferedImage(24, 8, BufferedImage.TYPE_INT_RGB);
        for (int cell = 0; cell < 3; cell++)
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 8; x++)
                    img.setRGB(cell * 8 + x, y, new Color(cell * 60 + 10, (x + y * 8) % 8 * 20 + 5, 0).getRGB());

        IndexedImage template = new IndexedImage(8, 8, 8, palette()); // 8bpp
        Screen screen = new Screen(24, 8, 0);
        Screen.ImportResult result = screen.applyImageRebuildingPalette(img, template, 1, true);

        assertThat(result.palette.getNumColors()).isEqualTo(256);
        assertThat(result.unmatchedPixels).isEqualTo(0);

        BufferedImage rendered = screen.getImage(result.ncgr, result.palette);
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 24; x++)
                assertThat(rendered.getRGB(x, y) & 0xFFFFFF).isEqualTo(img.getRGB(x, y) & 0xFFFFFF);
    }

    @Test
    @DisplayName("the rebuilt NCGR and mutated screen survive a save()/reload round-trip")
    void savedBytesReloadCleanly()
    {
        Palette pal = palette();
        BufferedImage img = sampleImage(pal.getColors());
        IndexedImage template = new IndexedImage(8, 8, 4, pal);

        Screen screen = new Screen(16, 16, 0);
        Screen.ImportResult result = screen.applyImage(img, template, pal, true);

        // Persist both, reload, and re-render — proves the emitted NSCR/NCGR bytes are well-formed.
        Screen reloadedScreen = new Screen(screen.save());
        IndexedImage reloadedNcgr = new IndexedImage(result.ncgr.save(), 0, 0, 1, 1, false);

        BufferedImage rendered = reloadedScreen.getImage(reloadedNcgr, pal);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
                assertThat(rendered.getRGB(x, y) & 0xFFFFFF).isEqualTo(img.getRGB(x, y) & 0xFFFFFF);
    }
}
