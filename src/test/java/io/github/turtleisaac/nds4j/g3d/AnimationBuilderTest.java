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

import io.github.turtleisaac.nds4j.g3d.AnimationBuilder.Channel;
import io.github.turtleisaac.nds4j.g3d.AnimationBuilder.MaterialAnim;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the animation writer: {@link AnimationBuilder} authors a texture-SRT (NSBTA) file from scratch and
 * the production {@link TextureSrtAnimationSet} reads it back to the authored tracks. This is the writer
 * side of the animation formats proven end-to-end (author &rarr; decode), the counterpart to
 * {@link ModelBuilder}/{@link ObjImporter} for geometry.
 */
@DisplayName("author an NSBTA animation from scratch (writer side)")
public class AnimationBuilderTest
{
    @Test
    @DisplayName("constant and keyframe channels author and decode to the authored values")
    void authorsTextureSrt()
    {
        int frames = 32;
        float[] scroll = new float[frames];
        for (int i = 0; i < frames; i++) scroll[i] = i / (float) frames; // 0 -> ~1 ramp

        MaterialAnim mat = new MaterialAnim("water",
                Channel.constant(1f), Channel.constant(1f), Channel.constant(0f),
                Channel.keyframes(scroll, 1), Channel.constant(0f));
        byte[] nsbta = AnimationBuilder.buildTextureSrt("scroll", frames, List.of(mat));

        TextureSrtAnimationSet set = new TextureSrtAnimationSet(nsbta);
        assertThat(set.save()).as("authored NSBTA round-trips its own bytes").isEqualTo(nsbta);

        assertThat(set.getAnimations()).hasSize(1);
        TextureSrtAnimationSet.Animation anim = set.getAnimations().get(0);
        assertThat(anim.getName()).isEqualTo("scroll");
        assertThat(anim.getFrameCount()).isEqualTo(frames);
        assertThat(anim.getMaterials()).hasSize(1);

        TextureSrtAnimationSet.MaterialSrt m = anim.getMaterials().get(0);
        assertThat(m.getName()).isEqualTo("water");
        // constants survive exactly
        assertThat(m.scaleSAt(0)).isCloseTo(1f, Offset.offset(1e-3f));
        assertThat(m.scaleTAt(10)).isCloseTo(1f, Offset.offset(1e-3f));
        assertThat(m.rotationAt(5)).isCloseTo(0f, Offset.offset(0.1f));
        assertThat(m.transTAt(7)).isCloseTo(0f, Offset.offset(1e-3f));
        // the keyframe scroll ramps as authored (linear from 0 towards 1)
        assertThat(m.transSAt(0)).isCloseTo(0f, Offset.offset(1e-3f));
        assertThat(m.transSAt(frames / 2)).isCloseTo(0.5f, Offset.offset(1e-2f));
        assertThat(m.transSAt(frames - 1)).isGreaterThan(0.9f);
    }

    @Test
    @DisplayName("a keyframe rotation channel authors and decodes back to its angles")
    void authorsRotationChannel()
    {
        int frames = 16;
        float[] rot = new float[frames];
        for (int i = 0; i < frames; i++) rot[i] = 360f * i / frames; // full spin

        MaterialAnim mat = new MaterialAnim("spin",
                Channel.constant(1f), Channel.constant(1f), Channel.keyframes(rot, 1),
                Channel.constant(0f), Channel.constant(0f));
        byte[] nsbta = AnimationBuilder.buildTextureSrt("rot", frames, List.of(mat));
        TextureSrtAnimationSet.MaterialSrt m = new TextureSrtAnimationSet(nsbta).getAnimations().get(0).getMaterials().get(0);

        // rotation is stored as (sin,cos) fx16, so compare on the circle (wrap-safe)
        for (int f : new int[]{0, 4, 8, 12})
        {
            double expected = Math.toRadians(rot[f]);
            double got = Math.toRadians(m.rotationAt(f));
            assertThat(Math.sin(got)).as("sin at frame %d", f).isCloseTo(Math.sin(expected), Offset.offset(0.02));
            assertThat(Math.cos(got)).as("cos at frame %d", f).isCloseTo(Math.cos(expected), Offset.offset(0.02));
        }
    }

    @Test
    @DisplayName("multiple materials each get their own independent tracks")
    void authorsMultipleMaterials()
    {
        int frames = 8;
        MaterialAnim a = new MaterialAnim("matA",
                Channel.constant(2f), Channel.constant(2f), Channel.constant(0f),
                Channel.constant(0.25f), Channel.constant(0f));
        MaterialAnim b = new MaterialAnim("matB",
                Channel.constant(0.5f), Channel.constant(0.5f), Channel.constant(90f),
                Channel.constant(0f), Channel.constant(0.75f));
        byte[] nsbta = AnimationBuilder.buildTextureSrt("multi", frames, List.of(a, b));

        TextureSrtAnimationSet.Animation anim = new TextureSrtAnimationSet(nsbta).getAnimations().get(0);
        assertThat(anim.getMaterials()).hasSize(2);
        TextureSrtAnimationSet.MaterialSrt ma = find(anim, "matA"), mb = find(anim, "matB");

        assertThat(ma.scaleSAt(0)).isCloseTo(2f, Offset.offset(1e-3f));
        assertThat(ma.transSAt(0)).isCloseTo(0.25f, Offset.offset(1e-3f));
        assertThat(mb.scaleSAt(0)).isCloseTo(0.5f, Offset.offset(1e-3f));
        assertThat(mb.transTAt(0)).isCloseTo(0.75f, Offset.offset(1e-3f));
        assertThat(Math.round(mb.rotationAt(0))).isEqualTo(90);
    }

    private static TextureSrtAnimationSet.MaterialSrt find(TextureSrtAnimationSet.Animation a, String name)
    {
        return a.getMaterials().stream().filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
    }
}
