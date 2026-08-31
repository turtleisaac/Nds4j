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

import io.github.turtleisaac.nds4j.framework.GenericNtrFile;
import io.github.turtleisaac.nds4j.framework.MemBuf;

/**
 * A streamed audio track — a Nintendo DS "STRM". Unlike a {@link Wave} (a short one-shot sample), a
 * stream is a long, possibly multi-channel track stored in fixed-size blocks that interleave the
 * channels, so the hardware can DMA it a block at a time. After the NTR header, a {@code "HEAD"} block
 * describes the format (PCM8/PCM16/IMA-ADPCM), channel count, sample rate, loop point, and the block
 * geometry (block length, samples per block, and the ragged last block); a {@code "DATA"} block holds
 * the interleaved audio.
 * <p>
 * {@link #decodeChannels()} yields one signed-16-bit PCM array per channel; {@link #toWav()} interleaves
 * them into a stereo/mono WAV. The container is preserved verbatim ({@link #save()} is byte-exact).
 * RE'd against ndspy ({@code soundStream.py}) and GBATEK.
 */
public class Stream extends GenericNtrFile
{
    private byte[] raw;

    private int format;         // 0 PCM8, 1 PCM16, 2 ADPCM
    private boolean loops;
    private int channels;
    private int sampleRate;
    private int timer;
    private long loopStart;
    private long numSamples;     // per channel
    private long dataOffset;     // to first sample byte
    private long numBlocks;
    private long blockLength;    // bytes per channel, per full block
    private long samplesPerBlock;
    private long lastBlockLength;
    private long lastBlockSamples;

    public Stream()
    {
        super("STRM");
    }

    public static Stream fromBytes(byte[] data)
    {
        Stream strm = new Stream();
        strm.read(data);
        return strm;
    }

    private void read(byte[] data)
    {
        raw = data;
        MemBuf buf = MemBuf.create(data);
        MemBuf.MemBufReader reader = buf.reader();
        readGenericNtrHeader(reader);   // "STRM" + NTR header
        // "HEAD"(4) + blockSize(4) => fields at 0x18
        reader.setPosition(0x18);
        format = reader.readByte() & 0xFF;
        loops = (reader.readByte() & 0xFF) != 0;
        channels = reader.readByte() & 0xFF;
        reader.skip(1);                 // padding
        sampleRate = reader.readUInt16();
        timer = reader.readUInt16();
        loopStart = reader.readUInt32();
        numSamples = reader.readUInt32();
        dataOffset = reader.readUInt32();
        numBlocks = reader.readUInt32();
        blockLength = reader.readUInt32();
        samplesPerBlock = reader.readUInt32();
        lastBlockLength = reader.readUInt32();
        lastBlockSamples = reader.readUInt32();
    }

    /** @return one signed-16-bit PCM array per channel. */
    public short[][] decodeChannels()
    {
        short[][] out = new short[channels][(int) numSamples];
        int[] written = new int[channels];
        int base = (int) dataOffset;

        for (int b = 0; b < numBlocks; b++)
        {
            boolean last = (b == numBlocks - 1);
            int thisLen = (int) (last ? lastBlockLength : blockLength);
            int thisSamples = (int) (last ? lastBlockSamples : samplesPerBlock);
            int blockStart = base + (int) (b * blockLength * channels);
            for (int c = 0; c < channels; c++)
            {
                int chStart = blockStart + c * thisLen;
                decodeBlockInto(chStart, thisLen, thisSamples, out[c], written, c);
            }
        }
        return out;
    }

    private void decodeBlockInto(int off, int lenBytes, int nSamples, short[] dst, int[] written, int c)
    {
        int w = written[c];
        switch (format)
        {
            case Wave.PCM8:
                for (int i = 0; i < nSamples && w < dst.length; i++)
                    dst[w++] = (short) (raw[off + i] << 8);
                break;
            case Wave.PCM16:
                for (int i = 0; i < nSamples && w < dst.length; i++)
                    dst[w++] = (short) Wave.u16(raw, off + i * 2);
                break;
            case Wave.ADPCM:
            {
                // each block carries its own 4-byte IMA seed header
                int predictor = (short) Wave.u16(raw, off);
                int index = Wave.u16(raw, off + 2) & 0x7F;
                if (index > 88) index = 88;
                if (w < dst.length) dst[w++] = (short) predictor;
                int produced = 1;
                for (int p = off + 4; p < off + lenBytes && produced < nSamples; p++)
                {
                    int by = raw[p] & 0xFF;
                    for (int half = 0; half < 2 && produced < nSamples; half++)
                    {
                        int code = (half == 0) ? (by & 0x0F) : (by >> 4);
                        int[] r = Adpcm.step(predictor, index, code);
                        predictor = r[0]; index = r[1];
                        if (w < dst.length) dst[w++] = (short) predictor;
                        produced++;
                    }
                }
                break;
            }
            default:
                throw new IllegalStateException("Unknown STRM format " + format);
        }
        written[c] = w;
    }

    /** @return a canonical 16-bit PCM WAV, channels interleaved (mono or stereo). */
    public byte[] toWav()
    {
        short[][] ch = decodeChannels();
        int n = ch.length == 0 ? 0 : ch[0].length;
        short[] interleaved = new short[n * channels];
        for (int i = 0; i < n; i++)
            for (int c = 0; c < channels; c++)
                interleaved[i * channels + c] = ch[c][i];
        return WavFile.pcm16(interleaved, channels, sampleRate);
    }

    /** @return a mono downmix (average of channels) for waveform previewing. */
    public short[] decodeMonoDownmix()
    {
        short[][] ch = decodeChannels();
        if (ch.length == 1) return ch[0];
        int n = ch[0].length;
        short[] mono = new short[n];
        for (int i = 0; i < n; i++)
        {
            int sum = 0;
            for (int c = 0; c < channels; c++) sum += ch[c][i];
            mono[i] = (short) (sum / channels);
        }
        return mono;
    }

    public byte[] save() { return raw; }

    public int getFormat() { return format; }
    public boolean loops() { return loops; }
    public int getChannels() { return channels; }
    public int getSampleRate() { return sampleRate; }
    public long getNumSamples() { return numSamples; }
    public long getBlockCount() { return numBlocks; }
    /** @return track length in seconds. */
    public double getDurationSeconds() { return sampleRate == 0 ? 0 : (double) numSamples / sampleRate; }
}
