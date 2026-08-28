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

import io.github.turtleisaac.nds4j.framework.MemBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * An object representation of an NSBMA file (a Nitro 3D <b>material-colour animation</b> set, magic
 * {@code BMA0}).
 * <p>
 * An NSBMA holds a {@code MAT0} block naming one or more {@link Animation}s; each animation drives a
 * material's lighting colours over time &mdash; the four 15-bit colours (<em>diffuse</em>,
 * <em>ambient</em>, <em>specular</em>, <em>emission</em>) and the 5-bit <em>alpha</em>. Applying it to a
 * {@link Model} means, at frame <em>f</em>, replacing that material's colours with the sampled values
 * (pulsing/glowing effects, the flashing chain in {@code demo_kusari}, fade-ins, and so on).
 * <p>
 * The container round-trips byte-for-byte (the {@code MAT0} block is preserved verbatim by
 * {@link G3dFile}); the tracks are parsed as a read-only view over it. This format is <b>not</b> in the
 * reference jar; the layout was reverse-engineered from the retail files (see the package handoff):
 * per material a 20-byte record of five {@code u32} channels, each {@code value|offset (bits 0-15) |
 * frameCount (bits 16-23) | flags (bits 24-31)}; flag bit {@code 0x20} marks a <em>constant</em> channel
 * (the value is inline), otherwise the low 16 bits are an offset (from the animation start) to a
 * per-frame array &mdash; {@code u16} per frame for the colours, {@code u8} per frame for alpha.
 */
public class MaterialColorAnimationSet extends G3dFile
{
    private final List<Animation> animations = new ArrayList<>();

    /**
     * Generates an object representation of an NSBMA file.
     * @param data a <code>byte[]</code> representation of an NSBMA file
     */
    public MaterialColorAnimationSet(byte[] data)
    {
        super("BMA0");
        readContainer(data);
        int mat0 = indexOfBlock("MAT0");
        if (mat0 < 0)
            throw new RuntimeException("Not a valid BMA0 file: missing MAT0 block.");
        parseMat0(block(mat0));
    }

    private void parseMat0(byte[] d)
    {
        MemBuf buf = MemBuf.create(d);
        MemBuf.MemBufReader reader = buf.reader();
        reader.setPosition(8); // MAT0 magic + block size, then the animation dictionary
        G3dDictionary dict = new G3dDictionary(reader);
        for (int i = 0; i < dict.size(); i++)
            animations.add(new Animation(d, (int) readU32(dict.getRecord(i), 0), dict.getName(i)));
    }

    /**
     * Gets the animations in this file.
     * @return a <code>List</code> of {@link Animation}
     */
    public List<Animation> getAnimations()
    {
        return animations;
    }

    // save(), equals(), hashCode() are inherited from G3dFile (block-level, byte-exact).

    @Override
    public String toString()
    {
        return String.format("MaterialColorAnimationSet[%d animations]", animations.size());
    }

    /** A single named material-colour animation: a frame count and one {@link MaterialColor} per material. */
    public static class Animation
    {
        private final String name;
        private final int frameCount;
        private final List<MaterialColor> materials = new ArrayList<>();

        Animation(byte[] d, int animStart, String name)
        {
            this.name = name;
            // header: char[4] tag, u16 numFrames, u16; then a material dictionary (20-byte records =
            // 5 u32 channels: diffuse, ambient, specular, emission, alpha).
            frameCount = readU16(d, animStart + 4);
            MemBuf buf = MemBuf.create(d);
            MemBuf.MemBufReader reader = buf.reader();
            reader.setPosition(animStart + 8);
            G3dDictionary dict = new G3dDictionary(reader);
            for (int i = 0; i < dict.size(); i++)
            {
                byte[] r = dict.getRecord(i);
                ColorChannel diffuse = colorChannel(d, animStart, readU32(r, 0));
                ColorChannel ambient = colorChannel(d, animStart, readU32(r, 4));
                ColorChannel specular = colorChannel(d, animStart, readU32(r, 8));
                ColorChannel emission = colorChannel(d, animStart, readU32(r, 12));
                ScalarChannel alpha = alphaChannel(d, animStart, readU32(r, 16));
                materials.add(new MaterialColor(dict.getName(i), diffuse, ambient, specular, emission, alpha));
            }
        }

        /** @return this animation's name */
        public String getName() { return name; }
        /** @return the number of frames */
        public int getFrameCount() { return frameCount; }
        /** @return the per-material colour tracks */
        public List<MaterialColor> getMaterials() { return materials; }

        @Override
        public String toString()
        {
            return String.format("Animation[%s, %d frames, %d materials]", name, frameCount, materials.size());
        }
    }

    // A 15-bit colour channel: constant (value inline) or a per-frame u16 array at animStart+offset.
    private static ColorChannel colorChannel(byte[] d, int animStart, long ch)
    {
        int value = (int) (ch & 0xFFFF);
        int frames = (int) ((ch >> 16) & 0xFF);
        int flags = (int) ((ch >> 24) & 0xFF);
        if ((flags & CONST) != 0)
            return new ColorChannel(new int[]{value});
        int[] keys = new int[Math.max(1, frames)];
        int p = animStart + value;
        for (int i = 0; i < keys.length; i++)
            keys[i] = readU16(d, p + i * 2);
        return new ColorChannel(keys);
    }

    // A 5-bit alpha channel: constant (value inline) or a per-frame u8 array at animStart+offset.
    private static ScalarChannel alphaChannel(byte[] d, int animStart, long ch)
    {
        int value = (int) (ch & 0xFFFF);
        int frames = (int) ((ch >> 16) & 0xFF);
        int flags = (int) ((ch >> 24) & 0xFF);
        if ((flags & CONST) != 0)
            return new ScalarChannel(new int[]{value & 0x1F});
        int[] keys = new int[Math.max(1, frames)];
        int p = animStart + value;
        for (int i = 0; i < keys.length; i++)
            keys[i] = d[p + i] & 0xFF;
        return new ScalarChannel(keys);
    }

    /** One material's five colour tracks; sample each at a frame to drive the material's lighting. */
    public static class MaterialColor
    {
        private final String name;
        private final ColorChannel diffuse, ambient, specular, emission;
        private final ScalarChannel alpha;

        MaterialColor(String name, ColorChannel diffuse, ColorChannel ambient, ColorChannel specular,
                      ColorChannel emission, ScalarChannel alpha)
        {
            this.name = name;
            this.diffuse = diffuse; this.ambient = ambient; this.specular = specular;
            this.emission = emission; this.alpha = alpha;
        }

        /** @return the material's name */
        public String getName() { return name; }
        /** @return the diffuse-colour track */
        public ColorChannel getDiffuse() { return diffuse; }
        /** @return the ambient-colour track */
        public ColorChannel getAmbient() { return ambient; }
        /** @return the specular-colour track */
        public ColorChannel getSpecular() { return specular; }
        /** @return the emission-colour track */
        public ColorChannel getEmission() { return emission; }
        /** @return the alpha (0&ndash;31) track */
        public ScalarChannel getAlpha() { return alpha; }
    }

    /** A 15-bit ({@code BGR555}) colour channel, constant or keyed per frame. */
    public static final class ColorChannel
    {
        private final int[] keys; // one 15-bit colour per frame, or a single constant

        ColorChannel(int[] keys) { this.keys = keys; }

        /** @return true if this channel holds one constant colour */
        public boolean isConstant() { return keys.length == 1; }

        /** @param frame a frame index @return the raw 15-bit {@code BGR555} colour at that frame */
        public int rawAt(int frame)
        {
            return keys[Math.min(Math.max(frame, 0), keys.length - 1)];
        }

        /** @param frame a frame index @return the colour at that frame as {@code 0xRRGGBB} (8-bit per channel) */
        public int rgbAt(int frame)
        {
            int v = rawAt(frame);
            int r = (v & 0x1F) * 255 / 31;
            int g = ((v >> 5) & 0x1F) * 255 / 31;
            int b = ((v >> 10) & 0x1F) * 255 / 31;
            return (r << 16) | (g << 8) | b;
        }
    }

    /** A scalar channel (alpha, 0&ndash;31), constant or keyed per frame. */
    public static final class ScalarChannel
    {
        private final int[] keys;

        ScalarChannel(int[] keys) { this.keys = keys; }

        /** @return true if this channel holds one constant value */
        public boolean isConstant() { return keys.length == 1; }

        /** @param frame a frame index @return the value (0&ndash;31) at that frame */
        public int at(int frame)
        {
            return keys[Math.min(Math.max(frame, 0), keys.length - 1)];
        }
    }

    private static final int CONST = 0x20;

    private static int readU16(byte[] d, int o)
    {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    private static long readU32(byte[] d, int o)
    {
        return (d[o] & 0xFFL) | ((d[o + 1] & 0xFFL) << 8) | ((d[o + 2] & 0xFFL) << 16) | ((d[o + 3] & 0xFFL) << 24);
    }
}
