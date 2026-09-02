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
 * Pins the DS synthesis primitives (LFO sine, pitch scaling, PSG square, noise LFSR) to the hardware
 * behaviour matching Gota7's GotaSequenceLib / NitroStudio2. Pure math, no ROM needed.
 */
@DisplayName("DS synthesis primitives (DsSynth)")
public class DsSynthTest
{
    @Test
    @DisplayName("LFO sine table: zero crossings and peaks at the right phases")
    void sine()
    {
        assertThat(DsSynth.sin(0x00)).isEqualTo(0);
        assertThat(DsSynth.sin(0x20)).isEqualTo(127);   // +peak at quarter
        assertThat(DsSynth.sin(0x40)).isEqualTo(0);     // zero at half
        assertThat(DsSynth.sin(0x60)).isEqualTo(-127);  // -peak at three-quarters
        assertThat(DsSynth.sin(0x7F)).isLessThan(0);
    }

    @Test
    @DisplayName("pitch scaling is 2^(units/768) per octave")
    void pitch()
    {
        assertThat(DsSynth.pitchMultiplier(0)).isEqualTo(1.0);
        assertThat(DsSynth.pitchMultiplier(768)).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(DsSynth.pitchMultiplier(-768)).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(DsSynth.noteFrequency(69)).isCloseTo(440.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("PSG square honors the duty cycle over its 8-step counter")
    void psgSquare()
    {
        // duty 3 (50%): counter 0..3 low, 4..7 high
        for (int c = 0; c <= 3; c++) assertThat(DsSynth.psgSquare(c, 3)).isEqualTo((short) -0x8000);
        for (int c = 4; c <= 7; c++) assertThat(DsSynth.psgSquare(c, 3)).isEqualTo((short) 0x7FFF);
        // duty 0 (12.5%): only counter 0 is low
        assertThat(DsSynth.psgSquare(0, 0)).isEqualTo((short) -0x8000);
        assertThat(DsSynth.psgSquare(1, 0)).isEqualTo((short) 0x7FFF);
    }

    @Test
    @DisplayName("noise LFSR advances and is not a constant")
    void noise()
    {
        int[] lfsr = { 0x7FFF };
        boolean sawPos = false, sawNeg = false;
        for (int i = 0; i < 64; i++)
        {
            short s = DsSynth.noiseStep(lfsr);
            if (s > 0) sawPos = true;
            if (s < 0) sawNeg = true;
        }
        assertThat(sawPos && sawNeg).as("noise produces both polarities").isTrue();
        assertThat(lfsr[0]).isNotEqualTo(0x7FFF);   // state advanced
    }

    @Test
    @DisplayName("LFO phase advances and wraps within the 7-bit sine index")
    void lfoPhase()
    {
        int phase = 0;
        for (int i = 0; i < 10; i++) phase = DsSynth.advanceLfoPhase(phase, 16);
        assertThat(phase & 0xFFFF).isNotEqualTo(0);
        assertThat((phase >> 8) & 0x80).isEqualTo(0); // index stays within 0..0x7F
    }

    @Test
    @DisplayName("GetChannelTimer uses the 768-entry pitch table, not 2^(-p/768)")
    void channelTimer()
    {
        assertThat(DsSynth.channelTimer(511, 0)).isEqualTo(511);
        assertThat(DsSynth.channelTimer(511, 64)).isEqualTo(482);
        assertThat(DsSynth.channelTimer(511, -64)).isEqualTo(541);
        assertThat(DsSynth.channelTimer(511, 768)).isEqualTo(255);
        assertThat(DsSynth.channelTimer(511, -768)).isEqualTo(1022);
        assertThat(DsSynth.channelTimer(8006, 0)).isEqualTo(8006);
        assertThat(DsSynth.channelTimer(8006, 64)).isEqualTo(7556);
        assertThat(DsSynth.channelTimer(16, 0)).isEqualTo(16);
        assertThat(DsSynth.channelTimer(0x1000, 300)).isEqualTo(3124);
        assertThat(DsSynth.MIX_RATE).isEqualTo(65456);
        assertThat(DsSynth.MIX_SAMPLES_PER_FRAME).isEqualTo(341);
    }
}
