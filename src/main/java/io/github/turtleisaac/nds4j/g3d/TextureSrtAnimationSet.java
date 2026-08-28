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

    private void parseSrt0(byte[] d)
    {
        MemBuf buf = MemBuf.create(d);
        MemBuf.MemBufReader reader = buf.reader();
        reader.setPosition(8); // SRT0 magic + block size, then the animation dictionary
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

        Animation(byte[] d, int animStart, String name)
        {
            this.name = name;
            // header: char[4] tag, u16 numFrames, u8, u8; then a material dictionary (40-byte records:
            // 10 u32 params = an (info, offset/value) pair per channel).
            frameCount = readU16(d, animStart + 4);
            MemBuf buf = MemBuf.create(d);
            MemBuf.MemBufReader reader = buf.reader();
            reader.setPosition(animStart + 8);
            G3dDictionary dict = new G3dDictionary(reader);
            for (int i = 0; i < dict.size(); i++)
            {
                byte[] r = dict.getRecord(i);
                long[] param = new long[10];
                for (int k = 0; k < 10; k++)
                    param[k] = readU32(r, k * 4);
                Channel scaleS = channelST(d, animStart, param[0], param[1], frameCount);
                Channel scaleT = channelST(d, animStart, param[2], param[3], frameCount);
                Channel rot = channelRot(d, animStart, param[4], param[5], frameCount);
                Channel transS = channelST(d, animStart, param[6], param[7], frameCount);
                Channel transT = channelST(d, animStart, param[8], param[9], frameCount);
                materials.add(new MaterialSrt(dict.getName(i), scaleS, scaleT, rot, transS, transT));
            }
        }

        /** @return this animation's name */
        public String getName() { return name; }
        /** @return the number of frames */
        public int getFrameCount() { return frameCount; }
        /** @return the per-material texture-SRT tracks */
        public List<MaterialSrt> getMaterials() { return materials; }

        @Override
        public String toString()
        {
            return String.format("Animation[%s, %d frames, %d materials]", name, frameCount, materials.size());
        }
    }

    // A scale/translation channel: constant (value stored inline in the offset param), or a keyframe
    // array at animStart+offset sampled every `step` frames (fx16 or fx32).
    private static Channel channelST(byte[] d, int animStart, long info, long off, int frameCount)
    {
        boolean fx16 = (info & ELEM_FX16) != 0;
        if ((info & ELEM_CONST) != 0)
            return new Channel(new float[]{fx16 ? (short) (off & 0xFFFF) / 4096f : (int) off / 4096f}, 1);
        int step = frameStep(info);
        int count = valueCount(frameCount, step);
        float[] keys = new float[count];
        int p = animStart + (int) off;
        for (int i = 0; i < count; i++)
            keys[i] = fx16 ? (short) readU16(d, p + i * 2) / 4096f : (int) readU32(d, p + i * 4) / 4096f;
        return new Channel(keys, step);
    }

    // A rotation channel, stored as (sin, cos) fx pairs and exposed in degrees. Constant packs the pair
    // into the offset param; variable is a keyframe array of pairs at animStart+offset.
    private static Channel channelRot(byte[] d, int animStart, long info, long off, int frameCount)
    {
        if ((info & ELEM_CONST) != 0)
            return new Channel(new float[]{angle((short) (off & 0xFFFF), (short) ((off >> 16) & 0xFFFF))}, 1);
        int step = frameStep(info);
        int count = valueCount(frameCount, step);
        float[] keys = new float[count];
        int p = animStart + (int) off;
        for (int i = 0; i < count; i++)
            keys[i] = angle((short) readU16(d, p + i * 4), (short) readU16(d, p + i * 4 + 2));
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

        MaterialSrt(String name, Channel scaleS, Channel scaleT, Channel rot, Channel transS, Channel transT)
        {
            this.name = name;
            this.scaleS = scaleS; this.scaleT = scaleT; this.rot = rot;
            this.transS = transS; this.transT = transT;
        }

        /** @return the material's name */
        public String getName() { return name; }
        /** @param frame a frame index @return the S scale at that frame */
        public float scaleSAt(int frame) { return scaleS.sample(frame); }
        /** @param frame a frame index @return the T scale at that frame */
        public float scaleTAt(int frame) { return scaleT.sample(frame); }
        /** @param frame a frame index @return the rotation in degrees at that frame */
        public float rotationAt(int frame) { return rot.sample(frame); }
        /** @param frame a frame index @return the S translation at that frame */
        public float transSAt(int frame) { return transS.sample(frame); }
        /** @param frame a frame index @return the T translation at that frame */
        public float transTAt(int frame) { return transT.sample(frame); }
    }

    // A single channel: a keyframe array sampled with linear interpolation every `step` frames.
    private static final class Channel
    {
        private final float[] keys;
        private final int step;

        Channel(float[] keys, int step) { this.keys = keys; this.step = step; }

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
        if ((info & ELEM_STEP_4) != 0) return 4;
        if ((info & ELEM_STEP_2) != 0) return 2;
        return 1;
    }

    private static int valueCount(int frames, int step)
    {
        return (int) Math.ceil(frames / (double) step) + (frames - 1) % step;
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
