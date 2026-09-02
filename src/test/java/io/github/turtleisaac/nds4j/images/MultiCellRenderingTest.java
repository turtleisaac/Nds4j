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

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the render path that stitches the multi-cell layers together: an NMCR ({@link MultiCellBank})
 * composing an NCER's cells (drawn from an NCGR/NCLR), and an NMAR ({@link MultiCellAnimation}) playing a
 * frame of that multi-cell with its transform. Fixtures come from <b>White2</b>, the only bundled ROM that
 * uses these formats.
 * <p>
 * A coherent, self-referencing NMCR+NCER+NCGR+NCLR set has to be located within one NARC. Not every
 * NCGR these reference is renderable through the tiled OAM path (some pokegra sprites are stored as a
 * scanned bitmap the cell renderer rejects), so the search skips bundles that throw while wiring or
 * rendering and uses the first that produces an image; the test is skipped if none is found.
 */
@DisplayName("Multi-cell (NMCR/NMAR) rendering")
public class MultiCellRenderingTest
{
    private static NintendoDsRom rom;

    @BeforeAll
    static void loadRom()
    {
        rom = TestRoms.require("White2.nds");
    }

    private static String magic(byte[] d)
    {
        return d == null || d.length < 4 ? "" : new String(d, 0, 4, StandardCharsets.ISO_8859_1);
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

    /** A wired, renderable bundle discovered in the ROM. */
    private static final class Bundle
    {
        MultiCellBank nmcr;
        MultiCellAnimation nmar; // may be null
        int renderableMultiCell = -1;
    }

    /**
     * Walks the ROM for a NARC that holds a coherent NMCR/NCER/NCGR/NCLR group (and an NMAR if present)
     * that actually renders. Members are decompressed first, since these often ship LZ-compressed.
     */
    private static Bundle findRenderableBundle()
    {
        int attempts = 0;
        for (int f = 0; f < rom.getNumFiles(); f++)
        {
            if (!magic(rom.getFile(f)).equals("NARC"))
                continue;
            Narc narc;
            try { narc = new Narc(rom.getFile(f)); }
            catch (RuntimeException e) { continue; }

            int n = narc.getNumFiles();
            byte[][] mem = new byte[n][];
            for (int j = 0; j < n; j++)
                mem[j] = decomp(narc.getFile(j));

            for (int j = 0; j < n; j++)
            {
                if (!magic(mem[j]).equals("RCMN"))
                    continue;
                // nearest preceding NCER; ALL nearby NCGR candidates (the one physically closest to the
                // NCER is often a scanned bitmap the cell renderer rejects, so try each); nearby palettes
                int ncer = -1;
                for (int k = j; k >= 0 && k > j - 6; k--)
                    if (magic(mem[k]).equals("RECN")) { ncer = k; break; }
                if (ncer < 0)
                    continue;

                for (int ncgr = Math.max(0, ncer - 6); ncgr <= ncer; ncgr++)
                {
                    if (!magic(mem[ncgr]).equals("RGCN"))
                        continue;
                    for (int nclr = Math.max(0, j - 50); nclr < Math.min(n, j + 50); nclr++)
                    {
                        if (!magic(mem[nclr]).equals("RLCN"))
                            continue;
                        if (++attempts > 4000) // keep the scan bounded
                            return null;
                        try
                        {
                            IndexedImage image = new IndexedImage(mem[ncgr], 0, 0, 1, 1, true);
                            image.setPalette(new Palette(mem[nclr], 0));
                            CellBank cells = new CellBank(mem[ncer]);
                            cells.setParentImage(image); // throws for scanned NCGRs; skip those
                            MultiCellBank nmcr = new MultiCellBank(mem[j]);
                            nmcr.setCellBank(cells);

                            for (int m = 0; m < nmcr.getNumMultiCells(); m++)
                            {
                                BufferedImage img = nmcr.getTransparentMultiCellImage(m);
                                if (anyOpaque(img))
                                {
                                    Bundle b = new Bundle();
                                    b.nmcr = nmcr;
                                    b.renderableMultiCell = m;
                                    for (int k = j; k < Math.min(n, j + 4); k++)
                                        if (magic(mem[k]).equals("RAMN"))
                                        {
                                            MultiCellAnimation nmar = new MultiCellAnimation(mem[k]);
                                            nmar.setMultiCellBank(nmcr);
                                            b.nmar = nmar;
                                            break;
                                        }
                                    return b;
                                }
                            }
                        }
                        catch (RuntimeException e) { /* not a coherent/renderable set; keep looking */ }
                    }
                }
            }
        }
        return null;
    }

    private static boolean anyOpaque(BufferedImage img)
    {
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++)
                if ((img.getRGB(x, y) >>> 24) != 0)
                    return true;
        return false;
    }

    @Test
    @DisplayName("an NMCR composes its cells into a sized image from the NCER/NCGR")
    void multiCellRendersOverCells()
    {
        Bundle b = findRenderableBundle();
        Assumptions.assumeTrue(b != null, "no renderable NMCR/NCER/NCGR/NCLR bundle found in the test ROM");

        int m = b.renderableMultiCell;
        BufferedImage opaque = b.nmcr.getMultiCellImage(m);
        BufferedImage transparent = b.nmcr.getTransparentMultiCellImage(m);
        Rectangle bounds = b.nmcr.getMultiCellBounds(m);

        assertThat(opaque.getWidth()).isPositive();
        assertThat(opaque.getHeight()).isPositive();
        // the bounds the image is sized from must agree with the rendered image
        assertThat(opaque.getWidth()).isEqualTo(Math.max(1, bounds.width));
        assertThat(opaque.getHeight()).isEqualTo(Math.max(1, bounds.height));
        assertThat(transparent.getWidth()).isEqualTo(opaque.getWidth());

        // the transparent variant must actually leave color-index-0 pixels transparent
        boolean anyTransparent = false;
        for (int y = 0; y < transparent.getHeight() && !anyTransparent; y++)
            for (int x = 0; x < transparent.getWidth(); x++)
                if ((transparent.getRGB(x, y) >>> 24) == 0) { anyTransparent = true; break; }
        assertThat(anyTransparent).as("a composed multi-cell should have transparent pixels").isTrue();
    }

    @Test
    @DisplayName("an NMAR renders a frame of its multi-cell without throwing")
    void nmarFrameRenders()
    {
        Bundle b = findRenderableBundle();
        Assumptions.assumeTrue(b != null && b.nmar != null, "no renderable NMAR bundle found in the test ROM");

        MultiCellAnimation.Animation.Frame frame = b.nmar.getAnimations()[0].getFrames()[0];
        BufferedImage img = b.nmar.getFrameImage(frame);
        assertThat(img.getWidth()).isPositive();
        assertThat(img.getHeight()).isPositive();
        // the transform accessors must be readable for whatever element type this animation uses
        frame.getRotation();
        frame.getScaleX();
        frame.getTranslateX();
    }

    @Test
    @DisplayName("rendering a multi-cell without a cell bank fails clearly")
    void multiCellRenderRequiresCellBank()
    {
        List<byte[]> nmcr = NtrFixtures.collect(rom, "RCMN");
        Assumptions.assumeFalse(nmcr.isEmpty(), "no RCMN files found in the test ROM");

        MultiCellBank bank = new MultiCellBank(nmcr.get(0));
        assertThatThrownBy(() -> bank.getMultiCellImage(0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bank.getMultiCellBounds(0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("rendering an NMAR frame without a multi-cell bank fails clearly")
    void frameRenderRequiresMultiCellBank()
    {
        List<byte[]> nmar = NtrFixtures.collect(rom, "RAMN");
        Assumptions.assumeFalse(nmar.isEmpty(), "no RAMN files found in the test ROM");

        MultiCellAnimation anim = new MultiCellAnimation(nmar.get(0));
        MultiCellAnimation.Animation.Frame frame = anim.getAnimations()[0].getFrames()[0];
        assertThatThrownBy(() -> anim.getFrameImage(frame)).isInstanceOf(IllegalStateException.class);
    }
}
