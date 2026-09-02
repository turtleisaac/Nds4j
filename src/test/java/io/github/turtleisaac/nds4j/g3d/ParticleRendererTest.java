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
 * Tests {@link ParticleRenderer} &mdash; the headless previewer that plays a decoded {@link ParticleSet}
 * (Gen IV SPL move effect). It must produce visible, deterministic frames from a real archive's emitters.
 */
@DisplayName("ParticleRenderer (SPL move-effect previewer)")
public class ParticleRendererTest
{
    private static String magic(byte[] d)
    {
        return d == null || d.length < 4 ? "" : new String(d, 0, 4, StandardCharsets.ISO_8859_1);
    }

    // Finds an SPA archive that actually emits moving particles, so there is something to preview.
    private static ParticleSet findLivelyArchive(String romName)
    {
        NintendoDsRom rom = TestRoms.require(romName);
        ParticleSet best = null;
        double bestScore = 0;
        for (int i = 0; i < rom.getNumFiles(); i++)
        {
            if (!magic(rom.getFile(i)).equals("NARC")) continue;
            Narc narc;
            try { narc = new Narc(rom.getFile(i)); }
            catch (RuntimeException e) { continue; }
            for (int j = 0; j < narc.getNumFiles(); j++)
            {
                if (!magic(narc.getFile(j)).equals(ParticleSet.MAGIC)) continue;
                ParticleSet set = new ParticleSet(narc.getFile(j));
                if (set.getTextures().isEmpty()) continue;
                double score = 0;
                for (ParticleSet.Emitter em : set.getEmitters())
                    score += (em.getParticlePosVeloMag() + em.getParticleAxisVeloMag())
                            * Math.max(1, em.getParticleLifetime()) * Math.max(0.5, em.getEmissionVolume());
                if (score > bestScore) { bestScore = score; best = set; }
            }
        }
        return best;
    }

    private static long nonBlackPixels(BufferedImage img)
    {
        long n = 0;
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++)
                if ((img.getRGB(x, y) & 0xFFFFFF) > 0x101820) n++;
        return n;
    }

    @Test
    @DisplayName("plays a real move effect into visible, deterministic frames")
    void playsMoveEffect()
    {
        ParticleSet set = null;
        for (String romName : new String[]{"Platinum.nds", "HeartGold.nds", "SoulSilver.nds", "Diamond.nds", "Pearl.nds"})
        {
            try { set = findLivelyArchive(romName); }
            catch (RuntimeException e) { continue; }
            if (set != null) break;
        }
        Assumptions.assumeTrue(set != null, "no lively SPA archive found in any available ROM");

        ParticleRenderer renderer = new ParticleRenderer(192, 192).seed(7);
        List<BufferedImage> frames = renderer.render(set, 48);
        assertThat(frames).hasSize(48);
        for (BufferedImage f : frames)
        {
            assertThat(f.getWidth()).isEqualTo(192);
            assertThat(f.getHeight()).isEqualTo(192);
        }

        // At least one mid-clip frame must have lit a meaningful number of pixels (the effect actually plays).
        long litMax = 0;
        for (BufferedImage f : frames) litMax = Math.max(litMax, nonBlackPixels(f));
        assertThat(litMax).as("the previewer lights up particles").isGreaterThan(50);

        // Determinism: same seed reproduces the exact frames.
        List<BufferedImage> again = new ParticleRenderer(192, 192).seed(7).render(set, 48);
        for (int i = 0; i < frames.size(); i++)
        {
            boolean identical = true;
            for (int y = 0; y < 192 && identical; y++)
                for (int x = 0; x < 192; x++)
                    if (frames.get(i).getRGB(x, y) != again.get(i).getRGB(x, y)) { identical = false; break; }
            assertThat(identical).as("frame %d is deterministic for a fixed seed", i).isTrue();
        }
    }
}
