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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link MaterialColorAnimationSet} (NSBMA / {@code BMA0}, material-color animation). Like the
 * other {@code NSB*} formats it must round-trip byte-for-byte over every matching file in the retail
 * ROMs, and its five color tracks per material must decode and sample &mdash; validated both broadly
 * (all files) and on a known effect ({@code demo_kusari}, whose alpha fades in from 0 to full).
 */
@DisplayName("NSBMA (material-color animation)")
public class MaterialColorAnimationTest
{
    private static String magic(byte[] d)
    {
        return d == null || d.length < 4 ? "" : new String(d, 0, 4, StandardCharsets.ISO_8859_1);
    }

    private static List<byte[]> collect(String romName)
    {
        NintendoDsRom rom = TestRoms.require(romName);
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
                if (magic(narc.getFile(j)).equals("BMA0"))
                    found.add(narc.getFile(j));
        }
        return found;
    }

    @Test
    @DisplayName("byte-exact round-trip and every channel samples across all ROMs")
    void roundTripAndDecode()
    {
        int total = 0, animations = 0;
        for (String romName : new String[]{"Platinum.nds", "HeartGold.nds", "SoulSilver.nds", "Diamond.nds", "Pearl.nds"})
        {
            List<byte[]> files;
            try { files = collect(romName); }
            catch (RuntimeException e) { continue; } // ROM not present in this environment
            for (byte[] file : files)
            {
                MaterialColorAnimationSet set = new MaterialColorAnimationSet(file);
                assertThat(set.save()).as("BMA0 round-trips byte-for-byte").isEqualTo(file);
                assertThat(new MaterialColorAnimationSet(set.save())).isEqualTo(set);
                for (MaterialColorAnimationSet.Animation anim : set.getAnimations())
                {
                    assertThat(anim.getFrameCount()).isGreaterThan(0);
                    int last = anim.getFrameCount() - 1;
                    for (MaterialColorAnimationSet.MaterialColor m : anim.getMaterials())
                    {
                        // each color track yields an in-range 24-bit RGB at both ends
                        for (MaterialColorAnimationSet.ColorChannel ch : new MaterialColorAnimationSet.ColorChannel[]{
                                m.getDiffuse(), m.getAmbient(), m.getSpecular(), m.getEmission()})
                        {
                            assertThat(ch.rgbAt(0) & ~0xFFFFFF).isZero();
                            assertThat(ch.rgbAt(last) & ~0xFFFFFF).isZero();
                        }
                        assertThat(m.getAlpha().at(0)).isBetween(0, 31);
                        assertThat(m.getAlpha().at(last)).isBetween(0, 31);
                    }
                    animations++;
                }
                total++;
            }
        }
        Assumptions.assumeTrue(total > 0, "no BMA0 files found in any available ROM");
        assertThat(animations).isGreaterThan(0);
    }

    @Test
    @DisplayName("demo_kusari: constant colors + a fading alpha ramp decode correctly")
    void demoKusariAlphaFade()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        Narc narc;
        try { narc = new Narc(rom.getFile(139)); }
        catch (RuntimeException e) { Assumptions.assumeTrue(false, "narc 139 not readable"); return; }
        Assumptions.assumeTrue(magic(narc.getFile(5)).equals("BMA0"), "expected demo_kusari NSBMA at narc 139 f5");

        MaterialColorAnimationSet set = new MaterialColorAnimationSet(narc.getFile(5));
        MaterialColorAnimationSet.Animation anim = set.getAnimations().get(0);
        assertThat(anim.getName()).isEqualTo("demo_kusari");
        assertThat(anim.getFrameCount()).isEqualTo(201);

        MaterialColorAnimationSet.MaterialColor mat = anim.getMaterials().get(0);
        // the four colors are constant in this clip; alpha is an animated pulse: it fades in from 0 to
        // full (31), holds, then fades back out to 0 — a glowing chain.
        assertThat(mat.getDiffuse().isConstant()).isTrue();
        assertThat(mat.getAlpha().isConstant()).isFalse();
        assertThat(mat.getAlpha().at(0)).isEqualTo(0);
        assertThat(mat.getAlpha().at(anim.getFrameCount() - 1)).isEqualTo(0);
        int peak = 0;
        for (int f = 0; f < anim.getFrameCount(); f++)
            peak = Math.max(peak, mat.getAlpha().at(f));
        assertThat(peak).as("the pulse should reach full 5-bit alpha").isEqualTo(31);
        // rises early, so the first half's peak is full while the endpoints are dark
        assertThat(mat.getAlpha().at(anim.getFrameCount() / 2)).isGreaterThan(0);
    }
}
