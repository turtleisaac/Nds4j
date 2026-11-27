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
 * Repeat and flip bits of a {@code TEXIMAGE_PARAM} word.
 * @see "GBATEK DS 3D Video, TEXIMAGE_PARAM"
 */
enum TexWrap
{
    /** Repeat along S. @see "GBATEK DS 3D Video, TEXIMAGE_PARAM" */
    REPEAT_S(1 << 16),

    /** Repeat along T. @see "GBATEK DS 3D Video, TEXIMAGE_PARAM" */
    REPEAT_T(1 << 17),

    /** Flip on every other S tile. @see "GBATEK DS 3D Video, TEXIMAGE_PARAM" */
    FLIP_S(1 << 18),

    /** Flip on every other T tile. @see "GBATEK DS 3D Video, TEXIMAGE_PARAM" */
    FLIP_T(1 << 19);

    final int mask;

    /**
     * @param mask bit this flag occupies in the texture parameter word
     */
    TexWrap(int mask)
    {
        this.mask = mask;
    }

    /**
     * @param param a texture image parameter
     * @return whether this bit is set
     * @see "GBATEK DS 3D Video, TEXIMAGE_PARAM"
     */
    boolean set(long param)
    {
        // A clear bit clamps, or does not flip.
        return (param & mask) != 0;
    }
}
