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
 * Drives {@link SequencePlayer#renderStereo} with a 1-note SSEQ and asserts the DS envelope
 * actually falls. A mix that clips 16 voices to the rails makes decay inaudible (cacophony).
 */
@DisplayName("SSEQ note decay envelope")
public class SequencePlayerEnvelopeTest
{
    private static final int RATE = 32768;

    @Test
    @DisplayName("decay 80 / sustain 0: amplitude falls to near-silence while the note is still gated")
    void decayToSilenceWhileHeld()
    {
        // 240-tick note (2.5s at 120 BPM) with instant attack, decay 80, sustain 0, instant release
        short[] pcm = renderNote(80, 0, 240);
        double peak = rms(pcm, 0, RATE / 10);          // first 100 ms
        double late = rms(pcm, RATE, RATE / 10);       // 1.00–1.10 s
        double later = rms(pcm, (int) (RATE * 1.8), RATE / 10); // 1.80–1.90 s
        assertThat(peak).as("note is audible").isGreaterThan(0.02);
        assertThat(late / peak).as("must have decayed by 1s, not hang at full scale").isLessThan(0.20);
        assertThat(later / peak).as("must be nearly silent by 1.8s").isLessThan(0.05);
    }

    @Test
    @DisplayName("decay 80 / sustain 40: falls then holds, not a clipped square")
    void decayThenSustain()
    {
        short[] pcm = renderNote(80, 40, 240);
        double peak = rms(pcm, 0, RATE / 10);
        double held = rms(pcm, (int) (RATE * 1.2), RATE / 10);
        assertThat(peak).isGreaterThan(0.02);
        assertThat(held / peak).as("sustain 40 is a quiet hold, not full scale").isLessThan(0.25);
        assertThat(held / peak).as("sustain 40 is not silence").isGreaterThan(0.04);
    }

    private static short[] renderNote(int decay, int sustain, int durTicks)
    {
        byte[] ev = new byte[] {
                (byte) 0xC7, 0x00,
                (byte) 0xD0, 127,
                (byte) 0xD1, (byte) decay,
                (byte) 0xD2, (byte) sustain,
                (byte) 0xD3, 127,
                0x3C, 0x7F, (byte) 0x81, 0x70,     // note 60 vel 127 dur 240
                (byte) 0x80, (byte) 0x81, 0x70,     // rest 240
                (byte) 0xFF
        };
        Sequence seq = Sequence.fromBytes(sseqFile(ev));
        InstrumentBank bank = InstrumentBank.fromBytes(psgBank());
        SequencePlayer p = new SequencePlayer(seq, bank, new WaveArchive[4]);
        short[] pcm = p.renderStereo(RATE, 3.0);
        assertThat(p.dbgVoices).isGreaterThan(0);
        return pcm;
    }

    /** RMS of the left channel over [from, from+n) frames. */
    static double rms(short[] stereo, int fromFrame, int n)
    {
        double e = 0;
        int count = 0;
        int end = Math.min(stereo.length / 2, fromFrame + n);
        for (int i = fromFrame; i < end; i++)
        {
            double s = stereo[i * 2] / 32768.0;
            e += s * s;
            count++;
        }
        return count == 0 ? 0 : Math.sqrt(e / count);
    }

    private static byte[] sseqFile(byte[] events)
    {
        byte[] f = new byte[0x1C + events.length];
        putMagic(f, 0, "SSEQ");
        putU16(f, 4, 0xFEFF); putU16(f, 6, 0x0100);
        putU32(f, 8, f.length); putU16(f, 12, 0x10); putU16(f, 14, 1);
        putMagic(f, 16, "DATA"); putU32(f, 20, f.length - 16); putU32(f, 24, 0x1C);
        System.arraycopy(events, 0, f, 0x1C, events.length);
        return f;
    }

    private static byte[] psgBank()
    {
        int body = 0x40;
        byte[] f = new byte[0x4A];
        putMagic(f, 0, "SBNK");
        putU16(f, 4, 0xFEFF); putU16(f, 6, 0x0100);
        putU32(f, 8, f.length); putU16(f, 12, 0x10); putU16(f, 14, 1);
        putMagic(f, 16, "DATA"); putU32(f, 20, f.length - 16);
        putU32(f, 0x38, 1);
        f[0x3C] = 2; putU16(f, 0x3D, body);
        putU16(f, body, 3); f[body + 4] = 60;
        f[body + 5] = 127; f[body + 6] = 127; f[body + 7] = 127; f[body + 8] = 127;
        f[body + 9] = 64;
        return f;
    }

    private static void putMagic(byte[] b, int o, String m)
    {
        for (int i = 0; i < 4; i++) b[o + i] = (byte) m.charAt(i);
    }
    private static void putU16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
    private static void putU32(byte[] b, int o, int v)
    {
        b[o] = (byte) v; b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24);
    }
}
