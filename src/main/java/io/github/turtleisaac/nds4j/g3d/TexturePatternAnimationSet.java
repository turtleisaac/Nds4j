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
import java.util.TreeMap;

/**
 * An object representation of an NSBTP file (a Nitro 3D <b>texture-pattern animation</b> set, magic
 * {@code BTP0}).
 * <p>
 * An NSBTP holds a {@code PAT0} block naming one or more {@link Animation}s; each animation swaps which
 * texture/palette a material samples over time (keyframes of {@code (frame → texture, palette)} per
 * material) &mdash; the flip-book effect used for blinking eyes, spinning coins and the like. Applying
 * it to a {@link Model} means, at frame <em>f</em>, pointing a material at the texture/palette of its
 * latest keyframe at or before <em>f</em>.
 * <p>
 * The container round-trips byte-for-byte (the {@code PAT0} block is preserved verbatim by
 * {@link G3dFile}); the keyframes are parsed as a read-only view over it. Layout reverse-engineered
 * from {@code nitroreader.nsbtp.*}.
 */
public class TexturePatternAnimationSet extends G3dFile
{
    private final List<Animation> animations = new ArrayList<>();

    /**
     * Generates an object representation of an NSBTP file.
     * @param data a <code>byte[]</code> representation of an NSBTP file
     */
    public TexturePatternAnimationSet(byte[] data)
    {
        super("BTP0");
        readContainer(data);
        int pat0 = indexOfBlock("PAT0");
        if (pat0 < 0)
            throw new RuntimeException("Not a valid BTP0 file: missing PAT0 block.");
        parsePat0(block(pat0));
    }

    private void parsePat0(byte[] d)
    {
        MemBuf buf = MemBuf.create(d);
        MemBuf.MemBufReader reader = buf.reader();
        reader.setPosition(8); // PAT0 magic + block size, then the animation dictionary
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
        return String.format("TexturePatternAnimationSet[%d animations]", animations.size());
    }

    /**
     * A single named texture-pattern animation: a frame count and a keyframe map per animated material.
     */
    public static class Animation
    {
        private final String name;
        private final int frameCount;
        private final List<MaterialPattern> materials = new ArrayList<>();

        Animation(byte[] d, int animStart, String name)
        {
            this.name = name;
            // header: char[4] tag, u16 numFrames, u8 numTextures, u8 numPalettes, u16 ofsTexNames,
            //         u16 ofsPltNames; then a material dictionary (8-byte records)
            frameCount = readU16(d, animStart + 4);
            int numTex = d[animStart + 6] & 0xFF;
            int numPlt = d[animStart + 7] & 0xFF;
            int ofsTexNames = readU16(d, animStart + 8);
            int ofsPltNames = readU16(d, animStart + 10);

            String[] texNames = names(d, animStart + ofsTexNames, numTex);
            String[] pltNames = names(d, animStart + ofsPltNames, numPlt);

            MemBuf buf = MemBuf.create(d);
            MemBuf.MemBufReader reader = buf.reader();
            reader.setPosition(animStart + 12);
            G3dDictionary dict = new G3dDictionary(reader);
            for (int i = 0; i < dict.size(); i++)
            {
                byte[] rec = dict.getRecord(i);
                long param1 = readU32(rec, 0), param2 = readU32(rec, 4);
                int numKeyframes = (int) (param1 & 0xFFFF);
                int offset = (int) (param2 >>> 16); // relative to animStart
                TreeMap<Integer, TexturePalette> keyframes = new TreeMap<>();
                int p = animStart + offset;
                for (int k = 0; k < numKeyframes; k++)
                {
                    int frame = readU16(d, p);
                    int texIdx = d[p + 2] & 0xFF;
                    int pltIdx = d[p + 3] & 0xFF;
                    keyframes.put(frame, new TexturePalette(texNames[texIdx], pltNames[pltIdx]));
                    p += 4;
                }
                materials.add(new MaterialPattern(dict.getName(i), keyframes));
            }
        }

        private static String[] names(byte[] d, int at, int count)
        {
            String[] out = new String[count];
            for (int i = 0; i < count; i++)
            {
                int start = at + i * 16, len = 0;
                while (len < 16 && d[start + len] != 0)
                    len++;
                out[i] = new String(d, start, len, java.nio.charset.StandardCharsets.US_ASCII);
            }
            return out;
        }

        /** @return this animation's name */
        public String getName() { return name; }
        /** @return the number of frames */
        public int getFrameCount() { return frameCount; }
        /** @return the per-material pattern tracks */
        public List<MaterialPattern> getMaterials() { return materials; }

        @Override
        public String toString()
        {
            return String.format("Animation[%s, %d frames, %d materials]", name, frameCount, materials.size());
        }
    }

    /** A material's texture-pattern track: keyframes of {@code frame → texture/palette}. */
    public static class MaterialPattern
    {
        private final String name;
        private final TreeMap<Integer, TexturePalette> keyframes;

        MaterialPattern(String name, TreeMap<Integer, TexturePalette> keyframes)
        {
            this.name = name;
            this.keyframes = keyframes;
        }

        /** @return the material's name */
        public String getName() { return name; }
        /** @return the keyframes, sorted by frame (each maps to the texture/palette shown from then on) */
        public TreeMap<Integer, TexturePalette> getKeyframes() { return keyframes; }

        /**
         * @param frame a frame index
         * @return the texture/palette this material shows on that frame (the latest keyframe at or before
         *         it), or null if the animation has no keyframe yet
         */
        public TexturePalette at(int frame)
        {
            java.util.Map.Entry<Integer, TexturePalette> e = keyframes.floorEntry(frame);
            return e != null ? e.getValue() : (keyframes.isEmpty() ? null : keyframes.firstEntry().getValue());
        }
    }

    /** The texture and palette names a material samples at a keyframe. */
    public static class TexturePalette
    {
        private final String texture;
        private final String palette;

        TexturePalette(String texture, String palette)
        {
            this.texture = texture;
            this.palette = palette;
        }

        /** @return the texture name */
        public String getTexture() { return texture; }
        /** @return the palette name */
        public String getPalette() { return palette; }

        @Override
        public String toString() { return texture + "/" + palette; }
    }

    private static int readU16(byte[] d, int o)
    {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    private static long readU32(byte[] d, int o)
    {
        return (d[o] & 0xFFL) | ((d[o + 1] & 0xFFL) << 8) | ((d[o + 2] & 0xFFL) << 16) | ((d[o + 3] & 0xFFL) << 24);
    }
}
