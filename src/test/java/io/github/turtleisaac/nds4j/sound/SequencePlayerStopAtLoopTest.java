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

@DisplayName("SequencePlayer stopAtLoop")
public class SequencePlayerStopAtLoopTest
{
    @Test
    @DisplayName("a 0x94 looping track renders one playthrough instead of filling the cap")
    void stopAtLoopEndsAtJump()
    {
        // note-wait off, one note + rest, jump back to the note — an infinite BGM loop
        byte[] ev = new byte[] {
                (byte) 0xC7, 0x00,
                0x3C, 0x7F, 24,
                (byte) 0x80, 24,
                (byte) 0x94, 2, 0, 0 // jump to event offset 2 (the note)
        };
        SequencePlayer looping = player(ev);
        short[] filled = looping.renderStereo(22050, 2.0);
        assertThat(filled.length / 2).isGreaterThan(22050); // ~2s cap without stopAtLoop

        SequencePlayer once = player(ev);
        once.stopAtLoop = true;
        short[] cut = once.renderStereo(22050, 2.0);
        assertThat(cut.length / 2).isLessThan(22050); // two loop cycles + release, still well under 1s
        assertThat(once.dbgVoices).isGreaterThan(0);
        assertThat(once.loopStartFrame).isGreaterThan(0);
        assertThat(once.loopEndFrame).isGreaterThan(once.loopStartFrame);
        // second cycle should be about as long as the first (intro+body ≈ body)
        int period = once.loopEndFrame - once.loopStartFrame;
        assertThat(period).isGreaterThan(once.loopStartFrame / 4);
    }

    private static SequencePlayer player(byte[] ev)
    {
        short[] pcm = new short[64];
        for (int i = 0; i < pcm.length; i++) pcm[i] = 8000;
        WaveArchive arc = WaveArchive.fromBytes(swar(pcm));
        InstrumentBank bank = InstrumentBank.fromBytes(bank());
        return new SequencePlayer(Sequence.fromBytes(sseq(ev)), bank, new WaveArchive[] { arc, null, null, null });
    }

    private static byte[] sseq(byte[] ev)
    {
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

    private static byte[] swar(short[] pcm)
    {
        int info = 0x40, data = 0x4C;
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
        putU16(f, info + 2, 22050);
        putU16(f, info + 4, 760);
        putU32(f, info + 8, (pcm.length + 1) / 2);
        for (int i = 0; i < pcm.length; i++) putU16(f, data + i * 2, pcm[i] & 0xFFFF);
        return f;
    }

    private static byte[] bank()
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
