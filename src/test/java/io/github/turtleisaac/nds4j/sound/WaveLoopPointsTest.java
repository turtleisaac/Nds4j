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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The synth loops {@code [loopStart, loopEnd)}, and loopEnd is the encoded
 * {@code (loopStartWords + lengthWords)} converted to a decoded index — not the (possibly padded)
 * decoded buffer length. CI-safe, no ROM.
 */
@DisplayName("SWAV loop start/end in decoded samples")
public class WaveLoopPointsTest
{
    @Test
    @DisplayName("PCM16 loop end is (loopStart + length) words × 2 samples")
    void pcm16LoopEnd()
    {
        Wave w = info(Wave.PCM16, true, 2, 10);
        assertThat(w.getLoopStartSample()).isEqualTo(4);
        assertThat(w.getLoopEndSample()).isEqualTo(24);
    }

    @Test
    @DisplayName("ADPCM loop end subtracts the 4-byte seed header (the −7)")
    void adpcmLoopEnd()
    {
        Wave w = info(Wave.ADPCM, true, 10, 20);
        assertThat(w.getLoopStartSample()).isEqualTo(10 * 8 - 7);
        assertThat(w.getLoopEndSample()).isEqualTo((10 + 20) * 8 - 7);
    }

    @Test
    @DisplayName("SequencePlayer wraps at loopEnd, not the padded decoded buffer")
    void synthDoesNotPlayPaddingPastLoopEnd()
    {
        // 30-sample loop of quiet PCM, then 10 samples of a loud pad that must never be heard.
        short[] pcm = new short[40];
        for (int i = 0; i < 30; i++) pcm[i] = 800;
        for (int i = 30; i < 40; i++) pcm[i] = 30000;
        WaveArchive arc = WaveArchive.fromBytes(swarPcm16(pcm, 0, 15)); // 15 words = 30 samples
        InstrumentBank bank = InstrumentBank.fromBytes(pcmBank());
        Sequence seq = Sequence.fromBytes(sseqHold());
        SequencePlayer player = new SequencePlayer(seq, bank, new WaveArchive[] { arc, null, null, null });
        short[] out = player.renderStereo(32768, 0.4);
        assertThat(player.dbgVoices).isGreaterThan(0);
        int peak = 0;
        for (int i = 0; i < out.length; i++)
        {
            int a = Math.abs(out[i]);
            if (a > peak) peak = a;
        }
        // quiet loop through the limiter is a few hundred; the 30000 pad would peak above 8000
        assertThat(peak)
                .as("loop wrap must not read the padding past loopEnd")
                .isLessThan(4000);
        assertThat(peak).as("the quiet loop itself must be audible").isGreaterThan(50);
    }

    private static byte[] swarPcm16(short[] pcm, int loopStartWords, int lengthWords)
    {
        int info = 0x40;
        int data = 0x4C;
        byte[] f = new byte[data + pcm.length * 2];
        putMagic(f, 0, "SWAR");
        putU16(f, 4, 0xFEFF);
        putU16(f, 6, 0x0100);
        putU32(f, 8, f.length);
        putU16(f, 12, 0x10);
        putU16(f, 14, 1);
        putMagic(f, 16, "DATA");
        putU32(f, 20, f.length - 16);
        putU32(f, 0x38, 1);
        putU32(f, 0x3C, info);
        f[info] = Wave.PCM16;
        f[info + 1] = 1;
        putU16(f, info + 2, 32768);
        putU16(f, info + 4, 511); // 16756991/511 ≈ 32792 Hz, near the render rate
        putU16(f, info + 6, loopStartWords);
        putU32(f, info + 8, lengthWords);
        for (int i = 0; i < pcm.length; i++)
            putU16(f, data + i * 2, pcm[i] & 0xFFFF);
        return f;
    }

    private static byte[] pcmBank()
    {
        int body = 0x40;
        byte[] f = new byte[0x4A];
        putMagic(f, 0, "SBNK");
        putU16(f, 4, 0xFEFF);
        putU16(f, 6, 0x0100);
        putU32(f, 8, f.length);
        putU16(f, 12, 0x10);
        putU16(f, 14, 1);
        putMagic(f, 16, "DATA");
        putU32(f, 20, f.length - 16);
        putU32(f, 0x38, 1);
        f[0x3C] = 1;
        putU16(f, 0x3D, body);
        putU16(f, body, 0);     // wave 0
        putU16(f, body + 2, 0); // arc 0
        f[body + 4] = 60;
        f[body + 5] = 127;
        f[body + 6] = 127;
        f[body + 7] = 127;
        f[body + 8] = 127;
        f[body + 9] = 64;
        return f;
    }

    private static byte[] sseqHold()
    {
        byte[] ev = new byte[] {
                (byte) 0xC7, 0x00,
                (byte) 0x81, 0x00,
                0x3C, 0x7F, 48,     // hold a 48-tick note (loops many times)
                (byte) 0x80, 48,
                (byte) 0xFF
        };
        byte[] f = new byte[0x1C + ev.length];
        putMagic(f, 0, "SSEQ");
        putU16(f, 4, 0xFEFF);
        putU16(f, 6, 0x0100);
        putU32(f, 8, f.length);
        putU16(f, 12, 0x10);
        putU16(f, 14, 1);
        putMagic(f, 16, "DATA");
        putU32(f, 20, f.length - 16);
        putU32(f, 24, 0x1C);
        System.arraycopy(ev, 0, f, 0x1C, ev.length);
        return f;
    }

    private static void putMagic(byte[] b, int o, String m)
    {
        for (int i = 0; i < 4; i++) b[o + i] = (byte) m.charAt(i);
    }
    private static void putU16(byte[] b, int o, int v)
    {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
    }
    private static void putU32(byte[] b, int o, int v)
    {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }

    private static Wave info(int type, boolean loop, int loopStartWords, int lengthWords)
    {
        byte[] buf = new byte[12];
        buf[0] = (byte) type;
        buf[1] = (byte) (loop ? 1 : 0);
        buf[6] = (byte) loopStartWords;
        buf[7] = (byte) (loopStartWords >> 8);
        buf[8] = (byte) lengthWords;
        buf[9] = (byte) (lengthWords >> 8);
        return Wave.fromInfoStruct(buf, 0, 12, 12);
    }
}
