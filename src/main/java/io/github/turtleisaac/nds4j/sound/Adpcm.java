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

/**
 * The IMA-ADPCM step machine the DS sound hardware uses (the "type 2" wave encoding), shared by
 * {@link Wave} and {@link Stream}. Standard 4-bit IMA: a per-nibble adaptive step decodes to a signed
 * 16-bit sample and advances the step index. RE'd against GBATEK's sound chapter and ndspy.
 */
final class Adpcm
{
    private Adpcm() {}

    static final int[] INDEX =
        { -1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8 };
    static final int[] STEP = {
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66,
        73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449,
        494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272,
        2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442,
        11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767 };

    /**
     * Advance the ADPCM state by one 4-bit code.
     * @return a 2-element array {@code {newPredictor, newIndex}}
     */
    static int[] step(int predictor, int index, int code)
    {
        int st = STEP[index];
        int diff = st >> 3;
        if ((code & 1) != 0) diff += st >> 2;
        if ((code & 2) != 0) diff += st >> 1;
        if ((code & 4) != 0) diff += st;
        if ((code & 8) != 0) predictor -= diff; else predictor += diff;
        if (predictor < -0x8000) predictor = -0x8000;
        if (predictor > 0x7FFF) predictor = 0x7FFF;
        index += INDEX[code];
        if (index < 0) index = 0;
        if (index > 88) index = 88;
        return new int[] { predictor, index };
    }
}
