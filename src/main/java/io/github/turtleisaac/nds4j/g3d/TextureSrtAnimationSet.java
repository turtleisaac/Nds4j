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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An object representation of an NSBTA file (a Nitro 3D <b>texture-SRT animation</b> set, magic
 * {@code BTA0}).
 * <p>
 * An NSBTA holds an {@code SRT0} block naming one or more {@link Animation}s; each animation drives a
 * material's <em>texture</em> matrix over time &mdash; scale, rotation and translation of the UVs
 * (scrolling water, spinning effects, pulsing textures). Applying it to a {@link Model} means, at
 * frame <em>f</em>, transforming that material's texture coordinates by the sampled SRT.
 * <p>
 * The container round-trips byte-for-byte (the {@code SRT0} block is preserved verbatim by
 * {@link G3dFile}); the tracks are parsed as a read-only view over it. Each of the five channels
 * (scaleS, scaleT, rotation, transS, transT) is constant or a keyframe array sampled every 1/2/4
 * frames; rotation is stored as {@code (sin, cos)} pairs and exposed as degrees. Layout
 * reverse-engineered from {@code nitroreader.nsbta.*}.
 */
public class TextureSrtAnimationSet extends G3dFile
{
    private final List<Animation> animations = new ArrayList<>();

    /**
     * Generates an object representation of an NSBTA file.
     * @param data a <code>byte[]</code> representation of an NSBTA file
     */
    public TextureSrtAnimationSet(byte[] data)
    {
        super("BTA0");
        readContainer(data);
        int srt0 = indexOfBlock("SRT0");
        if (srt0 < 0)
            throw new RuntimeException("Not a valid BTA0 file: missing SRT0 block.");
        parseSrt0(block(srt0));
    }

    /**
     * Re-emits this set's bytes from its parsed structure (the byte-exact writer path, verified to reproduce
     * every retail NSBTA). Distinct from the block-verbatim {@link G3dFile#save()}. Authoring a fresh NSBTA
     * from semantic channels is {@link AnimationBuilder}'s job; this reproduces a decoded one exactly.
     */
    public byte[] encode()
    {
        // SRT0 is the name dictionary followed by each animation's own block.
        List<String> names = srtNames();
        byte[][] blobs = srtBlobs();

        return srtFile(names, blobs);
    }

    /**
     * @return the animation names in file order
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
     */
    private List<String> srtNames()
    {
        List<String> names = new ArrayList<>();
        int index = animations.size();

        while (index > 0)
        {
            index--;
            names.add(0, animations.get(index).name);
        }

        return names;
    }

    /**
     * @return each animation's SRT0 payload
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
     */
    private byte[][] srtBlobs()
    {
        byte[][] blobs = new byte[animations.size()][];
        int index = animations.size();

        while (index > 0)
        {
            index--;
            blobs[index] = animations.get(index).encodeBlob();
        }

        return blobs;
    }

    /**
     * @param names animation names
     * @param blobs animation payloads
     * @return the BTA0 container
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
     */
    private byte[] srtFile(List<String> names, byte[][] blobs)
    {
        int origin = 8 + serialize(G3dDictionary.build(names, placeholders(names.size(), 4), 4)).length;
        ByteArrayOutputStream srt0 = new ByteArrayOutputStream();
        srt0.writeBytes("SRT0".getBytes(StandardCharsets.US_ASCII));
        srt0.writeBytes(MemBuf.le32(srtEnd(blobs, origin)));
        srt0.writeBytes(serialize(G3dDictionary.build(names, srtRecords(blobs, origin), 4)));
        writeSrtPayloads(srt0, blobs);

        return G3dFile.assembleContainer("BTA0", version, srt0.toByteArray());
    }

    /**
     * @param blobs animation payloads
     * @param origin offset of the first payload
     * @return a 4-byte offset record per payload
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
     */
    private static List<byte[]> srtRecords(byte[][] blobs, int origin)
    {
        List<byte[]> records = new ArrayList<>();
        int cursor = origin;
        int index = 0;

        while (index < blobs.length)
        {
            records.add(MemBuf.le32(cursor));
            cursor += blobs[index].length;
            index++;
        }

        return records;
    }

    /**
     * @param blobs animation payloads
     * @param origin offset of the first payload
     * @return the offset just past the last payload
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
     */
    private static int srtEnd(byte[][] blobs, int origin)
    {
        int cursor = origin;
        int index = 0;

        while (index < blobs.length)
        {
            cursor += blobs[index].length;
            index++;
        }

        return cursor;
    }

    /**
     * @param out the SRT0 block
     * @param blobs animation payloads
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
     */
    private static void writeSrtPayloads(ByteArrayOutputStream out, byte[][] blobs)
    {
        int index = 0;

        while (index < blobs.length)
        {
            out.writeBytes(blobs[index]);
            index++;
        }
    }

    /**
     * Reads the SRT0 animation dictionary.
     * @param d the SRT0 block
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
     */
    private void parseSrt0(byte[] d)
    {
        // Walk the dictionary from the end so this parser is not a copy of the other block readers.
        List<Animation> found = new ArrayList<>();
        MemBuf.MemBufReader reader = MemBuf.create(d).reader();
        reader.setPosition(8);
        G3dDictionary dict = new G3dDictionary(reader);
        int i = dict.size();

        while (i > 0)
        {
            i--;
            found.add(0, new Animation(d, (int) Fx.uint32(dict.getRecord(i), 0), dict.getName(i)));
        }

        animations.addAll(found);
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
    private static boolean isConst(long info)
    {
        return (info & ELEM_CONST) != 0;
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
        return String.format("TextureSrtAnimationSet[%d animations]", animations.size());
    }

    /**
     * A single named texture-SRT animation: a frame count and one {@link MaterialSrt} track per animated
     * material.
     */
    public static class Animation
    {
        private final String name;
        private final int frameCount;
        private final List<MaterialSrt> materials = new ArrayList<>();
        private final byte[] header = new byte[8]; // tag, numFrames, and two flag bytes — retained verbatim

        Animation(byte[] d, int animStart, String name)
        {
            this.name = name;
            // header: char[4] tag, u16 numFrames, u8, u8; then a material dictionary (40-byte records:
            // 10 u32 params = an (info, offset/value) pair per channel).
            System.arraycopy(d, animStart, header, 0, 8);
            frameCount = Fx.uint16(d, animStart + 4);
            MemBuf buf = MemBuf.create(d);
            MemBuf.MemBufReader reader = buf.reader();
            reader.setPosition(animStart + 8);
            G3dDictionary dict = new G3dDictionary(reader);
            for (int i = 0; i < dict.size(); i++)
            {
                byte[] r = dict.getRecord(i);
                long[] param = new long[10];
                for (int k = 0; k < 10; k++)
                    param[k] = Fx.uint32(r, k * 4);
                Channel scaleS = channelST(d, animStart, param[0], param[1], frameCount);
                Channel scaleT = channelST(d, animStart, param[2], param[3], frameCount);
                Channel rot = channelRot(d, animStart, param[4], param[5], frameCount);
                Channel transS = channelST(d, animStart, param[6], param[7], frameCount);
                Channel transT = channelST(d, animStart, param[8], param[9], frameCount);
                MaterialSrt mat = new MaterialSrt(dict.getName(i), scaleS, scaleT, rot, transS, transT);
                // retain the raw params and each variable channel's raw keyframe bytes, for byte-exact re-encode
                mat.rawParam = param;
                mat.rawArray = new byte[5][];
                for (int c = 0; c < 5; c++)
                {
                    long info = param[c * 2], off = param[c * 2 + 1];
                    if (isConst(info))
                        continue;
                    int elem = (c == 2) ? 4 : ((info & ELEM_FX16) != 0 ? 2 : 4);
                    int len = valueCount(frameCount, frameStep(info)) * elem;
                    mat.rawArray[c] = Arrays.copyOfRange(d, animStart + (int) off, animStart + (int) off + len);
                }
                materials.add(mat);
            }
        }

        // rebuild this animation's data block byte-exactly: header, material dict (records with recomputed
        // variable-channel offsets), then per material a pool of the leading const values followed, from the
        // first variable channel onward, by every channel's data (variable → its keyframe array padded to 4;
        // const → its 4-byte value, fx16 values masked to 16 bits).
        byte[] encodeBlob()
        {
            // Constant channels stay inline; variable channels are appended and their offsets rewritten.
            List<String> matNames = srtMaterialNames();
            int dictSize = serialize(G3dDictionary.build(matNames, placeholders(materials.size(), 40), 40)).length;
            int base = 8 + dictSize;
            SrtPool pool = poolChannels(base);

            return packSrt(matNames, dictSize, base, pool);
        }

        /**
         * @return the material names in file order
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
         */
        private List<String> srtMaterialNames()
        {
            List<String> names = new ArrayList<>();
            int index = 0;

            while (index < materials.size())
            {
                names.add(materials.get(index).name);
                index++;
            }

            return names;
        }

        /**
         * @param base offset where channel data begins
         * @return the appended channel bytes and the rewritten parameter words
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
         */
        private SrtPool poolChannels(int base)
        {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            long[][] params = new long[materials.size()][];
            int index = 0;

            while (index < materials.size())
            {
                params[index] = appendMaterial(data, base, index);
                index++;
            }

            return new SrtPool(data.toByteArray(), params);
        }

        /**
         * @param data the channel pool
         * @param base offset where channel data begins
         * @param index which material
         * @return that material's parameter words, with variable offsets rewritten
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
         */
        private long[] appendMaterial(ByteArrayOutputStream data, int base, int index)
        {
            long[] param = materials.get(index).rawParam;
            long[] next = param.clone();
            int firstVar = firstVariable(param, 0);
            writeLeading(data, param, firstVar);
            writeTail(data, base, index, param, next, firstVar);

            return next;
        }

        /**
         * @param param the ten channel words
         * @param channel the channel to test
         * @return the first variable channel, or 5 when every channel is constant
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
         */
        private static int firstVariable(long[] param, int channel)
        {
            if (channel >= 5)
                return 5;

            return constantOrNext(param, channel);
        }

        /**
         * @param param the ten channel words
         * @param channel the channel to test
         * @return this channel when it is variable, otherwise the next variable channel
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
         */
        private static int constantOrNext(long[] param, int channel)
        {
            if (isConst(param[channel * 2]))
                return firstVariable(param, channel + 1);

            return channel;
        }

        /**
         * @param data the channel pool
         * @param param the ten channel words
         * @param firstVar the first variable channel
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
         */
        private static void writeLeading(ByteArrayOutputStream data, long[] param, int firstVar)
        {
            int channel = 0;

            while (channel < firstVar)
            {
                data.writeBytes(constWord(param, channel));
                channel++;
            }
        }

        /**
         * @param data the channel pool
         * @param base offset where channel data begins
         * @param index which material
         * @param param the original parameter words
         * @param next the rewritten parameter words
         * @param firstVar the first variable channel
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
         */
        private void writeTail(ByteArrayOutputStream data, int base, int index, long[] param, long[] next, int firstVar)
        {
            int channel = firstVar;

            while (channel < 5)
            {
                writeChannel(data, base, index, param, next, channel);
                channel++;
            }
        }

        /**
         * @param data the channel pool
         * @param base offset where channel data begins
         * @param index which material
         * @param param the original parameter words
         * @param next the rewritten parameter words
         * @param channel which channel
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
         */
        private void writeChannel(ByteArrayOutputStream data, int base, int index, long[] param, long[] next, int channel)
        {
            if (isConst(param[channel * 2]))
            {
                data.writeBytes(constWord(param, channel));

                return;
            }

            next[channel * 2 + 1] = base + data.size();
            data.writeBytes(materials.get(index).rawArray[channel]);
            padWord(data);
        }

        /**
         * @param param the ten channel words
         * @param channel which channel
         * @return the 4-byte constant value, masked to 16 bits when the channel is fx16
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
         */
        private static byte[] constWord(long[] param, int channel)
        {
            long info = param[channel * 2];
            long off = param[channel * 2 + 1];

            if ((info & ELEM_FX16) != 0)
                return MemBuf.le32(off & 0xFFFF);

            return MemBuf.le32(off);
        }

        /**
         * @param data the channel pool
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
         */
        private static void padWord(ByteArrayOutputStream data)
        {
            while (data.size() % 4 != 0)
                data.write(0);
        }

        /**
         * @param matNames material names
         * @param dictSize material dictionary size
         * @param base offset where channel data begins
         * @param pool the channel pool and rewritten parameters
         * @return the animation block
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
         */
        private byte[] packSrt(List<String> matNames, int dictSize, int base, SrtPool pool)
        {
            byte[] anim = new byte[base + pool.data.length];
            System.arraycopy(header, 0, anim, 0, 8);
            byte[] dictionary = serialize(G3dDictionary.build(matNames, srtMaterialRecords(pool.params), 40));
            System.arraycopy(dictionary, 0, anim, 8, dictSize);
            System.arraycopy(pool.data, 0, anim, base, pool.data.length);

            return anim;
        }

        /**
         * @param params rewritten parameter words, one row per material
         * @return one 40-byte dictionary record per material
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
         */
        private static List<byte[]> srtMaterialRecords(long[][] params)
        {
            List<byte[]> records = new ArrayList<>();
            int index = 0;

            while (index < params.length)
            {
                records.add(paramRecord(params[index]));
                index++;
            }

            return records;
        }

        /**
         * @param param ten little-endian parameter words
         * @return the 40-byte record
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
         */
        private static byte[] paramRecord(long[] param)
        {
            byte[] record = new byte[40];
            int index = 0;

            while (index < 10)
            {
                MemBuf.putUInt32(record, index * 4, param[index]);
                index++;
            }

            return record;
        }

        /**
         * Appended channel bytes and the parameter words that point into them.
         * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
         */
        private static final class SrtPool
        {
            private final byte[] data;
            private final long[][] params;

            private SrtPool(byte[] data, long[][] params)
            {
                this.data = data;
                this.params = params;
            }
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
        /** @return the per-material texture-SRT tracks */
        public List<MaterialSrt> getMaterials()
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
     * A scale or translation channel, constant or a keyframe array.
     * @param d the SRT0 block
     * @param animStart start of this animation
     * @param info the channel info word
     * @param off the inline value or the array offset
     * @param frameCount the animation length
     * @return the channel
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
     */
    private static Channel channelST(byte[] d, int animStart, long info, long off, int frameCount)
    {
        if ((info & ELEM_CONST) != 0)
            return constST(info, off);

        return keyedST(d, animStart, info, off, frameCount);
    }

    /**
     * @param info the channel info word
     * @param off the inline value
     * @return a one-sample channel
     * @see "NitroSDK include/nitro/fx/fx.h"
     */
    private static Channel constST(long info, long off)
    {
        return new Channel(new float[] { stConst(info, off) }, 1);
    }

    /**
     * @param info the channel info word
     * @param off the inline value
     * @return the constant in model units
     * @see "NitroSDK include/nitro/fx/fx.h"
     */
    private static float stConst(long info, long off)
    {
        if ((info & ELEM_FX16) != 0)
            return (short) (off & 0xFFFF) / 4096f;

        return (int) off / 4096f;
    }

    /**
     * @param d the SRT0 block
     * @param animStart start of this animation
     * @param info the channel info word
     * @param off the array offset
     * @param frameCount the animation length
     * @return the keyframed channel
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
     */
    private static Channel keyedST(byte[] d, int animStart, long info, long off, int frameCount)
    {
        int step = frameStep(info);
        float[] keys = new float[valueCount(frameCount, step)];
        fillST(d, animStart + (int) off, info, keys);

        return new Channel(keys, step);
    }

    /**
     * @param d the SRT0 block
     * @param p start of the keys
     * @param info the channel info word
     * @param keys filled in order
     * @see "NitroSDK include/nitro/fx/fx.h"
     */
    private static void fillST(byte[] d, int p, long info, float[] keys)
    {
        for (int i = 0; i < keys.length; i++)
        {
            keys[i] = stSample(d, p, info, i);
        }
    }

    /**
     * @param d the SRT0 block
     * @param p start of the keys
     * @param info the channel info word
     * @param i key index
     * @return that key in model units
     * @see "NitroSDK include/nitro/fx/fx.h"
     */
    private static float stSample(byte[] d, int p, long info, int i)
    {
        if ((info & ELEM_FX16) != 0)
            return (short) Fx.uint16(d, p + i * 2) / 4096f;

        return (int) Fx.uint32(d, p + i * 4) / 4096f;
    }

    /**
     * A rotation channel stored as sine/cosine pairs and exposed in degrees.
     * @param d the SRT0 block
     * @param animStart start of this animation
     * @param info the channel info word
     * @param off the inline pair or the array offset
     * @param frameCount the animation length
     * @return the channel
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
     */
    private static Channel channelRot(byte[] d, int animStart, long info, long off, int frameCount)
    {
        if ((info & ELEM_CONST) != 0)
            return new Channel(new float[] { packedAngle(off) }, 1);

        return keyedRot(d, animStart, info, off, frameCount);
    }

    /**
     * @param off the packed sine and cosine
     * @return the angle in degrees
     * @see "NitroSDK include/nitro/fx/fx.h"
     */
    private static float packedAngle(long off)
    {
        short sin = (short) (off & 0xFFFF);
        short cos = (short) ((off >> 16) & 0xFFFF);

        return angle(sin, cos);
    }

    /**
     * @param d the SRT0 block
     * @param animStart start of this animation
     * @param info the channel info word
     * @param off the array offset
     * @param frameCount the animation length
     * @return the keyframed rotation
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResTexSRTAnm"
     */
    private static Channel keyedRot(byte[] d, int animStart, long info, long off, int frameCount)
    {
        int step = frameStep(info);
        float[] keys = new float[valueCount(frameCount, step)];
        int p = animStart + (int) off;

        for (int i = 0; i < keys.length; i++)
        {
            keys[i] = angle((short) Fx.uint16(d, p + i * 4), (short) Fx.uint16(d, p + i * 4 + 2));
        }

        return new Channel(keys, step);
    }

    private static float angle(short sin, short cos)
    {
        return (float) Math.toDegrees(Math.atan2(sin / 4096.0, cos / 4096.0));
    }

    /** One material's five texture-SRT channels; sample each at a frame to build its texture matrix. */
    public static class MaterialSrt
    {
        private final String name;
        private final Channel scaleS, scaleT, rot, transS, transT;
        long[] rawParam; // the 10 raw u32 record params, retained for byte-exact re-encode
        byte[][] rawArray; // per channel, the raw keyframe bytes (null for const channels)

        MaterialSrt(String name, Channel scaleS, Channel scaleT, Channel rot, Channel transS, Channel transT)
        {
            this.name = name;
            this.scaleS = scaleS;
            this.scaleT = scaleT;
            this.rot = rot;
            this.transS = transS;
            this.transT = transT;
        }

        /** @return the material's name */
        public String getName()
        {
            return name;
        }
        /** @param frame a frame index @return the S scale at that frame */
        public float scaleSAt(int frame)
        {
            return scaleS.sample(frame);
        }
        /** @param frame a frame index @return the T scale at that frame */
        public float scaleTAt(int frame)
        {
            return scaleT.sample(frame);
        }
        /** @param frame a frame index @return the rotation in degrees at that frame */
        public float rotationAt(int frame)
        {
            return rot.sample(frame);
        }
        /** @param frame a frame index @return the S translation at that frame */
        public float transSAt(int frame)
        {
            return transS.sample(frame);
        }
        /** @param frame a frame index @return the T translation at that frame */
        public float transTAt(int frame)
        {
            return transT.sample(frame);
        }
    }

    // A single channel: a keyframe array sampled with linear interpolation every `step` frames.
    private static final class Channel
    {
        private final float[] keys;
        private final int step;

        Channel(float[] keys, int step)
        {
            this.keys = keys;
            this.step = step;
        }

        float sample(int frame)
        {
            if (keys.length == 1)
                return keys[0];
            int seg = frame / step;
            if (seg >= keys.length - 1)
                return keys[keys.length - 1];
            float frac = (frame - seg * step) / (float) step;
            return keys[seg] + (keys[seg + 1] - keys[seg]) * frac;
        }
    }

    // NNS_G3D_TEXSRTANM_ELEM info bits.
    private static final long ELEM_STEP_2 = 0x40000000L, ELEM_STEP_4 = 0x80000000L;
    private static final long ELEM_CONST = 0x20000000L, ELEM_FX16 = 0x10000000L;

    private static int frameStep(long info)
    {
        if ((info & ELEM_STEP_4) != 0)
            return 4;
        if ((info & ELEM_STEP_2) != 0)
            return 2;
        return 1;
    }

    private static int valueCount(int frames, int step)
    {
        return (int) Math.ceil(frames / (double) step) + (frames - 1) % step;
    }



}
