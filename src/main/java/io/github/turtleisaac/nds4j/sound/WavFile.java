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

import io.github.turtleisaac.nds4j.framework.MemBuf;

/**
 * Minimal canonical RIFF/WAVE (PCM) codec, so decoded DS audio ({@link Wave}, {@link Stream}) can be
 * played in any tool and a WAV can be imported back as a {@link Wave}. Pure JVM, no {@code javax.sound}
 * (unavailable under CheerpJ).
 */
public final class WavFile
{
    private WavFile()
    {
    }

    /** @return a 16-bit mono PCM WAV file for {@code samples} at {@code sampleRate} Hz. */
    public static byte[] mono16(short[] samples, int sampleRate)
    {
        return pcm16(samples, 1, sampleRate);
    }

    /**
     * @param interleaved signed 16-bit samples, interleaved by channel (L,R,L,R,… for stereo)
     * @param channels number of channels
     * @param sampleRate sample rate in Hz
     * @return a 16-bit PCM WAV file
     */
    public static byte[] pcm16(short[] interleaved, int channels, int sampleRate)
    {
        int bytesPerSample = 2;
        int dataSize = interleaved.length * bytesPerSample;
        int blockAlign = channels * bytesPerSample;
        int byteRate = sampleRate * blockAlign;
        byte[] out = new byte[44 + dataSize];

        writeString(out, 0, "RIFF");
        writeU32(out, 4, 36 + dataSize);
        writeString(out, 8, "WAVE");
        writeString(out, 12, "fmt ");
        writeU32(out, 16, 16); // fmt chunk size
        writeU16(out, 20, 1); // PCM
        writeU16(out, 22, channels);
        writeU32(out, 24, sampleRate);
        writeU32(out, 28, byteRate);
        writeU16(out, 32, blockAlign);
        writeU16(out, 34, 16); // bits per sample
        writeString(out, 36, "data");
        writeU32(out, 40, dataSize);
        int p = 44;
        for (int i = 0; i < interleaved.length; i++)
        {
            out[p++] = (byte) (interleaved[i] & 0xFF);
            out[p++] = (byte) ((interleaved[i] >> 8) & 0xFF);
        }
        return out;
    }

    /** A decoded PCM buffer: always downmixed to signed 16-bit mono. */
    public static final class Pcm
    {
        public final short[] samples;
        public final int sampleRate;
        /** Channel count of the source file before the mono downmix. */
        public final int sourceChannels;
        public final int bitsPerSample;

        public Pcm(short[] samples, int sampleRate, int sourceChannels, int bitsPerSample)
        {
            this.samples = samples;
            this.sampleRate = sampleRate;
            this.sourceChannels = sourceChannels;
            this.bitsPerSample = bitsPerSample;
        }
    }

    /**
     * Parse a PCM WAV (format 1). Stereo/multi-channel is averaged to mono; 8-bit unsigned is
     * promoted to signed 16-bit. Other encodings (IEEE float, extensible, 24-bit) are rejected.
     */
    public static Pcm read(byte[] wav)
    {
        requireWave(wav);
        WavChunks chunks = walkChunks(wav);
        requirePcm(chunks);

        return mix(wav, chunks);
    }

    /**
     * @param wav the file bytes
     * @see "Multimedia Programming Interface and Data Specifications 1.0, RIFF WAVE"
     */
    private static void requireWave(byte[] wav)
    {
        if (wav == null || wav.length < 12)
            throw new IllegalArgumentException("not a WAV file");

        requireRiff(wav);
    }

    /**
     * @param wav the file bytes
     * @see "Multimedia Programming Interface and Data Specifications 1.0, RIFF WAVE"
     */
    private static void requireRiff(byte[] wav)
    {
        if (!ascii(wav, 0, 4).equals("RIFF") || !ascii(wav, 8, 4).equals("WAVE"))
            throw new IllegalArgumentException("not a RIFF/WAVE file");
    }

    /**
     * @param wav the file bytes
     * @return the fmt chunk and the data chunk
     * @see "Multimedia Programming Interface and Data Specifications 1.0, RIFF WAVE"
     */
    private static WavChunks walkChunks(byte[] wav)
    {
        WavChunks chunks = new WavChunks();
        int p = 12;

        while (p + 8 <= wav.length)
            p = oneChunk(wav, p, chunks);

        return chunks;
    }

    /**
     * @param wav the file bytes
     * @param p start of a chunk
     * @param chunks where fmt and data are recorded
     * @return the start of the next chunk
     * @see "Multimedia Programming Interface and Data Specifications 1.0, RIFF WAVE"
     */
    private static int oneChunk(byte[] wav, int p, WavChunks chunks)
    {
        String id = ascii(wav, p, 4);
        MemBuf.MemBufReader field0Reader = MemBuf.create(wav).reader();
        field0Reader.setPosition(p + 4);
        long field0 = field0Reader.readUInt32();
        int size = (int) field0;
        int body = p + 8;
        requireFits(wav, id, body, size);
        takeFmt(wav, id, body, size, chunks);
        takeData(id, body, size, chunks);

        return body + size + (size & 1);
    }

    /**
     * @param wav the file bytes
     * @param id the chunk id
     * @param body where the chunk payload starts
     * @param size the payload size
     * @see "Multimedia Programming Interface and Data Specifications 1.0, RIFF WAVE"
     */
    private static void requireFits(byte[] wav, String id, int body, int size)
    {
        if (body + size > wav.length)
            throw new IllegalArgumentException("WAV chunk '" + id + "' overruns the file");
    }

    /**
     * @param wav the file bytes
     * @param id the chunk id
     * @param body where the chunk payload starts
     * @param size the payload size
     * @param chunks where the fmt fields are recorded
     * @see "Multimedia Programming Interface and Data Specifications 1.0, RIFF WAVE"
     */
    private static void takeFmt(byte[] wav, String id, int body, int size, WavChunks chunks)
    {
        if (!id.equals("fmt "))
            return;

        readFmt(wav, body, size, chunks);
    }

    /**
     * @param wav the file bytes
     * @param body where the fmt payload starts
     * @param size the payload size
     * @param chunks where the fmt fields are recorded
     * @see "Multimedia Programming Interface and Data Specifications 1.0, RIFF WAVE"
     */
    private static void readFmt(byte[] wav, int body, int size, WavChunks chunks)
    {
        if (size < 16)
            throw new IllegalArgumentException("WAV fmt chunk too short");

        MemBuf.MemBufReader field1Reader = MemBuf.create(wav).reader();
        field1Reader.setPosition(body);
        int field1 = field1Reader.readUInt16();
        chunks.fmt = field1;
        MemBuf.MemBufReader field2Reader = MemBuf.create(wav).reader();
        field2Reader.setPosition(body + 2);
        int field2 = field2Reader.readUInt16();
        chunks.channels = field2;
        MemBuf.MemBufReader field3Reader = MemBuf.create(wav).reader();
        field3Reader.setPosition(body + 4);
        long field3 = field3Reader.readUInt32();
        chunks.sampleRate = (int) field3;
        MemBuf.MemBufReader field4Reader = MemBuf.create(wav).reader();
        field4Reader.setPosition(body + 14);
        int field4 = field4Reader.readUInt16();
        chunks.bits = field4;
    }

    /**
     * @param id the chunk id
     * @param body where the chunk payload starts
     * @param size the payload size
     * @param chunks where the data chunk is recorded
     * @see "Multimedia Programming Interface and Data Specifications 1.0, RIFF WAVE"
     */
    private static void takeData(String id, int body, int size, WavChunks chunks)
    {
        if (!id.equals("data"))
            return;

        chunks.dataOff = body;
        chunks.dataLen = size;
    }

    /**
     * @param chunks the fmt and data chunks
     * @see "Multimedia Programming Interface and Data Specifications 1.0, RIFF WAVE"
     */
    private static void requirePcm(WavChunks chunks)
    {
        boolean[] ok = { chunks.fmt >= 0, chunks.dataOff >= 0, chunks.fmt == 1, chunks.channels >= 1, chunks.sampleRate >= 1 };
        String[] messages = {
                "WAV has no fmt chunk",
                "WAV has no data chunk",
                "WAV encoding " + chunks.fmt + " is not PCM",
                "WAV has no channels",
                "WAV has no sample rate"
        };
        int index = 0;

        while (index < ok.length)
        {
            if (!ok[index])
                throw new IllegalArgumentException(messages[index]);

            index++;
        }

        requireBits(chunks);
    }

    /** @param chunks the fmt and data chunks */
    private static void requireBits(WavChunks chunks)
    {
        if (chunks.bits != 8 && chunks.bits != 16)
            throw new IllegalArgumentException("WAV bits-per-sample " + chunks.bits + " is not 8 or 16");
    }

    /**
     * @param wav the file bytes
     * @param chunks the fmt and data chunks
     * @return mono 16-bit frames
     * @see "Multimedia Programming Interface and Data Specifications 1.0, RIFF WAVE"
     */
    private static Pcm mix(byte[] wav, WavChunks chunks)
    {
        int bytesPerFrame = chunks.channels * (chunks.bits / 8);
        requireFrame(bytesPerFrame);
        int frames = chunks.dataLen / bytesPerFrame;
        short[] mono = new short[frames];

        for (int i = 0; i < frames; i++)
            mono[i] = mixFrame(wav, chunks, bytesPerFrame, i);

        return new Pcm(mono, chunks.sampleRate, chunks.channels, chunks.bits);
    }

    /** @param bytesPerFrame bytes in one interleaved frame */
    private static void requireFrame(int bytesPerFrame)
    {
        if (bytesPerFrame <= 0)
            throw new IllegalArgumentException("WAV frame size is 0");
    }

    /**
     * @param wav the file bytes
     * @param chunks the fmt and data chunks
     * @param bytesPerFrame bytes in one interleaved frame
     * @param frame which frame
     * @return the channels averaged to one signed sample
     * @see "Multimedia Programming Interface and Data Specifications 1.0, RIFF WAVE"
     */
    private static short mixFrame(byte[] wav, WavChunks chunks, int bytesPerFrame, int frame)
    {
        int acc = 0;

        for (int c = 0; c < chunks.channels; c++)
        {
            int o = chunks.dataOff + frame * bytesPerFrame + c * (chunks.bits / 8);
            acc += sample(wav, chunks.bits, o);
        }

        return (short) (acc / chunks.channels);
    }

    /**
     * @param wav the file bytes
     * @param bits 8 or 16
     * @param offset the sample's first byte
     * @return a signed 16-bit sample
     * @see "Multimedia Programming Interface and Data Specifications 1.0, RIFF WAVE"
     */
    private static int sample(byte[] wav, int bits, int offset)
    {
        if (bits == 8)
            return ((wav[offset] & 0xFF) - 128) << 8;

        MemBuf.MemBufReader reader = MemBuf.create(new byte[] { wav[offset], wav[offset + 1] }).reader();

        return reader.readShort();
    }

    /**
     * The fmt fields and the data chunk found while walking a WAVE file.
     * @see "Multimedia Programming Interface and Data Specifications 1.0, RIFF WAVE"
     */
    private static final class WavChunks
    {
        private int fmt = -1;
        private int channels;
        private int sampleRate;
        private int bits;
        private int dataOff = -1;
        private int dataLen;
    }

    private static String ascii(byte[] b, int o, int n)
    {
        return new String(b, o, n, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static void writeString(byte[] b, int o, String s)
    {
        for (int i = 0; i < s.length(); i++)
            b[o + i] = (byte) s.charAt(i);
    }
    private static void writeU32(byte[] b, int o, int v)
    {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }
    private static void writeU16(byte[] b, int o, int v)
    {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
    }
}
