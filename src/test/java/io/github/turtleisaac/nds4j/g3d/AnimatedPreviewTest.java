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

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the animated preview: {@link NitroAnimation} composing the four decoded animation tracks
 * (NSBCA/NSBTA/NSBTP/NSBVA), {@link SoftwareRenderer} applying a sampled {@link NitroAnimation.Frame},
 * and {@link AnimatedGif} writing a valid looping GIF. Each track is validated by <em>motion</em>: the
 * animation must actually change what is drawn between two frames.
 * <p>
 * Fixtures come from Platinum's effect NARCs (verified layout): narc 142 file 51 = manene model + file
 * 53 = NSBCA; narc 139 file 0 = demo_ana_d + file 1 = NSBTA water scroll, file 13 = gingaboss + file 14
 * = NSBTP sprite flip-book, file 79 = kurotama + file 81 = NSBVA.
 */
@DisplayName("animated preview (NitroAnimation + SoftwareRenderer + AnimatedGif)")
public class AnimatedPreviewTest
{
    private static String magic(byte[] d)
    {
        return d == null || d.length < 4 ? "" : new String(d, 0, 4, StandardCharsets.ISO_8859_1);
    }

    private static Narc narc(int index)
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        try { return new Narc(rom.getFile(index)); }
        catch (RuntimeException e) { Assumptions.assumeTrue(false, "narc " + index + " not readable"); return null; }
    }

    // Count pixels that differ meaningfully between two renders (motion, texture scroll, visibility, ...).
    private static int differingPixels(BufferedImage a, BufferedImage b)
    {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++)
            for (int x = 0; x < a.getWidth(); x++)
            {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                int dr = Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF));
                int dg = Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF));
                int db = Math.abs((p & 0xFF) - (q & 0xFF));
                if (dr + dg + db > 24)
                    n++;
            }
        return n;
    }

    @Test
    @DisplayName("NSBCA skeletal pose moves the model between frames")
    void skeletalAnimates()
    {
        Narc narc = narc(142);
        Assumptions.assumeTrue(magic(narc.getFile(51)).equals("BMD0") && magic(narc.getFile(53)).equals("BCA0"),
                "expected manene model + animation layout");
        ModelSet ms = new ModelSet(narc.getFile(51));
        Model model = ms.getModels().get(0);
        NitroAnimation anim = NitroAnimation.ofSkeletal(new SkeletalAnimationSet(narc.getFile(53)).getAnimations().get(0));
        assertThat(anim.hasSkeletal()).isTrue();
        assertThat(anim.getFrameCount()).isGreaterThan(1);

        BufferedImage f0 = SoftwareRenderer.render(model, anim.sample(model, 0), ms.getEmbeddedTextures(), 192, 192, 205, 12);
        BufferedImage f1 = SoftwareRenderer.render(model, anim.sample(model, anim.getFrameCount() / 2), ms.getEmbeddedTextures(), 192, 192, 205, 12);
        assertThat(differingPixels(f0, f1)).as("the walk cycle should move limbs between frames").isGreaterThan(200);
    }

    @Test
    @DisplayName("NSBTA texture-SRT scrolls a material's UVs")
    void textureSrtAnimates()
    {
        Narc narc = narc(139);
        Assumptions.assumeTrue(magic(narc.getFile(0)).equals("BMD0") && magic(narc.getFile(1)).equals("BTA0"),
                "expected demo_ana_d model + NSBTA layout");
        ModelSet ms = new ModelSet(narc.getFile(0));
        Model model = ms.getModels().get(0);
        TextureSrtAnimationSet.Animation ta = new TextureSrtAnimationSet(narc.getFile(1)).getAnimations().get(0);
        NitroAnimation anim = NitroAnimation.ofTextureSrt(ta);
        assertThat(anim.hasTextureSrt()).isTrue();

        BufferedImage f0 = SoftwareRenderer.render(model, anim.sample(model, 0), ms.getEmbeddedTextures(), 192, 192, 0, 80);
        BufferedImage f1 = SoftwareRenderer.render(model, anim.sample(model, ta.getFrameCount() / 3), ms.getEmbeddedTextures(), 192, 192, 0, 80);
        assertThat(differingPixels(f0, f1)).as("the water texture should scroll between frames").isGreaterThan(50);
    }

    @Test
    @DisplayName("NSBTP pattern swaps which texture a material samples")
    void patternAnimates()
    {
        Narc narc = narc(139);
        Assumptions.assumeTrue(magic(narc.getFile(13)).equals("BMD0") && magic(narc.getFile(14)).equals("BTP0"),
                "expected gingaboss model + NSBTP layout");
        ModelSet ms = new ModelSet(narc.getFile(13));
        Model model = ms.getModels().get(0);
        TexturePatternAnimationSet.Animation tp = new TexturePatternAnimationSet(narc.getFile(14)).getAnimations().get(0);
        NitroAnimation anim = NitroAnimation.ofPattern(tp);

        // the override must actually name different textures at different frames
        String[] early = anim.sample(model, 0).textureOverrideFor(0);
        String[] late = anim.sample(model, tp.getFrameCount() - 1).textureOverrideFor(0);
        assertThat(early).isNotNull();
        assertThat(late).isNotNull();
        assertThat(early[0]).isNotEqualTo(late[0]);

        BufferedImage f0 = SoftwareRenderer.render(model, anim.sample(model, 0), ms.getEmbeddedTextures(), 192, 192, 180, 0);
        BufferedImage f1 = SoftwareRenderer.render(model, anim.sample(model, tp.getFrameCount() - 1), ms.getEmbeddedTextures(), 192, 192, 180, 0);
        assertThat(differingPixels(f0, f1)).as("the flip-book should change the drawn sprite").isGreaterThan(50);
    }

    @Test
    @DisplayName("NSBVA visibility hides nodes on some frames")
    void visibilityAnimates()
    {
        Narc narc = narc(139);
        Assumptions.assumeTrue(magic(narc.getFile(79)).equals("BMD0") && magic(narc.getFile(81)).equals("BVA0"),
                "expected kurotama model + NSBVA layout");
        ModelSet ms = new ModelSet(narc.getFile(79));
        Model model = ms.getModels().get(0);
        VisibilityAnimationSet.Animation va = new VisibilityAnimationSet(narc.getFile(81)).getAnimations().get(0);
        NitroAnimation anim = NitroAnimation.ofVisibility(va);
        assertThat(anim.hasVisibility()).isTrue();

        // across the clip, at least one mesh must be visible on some frame and hidden on another
        int meshes = model.getMeshes().size();
        boolean anyToggles = false;
        for (int i = 0; i < meshes && !anyToggles; i++)
        {
            boolean first = anim.sample(model, 0).isVisible(i);
            for (int f = 1; f < va.getFrameCount(); f += 7)
                if (anim.sample(model, f).isVisible(i) != first) { anyToggles = true; break; }
        }
        assertThat(anyToggles).as("visibility animation should toggle at least one node").isTrue();
    }

    @Test
    @DisplayName("AnimatedGif writes a valid, multi-frame, looping GIF")
    void writesAnimatedGif() throws Exception
    {
        Narc narc = narc(142);
        Assumptions.assumeTrue(magic(narc.getFile(51)).equals("BMD0") && magic(narc.getFile(53)).equals("BCA0"),
                "expected manene model + animation layout");
        ModelSet ms = new ModelSet(narc.getFile(51));
        Model model = ms.getModels().get(0);
        NitroAnimation anim = NitroAnimation.ofSkeletal(new SkeletalAnimationSet(narc.getFile(53)).getAnimations().get(0));

        List<BufferedImage> frames = new ArrayList<>();
        for (int f = 0; f < 6; f++)
            frames.add(SoftwareRenderer.render(model, anim.sample(model, f * (anim.getFrameCount() / 6)),
                    ms.getEmbeddedTextures(), 96, 96, 205, 12));

        File gif = File.createTempFile("nds4j-anim", ".gif");
        gif.deleteOnExit();
        AnimatedGif.write(frames, 80, gif);
        assertThat(gif.length()).isGreaterThan(0);

        try (ImageInputStream iis = ImageIO.createImageInputStream(gif))
        {
            ImageReader reader = ImageIO.getImageReaders(iis).next();
            reader.setInput(iis);
            assertThat(reader.getNumImages(true)).as("all frames should be written").isEqualTo(6);
            reader.dispose();
        }
    }
}
