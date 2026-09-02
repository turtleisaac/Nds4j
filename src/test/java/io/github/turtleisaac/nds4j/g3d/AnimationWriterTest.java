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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the animation <b>writers</b> &mdash; the native, byte-for-byte replacements for the animation half
 * of Nintendo's {@code g3dcvtr}. Each writer rebuilds an NSB* animation from its parsed structure; the bar
 * is that re-encoding every retail animation reproduces the original bytes exactly (retail animations are
 * themselves g3dcvtr output, so this validates against the real converter). Requires a retail ROM
 * (skipped otherwise): {@code -Drom.dir=<dir>}.
 */
@DisplayName("Animation writers (.nsb* re-encode, byte-identical to g3dcvtr)")
public class AnimationWriterTest
{
    private static String magic(byte[] f) { return f.length < 4 ? "" : new String(f, 0, 4, StandardCharsets.US_ASCII); }

    // decode + re-encode every file of the given magic across the ROM; assert byte-exact and count them
    private int roundTrip(NintendoDsRom rom, String blockMagic, Function<byte[], byte[]> reencode)
    {
        int checked = 0;
        for (int i = 0; i < rom.getNumFiles(); i++)
        {
            byte[] f = rom.getFile(i);
            // Not every title wraps these in a NARC -- e.g. 4 of Animal Crossing: Wild World's 5 BMA0
            // files are loose top-level ROM files.
            if (magic(f).equals(blockMagic))
            {
                assertThat(reencode.apply(f))
                        .as("%s top-level file %d re-encodes byte-for-byte", blockMagic, i)
                        .isEqualTo(f);
                checked++;
                continue;
            }
            if (!magic(f).equals("NARC")) continue;
            Narc narc;
            try { narc = new Narc(f); }
            catch (RuntimeException e) { continue; }
            for (int j = 0; j < narc.getNumFiles(); j++)
            {
                byte[] bf = narc.getFile(j);
                if (!magic(bf).equals(blockMagic)) continue;
                assertThat(reencode.apply(bf))
                        .as("%s narc %d file %d re-encodes byte-for-byte", blockMagic, i, j)
                        .isEqualTo(bf);
                checked++;
            }
        }
        return checked;
    }

    @Test
    @DisplayName("every retail NSBVA (visibility) re-encodes byte-for-byte")
    void visibilityRoundTrip()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        int n = roundTrip(rom, "BVA0", bf -> new VisibilityAnimationSet(bf).encode());
        Assumptions.assumeTrue(n > 0, "no NSBVA files found");
    }

    @Test
    @DisplayName("every retail NSBTP (texture pattern) re-encodes byte-for-byte")
    void texturePatternRoundTrip()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        int n = roundTrip(rom, "BTP0", bf -> new TexturePatternAnimationSet(bf).encode());
        Assumptions.assumeTrue(n > 0, "no NSBTP files found");
    }

    @Test
    @DisplayName("every retail NSBTA (texture SRT) re-encodes byte-for-byte")
    void textureSrtRoundTrip()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        int n = roundTrip(rom, "BTA0", bf -> new TextureSrtAnimationSet(bf).encode());
        Assumptions.assumeTrue(n > 0, "no NSBTA files found");
    }

    @Test
    @DisplayName("every retail NSBMA (material color) re-encodes byte-for-byte")
    void materialColorRoundTrip()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        int n = roundTrip(rom, "BMA0", bf -> new MaterialColorAnimationSet(bf).encode());
        Assumptions.assumeTrue(n > 0, "no NSBMA files found");
    }

    @Test
    @DisplayName("NSBMA parses without throwing and save() round-trips across Animal Crossing and Phantom Hourglass")
    void materialColorParsesAndSavesAcrossOtherRoms()
    {
        // Found a sixth reader defect this way, against two non-Pokemon titles: some of their NSBMA
        // channels carry a flags byte (0x40/0x41) this reverse-engineered format doesn't recognize --
        // neither the documented 0x20 constant bit nor a frame count consistent with a plain per-frame
        // array (the declared count read past the end of the block). The constructor now clamps such a
        // channel's decoded keys to what the buffer holds instead of crashing (see
        // MaterialColorAnimationSet.clampFrameCount); save() -- block-verbatim per G3dFile -- still
        // reproduces the file exactly regardless, since the underlying bytes are never touched. encode()
        // (the byte-exact re-encode path) is a known gap for these particular files: it can't yet
        // reproduce the unrecognized channels' true layout, only save() is guaranteed here.
        for (String romName : new String[] {"Animal Crossing - Wild World.nds",
                "Legend of Zelda, The - Phantom Hourglass.nds"})
        {
            NintendoDsRom rom = TestRoms.require(romName);
            int n = roundTrip(rom, "BMA0", bf -> new MaterialColorAnimationSet(bf).save());
            Assumptions.assumeTrue(n > 0, "no NSBMA files found in " + romName);
        }
    }

    @Test
    @DisplayName("every retail NSBCA (skeletal) re-encodes byte-for-byte")
    void skeletalRoundTrip()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        int n = roundTrip(rom, "BCA0", bf -> new SkeletalAnimationSet(bf).encode());
        Assumptions.assumeTrue(n > 0, "no NSBCA files found");
    }

    @Test
    @DisplayName("an authored visibility animation round-trips through decode")
    void authorVisibility()
    {
        boolean[][] visible = {{true, false, true}, {false, false, true}}; // [node][frame]
        VisibilityAnimationSet.Animation anim = new VisibilityAnimationSet.Animation("blink", visible);
        VisibilityAnimationSet set = VisibilityAnimationSet.author(java.util.List.of(anim), 1);

        VisibilityAnimationSet reread = new VisibilityAnimationSet(set.encode());
        assertThat(reread.getAnimations()).hasSize(1);
        VisibilityAnimationSet.Animation a = reread.getAnimations().get(0);
        assertThat(a.getName()).isEqualTo("blink");
        assertThat(a.getFrameCount()).isEqualTo(3);
        assertThat(a.getNodeCount()).isEqualTo(2);
        assertThat(a.isVisible(0, 1)).isFalse();
        assertThat(a.isVisible(1, 2)).isTrue();
    }
}
