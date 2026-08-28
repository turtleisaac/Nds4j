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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the viewer's headless render path ({@link ModelViewer#renderView}): it must composite a drawn 3D
 * viewport (saturated model pixels), an inspector sidebar (a solid panel region), and change what it
 * draws as the animation frame advances. The interactive Swing shell needs a display and is exercised
 * only through this shared function.
 */
@DisplayName("model viewer (headless composited HUD)")
public class ModelViewerTest
{
    private static String magic(byte[] d)
    {
        return d == null || d.length < 4 ? "" : new String(d, 0, 4, StandardCharsets.ISO_8859_1);
    }

    @Test
    @DisplayName("renderView composites a viewport, a sidebar and per-frame motion")
    void compositesAndAnimates()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        Narc narc;
        try { narc = new Narc(rom.getFile(142)); }
        catch (RuntimeException e) { Assumptions.assumeTrue(false, "narc 142 not readable"); return; }
        Assumptions.assumeTrue(magic(narc.getFile(51)).equals("BMD0") && magic(narc.getFile(53)).equals("BCA0"),
                "expected manene model + animation layout");

        ModelSet ms = new ModelSet(narc.getFile(51));
        Model model = ms.getModels().get(0);
        NitroAnimation anim = NitroAnimation.ofSkeletal(new SkeletalAnimationSet(narc.getFile(53)).getAnimations().get(0));

        int w = 640, h = 400;
        BufferedImage a = ModelViewer.renderView(model, ms.getEmbeddedTextures(), anim, "walk", 0, 205, 14, w, h);
        BufferedImage b = ModelViewer.renderView(model, ms.getEmbeddedTextures(), anim, "walk", anim.getFrameCount() / 2, 205, 14, w, h);
        assertThat(a.getWidth()).isEqualTo(w);
        assertThat(a.getHeight()).isEqualTo(h);

        // viewport (left) has saturated model pixels
        int viewportW = w - Math.max(280, w * 36 / 100);
        int saturated = 0;
        for (int y = 30; y < h - 80; y++)
            for (int x = 0; x < viewportW; x++)
            {
                int rgb = a.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, gg = (rgb >> 8) & 0xFF, bl = rgb & 0xFF;
                if (Math.max(r, Math.max(gg, bl)) - Math.min(r, Math.min(gg, bl)) > 40)
                    saturated++;
            }
        assertThat(saturated).as("the viewport should draw a textured model").isGreaterThan(500);

        // sidebar (right) is a solid dark panel: many pixels share the panel colour
        int panelPixels = 0;
        for (int y = 40; y < h - 40; y++)
            for (int x = viewportW + 20; x < w - 5; x++)
            {
                int rgb = b.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, gg = (rgb >> 8) & 0xFF, bl = rgb & 0xFF;
                if (Math.abs(r - 33) < 6 && Math.abs(gg - 36) < 6 && Math.abs(bl - 44) < 6)
                    panelPixels++;
            }
        assertThat(panelPixels).as("the sidebar should render as a solid inspector panel").isGreaterThan(1000);

        // advancing the frame changes the viewport
        int diff = 0;
        for (int y = 30; y < h - 80; y++)
            for (int x = 0; x < viewportW; x++)
                if (a.getRGB(x, y) != b.getRGB(x, y))
                    diff++;
        assertThat(diff).as("scrubbing the frame should re-pose the model").isGreaterThan(200);
    }
}
