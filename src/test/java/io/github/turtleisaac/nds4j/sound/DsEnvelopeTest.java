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
import static org.assertj.core.api.Assertions.offset;

/**
 * Pins the DS envelope conversion to the hardware behaviour (matching VGMTrans). Pure math, no ROM needed.
 * The key correctness point: sustain is <b>logarithmic</b> — a sustain register of 64 is ~0.25 amplitude
 * (&minus;11.9&nbsp;dB), not the 0.50 a naive linear conversion would give.
 */
@DisplayName("DS envelope conversion (VGMTrans parity)")
public class DsEnvelopeTest
{
    @Test
    @DisplayName("sustain is the DS decibel curve, not linear")
    void sustainIsLogarithmic()
    {
        assertThat(DsEnvelope.sustainLevel(127)).isEqualTo(1.0);
        assertThat(DsEnvelope.sustainLevel(0)).isEqualTo(0.0);
        // DECIBEL_SQUARE_TABLE[64] = -119 -> -11.9 dB -> 10^(-11.9/20) ~= 0.254 (NOT the linear 0.504)
        assertThat(DsEnvelope.sustainLevel(64)).isCloseTo(0.254, offset(0.005));
        // strictly increasing
        assertThat(DsEnvelope.sustainLevel(96)).isGreaterThan(DsEnvelope.sustainLevel(64));
        assertThat(DsEnvelope.sustainLevel(32)).isLessThan(DsEnvelope.sustainLevel(64));
    }

    @Test
    @DisplayName("attack: high register = fast, low = slow (seconds)")
    void attackMonotonic()
    {
        double fast = DsEnvelope.attackSeconds(127);
        double slow = DsEnvelope.attackSeconds(0);
        assertThat(fast).isLessThan(0.02);      // near-instant
        assertThat(slow).isGreaterThan(1.0);    // multi-second
        assertThat(DsEnvelope.attackSeconds(100)).isLessThan(slow);
    }

    @Test
    @DisplayName("falling-rate is Gota DecayTable (not the VGMTrans formula)")
    void fallingRate()
    {
        assertThat(DsEnvelope.getFallingRate(0x7F)).isEqualTo(0xFFFF);
        assertThat(DsEnvelope.getFallingRate(0x7E)).isEqualTo(0x3C00);
        assertThat(DsEnvelope.getFallingRate(0)).isEqualTo(1);
        assertThat(DsEnvelope.getFallingRate(0x10)).isEqualTo(0x21);
        assertThat(DsEnvelope.getFallingRate(80)).isEqualTo(167);       // formula would be 166
        assertThat(DsEnvelope.getFallingRate(100)).isEqualTo(295);
        assertThat(DsEnvelope.decaySeconds(0x7F)).isEqualTo(0.001);
        assertThat(DsEnvelope.releaseSeconds(127)).isGreaterThan(0);
    }

    @Test
    @DisplayName("attackRate is Gota AttackTable")
    void attackTable()
    {
        assertThat(DsEnvelope.attackRate(0)).isEqualTo(255);
        assertThat(DsEnvelope.attackRate(109)).isEqualTo(143);
        assertThat(DsEnvelope.attackRate(127)).isEqualTo(0);
        assertThat(DsEnvelope.ATTACK_TABLE).hasSize(128);
        assertThat(DsEnvelope.DECAY_TABLE).hasSize(128);
    }

    @Test
    @DisplayName("Gota GetChannelVolume: full=127, silent=0")
    void channelVolume()
    {
        assertThat(DsEnvelope.channelVolume(0)).isEqualTo(127);
        assertThat(DsEnvelope.channelVolume(-92544)).isEqualTo(0);
        assertThat(DsEnvelope.channelVolume(-70000)).isEqualTo(0);
        assertThat(DsEnvelope.channelAmp(0)).isEqualTo(1.0);
        assertThat(DsEnvelope.channelAmp(-15232)).isLessThan(0.3);
        assertThat(DsEnvelope.channelAmp(-15232)).isGreaterThan(0.15);
    }
}
