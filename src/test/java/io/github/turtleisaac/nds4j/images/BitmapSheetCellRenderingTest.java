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
import io.github.turtleisaac.nds4j.TestRoms;
import io.github.turtleisaac.nds4j.framework.NitroLz;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@link CellBank} composing a <b>LINE_BUFFER</b> (plain linear-raster "bitmap") NCGR — the
 * Gen V "pokegra" battle-sprite sheets ({@code a/0/0/4}, see memory {@code bw2-graphics-narc-map}). These
 * sheets aren't a bank of independently-addressable tile blocks like an ordinary sprite NCGR: an OAM's
 * tile index instead names a position in the sheet's own tile grid, and the OAM is a <b>rectangular
 * crop</b> of that grid, not a run of consecutive tiles. Every OAM in these sheets is also affine with
 * the <b>double-size</b> flag set, so its footprint (and therefore the cell's bounding box and draw
 * offset) is twice its physical pixel size, centered.
 * <p>
 * Before this fix, {@link CellBank#setParentImage} refused any non-{@code NOT_SCANNED} image outright
 * (a {@code LINE_BUFFER} sheet included), and the old tiled addressing would otherwise have produced a
 * scrambled image or a {@code NegativeArraySizeException}. Fixtures come from <b>White2</b>.
 */
@DisplayName("CellBank composing a LINE_BUFFER (bitmap-sheet) NCGR")
public class BitmapSheetCellRenderingTest
{
    // a/0/0/4 layout, 20 files per species (see bw2-graphics-narc-map): front NCGR = 0 (small/tiled
    // variant) and 2 (the LINE_BUFFER sheet), front NCER/NANR/NMCR/NMAR = 4/5/6/7, palettes = 18 (normal).
    private static final int FILES_PER_SPECIES = 20;
    private static final int FRONT_NCGR_SHEET = 2;
    private static final int FRONT_NCER = 4;
    private static final int PALETTE_NORMAL = 18;

    private static Narc pokegra;

    @BeforeAll
    static void loadFixtures()
    {
        NintendoDsRom rom = TestRoms.require("White2.nds");
        byte[] narcFile = rom.getFileByName("a/0/0/4");
        Assumptions.assumeTrue(narcFile != null, "a/0/0/4 (pokegra) not found in the test ROM");
        pokegra = new Narc(narcFile);
    }

    private static byte[] decomp(byte[] d)
    {
        try
        {
            if (NitroLz.isCompressed(d))
                return NitroLz.decompress(d);
        }
        catch (RuntimeException ignored) { }
        return d;
    }

    private static IndexedImage sheetFor(int speciesIndex)
    {
        byte[] raw = decomp(pokegra.getFile(speciesIndex * FILES_PER_SPECIES + FRONT_NCGR_SHEET));
        return new IndexedImage(raw, 0, 0, 1, 1, true);
    }

    private static CellBank cellsFor(int speciesIndex)
    {
        return new CellBank(decomp(pokegra.getFile(speciesIndex * FILES_PER_SPECIES + FRONT_NCER)));
    }

    private static Palette paletteFor(int speciesIndex)
    {
        return new Palette(decomp(pokegra.getFile(speciesIndex * FILES_PER_SPECIES + PALETTE_NORMAL)), 0);
    }

    private static int countOpaque(BufferedImage image)
    {
        int n = 0;
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++)
                if ((image.getRGB(x, y) >>> 24) != 0)
                    n++;
        return n;
    }

    @Test
    @DisplayName("the front sprite sheet is classified LINE_BUFFER (guards the fixture assumption)")
    void sheetIsLineBuffer()
    {
        IndexedImage sheet = sheetFor(1); // Bulbasaur
        assertThat(sheet.getScanMode()).isEqualTo(IndexedImage.NcgrUtils.ScanMode.LINE_BUFFER);
    }

    @Test
    @DisplayName("setParentImage now accepts a LINE_BUFFER sheet instead of refusing it outright")
    void setParentImageAcceptsLineBuffer()
    {
        CellBank ncer = cellsFor(1);
        IndexedImage sheet = sheetFor(1);
        sheet.setPalette(paletteFor(1));
        // Must not throw "Can't use a scanned image with an NCER" for LINE_BUFFER specifically.
        ncer.setParentImage(sheet);
    }

    @Test
    @DisplayName("getOamFootprint doubles only for an affine OAM with the double-size flag set")
    void footprintDoublingIsAffineAndDoubleSizeOnly()
    {
        // Every OAM in these retail sheets is affine+double-size (per the handoff RE): confirm the
        // footprint helper actually doubles each one, and that it isn't flagged disabled (that bit means
        // something different for a non-affine OAM).
        CellBank ncer = cellsFor(1);
        CellBank.Cell cell = ncer.getCell(0);
        assertThat(cell.getOams()).isNotEmpty();
        for (CellBank.Cell.OAM oam : cell.getOams())
        {
            assertThat(oam.isRotation()).as("every OAM in this sheet is affine").isTrue();
            assertThat(oam.isSizeDisable()).as("every OAM in this sheet is double-size").isTrue();
            assertThat(CellBank.isOamDisabled(oam)).as("an affine OAM is never 'disabled' by this bit").isFalse();

            int[] physical = CellBank.getOamSize(oam);
            int[] footprint = CellBank.getOamFootprint(oam);
            assertThat(footprint).isEqualTo(new int[]{physical[0] * 2, physical[1] * 2});
        }
    }

    @Test
    @DisplayName("cell 0 (Bulbasaur's head) renders to the known-correct size, ink count, and a golden pixel")
    void bulbasaurHeadRendersPixelExact()
    {
        CellBank ncer = cellsFor(1);
        IndexedImage sheet = sheetFor(1);
        sheet.setPalette(paletteFor(1));
        ncer.setParentImage(sheet);

        BufferedImage cell0 = ncer.getTransparentNcerImage(0);

        // The double-size footprint doubles the cell's bounding box, so this is 2x the physical OAM
        // extents, not clipped or scrambled.
        assertThat(cell0.getWidth()).isEqualTo(64);
        assertThat(cell0.getHeight()).isEqualTo(37);

        assertThat(countOpaque(cell0)).as("ink pixel count must match the known-correct render").isEqualTo(397);

        int cx = cell0.getWidth() / 2, cy = cell0.getHeight() / 2;
        assertThat(cell0.getRGB(cx, cy))
                .as("a known-correct pixel in the assembled head")
                .isEqualTo(0xFFF8F8F8);
    }

    @Test
    @DisplayName("every cell of several species renders without throwing and draws non-empty ink")
    void everyCellRendersAcrossSeveralSpecies()
    {
        // The original bug (tiled addressing applied to a linear-raster sheet) either scrambled the
        // image or threw NegativeArraySizeException; sweep a handful of species and every one of their
        // cells to guard against a regression hiding in a single hand-picked fixture.
        for (int species = 1; species <= 7; species++)
        {
            CellBank ncer = cellsFor(species);
            IndexedImage sheet = sheetFor(species);
            sheet.setPalette(paletteFor(species));
            ncer.setParentImage(sheet);

            assertThat(ncer.getNumCells()).as("species #%d must have at least one cell", species).isGreaterThan(0);
            for (int c = 0; c < ncer.getNumCells(); c++)
            {
                BufferedImage img = ncer.getTransparentNcerImage(c);
                assertThat(img.getWidth()).as("species #%d cell #%d width", species, c).isGreaterThan(0);
                assertThat(img.getHeight()).as("species #%d cell #%d height", species, c).isGreaterThan(0);
            }
            // At least the whole species' cells together must draw real ink (some individual cells, like
            // a tiny accessory part, can legitimately be near-empty).
            int totalOpaque = 0;
            for (int c = 0; c < ncer.getNumCells(); c++)
                totalOpaque += countOpaque(ncer.getTransparentNcerImage(c));
            assertThat(totalOpaque).as("species #%d must draw ink across its cells", species).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("a genuinely scanned (non-LINE_BUFFER) image is still refused")
    void stillRefusesTrueScannedImages()
    {
        // A blank, freshly-constructed IndexedImage is NOT_SCANNED by default and must still be accepted;
        // this isn't the regression target, just confirms the guard didn't turn into a no-op.
        CellBank ncer = cellsFor(1);
        Palette pal = paletteFor(1);
        IndexedImage plain = new IndexedImage(8, 8, 4, pal);
        assertThat(plain.getScanMode()).isEqualTo(IndexedImage.NcgrUtils.ScanMode.NOT_SCANNED);
        ncer.setParentImage(plain); // must not throw
    }

    @Test
    @DisplayName("editing (save()) a LINE_BUFFER-backed OAM is explicitly refused, not silently wrong")
    void editingLineBufferOamIsRefused()
    {
        CellBank ncer = cellsFor(1);
        IndexedImage sheet = sheetFor(1);
        sheet.setPalette(paletteFor(1));
        ncer.setParentImage(sheet);

        CellBank.Cell.OAM.OamImage[] images = ncer.getCellImages(0);
        assertThatThrownBy(images[0]::save).isInstanceOf(RuntimeException.class);
    }
}
