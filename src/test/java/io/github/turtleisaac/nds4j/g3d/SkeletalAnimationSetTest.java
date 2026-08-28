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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link SkeletalAnimationSet} (NSBCA / {@code BCA0}) &mdash; byte-exact container round-trip
 * over every animation in a retail ROM, plus that its tracks decode and pose a model's skeleton.
 */
@DisplayName("NSBCA (skeletal animation)")
public class SkeletalAnimationSetTest
{
    private static NintendoDsRom rom;
    private static List<byte[]> nsbcaFiles;

    @BeforeAll
    static void loadFixtures()
    {
        rom = TestRoms.require("Platinum.nds");
        nsbcaFiles = collect(rom, "BCA0");
        Assumptions.assumeFalse(nsbcaFiles.isEmpty(), "no BCA0 files found in the test ROM");
    }

    private static String magic(byte[] d)
    {
        return d == null || d.length < 4 ? "" : new String(d, 0, 4, StandardCharsets.ISO_8859_1);
    }

    private static List<byte[]> collect(NintendoDsRom rom, String want)
    {
        List<byte[]> found = new ArrayList<>();
        for (int i = 0; i < rom.getNumFiles(); i++)
        {
            byte[] f = rom.getFile(i);
            if (!magic(f).equals("NARC"))
                continue;
            Narc narc;
            try { narc = new Narc(f); }
            catch (RuntimeException e) { continue; }
            for (int j = 0; j < narc.getNumFiles(); j++)
                if (magic(narc.getFile(j)).equals(want))
                    found.add(narc.getFile(j));
        }
        return found;
    }

    @Test
    @DisplayName("save() reproduces every BCA0 file byte-for-byte")
    void writtenAnimationSetEqualsOriginalBytes()
    {
        for (int i = 0; i < nsbcaFiles.size(); i++)
        {
            byte[] original = nsbcaFiles.get(i);
            byte[] written = new SkeletalAnimationSet(original).save();
            assertThat(written).as("BCA0 file #%d must round-trip byte-for-byte", i).isEqualTo(original);
        }
    }

    @Test
    @DisplayName("a saved NSBCA re-reads equal to the original object")
    void writtenAnimationSetEqualsOriginalObject()
    {
        for (byte[] file : nsbcaFiles)
        {
            SkeletalAnimationSet original = new SkeletalAnimationSet(file);
            SkeletalAnimationSet reloaded = new SkeletalAnimationSet(original.save());
            assertThat(reloaded).isEqualTo(original);
            assertThat(reloaded.hashCode()).isEqualTo(original.hashCode());
        }
    }

    @Test
    @DisplayName("every animation decodes and samples without error")
    void tracksDecodeAndSample()
    {
        int animations = 0;
        for (byte[] file : nsbcaFiles)
        {
            for (SkeletalAnimationSet.Animation anim : new SkeletalAnimationSet(file).getAnimations())
            {
                assertThat(anim.getFrameCount()).isGreaterThan(0);
                int last = anim.getFrameCount() - 1;
                for (SkeletalAnimationSet.NodeAnim node : anim.getNodes())
                {
                    // sampling the first and last frame must produce finite SRT (or null for base tracks)
                    for (int frame : new int[]{0, last})
                    {
                        assertFinite(node.translationAt(frame), 3);
                        assertFinite(node.scaleAt(frame), 3);
                        assertFinite(node.rotationAt(frame), 9);
                    }
                }
                animations++;
            }
        }
        assertThat(animations).as("the ROM should contain animations").isGreaterThan(0);
    }

    private static void assertFinite(double[] v, int len)
    {
        if (v == null)
            return; // base track: keep bind pose
        assertThat(v.length).isEqualTo(len);
        for (double d : v)
            assertThat(Double.isFinite(d)).as("SRT value is finite").isTrue();
    }

    @Test
    @DisplayName("an animation poses a model's skeleton, moving its geometry")
    void animationPosesModel()
    {
        // Find a NARC that pairs a multi-node model with an animation for the same skeleton, then confirm
        // pose() returns per-mesh geometry the right shape and that a mid-animation frame actually moves
        // vertices relative to the bind pose (a static or ignored animation would leave them identical).
        boolean checked = false;
        for (int i = 0; i < rom.getNumFiles() && !checked; i++)
        {
            byte[] f = rom.getFile(i);
            if (!magic(f).equals("NARC"))
                continue;
            Narc narc;
            try { narc = new Narc(f); }
            catch (RuntimeException e) { continue; }

            List<Model> models = new ArrayList<>();
            List<SkeletalAnimationSet.Animation> anims = new ArrayList<>();
            for (int j = 0; j < narc.getNumFiles(); j++)
            {
                byte[] g = narc.getFile(j);
                if (magic(g).equals("BMD0"))
                    try { models.addAll(new ModelSet(g).getModels()); } catch (RuntimeException ignored) {}
                else if (magic(g).equals("BCA0"))
                    try { anims.addAll(new SkeletalAnimationSet(g).getAnimations()); } catch (RuntimeException ignored) {}
            }

            for (Model model : models)
            {
                if (model.getNodeCount() < 2 || model.getVertexCount() == 0)
                    continue;
                for (SkeletalAnimationSet.Animation anim : anims)
                {
                    if (anim.getNodes().size() != model.getNodeCount() || anim.getFrameCount() < 2)
                        continue;

                    List<float[]> bind = model.pose(anim, 0);
                    List<float[]> mid = model.pose(anim, anim.getFrameCount() / 2);
                    assertThat(bind).hasSameSizeAs(model.getMeshes());
                    for (int m = 0; m < bind.size(); m++)
                        assertThat(bind.get(m).length).isEqualTo(model.getMeshes().get(m).getPositions().length);

                    boolean moved = false;
                    for (int m = 0; m < bind.size() && !moved; m++)
                        for (int k = 0; k < bind.get(m).length; k++)
                            if (Math.abs(bind.get(m)[k] - mid.get(m)[k]) > 1e-4f) { moved = true; break; }

                    assertThat(moved).as("a mid-animation frame should move the model's geometry").isTrue();
                    checked = true;
                    break;
                }
                if (checked)
                    break;
            }
        }
        Assumptions.assumeTrue(checked, "need a NARC pairing a multi-node model with a matching animation");
    }

    @Test
    @DisplayName("a non-NSBCA input is rejected")
    void rejectsNonAnimationSet()
    {
        byte[] junk = new byte[0x20];
        junk[0] = 'J'; junk[1] = 'U'; junk[2] = 'N'; junk[3] = 'K';
        assertThatThrownBy(() -> new SkeletalAnimationSet(junk)).isInstanceOf(RuntimeException.class);
    }
}
