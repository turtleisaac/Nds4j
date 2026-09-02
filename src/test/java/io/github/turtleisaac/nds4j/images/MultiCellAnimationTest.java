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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link MultiCellAnimation} (NMAR). The reader/writer is exercised against every RAMN file in
 * a retail ROM. The Pok&eacute;mon Gen IV ROMs don't use NMCR/NMAR, so the fixtures come from
 * <b>White2</b> (Gen V).
 */
@DisplayName("NMAR (MultiCellAnimation)")
public class MultiCellAnimationTest
{
    private static List<byte[]> nmarFiles;

    @BeforeAll
    static void loadFixtures()
    {
        NintendoDsRom rom = TestRoms.require("White2.nds");
        nmarFiles = NtrFixtures.collect(rom, "RAMN");
        Assumptions.assumeFalse(nmarFiles.isEmpty(), "no RAMN files found in the test ROM");
    }

    @Test
    @DisplayName("save() reproduces every RAMN file byte-for-byte")
    void writtenNmarEqualsOriginalBytes()
    {
        // The strongest correctness statement available for a reader/writer: the bytes it emits for a
        // file it just read are identical to the bytes it read. Run over the whole ROM so no element
        // type, label layout, or padding quirk goes unexercised.
        for (int i = 0; i < nmarFiles.size(); i++)
        {
            byte[] original = nmarFiles.get(i);
            byte[] written = new MultiCellAnimation(original).save();
            assertThat(written)
                    .as("RAMN file #%d must round-trip byte-for-byte", i)
                    .isEqualTo(original);
        }
    }

    @Test
    @DisplayName("a saved NMAR re-reads equal to the original object")
    void writtenNmarEqualsOriginalObject()
    {
        for (int i = 0; i < nmarFiles.size(); i++)
        {
            MultiCellAnimation original = new MultiCellAnimation(nmarFiles.get(i));
            MultiCellAnimation reloaded = new MultiCellAnimation(original.save());
            assertThat(reloaded)
                    .as("RAMN file #%d must equal itself after a save/load cycle", i)
                    .isEqualTo(original);
        }
    }

    @Test
    @DisplayName("every animation exposes coherent frames")
    void animationsExposeFrames()
    {
        MultiCellAnimation animation = new MultiCellAnimation(nmarFiles.get(0));
        assertThat(animation.getNumAnimations()).isGreaterThan(0);
        assertThat(animation.getAnimations()).hasSize(animation.getNumAnimations());

        for (MultiCellAnimation.Animation seq : animation.getAnimations())
        {
            assertThat(seq.getFrames()).as("an animation always has at least one frame").isNotEmpty();
            assertThat(seq.getElement())
                    .as("element type is one of index/SRT/translation")
                    .isBetween(MultiCellAnimation.ELEMENT_INDEX, MultiCellAnimation.ELEMENT_TRANSLATION);
            for (MultiCellAnimation.Animation.Frame frame : seq.getFrames())
            {
                assertThat(frame.getDuration()).as("a frame is shown for a non-negative time").isGreaterThanOrEqualTo(0);
                assertThat(frame.getMultiCellIndex()).as("a frame names a non-negative multi-cell").isGreaterThanOrEqualTo(0);
            }
        }
    }

    @Test
    @DisplayName("editing a frame's multi-cell index survives a save/load cycle")
    void editingMultiCellIndexPersists()
    {
        MultiCellAnimation animation = new MultiCellAnimation(nmarFiles.get(0));
        MultiCellAnimation.Animation.Frame frame = animation.getAnimations()[0].getFrames()[0];

        frame.setMultiCellIndex(1);
        assertThat(frame.getMultiCellIndex()).isEqualTo(1);

        MultiCellAnimation reloaded = new MultiCellAnimation(animation.save());
        assertThat(reloaded.getAnimations()[0].getFrames()[0].getMultiCellIndex())
                .as("an edited multi-cell index must persist through serialisation")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a non-NMAR input is rejected")
    void rejectsNonNmar()
    {
        byte[] notNmar = new byte[0x20];
        notNmar[0] = 'J'; notNmar[1] = 'U'; notNmar[2] = 'N'; notNmar[3] = 'K';
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new MultiCellAnimation(notNmar))
                .isInstanceOf(RuntimeException.class);
    }

    /** @return {fixture index, animation index} of the first animation of the given element type, or null. */
    private static int[] findAnimation(int element)
    {
        for (int i = 0; i < nmarFiles.size(); i++)
        {
            MultiCellAnimation anim = new MultiCellAnimation(nmarFiles.get(i));
            for (int j = 0; j < anim.getNumAnimations(); j++)
                if (anim.getAnimations()[j].getElement() == element)
                    return new int[]{i, j};
        }
        return null;
    }

    @Test
    @DisplayName("an SRT frame's scale/rotation/translation edits survive a save/load cycle")
    void srtTransformAccessorsPersist()
    {
        int[] loc = findAnimation(MultiCellAnimation.ELEMENT_SRT);
        Assumptions.assumeTrue(loc != null, "no SRT-element animation found in the test ROM");

        MultiCellAnimation anim = new MultiCellAnimation(nmarFiles.get(loc[0]));
        MultiCellAnimation.Animation.Frame frame = anim.getAnimations()[loc[1]].getFrames()[0];

        // exact fixed-point values (multiples of 1/4096) so nothing is lost in the 20.12 encoding
        frame.setScale(1.5, 0.5);
        frame.setRotation(0x4000);
        frame.setTranslate(7, -9);
        assertThat(frame.getScaleX()).isEqualTo(1.5);
        assertThat(frame.getScaleY()).isEqualTo(0.5);
        assertThat(frame.getRotation()).isEqualTo(0x4000);
        assertThat(frame.getTranslateX()).isEqualTo(7);
        assertThat(frame.getTranslateY()).isEqualTo(-9);

        MultiCellAnimation.Animation.Frame reloaded =
                new MultiCellAnimation(anim.save()).getAnimations()[loc[1]].getFrames()[0];
        assertThat(reloaded.getScaleX()).isEqualTo(1.5);
        assertThat(reloaded.getScaleY()).isEqualTo(0.5);
        assertThat(reloaded.getRotation()).isEqualTo(0x4000);
        assertThat(reloaded.getTranslateX()).isEqualTo(7);
        assertThat(reloaded.getTranslateY()).isEqualTo(-9);
    }

    @Test
    @DisplayName("a translation frame carries translation but rejects scale/rotation")
    void translationElementAccessors()
    {
        int[] loc = findAnimation(MultiCellAnimation.ELEMENT_TRANSLATION);
        Assumptions.assumeTrue(loc != null, "no translation-element animation found in the test ROM");

        MultiCellAnimation anim = new MultiCellAnimation(nmarFiles.get(loc[0]));
        MultiCellAnimation.Animation.Frame frame = anim.getAnimations()[loc[1]].getFrames()[0];

        frame.setTranslate(5, -3);
        assertThat(frame.getTranslateX()).isEqualTo(5);
        assertThat(frame.getTranslateY()).isEqualTo(-3);
        // a translation element has no scale or rotation
        assertThat(frame.getScaleX()).isEqualTo(1.0);
        assertThat(frame.getRotation()).isZero();
        assertThatThrownBy(() -> frame.setRotation(1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> frame.setScale(2.0, 2.0)).isInstanceOf(IllegalStateException.class);

        assertThat(new MultiCellAnimation(anim.save()).getAnimations()[loc[1]].getFrames()[0].getTranslateX())
                .as("an edited translation must persist through serialisation")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("an index frame carries no transform")
    void indexElementHasNoTransform()
    {
        int[] loc = findAnimation(MultiCellAnimation.ELEMENT_INDEX);
        Assumptions.assumeTrue(loc != null, "no index-element animation found in the test ROM");

        MultiCellAnimation anim = new MultiCellAnimation(nmarFiles.get(loc[0]));
        MultiCellAnimation.Animation.Frame frame = anim.getAnimations()[loc[1]].getFrames()[0];

        assertThat(frame.getTranslateX()).isZero();
        assertThat(frame.getTranslateY()).isZero();
        assertThat(frame.getRotation()).isZero();
        assertThat(frame.getScaleX()).isEqualTo(1.0);
        assertThatThrownBy(() -> frame.setTranslate(1, 1)).isInstanceOf(IllegalStateException.class);
    }
}
