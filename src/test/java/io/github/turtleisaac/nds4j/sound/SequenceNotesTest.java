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

import io.github.turtleisaac.nds4j.NintendoDsRom;
import io.github.turtleisaac.nds4j.TestRoms;
import io.github.turtleisaac.nds4j.sound.SoundArchive.RecordType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SSEQ note extraction")
public class SequenceNotesTest
{
    @Test
    @DisplayName("a one-note sequence yields that note at tick 0")
    void oneNote()
    {
        // note-wait off, program 0, note 60 vel 127 dur 48, rest 48, end
        byte[] ev = new byte[] {
                (byte) 0xC7, 0x00,
                (byte) 0x81, 0x00,
                0x3C, 0x7F, 48,
                (byte) 0x80, 48,
                (byte) 0xFF
        };
        SequenceNotes.Result r = SequenceNotes.extract(sseq(ev));
        assertThat(r.notes).hasSize(1);
        SequenceNotes.Note n = r.notes.get(0);
        assertThat(n.track).isEqualTo(0);
        assertThat(n.tick).isEqualTo(0);
        assertThat(n.key).isEqualTo(60);
        assertThat(n.velocity).isEqualTo(127);
        assertThat(n.duration).isEqualTo(48);
        assertThat(r.ticks).isGreaterThanOrEqualTo(48);
    }

    @Test
    @DisplayName("note-wait-on advances the track by the note duration")
    void noteWaitOn()
    {
        // default note-wait on: two notes with no rests — second starts after first's duration
        byte[] ev = new byte[] {
                0x3C, 0x40, 24,
                0x3E, 0x40, 24,
                (byte) 0xFF
        };
        SequenceNotes.Result r = SequenceNotes.extract(sseq(ev));
        assertThat(r.notes).hasSize(2);
        assertThat(r.notes.get(0).tick).isEqualTo(0);
        assertThat(r.notes.get(1).tick).isEqualTo(24);
        assertThat(r.notes.get(1).key).isEqualTo(62);
    }

    @Test
    @DisplayName("0x93 track offsets are event-data indices, not file offsets")
    void spawnedTrackUsesEventOffset()
    {
        // alloc t0+t1, open t1 at the byte after track 0's end, each track plays one note
        byte[] ev = new byte[] {
                (byte) 0xFE, 0x03, 0x00,
                (byte) 0x93, 0x01, 16, 0, 0,
                (byte) 0xC7, 0x00,
                0x3C, 0x7F, 24,
                (byte) 0x80, 24,
                (byte) 0xFF,
                (byte) 0xC7, 0x00,
                0x40, 0x7F, 24,
                (byte) 0x80, 24,
                (byte) 0xFF
        };
        SequenceNotes.Result r = SequenceNotes.extract(sseq(ev));
        assertThat(r.notes).hasSize(2);
        assertThat(r.notes.get(0).track).isEqualTo(0);
        assertThat(r.notes.get(0).key).isEqualTo(60);
        assertThat(r.notes.get(1).track).isEqualTo(1);
        assertThat(r.notes.get(1).key).isEqualTo(64);
        assertThat(r.trackCount).isGreaterThanOrEqualTo(2);
        assertThat(r.loopStartTick).isEqualTo(-1);
    }

    @Test
    @DisplayName("0x94 records loop start/end ticks")
    void loopJumpTicks()
    {
        byte[] ev = new byte[] {
                (byte) 0xC7, 0x00,
                0x3C, 0x7F, 24,
                (byte) 0x80, 24,
                (byte) 0x94, 2, 0, 0
        };
        SequenceNotes.Result r = SequenceNotes.extract(sseq(ev));
        assertThat(r.loopStartTick).isEqualTo(0); // jump target is the note at tick 0
        assertThat(r.loopEndTick).isEqualTo(24); // rest 24, then 0x94 (note-wait off)
    }

    @Test
    @DisplayName("a multi-track retail SSEQ has notes on several tracks (HeartGold)")
    void retailSongUsesSpawnedTracks()
    {
        NintendoDsRom rom = TestRoms.require("HeartGold.nds");
        SoundArchive sdat = null;
        for (int i = 0; i < rom.getNumFiles(); i++)
        {
            byte[] f = rom.getFile(i);
            if (f.length >= 4 && new String(f, 0, 4, StandardCharsets.US_ASCII).equals("SDAT"))
            {
                sdat = SoundArchive.fromBytes(f);
                break;
            }
        }
        assertThat(sdat).isNotNull();
        boolean found = false;
        for (int i = 0; i < sdat.getRecordCount(RecordType.SEQUENCE); i++)
        {
            byte[] f = sdat.getFileFor(RecordType.SEQUENCE, i);
            if (f == null || f.length < 200) continue;
            SequenceNotes.Result r = SequenceNotes.extract(Sequence.fromBytes(f));
            int[] per = new int[16];
            for (int n = 0; n < r.notes.size(); n++) per[r.notes.get(n).track]++;
            int tracksWithNotes = 0;
            for (int t = 0; t < 16; t++) if (per[t] > 0) tracksWithNotes++;
            if (tracksWithNotes >= 4 && r.notes.size() > 80)
            {
                assertThat(per[0]).as("track 0 still has notes").isGreaterThan(0);
                assertThat(tracksWithNotes).as("spawned tracks must sound").isGreaterThanOrEqualTo(4);
                found = true;
                break;
            }
        }
        assertThat(found).as("found a dense multi-track sequence").isTrue();
    }

    private static Sequence sseq(byte[] ev)
    {
        byte[] f = new byte[0x1C + ev.length];
        f[0] = 'S'; f[1] = 'S'; f[2] = 'E'; f[3] = 'Q';
        f[4] = (byte) 0xFF; f[5] = (byte) 0xFE;
        f[6] = 0x00; f[7] = 0x01;
        int len = f.length;
        f[8] = (byte) len; f[9] = (byte) (len >> 8); f[10] = (byte) (len >> 16); f[11] = (byte) (len >> 24);
        f[12] = 0x10; f[13] = 0x00;
        f[14] = 0x01; f[15] = 0x00;
        f[16] = 'D'; f[17] = 'A'; f[18] = 'T'; f[19] = 'A';
        int block = len - 16;
        f[20] = (byte) block; f[21] = (byte) (block >> 8); f[22] = (byte) (block >> 16); f[23] = (byte) (block >> 24);
        f[24] = 0x1C; f[25] = 0x00; f[26] = 0x00; f[27] = 0x00;
        System.arraycopy(ev, 0, f, 0x1C, ev.length);
        return Sequence.fromBytes(f);
    }
}
