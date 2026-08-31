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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates SSEQ → Standard MIDI File export: the emitted file must parse as a well-formed SMF (via
 * {@code javax.sound.midi}) with a 48-PPQN division, a tempo, and real notes. Requires a retail ROM:
 * {@code -Drom.dir=<dir>}.
 */
@DisplayName("SSEQ -> Standard MIDI File export")
public class SequenceMidiTest
{
    private static String magic(byte[] f) { return f == null || f.length < 4 ? "" : new String(f, 0, 4, StandardCharsets.US_ASCII); }

    @Test
    @DisplayName("a sequence exports a well-formed MIDI with notes and tempo")
    void exportsValidMidi() throws Exception
    {
        NintendoDsRom rom = TestRoms.require("HeartGold.nds");
        SoundArchive sdat = null;
        for (int i = 0; i < rom.getNumFiles(); i++)
            if (magic(rom.getFile(i)).equals("SDAT")) { sdat = SoundArchive.fromBytes(rom.getFile(i)); break; }
        assertThat(sdat).isNotNull();

        // find a sequence with a decent number of notes and check its MIDI
        for (int i = 0; i < sdat.getRecordCount(RecordType.SEQUENCE); i++)
        {
            byte[] f = sdat.getFileFor(RecordType.SEQUENCE, i);
            if (!magic(f).equals("SSEQ")) continue;
            byte[] smf = SequenceMidi.convert(Sequence.fromBytes(f));
            javax.sound.midi.Sequence ms = MidiSystem.getSequence(new ByteArrayInputStream(smf));

            int notes = 0; boolean tempo = false;
            for (Track t : ms.getTracks())
                for (int k = 0; k < t.size(); k++)
                {
                    MidiMessage m = t.get(k).getMessage();
                    if (m instanceof ShortMessage)
                    {
                        ShortMessage sm = (ShortMessage) m;
                        if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) notes++;
                    }
                    else if (m instanceof MetaMessage && ((MetaMessage) m).getType() == 0x51) tempo = true;
                }

            if (notes > 50)
            {
                assertThat(ms.getResolution()).as("48 PPQN").isEqualTo(48);
                assertThat(tempo).as("has a tempo event").isTrue();
                assertThat(notes).as("has real notes").isGreaterThan(50);
                return;
            }
        }
        throw new AssertionError("no sequence with enough notes found to validate MIDI export");
    }

    @Test
    @DisplayName("DAW export gives one clean, named instrument per track with the same notes")
    void dawExportOneInstrumentPerTrack() throws Exception
    {
        NintendoDsRom rom = TestRoms.require("HeartGold.nds");
        SoundArchive sdat = null;
        for (int i = 0; i < rom.getNumFiles(); i++)
            if (magic(rom.getFile(i)).equals("SDAT")) { sdat = SoundArchive.fromBytes(rom.getFile(i)); break; }
        assertThat(sdat).isNotNull();

        for (int i = 0; i < sdat.getRecordCount(RecordType.SEQUENCE); i++)
        {
            byte[] f = sdat.getFileFor(RecordType.SEQUENCE, i);
            if (!magic(f).equals("SSEQ")) continue;
            Sequence seq = Sequence.fromBytes(f);
            int plainNotes = countNotes(SequenceMidi.convert(seq));
            if (plainNotes < 100) continue; // exercise a real, multi-instrument song

            javax.sound.midi.Sequence daw = MidiSystem.getSequence(new ByteArrayInputStream(SequenceMidi.convertForDaw(seq)));
            int dawNotes = 0, namedTracks = 0;
            for (Track t : daw.getTracks())
            {
                int progChanges = 0, notes = 0; boolean named = false;
                for (int k = 0; k < t.size(); k++)
                {
                    MidiMessage m = t.get(k).getMessage();
                    if (m instanceof ShortMessage)
                    {
                        ShortMessage sm = (ShortMessage) m;
                        if (sm.getCommand() == ShortMessage.PROGRAM_CHANGE) progChanges++;
                        if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) notes++;
                    }
                    else if (m instanceof MetaMessage && ((MetaMessage) m).getType() == 0x03) named = true;
                }
                dawNotes += notes;
                if (notes > 0)
                {
                    assertThat(progChanges).as("track has exactly one program change").isEqualTo(1);
                    assertThat(named).as("track is named").isTrue();
                    namedTracks++;
                }
            }
            assertThat(dawNotes).as("DAW export preserves every note").isEqualTo(plainNotes);
            assertThat(namedTracks).as("split into multiple instrument tracks").isGreaterThan(1);
            return;
        }
    }

    private int countNotes(byte[] smf) throws Exception
    {
        javax.sound.midi.Sequence ms = MidiSystem.getSequence(new ByteArrayInputStream(smf));
        int n = 0;
        for (Track t : ms.getTracks())
            for (int k = 0; k < t.size(); k++)
            {
                MidiMessage m = t.get(k).getMessage();
                if (m instanceof ShortMessage && ((ShortMessage) m).getCommand() == ShortMessage.NOTE_ON && ((ShortMessage) m).getData2() > 0) n++;
            }
        return n;
    }
}
