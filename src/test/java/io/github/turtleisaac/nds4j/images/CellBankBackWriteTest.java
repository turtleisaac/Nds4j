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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link CellBank#applyImage} — writing an edited assembled-cell image back into the NCGR (NCER
 * back-write). Renders a real cell, feeds the render straight back, and asserts a re-render is
 * pixel-identical (so the composition and its inverse agree, colours and sub-palettes included).
 */
@DisplayName("NCER back-write (CellBank.applyImage)")
public class CellBankBackWriteTest
{
    private static NintendoDsRom rom;

    @BeforeAll
    static void loadRom() { rom = TestRoms.require("HeartGold.nds"); }

    private static String magic(byte[] d)
    {
        return d == null || d.length < 4 ? "" : new String(d, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static int indexOf(Narc narc, String m)
    {
        for (int i = 0; i < narc.getNumFiles(); i++)
            if (magic(narc.getFile(i)).equals(m)) return i;
        return -1;
    }

    @Test
    @DisplayName("re-importing a rendered cell reproduces it pixel-for-pixel, unmatched 0")
    void cellRoundTrips()
    {
        boolean exercised = false;
        for (int f = 0; f < rom.getNumFiles() && !exercised; f++)
        {
            if (!magic(rom.getFile(f)).equals("NARC")) continue;
            Narc narc;
            try { narc = new Narc(rom.getFile(f)); } catch (RuntimeException e) { continue; }

            int ncerI = indexOf(narc, "RECN"), ncgrI = indexOf(narc, "RGCN"), nclrI = indexOf(narc, "RLCN");
            if (ncerI < 0 || ncgrI < 0 || nclrI < 0) continue;

            IndexedImage ncgr;
            Palette palette;
            CellBank bank;
            try
            {
                ncgr = new IndexedImage(narc.getFile(ncgrI), 0, 0, 1, 1, true);
                palette = new Palette(narc.getFile(nclrI), 0);
                ncgr.setPalette(palette);
                bank = new CellBank(narc.getFile(ncerI));
                bank.setParentImage(ncgr); // throws for scanned NCGRs — skip those bundles
            }
            catch (RuntimeException e) { continue; }

            // Find a cell that actually composes to something.
            int cellIndex = -1;
            for (int c = 0; c < bank.getNumCells(); c++)
            {
                java.awt.Rectangle b = bank.getCellBounds(c);
                if (b.width > 0 && b.height > 0) { cellIndex = c; break; }
            }
            if (cellIndex < 0) continue;

            BufferedImage rendered = bank.getNcerImage(cellIndex);
            CellBank.ImportResult res = bank.applyImage(cellIndex, rendered, ncgr, palette);
            assertThat(res.unmatchedPixels).as("a rendered cell fits its own palette exactly").isEqualTo(0);

            BufferedImage again = bank.getNcerImage(cellIndex);
            assertThat(again.getWidth()).isEqualTo(rendered.getWidth());
            assertThat(again.getHeight()).isEqualTo(rendered.getHeight());
            for (int y = 0; y < rendered.getHeight(); y++)
                for (int x = 0; x < rendered.getWidth(); x++)
                    assertThat(again.getRGB(x, y) & 0xFFFFFF)
                            .as("cell %d pixel (%d,%d)", cellIndex, x, y)
                            .isEqualTo(rendered.getRGB(x, y) & 0xFFFFFF);

            // The edited NCGR must save + reload cleanly and still round-trip.
            IndexedImage reloaded = new IndexedImage(ncgr.save(), 0, 0, 1, 1, true);
            reloaded.setPalette(palette);
            bank.setParentImage(reloaded);
            BufferedImage afterReload = bank.getNcerImage(cellIndex);
            for (int y = 0; y < rendered.getHeight(); y++)
                for (int x = 0; x < rendered.getWidth(); x++)
                    assertThat(afterReload.getRGB(x, y) & 0xFFFFFF).isEqualTo(rendered.getRGB(x, y) & 0xFFFFFF);

            exercised = true;
        }
        Assumptions.assumeTrue(exercised, "no loadable NCER+NCGR+NCLR bundle found in the test ROM");
    }

    @Test
    @DisplayName("rebuilding the palette from a rendered cell reproduces it (transparent-mode), unmatched 0")
    void cellRoundTripsWithPaletteRebuild()
    {
        boolean exercised = false;
        for (int f = 0; f < rom.getNumFiles() && !exercised; f++)
        {
            if (!magic(rom.getFile(f)).equals("NARC")) continue;
            Narc narc;
            try { narc = new Narc(rom.getFile(f)); } catch (RuntimeException e) { continue; }
            int ncerI = indexOf(narc, "RECN"), ncgrI = indexOf(narc, "RGCN"), nclrI = indexOf(narc, "RLCN");
            if (ncerI < 0 || ncgrI < 0 || nclrI < 0) continue;

            IndexedImage ncgr;
            Palette palette;
            CellBank bank;
            try
            {
                ncgr = new IndexedImage(narc.getFile(ncgrI), 0, 0, 1, 1, true);
                palette = new Palette(narc.getFile(nclrI), 0);
                ncgr.setPalette(palette);
                bank = new CellBank(narc.getFile(ncerI));
                bank.setParentImage(ncgr);
            }
            catch (RuntimeException e) { continue; }

            int cellIndex = -1;
            for (int c = 0; c < bank.getNumCells(); c++)
            {
                java.awt.Rectangle b = bank.getCellBounds(c);
                if (b.width > 0 && b.height > 0) { cellIndex = c; break; }
            }
            if (cellIndex < 0) continue;

            // Transparent render: index-0 pixels are transparent, so the rebuild (which reserves slot 0)
            // round-trips exactly — opaque colours come back, transparent pixels stay transparent.
            BufferedImage rendered = bank.getTransparentNcerImage(cellIndex);
            CellBank.ImportResult res = bank.applyImageRebuildingPalette(cellIndex, rendered, ncgr, palette);
            assertThat(res.unmatchedPixels).isEqualTo(0);
            assertThat(res.palette).isNotNull();

            BufferedImage again = bank.getTransparentNcerImage(cellIndex);
            for (int y = 0; y < rendered.getHeight(); y++)
                for (int x = 0; x < rendered.getWidth(); x++)
                {
                    int a = rendered.getRGB(x, y), b = again.getRGB(x, y);
                    boolean aT = (a >>> 24) == 0, bT = (b >>> 24) == 0;
                    if (aT || bT) assertThat(bT).as("transparency at (%d,%d)", x, y).isEqualTo(aT);
                    else assertThat(b & 0xFFFFFF).as("colour at (%d,%d)", x, y).isEqualTo(a & 0xFFFFFF);
                }
            exercised = true;
        }
        Assumptions.assumeTrue(exercised, "no loadable NCER+NCGR+NCLR bundle found in the test ROM");
    }
}
