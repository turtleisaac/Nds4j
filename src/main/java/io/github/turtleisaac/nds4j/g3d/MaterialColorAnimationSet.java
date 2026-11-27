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
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/**
 * An object representation of an NSBMA file (a Nitro 3D <b>material-color animation</b> set, magic
 * {@code BMA0}).
 * <p>
 * An NSBMA holds a {@code MAT0} block naming one or more {@link Animation}s; each animation drives a
 * material's lighting colors over time &mdash; the four 15-bit colors (<em>diffuse</em>,
 * <em>ambient</em>, <em>specular</em>, <em>emission</em>) and the 5-bit <em>alpha</em>. Applying it to a
 * {@link Model} means, at frame <em>f</em>, replacing that material's colors with the sampled values
 * (pulsing/glowing effects, the flashing chain in {@code demo_kusari}, fade-ins, and so on).
 * <p>
 * The container round-trips byte-for-byte (the {@code MAT0} block is preserved verbatim by
 * {@link G3dFile}); the tracks are parsed as a read-only view over it. This format is <b>not</b> in the
 * reference jar; the layout was reverse-engineered from the retail files (see the package handoff):
 * per material a 20-byte record of five {@code u32} channels, each {@code value|offset (bits 0-15) |
 * frameCount (bits 16-23) | flags (bits 24-31)}; flag bit {@code 0x20} marks a <em>constant</em> channel
 * (the value is inline), otherwise the low 16 bits are an offset (from the animation start) to a
 * per-frame array &mdash; {@code u16} per frame for the colors, {@code u8} per frame for alpha.
 */
public class MaterialColorAnimationSet extends G3dFile
{
    private final List<Animation> animations = new ArrayList<>();

    // Writes edits back into the live MAT0 block so save() reflects them (see G3dFile.writeBlockU8/U16).
    // Channels are same-size in-place edits, so an unedited file stays byte-exact and an edited one is
    // byte-valid.
    interface BlockWriter
    {
        void u16(int offset, int value);
        void u8(int offset, int value);
    }

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
        BlockWriter writer = new BlockWriter() {
            public void u16(int offset, int value)
            {
                writeBlockU16(mat0, offset, value);
            }
            public void u8(int offset, int value)
            {
                writeBlockU8(mat0, offset, value);
            }
        };
        parseMat0(block(mat0), writer);
    }

    /**
     * Re-emits this set's bytes from its parsed structure (the byte-exact writer path, verified to reproduce
     * every retail NSBMA). Distinct from the block-verbatim {@link G3dFile#save()}.
     */
    public byte[] encode()
    {
        // MAT0 is the name dictionary followed by each animation's own block.
        String[] names = colorNames();
        byte[][] blobs = colorBlobs();

        return colorFile(names, blobs);
    }

    /**
     * @return the animation names in file order
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
     */
    private String[] colorNames()
    {
        String[] names = new String[animations.size()];
        int index = 0;

        while (index < names.length)
        {
            names[index] = animations.get(index).name;
            index++;
        }

        return names;
    }

    /**
     * @return each animation's MAT0 payload
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
     */
    private byte[][] colorBlobs()
    {
        byte[][] blobs = new byte[animations.size()][];
        int index = 0;

        while (index < blobs.length)
        {
            blobs[index] = animations.get(index).encodeBlob();
            index++;
        }

        return blobs;
    }

    /**
     * @param names animation names
     * @param blobs animation payloads
     * @return the BMA0 container
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
     */
    private byte[] colorFile(String[] names, byte[][] blobs)
    {
        List<String> listed = java.util.Arrays.asList(names);
        int origin = 8 + serialize(G3dDictionary.build(listed, placeholders(listed.size(), 4), 4)).length;
        MemBuf mat0 = MemBuf.create();
        MemBuf.MemBufWriter writer = mat0.writer();
        writer.write("MAT0".getBytes(StandardCharsets.US_ASCII));
        writer.write(MemBuf.le32(NsbBlock.end(blobs, origin)));
        writer.write(serialize(G3dDictionary.build(listed, NsbBlock.offsets(blobs, origin), 4)));

        for (byte[] blob : blobs)
            writer.write(blob);

        return G3dFile.assembleContainer("BMA0", version, mat0.reader().getBuffer());
    }

    /**
     * Reads the MAT0 animation dictionary.
     * @param d the MAT0 block
     * @param writer where a later edit writes a constant channel back
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
     */
    private void parseMat0(byte[] d, BlockWriter writer)
    {
        // The dictionary follows the magic and the block size.
        animations.addAll(matAnims(d, matDict(d), writer));
    }

    /**
     * @param d the MAT0 block
     * @return the animation dictionary
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
     */
    private static G3dDictionary matDict(byte[] d)
    {
        MemBuf buf = MemBuf.create(d);
        buf.reader().setPosition(8);

        return new G3dDictionary(buf.reader());
    }

    /**
     * @param d the MAT0 block
     * @param dict the animation dictionary
     * @param writer where a later edit writes a constant channel back
     * @return the animations in dictionary order
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
     */
    private static List<Animation> matAnims(byte[] d, G3dDictionary dict, BlockWriter writer)
    {
        List<Animation> found = new ArrayList<>();
        int i = 0;

        while (i < dict.size())
        {
            found.add(new Animation(d, (int) Fx.uint32(dict.getRecord(i), 0), dict.getName(i), writer));
            i++;
        }

        return found;
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
        return String.format("MaterialColorAnimationSet[%d animations]", animations.size());
    }

    /** A single named material-color animation: a frame count and one {@link MaterialColor} per material. */
    public static class Animation
    {
        private final String name;
        private final int frameCount;
        private final List<MaterialColor> materials = new ArrayList<>();
        private final byte[] header = new byte[8]; // tag, numFrames and flags — retained verbatim

        Animation(byte[] d, int animStart, String name, BlockWriter writer)
        {
            this.name = name;
            // header: char[4] tag, u16 numFrames, u16; then a material dictionary (20-byte records =
            // 5 u32 channels: diffuse, ambient, specular, emission, alpha).
            System.arraycopy(d, animStart, header, 0, 8);
            frameCount = Fx.uint16(d, animStart + 4);
            MemBuf buf = MemBuf.create(d);
            MemBuf.MemBufReader reader = buf.reader();
            reader.setPosition(animStart + 8);
            G3dDictionary dict = new G3dDictionary(reader);
            // records start right after the dictionary header (2 + rawTree(10 + count*4) + 4); each is
            // 20 bytes = five u32 channels. This absolute offset is what an in-place edit writes to.
            int count = dict.size();
            int recordBase = animStart + 8 + 16 + count * 4;
            for (int i = 0; i < count; i++)
            {
                byte[] r = dict.getRecord(i);
                int rec = recordBase + i * 20;
                ColorChannel diffuse = colorChannel(d, animStart, Fx.uint32(r, 0), rec, writer);
                ColorChannel ambient = colorChannel(d, animStart, Fx.uint32(r, 4), rec + 4, writer);
                ColorChannel specular = colorChannel(d, animStart, Fx.uint32(r, 8), rec + 8, writer);
                ColorChannel emission = colorChannel(d, animStart, Fx.uint32(r, 12), rec + 12, writer);
                ScalarChannel alpha = alphaChannel(d, animStart, Fx.uint32(r, 16), rec + 16, writer);
                MaterialColor mat = new MaterialColor(dict.getName(i), diffuse, ambient, specular, emission, alpha);
                // retain the raw channel u32s and each variable channel's raw per-frame bytes, for re-encode
                mat.rawCh = new long[] { Fx.uint32(r, 0), Fx.uint32(r, 4), Fx.uint32(r, 8), Fx.uint32(r, 12), Fx.uint32(r, 16) };
                mat.rawArr = new byte[5][];
                for (int k = 0; k < 5; k++)
                {
                    long ch = mat.rawCh[k];
                    if ((((ch >> 24) & 0xFF) & CONST) != 0)
                        continue; // constant → value is inline
                    int off = (int) (ch & 0xFFFF), len = frameCount * (k < 4 ? 2 : 1);
                    // copyOfRange never throws for a too-large "to" (it zero-pads), only for a "from"
                    // outside the buffer, so this stays safe even for the unrecognized-encoding channels
                    // colorChannel/alphaChannel clamp around below.
                    mat.rawArr[k] = Arrays.copyOfRange(d, animStart + off, animStart + off + len);
                }
                materials.add(mat);
            }
        }

        // rebuild this animation's data block byte-exactly: header, material dict (records with recomputed
        // variable-channel offsets), then the per-frame arrays — all color arrays first (u16/frame), the
        // region padded to 4 bytes, then all alpha arrays (u8/frame). Channels sharing a source array keep
        // sharing it; the arrays are emitted in ascending original-offset order.
        byte[] encodeBlob()
        {
            List<String> matNames = colorMaterialNames();
            int dictSize = serialize(G3dDictionary.build(matNames, placeholders(materials.size(), 20), 20)).length;
            int base = 8 + dictSize;
            TreeMap<Integer, byte[]> colorArrays = new TreeMap<>();
            TreeMap<Integer, byte[]> alphaArrays = new TreeMap<>();
            collectArrays(colorArrays, alphaArrays);
            java.util.Map<Integer, Integer> newOff = new java.util.HashMap<>();
            byte[] pooled = poolArrays(colorArrays, alphaArrays, base, newOff);

            return packColor(matNames, dictSize, base, pooled, newOff);
        }

        /**
         * @return the material names in file order
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
         */
        private List<String> colorMaterialNames()
        {
            String[] names = new String[materials.size()];

            for (int index = 0; index < names.length; index++)
                names[index] = materials.get(index).name;

            return java.util.Arrays.asList(names);
        }

        /**
         * @param colorArrays color keyframe arrays, keyed by their original offset
         * @param alphaArrays alpha keyframe arrays, keyed by their original offset
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
         */
        private void collectArrays(TreeMap<Integer, byte[]> colorArrays, TreeMap<Integer, byte[]> alphaArrays)
        {
            for (MaterialColor material : materials)
                collectOne(material, colorArrays, alphaArrays);
        }

        /**
         * @param material one material
         * @param colorArrays color keyframe arrays
         * @param alphaArrays alpha keyframe arrays
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
         */
        private static void collectOne(MaterialColor material, TreeMap<Integer, byte[]> colorArrays, TreeMap<Integer, byte[]> alphaArrays)
        {
            int channel = 0;

            while (channel < 5)
            {
                keepArray(material, channel, colorArrays, alphaArrays);
                channel++;
            }
        }

        /**
         * @param material one material
         * @param channel which channel
         * @param colorArrays color keyframe arrays
         * @param alphaArrays alpha keyframe arrays
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
         */
        private static void keepArray(MaterialColor material, int channel, TreeMap<Integer, byte[]> colorArrays,
                TreeMap<Integer, byte[]> alphaArrays)
        {
            if (material.rawArr[channel] == null)
                return;

            int off = (int) (material.rawCh[channel] & 0xFFFF);
            arrayMap(channel, colorArrays, alphaArrays).put(off, material.rawArr[channel]);
        }

        /**
         * @param channel which channel
         * @param colorArrays color keyframe arrays
         * @param alphaArrays alpha keyframe arrays
         * @return the map that channel belongs in
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
         */
        private static TreeMap<Integer, byte[]> arrayMap(int channel, TreeMap<Integer, byte[]> colorArrays,
                TreeMap<Integer, byte[]> alphaArrays)
        {
            if (channel < 4)
                return colorArrays;

            return alphaArrays;
        }

        /**
         * @param colorArrays color arrays, written first
         * @param alphaArrays alpha arrays, written after the color region is padded
         * @param base where the pool starts
         * @param newOff original offset to the new offset
         * @return the pooled bytes
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
         */
        private static byte[] poolArrays(TreeMap<Integer, byte[]> colorArrays, TreeMap<Integer, byte[]> alphaArrays, int base,
                java.util.Map<Integer, Integer> newOff)
        {
            MemBuf pooled = MemBuf.create();
            MemBuf.MemBufWriter data = pooled.writer();
            appendArrays(data, colorArrays, base, newOff);
            padColor(data);
            appendArrays(data, alphaArrays, base, newOff);
            padColor(data);

            return pooled.reader().getBuffer();
        }

        /**
         * @param data the channel pool
         * @param arrays arrays in offset order
         * @param base where the pool starts
         * @param newOff original offset to the new offset
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
         */
        private static void appendArrays(MemBuf.MemBufWriter data, TreeMap<Integer, byte[]> arrays, int base,
                java.util.Map<Integer, Integer> newOff)
        {
            for (java.util.Map.Entry<Integer, byte[]> entry : arrays.entrySet())
            {
                newOff.put(entry.getKey(), base + data.getPosition());
                data.write(entry.getValue());
            }
        }

        /**
         * @param data the channel pool
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
         */
        private static void padColor(MemBuf.MemBufWriter data)
        {
            data.align(4);
        }

        /**
         * @param matNames material names
         * @param dictSize material dictionary size
         * @param base where the pool starts
         * @param pooled the channel pool
         * @param newOff original offset to the new offset
         * @return the animation block
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
         */
        private byte[] packColor(List<String> matNames, int dictSize, int base, byte[] pooled, java.util.Map<Integer, Integer> newOff)
        {
            byte[] anim = new byte[base + pooled.length];
            System.arraycopy(header, 0, anim, 0, 8);
            byte[] dictionary = serialize(G3dDictionary.build(matNames, colorRecords(newOff), 20));
            System.arraycopy(dictionary, 0, anim, 8, dictSize);
            System.arraycopy(pooled, 0, anim, base, pooled.length);

            return anim;
        }

        /**
         * @param newOff original offset to the new offset
         * @return one 20-byte record per material
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
         */
        private List<byte[]> colorRecords(java.util.Map<Integer, Integer> newOff)
        {
            List<byte[]> records = new ArrayList<>();
            int index = 0;

            while (index < materials.size())
            {
                records.add(colorRecord(materials.get(index), newOff));
                index++;
            }

            return records;
        }

        /**
         * @param material one material
         * @param newOff original offset to the new offset
         * @return the 20-byte record
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
         */
        private static byte[] colorRecord(MaterialColor material, java.util.Map<Integer, Integer> newOff)
        {
            byte[] record = new byte[20];
            int channel = 0;

            while (channel < 5)
            {
                MemBuf.putUInt32(record, channel * 4, retarget(material, channel, newOff));
                channel++;
            }

            return record;
        }

        /**
         * @param material one material
         * @param channel which channel
         * @param newOff original offset to the new offset
         * @return the channel word, with a variable offset rewritten
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
         */
        private static long retarget(MaterialColor material, int channel, java.util.Map<Integer, Integer> newOff)
        {
            long packed = material.rawCh[channel];

            if (material.rawArr[channel] == null)
                return packed;

            int updated = newOff.get((int) (packed & 0xFFFF)) & 0xFFFF;

            return (packed & ~0xFFFFL) | updated;
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
        /** @return the per-material color tracks */
        public List<MaterialColor> getMaterials()
        {
            return materials;
        }

        @Override
        public String toString()
        {
            return String.format("Animation[%s, %d frames, %d materials]", name, frameCount, materials.size());
        }
    }

    /**
     * A 15-bit color channel, constant or one u16 per frame.
     * @param d the MAT0 block
     * @param animStart start of this animation
     * @param ch the packed channel word
     * @param fieldOffset where a constant channel is written back
     * @param writer the in-place editor
     * @return the channel
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
     */
    private static ColorChannel colorChannel(byte[] d, int animStart, long ch, int fieldOffset, BlockWriter writer)
    {
        if (channelConst(ch))
            return new ColorChannel(new int[] { (int) (ch & 0xFFFF) }, writer, fieldOffset, true);

        return animatedColor(d, animStart, ch, writer);
    }

    /**
     * @param ch the packed channel word
     * @return whether the value is stored in the word itself
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
     */
    private static boolean channelConst(long ch)
    {
        int flags = (int) ((ch >> 24) & 0xFF);

        return (flags & CONST) != 0;
    }

    /**
     * @param d the MAT0 block
     * @param animStart start of this animation
     * @param ch the packed channel word
     * @param writer the in-place editor
     * @return the per-frame color channel
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
     */
    private static ColorChannel animatedColor(byte[] d, int animStart, long ch, BlockWriter writer)
    {
        int value = (int) (ch & 0xFFFF);
        int frames = (int) ((ch >> 16) & 0xFF);
        int p = animStart + value;
        int[] keys = new int[clampFrameCount(d.length, p, Math.max(1, frames), 2)];
        fillColorKeys(d, p, keys);

        return new ColorChannel(keys, writer, p, false);
    }

    /**
     * @param d the MAT0 block
     * @param p start of the u16 keys
     * @param keys filled with each frame, or 0 past the buffer
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
     */
    private static void fillColorKeys(byte[] d, int p, int[] keys)
    {
        for (int i = 0; i < keys.length; i++)
        {
            keys[i] = colorKey(d, p, i);
        }
    }

    /**
     * @param d the MAT0 block
     * @param p start of the u16 keys
     * @param i frame index
     * @return that frame's 15-bit color, or 0 when the pair would run off the buffer
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
     */
    private static int colorKey(byte[] d, int p, int i)
    {
        if (p + i * 2 + 1 >= d.length)
            return 0;

        return Fx.uint16(d, p + i * 2);
    }

    /**
     * A 5-bit alpha channel, constant or one byte per frame.
     * @param d the MAT0 block
     * @param animStart start of this animation
     * @param ch the packed channel word
     * @param fieldOffset where a constant channel is written back
     * @param writer the in-place editor
     * @return the channel
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
     */
    private static ScalarChannel alphaChannel(byte[] d, int animStart, long ch, int fieldOffset, BlockWriter writer)
    {
        if (channelConst(ch))
            return constantAlpha(ch, fieldOffset, writer);

        return animatedAlpha(d, animStart, ch, writer);
    }

    /**
     * @param ch the packed channel word
     * @param fieldOffset where the constant is written back
     * @param writer the in-place editor
     * @return the constant alpha, keeping its low 5 bits
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
     */
    private static ScalarChannel constantAlpha(long ch, int fieldOffset, BlockWriter writer)
    {
        int value = (int) (ch & 0x1F);

        return new ScalarChannel(new int[] { value }, writer, fieldOffset, true);
    }

    /**
     * @param d the MAT0 block
     * @param animStart start of this animation
     * @param ch the packed channel word
     * @param writer the in-place editor
     * @return the per-frame alpha channel
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
     */
    private static ScalarChannel animatedAlpha(byte[] d, int animStart, long ch, BlockWriter writer)
    {
        int value = (int) (ch & 0xFFFF);
        int frames = (int) ((ch >> 16) & 0xFF);
        int p = animStart + value;
        int[] keys = new int[clampFrameCount(d.length, p, Math.max(1, frames), 1)];
        fillAlphaKeys(d, p, keys);

        return new ScalarChannel(keys, writer, p, false);
    }

    /**
     * @param d the MAT0 block
     * @param p start of the bytes
     * @param keys filled with each frame, or 0 past the buffer
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
     */
    private static void fillAlphaKeys(byte[] d, int p, int[] keys)
    {
        for (int i = 0; i < keys.length; i++)
        {
            keys[i] = alphaKey(d, p, i);
        }
    }

    /**
     * @param d the MAT0 block
     * @param p start of the bytes
     * @param i frame index
     * @return that frame's byte, or 0 when it would run off the buffer
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResMatCAnm"
     */
    private static int alphaKey(byte[] d, int p, int i)
    {
        if (p + i >= d.length)
            return 0;

        return Fx.uint8(d, p + i);
    }

    // Some non-Pokemon titles (first seen in Animal Crossing: Wild World and Phantom Hourglass) carry a
    // flags byte (observed 0x40/0x41) this reverse-engineered format doesn't recognize -- neither the
    // documented 0x20 constant bit nor a frame count consistent with a plain per-frame array, since the
    // declared frame count reads past the end of the block. Rather than guess at an undocumented curve
    // encoding (risking a wrong-but-plausible decode), clamp to what the buffer actually holds: the file
    // still round-trips byte-exact regardless (save() re-emits the MAT0 block verbatim), only this one
    // channel's *decoded* values for the truncated tail are unavailable.
    private static int clampFrameCount(int dataLen, int p, int frames, int stride)
    {
        int available = (dataLen - p) / stride;
        return Math.max(1, Math.min(frames, available));
    }

    /** One material's five color tracks; sample each at a frame to drive the material's lighting. */
    public static class MaterialColor
    {
        private final String name;
        private final ColorChannel diffuse, ambient, specular, emission;
        private final ScalarChannel alpha;
        long[] rawCh; // the 5 raw channel u32s, retained for byte-exact re-encode
        byte[][] rawArr; // per channel, the raw per-frame bytes (null for const channels)

        MaterialColor(String name, ColorChannel diffuse, ColorChannel ambient, ColorChannel specular,
                ColorChannel emission, ScalarChannel alpha)
        {
            this.name = name;
            this.diffuse = diffuse;
            this.ambient = ambient;
            this.specular = specular;
            this.emission = emission;
            this.alpha = alpha;
        }

        /** @return the material's name */
        public String getName()
        {
            return name;
        }
        /** @return the diffuse-color track */
        public ColorChannel getDiffuse()
        {
            return diffuse;
        }
        /** @return the ambient-color track */
        public ColorChannel getAmbient()
        {
            return ambient;
        }
        /** @return the specular-color track */
        public ColorChannel getSpecular()
        {
            return specular;
        }
        /** @return the emission-color track */
        public ColorChannel getEmission()
        {
            return emission;
        }
        /** @return the alpha (0&ndash;31) track */
        public ScalarChannel getAlpha()
        {
            return alpha;
        }
    }

    /** A 15-bit ({@code BGR555}) color channel, constant or keyed per frame. Editable in place. */
    public static final class ColorChannel
    {
        private final int[] keys; // one 15-bit color per frame, or a single constant
        private final BlockWriter writer;
        private final int offset; // constant: the channel's u32 field; animated: the array's block offset
        private final boolean constant;

        ColorChannel(int[] keys, BlockWriter writer, int offset, boolean constant)
        {
            this.keys = keys;
            this.writer = writer;
            this.offset = offset;
            this.constant = constant;
        }

        /** @return true if this channel holds one constant color */
        public boolean isConstant()
        {
            return constant;
        }

        /** @param frame a frame index @return the raw 15-bit {@code BGR555} color at that frame */
        public int rawAt(int frame)
        {
            return keys[Math.min(Math.max(frame, 0), keys.length - 1)];
        }

        /** @param frame a frame index @return the color at that frame as {@code 0xRRGGBB} (8-bit per channel) */
        public int rgbAt(int frame)
        {
            return toRgb(rawAt(frame));
        }

        /**
         * Sets the raw 15-bit {@code BGR555} color at {@code frame}, writing it back into the file so
         * {@link #save()} emits a valid, edited NSBMA (a constant channel ignores {@code frame}). The
         * edit is same-size and in place.
         * @param frame the frame to set (ignored for a constant channel)
         * @param raw15 the 15-bit color
         */
        public void setRaw(int frame, int raw15)
        {
            // Keep all 16 bits the color word can hold. BGR555 color lives in the low 15 bits, but some
            // retail NSBMA files set bit 15 of a color word, and the parser preserves it (rawAt returns
            // it) -- masking it off here would make setRaw(rawAt(f)) a lossy no-op and drop a bit the
            // file actually contained. (setRgb always supplies bit 15 = 0, so authoring is unaffected.)
            raw15 &= 0xFFFF;
            if (constant)
            {
                keys[0] = raw15;
                writer.u16(offset, raw15); // low 16 bits of the channel u32 hold the value
            }
            else
            {
                int f = Math.min(Math.max(frame, 0), keys.length - 1);
                keys[f] = raw15;
                writer.u16(offset + f * 2, raw15);
            }
        }

        /** Sets the color from {@code 0xRRGGBB}, quantised to 15-bit. @param frame the frame @param rgb the color */
        public void setRgb(int frame, int rgb)
        {
            int r = ((rgb >> 16) & 0xFF) >> 3;
            int g = ((rgb >> 8) & 0xFF) >> 3;
            int b = (rgb & 0xFF) >> 3;
            setRaw(frame, r | (g << 5) | (b << 10));
        }

        private static int toRgb(int v)
        {
            int r = (v & 0x1F) * 255 / 31;
            int g = ((v >> 5) & 0x1F) * 255 / 31;
            int b = ((v >> 10) & 0x1F) * 255 / 31;
            return (r << 16) | (g << 8) | b;
        }
    }

    /** A scalar channel (alpha, 0&ndash;31), constant or keyed per frame. Editable in place. */
    public static final class ScalarChannel
    {
        private final int[] keys;
        private final BlockWriter writer;
        private final int offset; // constant: the channel's u32 field; animated: the array's block offset
        private final boolean constant;

        ScalarChannel(int[] keys, BlockWriter writer, int offset, boolean constant)
        {
            this.keys = keys;
            this.writer = writer;
            this.offset = offset;
            this.constant = constant;
        }

        /** @return true if this channel holds one constant value */
        public boolean isConstant()
        {
            return constant;
        }

        /** @param frame a frame index @return the value (0&ndash;31) at that frame */
        public int at(int frame)
        {
            return keys[Math.min(Math.max(frame, 0), keys.length - 1)];
        }

        /**
         * Sets the value at {@code frame}, writing it back into the file (a constant channel ignores
         * {@code frame}). Same-size, in place.
         * <p>
         * A constant channel is an inline 5-bit field (0&ndash;31). An animated channel stores one byte
         * per frame, and some retail files hold per-frame values above 31; {@link #at(int)} reports those
         * bytes verbatim (0&ndash;255), so the write-back preserves the full byte width rather than
         * clamping to 31 &mdash; otherwise {@code set(at(f))} would be a lossy no-op and corrupt a
         * keyframe the file actually contained.
         * @param frame the frame to set (ignored for a constant channel)
         * @param value the value; a constant channel keeps its low 5 bits, an animated frame its low 8
         */
        public void set(int frame, int value)
        {
            if (constant)
            {
                keys[0] = value & 0x1F;
                writer.u16(offset, keys[0]); // low 16 bits hold the value; upper bits (frames/flags) unchanged
            }
            else
            {
                value &= 0xFF;
                int f = Math.min(Math.max(frame, 0), keys.length - 1);
                keys[f] = value;
                writer.u8(offset + f, value);
            }
        }
    }

    private static final int CONST = 0x20;

}
