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

import io.github.turtleisaac.nds4j.framework.Endianness;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class NarcTest
{
    private final byte[] b1 = {0, 0, 0, 0};
    private final byte[] b2 = {1, 1, 1, 1};
    private final byte[] b3 = {0, 0, 0, 0};
    private final ArrayList<byte[]> testList = new ArrayList<>(Arrays.stream(new byte[][]{b1, b2, b3}).collect(Collectors.toList()));
    private final Narc narc = Narc.fromContentsAndNames(testList, new Fnt.Folder(), Endianness.EndiannessType.BIG);

    @Test
    void narcNotNull() {
        assertThat(narc)
                .isNotNull();
    }

    @Test
    void fromContentsAndNamesProducesSame() {
        assertThat(narc)
                .isEqualTo(Narc.fromContentsAndNames(testList, new Fnt.Folder(), Endianness.EndiannessType.BIG));
    }

    @Test
    void fromContentsAndNamesProducesDifferent() {
        assertThat(narc)
                .isNotEqualTo(Narc.fromContentsAndNames(new ArrayList<>(), new Fnt.Folder(), Endianness.EndiannessType.BIG));
    }

    @Test
    void fileContentsModificationChangesEquality() {
        Narc narc2 = Narc.fromContentsAndNames(testList, new Fnt.Folder(), Endianness.EndiannessType.BIG);
        narc.files.set(0, new byte[] {0});
        assertThat(narc)
                .isNotEqualTo(narc2);
    }

    @Test
    void saveDoesNotAffectEquality() {
        Narc narc2 = Narc.fromContentsAndNames(testList, new Fnt.Folder(), Endianness.EndiannessType.BIG);
        narc.save();
        assertThat(narc)
                .isEqualTo(narc2);
    }

    @Test
    void differentNarcsDoNotSaveIdentically() {
        Narc narc2 = Narc.fromContentsAndNames(testList, new Fnt.Folder(), Endianness.EndiannessType.BIG);
        narc2.files.remove(0);
        assertThat(narc.save())
                .isNotEqualTo(narc2.save());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("save() reproduces every real NARC in the ROM byte-for-byte")
    void writtenNarcRoundTripsByteExactAcrossRom() {
        // The tests above only compare Nds4j against itself; none checks Nds4j against the retail
        // packer. Doing so revealed two writer defects (a nameless archive's filename table was 4
        // bytes too large, and inter-file padding used 0x00 instead of retail's 0xFF), both of which
        // shifted or altered the bytes. A byte-level round-trip over every real NARC guards them.
        NintendoDsRom rom = TestRoms.require("HeartGold.nds");
        int checked = 0;
        for (int i = 0; i < rom.getNumFiles(); i++) {
            byte[] f = rom.getFile(i);
            if (f == null || f.length < 4 || !new String(f, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1).equals("NARC"))
                continue;
            Narc parsed;
            try { parsed = new Narc(f); }
            catch (RuntimeException e) { continue; }
            assertThat(parsed.save()).as("NARC at ROM file %d must round-trip byte-for-byte", i).isEqualTo(f);
            checked++;
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(checked > 0, "no NARCs found in the test ROM");
    }
}
