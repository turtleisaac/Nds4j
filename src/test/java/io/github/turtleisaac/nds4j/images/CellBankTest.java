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

import io.github.turtleisaac.nds4j.NintendoDsRom;
import io.github.turtleisaac.nds4j.TestRoms;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link CellBank} (NCER), exercised against every RECN file in a retail ROM. NCER is the
 * densest of the 2D formats — bank types 0 and 1, optional VRAM-transfer partition data, optional
 * TACU attributes, and a label section whose entry count is independent of the cell count — so a
 * round-trip over the whole population is what actually pins the reader and writer down.
 */
@DisplayName("NCER (CellBank)")
public class CellBankTest
{
    private static List<byte[]> ncerFiles;

    @BeforeAll
    static void loadFixtures()
    {
        NintendoDsRom rom = TestRoms.require("HeartGold.nds");
        ncerFiles = NtrFixtures.collect(rom, "RECN");
        Assumptions.assumeFalse(ncerFiles.isEmpty(), "no RECN files found in the test ROM");
    }

    @Test
    @DisplayName("save() reproduces every RECN file byte-for-byte")
    void writtenNcerEqualsOriginalBytes()
    {
        // Covers both bank types, VRAM partition and TACU sections, and label sections that name only
        // some cells or carry more names than there are cells.
        for (int i = 0; i < ncerFiles.size(); i++)
        {
            byte[] original = ncerFiles.get(i);
            byte[] written = new CellBank(original).save();
            assertThat(written)
                    .as("RECN file #%d must round-trip byte-for-byte", i)
                    .isEqualTo(original);
        }
    }

    @Test
    @DisplayName("save() reproduces every RECN file in Phantom Hourglass byte-for-byte")
    void writtenNcerRoundTripsByteExactAcrossPhantomHourglass()
    {
        // Found a fourth writer defect this way, against a third-party (non-Pokemon) title: 137/228 of
        // this ROM's NCER files are a couple of bytes physically longer than their own NTR header
        // declares (e.g. a 256-byte file whose header fileSize field says 254), with the remainder
        // outside every declared section -- not VRAM-partition/TACU data, not label data. Now captured
        // as trailingPadding and re-emitted; the header's own fileSize field is preserved from the parse
        // (like Palette's identical fix) rather than recomputed, since it legitimately differs from the
        // physical length.
        NintendoDsRom rom = TestRoms.require("Legend of Zelda, The - Phantom Hourglass.nds");
        List<byte[]> files = NtrFixtures.collect(rom, "RECN");
        Assumptions.assumeFalse(files.isEmpty(), "no RECN files found in the test ROM");
        for (int i = 0; i < files.size(); i++)
        {
            byte[] original = files.get(i);
            byte[] written = new CellBank(original).save();
            assertThat(written).as("RECN file #%d must round-trip byte-for-byte", i).isEqualTo(original);
        }
    }

    @Test
    @DisplayName("a saved NCER re-reads equal to the original object")
    void writtenNcerEqualsOriginalObject()
    {
        for (int i = 0; i < ncerFiles.size(); i++)
        {
            CellBank original = new CellBank(ncerFiles.get(i));
            CellBank reloaded = new CellBank(original.save());
            assertThat(reloaded)
                    .as("RECN file #%d must equal itself after a save/load cycle", i)
                    .isEqualTo(original);
            assertThat(reloaded.hashCode())
                    .as("equal banks must share a hash code")
                    .isEqualTo(original.hashCode());
        }
    }

    @Test
    @DisplayName("distinct cell banks are not equal")
    void distinctBanksAreNotEqual()
    {
        // equals() must actually discriminate, or the round-trip equality above proves nothing.
        CellBank a = new CellBank(ncerFiles.get(0));
        byte[] otherBytes = ncerFiles.stream().filter(f -> !java.util.Arrays.equals(f, ncerFiles.get(0)))
                .findFirst().orElse(null);
        Assumptions.assumeTrue(otherBytes != null, "need two different NCER files");
        assertThat(a).isNotEqualTo(new CellBank(otherBytes));
    }

    @Test
    @DisplayName("the fixtures exercise both bank types")
    void bothBankTypesArePresent()
    {
        // Bank types 0 and 1 lay their cells out differently (type 1 adds an 8-byte bounding rectangle),
        // so the byte-exact test only proves both paths if both appear in the corpus.
        boolean sawType0 = false, sawType1 = false;
        for (byte[] file : ncerFiles)
        {
            int type = new CellBank(file).getBankType();
            sawType0 |= type == 0;
            sawType1 |= type == 1;
        }
        assertThat(sawType0).as("a bank-type-0 NCER should be present").isTrue();
        assertThat(sawType1).as("a bank-type-1 NCER should be present").isTrue();
    }

    @Test
    @DisplayName("cell geometry is coherent")
    void cellGeometryIsCoherent()
    {
        CellBank bank = new CellBank(ncerFiles.get(0));
        assertThat(bank.getNumCells()).isGreaterThan(0);

        for (int i = 0; i < bank.getNumCells(); i++)
        {
            Rectangle bounds = bank.getCellBounds(i);
            assertThat(bounds.width).as("cell %d has a non-negative width", i).isGreaterThanOrEqualTo(0);
            assertThat(bounds.height).as("cell %d has a non-negative height", i).isGreaterThanOrEqualTo(0);

            for (CellBank.Cell.OAM oam : bank.getCell(i).getOams())
            {
                int[] size = CellBank.getOamSize(oam);
                assertThat(size[0]).as("OAM width is one of the eight legal sizes").isIn(8, 16, 32, 64);
                assertThat(size[1]).as("OAM height is one of the eight legal sizes").isIn(8, 16, 32, 64);
            }
        }
    }

    @Test
    @DisplayName("an edited OAM field survives a save/load cycle")
    void editedOamFieldPersists()
    {
        // Exercises the OAM attribute packing directly: change a field, serialise, and confirm the
        // change is read back — the write path for the attr0/attr1/attr2 bitfields.
        CellBank bank = new CellBank(ncerFiles.get(0));
        CellBank.Cell.OAM oam = firstOam(bank);
        Assumptions.assumeTrue(oam != null, "need an NCER with at least one OAM");

        oam.setPalette(oam.getPalette() == 3 ? 5 : 3);
        oam.setPriority(oam.getPriority() == 2 ? 1 : 2);
        int expectedPalette = oam.getPalette();
        int expectedPriority = oam.getPriority();

        CellBank reloaded = new CellBank(bank.save());
        CellBank.Cell.OAM reloadedOam = firstOam(reloaded);
        assertThat(reloadedOam.getPalette()).isEqualTo(expectedPalette);
        assertThat(reloadedOam.getPriority()).isEqualTo(expectedPriority);
    }

    @Test
    @DisplayName("an edited cell name survives a save/load cycle")
    void editedCellNamePersists()
    {
        CellBank bank = labelledBank();
        Assumptions.assumeTrue(bank != null, "need an NCER whose cells carry names");

        bank.getCell(0).setName("edited");
        CellBank reloaded = new CellBank(bank.save());
        assertThat(reloaded.getCell(0).getName()).isEqualTo("edited");
    }

    @Test
    @DisplayName("a non-NCER input is rejected")
    void rejectsNonNcer()
    {
        byte[] notNcer = new byte[0x30];
        notNcer[0] = 'J'; notNcer[1] = 'U'; notNcer[2] = 'N'; notNcer[3] = 'K';
        assertThatThrownBy(() -> new CellBank(notNcer)).isInstanceOf(RuntimeException.class);
    }

    private static CellBank.Cell.OAM firstOam(CellBank bank)
    {
        for (int i = 0; i < bank.getNumCells(); i++)
            if (bank.getCell(i).getOams().length > 0)
                return bank.getCell(i).getOams()[0];
        return null;
    }

    private static CellBank labelledBank()
    {
        for (byte[] file : ncerFiles)
        {
            CellBank bank = new CellBank(file);
            if (bank.getNumCells() > 0 && !bank.getCell(0).getName().isEmpty())
                return bank;
        }
        return null;
    }
}
