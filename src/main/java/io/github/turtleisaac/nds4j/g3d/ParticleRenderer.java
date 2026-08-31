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
 * the emitters' alpha-mask sprites (additively, the way a glow effect reads) into a sequence of frames, which
 * {@link AnimatedGif} can turn into a looping GIF.
 * <p>
 * This is an <em>interpretation</em> of the NNS particle runtime, not a byte-exact round-trip: the decoded
 * emitter parameters ({@link ParticleSet.Emitter}) are the correctness bar (they read byte-exactly), and this
 * class turns them into a recognisable, deterministic playback. The simulation is seeded, so a given archive
 * always renders the same frames.
 */
public class ParticleRenderer
{
    private final int width, height;
    private long seed = 0x5eed1234L;
    private int background = 0x08080f;

    /** Creates a previewer that renders {@code width}×{@code height} frames. */
    public ParticleRenderer(int width, int height)
    {
        this.width = width;
        this.height = height;
    }

    /** Sets the RNG seed (playback is deterministic for a given seed). @return this */
    public ParticleRenderer seed(long seed) { this.seed = seed; return this; }
    /** Sets the background color (0xRRGGBB) the additive glow is composited over. @return this */
    public ParticleRenderer background(int rgb) { this.background = rgb; return this; }

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
        BufferedImage[] sprites = new BufferedImage[set.getTextures().size()];
        for (int i = 0; i < sprites.length; i++) sprites[i] = set.getTextures().get(i).getImage();

        Random rng = new Random(seed);
        List<P> live = new ArrayList<>();
        // First pass: simulate the whole clip to learn the spatial extent, so the camera can auto-fit.
        double[] extent = simulateExtent(set, frameCount, sprites.length);
        double cx = extent[0], cy = extent[1], cz = extent[2], radius = extent[3];
        double worldToScreen = Math.min(width, height) * 0.35 / Math.max(radius, 1e-3);

        // Second pass: simulate again (same seed) and render each frame.
        rng.setSeed(seed);
        live.clear();
        List<BufferedImage> frames = new ArrayList<>(frameCount);
        for (int frame = 0; frame < frameCount; frame++)
        {
            spawn(set, frame, sprites.length, rng, live);
            advance(live);
            frames.add(compose(live, sprites, cx, cy, cz, worldToScreen));
        }
        return frames;
    }

    // Runs the sim once (discarding rendering) to find the centroid and radius of all live particles.
    private double[] simulateExtent(ParticleSet set, int frameCount, int spriteCount)
    {
        Random rng = new Random(seed);
        List<P> live = new ArrayList<>();
        double minX = 1e9, minY = 1e9, minZ = 1e9, maxX = -1e9, maxY = -1e9, maxZ = -1e9;
        boolean any = false;
        for (int frame = 0; frame < frameCount; frame++)
        {
            spawn(set, frame, spriteCount, rng, live);
            advance(live);
            for (P p : live)
            {
                any = true;
                minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
                minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
                minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z);
            }
        }
        if (!any) return new double[]{0, 0, 0, 1};
        double cx = (minX + maxX) / 2, cy = (minY + maxY) / 2, cz = (minZ + maxZ) / 2;
        double r = 0.5 * Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        return new double[]{cx, cy, cz, Math.max(r, 1e-3)};
    }

    // Emits new particles for every emitter that is active on this frame.
    private void spawn(ParticleSet set, int frame, int spriteCount, Random rng, List<P> live)
    {
        for (ParticleSet.Emitter em : set.getEmitters())
        {
            int start = em.getEmissionStartTime();
            if (frame < start) continue;
            int life = em.getEmissionTime();
            if (life != 0 && frame >= start + life) continue;
            int interval = Math.max(1, em.getEmissionInterval());
            if ((frame - start) % interval != 0) continue;

            double volume = em.getEmissionVolume();
            int count = (int) Math.floor(volume);
            if (rng.nextDouble() < volume - count) count++;   // fractional emission
            count = Math.max(count, volume > 0 ? 1 : 0);
            if (live.size() > 6000) return;                    // safety cap for the preview

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
        p.vy = dir[1] * mag + axisMag;   // axis velocity biases along the emitter's up
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
            p.x += p.vx; p.y += p.vy; p.z += p.vz;
            double air = p.em.getAirResistance();
            p.vx *= air; p.vy *= air; p.vz *= air;
            ParticleSet.Gravity g = p.em.getGravity();
            if (g != null) { p.vx += g.getX(); p.vy += g.getY(); p.vz += g.getZ(); }
            if (++p.age >= p.life) live.remove(i);
        }
    }

    // Composites all live particles additively into one frame.
    private BufferedImage compose(List<P> live, BufferedImage[] sprites, double cx, double cy, double cz, double s)
    {
        float[] acc = new float[width * height * 3];
        for (P p : live)
        {
            if (p.tex < 0 || sprites[p.tex] == null) continue;
            double lifeT = p.life <= 1 ? 1 : (double) p.age / (p.life - 1);
            double scale = p.baseScale * scaleFactor(p.em, lifeT);
            double alpha = p.alpha01 * alphaFactor(p.em, lifeT);
            int tint = tintColor(p.em, lifeT, p.tint);
            if (alpha <= 0.001 || scale <= 0) continue;

            double sx = width / 2.0 + (p.x - cx) * s;
            double sy = height / 2.0 - (p.y - cy) * s;
            double halfPx = Math.max(1.5, scale * s * 0.5);
            drawSprite(acc, sprites[p.tex], sx, sy, halfPx, tint, (float) alpha);
        }
        return tonemap(acc);
    }

    // Additively splats one sprite, tinted and alpha-scaled, centered at (sx,sy) with half-size halfPx.
    private void drawSprite(float[] acc, BufferedImage sprite, double sx, double sy, double halfPx, int tint, float alpha)
    {
        int sw = sprite.getWidth(), sh = sprite.getHeight();
        int x0 = (int) Math.floor(sx - halfPx), x1 = (int) Math.ceil(sx + halfPx);
        int y0 = (int) Math.floor(sy - halfPx), y1 = (int) Math.ceil(sy + halfPx);
        float tr = ((tint >> 16) & 0xFF) / 255f, tg = ((tint >> 8) & 0xFF) / 255f, tb = (tint & 0xFF) / 255f;
        for (int y = Math.max(0, y0); y < Math.min(height, y1); y++)
        {
            double v = (y - (sy - halfPx)) / (2 * halfPx);
            int syp = (int) (v * sh);
            if (syp < 0 || syp >= sh) continue;
            for (int x = Math.max(0, x0); x < Math.min(width, x1); x++)
            {
                double u = (x - (sx - halfPx)) / (2 * halfPx);
                int sxp = (int) (u * sw);
                if (sxp < 0 || sxp >= sw) continue;
                int argb = sprite.getRGB(sxp, syp);
                float sa = ((argb >>> 24) / 255f) * alpha;
                if (sa <= 0) continue;
                int idx = (y * width + x) * 3;
                acc[idx]     += ((argb >> 16) & 0xFF) / 255f * tr * sa;
                acc[idx + 1] += ((argb >> 8) & 0xFF) / 255f * tg * sa;
                acc[idx + 2] += (argb & 0xFF) / 255f * tb * sa;
            }
        }
    }

    // Clamps the additive accumulation buffer over the background into an 8-bit RGB image.
    private BufferedImage tonemap(float[] acc)
    {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        float br = ((background >> 16) & 0xFF) / 255f, bg = ((background >> 8) & 0xFF) / 255f, bb = (background & 0xFF) / 255f;
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
            {
                int idx = (y * width + x) * 3;
                int r = clamp8(br + acc[idx]);
                int g = clamp8(bg + acc[idx + 1]);
                int b = clamp8(bb + acc[idx + 2]);
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        return img;
    }

    private static int clamp8(float v) { return Math.max(0, Math.min(255, (int) (v * 255))); }

    private static double scaleFactor(ParticleSet.Emitter em, double t)
    {
        ParticleSet.ScaleAnim a = em.getScaleAnim();
        if (a == null) return 1;
        if (t < a.getInEndTime() && a.getInEndTime() > 0)
            return lerp(a.getInitial(), a.getIntermediate(), t / a.getInEndTime());
        if (t > a.getOutStartTime() && a.getOutStartTime() < 1)
            return lerp(a.getIntermediate(), a.getEnding(), (t - a.getOutStartTime()) / (1 - a.getOutStartTime()));
        return a.getIntermediate();
    }

    private static double alphaFactor(ParticleSet.Emitter em, double t)
    {
        ParticleSet.AlphaAnim a = em.getAlphaAnim();
        if (a == null) return 1;
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
        if (a == null || !a.isInterpolate()) return base;
        int i = a.getInitial(), e = a.getEnding();
        int r = (int) lerp((i >> 16) & 0xFF, (e >> 16) & 0xFF, t);
        int g = (int) lerp((i >> 8) & 0xFF, (e >> 8) & 0xFF, t);
        int b = (int) lerp(i & 0xFF, e & 0xFF, t);
        return (r << 16) | (g << 8) | b;
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * Math.max(0, Math.min(1, t)); }

    // Picks a random spawn direction consistent with the emitter shape.
    private static double[] randomDir(ParticleSet.EmitterShape shape, Random rng)
    {
        switch (shape)
        {
            case CIRCLE: case CIRCLE_EVEN: case CIRCLE_VOLUME: case CYLINDER: case CYLINDER_EVEN:
            {
                double a = rng.nextDouble() * Math.PI * 2;
                return new double[]{Math.cos(a), 0, Math.sin(a)};   // in the XZ plane
            }
            case HEMISPHERE: case HEMISPHERE_VOLUME:
            {
                double[] d = sphere(rng); d[1] = Math.abs(d[1]); return d;
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
            case SPHERE_VOLUME: case CIRCLE_VOLUME: case HEMISPHERE_VOLUME:
            case CYLINDER: case CYLINDER_EVEN:
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
        return new double[]{r * Math.cos(a), z, r * Math.sin(a)};
    }
}
