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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * An object representation of an SPA file (a Nitro <b>SPL particle</b> archive) &mdash; the format Gen IV
 * uses for battle/move particle effects. Its magic is stored <em>byte-reversed</em> on disk as
 * {@code " APS"} (i.e. {@code "SPA "} little-endian), which is why a forward {@code "SPA"} scan misses it;
 * the retail ROMs pack hundreds (Platinum: narcs 460/461).
 * <p>
 * The container round-trips byte-for-byte (the raw bytes are preserved and {@link #save()} returns them),
 * so an unedited file is exact. On top of that it decodes the archive header (emitter and texture counts,
 * the texture section) and the embedded {@code " TPS"} ({@code "SPT "}) <b>particle textures</b> to
 * {@link BufferedImage}s &mdash; the alpha-mask sprites (glows, sparks, rings, streaks) the emitters draw.
 * The per-emitter behaviour parameters are preserved verbatim but not yet decoded.
 * <p>
 * Layout reverse-engineered from the retail files: header {@code " APS"}, version {@code "12_1"},
 * {@code u16 emitterCount}, {@code u16 textureCount}, then at {@code +0x14}/{@code +0x18} the texture
 * section size/offset. Each {@code " TPS"} texture: {@code u32 texParam} (format = {@code &7},
 * width = {@code 8<<((p>>4)&7)}, height = {@code 8<<((p>>8)&7)}), {@code u32 texelSize}, {@code u32}
 * palette offset, {@code u32} palette size, {@code u32} total size; texels at {@code +0x20}, palette at
 * the given offset.
 */
public class ParticleSet
{
    /** SPA magic as stored on disk (the 4CC {@code "SPA "} byte-reversed). */
    public static final String MAGIC = " APS";

    private final byte[] data;
    private final String version;
    private final int emitterCount;
    private final List<Emitter> emitters = new ArrayList<>();
    private final List<ParticleTexture> textures = new ArrayList<>();
    private int emitterBlockEnd;   // where the emitter walk finished (should equal the texture-section offset)

    /**
     * Generates an object representation of an SPA file.
     * @param data a <code>byte[]</code> representation of an SPA file
     */
    public ParticleSet(byte[] data)
    {
        this.data = data.clone();
        if (data.length < 0x20 || !magicAt(0).equals(MAGIC))
            throw new RuntimeException("Not a valid SPA file (magic \" APS\" expected).");
        version = new String(data, 4, 4, StandardCharsets.US_ASCII);
        emitterCount = u16(8);
        int textureCount = u16(10);
        int texSectionOffset = (int) u32(0x18);

        // Walk the emitter block (starts right after the 0x20-byte header). Each emitter is an 0x58-byte
        // fixed body followed by flag-gated optional blocks (scale/color/alpha/tex anim, child, six field
        // modifiers); there is no per-emitter size field, so every width must be exact — the walk is
        // validated by landing precisely on the texture section over all five retail ROMs.
        int e = 0x20;
        for (int i = 0; i < emitterCount && e + 0x58 <= data.length; i++)
        {
            Emitter em = new Emitter(e);
            emitters.add(em);
            e += em.byteSize;
        }
        emitterBlockEnd = e;

        int p = texSectionOffset;
        for (int i = 0; i < textureCount && p + 0x20 <= data.length; i++)
        {
            if (!magicAt(p).equals(" TPS"))
                break; // desync guard: the texture section should be a run of SPT blocks
            ParticleTexture t = new ParticleTexture(p);
            textures.add(t);
            p += t.totalSize;
        }
    }

    /** @return the archive's version tag (e.g. {@code "12_1"}) */
    public String getVersion() { return version; }
    /** @return the number of particle emitters declared in the header */
    public int getEmitterCount() { return emitterCount; }
    /** @return the decoded particle emitters (behaviour parameters: spawn, velocity, life, color, fields) */
    public List<Emitter> getEmitters() { return emitters; }
    /**
     * @return the byte offset just past the last decoded emitter. For a correctly-decoded archive this
     * equals the texture-section offset (the emitter walk consumed exactly the emitter block) &mdash; the
     * self-checking oracle that every emitter field width is right, since there is no per-emitter size field.
     */
    public int getEmitterBlockEnd() { return emitterBlockEnd; }
    /** @return the byte offset where the texture section begins */
    public int getTextureSectionOffset() { return (int) u32(0x18); }
    /** @return the decoded particle textures */
    public List<ParticleTexture> getTextures() { return textures; }

    /**
     * Returns the file's bytes, reproducing it exactly (an unedited archive round-trips byte-for-byte).
     * @return a <code>byte[]</code>
     */
    public byte[] save() { return data.clone(); }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return Arrays.equals(data, ((ParticleSet) o).data);
    }

    @Override
    public int hashCode() { return Objects.hash(Arrays.hashCode(data)); }

    @Override
    public String toString()
    {
        return String.format("ParticleSet[%s, %d emitters, %d textures]", version, emitterCount, textures.size());
    }

    /** How an emitter distributes newly-spawned particles in space (SPL {@code EmitterShape}). */
    public enum EmitterShape { POINT, SPHERE, CIRCLE, CIRCLE_EVEN, SPHERE_VOLUME, CIRCLE_VOLUME,
        CYLINDER, CYLINDER_EVEN, HEMISPHERE, HEMISPHERE_VOLUME }

    /**
     * One particle emitter: the behaviour that spawns and animates a cloud of particles (spawn shape/rate,
     * velocity, lifetime, color/scale/alpha-over-life curves, and optional force fields). The bytes are
     * preserved verbatim by the container; this is a decoded read-only view over them, reverse-engineered
     * from the retail files and cross-checked against the independent HaroohiePals SPL reader (the emitter
     * walk lands byte-exactly on the texture section over all 3144 retail archives).
     */
    public final class Emitter
    {
        final int byteSize;                 // total on-disk size incl. optional blocks (for the walk)

        // ---- fixed 0x58-byte body ----
        private final long flags;
        private final double posX, posY, posZ;     // emitter position (world units)
        private final double emissionVolume;       // fractional particles emitted per emission
        private final double emitterRadius, emitterLength;
        private final double axisX, axisY, axisZ;  // emitter axis (unit-ish)
        private final int color;                   // BGR555
        private final double particlePosVeloMag, particleAxisVeloMag, particleBaseScale, aspectRatio;
        private final int emissionStartTime;       // frames before first emission
        private final double minRotVelocity, maxRotVelocity, particleRotation; // degrees / degrees-per-frame
        private final int emissionTime;            // emitter lifetime in frames (0 = infinite)
        private final int particleLifetime;        // per-particle lifetime in frames
        private final double scaleRandomness, lifetimeRandomness, veloMagRandomness;
        private final int emissionInterval;        // frames between emissions (1 = every frame)
        private final int particleAlpha;           // 0..31
        private final double airResistance;
        private final int textureId, loopFrame;
        private final EmitterShape shape;

        // ---- optional flag-gated blocks (null when absent) ----
        private final ScaleAnim scaleAnim;
        private final ColorAnim colorAnim;
        private final AlphaAnim alphaAnim;
        private final int[] texAnimFrames;         // flip-book texture ids (null if no tex anim)
        private final Gravity gravity;

        Emitter(int start)
        {
            int c = start;
            this.flags = u32(c); c += 4;
            this.shape = EmitterShape.values()[(int) (flags & 0xF) % EmitterShape.values().length];
            this.posX = fx32(c); this.posY = fx32(c + 4); this.posZ = fx32(c + 8); c += 12;
            this.emissionVolume = fx32(c); c += 4;
            this.emitterRadius = fx32(c); c += 4;
            this.emitterLength = fx32(c); c += 4;
            this.axisX = fx16(c); this.axisY = fx16(c + 2); this.axisZ = fx16(c + 4); c += 6;
            this.color = bgr555(u16(c)); c += 2;
            this.particlePosVeloMag = fx32(c); c += 4;
            this.particleAxisVeloMag = fx32(c); c += 4;
            this.particleBaseScale = fx32(c); c += 4;
            this.aspectRatio = fx16(c); c += 2;
            this.emissionStartTime = u16(c); c += 2;
            this.minRotVelocity = s16(c) * 360.0 / 65536.0; c += 2;
            this.maxRotVelocity = s16(c) * 360.0 / 65536.0; c += 2;
            this.particleRotation = s16(c) * 360.0 / 65536.0; c += 2;
            c += 2; // padding
            this.emissionTime = u16(c); c += 2;
            this.particleLifetime = u16(c); c += 2;
            this.scaleRandomness = (data[c++] & 0xFF) / 256.0;
            this.lifetimeRandomness = (data[c++] & 0xFF) / 256.0;
            this.veloMagRandomness = (data[c++] & 0xFF) / 256.0;
            c += 1; // padding
            this.emissionInterval = data[c++] & 0xFF;
            this.particleAlpha = data[c++] & 0xFF;
            this.airResistance = ((data[c++] & 0xFF) + 384) / 512.0;
            this.textureId = data[c++] & 0xFF;
            this.loopFrame = data[c++] & 0xFF;
            c += 2; // DirBillboardScale
            c += 1; // tex-repeat / scale-mode packed byte
            c += 4; // tex-flip packed word
            c += 2; // QuadXOffset
            c += 2; // QuadYZOffset
            c += 4; // UserData  -> c is now start + 0x58

            this.scaleAnim = (flags >> 8 & 1) == 1 ? new ScaleAnim(c) : null;
            if (scaleAnim != null) c += 12;
            this.colorAnim = (flags >> 9 & 1) == 1 ? new ColorAnim(c) : null;
            if (colorAnim != null) c += 12;
            this.alphaAnim = (flags >> 10 & 1) == 1 ? new AlphaAnim(c) : null;
            if (alphaAnim != null) c += 8;
            if ((flags >> 11 & 1) == 1) { this.texAnimFrames = readTexAnim(c); c += 12; }
            else this.texAnimFrames = null;
            if ((flags >> 16 & 1) == 1) c += 20; // child particles (behaviour preserved verbatim)
            this.gravity = (flags >> 24 & 1) == 1 ? new Gravity(c) : null;
            if (gravity != null) c += 8;
            if ((flags >> 25 & 1) == 1) c += 8;  // field: random
            if ((flags >> 26 & 1) == 1) c += 16; // field: magnet
            if ((flags >> 27 & 1) == 1) c += 4;  // field: spin
            if ((flags >> 28 & 1) == 1) c += 8;  // field: collision
            if ((flags >> 29 & 1) == 1) c += 16; // field: convergence

            this.byteSize = c - start;
        }

        private int[] readTexAnim(int c)
        {
            int n = data[c + 8] & 0xFF;
            int[] f = new int[Math.min(n, 8)];
            for (int i = 0; i < f.length; i++) f[i] = data[c + i] & 0xFF;
            return f;
        }

        /** @return emitter position X (world units) */ public double getPosX() { return posX; }
        /** @return emitter position Y (world units) */ public double getPosY() { return posY; }
        /** @return emitter position Z (world units) */ public double getPosZ() { return posZ; }
        /** @return how particles are distributed in space when spawned */ public EmitterShape getShape() { return shape; }
        /** @return the spawn radius (units) for sphere/circle/cylinder shapes */ public double getEmitterRadius() { return emitterRadius; }
        /** @return fractional particles emitted per emission tick */ public double getEmissionVolume() { return emissionVolume; }
        /** @return frames between emissions (1 = every frame) */ public int getEmissionInterval() { return emissionInterval; }
        /** @return frames to wait before the first emission */ public int getEmissionStartTime() { return emissionStartTime; }
        /** @return emitter lifetime in frames (0 = infinite) */ public int getEmissionTime() { return emissionTime; }
        /** @return per-particle lifetime in frames */ public int getParticleLifetime() { return particleLifetime; }
        /** @return outward (position-relative) initial speed magnitude */ public double getParticlePosVeloMag() { return particlePosVeloMag; }
        /** @return axis-aligned initial speed magnitude */ public double getParticleAxisVeloMag() { return particleAxisVeloMag; }
        /** @return base particle scale */ public double getParticleBaseScale() { return particleBaseScale; }
        /** @return particle width/height aspect ratio */ public double getAspectRatio() { return aspectRatio; }
        /** @return per-frame air resistance factor (~0.75..1.25, 1 = none) */ public double getAirResistance() { return airResistance; }
        /** @return base particle color as packed 0xRRGGBB */ public int getColorRgb() { return color; }
        /** @return base particle alpha, 0..31 */ public int getParticleAlpha() { return particleAlpha; }
        /** @return index into the archive's texture list for this emitter's sprite */ public int getTextureId() { return textureId; }
        /** @return randomness applied to per-particle initial speed (0..~1) */ public double getVeloMagRandomness() { return veloMagRandomness; }
        /** @return randomness applied to per-particle scale (0..~1) */ public double getScaleRandomness() { return scaleRandomness; }
        /** @return randomness applied to per-particle lifetime (0..~1) */ public double getLifetimeRandomness() { return lifetimeRandomness; }
        /** @return the scale-over-life curve, or {@code null} if the emitter has none */ public ScaleAnim getScaleAnim() { return scaleAnim; }
        /** @return the color-over-life curve, or {@code null} if the emitter has none */ public ColorAnim getColorAnim() { return colorAnim; }
        /** @return the alpha-over-life curve, or {@code null} if the emitter has none */ public AlphaAnim getAlphaAnim() { return alphaAnim; }
        /** @return the gravity field, or {@code null} if the emitter has none */ public Gravity getGravity() { return gravity; }
        /** @return the flip-book texture-id sequence, or {@code null} if there is no texture animation */ public int[] getTexAnimFrames() { return texAnimFrames; }

        @Override public String toString()
        {
            return String.format("Emitter[shape=%s pos=(%.2f,%.2f,%.2f) vol=%.2f interval=%d life=%d tex=%d%s%s%s%s]",
                shape, posX, posY, posZ, emissionVolume, emissionInterval, particleLifetime, textureId,
                scaleAnim != null ? " +scale" : "", colorAnim != null ? " +color" : "",
                alphaAnim != null ? " +alpha" : "", gravity != null ? " +gravity" : "");
        }
    }

    /** Scale-over-life curve: ramps {@code initial → intermediate → ending} across the particle's life. */
    public final class ScaleAnim
    {
        private final double initial, intermediate, ending, inEnd, outStart;
        ScaleAnim(int c)
        {
            initial = fx16(c); intermediate = fx16(c + 2); ending = fx16(c + 4);
            inEnd = (data[c + 6] & 0xFF) / 256.0; outStart = (data[c + 7] & 0xFF) / 256.0;
        }
        /** @return scale at spawn */ public double getInitial() { return initial; }
        /** @return scale during the sustain phase */ public double getIntermediate() { return intermediate; }
        /** @return scale at death */ public double getEnding() { return ending; }
        /** @return normalised life-fraction at which the ramp-in ends (0..1) */ public double getInEndTime() { return inEnd; }
        /** @return normalised life-fraction at which the ramp-out starts (0..1) */ public double getOutStartTime() { return outStart; }
    }

    /** Color-over-life curve: interpolates {@code initial → ending} (both BGR555 → 0xRRGGBB). */
    public final class ColorAnim
    {
        private final int initial, ending;
        private final double inEnd, peak, outStart;
        private final boolean interpolate;
        ColorAnim(int c)
        {
            initial = bgr555(u16(c)); ending = bgr555(u16(c + 2));
            inEnd = (data[c + 4] & 0xFF) / 256.0; peak = (data[c + 5] & 0xFF) / 256.0;
            outStart = (data[c + 6] & 0xFF) / 256.0;
            interpolate = (u16(c + 8) >> 2 & 1) == 1;
        }
        /** @return color at spawn as 0xRRGGBB */ public int getInitial() { return initial; }
        /** @return color at death as 0xRRGGBB */ public int getEnding() { return ending; }
        /** @return normalised life-fraction at which the color reaches its peak (0..1) */ public double getPeakTime() { return peak; }
        /** @return whether the color interpolates smoothly (vs steps) */ public boolean isInterpolate() { return interpolate; }
    }

    /** Alpha-over-life curve: 5-bit {@code initial → peak → ending} alpha across the particle's life. */
    public final class AlphaAnim
    {
        private final int initial, peak, ending;
        private final double inEnd, outStart;
        AlphaAnim(int c)
        {
            int tmp = u16(c);
            initial = tmp & 0x1F; peak = tmp >> 5 & 0x1F; ending = tmp >> 10 & 0x1F;
            inEnd = (data[c + 4] & 0xFF) / 256.0; outStart = (data[c + 5] & 0xFF) / 256.0;
        }
        /** @return alpha at spawn, 0..31 */ public int getInitial() { return initial; }
        /** @return alpha at the sustain peak, 0..31 */ public int getPeak() { return peak; }
        /** @return alpha at death, 0..31 */ public int getEnding() { return ending; }
        /** @return normalised life-fraction at which the ramp-in ends (0..1) */ public double getInEndTime() { return inEnd; }
        /** @return normalised life-fraction at which the ramp-out starts (0..1) */ public double getOutStartTime() { return outStart; }
    }

    /** A constant gravity vector applied to every particle each frame. */
    public final class Gravity
    {
        private final double x, y, z;
        Gravity(int c) { x = fx16(c); y = fx16(c + 2); z = fx16(c + 4); }
        /** @return gravity X per frame */ public double getX() { return x; }
        /** @return gravity Y per frame */ public double getY() { return y; }
        /** @return gravity Z per frame */ public double getZ() { return z; }
    }

    /** One particle texture ({@code " TPS"}/{@code "SPT "}): an alpha-mask sprite an emitter draws. */
    public final class ParticleTexture
    {
        private final int start;
        private final int format, width, height;
        private final int texelOffset, paletteOffset;
        private final int totalSize;

        ParticleTexture(int start)
        {
            this.start = start;
            long param = u32(start + 4);
            this.format = (int) (param & 7);
            this.width = 8 << ((int) (param >> 4) & 7);
            this.height = 8 << ((int) (param >> 8) & 7);
            this.texelOffset = start + 0x20;
            this.paletteOffset = start + (int) u32(start + 12);
            this.totalSize = (int) u32(start + 20);
        }

        /** @return the texture width in texels */
        public int getWidth() { return width; }
        /** @return the texture height in texels */
        public int getHeight() { return height; }
        /** @return the NNS texture format (particle sprites are usually 6 = A5I3) */
        public int getFormat() { return format; }

        /**
         * Decodes this particle texture to an image (RGBA, with the sprite's alpha).
         * @return a {@link BufferedImage}
         */
        public BufferedImage getImage()
        {
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++)
                    img.setRGB(x, y, texel(y * width + x));
            return img;
        }

        // Decodes one texel to ARGB, covering the palette/alpha/direct formats a particle uses.
        private int texel(int p)
        {
            switch (format)
            {
                case 6: // A5I3: 3-bit index, 5-bit alpha
                {
                    int b = data[texelOffset + p] & 0xFF;
                    return (scale(b >> 3, 31) << 24) | color(b & 7);
                }
                case 1: // A3I5: 5-bit index, 3-bit alpha
                {
                    int b = data[texelOffset + p] & 0xFF;
                    return (scale(b >> 5, 7) << 24) | color(b & 0x1F);
                }
                case 2: // PLTT4 (2bpp)
                    return opaque(color(bits(p, 2)));
                case 3: // PLTT16 (4bpp)
                    return opaque(color(bits(p, 4)));
                case 4: // PLTT256 (8bpp)
                    return opaque(color(data[texelOffset + p] & 0xFF));
                case 7: // DIRECT (BGR555)
                {
                    int o = texelOffset + p * 2;
                    int v = (data[o] & 0xFF) | ((data[o + 1] & 0xFF) << 8);
                    return ((v & 0x8000) != 0 ? 0xFF000000 : 0) | bgr555(v);
                }
                default:
                    return 0;
            }
        }

        private int bits(int pixel, int bpp)
        {
            int perByte = 8 / bpp;
            int b = data[texelOffset + pixel / perByte] & 0xFF;
            return (b >> ((pixel % perByte) * bpp)) & ((1 << bpp) - 1);
        }

        private int color(int index)
        {
            int o = paletteOffset + index * 2;
            if (o + 1 >= data.length)
                return 0;
            return bgr555((data[o] & 0xFF) | ((data[o + 1] & 0xFF) << 8));
        }
    }

    private static int opaque(int rgb) { return 0xFF000000 | rgb; }
    private static int scale(int v, int max) { return v * 255 / max; }
    private static int bgr555(int v)
    {
        return (((v & 0x1F) << 3) << 16) | (((v >> 5) & 0x1F) << 3 << 8) | (((v >> 10) & 0x1F) << 3);
    }

    private String magicAt(int o)
    {
        return o + 4 <= data.length ? new String(data, o, 4, StandardCharsets.ISO_8859_1) : "";
    }

    private int u16(int o) { return (data[o] & 0xFF) | ((data[o + 1] & 0xFF) << 8); }
    private int s16(int o) { int v = u16(o); return v >= 0x8000 ? v - 0x10000 : v; }
    private long u32(int o)
    {
        return (data[o] & 0xFFL) | ((data[o + 1] & 0xFFL) << 8) | ((data[o + 2] & 0xFFL) << 16) | ((data[o + 3] & 0xFFL) << 24);
    }
    // Nitro fixed-point: fx32 and fx16 both carry 12 fractional bits (1.19.12 / 1.3.12).
    private double fx32(int o) { return (int) u32(o) / 4096.0; }
    private double fx16(int o) { return s16(o) / 4096.0; }
}
