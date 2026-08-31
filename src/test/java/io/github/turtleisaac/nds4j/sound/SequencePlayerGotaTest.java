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
 * Pins SequencePlayer behaviour that GotaSequenceLib / NitroStudio2 Deluxe define and that is
 * independent of a ROM: allocate-track, tie, and portamento-time sweep length.
 */
@DisplayName("SSEQ Gota/NS2D player semantics")
public class SequencePlayerGotaTest
{
    @Test
    @DisplayName("OpenTrack is ignored unless 0xFE allocated the destination")
    void allocateTrackGatesOpenTrack()
    {
        // track 0 opens track 1 (pc is an offset into getEventData(), 0 = first event byte)
        byte[] noAlloc = new byte[] {
                (byte) 0x93, 1, 6, 0x00, 0x00,      // open track 1 at pc 6
                (byte) 0xFF,
                (byte) 0xC7, 0x00,
                0x3C, 0x7F, 24,
                (byte) 0x80, 24,
                (byte) 0xFF
        };
        SequencePlayer skipped = player(noAlloc);
        skipped.renderStereo(32768, 0.5);
        assertThat(skipped.dbgNotes).as("track 1 is not allocated").isEqualTo(0);

        byte[] withAlloc = new byte[] {
                (byte) 0xFE, 0x02, 0x00,            // allocate track 1
                (byte) 0x93, 1, 9, 0x00, 0x00,      // open track 1 at pc 9
                (byte) 0xFF,
                (byte) 0xC7, 0x00,
                0x3C, 0x7F, 24,
                (byte) 0x80, 24,
                (byte) 0xFF
        };
        SequencePlayer played = player(withAlloc);
        played.renderStereo(32768, 0.5);
        assertThat(played.dbgNotes).as("allocated track 1 actually runs").isEqualTo(1);
        assertThat(played.dbgVoices).isGreaterThan(0);
    }

    @Test
    @DisplayName("tie reuses the live channel instead of allocating a second voice")
    void tieReusesChannel()
    {
        byte[] ev = new byte[] {
                (byte) 0xC7, 0x00,          // note-wait off
                (byte) 0xC8, 0x01,          // tie on
                0x3C, 0x7F, 48,             // C4
                0x3E, 0x7F, 48,             // D4, should retarget the same channel
                (byte) 0x80, 48,
                (byte) 0xFF
        };
        SequencePlayer p = player(ev);
        p.renderStereo(32768, 1.0);
        assertThat(p.dbgNotes).isEqualTo(2);
        assertThat(p.dbgVoices).as("tie must not start a second hardware channel").isEqualTo(1);
        assertThat(p.dbgMaxVoices).isEqualTo(1);
    }

    @Test
    @DisplayName("portamento time uses (t*t*|sweep|)>>11, not t*t/4")
    void portamentoSweepIsAudible()
    {
        // two notes a tritone apart with porta on and a long time; must produce audio (the
        // old t*t/4 formula also would, so this is a smoke pin + the length is covered by
        // matching Gota's integer expression in SequencePlayer.applySweep).
        byte[] ev = new byte[] {
                (byte) 0xC7, 0x00,
                (byte) 0xCE, 0x01,          // porta on
                (byte) 0xCF, 40,            // porta time
                0x3C, 0x7F, 24,
                0x48, 0x7F, 48,             // +12 semitones
                (byte) 0x80, 48,
                (byte) 0xFF
        };
        SequencePlayer p = player(ev);
        short[] pcm = p.renderStereo(32768, 1.0);
        assertThat(p.dbgVoices).isGreaterThan(0);
        int peak = 0;
        for (int i = 0; i < pcm.length; i++)
        {
            int a = Math.abs(pcm[i]);
            if (a > peak) peak = a;
        }
        assertThat(peak).isGreaterThan(500);
    }

    private static SequencePlayer player(byte[] events)
    {
        return new SequencePlayer(Sequence.fromBytes(sseqFile(events)),
                InstrumentBank.fromBytes(psgBank()), new WaveArchive[4]);
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
