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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link MultiCellBank} (NMCR). The reader/writer is exercised against every RCMN file in a
 * retail ROM, which is the population these were reverse-engineered from. The Pok&eacute;mon Gen IV ROMs
 * don't use NMCR/NMAR, so the fixtures come from <b>White2</b> (Gen V).
 */
@DisplayName("NMCR (MultiCellBank)")
public class MultiCellBankTest
{
    private static List<byte[]> nmcrFiles;

    @BeforeAll
    static void loadFixtures()
    {
        NintendoDsRom rom = TestRoms.require("White2.nds");
        nmcrFiles = NtrFixtures.collect(rom, "RCMN");
        Assumptions.assumeFalse(nmcrFiles.isEmpty(), "no RCMN files found in the test ROM");
    }

    @Test
    @DisplayName("save() reproduces every RCMN file byte-for-byte")
    void writtenNmcrEqualsOriginalBytes()
    {
        // The strongest correctness statement available for a reader/writer: the bytes it emits for a
        // file it just read are identical to the bytes it read. Run over the whole ROM so no multi-cell
        // count, cell-info layout, or attribute quirk goes unexercised.
        for (int i = 0; i < nmcrFiles.size(); i++)
        {
            byte[] original = nmcrFiles.get(i);
            byte[] written = new MultiCellBank(original).save();
            assertThat(written)
                    .as("RCMN file #%d must round-trip byte-for-byte", i)
                    .isEqualTo(original);
        }
    }

    @Test
    @DisplayName("a saved NMCR re-reads equal to the original object")
    void writtenNmcrEqualsOriginalObject()
    {
        for (int i = 0; i < nmcrFiles.size(); i++)
        {
            MultiCellBank original = new MultiCellBank(nmcrFiles.get(i));
            MultiCellBank reloaded = new MultiCellBank(original.save());
            assertThat(reloaded)
                    .as("RCMN file #%d must equal itself after a save/load cycle", i)
                    .isEqualTo(original);
        }
    }

    @Test
    @DisplayName("every multi-cell exposes coherent cell placements")
    void multiCellsExposeCellInfos()
    {
        MultiCellBank bank = new MultiCellBank(nmcrFiles.get(0));
        assertThat(bank.getNumMultiCells()).isGreaterThan(0);
        assertThat(bank.getMultiCells()).hasSize(bank.getNumMultiCells());

        for (MultiCellBank.MultiCell mc : bank.getMultiCells())
        {
            assertThat(mc.getNumCells()).as("a multi-cell composes at least one cell").isGreaterThan(0);
            assertThat(mc.getCellInfos()).hasSize(mc.getNumCells());
            for (MultiCellBank.CellInfo info : mc.getCellInfos())
            {
                assertThat(info.getCellIndex()).as("a placement names a non-negative cell").isGreaterThanOrEqualTo(0);
            }
        }
    }

    @Test
    @DisplayName("editing a placement's cell index survives a save/load cycle")
    void editingCellIndexPersists()
    {
        MultiCellBank bank = new MultiCellBank(nmcrFiles.get(0));
        MultiCellBank.MultiCell mc = bank.getMultiCell(0);
        MultiCellBank.CellInfo info = mc.getCellInfos()[0];

        info.setCellIndex(3);
        info.setX(5);
        info.setY(-9);
        info.setAttr(0x1F);
        mc.setAttribute(7);
        assertThat(info.getCellIndex()).isEqualTo(3);

        MultiCellBank reloaded = new MultiCellBank(bank.save());
        MultiCellBank.MultiCell reloadedMc = reloaded.getMultiCell(0);
        MultiCellBank.CellInfo reloadedInfo = reloadedMc.getCellInfos()[0];
        assertThat(reloadedInfo.getCellIndex()).as("edited cell index must persist").isEqualTo(3);
        assertThat(reloadedInfo.getX()).isEqualTo(5);
        assertThat(reloadedInfo.getY()).isEqualTo(-9);
        assertThat(reloadedInfo.getAttr()).as("edited placement attribute must persist").isEqualTo(0x1F);
        assertThat(reloadedMc.getAttribute()).as("edited multi-cell attribute must persist").isEqualTo(7);
    }

    @Test
    @DisplayName("a non-NMCR input is rejected")
    void rejectsNonNmcr()
    {
        byte[] notNmcr = new byte[0x20];
        notNmcr[0] = 'J'; notNmcr[1] = 'U'; notNmcr[2] = 'N'; notNmcr[3] = 'K';
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new MultiCellBank(notNmcr))
                .isInstanceOf(RuntimeException.class);
    }
}
