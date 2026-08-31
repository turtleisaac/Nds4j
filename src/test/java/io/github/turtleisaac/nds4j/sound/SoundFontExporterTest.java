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

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Soundbank;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the SoundFont (.sf2) export: a bank must produce a file that a real SF2 reader
 * ({@code javax.sound.midi}, i.e. the same parser DAWs use) loads back with the expected instruments.
 * Requires a retail ROM: {@code -Drom.dir=<dir>}.
 */
@DisplayName("SBNK -> SoundFont 2 (.sf2) export")
public class SoundFontExporterTest
{
    private static String magic(byte[] f) { return f == null || f.length < 4 ? "" : new String(f, 0, 4, StandardCharsets.US_ASCII); }

    @Test
    @DisplayName("a bank exports an .sf2 that loads with its instruments intact")
    void exportsValidSoundFont() throws Exception
    {
        NintendoDsRom rom = TestRoms.require("HeartGold.nds");
        SoundArchive sdat = null;
        for (int i = 0; i < rom.getNumFiles(); i++)
            if (magic(rom.getFile(i)).equals("SDAT")) { sdat = SoundArchive.fromBytes(rom.getFile(i)); break; }
        assertThat(sdat).isNotNull();

        // pick a bank that actually has instruments (via a named sequence's bank)
        int bankId = -1;
        for (int i = 0; i < sdat.getRecordCount(RecordType.SEQUENCE); i++)
        {
            byte[] f = sdat.getFileFor(RecordType.SEQUENCE, i);
            if (!magic(f).equals("SSEQ")) continue;
            int b = sdat.getSequenceBankId(i);
            byte[] bf = sdat.getFileFor(RecordType.BANK, b);
            if (magic(bf).equals("SBNK") && InstrumentBank.fromBytes(bf).getInstrumentCount() > 10) { bankId = b; break; }
        }
        assertThat(bankId).as("found a bank with instruments").isGreaterThanOrEqualTo(0);

        byte[] sf2 = SoundFontExporter.fromBank(sdat, bankId, "test_bank");
        assertThat(new String(sf2, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");

        // load it back through the JVM's SoundFont reader (the same format DAWs consume)
        Soundbank sb = MidiSystem.getSoundbank(new ByteArrayInputStream(sf2));
        assertThat(sb).isNotNull();
        assertThat(sb.getInstruments().length).as("has instruments").isGreaterThan(5);
    }
}
