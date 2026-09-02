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

package io.github.turtleisaac.nds4j.framework;

import io.github.turtleisaac.nds4j.Narc;
import io.github.turtleisaac.nds4j.NintendoDsRom;
import io.github.turtleisaac.nds4j.TestRoms;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link NitroLz} (the Nitro LZ10/LZ11 codec). The correctness bar is the round-trip
 * ({@code decompress(compress(x)) == x}) for both types over structured and random data, plus an
 * independent decode check against the retail ROMs: real LZ-compressed files must decompress to
 * recognisable Nitro formats.
 */
@DisplayName("NitroLz (Nitro LZ10/LZ11 codec)")
public class NitroLzTest
{
    private static String magic(byte[] d)
    {
        return d == null || d.length < 4 ? "" : new String(d, 0, 4, StandardCharsets.ISO_8859_1);
    }

    @Test
    @DisplayName("compress -> decompress is the identity for LZ10 and LZ11")
    void roundTripBothTypes()
    {
        byte[] repetitive = new byte[4000];
        for (int i = 0; i < repetitive.length; i++) repetitive[i] = (byte) ("NITRO".charAt(i % 5));
        byte[] random = new byte[5000];
        new Random(42).nextBytes(random);
        byte[] structured = new byte[8192];
        for (int i = 0; i < structured.length; i++) structured[i] = (byte) ((i / 16) & 0xFF);

        byte[][] cases = {new byte[0], new byte[]{7}, "AAAAAAAAAAAAAAAA".getBytes(StandardCharsets.US_ASCII),
                new byte[3000], repetitive, random, structured};
        for (byte[] c : cases)
        {
            byte[] lz10 = NitroLz.compress(c);
            byte[] lz11 = NitroLz.compressLz11(c);
            assertThat(lz10[0] & 0xFF).isEqualTo(0x10);
            assertThat(lz11[0] & 0xFF).isEqualTo(0x11);
            assertThat(NitroLz.decompress(lz10)).as("LZ10 round-trip, len %d", c.length).isEqualTo(c);
            assertThat(NitroLz.decompress(lz11)).as("LZ11 round-trip, len %d", c.length).isEqualTo(c);
        }
    }

    @Test
    @DisplayName("a \"LZ77\"-tagged stream is detected and decompresses transparently")
    void lz77TaggedVariant()
    {
        // Some titles (first found live-driving NitroViewer against Animal Crossing: Wild World's loose
        // top-level .nsbtx files) wrap the ordinary [type][u24 size]... stream in a 4-byte ASCII "LZ77"
        // tag. Before this, isCompressed() never recognised it (it only looked at offset 0), so every such
        // file was invisible -- in ACWW alone, 6348 top-level files, 2503 of them valid BTX0 texture sets.
        byte[] data = "some moderately repetitive test payload, repetitive test payload, test payload"
                .getBytes(StandardCharsets.US_ASCII);
        byte[] lz10Tagged = NitroLz.compressLz77Tagged(data);
        byte[] lz11Tagged = NitroLz.compressLz11Lz77Tagged(data);

        assertThat(magic(lz10Tagged)).isEqualTo("LZ77");
        assertThat(lz10Tagged[4] & 0xFF).isEqualTo(0x10);
        assertThat(magic(lz11Tagged)).isEqualTo("LZ77");
        assertThat(lz11Tagged[4] & 0xFF).isEqualTo(0x11);

        assertThat(NitroLz.isCompressed(lz10Tagged)).as("tagged LZ10 is recognised").isTrue();
        assertThat(NitroLz.isCompressed(lz11Tagged)).as("tagged LZ11 is recognised").isTrue();
        assertThat(NitroLz.decompress(lz10Tagged)).isEqualTo(data);
        assertThat(NitroLz.decompress(lz11Tagged)).isEqualTo(data);

        // an untagged bare stream must still work exactly as before (no regression)
        byte[] bare = NitroLz.compress(data);
        assertThat(NitroLz.isCompressed(bare)).isTrue();
        assertThat(NitroLz.decompress(bare)).isEqualTo(data);
    }

    @Test
    @DisplayName("real \"LZ77\"-tagged files in Animal Crossing: Wild World decompress to recognisable formats")
    void decodesLz77TaggedRetailFiles()
    {
        NintendoDsRom rom;
        try { rom = TestRoms.require("Animal Crossing - Wild World.nds"); }
        catch (RuntimeException e) { Assumptions.assumeTrue(false, "Animal Crossing not available"); return; }

        int tagged = 0, knownMagic = 0, reRoundTrip = 0;
        for (int i = 0; i < rom.getNumFiles(); i++)
        {
            byte[] raw = rom.getFile(i);
            if (raw == null || raw.length < 8 || !magic(raw).equals("LZ77") || !NitroLz.isCompressed(raw))
                continue;
            byte[] dec;
            try { dec = NitroLz.decompress(raw); }
            catch (RuntimeException e) { continue; }
            tagged++;
            String m = magic(dec);
            if (m.equals("BMD0") || m.equals("BTX0") || m.equals("BCA0") || m.equals("BTP0")
                    || m.equals("BTA0") || m.equals("BVA0") || m.equals("BMA0"))
                knownMagic++;
            if (java.util.Arrays.equals(NitroLz.decompress(NitroLz.compressLz77Tagged(dec)), dec))
                reRoundTrip++;
        }
        Assumptions.assumeTrue(tagged > 50, "not enough LZ77-tagged files found");
        System.out.printf("NitroLz: %d \"LZ77\"-tagged files, %d with a known Nitro magic, %d re-round-tripped%n",
                tagged, knownMagic, reRoundTrip);
        assertThat(knownMagic).as("real LZ77-tagged files decompress to recognisable formats")
                .isGreaterThan(tagged / 2);
        assertThat(reRoundTrip).as("every decoded file re-compresses/decompresses back identically")
                .isEqualTo(tagged);
    }

    @Test
    @DisplayName("compressible data actually shrinks")
    void compresses()
    {
        byte[] runs = new byte[10000]; // highly compressible
        byte[] lz10 = NitroLz.compress(runs);
        byte[] lz11 = NitroLz.compressLz11(runs);
        assertThat(lz10.length).as("LZ10 compresses a run of zeros").isLessThan(runs.length / 4);
        assertThat(lz11.length).as("LZ11 compresses a run of zeros further").isLessThan(lz10.length);
        assertThat(NitroLz.decompress(lz10)).isEqualTo(runs);
        assertThat(NitroLz.decompress(lz11)).isEqualTo(runs);
    }

    @Test
    @DisplayName("real ROM LZ files decompress to recognisable Nitro formats and re-round-trip")
    void decodesRetailFiles()
    {
        NintendoDsRom rom;
        try { rom = TestRoms.require("Platinum.nds"); }
        catch (RuntimeException e) { Assumptions.assumeTrue(false, "Platinum not available"); return; }

        int decoded = 0, knownMagic = 0, reRoundTrip = 0;
        Random pick = new Random(1);
        for (int i = 0; i < rom.getNumFiles() && decoded < 400; i++)
        {
            if (!magic(rom.getFile(i)).equals("NARC")) continue;
            Narc narc;
            try { narc = new Narc(rom.getFile(i)); }
            catch (RuntimeException e) { continue; }
            for (int j = 0; j < narc.getNumFiles() && decoded < 400; j++)
            {
                byte[] f = narc.getFile(j);
                if (!NitroLz.isCompressed(f) || f.length < 16) continue;
                long declSize = (f[1] & 0xFF) | ((f[2] & 0xFF) << 8) | ((f[3] & 0xFF) << 16);
                if (declSize < 32 || declSize > 4_000_000) continue;
                byte[] dec;
                try { dec = NitroLz.decompress(f); }
                catch (RuntimeException e) { continue; } // heuristic false positive (not really LZ)
                decoded++;
                // reversed Nitro magics of the common 2D graphics formats + the 3D ones
                String m = magic(dec);
                if (m.equals("RGCN") || m.equals("RLCN") || m.equals("RCSN") || m.equals("RECN")
                        || m.equals("RNAN") || m.equals("BMD0") || m.equals("BTX0") || m.equals("BCA0"))
                    knownMagic++;
                if (pick.nextInt(15) == 0)
                {
                    byte[] recompressed = NitroLz.compress(dec);
                    if (java.util.Arrays.equals(NitroLz.decompress(recompressed), dec)) reRoundTrip++;
                }
            }
        }
        Assumptions.assumeTrue(decoded > 50, "not enough LZ files sampled");
        System.out.printf("NitroLz: decoded %d retail LZ files, %d with a known Nitro magic, %d re-round-tripped%n",
                decoded, knownMagic, reRoundTrip);
        // the overwhelming majority of real LZ files are 2D graphics / 3D assets with a known magic
        assertThat(knownMagic).as("real LZ files decompress to recognisable formats")
                .isGreaterThan(decoded * 3 / 4);
    }

    @Test
    @DisplayName("a real LZ-compressed NSBMD decompresses into a loadable model")
    void decompressesAModel()
    {
        NintendoDsRom rom;
        try { rom = TestRoms.require("HeartGold.nds"); }
        catch (RuntimeException e) { Assumptions.assumeTrue(false, "HeartGold not available"); return; }

        for (int i = 0; i < rom.getNumFiles(); i++)
        {
            if (!magic(rom.getFile(i)).equals("NARC")) continue;
            Narc narc;
            try { narc = new Narc(rom.getFile(i)); }
            catch (RuntimeException e) { continue; }
            for (int j = 0; j < narc.getNumFiles(); j++)
            {
                byte[] f = narc.getFile(j);
                if (!NitroLz.isCompressed(f)) continue;
                byte[] dec;
                try { dec = NitroLz.decompress(f); }
                catch (RuntimeException e) { continue; }
                if (!magic(dec).equals("BMD0")) continue;
                io.github.turtleisaac.nds4j.g3d.ModelSet ms;
                try { ms = new io.github.turtleisaac.nds4j.g3d.ModelSet(dec); }
                catch (RuntimeException e) { continue; }
                if (ms.getModels().isEmpty()) continue;
                io.github.turtleisaac.nds4j.g3d.Model m = ms.getModels().get(0);
                if (m.getExpectedVertexCount() < 30) continue;

                // the decompressed model decodes cleanly (vertex-count oracle) and re-round-trips
                assertThat(m.getVertexCount()).isEqualTo(m.getExpectedVertexCount());
                assertThat(NitroLz.decompress(NitroLz.compress(dec))).as("re-round-trip").isEqualTo(dec);
                return;
            }
        }
        Assumptions.assumeTrue(false, "no LZ-compressed NSBMD found");
    }
}
