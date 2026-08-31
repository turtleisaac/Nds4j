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

/**
 * WAV round-trip (CI-safe) plus importing a WAV over a retail SWAR/SDAT (ROM-gated).
 */
@DisplayName("WAV ↔ SWAV import")
public class WavImportTest
{
    @Test
    @DisplayName("PCM16 WAV round-trips through WavFile.read")
    void wavRoundTrip()
    {
        short[] pcm = new short[] { 0, 1234, -32000, 32000, 50 };
        byte[] wav = WavFile.mono16(pcm, 22050);
        WavFile.Pcm got = WavFile.read(wav);
        assertThat(got.sampleRate).isEqualTo(22050);
        assertThat(got.sourceChannels).isEqualTo(1);
        assertThat(got.samples).isEqualTo(pcm);
    }

    @Test
    @DisplayName("stereo WAV downmixes to mono")
    void stereoDownmix()
    {
        short[] interleaved = { 100, 300, -100, 100 }; // L,R,L,R
        byte[] wav = WavFile.pcm16(interleaved, 2, 8000);
        WavFile.Pcm got = WavFile.read(wav);
        assertThat(got.sourceChannels).isEqualTo(2);
        assertThat(got.samples).containsExactly((short) 200, (short) 0);
    }

    @Test
    @DisplayName("PCM16 encode/decode through Wave.fromPcm is lossless")
    void pcm16EncodeDecode()
    {
        short[] pcm = new short[64];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (i * 400 - 8000);
        Wave w = Wave.fromPcm(pcm, 16000, false, 0, Wave.PCM16);
        assertThat(w.getWaveType()).isEqualTo(Wave.PCM16);
        assertThat(w.getRawSampleRate()).isEqualTo(16000);
        assertThat(w.decode()).isEqualTo(pcm);
        WavFile.Pcm back = WavFile.read(w.toWav());
        assertThat(back.samples).isEqualTo(pcm);
    }

    @Test
    @DisplayName("ADPCM encode/decode is close (greedy IMA)")
    void adpcmRoundTripClose()
    {
        short[] pcm = new short[200];
        for (int i = 0; i < pcm.length; i++)
            pcm[i] = (short) (Math.sin(i / 8.0) * 12000);
        Wave w = Wave.fromPcm(pcm, 22050, false, 0, Wave.ADPCM);
        short[] got = w.decode();
        assertThat(got.length).isGreaterThanOrEqualTo(pcm.length - 1);
        long err = 0;
        int n = Math.min(pcm.length, got.length);
        for (int i = 0; i < n; i++) err += Math.abs(pcm[i] - got[i]);
        assertThat(err / n).as("mean absolute error").isLessThan(800);
    }

    @Test
    @DisplayName("replacing a FAT file with itself rebuilds a parseable SDAT (Platinum)")
    void replaceFileIdentityParses()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        SoundArchive sdat = firstSdat(rom);
        byte[] original = sdat.getFileData(0);
        sdat.replaceFile(0, original);
        SoundArchive again = SoundArchive.fromBytes(sdat.save());
        assertThat(again.getFileCount()).isEqualTo(sdat.getFileCount());
        assertThat(again.getFileData(0)).isEqualTo(original);
        assertThat(again.getRecordCount(RecordType.SEQUENCE)).isGreaterThan(0);
    }

    @Test
    @DisplayName("importWav replaces a wave archive sample and the SDAT still parses")
    void importWavIntoSdat()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        SoundArchive sdat = firstSdat(rom);
        int arc = -1;
        for (int i = 0; i < sdat.getRecordCount(RecordType.WAVE_ARCHIVE); i++)
        {
            byte[] f = sdat.getFileFor(RecordType.WAVE_ARCHIVE, i);
            if (f != null && f.length >= 4 && magic(f).equals("SWAR"))
            {
                WaveArchive swar = WaveArchive.fromBytes(f);
                if (swar.getWaveCount() > 0) { arc = i; break; }
            }
        }
        assertThat(arc).isGreaterThanOrEqualTo(0);

        short[] pcm = new short[256];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) ((i % 32) * 800);
        byte[] wav = WavFile.mono16(pcm, 16000);
        sdat.importWav(arc, 0, wav);

        WaveArchive swar = WaveArchive.fromBytes(sdat.getFileFor(RecordType.WAVE_ARCHIVE, arc));
        assertThat(swar.getWave(0).getSampleCount()).isGreaterThan(0);
        SoundArchive again = SoundArchive.fromBytes(sdat.save());
        assertThat(again.getFileFor(RecordType.WAVE_ARCHIVE, arc)).isEqualTo(sdat.getFileFor(RecordType.WAVE_ARCHIVE, arc));
    }

    private static SoundArchive firstSdat(NintendoDsRom rom)
    {
        for (int i = 0; i < rom.getNumFiles(); i++)
        {
            byte[] f = rom.getFile(i);
            if (magic(f).equals("SDAT")) return SoundArchive.fromBytes(f);
        }
        throw new AssertionError("no SDAT in ROM");
    }

    private static String magic(byte[] f)
    {
        return f == null || f.length < 4 ? "" : new String(f, 0, 4, StandardCharsets.US_ASCII);
    }
}
