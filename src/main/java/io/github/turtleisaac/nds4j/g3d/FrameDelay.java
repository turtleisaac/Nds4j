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

/**
 * GIF delays for a sequence of SPL ticks.
 * Each delay is a whole number of centiseconds, and none is under {@link #MIN_CENTISECONDS},
 * because viewers treat a shorter delay as a tenth of a second.
 */
public final class FrameDelay
{
    /** Shortest delay a GIF frame is given, in centiseconds. */
    public static final int MIN_CENTISECONDS = 2;

    /** Delay used when no ticks-per-second rate is supplied. */
    public static final int DEFAULT_CENTISECONDS = 2;

    private FrameDelay()
    {
    }

    /**
     * One centisecond delay per tick.
     * Without a rate every delay is {@link #DEFAULT_CENTISECONDS}.
     * With a rate the delays are whole centiseconds whose running total tracks that many ticks per second.
     * @param ticks how many frames to time
     * @param ticksPerSecond playback rate, or {@code null} for the default delay
     * @return one delay per tick
     */
    public static int[] centiseconds(int ticks, Double ticksPerSecond)
    {
        // A negative count is an empty sequence.
        int count = Math.max(0, ticks);
        int[] delays = new int[count];

        // No rate keeps the fixed default delay.
        if (ticksPerSecond == null || !(ticksPerSecond > 0))
        {
            fillDefault(delays);
            return delays;
        }

        // Spend the time owed to each tick, never dropping under the GIF floor.
        fillTracked(delays, ticksPerSecond.doubleValue());
        return delays;
    }

    /**
     * Fills every delay with the default.
     * @param delays the array to fill
     */
    private static void fillDefault(int[] delays)
    {
        for (int i = 0; i < delays.length; i++)
        {
            delays[i] = DEFAULT_CENTISECONDS;
        }
    }

    /**
     * Fills delays so their sum stays near {@code ticks / ticksPerSecond} seconds.
     * @param delays the array to fill
     * @param ticksPerSecond playback rate
     */
    private static void fillTracked(int[] delays, double ticksPerSecond)
    {
        // Centiseconds still owed, carried from one tick to the next.
        double owed = 0;
        double step = 100.0 / ticksPerSecond;

        for (int i = 0; i < delays.length; i++)
        {
            owed += step;
            int whole = (int) Math.floor(owed);

            // A viewer clamps a delay under two centiseconds, so the floor stays at two.
            if (whole < MIN_CENTISECONDS)
            {
                whole = MIN_CENTISECONDS;
            }

            delays[i] = whole;
            owed -= whole;
        }
    }
}
