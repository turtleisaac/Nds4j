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

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SoftwareRenderer}: the headless preview must actually draw a textured model &mdash;
 * produce an image with saturated, model-colored pixels over the grey background.
 */
@DisplayName("software renderer (headless preview)")
public class SoftwareRendererTest
{
    private static List<byte[]> nsbmdFiles;

    @BeforeAll
    static void loadFixtures()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        nsbmdFiles = new ArrayList<>();
        for (int i = 0; i < rom.getNumFiles(); i++)
        {
            byte[] f = rom.getFile(i);
            if (!magic(f).equals("NARC"))
                continue;
            Narc narc;
            try { narc = new Narc(f); }
            catch (RuntimeException e) { continue; }
            for (int j = 0; j < narc.getNumFiles(); j++)
                if (magic(narc.getFile(j)).equals("BMD0"))
                    nsbmdFiles.add(narc.getFile(j));
        }
        Assumptions.assumeFalse(nsbmdFiles.isEmpty(), "no BMD0 files found in the test ROM");
    }

    private static String magic(byte[] d)
    {
        return d == null || d.length < 4 ? "" : new String(d, 0, 4, StandardCharsets.ISO_8859_1);
    }

    @Test
    @DisplayName("renders a textured model with visible, saturated pixels")
    void rendersTexturedModel()
    {
        ModelSet ms = null;
        Model model = null;
        for (byte[] file : nsbmdFiles)
        {
            ModelSet candidate = new ModelSet(file);
            if (!candidate.hasEmbeddedTextures())
                continue;
            for (Model m : candidate.getModels())
                if (m.getVertexCount() > 500 && m.getMeshes().stream()
                        .anyMatch(me -> me.getMaterial() != null && me.getMaterial().getTextureName() != null))
                {
                    ms = candidate;
                    model = m;
                    break;
                }
            if (model != null)
                break;
        }
        Assumptions.assumeTrue(model != null, "need a textured model");

        BufferedImage img = SoftwareRenderer.render(model, ms.getEmbeddedTextures(), 256, 256, 200, 12);
        assertThat(img.getWidth()).isEqualTo(256);
        assertThat(img.getHeight()).isEqualTo(256);

        // the background is near-grey (saturation ~6); a drawn, textured model adds many saturated pixels
        int saturated = 0;
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++)
            {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                int sat = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                if (sat > 40)
                    saturated++;
            }
        assertThat(saturated).as("the rendered model should cover a meaningful, colorful area")
                .isGreaterThan(1000);
    }

    @Test
    @DisplayName("renders a posed animation frame without error")
    void rendersPosedFrame()
    {
        // manene (Platinum narc 142): model #51, animation file #53. Render a mid-animation frame.
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        Narc narc;
        try { narc = new Narc(rom.getFile(142)); }
        catch (RuntimeException e) { Assumptions.assumeTrue(false, "narc 142 not readable"); return; }
        Assumptions.assumeTrue(magic(narc.getFile(51)).equals("BMD0") && magic(narc.getFile(53)).equals("BCA0"),
                "expected manene model + animation layout");

        ModelSet ms = new ModelSet(narc.getFile(51));
        Model model = ms.getModels().get(0);
        SkeletalAnimationSet.Animation anim = new SkeletalAnimationSet(narc.getFile(53)).getAnimations().get(0);
        List<float[]> posed = model.pose(anim, anim.getFrameCount() / 2);

        BufferedImage img = SoftwareRenderer.render(model, posed, ms.getEmbeddedTextures(), 256, 256, 200, 10);
        assertThat(img.getWidth()).isEqualTo(256);
        int drawn = 0;
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++)
            {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 40)
                    drawn++;
            }
        assertThat(drawn).as("the posed model should render visible pixels").isGreaterThan(1000);
    }
}
