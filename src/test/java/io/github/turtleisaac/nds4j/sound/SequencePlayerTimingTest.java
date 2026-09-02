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
import static org.assertj.core.api.Assertions.offset;

/**
 * Pins SSEQ sequencer timing to the DS sound-driver model (GotaSequenceLib / NitroStudio2):
 * a rest of N ticks lasts N ticks, not N+1. The extra-tick bug made tracks with dense short
 * rests (volume/expression ramps, fast runs) drag and feel jittery. CI-safe: synthetic
 * SSEQ + PSG SBNK, no ROM. Drives the real {@link SequencePlayer#renderStereo} entry point.
 */
@DisplayName("SSEQ sequencer rest/note-wait timing")
public class SequencePlayerTimingTest
{
    private static final int RATE = 32768;
    private static final double TICK_SEC = 60.0 / (120.0 * 48.0); // 1/96 s at default tempo 120

    @Test
    @DisplayName("a rest of N ticks separates two notes by N ticks (not N+1)")
    void restLastsExactlyNTicks()
    {
        double interval24 = onsetInterval(renderTwoNotesSeparatedByRest(24));
        double interval48 = onsetInterval(renderTwoNotesSeparatedByRest(48));

        assertThat(interval24)
                .as("REST 24 at 120 BPM is 24/96 = 0.25s; the old off-by-one made this 25/96 = 0.260s")
                .isCloseTo(24 * TICK_SEC, offset(0.004));
        assertThat(interval48)
                .as("REST 48 at 120 BPM is 0.50s")
                .isCloseTo(48 * TICK_SEC, offset(0.004));

        // scaled, not shifted: doubling the rest doubles the gap (an extra +1 tick would not)
        assertThat(interval48 / interval24)
                .as("rest length must scale; a uniform +1 tick per rest would make 48/24 < 2")
                .isCloseTo(2.0, offset(0.04));

        // explicitly not the off-by-one value
        assertThat(Math.abs(interval24 - 24 * TICK_SEC))
                .as("must match N ticks, not N+1")
                .isLessThan(Math.abs(interval24 - 25 * TICK_SEC));
    }

    @Test
    @DisplayName("note-wait-on: a note's duration is the wait, also not N+1")
    void noteWaitLastsExactlyNTicks()
    {
        // default note-wait ON: two notes of duration 24, no rests. Second starts 24 ticks later.
        // Instant decay to sustain 0 so the first note goes silent and the second onset is measurable.
        byte[] events = new byte[] {
                (byte) 0xD1, 0x7F,  // decay instant
                (byte) 0xD2, 0x00,  // sustain silent
                (byte) 0xD3, 0x7F,  // release instant
                0x3C, 0x7F, 24,     // note 60 vel 127 dur 24
                0x3C, 0x7F, 24,
                (byte) 0xFF
        };
        double interval = onsetInterval(render(events));
        assertThat(interval)
                .as("note-wait duration 24 is 0.25s, not 25 ticks")
                .isCloseTo(24 * TICK_SEC, offset(0.004));
        assertThat(Math.abs(interval - 24 * TICK_SEC))
                .isLessThan(Math.abs(interval - 25 * TICK_SEC));
    }

    private static short[] renderTwoNotesSeparatedByRest(int restTicks)
    {
        if (restTicks < 0 || restTicks > 127)
            throw new IllegalArgumentException("single-byte varlen rest only");
        byte[] events = new byte[] {
                (byte) 0xC7, 0x00,          // note-wait off (timing from rests)
                (byte) 0x81, 0x00,          // program 0
                0x3C, 0x7F, 0x06,           // note 60 vel 127 dur 6
                (byte) 0x80, (byte) restTicks,
                0x3C, 0x7F, 0x06,
                (byte) 0xFF
        };
        return render(events);
    }

    private static short[] render(byte[] events)
    {
        Sequence seq = Sequence.fromBytes(sseqFile(events));
        InstrumentBank bank = InstrumentBank.fromBytes(psgBank());
        SequencePlayer player = new SequencePlayer(seq, bank, new WaveArchive[4]);
        short[] pcm = player.renderStereo(RATE, 2.0);
        assertThat(player.dbgNotes).as("both notes became voices").isGreaterThanOrEqualTo(2);
        assertThat(pcm.length).isGreaterThan(RATE / 4);
        return pcm;
    }

    /** Seconds between the first two amplitude onsets in interleaved stereo PCM. */
    static double onsetInterval(short[] stereo)
    {
        int a = firstOnset(stereo, 0);
        assertThat(a).as("first note must be audible").isGreaterThanOrEqualTo(0);
        int gap = firstSilence(stereo, a + RATE / 200, RATE / 300);
        assertThat(gap).as("notes must be separated by silence").isGreaterThan(a);
        int b = firstOnset(stereo, gap * 2);
        assertThat(b).as("second note must be audible").isGreaterThan(a);
        return (b - a) / (double) RATE;
    }

    private static int firstOnset(short[] pcm, int fromSampleIndex)
    {
        int from = fromSampleIndex < 0 ? 0 : fromSampleIndex;
        if ((from & 1) != 0) from++;
        for (int i = from; i + 1 < pcm.length; i += 2)
            if (Math.abs(pcm[i]) > 1000 || Math.abs(pcm[i + 1]) > 1000)
                return i / 2;
        return -1;
    }

    private static int firstSilence(short[] pcm, int fromFrame, int holdFrames)
    {
        int run = 0;
        int n = pcm.length / 2;
        for (int f = fromFrame; f < n; f++)
        {
            if (Math.abs(pcm[f * 2]) < 200 && Math.abs(pcm[f * 2 + 1]) < 200)
            {
                if (++run >= holdFrames) return f - holdFrames + 1;
            }
            else run = 0;
        }
        return -1;
    }

    private static byte[] sseqFile(byte[] events)
    {
        byte[] f = new byte[0x1C + events.length];
        putMagic(f, 0, "SSEQ");
        putU16(f, 4, 0xFEFF);
        putU16(f, 6, 0x0100);
        putU32(f, 8, f.length);
        putU16(f, 12, 0x10);
        putU16(f, 14, 1);
        putMagic(f, 16, "DATA");
        putU32(f, 20, f.length - 16);
        putU32(f, 24, 0x1C);
        System.arraycopy(events, 0, f, 0x1C, events.length);
        return f;
    }

    /** One program, type 2 (PSG square), duty 3, unity C4, instant ADSR. */
    private static byte[] psgBank()
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
        f[0x3C] = 2;
        putU16(f, 0x3D, body);
        putU16(f, body, 3);
        f[body + 4] = 60;
        f[body + 5] = 127;
        f[body + 6] = 127;
        f[body + 7] = 127;
        f[body + 8] = 127;
        f[body + 9] = 64;
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
}
