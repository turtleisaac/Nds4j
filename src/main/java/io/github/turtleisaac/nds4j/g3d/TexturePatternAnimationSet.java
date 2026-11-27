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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /**
     * Assembles an NSBTP file from authored pattern animations &mdash; the writer counterpart to reading.
     * @param animations the animations, in order
     * @param version the NTR container version half-word (1 for a fresh file)
     */
    public static TexturePatternAnimationSet author(List<Animation> animations, int version)
    {
        return new TexturePatternAnimationSet(encode(animations, version));
    }

    /**
     * Re-emits this set's bytes from its parsed structure (the byte-exact writer path, verified to reproduce
     * every retail NSBTP). Distinct from the block-verbatim {@link G3dFile#save()}.
     */
    public byte[] encode()
    {
        return encode(animations, version);
    }

    /**
     * Reads the PAT0 animation dictionary.
     * @param d the PAT0 block
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexPatAnm"
     */
    private void parsePat0(byte[] d)
    {
        // Magic and block size, then one animation per dictionary record.
        MemBuf buf = MemBuf.create(d);
        MemBuf.MemBufReader reader = buf.reader();
        reader.setPosition(8);
        G3dDictionary dict = new G3dDictionary(reader);

        for (int i = 0; i < dict.size(); i++)
        {
            animations.add(new Animation(d, (int) Fx.uint32(dict.getRecord(i), 0), dict.getName(i)));
        }
    }

    // Builds the whole NSBTP: a PAT0 block = magic + size + animation dictionary + each animation's data,
    // wrapped in the NTR container. Each animation is a 12-byte header (tag, numFrames, numTex, numPlt, and
    // the offsets to the texture/palette name tables), then a material dictionary whose 8-byte records give
    // {numKeyframes, 0, ratio = numKeyframes*4096/numFrames, offset-to-keyframes}, then the keyframe arrays
    // (frame, texIdx, pltIdx), then the 16-byte texture and palette name tables.
    private static byte[] encode(List<Animation> animations, int version)
    {
        // PAT0 is the name dictionary followed by each animation's own block.
        List<String> names = patternNames(animations);
        byte[][] blobs = patternBlobs(animations);
        byte[] pat0 = patternBlock(names, blobs);

        return G3dFile.assembleContainer("BTP0", version, pat0);
    }

    /**
     * @param animations the pattern animations
     * @return their names in file order
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexPatAnm"
     */
    private static List<String> patternNames(List<Animation> animations)
    {
        List<String> names = new ArrayList<>();

        for (Animation animation : animations)
            names.add(animation.name);

        return names;
    }

    /**
     * @param animations the pattern animations
     * @return each animation's PAT0 payload
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexPatAnm"
     */
    private static byte[][] patternBlobs(List<Animation> animations)
    {
        byte[][] blobs = new byte[animations.size()][];
        int index = 0;

        while (index < animations.size())
        {
            blobs[index] = animations.get(index).encodeBlob();
            index++;
        }

        return blobs;
    }

    /**
     * @param names animation names
     * @param blobs animation payloads
     * @return the PAT0 block
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexPatAnm"
     */
    private static byte[] patternBlock(List<String> names, byte[][] blobs)
    {
        int dictSize = serialize(G3dDictionary.build(names, placeholders(names.size(), 4), 4)).length;
        int origin = 8 + dictSize;
        byte[] dictionary = serialize(G3dDictionary.build(names, NsbBlock.offsets(blobs, origin), 4));

        return NsbBlock.block("PAT0", NsbBlock.end(blobs, origin), dictionary, blobs);
    }

    private static List<byte[]> placeholders(int n, int recSize)
    {
        List<byte[]> l = new ArrayList<>();
        for (int i = 0; i < n; i++)
            l.add(new byte[recSize]);
        return l;
    }
    private static byte[] serialize(G3dDictionary d)
    {
        MemBuf b = MemBuf.create();
        d.write(b.writer());
        return b.reader().getBuffer();
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
        // the texture/palette name tables in their original order — retained so re-encoding reproduces the
        // exact texIdx/pltIdx each keyframe used (the order is not always first-appearance)
        private final String[] texNames, pltNames;

        Animation(byte[] d, int animStart, String name)
        {
            this.name = name;
            // header: char[4] tag, u16 numFrames, u8 numTextures, u8 numPalettes, u16 ofsTexNames,
            //         u16 ofsPltNames; then a material dictionary (8-byte records)
            frameCount = Fx.uint16(d, animStart + 4);
            int numTex = Fx.uint8(d, animStart + 6);
            int numPlt = Fx.uint8(d, animStart + 7);
            int ofsTexNames = Fx.uint16(d, animStart + 8);
            int ofsPltNames = Fx.uint16(d, animStart + 10);

            String[] texNames = names(d, animStart + ofsTexNames, numTex);
            String[] pltNames = names(d, animStart + ofsPltNames, numPlt);
            this.texNames = texNames;
            this.pltNames = pltNames;

            MemBuf buf = MemBuf.create(d);
            MemBuf.MemBufReader reader = buf.reader();
            reader.setPosition(animStart + 12);
            G3dDictionary dict = new G3dDictionary(reader);
            for (int i = 0; i < dict.size(); i++)
            {
                byte[] rec = dict.getRecord(i);
                long param1 = Fx.uint32(rec, 0), param2 = Fx.uint32(rec, 4);
                int numKeyframes = (int) (param1 & 0xFFFF);
                int offset = (int) (param2 >>> 16); // relative to animStart
                TreeMap<Integer, TexturePalette> keyframes = new TreeMap<>();
                int p = animStart + offset;
                for (int k = 0; k < numKeyframes; k++)
                {
                    int frame = Fx.uint16(d, p);
                    int texIdx = Fx.uint8(d, p + 2);
                    int pltIdx = Fx.uint8(d, p + 3);
                    keyframes.put(frame, new TexturePalette(texNames[texIdx], pltNames[pltIdx]));
                    p += 4;
                }
                materials.add(new MaterialPattern(dict.getName(i), keyframes));
            }
        }

        /**
         * Authors a texture-pattern animation. The texture and palette name tables are derived from the
         * order the names first appear across the materials' keyframes.
         * @param name the animation's name
         * @param frameCount the animation length in frames
         * @param materials the per-material pattern tracks
         */
        public Animation(String name, int frameCount, List<MaterialPattern> materials)
        {
            this.name = name;
            this.frameCount = frameCount;
            this.materials.addAll(materials);
            LinkedHashSet<String> tex = new LinkedHashSet<>(), plt = new LinkedHashSet<>();
            for (MaterialPattern m : materials)
                for (TexturePalette tp : m.keyframes.values())
                {
                    tex.add(tp.getTexture());
                    plt.add(tp.getPalette());
                }
            this.texNames = tex.toArray(new String[0]);
            this.pltNames = plt.toArray(new String[0]);
        }

        // rebuild this animation's data block byte-exactly (header, material dict, keyframe arrays, name tables)
        byte[] encodeBlob()
        {
            // Keyframe arrays sit after the material dictionary; name tables follow them.
            List<String> matNames = patternMaterialNames();
            int dictSize = serialize(G3dDictionary.build(matNames, placeholders(materials.size(), 8), 8)).length;
            KeyLayout keys = keyLayout(12 + dictSize);
            int ofsTex = keys.cursor;
            int ofsPlt = ofsTex + texNames.length * 16;

            return patternAnim(matNames, dictSize, keys, ofsTex, ofsPlt);
        }

        /**
         * @return the material names in file order
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexPatAnm"
         */
        private List<String> patternMaterialNames()
        {
            String[] names = new String[materials.size()];
            int index = names.length;

            while (index > 0)
            {
                index--;
                names[index] = materials.get(index).name;
            }

            return java.util.Arrays.asList(names);
        }

        /**
         * @param start offset of the first keyframe array
         * @return the keyframe bytes and where each material's array begins
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexPatAnm"
         */
        private KeyLayout keyLayout(int start)
        {
            byte[][] blobs = new byte[materials.size()][];
            int[] offsets = new int[materials.size()];
            int cursor = start;
            int index = 0;

            while (index < materials.size())
            {
                blobs[index] = keyBytes(materials.get(index));
                offsets[index] = cursor;
                cursor += blobs[index].length;
                index++;
            }

            return new KeyLayout(blobs, offsets, cursor);
        }

        /**
         * @param material one material's keyframes
         * @return frame, texture index, palette index, packed as 4 bytes each
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexPatAnm"
         */
        private byte[] keyBytes(MaterialPattern material)
        {
            byte[] bytes = new byte[material.keyframes.size() * 4];
            int index = 0;

            for (Map.Entry<Integer, TexturePalette> entry : material.keyframes.entrySet())
            {
                MemBuf.putUInt16(bytes, index * 4, entry.getKey());
                bytes[index * 4 + 2] = (byte) indexOf(texNames, entry.getValue().getTexture());
                bytes[index * 4 + 3] = (byte) indexOf(pltNames, entry.getValue().getPalette());
                index++;
            }

            return bytes;
        }

        /**
         * @param matNames material names
         * @param dictSize material dictionary size
         * @param keys keyframe layout
         * @param ofsTex offset of the texture name table
         * @param ofsPlt offset of the palette name table
         * @return the animation block
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexPatAnm"
         */
        private byte[] patternAnim(List<String> matNames, int dictSize, KeyLayout keys, int ofsTex, int ofsPlt)
        {
            int animSize = ofsPlt + pltNames.length * 16;
            byte[] anim = new byte[animSize];
            anim[0] = 0x4d;
            anim[1] = 0;
            anim[2] = 0x50;
            anim[3] = 0x54;
            MemBuf.putUInt16(anim, 4, frameCount);
            anim[6] = (byte) texNames.length;
            anim[7] = (byte) pltNames.length;
            MemBuf.putUInt16(anim, 8, ofsTex);
            MemBuf.putUInt16(anim, 10, ofsPlt);
            byte[] dictionary = serialize(G3dDictionary.build(matNames, materialRecords(keys), 8));
            System.arraycopy(dictionary, 0, anim, 12, dictSize);
            copyKeys(anim, keys);
            copyNames(anim, ofsTex, texNames);
            copyNames(anim, ofsPlt, pltNames);

            return anim;
        }

        /**
         * @param keys keyframe layout
         * @return one 8-byte dictionary record per material
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexPatAnm"
         */
        private List<byte[]> materialRecords(KeyLayout keys)
        {
            List<byte[]> records = new ArrayList<>();
            int index = 0;

            while (index < materials.size())
            {
                records.add(materialRecord(materials.get(index).keyframes.size(), keys.offsets[index]));
                index++;
            }

            return records;
        }

        /**
         * @param numKf how many keyframes the material has
         * @param offset where that keyframe array starts
         * @return the 8-byte material record
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexPatAnm"
         */
        private byte[] materialRecord(int numKf, int offset)
        {
            byte[] record = new byte[8];
            MemBuf.putUInt16(record, 0, numKf);
            MemBuf.putUInt16(record, 2, 0);
            MemBuf.putUInt16(record, 4, keyRatio(numKf));
            MemBuf.putUInt16(record, 6, offset);

            return record;
        }

        /**
         * @param numKf how many keyframes the material has
         * @return {@code numKf * 4096 / frameCount}, or 0 when the animation has no frames
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexPatAnm"
         */
        private int keyRatio(int numKf)
        {
            if (frameCount == 0)
                return 0;

            return (numKf * 4096) / frameCount;
        }

        /**
         * @param anim the animation block
         * @param keys keyframe layout
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexPatAnm"
         */
        private void copyKeys(byte[] anim, KeyLayout keys)
        {
            for (int index = 0; index < keys.blobs.length; index++)
                System.arraycopy(keys.blobs[index], 0, anim, keys.offsets[index], keys.blobs[index].length);
        }

        /**
         * @param anim the animation block
         * @param at where the first name is written
         * @param names 16-byte name slots
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexPatAnm"
         */
        private static void copyNames(byte[] anim, int at, String[] names)
        {
            for (int index = 0; index < names.length; index++)
                writeName(anim, at + index * 16, names[index]);
        }

        /**
         * Keyframe bytes and the offset of each material's array.
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexPatAnm"
         */
        private static final class KeyLayout
        {
            private final byte[][] blobs;
            private final int[] offsets;
            private final int cursor;

            private KeyLayout(byte[][] blobs, int[] offsets, int cursor)
            {
                this.blobs = blobs;
                this.offsets = offsets;
                this.cursor = cursor;
            }
        }

        private static int indexOf(String[] arr, String s)
        {
            for (int i = 0; i < arr.length; i++)
                if (arr[i].equals(s))
                    return i;
            return 0;
        }
        private static void writeName(byte[] d, int at, String s)
        {
            byte[] b = s.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(b, 0, d, at, Math.min(16, b.length));
        }

        /**
         * @param d the animation bytes
         * @param at start of the 16-byte name table
         * @param count how many names
         * @return the names, in table order
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResName"
         */
        private static String[] names(byte[] d, int at, int count)
        {
            String[] out = new String[count];

            for (int i = 0; i < count; i++)
            {
                out[i] = oneName(d, at + i * 16);
            }

            return out;
        }

        /**
         * @param d the animation bytes
         * @param start start of one 16-byte name
         * @return the name, without the trailing NUL padding
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResName"
         */
        private static String oneName(byte[] d, int start)
        {
            int len = nameLen(d, start);

            return new String(d, start, len, java.nio.charset.StandardCharsets.US_ASCII);
        }

        /**
         * @param d the animation bytes
         * @param start start of one 16-byte name
         * @return the number of bytes before the NUL, at most 16
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResName"
         */
        private static int nameLen(byte[] d, int start)
        {
            int len = 0;

            while (nameContinues(d, start, len))
            {
                len++;
            }

            return len;
        }

        /**
         * @param d the animation bytes
         * @param start start of one 16-byte name
         * @param len bytes already accepted
         * @return whether another byte belongs to the name
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResName"
         */
        private static boolean nameContinues(byte[] d, int start, int len)
        {
            if (len >= 16)
                return false;

            return Fx.uint8(d, start + len) != 0;
        }

        /** @return this animation's name */
        public String getName()
        {
            return name;
        }
        /** @return the number of frames */
        public int getFrameCount()
        {
            return frameCount;
        }
        /** @return the per-material pattern tracks */
        public List<MaterialPattern> getMaterials()
        {
            return materials;
        }

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

        /**
         * A material's texture-pattern track.
         * @param name the material's name
         * @param keyframes {@code frame → texture/palette}, sorted by frame
         */
        public MaterialPattern(String name, TreeMap<Integer, TexturePalette> keyframes)
        {
            this.name = name;
            this.keyframes = keyframes;
        }

        /** @return the material's name */
        public String getName()
        {
            return name;
        }
        /** @return the keyframes, sorted by frame (each maps to the texture/palette shown from then on) */
        public TreeMap<Integer, TexturePalette> getKeyframes()
        {
            return keyframes;
        }

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

        /**
         * @param texture the texture name a material samples at this keyframe
         * @param palette the palette name
         */
        public TexturePalette(String texture, String palette)
        {
            this.texture = texture;
            this.palette = palette;
        }

        /** @return the texture name */
        public String getTexture()
        {
            return texture;
        }
        /** @return the palette name */
        public String getPalette()
        {
            return palette;
        }

        @Override
        public String toString()
        {
            return texture + "/" + palette;
        }
    }



}
