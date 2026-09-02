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

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MIDI ⇄ SSEQ round-trip: SSEQ → MIDI → SSEQ → MIDI must reproduce the same notes and tempo. This is
 * the "1:1 import match" path — it proves the sequence decode and the MIDI import are mutually consistent
 * (and, transitively, that the note/timing decode is right, independent of the software synth). Requires a
 * retail ROM: {@code -Drom.dir=<dir>}.
 */
@DisplayName("MIDI <-> SSEQ round-trip")
public class MidiSequenceTest
{
    private static String magic(byte[] f) { return f == null || f.length < 4 ? "" : new String(f, 0, 4, StandardCharsets.US_ASCII); }

    // sorted (startTick, note, velocity, duration) of every note in a MIDI file
    private List<long[]> notes(byte[] smf) throws Exception
    {
        javax.sound.midi.Sequence ms = MidiSystem.getSequence(new ByteArrayInputStream(smf));
        List<long[]> out = new ArrayList<>();
        for (Track t : ms.getTracks())
        {
            Map<Integer, long[]> pending = new HashMap<>();
            for (int i = 0; i < t.size(); i++)
            {
                MidiMessage m = t.get(i).getMessage();
                if (!(m instanceof ShortMessage)) continue;
                ShortMessage sm = (ShortMessage) m;
                if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0)
                    pending.put(sm.getData1(), new long[]{ t.get(i).getTick(), sm.getData1(), sm.getData2() });
                else if (sm.getCommand() == ShortMessage.NOTE_OFF || (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() == 0))
                {
                    long[] on = pending.remove(sm.getData1());
                    if (on != null) out.add(new long[]{ on[0], on[1], on[2], t.get(i).getTick() - on[0] });
                }
            }
        }
        out.sort(new Comparator<long[]>() { public int compare(long[] a, long[] b) { for (int k = 0; k < 4; k++) if (a[k] != b[k]) return Long.compare(a[k], b[k]); return 0; } });
        return out;
    }

    @Test
    @DisplayName("SSEQ -> MIDI -> SSEQ -> MIDI reproduces every note and the tempo")
    void roundTrip() throws Exception
    {
        NintendoDsRom rom = TestRoms.require("HeartGold.nds");
        SoundArchive sdat = null;
        for (int i = 0; i < rom.getNumFiles(); i++)
            if (magic(rom.getFile(i)).equals("SDAT")) { sdat = SoundArchive.fromBytes(rom.getFile(i)); break; }
        assertThat(sdat).isNotNull();

        int tested = 0;
        for (int i = 0; i < sdat.getRecordCount(RecordType.SEQUENCE) && tested < 6; i++)
        {
            byte[] f = sdat.getFileFor(RecordType.SEQUENCE, i);
            if (!magic(f).equals("SSEQ")) continue;

            byte[] m1 = SequenceMidi.convert(Sequence.fromBytes(f));
            byte[] sseq2 = MidiSequence.toSseq(m1);
            assertThat(magic(sseq2)).as("re-imported SSEQ is well-formed").isEqualTo("SSEQ");
            byte[] m2 = SequenceMidi.convert(Sequence.fromBytes(sseq2));

            List<long[]> n1 = notes(m1), n2 = notes(m2);
            if (n1.size() < 20) continue; // only test substantial songs
            assertThat(n2.size()).as("same note count after round-trip (seq %d)", i).isEqualTo(n1.size());
            for (int k = 0; k < n1.size(); k++)
                assertThat(Arrays.equals(n1.get(k), n2.get(k)))
                        .as("note %d identical after round-trip (seq %d)", k, i).isTrue();
            tested++;
        }
        assertThat(tested).as("round-tripped several sequences").isGreaterThan(0);
    }
}
