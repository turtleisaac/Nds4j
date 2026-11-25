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
import io.github.turtleisaac.nds4j.framework.MemBuf;
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
            if (!magic(rom.getFile(i)).equals("NARC"))
                continue;
            Narc narc;
            try
            {
                narc = new Narc(rom.getFile(i));
            }
            catch (RuntimeException e)
            {
                continue;
            }
            for (int j = 0; j < narc.getNumFiles(); j++)
            {
                if (!magic(narc.getFile(j)).equals(ParticleSet.MAGIC))
                    continue;
                ParticleSet set = new ParticleSet(narc.getFile(j));
                if (set.getTextures().isEmpty())
                    continue;
                double score = 0;
                for (ParticleSet.Emitter em : set.getEmitters())
                    score += (em.getParticlePosVeloMag() + em.getParticleAxisVeloMag())
                            * Math.max(1, em.getParticleLifetime()) * Math.max(0.5, em.getEmissionVolume());
                if (score > bestScore)
                {
                    bestScore = score;
                    best = set;
                }
            }
        }
        return best;
    }

    private static long nonBlackPixels(BufferedImage img)
    {
        long n = 0;
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++)
                if ((img.getRGB(x, y) & 0xFFFFFF) > 0x101820)
                    n++;
        return n;
    }

    @Test
    @DisplayName("plays a real move effect into visible, deterministic frames")
    void playsMoveEffect()
    {
        ParticleSet set = null;
        for (String romName : new String[] { "Platinum.nds", "HeartGold.nds", "SoulSilver.nds", "Diamond.nds", "Pearl.nds" })
        {
            try
            {
                set = findLivelyArchive(romName);
            }
            catch (RuntimeException e)
            {
                continue;
            }
            if (set != null)
                break;
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
        for (BufferedImage f : frames)
            litMax = Math.max(litMax, nonBlackPixels(f));
        assertThat(litMax).as("the previewer lights up particles").isGreaterThan(50);

        // Determinism: same seed reproduces the exact frames.
        List<BufferedImage> again = new ParticleRenderer(192, 192).seed(7).render(set, 48);
        for (int i = 0; i < frames.size(); i++)
        {
            boolean identical = true;
            for (int y = 0; y < 192 && identical; y++)
                for (int x = 0; x < 192; x++)
                    if (frames.get(i).getRGB(x, y) != again.get(i).getRGB(x, y))
                    {
                        identical = false;
                        break;
                    }
            assertThat(identical).as("frame %d is deterministic for a fixed seed", i).isTrue();
        }
    }

    /**
     * A scale of 1 is a quarter of the SPL view, and the rest of the frame stays clear.
     */
    @Test
    @DisplayName("a unit-scale sprite uses the SPL camera and a transparent frame")
    void unitScaleUsesTheSplCamera()
    {
        ParticleSet set = new ParticleSet(unitSprite());
        BufferedImage frame = new ParticleRenderer(160, 160).render(set, 1).get(0);

        // The corner is outside the quad, so it stays transparent.
        assertThat(frame.getRGB(0, 0) >>> 24)
                .isZero();

        // The quad is about 40 pixels on a 160-pixel frame (view height 8, quad height 2).
        int minX = 160;
        int maxX = -1;
        int lit = 0;
        for (int y = 0; y < 160; y++)
        {
            for (int x = 0; x < 160; x++)
            {
                if ((frame.getRGB(x, y) >>> 24) > 16)
                {
                    lit++;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                }
            }
        }
        assertThat(maxX - minX + 1)
                .isGreaterThan(30);
        assertThat(lit)
                .isGreaterThan(400);
    }

    /**
     * A sprite halfway to the eye covers about twice the target-plane sprite.
     */
    @Test
    @DisplayName("perspective enlarges a sprite closer to the eye")
    void closerSpriteProjectsLarger()
    {
        int origin = litWidth(spriteAt(0));
        int closer = litWidth(spriteAt(0x2000));

        assertThat(closer)
                .isGreaterThan(origin + 20);
    }

    /**
     * A sprite past the eye is not projected.
     */
    @Test
    @DisplayName("a sprite behind the eye leaves the frame clear")
    void spriteBehindTheEyeIsClear()
    {
        BufferedImage frame = new ParticleRenderer(160, 160).render(new ParticleSet(spriteAt(0x6000)), 1).get(0);

        assertThat(frame.getRGB(80, 80) >>> 24)
                .isZero();
    }

    /**
     * Width of the opaque run through the middle of one rendered frame.
     * @param spa the archive
     * @return the width in pixels
     */
    private static int litWidth(byte[] spa)
    {
        BufferedImage frame = new ParticleRenderer(160, 160).render(new ParticleSet(spa), 1).get(0);
        int minX = 160;
        int maxX = -1;
        for (int x = 0; x < 160; x++)
        {
            if ((frame.getRGB(x, 80) >>> 24) > 16)
            {
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
            }
        }
        return maxX - minX + 1;
    }

    /**
     * The same solid sprite with its emitter moved on Z.
     * @param zFx32 emitter Z in fx32
     * @return the SPA bytes
     */
    private static byte[] spriteAt(int zFx32)
    {
        // Emitter Z is the third fx32 after the flags, at file offset 0x2C.
        MemBuf buf = MemBuf.create(halfSprite(0, 0, false, true, false));
        MemBuf.MemBufWriter writer = buf.writer();
        int end = writer.getPosition();
        writer.setPosition(0x2C);
        writer.writeInt(zFx32);
        writer.setPosition(end);
        return buf.reader().getBuffer();
    }

    /**
     * background() fills pixels the particles do not cover.
     */
    @Test
    @DisplayName("an explicit backdrop shows around the sprite")
    void backdropFillsUncoveredPixels()
    {
        ParticleSet set = new ParticleSet(unitSprite());
        BufferedImage frame = new ParticleRenderer(160, 160).background(0x102030).render(set, 1).get(0);

        assertThat(frame.getRGB(0, 0) & 0xFFFFFF)
                .isEqualTo(0x102030);
    }

    /**
     * A repeat count of 1 draws the texture twice across the quad.
     */
    @Test
    @DisplayName("S repeat stamps the texture a second time")
    void repeatDrawsTheTextureTwice()
    {
        BufferedImage frame = new ParticleRenderer(160, 160).render(new ParticleSet(halfSprite(1, 1, false)), 1).get(0);

        // The second copy's left half sits just right of centre and is opaque.
        assertThat(frame.getRGB(85, 80) >>> 24)
                .isGreaterThan(16);
        assertThat(frame.getRGB(95, 80) >>> 24)
                .isZero();
    }

    /**
     * Without the texture's repeat bit, a span past 1 stays on the edge texel.
     */
    @Test
    @DisplayName("a clamped span does not stamp a second copy")
    void clampHoldsTheEdgeTexel()
    {
        BufferedImage frame = new ParticleRenderer(160, 160).render(new ParticleSet(halfSprite(1, 0, false)), 1).get(0);

        assertThat(frame.getRGB(85, 80) >>> 24)
                .isZero();
    }

    /**
     * Flip mirrors every second repeat.
     */
    @Test
    @DisplayName("flip mirrors the second copy")
    void flipMirrorsTheSecondCopy()
    {
        BufferedImage frame = new ParticleRenderer(160, 160).render(new ParticleSet(halfSprite(1, 5, false)), 1).get(0);

        // The mirrored copy puts the opaque half on the outer side.
        assertThat(frame.getRGB(95, 80) >>> 24)
                .isGreaterThan(16);
    }

    /**
     * Flip S joins a right-edge mark to its mirror across the repeat boundary.
     */
    @Test
    @DisplayName("flip S completes the shape across the repeat boundary")
    void flipSJoinsTheRepeatedEdge()
    {
        BufferedImage frame = new ParticleRenderer(160, 160).render(new ParticleSet(halfSprite(1, 5, false, false, true)), 1).get(0);

        // Both sides of the centre sample the texture's right edge.
        assertThat(frame.getRGB(75, 80) >>> 24)
                .isGreaterThan(16);
        assertThat(frame.getRGB(85, 80) >>> 24)
                .isGreaterThan(16);
        assertThat(frame.getRGB(62, 80) >>> 24)
                .isZero();
    }

    /**
     * A negated span reads the texture from the other end.
     */
    @Test
    @DisplayName("a reversed span flips the sprite")
    void reverseFlipsTheSprite()
    {
        BufferedImage frame = new ParticleRenderer(160, 160).render(new ParticleSet(halfSprite(0, 1, true)), 1).get(0);

        assertThat(frame.getRGB(90, 80) >>> 24)
                .isGreaterThan(16);
    }

    /**
     * One emitter at the origin with a solid 16×16 A5I3 sprite and scale 1.
     * @return the SPA bytes
     */
    private static byte[] unitSprite()
    {
        return halfSprite(0, 0, false, true, false);
    }

    /**
     * A 16×16 sprite that is opaque on its left half, with the given S repeat count and texture address mode.
     * @param repeatCount the emitter's S repeat count
     * @param sMode texture S repeat and flip bits
     * @param reverse whether {@code tex_s} is negated
     * @return the SPA bytes
     */
    private static byte[] halfSprite(int repeatCount, int sMode, boolean reverse)
    {
        return halfSprite(repeatCount, sMode, reverse, false, false);
    }

    /**
     * Builds the half sprite, or a solid one when {@code solid} is set.
     * @param repeatCount the emitter's S repeat count
     * @param sMode texture S repeat and flip bits
     * @param reverse whether {@code tex_s} is negated
     * @param solid whether every texel is opaque
     * @param rightHalf whether the opaque half is the right side
     * @return the SPA bytes
     */
    private static byte[] halfSprite(int repeatCount, int sMode, boolean reverse, boolean solid, boolean rightHalf)
    {
        MemBuf file = MemBuf.create();
        MemBuf.MemBufWriter out = file.writer();

        // Archive header. The texture section starts immediately after the one emitter.
        out.writeString(" APS");
        out.writeString("12_1");
        out.writeShort((short) 1);
        out.writeShort((short) 1);
        out.setPosition(0x18);
        out.writeInt(0x78);
        out.setPosition(0x20);
        out.write(emitterBody(repeatCount, reverse));

        // A5I3 16×16. The left half is white unless the whole sprite is solid.
        out.writeString(" TPS");
        out.writeInt(6 | (1 << 4) | (1 << 8) | (sMode << 12));
        out.writeInt(256);
        out.writeInt(0x120);
        out.writeInt(16);
        out.writeInt(304);
        out.writeInt(0);
        out.writeInt(304);
        for (int texel = 0; texel < 256; texel++)
        {
            boolean left = texel % 16 < 8;
            boolean opaque = solid || (rightHalf ? !left : left);
            out.writeByte((byte) (opaque ? (31 << 3) | 1 : 0));
        }
        out.writeShort((short) 0);
        out.writeShort((short) 0x7FFF);
        for (int color = 0; color < 6; color++)
            out.writeShort((short) 0);
        return file.reader().getBuffer();
    }

    /**
     * Each texture format decodes, including the formats the synthetic sprites do not use.
     */
    @Test
    @DisplayName("every particle texture format decodes a texel")
    void everyTextureFormatDecodes()
    {
        assertThat(texel(6, 64, new byte[] { (byte) ((31 << 3) | 1) }))
                .isEqualTo(0xFFF8F8F8);
        assertThat(texel(1, 64, new byte[] { (byte) ((7 << 5) | 1) }) & 0xFFFFFF)
                .isEqualTo(0xF8F8F8);
        assertThat(texel(4, 64, new byte[] { 1 }))
                .isEqualTo(0xFFF8F8F8);
        assertThat(texel(3, 32, new byte[] { 1 }))
                .isEqualTo(0xFFF8F8F8);
        assertThat(texel(2, 16, new byte[] { 1 }))
                .isEqualTo(0xFFF8F8F8);
        assertThat(texel(7, 128, new byte[] { (byte) 0xFF, (byte) 0xFF }))
                .isEqualTo(0xFFF8F8F8);
        assertThat(texel(0, 64, new byte[] { 1 }))
                .isZero();
    }

    /**
     * The first pixel of an 8×8 texture in {@code format}.
     * @param format the NNS texture format
     * @param texelBytes size of the texel block
     * @param sample the first texel bytes
     * @return the packed pixel
     */
    private static int texel(int format, int texelBytes, byte[] sample)
    {
        byte[] texels = new byte[texelBytes];
        System.arraycopy(sample, 0, texels, 0, sample.length);
        ParticleSet.ParticleTexture texture = new ParticleSet(oneTexture(format, texels)).getTextures().get(0);
        return texture.getImage().getRGB(0, 0);
    }

    /**
     * An archive whose only member is one 8×8 texture.
     * @param format the NNS texture format
     * @param texels the texel block
     * @return the SPA bytes
     */
    private static byte[] oneTexture(int format, byte[] texels)
    {
        int paletteAt = 0x20 + texels.length;
        int total = paletteAt + 16;
        MemBuf file = MemBuf.create();
        MemBuf.MemBufWriter out = file.writer();

        // No emitters. The texture section is the header's next block.
        out.writeString(" APS");
        out.writeString("12_1");
        out.writeShort((short) 0);
        out.writeShort((short) 1);
        out.setPosition(0x18);
        out.writeInt(0x20);
        out.setPosition(0x20);
        out.writeString(" TPS");
        out.writeInt(format);
        out.writeInt(texels.length);
        out.writeInt(paletteAt);
        out.writeInt(16);
        out.writeInt(total);
        out.writeInt(0);
        out.writeInt(total);
        out.write(texels);
        out.writeShort((short) 0);
        out.writeShort((short) 0x7FFF);
        for (int color = 0; color < 6; color++)
            out.writeShort((short) 0);
        return file.reader().getBuffer();
    }

    /**
     * One 0x58-byte emitter with scale 1, aspect 1, and the given repeat and reverse flags.
     * @param repeatCount the S repeat count stored in the emitter
     * @param reverse whether {@code tex_s} is negated
     * @return the emitter bytes
     */
    private static byte[] emitterBody(int repeatCount, boolean reverse)
    {
        MemBuf buf = MemBuf.create();
        MemBuf.MemBufWriter body = buf.writer();

        // Volume 1, scale 1, aspect 1, a short lifetime, then interval, alpha, and texture flags.
        body.setPosition(16);
        body.writeInt(0x1000);
        body.setPosition(44);
        body.writeInt(0x1000);
        body.writeShort((short) 0x1000);
        body.setPosition(60);
        body.writeShort((short) 4);
        body.writeShort((short) 8);
        body.setPosition(0x44);
        body.writeByte((byte) 1);
        body.writeByte((byte) 31);
        body.setPosition(0x4B);
        body.writeByte((byte) repeatCount);
        if (reverse)
            body.writeByte((byte) 1);
        body.setPosition(0x58);
        return buf.reader().getBuffer();
    }
}
