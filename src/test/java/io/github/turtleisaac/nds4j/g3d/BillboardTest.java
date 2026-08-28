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
 * Tests camera-facing billboard rendering. A {@code BB}/{@code BBY} sprite must look the same from any
 * orbit angle (it tracks the camera), whereas an ordinary model changes a lot as it turns. Uses Platinum
 * narc 139: {@code hero} (file 61) is a single-quad billboard sprite; {@code manene} (narc 142 f51) is an
 * ordinary multi-part model for contrast.
 */
@DisplayName("billboard rendering (camera-facing sprites)")
public class BillboardTest
{
    private static String magic(byte[] d)
    {
        return d == null || d.length < 4 ? "" : new String(d, 0, 4, StandardCharsets.ISO_8859_1);
    }

    private static int drawnDiff(BufferedImage a, BufferedImage b)
    {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++)
            for (int x = 0; x < a.getWidth(); x++)
                if (a.getRGB(x, y) != b.getRGB(x, y)) n++;
        return n;
    }

    private static int saturatedPixels(BufferedImage img)
    {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++)
            {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 40) n++;
            }
        return n;
    }

    @Test
    @DisplayName("a billboard sprite is camera-facing at every orbit angle")
    void billboardTracksCamera()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        Narc narc;
        try { narc = new Narc(rom.getFile(139)); }
        catch (RuntimeException e) { Assumptions.assumeTrue(false, "narc 139 not readable"); return; }
        Assumptions.assumeTrue(magic(narc.getFile(61)).equals("BMD0"), "expected hero NSBMD at narc 139 f61");

        ModelSet ms = new ModelSet(narc.getFile(61));
        Model hero = ms.getModels().get(0);
        int bb = 0;
        for (int n = 0; n < hero.getNodeCount(); n++)
            if (hero.isBillboardNode(n)) bb++;
        Assumptions.assumeTrue(bb > 0, "hero should have a billboard node");

        BufferedImage y20 = SoftwareRenderer.render(hero, ms.getEmbeddedTextures(), 160, 160, 20, 12);
        BufferedImage y200 = SoftwareRenderer.render(hero, ms.getEmbeddedTextures(), 160, 160, 200, 12);
        assertThat(saturatedPixels(y20)).as("the billboard sprite is drawn").isGreaterThan(200);

        // a single centred billboard projects to the same place regardless of yaw: near-identical images
        assertThat(drawnDiff(y20, y200)).as("a billboard should not change as the camera orbits")
                .isLessThan(y20.getWidth() * y20.getHeight() / 50);
    }

    @Test
    @DisplayName("an ordinary model, by contrast, changes a lot as it turns")
    void ordinaryModelChangesWithOrbit()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        Narc narc;
        try { narc = new Narc(rom.getFile(142)); }
        catch (RuntimeException e) { Assumptions.assumeTrue(false, "narc 142 not readable"); return; }
        Assumptions.assumeTrue(magic(narc.getFile(51)).equals("BMD0"), "expected manene NSBMD");

        ModelSet ms = new ModelSet(narc.getFile(51));
        Model manene = ms.getModels().get(0);
        Assumptions.assumeTrue(!manene.isBillboardNode(0), "manene node 0 is not a billboard");

        BufferedImage y20 = SoftwareRenderer.render(manene, ms.getEmbeddedTextures(), 160, 160, 20, 12);
        BufferedImage y200 = SoftwareRenderer.render(manene, ms.getEmbeddedTextures(), 160, 160, 200, 12);
        assertThat(drawnDiff(y20, y200)).as("an ordinary model turns with the camera")
                .isGreaterThan(y20.getWidth() * y20.getHeight() / 20);
    }
}
