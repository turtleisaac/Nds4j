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

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A headless, pure-JVM previewer that actually <em>plays</em> a {@link ParticleSet} (Gen IV SPL move/battle
 * effect): it simulates each emitter's spawn/velocity/lifetime/field behaviour frame by frame and composites
 * the emitters' alpha-mask sprites over a transparent frame. The quad size uses the SPL
 * default camera. {@link AnimatedGif} can turn the frames into a GIF.
 * <p>
 * This is an <em>interpretation</em> of the NNS particle runtime, not a byte-exact round-trip: the decoded
 * emitter parameters ({@link ParticleSet.Emitter}) are the correctness bar (they read byte-exactly), and this
 * class turns them into a recognisable, deterministic playback. The simulation is seeded, so a given archive
 * always renders the same frames.
 */
public class ParticleRenderer
{
    /**
     * Eye position on Z for the camera {@code sub_02014DB4} passes to
     * {@code Camera_Init_FromTargetAndPos}: {@code (0, 0, 4)} looking at the origin.
     */
    private static final double EYE_Z = 4.0;

    /**
     * Tangent of half the fovy. The same init stores index {@code 0x2000}, a quarter turn,
     * and {@code NNS_G3dGlbPerspective} uses that angle, so the tangent is 1.
     */
    private static final double TAN_HALF_FOVY = 1.0;

    private final int width, height;
    private long seed = 0x5eed1234L;
    private int background = 0x08080f;
    private boolean backdrop = false;
    private Double ticksPerSecond;

    /** Creates a previewer that renders {@code width}×{@code height} frames. */
    public ParticleRenderer(int width, int height)
    {
        this.width = width;
        this.height = height;
    }

    /** Sets the RNG seed (playback is deterministic for a given seed). @return this */
    public ParticleRenderer seed(long seed)
    {
        this.seed = seed;
        return this;
    }

    /**
     * Plays the ticks at {@code ticksPerSecond}.
     * Without this call each frame keeps the default GIF delay.
     * @param ticksPerSecond SPL ticks per second of playback
     * @return this previewer
     */
    public ParticleRenderer ticksPerSecond(double ticksPerSecond)
    {
        this.ticksPerSecond = Double.valueOf(ticksPerSecond);
        return this;
    }

    /**
     * Centisecond GIF delay for each of {@code ticks} frames at this preview's rate.
     * @param ticks how many frames were rendered
     * @return one delay per tick
     */
    public int[] frameDelaysCs(int ticks)
    {
        return FrameDelay.centiseconds(ticks, ticksPerSecond);
    }

    /**
     * Composites the effect over an opaque color. Without this call the frame stays transparent.
     * @param rgb a packed {@code 0xRRGGBB} color
     * @return this previewer
     */
    public ParticleRenderer background(int rgb)
    {
        this.background = rgb;
        this.backdrop = true;
        return this;
    }

    /** A single live particle in the simulation. */
    private static final class P
    {
        double x, y, z, vx, vy, vz;
        int age, life, tex;
        double baseScale, alpha01;
        int tint;
        ParticleSet.Emitter em;
    }

    /**
     * Simulates and renders {@code frameCount} frames of the whole archive (all emitters together).
     * @param set the particle archive
     * @param frameCount how many frames to play
     * @return one {@link BufferedImage} per frame
     */
    public List<BufferedImage> render(ParticleSet set, int frameCount)
    {
        // Pre-decode the sprite images once (indexed by textureId).
        ParticleSet.ParticleTexture[] textures = set.getTextures().toArray(new ParticleSet.ParticleTexture[0]);
        BufferedImage[] sprites = new BufferedImage[textures.length];
        for (int i = 0; i < sprites.length; i++)
            sprites[i] = textures[i].getImage();

        // Replay one seeded simulation on the SPL camera.
        Random rng = new Random(seed);
        List<P> live = new ArrayList<>();
        List<BufferedImage> frames = new ArrayList<>(frameCount);
        for (int frame = 0; frame < frameCount; frame++)
        {
            spawn(set, frame, sprites.length, rng, live);
            advance(live);
            frames.add(compose(live, sprites, textures));
        }
        return frames;
    }

    // Emits new particles for every emitter that is active on this frame.
    private void spawn(ParticleSet set, int frame, int spriteCount, Random rng, List<P> live)
    {
        for (ParticleSet.Emitter em : set.getEmitters())
        {
            int start = em.getEmissionStartTime();
            if (frame < start)
                continue;
            int life = em.getEmissionTime();
            if (life != 0 && frame >= start + life)
                continue;
            int interval = Math.max(1, em.getEmissionInterval());
            if ((frame - start) % interval != 0)
                continue;

            double volume = em.getEmissionVolume();
            int count = (int) Math.floor(volume);
            if (rng.nextDouble() < volume - count)
                count++; // fractional emission
            count = Math.max(count, volume > 0 ? 1 : 0);
            if (live.size() > 6000)
                return; // safety cap for the preview

            for (int k = 0; k < count && live.size() < 6000; k++)
                live.add(makeParticle(em, spriteCount, rng));
        }
    }

    private P makeParticle(ParticleSet.Emitter em, int spriteCount, Random rng)
    {
        P p = new P();
        p.em = em;
        // Spawn direction: a random unit vector; volume shapes place the particle out along it.
        double[] dir = randomDir(em.getShape(), rng);
        double radius = em.getEmitterRadius();
        double r = isVolumeShape(em.getShape()) ? radius * Math.cbrt(rng.nextDouble()) : radius;
        p.x = em.getPosX() + dir[0] * r;
        p.y = em.getPosY() + dir[1] * r;
        p.z = em.getPosZ() + dir[2] * r;

        double mag = em.getParticlePosVeloMag() * (1 - em.getVeloMagRandomness() * rng.nextDouble());
        double axisMag = em.getParticleAxisVeloMag();
        p.vx = dir[0] * mag;
        p.vy = dir[1] * mag + axisMag; // axis velocity biases along the emitter's up
        p.vz = dir[2] * mag;

        int life = em.getParticleLifetime();
        p.life = Math.max(1, (int) (life * (1 - em.getLifetimeRandomness() * rng.nextDouble())));
        p.age = 0;
        p.tex = spriteCount == 0 ? -1 : Math.floorMod(em.getTextureId(), spriteCount);
        p.baseScale = em.getParticleBaseScale() * (1 - em.getScaleRandomness() * rng.nextDouble());
        p.alpha01 = em.getParticleAlpha() / 31.0;
        p.tint = em.getColorRgb();
        return p;
    }

    // Advances every live particle one frame and drops the dead ones.
    private void advance(List<P> live)
    {
        for (int i = live.size() - 1; i >= 0; i--)
        {
            P p = live.get(i);
            p.x += p.vx;
            p.y += p.vy;
            p.z += p.vz;
            double air = p.em.getAirResistance();
            p.vx *= air;
            p.vy *= air;
            p.vz *= air;
            ParticleSet.Gravity g = p.em.getGravity();
            if (g != null)
            {
                p.vx += g.getX();
                p.vy += g.getY();
                p.vz += g.getZ();
            }
            if (++p.age >= p.life)
                live.remove(i);
        }
    }

    /**
     * Paints the live particles over one frame.
     * @param live particles still alive this frame
     * @param sprites decoded emitter textures
     * @param textures the same textures, with their repeat and flip flags
     * @return the frame, with alpha
     */
    private BufferedImage compose(List<P> live, BufferedImage[] sprites, ParticleSet.ParticleTexture[] textures)
    {
        // Clear, or the flat color chosen with background().
        float[] acc = new float[width * height * 4];
        if (backdrop)
            fillBackdrop(acc);

        // Later particles cover earlier ones, the same order the draw loop emits them.
        for (P p : live)
            paint(acc, p, sprites, textures);
        return toImage(acc);
    }

    /**
     * Fills the frame with the opaque backdrop color.
     * @param acc premultiplied red, green, blue, and alpha per pixel
     */
    private void fillBackdrop(float[] acc)
    {
        float br = ((background >> 16) & 0xFF) / 255f;
        float bg = ((background >> 8) & 0xFF) / 255f;
        float bb = (background & 0xFF) / 255f;
        for (int i = 0; i < acc.length; i += 4)
        {
            acc[i] = br;
            acc[i + 1] = bg;
            acc[i + 2] = bb;
            acc[i + 3] = 1f;
        }
    }

    /**
     * Draws one billboard if it still has size and alpha.
     * @param acc premultiplied red, green, blue, and alpha per pixel
     * @param p the particle
     * @param sprites decoded emitter textures
     * @param textures the same textures, with their repeat and flip flags
     */
    private void paint(float[] acc, P p, BufferedImage[] sprites, ParticleSet.ParticleTexture[] textures)
    {
        // A missing texture, or a particle that has faded, leaves the frame alone.
        if (p.tex < 0 || p.tex >= sprites.length || sprites[p.tex] == null)
            return;
        double lifeT = lifeFraction(p);
        double scale = p.baseScale * scaleFactor(p.em, lifeT);
        double alpha = p.alpha01 * alphaFactor(p.em, lifeT);
        if (alpha <= 0.001 || scale <= 0)
            return;

        // Perspective from the eye. A point behind the eye has no place on the frame.
        double depth = EYE_Z - p.z;
        if (depth <= 1.0e-4)
            return;
        double pixels = (height * 0.5) / (depth * TAN_HALF_FOVY);
        double sx = width / 2.0 + p.x * pixels;
        double sy = height / 2.0 - p.y * pixels;
        double halfY = scale * pixels;
        double halfX = halfY * p.em.getAspectRatio();
        int tint = tintColor(p.em, lifeT, p.tint);
        drawSprite(acc, sprites[p.tex], textures[p.tex], sx, sy, halfX, halfY, tint, (float) alpha, p.em);
    }

    /**
     * Age of a particle as a fraction of its life, from 0 at birth to 1 at the last frame.
     * @param p the particle
     * @return the fraction
     */
    private static double lifeFraction(P p)
    {
        if (p.life <= 1)
            return 1;
        return (double) p.age / (p.life - 1);
    }

    /**
     * The texture coordinate span {@code spl_init} stores in {@code tex_s} or {@code tex_t}.
     * A count of 1 is one extra repeat. The shift is kept in a signed halfword, so a count of 3 is -8.
     * @param count the 2-bit repeat count
     * @param reverse whether that span is negated
     * @return the span, in textures, across the quad
     */
    private static double texSpan(int count, boolean reverse)
    {
        short stored = (short) (0x1000 << count);
        if (reverse)
            stored = (short) -stored;
        return stored / 4096.0;
    }

    /**
     * Blends one tinted sprite into the frame with source-over alpha.
     * @param acc premultiplied red, green, blue, and alpha per pixel
     * @param sprite the decoded emitter texture
     * @param texture repeat and flip flags for that texture
     * @param sx centre x
     * @param sy centre y
     * @param halfX half the quad width in pixels
     * @param halfY half the quad height in pixels
     * @param tint packed {@code 0xRRGGBB} vertex color
     * @param alpha polygon alpha, 0 to 1
     * @param em the emitter, for its texture span
     */
    private void drawSprite(float[] acc, BufferedImage sprite, ParticleSet.ParticleTexture texture,
            double sx, double sy, double halfX, double halfY, int tint, float alpha, ParticleSet.Emitter em)
    {
        // Clip the quad to the frame, then blend every covered pixel.
        int x0 = Math.max(0, (int) Math.floor(sx - halfX));
        int x1 = Math.min(width, (int) Math.ceil(sx + halfX));
        int y0 = Math.max(0, (int) Math.floor(sy - halfY));
        int y1 = Math.min(height, (int) Math.ceil(sy + halfY));
        float tr = ((tint >> 16) & 0xFF) / 255f;
        float tg = ((tint >> 8) & 0xFF) / 255f;
        float tb = (tint & 0xFF) / 255f;
        double spanS = texSpan(em.getTexRepeatS(), em.isReverseTexS());
        double spanT = texSpan(em.getTexRepeatT(), em.isReverseTexT());
        for (int y = y0; y < y1; y++)
        {
            int syp = texel(y, sy, halfY, sprite.getHeight(), spanT, texture.isRepeatT(), texture.isFlipT());
            for (int x = x0; x < x1; x++)
            {
                int sxp = texel(x, sx, halfX, sprite.getWidth(), spanS, texture.isRepeatS(), texture.isFlipS());
                blendTexel(acc, sprite.getRGB(sxp, syp), x, y, tr, tg, tb, alpha);
            }
        }
    }

    /**
     * Maps one screen pixel into a sprite texel.
     * The quad's coordinate runs across {@code span} texture widths, then repeats, flips, or clamps.
     * @param pixel the screen coordinate
     * @param centre the quad centre
     * @param half half the quad size
     * @param size the sprite size along this axis
     * @param span how many texture widths the quad covers
     * @param repeat whether coordinates past one texture wrap
     * @param flip whether every second wrap is mirrored
     * @return a texel index inside the sprite
     * @see <a href="https://problemkaputt.de/gbatek.htm#ds3dtextureattributes">GBATEK texture attributes</a>
     */
    private static int texel(int pixel, double centre, double half, int size, double span, boolean repeat, boolean flip)
    {
        double u = (pixel - (centre - half)) / (2 * half) * span;
        double wrapped = address(u, repeat, flip);
        int i = (int) (wrapped * size);
        if (i < 0)
            return 0;
        if (i >= size)
            return size - 1;
        return i;
    }

    /**
     * Folds one texture coordinate into the unit interval.
     * @param u the coordinate, in textures
     * @param repeat whether values outside 0..1 wrap
     * @param flip whether odd wraps are mirrored
     * @return a coordinate in 0..1
     */
    private static double address(double u, boolean repeat, boolean flip)
    {
        if (!repeat)
        {
            if (u < 0)
                return 0;
            if (u > 1)
                return 1;
            return u;
        }
        double tile = Math.floor(u);
        double frac = u - tile;
        if (flip && (((int) tile) & 1) != 0)
            return 1 - frac;
        return frac;
    }

    /**
     * Source-over blends one texel. Vertex color modulates the texture, matching 3D modulation.
     * @param acc premultiplied red, green, blue, and alpha per pixel
     * @param argb the texel
     * @param x screen x
     * @param y screen y
     * @param tr tint red, 0 to 1
     * @param tg tint green, 0 to 1
     * @param tb tint blue, 0 to 1
     * @param alpha polygon alpha, 0 to 1
     * @see <a href="https://problemkaputt.de/gbatek.htm#ds3dtextureblending">GBATEK texture blending</a>
     */
    private void blendTexel(float[] acc, int argb, int x, int y, float tr, float tg, float tb, float alpha)
    {
        // Texture alpha times polygon alpha. A clear texel does not change the pixel.
        float sa = ((argb >>> 24) / 255f) * alpha;
        if (sa <= 0)
            return;
        float sr = ((argb >> 16) & 0xFF) / 255f * tr * sa;
        float sg = ((argb >> 8) & 0xFF) / 255f * tg * sa;
        float sb = (argb & 0xFF) / 255f * tb * sa;
        int idx = (y * width + x) * 4;
        float inv = 1f - sa;
        acc[idx] = sr + acc[idx] * inv;
        acc[idx + 1] = sg + acc[idx + 1] * inv;
        acc[idx + 2] = sb + acc[idx + 2] * inv;
        acc[idx + 3] = sa + acc[idx + 3] * inv;
    }

    /**
     * Turns the premultiplied buffer into an ARGB image.
     * @param acc premultiplied red, green, blue, and alpha per pixel
     * @return the frame
     */
    private BufferedImage toImage(float[] acc)
    {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                int idx = (y * width + x) * 4;
                float a = acc[idx + 3];
                if (a <= 0.001f)
                {
                    img.setRGB(x, y, 0);
                    continue;
                }
                int r = clamp8(acc[idx] / a);
                int g = clamp8(acc[idx + 1] / a);
                int b = clamp8(acc[idx + 2] / a);
                int ia = clamp8(a);
                img.setRGB(x, y, (ia << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    private static int clamp8(float v)
    {
        return Math.max(0, Math.min(255, (int) (v * 255)));
    }

    private static double scaleFactor(ParticleSet.Emitter em, double t)
    {
        ParticleSet.ScaleAnim a = em.getScaleAnim();
        if (a == null)
            return 1;
        if (t < a.getInEndTime() && a.getInEndTime() > 0)
            return lerp(a.getInitial(), a.getIntermediate(), t / a.getInEndTime());
        if (t > a.getOutStartTime() && a.getOutStartTime() < 1)
            return lerp(a.getIntermediate(), a.getEnding(), (t - a.getOutStartTime()) / (1 - a.getOutStartTime()));
        return a.getIntermediate();
    }

    private static double alphaFactor(ParticleSet.Emitter em, double t)
    {
        ParticleSet.AlphaAnim a = em.getAlphaAnim();
        if (a == null)
            return 1;
        double init = a.getInitial() / 31.0, peak = a.getPeak() / 31.0, end = a.getEnding() / 31.0;
        if (t < a.getInEndTime() && a.getInEndTime() > 0)
            return lerp(init, peak, t / a.getInEndTime());
        if (t > a.getOutStartTime() && a.getOutStartTime() < 1)
            return lerp(peak, end, (t - a.getOutStartTime()) / (1 - a.getOutStartTime()));
        return peak;
    }

    private static int tintColor(ParticleSet.Emitter em, double t, int base)
    {
        ParticleSet.ColorAnim a = em.getColorAnim();
        if (a == null || !a.isInterpolate())
            return base;
        int i = a.getInitial(), e = a.getEnding();
        int r = (int) lerp((i >> 16) & 0xFF, (e >> 16) & 0xFF, t);
        int g = (int) lerp((i >> 8) & 0xFF, (e >> 8) & 0xFF, t);
        int b = (int) lerp(i & 0xFF, e & 0xFF, t);
        return (r << 16) | (g << 8) | b;
    }

    private static double lerp(double a, double b, double t)
    {
        return a + (b - a) * Math.max(0, Math.min(1, t));
    }

    // Picks a random spawn direction consistent with the emitter shape.
    private static double[] randomDir(ParticleSet.EmitterShape shape, Random rng)
    {
        switch (shape)
        {
            case CIRCLE:
            case CIRCLE_EVEN:
            case CIRCLE_VOLUME:
            case CYLINDER:
            case CYLINDER_EVEN:
            {
                double a = rng.nextDouble() * Math.PI * 2;
                return new double[] { Math.cos(a), 0, Math.sin(a) }; // in the XZ plane
            }
            case HEMISPHERE:
            case HEMISPHERE_VOLUME:
            {
                double[] d = sphere(rng);
                d[1] = Math.abs(d[1]);
                return d;
            }
            case POINT:
            default:
                return sphere(rng);
        }
    }

    private static boolean isVolumeShape(ParticleSet.EmitterShape s)
    {
        switch (s)
        {
            case SPHERE_VOLUME:
            case CIRCLE_VOLUME:
            case HEMISPHERE_VOLUME:
            case CYLINDER:
            case CYLINDER_EVEN:
                return true;
            default:
                return false;
        }
    }

    private static double[] sphere(Random rng)
    {
        double z = rng.nextDouble() * 2 - 1;
        double a = rng.nextDouble() * Math.PI * 2;
        double r = Math.sqrt(Math.max(0, 1 - z * z));
        return new double[] { r * Math.cos(a), z, r * Math.sin(a) };
    }
}
