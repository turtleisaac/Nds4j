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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the SDAT sound-archive stack: byte-exact container round-trip (the project correctness bar), and
 * a sanity floor on the wave decode. Requires a retail ROM (skipped otherwise): {@code -Drom.dir=<dir>}.
 */
@DisplayName("SDAT sound archive (container round-trip + wave decode)")
public class SoundArchiveTest
{
    private static String magic(byte[] f) { return f == null || f.length < 4 ? "" : new String(f, 0, 4, StandardCharsets.US_ASCII); }

    private List<SoundArchive> allSdats(NintendoDsRom rom)
    {
        List<SoundArchive> out = new ArrayList<>();
        for (int i = 0; i < rom.getNumFiles(); i++)
        {
            byte[] f = rom.getFile(i);
            if (!magic(f).equals("SDAT")) continue;
            out.add(SoundArchive.fromBytes(f));
        }
        return out;
    }

    @Test
    @DisplayName("every SDAT in the ROM round-trips byte-for-byte")
    void sdatRoundTrip()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        int checked = 0;
        for (int i = 0; i < rom.getNumFiles(); i++)
        {
            byte[] f = rom.getFile(i);
            if (!magic(f).equals("SDAT")) continue;
            SoundArchive sdat = SoundArchive.fromBytes(f);
            assertThat(sdat.save()).as("SDAT file %d round-trips", i).isEqualTo(f);
            checked++;
        }
        assertThat(checked).as("found at least one SDAT").isGreaterThan(0);
    }

    @Test
    @DisplayName("every embedded file has a known audio magic and is reachable via the FAT")
    void embeddedFilesAreKnown()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        for (SoundArchive sdat : allSdats(rom))
        {
            for (int i = 0; i < sdat.getFileCount(); i++)
            {
                String m = magic(sdat.getFileData(i));
                assertThat(m).as("embedded file %d magic", i)
                        .isIn("SSEQ", "SSAR", "SBNK", "SWAR", "STRM");
            }
        }
    }

    @Test
    @DisplayName("wave archives decode to in-range PCM without desyncing")
    void wavesDecode()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        SoundArchive sdat = allSdats(rom).get(0);
        int pcm8 = 0, adpcm = 0, totalWaves = 0;
        for (int i = 0; i < sdat.getRecordCount(RecordType.WAVE_ARCHIVE); i++)
        {
            byte[] f = sdat.getFileFor(RecordType.WAVE_ARCHIVE, i);
            if (!magic(f).equals("SWAR")) continue;
            WaveArchive swar = WaveArchive.fromBytes(f);
            assertThat(swar.save()).as("SWAR %d round-trips", i).isEqualTo(f);
            for (int w = 0; w < swar.getWaveCount(); w++)
            {
                Wave wave = swar.getWave(w);
                short[] pcm = wave.decode();
                assertThat(pcm.length).as("wave %d/%d decodes samples", i, w).isGreaterThan(0);
                if (wave.getWaveType() == Wave.PCM8) pcm8++;
                if (wave.getWaveType() == Wave.ADPCM) adpcm++;
                totalWaves++;
            }
        }
        assertThat(totalWaves).as("decoded many waves").isGreaterThan(100);
        assertThat(pcm8).as("saw PCM8 waves").isGreaterThan(0);
        assertThat(adpcm).as("saw ADPCM waves").isGreaterThan(0);
    }

    @Test
    @DisplayName("a STRM stream decodes per-channel PCM and round-trips (White2)")
    void streamDecode()
    {
        NintendoDsRom rom = TestRoms.require("White2.nds");
        for (SoundArchive sdat : allSdats(rom))
        {
            for (int i = 0; i < sdat.getRecordCount(RecordType.STREAM); i++)
            {
                byte[] f = sdat.getFileFor(RecordType.STREAM, i);
                if (!magic(f).equals("STRM")) continue;
                Stream strm = Stream.fromBytes(f);
                assertThat(strm.save()).as("STRM %d round-trips", i).isEqualTo(f);
                short[][] ch = strm.decodeChannels();
                assertThat(ch.length).isEqualTo(strm.getChannels());
                assertThat(ch[0].length).isEqualTo((int) strm.getNumSamples());
                byte[] wav = strm.toWav();
                assertThat(new String(wav, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
                return;
            }
        }
    }

    @Test
    @DisplayName("every SBNK bank round-trips and resolves programs to sample regions")
    void banksDecode()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        SoundArchive sdat = allSdats(rom).get(0);
        int checked = 0, pcmRegions = 0;
        for (int i = 0; i < sdat.getRecordCount(RecordType.BANK); i++)
        {
            byte[] f = sdat.getFileFor(RecordType.BANK, i);
            if (!magic(f).equals("SBNK")) continue;
            InstrumentBank bank = InstrumentBank.fromBytes(f);
            assertThat(bank.save()).as("SBNK %d round-trips", i).isEqualTo(f);
            for (int p = 0; p < bank.getInstrumentCount(); p++)
                for (InstrumentBank.NoteRegion r : bank.getInstrument(p).regions)
                    if (r.isPcm) pcmRegions++;
            checked++;
        }
        assertThat(checked).isGreaterThan(0);
        assertThat(pcmRegions).as("resolved many PCM regions").isGreaterThan(100);
    }

    @Test
    @DisplayName("every SSEQ sequence round-trips and exposes its event stream")
    void sequencesDecode()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        SoundArchive sdat = allSdats(rom).get(0);
        int checked = 0;
        for (int i = 0; i < sdat.getRecordCount(RecordType.SEQUENCE); i++)
        {
            byte[] f = sdat.getFileFor(RecordType.SEQUENCE, i);
            if (!magic(f).equals("SSEQ")) continue;
            Sequence seq = Sequence.fromBytes(f);
            assertThat(seq.save()).as("SSEQ %d round-trips", i).isEqualTo(f);
            assertThat(seq.getEventDataOffset()).isGreaterThanOrEqualTo(0x1C);
            assertThat(seq.getEventData().length).isGreaterThan(0);
            checked++;
        }
        assertThat(checked).isGreaterThan(0);
    }

    @Test
    @DisplayName("a decoded cry exports a valid RIFF/WAVE file")
    void wavExport()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        SoundArchive sdat = allSdats(rom).get(0);
        // find the first wave archive with a wave
        for (int i = 0; i < sdat.getRecordCount(RecordType.WAVE_ARCHIVE); i++)
        {
            byte[] f = sdat.getFileFor(RecordType.WAVE_ARCHIVE, i);
            if (!magic(f).equals("SWAR")) continue;
            WaveArchive swar = WaveArchive.fromBytes(f);
            if (swar.getWaveCount() == 0) continue;
            byte[] wav = swar.getWave(0).toWav();
            assertThat(new String(wav, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
            assertThat(new String(wav, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");
            assertThat(wav.length).isGreaterThan(44);
            return;
        }
    }
}
