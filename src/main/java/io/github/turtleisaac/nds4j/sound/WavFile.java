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

/**
 * Minimal canonical RIFF/WAVE (PCM) codec, so decoded DS audio ({@link Wave}, {@link Stream}) can be
 * played in any tool and a WAV can be imported back as a {@link Wave}. Pure JVM, no {@code javax.sound}
 * (unavailable under CheerpJ).
 */
public final class WavFile
{
    private WavFile() {}

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
        writeU32(out, 16, 16);          // fmt chunk size
        writeU16(out, 20, 1);           // PCM
        writeU16(out, 22, channels);
        writeU32(out, 24, sampleRate);
        writeU32(out, 28, byteRate);
        writeU16(out, 32, blockAlign);
        writeU16(out, 34, 16);          // bits per sample
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
        if (wav == null || wav.length < 12)
            throw new IllegalArgumentException("not a WAV file");
        if (!ascii(wav, 0, 4).equals("RIFF") || !ascii(wav, 8, 4).equals("WAVE"))
            throw new IllegalArgumentException("not a RIFF/WAVE file");

        int fmt = -1, channels = 0, sampleRate = 0, bits = 0;
        int dataOff = -1, dataLen = 0;
        int p = 12;
        while (p + 8 <= wav.length)
        {
            String id = ascii(wav, p, 4);
            int size = (int) u32(wav, p + 4);
            int body = p + 8;
            if (body + size > wav.length)
                throw new IllegalArgumentException("WAV chunk '" + id + "' overruns the file");
            if (id.equals("fmt "))
            {
                if (size < 16) throw new IllegalArgumentException("WAV fmt chunk too short");
                fmt = u16(wav, body);
                channels = u16(wav, body + 2);
                sampleRate = (int) u32(wav, body + 4);
                bits = u16(wav, body + 14);
            }
            else if (id.equals("data"))
            {
                dataOff = body;
                dataLen = size;
            }
            p = body + size + (size & 1); // chunks are word-aligned
        }
        if (fmt < 0) throw new IllegalArgumentException("WAV has no fmt chunk");
        if (dataOff < 0) throw new IllegalArgumentException("WAV has no data chunk");
        if (fmt != 1) throw new IllegalArgumentException("WAV encoding " + fmt + " is not PCM");
        if (channels < 1) throw new IllegalArgumentException("WAV has no channels");
        if (sampleRate < 1) throw new IllegalArgumentException("WAV has no sample rate");
        if (bits != 8 && bits != 16)
            throw new IllegalArgumentException("WAV bits-per-sample " + bits + " is not 8 or 16");

        int bytesPerFrame = channels * (bits / 8);
        if (bytesPerFrame <= 0) throw new IllegalArgumentException("WAV frame size is 0");
        int frames = dataLen / bytesPerFrame;
        short[] mono = new short[frames];
        for (int i = 0; i < frames; i++)
        {
            int acc = 0;
            for (int c = 0; c < channels; c++)
            {
                int o = dataOff + i * bytesPerFrame + c * (bits / 8);
                if (bits == 8) acc += ((wav[o] & 0xFF) - 128) << 8; // unsigned 8-bit → signed 16
                else acc += (short) u16(wav, o);
            }
            mono[i] = (short) (acc / channels);
        }
        return new Pcm(mono, sampleRate, channels, bits);
    }

    private static String ascii(byte[] b, int o, int n)
    {
        return new String(b, o, n, java.nio.charset.StandardCharsets.ISO_8859_1);
    }
    private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
    private static long u32(byte[] b, int o)
    {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    private static void writeString(byte[] b, int o, String s)
    {
        for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i);
    }
    private static void writeU32(byte[] b, int o, int v)
    {
        b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24);
    }
    private static void writeU16(byte[] b, int o, int v)
    {
        b[o] = (byte) v; b[o + 1] = (byte) (v >> 8);
    }
}
