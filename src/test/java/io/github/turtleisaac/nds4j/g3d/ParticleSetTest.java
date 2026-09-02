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

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link ParticleSet} (SPA / Nitro SPL particle archives &mdash; Gen IV move/battle effects). Every
 * SPA file must round-trip byte-for-byte, and its embedded {@code " TPS"} particle textures must decode
 * to correctly-sized sprites with real alpha. The SPA magic is stored byte-reversed (" APS"), so the
 * fixture collector matches that, not {@code "SPA "}.
 */
@DisplayName("SPA (Nitro SPL particle archives)")
public class ParticleSetTest
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
                if (magic(narc.getFile(j)).equals(ParticleSet.MAGIC))
                    found.add(narc.getFile(j));
        }
        return found;
    }

    @Test
    @DisplayName("byte-exact round-trip and particle textures decode across all ROMs")
    void roundTripAndDecode()
    {
        int files = 0, textures = 0, spritesWithAlpha = 0;
        for (String romName : new String[]{"Platinum.nds", "HeartGold.nds", "SoulSilver.nds", "Diamond.nds", "Pearl.nds"})
        {
            List<byte[]> spa;
            try { spa = collect(romName); }
            catch (RuntimeException e) { continue; }
            for (byte[] file : spa)
            {
                ParticleSet set = new ParticleSet(file);
                assertThat(set.save()).as("SPA round-trips byte-for-byte").isEqualTo(file);
                assertThat(new ParticleSet(set.save())).isEqualTo(set);
                assertThat(set.getVersion()).isEqualTo("12_1");

                for (ParticleSet.ParticleTexture t : set.getTextures())
                {
                    BufferedImage img = t.getImage();
                    assertThat(img.getWidth()).isEqualTo(t.getWidth());
                    assertThat(img.getHeight()).isEqualTo(t.getHeight());
                    assertThat(t.getWidth()).isBetween(8, 1024);
                    assertThat(t.getHeight()).isBetween(8, 1024);
                    boolean anyAlpha = false, anyOpaque = false;
                    for (int y = 0; y < img.getHeight() && !(anyAlpha && anyOpaque); y++)
                        for (int x = 0; x < img.getWidth(); x++)
                        {
                            int a = img.getRGB(x, y) >>> 24;
                            if (a > 0) anyAlpha = true;
                            if (a > 200) anyOpaque = true;
                        }
                    if (anyAlpha) spritesWithAlpha++;
                    textures++;
                }
                files++;
            }
        }
        Assumptions.assumeTrue(files > 0, "no SPA files found in any available ROM");
        System.out.printf("SPA: %d files round-tripped, %d particle textures decoded (%d with alpha)%n",
                files, textures, spritesWithAlpha);
        assertThat(files).as("the ROMs pack hundreds of SPA files").isGreaterThan(100);
        assertThat(textures).isGreaterThan(100);
        // particle sprites are alpha masks: the vast majority must carry real alpha
        assertThat(spritesWithAlpha).isGreaterThan(textures * 3 / 4);
    }

    @Test
    @DisplayName("every emitter's fields decode: the walk lands exactly on the texture section")
    void emitterWalkIsByteExact()
    {
        int files = 0, walkedExact = 0, emitters = 0, withAnim = 0, withGravity = 0;
        for (String romName : new String[]{"Platinum.nds", "HeartGold.nds", "SoulSilver.nds", "Diamond.nds", "Pearl.nds"})
        {
            List<byte[]> spa;
            try { spa = collect(romName); }
            catch (RuntimeException e) { continue; }
            for (byte[] file : spa)
            {
                ParticleSet set = new ParticleSet(file);
                files++;
                // There is no per-emitter size field: if any field width were wrong the walk would desync,
                // so landing precisely on the texture section proves the whole emitter struct is correct.
                assertThat(set.getEmitters()).hasSize(set.getEmitterCount());
                if (set.getEmitterBlockEnd() == set.getTextureSectionOffset())
                    walkedExact++;
                for (ParticleSet.Emitter em : set.getEmitters())
                {
                    emitters++;
                    assertThat(em.getShape()).isNotNull();
                    assertThat(em.getParticleAlpha()).isBetween(0, 31);
                    assertThat(em.getEmissionInterval()).isGreaterThanOrEqualTo(0);
                    if (em.getScaleAnim() != null || em.getColorAnim() != null || em.getAlphaAnim() != null) withAnim++;
                    if (em.getGravity() != null) withGravity++;
                }
            }
        }
        Assumptions.assumeTrue(files > 0, "no SPA files found in any available ROM");
        System.out.printf("SPA emitters: %d files, %d/%d walked byte-exact, %d emitters (%d with anim, %d with gravity)%n",
                files, walkedExact, files, emitters, withAnim, withGravity);
        // the emitter struct is fully RE'd, so every archive must walk exactly onto its texture section
        assertThat(walkedExact).as("all emitter blocks decode byte-exactly").isEqualTo(files);
        assertThat(emitters).isGreaterThan(1000);
        assertThat(withAnim).as("many emitters carry over-life curves").isGreaterThan(100);
    }

    @Test
    @DisplayName("a known archive decodes its emitters/textures and sprite sizes")
    void knownArchive()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        Narc narc;
        try { narc = new Narc(rom.getFile(460)); }
        catch (RuntimeException e) { Assumptions.assumeTrue(false, "narc 460 not readable"); return; }
        Assumptions.assumeTrue(magic(narc.getFile(0)).equals(ParticleSet.MAGIC), "expected SPA at narc 460 f0");

        ParticleSet set = new ParticleSet(narc.getFile(0));
        assertThat(set.getTextures()).hasSize(3);
        assertThat(set.getEmitterCount()).isGreaterThan(0);
        // the three sprites in this archive are 16x16, 32x32 and 8x64 (a glow, a spark and a streak)
        assertThat(set.getTextures().get(0).getWidth()).isEqualTo(16);
        assertThat(set.getTextures().get(0).getHeight()).isEqualTo(16);
        assertThat(set.getTextures().get(2).getWidth()).isEqualTo(8);
        assertThat(set.getTextures().get(2).getHeight()).isEqualTo(64);
        assertThat(set.getTextures().get(0).getFormat()).isEqualTo(6); // A5I3 alpha mask
    }
}
