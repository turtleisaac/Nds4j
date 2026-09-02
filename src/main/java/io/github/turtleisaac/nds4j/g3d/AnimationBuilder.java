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
import java.util.ArrayList;
import java.util.List;

/**
 * Authors 3D animation files from scratch &mdash; the writer side of the animation formats, following the
 * same recipe as {@link ModelBuilder}: serialize the tracks, build the NNS resource dictionaries with
 * {@link G3dDictionary#build}, and assemble the container with {@link G3dFile#assembleContainer}. The output
 * is byte-valid and read back by the production decoders.
 * <p>
 * Currently covers <b>NSBTA</b> (texture-SRT: UV scale / rotation / translation over time &mdash; scrolling
 * water, spinning or pulsing textures). Each material's five channels are either {@linkplain Channel#constant
 * constant} or a per-frame {@linkplain Channel#keyframes keyframe array}. The other four animation formats
 * follow the identical pattern (serialize tracks &rarr; build dicts &rarr; assemble); NSBTA is the visual,
 * fully-round-tripped instance of it. See {@link TextureSrtAnimationSet} for the read side and
 * {@link NitroAnimation} to play the result on a {@link Model}.
 */
public final class AnimationBuilder
{
    private AnimationBuilder() {}

    // NNS_G3D_TEXSRTANM_ELEM info bits (mirror of TextureSrtAnimationSet).
    private static final long ELEM_STEP_2 = 0x40000000L, ELEM_STEP_4 = 0x80000000L;
    private static final long ELEM_CONST = 0x20000000L, ELEM_FX16 = 0x10000000L;

    /** One animation channel: a constant value, or a per-frame keyframe array (linearly interpolated). */
    public static final class Channel
    {
        private final boolean constant;
        private final float value;
        private final float[] keys;
        private final int step;

        private Channel(boolean constant, float value, float[] keys, int step)
        {
            this.constant = constant; this.value = value; this.keys = keys; this.step = step;
        }

        /** A channel that holds {@code value} for the whole animation. */
        public static Channel constant(float value) { return new Channel(true, value, null, 1); }

        /**
         * A channel sampled from {@code keys}, one key every {@code step} frames (step 1, 2 or 4), linearly
         * interpolated between keys. For scale/translation the keys are plain values; for the rotation
         * channel they are angles in degrees.
         */
        public static Channel keyframes(float[] keys, int step)
        {
            if (step != 1 && step != 2 && step != 4)
                throw new IllegalArgumentException("keyframe step must be 1, 2 or 4");
            return new Channel(false, 0, keys.clone(), step);
        }
    }

    /** One material's five texture-SRT channels (scale S/T, rotation, translation S/T), targeted by name. */
    public static final class MaterialAnim
    {
        final String name;
        final Channel scaleS, scaleT, rotation, transS, transT;

        public MaterialAnim(String name, Channel scaleS, Channel scaleT, Channel rotation,
                            Channel transS, Channel transT)
        {
            this.name = name;
            this.scaleS = scaleS; this.scaleT = scaleT; this.rotation = rotation;
            this.transS = transS; this.transT = transT;
        }
    }

    /**
     * Authors a single-animation NSBTA (texture-SRT) file.
     * @param animName the animation's dictionary name
     * @param frameCount the animation length in frames
     * @param materials the per-material tracks (each material's name must match the target model's material)
     * @return the NSBTA file bytes, ready for {@link TextureSrtAnimationSet}
     */
    public static byte[] buildTextureSrt(String animName, int frameCount, List<MaterialAnim> materials)
    {
        byte[] animBlock = buildAnimation(frameCount, materials);

        // SRT0 block: "SRT0" + size + animation dictionary (4-byte offset records, block-relative) + the
        // animation block. Measure the dictionary's serialized length so the animation offset is exact.
        int animDictLen = serialize(G3dDictionary.build(List.of(animName), List.of(rec4(0)), 4)).length;
        int animOffset = 8 + animDictLen;
        byte[] animDict = serialize(G3dDictionary.build(List.of(animName), List.of(rec4(animOffset)), 4));

        int srtLen = 8 + animDict.length + animBlock.length;
        byte[] srt0 = new byte[srtLen];
        srt0[0] = 'S'; srt0[1] = 'R'; srt0[2] = 'T'; srt0[3] = '0';
        u32(srt0, 4, srtLen);
        System.arraycopy(animDict, 0, srt0, 8, animDict.length);
        System.arraycopy(animBlock, 0, srt0, animOffset, animBlock.length);

        return G3dFile.assembleContainer("BTA0", 1, srt0);
    }

    // Builds one animation member: tag + frame count + material dictionary (40-byte records) + the variable
    // channels' keyframe arrays. All in-block offsets are relative to the animation member's start.
    private static byte[] buildAnimation(int frameCount, List<MaterialAnim> materials)
    {
        List<String> names = new ArrayList<>();
        for (MaterialAnim m : materials) names.add(m.name);

        // The material dictionary's serialized length is fixed by its record count (40-byte records), so
        // measure it first; the keyframe arrays start right after it.
        List<byte[]> dummy = new ArrayList<>();
        for (int i = 0; i < materials.size(); i++) dummy.add(new byte[40]);
        int dictLen = serialize(G3dDictionary.build(names, dummy, 40)).length;
        int arrayBase = 8 + dictLen; // animation-member-relative offset where keyframe data begins

        ByteArrayOutputStream arrays = new ByteArrayOutputStream();
        List<byte[]> records = new ArrayList<>();
        for (MaterialAnim m : materials)
        {
            byte[] rec = new byte[40];
            encodeScaleTrans(rec, 0, m.scaleS, frameCount, arrayBase, arrays);
            encodeScaleTrans(rec, 8, m.scaleT, frameCount, arrayBase, arrays);
            encodeRotation(rec, 16, m.rotation, frameCount, arrayBase, arrays);
            encodeScaleTrans(rec, 24, m.transS, frameCount, arrayBase, arrays);
            encodeScaleTrans(rec, 32, m.transT, frameCount, arrayBase, arrays);
            records.add(rec);
        }

        byte[] dict = serialize(G3dDictionary.build(names, records, 40));
        byte[] arrayBytes = arrays.toByteArray();

        byte[] out = new byte[8 + dict.length + arrayBytes.length];
        out[0] = 'T'; out[1] = ' ';        // member tag (decoder reads only the frame count)
        u16(out, 4, frameCount);
        System.arraycopy(dict, 0, out, 8, dict.length);
        System.arraycopy(arrayBytes, 0, out, 8 + dict.length, arrayBytes.length);
        return out;
    }

    // Encodes a scale or translation channel into a record's (info, off) pair at recOfs, appending keyframe
    // bytes (fx16) to `arrays` for the variable case and returning the running array cursor via arrayBase.
    private static void encodeScaleTrans(byte[] rec, int recOfs, Channel ch, int frameCount,
                                         int arrayBase, ByteArrayOutputStream arrays)
    {
        if (ch.constant)
        {
            u32(rec, recOfs, ELEM_CONST | ELEM_FX16);
            u32(rec, recOfs + 4, fx16(ch.value) & 0xFFFF);
            return;
        }
        int off = arrayBase + arrays.size();
        int count = valueCount(frameCount, ch.step);
        for (int i = 0; i < count; i++) writeU16(arrays, fx16(sampleKey(ch, i)));
        u32(rec, recOfs, ELEM_FX16 | stepBits(ch.step));
        u32(rec, recOfs + 4, off);
    }

    // Encodes the rotation channel as (sin, cos) fx16 pairs; degrees in, packed pair out.
    private static void encodeRotation(byte[] rec, int recOfs, Channel ch, int frameCount,
                                       int arrayBase, ByteArrayOutputStream arrays)
    {
        if (ch.constant)
        {
            int sin = fx16((float) Math.sin(Math.toRadians(ch.value)));
            int cos = fx16((float) Math.cos(Math.toRadians(ch.value)));
            u32(rec, recOfs, ELEM_CONST);
            u32(rec, recOfs + 4, ((cos & 0xFFFF) << 16) | (sin & 0xFFFF));
            return;
        }
        int off = arrayBase + arrays.size();
        int count = valueCount(frameCount, ch.step);
        for (int i = 0; i < count; i++)
        {
            double rad = Math.toRadians(sampleKey(ch, i));
            writeU16(arrays, fx16((float) Math.sin(rad)));
            writeU16(arrays, fx16((float) Math.cos(rad)));
        }
        u32(rec, recOfs, stepBits(ch.step));
        u32(rec, recOfs + 4, off);
    }

    // Reads the i-th stored key (arrays may hold fewer keys than frames when step > 1).
    private static float sampleKey(Channel ch, int i)
    {
        return ch.keys[Math.min(i, ch.keys.length - 1)];
    }

    private static long stepBits(int step) { return step == 4 ? ELEM_STEP_4 : step == 2 ? ELEM_STEP_2 : 0; }

    // Matches TextureSrtAnimationSet.valueCount so the writer emits exactly as many keys as the reader reads.
    private static int valueCount(int frames, int step)
    {
        return (int) Math.ceil(frames / (double) step) + (frames - 1) % step;
    }

    private static byte[] serialize(G3dDictionary d)
    {
        MemBuf b = MemBuf.create();
        d.write(b.writer());
        return b.reader().getBuffer();
    }

    private static byte[] rec4(long v) { byte[] r = new byte[4]; u32(r, 0, v); return r; }
    private static int fx16(float v) { return Math.round(v * 4096) & 0xFFFF; }
    private static void writeU16(ByteArrayOutputStream o, int v) { o.write(v & 0xFF); o.write((v >> 8) & 0xFF); }
    private static void u16(byte[] d, int o, int v) { d[o] = (byte) v; d[o + 1] = (byte) (v >> 8); }
    private static void u32(byte[] d, int o, long v)
    {
        d[o] = (byte) v; d[o + 1] = (byte) (v >> 8); d[o + 2] = (byte) (v >> 16); d[o + 3] = (byte) (v >> 24);
    }
}
