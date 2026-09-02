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

package io.github.turtleisaac.nds4j;

import io.github.turtleisaac.nds4j.framework.NitroLz;
import io.github.turtleisaac.nds4j.images.RawPalette;
import io.github.turtleisaac.nds4j.images.RawTexture;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link RawTexture} (NTFT) and {@link RawPalette} (NTFP) &mdash; headerless raw formats with
 * no confirmed retail example anywhere until <i>Learn with Pok&eacute;mon: Typing Adventure</i> (JP:
 * <i>Battle &amp; Get! Pok&eacute;mon Typing DS</i>) turned one up: 7979 NTFT/NTFP pairs, one per
 * Pok&eacute;mon "note" icon, discovered by filename (these formats have no magic to search for) via each
 * NARC's own filename table.
 * <p>
 * This is in the base package (not {@code images}) because collecting fixtures by filename needs direct
 * access to {@link Narc}'s package-private {@code filenames} field, the same reason {@link NarcTest} and
 * {@link FolderTest} live here.
 */
@DisplayName("NTFT/NTFP (RawTexture/RawPalette)")
public class RawTexturePaletteTest
{
    private static final class Pair
    {
        final byte[] ntft, ntfp;
        Pair(byte[] ntft, byte[] ntfp) { this.ntft = ntft; this.ntfp = ntfp; }
    }

    private static List<Pair> pairs;

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

    @BeforeAll
    static void loadFixtures()
    {
        NintendoDsRom rom = TestRoms.require("Learn with Pokemon - Typing Adventure.nds");
        pairs = new ArrayList<>();
        for (int i = 0; i < rom.getNumFiles(); i++)
        {
            byte[] top;
            try { top = decomp(rom.getFile(i)); }
            catch (RuntimeException e) { continue; }
            if (!magic(top).equals("NARC"))
                continue;
            Narc narc;
            try { narc = new Narc(top); }
            catch (RuntimeException e) { continue; }

            // Every note folder holds exactly one same-named .ntft/.ntfp pair (see the class javadoc).
            for (Map.Entry<String, Fnt.Folder> entry : narc.filenames.getFolders().entrySet())
            {
                String folderName = entry.getKey();
                Fnt.Folder sub = entry.getValue();
                byte[] ntft = null, ntfp = null;
                for (String f : sub.getFiles())
                {
                    if (f.toLowerCase().endsWith(".ntft"))
                        ntft = narc.getFileByName(folderName + "/" + f);
                    else if (f.toLowerCase().endsWith(".ntfp"))
                        ntfp = narc.getFileByName(folderName + "/" + f);
                }
                if (ntft != null && ntfp != null)
                    pairs.add(new Pair(ntft, ntfp));
            }
        }
        Assumptions.assumeFalse(pairs.isEmpty(), "no NTFT/NTFP pairs found in the test ROM");
    }

    @Test
    @DisplayName("save() reproduces every NTFT/NTFP pair in the ROM byte-for-byte")
    void everyPairRoundTripsByteExact()
    {
        // The strongest correctness statement available: the bytes emitted for a file just read are
        // identical to the bytes read. Run over the whole corpus (7979 pairs) so no size (32x32/64x64/
        // 128x128) or color-count variant goes unexercised.
        for (int i = 0; i < pairs.size(); i++)
        {
            Pair p = pairs.get(i);
            assertThat(new RawTexture(p.ntft).save()).as("NTFT pair #%d must round-trip byte-for-byte", i).isEqualTo(p.ntft);
            assertThat(new RawPalette(p.ntfp).save()).as("NTFP pair #%d must round-trip byte-for-byte", i).isEqualTo(p.ntfp);
        }
    }

    @Test
    @DisplayName("every NTFT is square, one of the three known retail sizes")
    void everyTextureIsAKnownSquareSize()
    {
        for (int i = 0; i < pairs.size(); i++)
        {
            RawTexture tex = new RawTexture(pairs.get(i).ntft);
            assertThat(tex.getWidth()).as("pair #%d width == height", i).isEqualTo(tex.getHeight());
            assertThat(tex.getWidth()).as("pair #%d is one of the three retail sizes", i).isIn(32, 64, 128);
        }
    }

    @Test
    @DisplayName("every NTFP is a tightly-packed (non-padded) color count")
    void everyPaletteIsPlausible()
    {
        for (int i = 0; i < pairs.size(); i++)
        {
            RawPalette pal = new RawPalette(pairs.get(i).ntfp);
            assertThat(pal.getNumColors()).as("pair #%d has at least one color", i).isGreaterThan(0);
            assertThat(pal.getNumColors()).as("pair #%d fits in a byte index", i).isLessThanOrEqualTo(256);
        }
    }

    @Test
    @DisplayName("decoding a texture through its palette produces a non-empty, correctly-sized image")
    void decodingProducesRealArtwork()
    {
        Pair p = pairs.get(0);
        RawTexture tex = new RawTexture(p.ntft);
        RawPalette pal = new RawPalette(p.ntfp);

        BufferedImage img = tex.getTransparentImage(pal);
        assertThat(img.getWidth()).isEqualTo(tex.getWidth());
        assertThat(img.getHeight()).isEqualTo(tex.getHeight());

        long opaque = 0;
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++)
                if ((img.getRGB(x, y) >>> 24) != 0)
                    opaque++;
        assertThat(opaque).as("a real icon must draw more than a few stray pixels").isGreaterThan(100);
    }

    @Test
    @DisplayName("an edited pixel and an edited color both persist through save/load")
    void editsRoundTrip()
    {
        Pair p = pairs.get(0);
        RawTexture tex = new RawTexture(p.ntft);
        RawPalette pal = new RawPalette(p.ntfp);

        tex.setPixelValue(0, 0, 7);
        pal.setColor(0, java.awt.Color.MAGENTA);

        RawTexture texReloaded = new RawTexture(tex.save());
        RawPalette palReloaded = new RawPalette(pal.save());
        assertThat(texReloaded.getPixelValue(0, 0)).isEqualTo(7);
        assertThat(palReloaded.getColor(0)).isEqualTo(java.awt.Color.MAGENTA);
    }

    @Test
    @DisplayName("non-square NTFT data and odd-length NTFP data are rejected")
    void rejectsMalformedInput()
    {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RawTexture(new byte[10]))
                .as("10 is not a perfect square")
                .isInstanceOf(RuntimeException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RawPalette(new byte[3]))
                .as("3 is not a multiple of 2")
                .isInstanceOf(RuntimeException.class);
    }
}
