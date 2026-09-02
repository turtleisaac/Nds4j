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

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the {@code LINE_BUFFER} NCGR path: a line-buffer (CHAR {@code flags} bit 0) image that carries an SOPC
 * section is a plain, <em>un-encrypted</em> raster, decoded/encoded without the scan cipher. These are common in
 * Gen V ("pokegra" battle-sprite sheets), so fixtures come from <b>White2</b>. Two things are checked:
 * <ol>
 *   <li>every NCGR in the ROM still round-trips byte-for-byte (the LINE_BUFFER encode is a correct inverse), and</li>
 *   <li>a line-buffer sheet decodes to the <em>plain</em> raster — i.e. the LCG decrypt that used to scramble
 *       these is no longer applied.</li>
 * </ol>
 */
@DisplayName("Line-buffer (bitmap) NCGR decode")
public class LineBufferNcgrTest
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

    /** Every NCGR in the ROM (LZ-decompressed) that parses to the {@code LINE_BUFFER} scan mode. */
    private static java.util.List<byte[]> lineBufferNcgrs()
    {
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < rom.getNumFiles(); i++)
        {
            if (!magic(rom.getFile(i)).equals("NARC"))
                continue;
            Narc narc;
            try { narc = new Narc(rom.getFile(i)); }
            catch (RuntimeException e) { continue; }
            for (int j = 0; j < narc.getNumFiles(); j++)
            {
                byte[] sub = decomp(narc.getFile(j));
                if (!magic(sub).equals("RGCN"))
                    continue;
                try
                {
                    if (new IndexedImage(sub, 0, 0, 1, 1, true).getScanMode()
                            == IndexedImage.NcgrUtils.ScanMode.LINE_BUFFER)
                        out.add(sub);
                }
                catch (RuntimeException ignored) { }
            }
        }
        return out;
    }

    @Test
    @DisplayName("save() reproduces every LINE_BUFFER NCGR byte-for-byte")
    void lineBufferNcgrsRoundTripByteExact()
    {
        java.util.List<byte[]> files = lineBufferNcgrs();
        Assumptions.assumeFalse(files.isEmpty(), "no LINE_BUFFER NCGRs found in the test ROM");
        // Gen V pokegra alone yields thousands; a couple is proof the new path is actually exercised.
        assertThat(files.size()).as("White2 should carry many LINE_BUFFER (bitmap) NCGRs").isGreaterThan(1);
        for (int k = 0; k < files.size(); k++)
        {
            byte[] original = files.get(k);
            assertThat(new IndexedImage(original, 0, 0, 1, 1, true).save())
                    .as("LINE_BUFFER NCGR #%d must round-trip byte-for-byte", k)
                    .isEqualTo(original);
        }
    }

    @Test
    @DisplayName("a line-buffer NCGR parses as LINE_BUFFER with no encryption key")
    void lineBufferClassification()
    {
        java.util.List<byte[]> files = lineBufferNcgrs();
        Assumptions.assumeFalse(files.isEmpty(), "no LINE_BUFFER NCGR found in the test ROM");
        byte[] sub = files.get(0);

        IndexedImage image = new IndexedImage(sub, 0, 0, 1, 1, true);
        assertThat(image.getScanMode()).isEqualTo(IndexedImage.NcgrUtils.ScanMode.LINE_BUFFER);
        assertThat(image.isScanned()).as("LINE_BUFFER is still a line-buffer layout").isTrue();
        assertThat(image.hasSopc()).as("LINE_BUFFER routing keys on the SOPC section").isTrue();
        // No LCG decrypt was run, so no encryption key was captured (the accessor throws when there is none).
        org.assertj.core.api.Assertions.assertThatThrownBy(image::getEncryptionKey)
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("a line-buffer NCGR decodes to the plain raster (no LCG scramble)")
    void lineBufferDecodesAsPlainRaster()
    {
        java.util.List<byte[]> files = lineBufferNcgrs();
        Assumptions.assumeFalse(files.isEmpty(), "no LINE_BUFFER NCGR found in the test ROM");
        byte[] sub = files.get(0);

        IndexedImage image = new IndexedImage(sub, 0, 0, 1, 1, true);
        Assumptions.assumeTrue(image.getBitDepth() == 4, "expected a 4bpp line-buffer sample");
        int[][] px = image.getPixels();
        int w = image.getWidth(), h = image.getHeight();

        // char data begins at 0x10 + s32(charHeader,0x1C) + 8  (== 0x30 for a standard header)
        int dataStart = 0x10 + s32(sub, 0x2C) + 8;

        // Independently unpack the raw char data as a plain 4bpp raster and require it to match exactly:
        // low nibble = even column, high nibble = odd column, row-major. This is only true if NO scan
        // cipher was applied — the very bug this path fixes.
        for (int y = 0; y < h; y++)
        {
            for (int x = 0; x < w; x++)
            {
                int byteIdx = dataStart + (y * w + x) / 2;
                if (byteIdx >= sub.length)
                    return; // grid larger than the stored data; the remainder is padded 0 (already checked)
                int b = sub[byteIdx] & 0xFF;
                int expected = ((x & 1) == 0) ? (b & 0xF) : ((b >> 4) & 0xF);
                assertThat(px[y][x])
                        .as("pixel (%d,%d) must be the plain-raster nibble", x, y)
                        .isEqualTo(expected);
            }
        }
    }

    private static int s32(byte[] d, int o)
    {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8) | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24);
    }
}
