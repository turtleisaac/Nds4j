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
 * The DS has 16 hardware channels (GotaSequenceLib / NitroStudio2). A 64-voice mix through tanh
 * turned dense songs into a wall of sound that masked quieter tracks until the density dropped.
 * These tests drive {@link SequencePlayer#renderStereo} with a synthetic overlapping-note SSEQ.
 */
@DisplayName("SSEQ 16-channel voice allocation")
public class SequencePlayerPolyphonyTest
{
    @Test
    @DisplayName("20 overlapping PCM notes never occupy more than 16 channels")
    void pcmCapIs16()
    {
        SequencePlayer p = player(overlapSeq(20), pcmBank(), pcmArc());
        short[] pcm = p.renderStereo(32768, 1.0);
        assertThat(p.dbgNotes).as("all 20 note commands ran").isEqualTo(20);
        assertThat(p.dbgMaxVoices).as("hardware channel cap").isLessThanOrEqualTo(16);
        int peak = peak(pcm);
        assertThat(peak).as("the mix is still audible").isGreaterThan(500);
        // 20 full-scale stacked voices would clip; 16 of these quiet samples stay well under
        assertThat(peak).as("must not stack past 16 channels into the limiter").isLessThan(20000);
    }

    @Test
    @DisplayName("PSG notes only steal among the 6 hardware PSG slots (8–13)")
    void psgCapIs6()
    {
        SequencePlayer p = player(overlapSeq(12), psgBank(), new WaveArchive[4]);
        p.renderStereo(32768, 0.5);
        assertThat(p.dbgNotes).isEqualTo(12);
        assertThat(p.dbgMaxVoices).as("PSG is channels 8–13 only").isLessThanOrEqualTo(6);
    }

    private static SequencePlayer player(byte[] events, byte[] bank, WaveArchive[] arcs)
    {
        return new SequencePlayer(Sequence.fromBytes(sseqFile(events)), InstrumentBank.fromBytes(bank), arcs);
    }

    /** note-wait off, N simultaneous notes of duration 48, then a rest. */
    private static byte[] overlapSeq(int n)
    {
        byte[] ev = new byte[4 + n * 3 + 3];
        int o = 0;
        ev[o++] = (byte) 0xC7; ev[o++] = 0x00;
        ev[o++] = (byte) 0x81; ev[o++] = 0x00;
        for (int i = 0; i < n; i++)
        {
            ev[o++] = (byte) (0x3C + (i % 12)); // C4..B4
            ev[o++] = 0x7F;
            ev[o++] = 48;
        }
        ev[o++] = (byte) 0x80; ev[o++] = 48;
        ev[o++] = (byte) 0xFF;
        return ev;
    }

    private static WaveArchive[] pcmArc()
    {
        short[] pcm = new short[64];
        for (int i = 0; i < pcm.length; i++) pcm[i] = 2000;
        return new WaveArchive[] { WaveArchive.fromBytes(swar(pcm)), null, null, null };
    }

    private static byte[] swar(short[] pcm)
    {
        int info = 0x40, data = 0x4C;
        byte[] f = new byte[data + pcm.length * 2];
        putMagic(f, 0, "SWAR");
        putU16(f, 4, 0xFEFF); putU16(f, 6, 0x0100);
        putU32(f, 8, f.length); putU16(f, 12, 0x10); putU16(f, 14, 1);
        putMagic(f, 16, "DATA"); putU32(f, 20, f.length - 16);
        putU32(f, 0x38, 1); putU32(f, 0x3C, info);
        f[info] = Wave.PCM16; f[info + 1] = 1;
        putU16(f, info + 2, 32768); putU16(f, info + 4, 511);
        putU16(f, info + 6, 0); putU32(f, info + 8, pcm.length / 2);
        for (int i = 0; i < pcm.length; i++) putU16(f, data + i * 2, pcm[i] & 0xFFFF);
        return f;
    }

    private static byte[] pcmBank() { return bank((byte) 1); }
    private static byte[] psgBank() { return bank((byte) 2); }

    private static byte[] bank(byte type)
    {
        int body = 0x40;
        byte[] f = new byte[0x4A];
        putMagic(f, 0, "SBNK");
        putU16(f, 4, 0xFEFF); putU16(f, 6, 0x0100);
        putU32(f, 8, f.length); putU16(f, 12, 0x10); putU16(f, 14, 1);
        putMagic(f, 16, "DATA"); putU32(f, 20, f.length - 16);
        putU32(f, 0x38, 1);
        f[0x3C] = type; putU16(f, 0x3D, body);
        putU16(f, body, type == 2 ? 3 : 0);
        putU16(f, body + 2, 0);
        f[body + 4] = 60;
        f[body + 5] = 127; f[body + 6] = 127; f[body + 7] = 127; f[body + 8] = 127;
        f[body + 9] = 64;
        return f;
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

    private static int peak(short[] pcm)
    {
        int p = 0;
        for (int i = 0; i < pcm.length; i++)
        {
            int a = Math.abs(pcm[i]);
            if (a > p) p = a;
        }
        return p;
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
