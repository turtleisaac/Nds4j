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

package io.github.turtleisaac.nds4j.g3d;

import io.github.turtleisaac.nds4j.Narc;
import io.github.turtleisaac.nds4j.NintendoDsRom;
import io.github.turtleisaac.nds4j.TestRoms;
import io.github.turtleisaac.nds4j.framework.MemBuf;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link G3dDictionary#build} &mdash; the writer-side keystone for source&rarr;NSB* conversion. The
 * builder must construct a <em>valid</em> NNS Patricia dictionary: it re-parses to the same names/records,
 * every name looks up to the right entry ({@link G3dDictionary#lookup}), and the on-disk structure is
 * correct. Validated against thousands of real dictionaries extracted from the retail ROMs (their
 * {@code TEX0} texture and palette dictionaries), plus a hand-checked byte-exact case.
 */
@DisplayName("G3dDictionary.build (NNS Patricia writer)")
public class G3dDictionaryBuildTest
{
    private static String magic(byte[] d, int o)
    {
        return d == null || d.length < o + 4 ? "" : new String(d, o, 4, StandardCharsets.ISO_8859_1);
    }

    private static int u16(byte[] d, int o) { return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8); }
    private static long u32(byte[] d, int o)
    {
        return (d[o] & 0xFFL) | ((d[o + 1] & 0xFFL) << 8) | ((d[o + 2] & 0xFFL) << 16) | ((d[o + 3] & 0xFFL) << 24);
    }

    private static byte[] serialize(G3dDictionary d)
    {
        MemBuf buf = MemBuf.create();
        d.write(buf.writer());
        return buf.reader().getBuffer();
    }

    // Parse a dictionary at an offset in a block, then rebuild it from its names/records and check it.
    private static int[] checkDictAt(byte[] block, int pos, int[] tally)
    {
        MemBuf buf = MemBuf.create(block);
        MemBuf.MemBufReader r = buf.reader();
        r.setPosition(pos);
        G3dDictionary original;
        try { original = new G3dDictionary(r); }
        catch (RuntimeException e) { return tally; }
        int count = original.size();
        if (count == 0)
            return tally;

        List<String> names = new ArrayList<>();
        List<byte[]> records = new ArrayList<>();
        for (int i = 0; i < count; i++) { names.add(original.getName(i)); records.add(original.getRecord(i)); }
        // duplicate names can't be represented by a name-keyed tree; skip (does not occur in practice)
        if (names.stream().distinct().count() != count)
            return tally;

        G3dDictionary built = G3dDictionary.build(names, records, original.getElementSize());
        tally[0]++; // tested

        // (a) every name resolves to its entry
        boolean lookupOk = true;
        for (int i = 0; i < count; i++)
            if (built.lookup(names.get(i)) != i) { lookupOk = false; break; }
        if (lookupOk) tally[1]++;

        // (b) the built dictionary re-parses to identical names/records
        G3dDictionary reparsed = new G3dDictionary(MemBuf.create(serialize(built)).reader());
        boolean reparseOk = reparsed.size() == count;
        for (int i = 0; i < count && reparseOk; i++)
            reparseOk = reparsed.getName(i).equals(names.get(i)) && java.util.Arrays.equals(reparsed.getRecord(i), records.get(i));
        if (reparseOk) tally[2]++;

        // (c) bonus: byte-exact against the retail dictionary (matches when node numbering aligns)
        int origLen = 16 + count * (20 + original.getElementSize());
        if (pos + origLen <= block.length)
        {
            byte[] origBytes = java.util.Arrays.copyOfRange(block, pos, pos + origLen);
            if (java.util.Arrays.equals(serialize(built), origBytes)) tally[3]++;
        }
        return tally;
    }

    private static void scanTex0(byte[] block, int[] tally)
    {
        if (block.length < 16 || !magic(block, 0).equals("TEX0"))
            return;
        int texDict = u16(block, 14);
        if (texDict > 0 && texDict < block.length - 8 && (block[texDict + 1] & 0xFF) < 64)
            checkDictAt(block, texDict, tally);
    }

    @Test
    @DisplayName("builds valid, re-parseable dictionaries for every retail TEX0 dictionary")
    void buildsValidDictionaries()
    {
        int[] tally = new int[4]; // tested, lookupOk, reparseOk, byteExact
        for (String romName : new String[]{"Platinum.nds", "HeartGold.nds", "Diamond.nds"})
        {
            NintendoDsRom rom;
            try { rom = TestRoms.require(romName); }
            catch (RuntimeException e) { continue; }
            for (int i = 0; i < rom.getNumFiles(); i++)
            {
                byte[] f = rom.getFile(i);
                if (!magic(f, 0).equals("NARC"))
                    continue;
                Narc narc;
                try { narc = new Narc(f); }
                catch (RuntimeException e) { continue; }
                for (int j = 0; j < narc.getNumFiles(); j++)
                {
                    byte[] bf = narc.getFile(j);
                    if (bf == null || bf.length < 20)
                        continue;
                    if (magic(bf, 0).equals("BTX0"))
                    {
                        int b0 = (int) u32(bf, 16);
                        if (b0 < bf.length - 4)
                            scanTex0(java.util.Arrays.copyOfRange(bf, b0, bf.length), tally);
                    }
                    else if (magic(bf, 0).equals("BMD0"))
                    {
                        int nb = u16(bf, 14);
                        for (int k = 0; k < nb; k++)
                        {
                            int bo = (int) u32(bf, 16 + k * 4);
                            if (bo < bf.length - 4 && magic(bf, bo).equals("TEX0"))
                            {
                                scanTex0(java.util.Arrays.copyOfRange(bf, bo, bf.length), tally);
                                break;
                            }
                        }
                    }
                }
            }
        }
        Assumptions.assumeTrue(tally[0] > 100, "need retail dictionaries to test against");
        System.out.printf("G3dDictionary.build: tested=%d lookupOk=%d reparseOk=%d byteExact=%d%n",
                tally[0], tally[1], tally[2], tally[3]);
        // every built tree must be a valid, re-parseable dictionary
        assertThat(tally[1]).as("every built tree resolves all names").isEqualTo(tally[0]);
        assertThat(tally[2]).as("every built dictionary re-parses identically").isEqualTo(tally[0]);
        // node numbering matches retail for a meaningful fraction (documents byte-exactness)
        assertThat(tally[3]).as("many match retail byte-for-byte").isGreaterThan(tally[0] / 5);
    }

    @Test
    @DisplayName("assembleContainer rebuilds a real NSB* file byte-for-byte from its blocks")
    void containerAssemblyIsByteExact()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        int checked = 0;
        outer:
        for (int i = 0; i < rom.getNumFiles(); i++)
        {
            byte[] f = rom.getFile(i);
            if (!magic(f, 0).equals("NARC"))
                continue;
            Narc narc;
            try { narc = new Narc(f); }
            catch (RuntimeException e) { continue; }
            for (int j = 0; j < narc.getNumFiles(); j++)
            {
                byte[] file = narc.getFile(j);
                String m = magic(file, 0);
                if (file == null || file.length < 24 || !(m.equals("BMA0") || m.equals("BTA0") || m.equals("BTP0")))
                    continue;
                int version = u16(file, 6);
                int numBlocks = u16(file, 14);
                if (numBlocks < 1 || numBlocks > 8)
                    continue;
                byte[][] blocks = new byte[numBlocks][];
                for (int k = 0; k < numBlocks; k++)
                {
                    int start = (int) u32(file, 16 + k * 4);
                    int end = (k + 1 < numBlocks) ? (int) u32(file, 16 + (k + 1) * 4) : file.length;
                    blocks[k] = java.util.Arrays.copyOfRange(file, start, end);
                }
                assertThat(G3dFile.assembleContainer(m, version, blocks))
                        .as("re-encoding %s blocks reproduces the file", m).isEqualTo(file);
                if (++checked >= 20)
                    break outer;
            }
        }
        Assumptions.assumeTrue(checked > 0, "no NSB* files to reassemble");
    }

    @Test
    @DisplayName("a hand-checked two-entry dictionary is byte-exact")
    void handCheckedByteExact()
    {
        // ball_blue / ball_w — verified by hand against the retail Patricia nodes
        List<String> names = List.of("ball_blue", "ball_w");
        List<byte[]> records = List.of(new byte[]{1, 2, 3, 4}, new byte[]{5, 6, 7, 8});
        G3dDictionary d = G3dDictionary.build(names, records, 4);
        assertThat(d.lookup("ball_blue")).isEqualTo(0);
        assertThat(d.lookup("ball_w")).isEqualTo(1);
        assertThat(d.lookup("nope")).isEqualTo(-1);

        byte[] bytes = serialize(d);
        // rev=0, count=2, sizeDict=16+2*(20+4)=64=0x40, const 8, 12+8=20=0x14
        assertThat(bytes[0]).isEqualTo((byte) 0);
        assertThat(bytes[1]).isEqualTo((byte) 2);
        assertThat(u16(bytes, 2)).as("sizeDict").isEqualTo(64);
        assertThat(u16(bytes, 4)).isEqualTo(0x0008);
        assertThat(u16(bytes, 6)).isEqualTo(0x0014);
        // nodes: {127,1,0,0}{70,2,1,0}{46,0,2,1}
        int t = 8;
        assertThat(bytes[t] & 0xFF).isEqualTo(127);
        assertThat(new int[]{bytes[t + 4] & 0xFF, bytes[t + 5] & 0xFF, bytes[t + 6] & 0xFF, bytes[t + 7] & 0xFF})
                .containsExactly(70, 2, 1, 0);
        assertThat(new int[]{bytes[t + 8] & 0xFF, bytes[t + 9] & 0xFF, bytes[t + 10] & 0xFF, bytes[t + 11] & 0xFF})
                .containsExactly(46, 0, 2, 1);
    }
}
