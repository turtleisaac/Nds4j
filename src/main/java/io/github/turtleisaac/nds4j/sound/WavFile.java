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
 * Minimal canonical RIFF/WAVE (PCM) writer, so decoded DS audio ({@link Wave}, {@link Stream}) can be
 * played in any tool. Pure JVM, no {@code javax.sound} dependency (which is unavailable under CheerpJ).
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
