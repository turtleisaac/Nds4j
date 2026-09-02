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
 * Tests for the three remaining Nitro 3D animation formats &mdash; {@link TextureSrtAnimationSet}
 * (NSBTA / {@code BTA0}), {@link TexturePatternAnimationSet} (NSBTP / {@code BTP0}) and
 * {@link VisibilityAnimationSet} (NSBVA / {@code BVA0}). Each must round-trip byte-for-byte over every
 * matching file in a retail ROM, and its tracks must decode and sample.
 */
@DisplayName("NSBTA / NSBTP / NSBVA (texture & visibility animation)")
public class TextureAndVisibilityAnimationTest
{
    private static List<byte[]> bta, btp, bva;

    @BeforeAll
    static void loadFixtures()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        bta = collect(rom, "BTA0");
        btp = collect(rom, "BTP0");
        bva = collect(rom, "BVA0");
        Assumptions.assumeFalse(bta.isEmpty() && btp.isEmpty() && bva.isEmpty(), "no NSBTA/NSBTP/NSBVA files found");
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
            // Not every title wraps these in a NARC -- Animal Crossing: Wild World's one BVA0 is a loose
            // top-level ROM file.
            if (magic(f).equals(want))
            {
                found.add(f);
                continue;
            }
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
    @DisplayName("NSBTA: byte-exact round-trip and every SRT channel samples")
    void nsbtaRoundTripAndDecode()
    {
        Assumptions.assumeFalse(bta.isEmpty(), "no BTA0 files");
        int animations = 0;
        for (byte[] file : bta)
        {
            TextureSrtAnimationSet set = new TextureSrtAnimationSet(file);
            assertThat(set.save()).as("BTA0 round-trips byte-for-byte").isEqualTo(file);
            assertThat(new TextureSrtAnimationSet(set.save())).isEqualTo(set);
            for (TextureSrtAnimationSet.Animation anim : set.getAnimations())
            {
                assertThat(anim.getFrameCount()).isGreaterThan(0);
                int last = anim.getFrameCount() - 1;
                for (TextureSrtAnimationSet.MaterialSrt m : anim.getMaterials())
                    for (float v : new float[]{m.scaleSAt(last), m.scaleTAt(last), m.rotationAt(last),
                            m.transSAt(last), m.transTAt(last)})
                        assertThat(Float.isFinite(v)).as("SRT sample is finite").isTrue();
                animations++;
            }
        }
        assertThat(animations).isGreaterThan(0);
    }

    @Test
    @DisplayName("NSBTP: byte-exact round-trip and pattern keyframes resolve")
    void nsbtpRoundTripAndDecode()
    {
        Assumptions.assumeFalse(btp.isEmpty(), "no BTP0 files");
        int animations = 0;
        for (byte[] file : btp)
        {
            TexturePatternAnimationSet set = new TexturePatternAnimationSet(file);
            assertThat(set.save()).as("BTP0 round-trips byte-for-byte").isEqualTo(file);
            assertThat(new TexturePatternAnimationSet(set.save())).isEqualTo(set);
            for (TexturePatternAnimationSet.Animation anim : set.getAnimations())
            {
                assertThat(anim.getFrameCount()).isGreaterThan(0);
                for (TexturePatternAnimationSet.MaterialPattern mat : anim.getMaterials())
                {
                    assertThat(mat.getKeyframes()).isNotEmpty();
                    TexturePatternAnimationSet.TexturePalette tp = mat.at(anim.getFrameCount() - 1);
                    assertThat(tp).isNotNull();
                    assertThat(tp.getTexture()).isNotNull();
                    assertThat(tp.getPalette()).isNotNull();
                }
                animations++;
            }
        }
        assertThat(animations).isGreaterThan(0);
    }

    @Test
    @DisplayName("NSBVA: byte-exact round-trip and per-node visibility decodes")
    void nsbvaRoundTripAndDecode()
    {
        Assumptions.assumeFalse(bva.isEmpty(), "no BVA0 files");
        int animations = 0;
        for (byte[] file : bva)
        {
            VisibilityAnimationSet set = new VisibilityAnimationSet(file);
            assertThat(set.save()).as("BVA0 round-trips byte-for-byte").isEqualTo(file);
            assertThat(new VisibilityAnimationSet(set.save())).isEqualTo(set);
            for (VisibilityAnimationSet.Animation anim : set.getAnimations())
            {
                assertThat(anim.getFrameCount()).isGreaterThan(0);
                assertThat(anim.getNodeCount()).isGreaterThan(0);
                // every (node, frame) must be addressable without error
                for (int n = 0; n < anim.getNodeCount(); n++)
                    for (int f = 0; f < anim.getFrameCount(); f++)
                        anim.isVisible(n, f);
                animations++;
            }
        }
        assertThat(animations).isGreaterThan(0);
    }

    @Test
    @DisplayName("NSBVA: parses and round-trips across Animal Crossing: Wild World and New Super Mario Bros")
    void nsbvaRoundTripAndDecodeAcrossOtherRoms()
    {
        // Found a fifth writer/reader defect this way, against two non-Pokemon titles: the bit-stream
        // reader always refilled its 32-bit word buffer immediately after draining the stream's very
        // last bit, even when there was no next word -- reading past the end of the buffer whenever a
        // node/frame count came out to an exact multiple of 32 for an animation that happened to be the
        // last thing in the file (Animal Crossing's one BVA0). See parseVis0/Animation's constructor.
        for (String romName : new String[] {"Animal Crossing - Wild World.nds", "New Super Mario Bros.nds"})
        {
            NintendoDsRom rom = TestRoms.require(romName);
            List<byte[]> files = collect(rom, "BVA0");
            Assumptions.assumeFalse(files.isEmpty(), "no BVA0 files found in " + romName);
            for (byte[] file : files)
            {
                VisibilityAnimationSet set = new VisibilityAnimationSet(file);
                assertThat(set.save()).as("BVA0 in %s round-trips byte-for-byte", romName).isEqualTo(file);
                for (VisibilityAnimationSet.Animation anim : set.getAnimations())
                    for (int n = 0; n < anim.getNodeCount(); n++)
                        for (int f = 0; f < anim.getFrameCount(); f++)
                            anim.isVisible(n, f);
            }
        }
    }

    @Test
    @DisplayName("non-matching input is rejected by each reader")
    void rejectsWrongMagic()
    {
        byte[] junk = new byte[0x20];
        junk[0] = 'J'; junk[1] = 'U'; junk[2] = 'N'; junk[3] = 'K';
        assertThatThrownBy(() -> new TextureSrtAnimationSet(junk)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new TexturePatternAnimationSet(junk)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new VisibilityAnimationSet(junk)).isInstanceOf(RuntimeException.class);
    }
}
