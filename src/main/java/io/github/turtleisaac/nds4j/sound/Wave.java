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

package io.github.turtleisaac.nds4j.sound;

import java.util.Arrays;

/**
 * A single sampled waveform — a Nintendo DS "SWAV", or one entry inside a {@link WaveArchive SWAR}. The
 * DS sound hardware plays three sample encodings, all of which decode here to signed 16-bit mono PCM:
 * <ul>
 *   <li><b>PCM8</b> — signed 8-bit samples,</li>
 *   <li><b>PCM16</b> — signed 16-bit little-endian samples,</li>
 *   <li><b>IMA-ADPCM</b> — 4-bit adaptive DPCM with a 4-byte seed header (initial sample + step index).</li>
 * </ul>
 * The wave header carries the sample rate, an ARM7 timer reload value, a loop flag, and the loop start /
 * length in 32-bit words. {@link #decode()} yields the full sample stream; {@link #toWav()} wraps it in a
 * canonical RIFF/WAVE file. RE'd against ndspy ({@code soundArchive.py}) and GBATEK's sound chapter.
 */
public class Wave
{
    public static final int PCM8 = 0;
    public static final int PCM16 = 1;
    public static final int ADPCM = 2;

    private final int waveType;
    private final boolean loops;
    private final int sampleRate;
    private final int timer;
    private final int loopStartWords;   // loop start, in 32-bit words of *encoded* data (after ADPCM header)
    private final int lengthWords;      // encoded length after loop start, in 32-bit words
    private final byte[] sampleData;    // raw encoded bytes (no info struct)

    Wave(int waveType, boolean loops, int sampleRate, int timer,
         int loopStartWords, int lengthWords, byte[] sampleData)
    {
        this.waveType = waveType;
        this.loops = loops;
        this.sampleRate = sampleRate;
        this.timer = timer;
        this.loopStartWords = loopStartWords;
        this.lengthWords = lengthWords;
        this.sampleData = sampleData;
    }

    /** Parse a standalone SWAV file ({@code "SWAV"} + NTR header + {@code "DATA"} block). */
    public static Wave fromSwavFile(byte[] file)
    {
        // 0x10 NTR header, then "DATA"(4) + u32 blockSize(4) => info struct at 0x18, sample data at 0x24
        int infoPos = 0x18;
        return fromInfoStruct(file, infoPos, infoPos + 0x0C, file.length);
    }

    /**
     * Parse one wave from inside a wave archive: a 12-byte info struct at {@code infoPos} immediately
     * followed by its encoded sample data, which runs to {@code dataEnd}.
     */
    static Wave fromInfoStruct(byte[] buf, int infoPos, int dataStart, int dataEnd)
    {
        int type = buf[infoPos] & 0xFF;
        boolean loop = (buf[infoPos + 1] & 0xFF) != 0;
        int rate = u16(buf, infoPos + 2);
        int time = u16(buf, infoPos + 4);
        int loopStart = u16(buf, infoPos + 6);
        long len = u32(buf, infoPos + 8);
        byte[] data = Arrays.copyOfRange(buf, dataStart, dataEnd);
        return new Wave(type, loop, rate, time, loopStart, (int) len, data);
    }

    /** @return the decoded waveform as signed 16-bit mono PCM. */
    public short[] decode()
    {
        switch (waveType)
        {
            case PCM8:  return decodePcm8();
            case PCM16: return decodePcm16();
            case ADPCM: return decodeAdpcm();
            default: throw new IllegalStateException("Unknown wave type " + waveType);
        }
    }

    private short[] decodePcm8()
    {
        short[] out = new short[sampleData.length];
        for (int i = 0; i < sampleData.length; i++)
            out[i] = (short) (sampleData[i] << 8); // signed 8-bit -> 16-bit
        return out;
    }

    private short[] decodePcm16()
    {
        int n = sampleData.length / 2;
        short[] out = new short[n];
        for (int i = 0; i < n; i++)
            out[i] = (short) u16(sampleData, i * 2);
        return out;
    }

    private short[] decodeAdpcm()
    {
        if (sampleData.length < 4) return new short[0];
        int predictor = (short) u16(sampleData, 0);
        int index = u16(sampleData, 2) & 0x7F;
        if (index > 88) index = 88;

        int nibbles = (sampleData.length - 4) * 2;
        short[] out = new short[nibbles + 1];
        out[0] = (short) predictor; // the seed sample is a real sample
        int o = 1;
        for (int b = 4; b < sampleData.length; b++)
        {
            int by = sampleData[b] & 0xFF;
            for (int half = 0; half < 2; half++)
            {
                int code = (half == 0) ? (by & 0x0F) : (by >> 4);
                int[] r = Adpcm.step(predictor, index, code);
                predictor = r[0]; index = r[1];
                out[o++] = (short) predictor;
            }
        }
        return out;
    }

    /** @return a canonical 16-bit mono RIFF/WAVE file of the decoded samples. */
    public byte[] toWav()
    {
        return WavFile.mono16(decode(), sampleRate);
    }

    public int getWaveType() { return waveType; }
    public boolean loops() { return loops; }

    /**
     * @return the playback sample rate in Hz. Derived from the ARM7 timer field
     * ({@code 16756991 / timer}, the exact hardware rate, as VGMTrans does) when present, else the stored
     * {@code sampleRate} field.
     */
    public int getSampleRate() { return timer > 0 ? 16756991 / timer : sampleRate; }

    /** @return the raw {@code sampleRate} header field (before the timer refinement). */
    public int getRawSampleRate() { return sampleRate; }
    public int getTimer() { return timer; }
    public int getLoopStartWords() { return loopStartWords; }

    /**
     * @return the loop start as an index into the decoded PCM samples. Loop offset is stored in 32-bit
     * words; PCM16 = 2 samples/word, PCM8 = 4, IMA-ADPCM = 8 (and shifted by the 4-byte seed header, so
     * {@code -7}, matching VGMTrans). 0 when the wave does not loop.
     */
    public int getLoopStartSample()
    {
        if (!loops) return 0;
        int perWord = (waveType == PCM16) ? 2 : (waveType == PCM8) ? 4 : 8;
        int s = loopStartWords * perWord;
        if (waveType == ADPCM) s -= 7; // ADPCM seed header: loopOff*2 - 8 + 1
        return Math.max(0, s);
    }

    /**
     * Exclusive end index of the looping region in decoded PCM. The SWAR length field is the encoded
     * size <em>after</em> {@link #getLoopStartWords()}, so the playable loop is
     * {@code [loopStart, loopEnd)} rather than the whole decoded buffer (which may include alignment
     * padding). Non-looping waves return the decoded length.
     */
    public int getLoopEndSample()
    {
        int perWord = (waveType == PCM16) ? 2 : (waveType == PCM8) ? 4 : 8;
        if (waveType == ADPCM)
            return Math.max(0, (loopStartWords + lengthWords) * 8 - 7);
        return (loopStartWords + lengthWords) * perWord;
    }
    public int getLengthWords() { return lengthWords; }
    /** @return number of decoded PCM samples this wave yields. */
    public int getSampleCount() { return decode().length; }

    static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
    static long u32(byte[] b, int o)
    {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }
}
