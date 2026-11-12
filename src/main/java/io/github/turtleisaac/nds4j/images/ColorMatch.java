/*
 * Copyright (c) 2026 Turtleisaac.
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

package io.github.turtleisaac.nds4j.images;

import java.awt.Color;

/** Nearest-palette-color matching shared by the PNG import paths. */
final class ColorMatch
{
    private ColorMatch()
    {
    }

    /** Squared RGB distance between {@code c} and the RGB part of {@code argb}. */
    static long distanceSq(Color c, int argb)
    {
        long dr = ((argb >> 16) & 0xFF) - c.getRed();
        long dg = ((argb >> 8) & 0xFF) - c.getGreen();
        long db = (argb & 0xFF) - c.getBlue();
        return dr * dr + dg * dg + db * db;
    }

    /** Nearest color's index relative to {@code base}; on a tie, {@code preferred} wins if it is one of the nearest. */
    static int nearestIndex(Color[] colors, int base, int count, int argb, int preferred)
    {
        int best = 0;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < count; i++)
        {
            long d = distanceSq(colors[base + i], argb);
            if (d < bestDist || (d == bestDist && i == preferred))
            {
                bestDist = d;
                best = i;
            }
        }

        return best;
    }
}
