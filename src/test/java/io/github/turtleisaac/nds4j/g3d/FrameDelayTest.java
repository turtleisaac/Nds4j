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

package io.github.turtleisaac.nds4j.g3d;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** GIF delays for a run of SPL ticks. */
class FrameDelayTest
{
    /**
     * Thirty ticks at thirty per second last one second.
     */
    @Test
    @DisplayName("30 ticks at 30 per second sum to one second")
    void thirtyTicksAtThirtyPerSecondLastOneSecond()
    {
        int[] delays = new ParticleRenderer(8, 8).ticksPerSecond(30).frameDelaysCs(30);

        assertThat(sum(delays))
                .isEqualTo(100);

        for (int delay : delays)
        {
            assertThat(delay)
                    .isGreaterThanOrEqualTo(FrameDelay.MIN_CENTISECONDS);
        }
    }

    /**
     * Omitting the rate keeps the default delay on every frame.
     */
    @Test
    @DisplayName("no rate keeps the default delay")
    void missingRateKeepsTheDefaultDelay()
    {
        int[] delays = new ParticleRenderer(8, 8).frameDelaysCs(30);

        assertThat(sum(delays))
                .isEqualTo(30 * FrameDelay.DEFAULT_CENTISECONDS);
    }

    /**
     * Adds the delays.
     * @param delays centiseconds per frame
     * @return the total
     */
    private static int sum(int[] delays)
    {
        int total = 0;

        for (int delay : delays)
        {
            total += delay;
        }

        return total;
    }
}
