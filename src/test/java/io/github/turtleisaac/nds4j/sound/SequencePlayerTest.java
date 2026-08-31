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
 * Exercises the whole SSEQ → SBNK → SWAR → PCM synthesis pipeline end-to-end: pick a real named song out
 * of the SDAT, render a few seconds, and assert it produced actual (non-silent) audio. This is a
 * <em>render</em> test (perceptual), not a byte-exact one. Requires a retail ROM: {@code -Drom.dir=<dir>}.
 */
@DisplayName("SSEQ software synthesizer (render to PCM)")
public class SequencePlayerTest
{
    private static String magic(byte[] f) { return f == null || f.length < 4 ? "" : new String(f, 0, 4, StandardCharsets.US_ASCII); }

    @Test
    @DisplayName("a sequence renders to non-silent stereo PCM through its bank and wave archives")
    void rendersAudio()
    {
        NintendoDsRom rom = TestRoms.require("HeartGold.nds");
        SoundArchive sdat = null;
        for (int i = 0; i < rom.getNumFiles(); i++)
        {
            byte[] f = rom.getFile(i);
            if (magic(f).equals("SDAT")) { sdat = SoundArchive.fromBytes(f); break; }
        }
        assertThat(sdat).isNotNull();

        // find the first sequence that wires up a valid bank and renders audible output
        int rendered = 0;
        long peak = 0;
        for (int i = 0; i < sdat.getRecordCount(RecordType.SEQUENCE) && rendered < 8; i++)
        {
            SequencePlayer player = SequencePlayer.forSequence(sdat, i);
            if (player == null) continue;
            short[] pcm = player.renderStereo(32768, 4.0);
            rendered++;
            for (short s : pcm) { long a = Math.abs(s); if (a > peak) peak = a; }
            if (peak > 1000 && player.dbgVoices > 0)
            {
                assertThat(pcm.length).as("stereo output has frames").isGreaterThan(0);
                assertThat(player.dbgVoices).as("notes turned into voices").isGreaterThan(0);
                return; // success
            }
        }
        assertThat(peak).as("some sequence produced audible audio (peak amplitude)").isGreaterThan(1000);
    }
}
