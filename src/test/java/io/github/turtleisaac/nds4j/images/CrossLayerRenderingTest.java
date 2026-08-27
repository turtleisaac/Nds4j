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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the visual pipeline that stitches the 2D formats together: an NSCR drawing its tiles from
 * an NCGR/NCLR, and an NANR playing an NCER's cells (themselves drawn from an NCGR), including editing
 * pixels through those layers back down to the source graphics.
 * <p>
 * These need a set of related files that actually reference each other, which retail ROMs bundle into a
 * single NARC. The tests locate the first NARC that carries a coherent bundle and that loads cleanly
 * (some NCGRs are stored scanned, which the cell assemblers reject); if none is found the test is
 * skipped rather than failed.
 */
@DisplayName("Cross-layer rendering and write-back")
public class CrossLayerRenderingTest
{
    private static NintendoDsRom rom;

    @BeforeAll
    static void loadRom()
    {
        rom = TestRoms.require("HeartGold.nds");
    }

    private static String magic(byte[] d)
    {
        return d == null || d.length < 4 ? "" : new String(d, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /** Finds the index of the first embedded file of the given magic in a NARC, or -1. */
    private static int indexOf(Narc narc, String magic)
    {
        for (int i = 0; i < narc.getNumFiles(); i++)
            if (magic(narc.getFile(i)).equals(magic))
                return i;
        return -1;
    }

    @Test
    @DisplayName("a screen assembles its tiles from an NCGR/NCLR and edits flow back to the NCGR")
    void screenRendersAndWritesBack()
    {
        boolean exercised = false;

        for (int f = 0; f < rom.getNumFiles() && !exercised; f++)
        {
            if (!magic(rom.getFile(f)).equals("NARC"))
                continue;
            Narc narc;
            try { narc = new Narc(rom.getFile(f)); }
            catch (RuntimeException e) { continue; }

            int ncgrI = indexOf(narc, "RGCN"), nclrI = indexOf(narc, "RLCN"), nscrI = indexOf(narc, "RCSN");
            if (ncgrI < 0 || nclrI < 0 || nscrI < 0)
                continue;

            IndexedImage ncgr;
            Palette palette;
            Screen screen;
            try
            {
                ncgr = new IndexedImage(narc.getFile(ncgrI), 0, 0, 1, 1, true);
                palette = new Palette(narc.getFile(nclrI), 0);
                screen = new Screen(narc.getFile(nscrI));
            }
            catch (RuntimeException e) { continue; }

            BufferedImage image = screen.getImage(ncgr, palette);
            assertThat(image.getWidth()).isEqualTo(screen.getWidth());
            assertThat(image.getHeight()).isEqualTo(screen.getHeight());

            // The transparent variant must actually leave some pixels transparent (colour index 0),
            // otherwise it is indistinguishable from the opaque render.
            BufferedImage transparent = screen.getTransparentImage(ncgr, palette);
            boolean anyTransparent = false;
            for (int y = 0; y < transparent.getHeight() && !anyTransparent; y++)
                for (int x = 0; x < transparent.getWidth(); x++)
                    if ((transparent.getRGB(x, y) >>> 24) == 0) { anyTransparent = true; break; }
            assertThat(anyTransparent).as("a screen render should have transparent (index 0) pixels").isTrue();

            // Editing a source pixel through the screen must change the NCGR it draws from.
            byte[] before = ncgr.save();
            int current = ncgr.getPixelValue(0, 0);
            screen.setSourcePixel(ncgr, 0, 0, current == 1 ? 2 : 1);
            assertThat(ncgr.save())
                    .as("editing a screen pixel must write through to the source NCGR")
                    .isNotEqualTo(before);

            exercised = true;
        }

        Assumptions.assumeTrue(exercised, "no loadable NCGR+NCLR+NSCR bundle found in the test ROM");
    }

    @Test
    @DisplayName("an animation frame renders its cell and pixel edits flow back to the NCGR")
    void animationRendersAndWritesBack()
    {
        boolean exercised = false;

        for (int f = 0; f < rom.getNumFiles() && !exercised; f++)
        {
            if (!magic(rom.getFile(f)).equals("NARC"))
                continue;
            Narc narc;
            try { narc = new Narc(rom.getFile(f)); }
            catch (RuntimeException e) { continue; }

            int nanrI = indexOf(narc, "RNAN"), ncerI = indexOf(narc, "RECN"),
                    ncgrI = indexOf(narc, "RGCN"), nclrI = indexOf(narc, "RLCN");
            if (nanrI < 0 || ncerI < 0 || ncgrI < 0 || nclrI < 0)
                continue;

            IndexedImage ncgr;
            CellBank bank;
            CellAnimation animation;
            try
            {
                ncgr = new IndexedImage(narc.getFile(ncgrI), 0, 0, 1, 1, true);
                ncgr.setPalette(new Palette(narc.getFile(nclrI), 0));
                bank = new CellBank(narc.getFile(ncerI));
                bank.setParentImage(ncgr); // throws for scanned NCGRs; skip those bundles
                animation = new CellAnimation(narc.getFile(nanrI));
                animation.setCellBank(bank);
            }
            catch (RuntimeException e) { continue; }

            CellAnimation.Animation.Frame frame = animation.getAnimations()[0].getFrames()[0];

            // Rendering the frame must produce the cell-bank-sized canvas without throwing, and the
            // transform accessors must be readable for whatever element type this animation uses.
            BufferedImage frameImage = animation.getFrameImage(frame);
            assertThat(frameImage.getWidth()).isEqualTo(CellBank.NCER_CANVAS_SIZE);
            assertThat(frameImage.getHeight()).isEqualTo(CellBank.NCER_CANVAS_SIZE);
            frame.getRotation();
            frame.getScaleX();
            frame.getTranslateX();

            // A pixel edit on the frame's cell must reach the source NCGR (NANR -> NCER -> NCGR).
            byte[] before = ncgr.save();
            CellBank.Cell.CellImage cellImage = animation.getFrameCellImage(frame);
            int current = cellImage.getPixelValue(4, 4);
            cellImage.setPixelValue(4, 4, current == 1 ? 2 : 1);
            cellImage.save();
            assertThat(ncgr.save())
                    .as("editing an animation frame's pixel must write through to the source NCGR")
                    .isNotEqualTo(before);

            exercised = true;
        }

        Assumptions.assumeTrue(exercised, "no loadable NANR+NCER+NCGR+NCLR bundle found in the test ROM");
    }

    @Test
    @DisplayName("rendering a frame without a cell bank fails clearly")
    void frameRenderRequiresCellBank()
    {
        List<byte[]> nanr = NtrFixtures.collect(rom, "RNAN");
        Assumptions.assumeFalse(nanr.isEmpty(), "no RNAN files found in the test ROM");

        CellAnimation animation = new CellAnimation(nanr.get(0));
        CellAnimation.Animation.Frame frame = animation.getAnimations()[0].getFrames()[0];
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> animation.getFrameImage(frame))
                .isInstanceOf(IllegalStateException.class);
    }
}
