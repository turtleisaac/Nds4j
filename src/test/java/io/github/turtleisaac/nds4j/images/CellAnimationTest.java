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
 * Tests for {@link CellAnimation} (NANR). The reader/writer is exercised against every RNAN file in
 * a retail ROM, which is the population these were reverse-engineered from.
 */
@DisplayName("NANR (CellAnimation)")
public class CellAnimationTest
{
    private static List<byte[]> nanrFiles;

    @BeforeAll
    static void loadFixtures()
    {
        NintendoDsRom rom = TestRoms.require("HeartGold.nds");
        nanrFiles = NtrFixtures.collect(rom, "RNAN");
        Assumptions.assumeFalse(nanrFiles.isEmpty(), "no RNAN files found in the test ROM");
    }

    @Test
    @DisplayName("save() reproduces every RNAN file byte-for-byte")
    void writtenNanrEqualsOriginalBytes()
    {
        // The strongest correctness statement available for a reader/writer: the bytes it emits for a
        // file it just read are identical to the bytes it read. Run over the whole ROM so no element
        // type, label layout, or padding quirk goes unexercised.
        for (int i = 0; i < nanrFiles.size(); i++)
        {
            byte[] original = nanrFiles.get(i);
            byte[] written = new CellAnimation(original).save();
            assertThat(written)
                    .as("RNAN file #%d must round-trip byte-for-byte", i)
                    .isEqualTo(original);
        }
    }

    @Test
    @DisplayName("a saved NANR re-reads equal to the original object")
    void writtenNanrEqualsOriginalObject()
    {
        for (int i = 0; i < nanrFiles.size(); i++)
        {
            CellAnimation original = new CellAnimation(nanrFiles.get(i));
            CellAnimation reloaded = new CellAnimation(original.save());
            assertThat(reloaded)
                    .as("RNAN file #%d must equal itself after a save/load cycle", i)
                    .isEqualTo(original);
        }
    }

    @Test
    @DisplayName("every animation exposes coherent frames")
    void animationsExposeFrames()
    {
        CellAnimation animation = new CellAnimation(nanrFiles.get(0));
        assertThat(animation.getNumAnimations()).isGreaterThan(0);
        assertThat(animation.getAnimations()).hasSize(animation.getNumAnimations());

        for (CellAnimation.Animation seq : animation.getAnimations())
        {
            assertThat(seq.getFrames()).as("an animation always has at least one frame").isNotEmpty();
            assertThat(seq.getElement())
                    .as("element type is one of index/SRT/translation")
                    .isBetween(CellAnimation.ELEMENT_INDEX, CellAnimation.ELEMENT_TRANSLATION);
            for (CellAnimation.Animation.Frame frame : seq.getFrames())
            {
                assertThat(frame.getDuration()).as("a frame is shown for a non-negative time").isGreaterThanOrEqualTo(0);
                assertThat(frame.getCellIndex()).as("a frame names a non-negative cell").isGreaterThanOrEqualTo(0);
            }
        }
    }

    @Test
    @DisplayName("editing a frame's cell index survives a save/load cycle")
    void editingCellIndexPersists()
    {
        CellAnimation animation = new CellAnimation(nanrFiles.get(0));
        CellAnimation.Animation.Frame frame = animation.getAnimations()[0].getFrames()[0];

        frame.setCellIndex(7);
        assertThat(frame.getCellIndex()).isEqualTo(7);

        CellAnimation reloaded = new CellAnimation(animation.save());
        assertThat(reloaded.getAnimations()[0].getFrames()[0].getCellIndex())
                .as("an edited cell index must persist through serialisation")
                .isEqualTo(7);
    }

    @Test
    @DisplayName("a non-NANR input is rejected")
    void rejectsNonNanr()
    {
        byte[] notNanr = new byte[0x20];
        notNanr[0] = 'J'; notNanr[1] = 'U'; notNanr[2] = 'N'; notNanr[3] = 'K';
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CellAnimation(notNanr))
                .isInstanceOf(RuntimeException.class);
    }
}
