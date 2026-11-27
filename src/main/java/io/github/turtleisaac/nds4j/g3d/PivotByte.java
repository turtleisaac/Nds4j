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
 * Low byte of a pivot-compressed rotation. The cell index is the low four bits.
 * The same bits sit at 4-10 of {@link NodeSrtFlag}.
 * @see "NitroSystem g3d/src/sbc.c getPivotMatrix"
 */
enum PivotByte
{
    /** The pivot cell is -1. @see "NitroSystem g3d/src/sbc.c getPivotMatrix" */
    MINUS(0x10),

    /** Negate reconstructed value c. @see "NitroSystem g3d/src/sbc.c getPivotMatrix" */
    SIGN_C(0x20),

    /** Negate reconstructed value d. @see "NitroSystem g3d/src/sbc.c getPivotMatrix" */
    SIGN_D(0x40);

    /** Bits that select the pivot cell. */
    static final int CELL = 0x0F;

    final int mask;

    /**
     * @param mask bit this flag occupies in the pivot byte
     */
    PivotByte(int mask)
    {
        this.mask = mask;
    }

    /**
     * @param flags the pivot byte
     * @return whether this bit is set
     * @see "NitroSystem g3d/src/sbc.c getPivotMatrix"
     */
    boolean set(int flags)
    {
        // A clear sign bit keeps the stored value.
        int masked = flags & mask;

        return masked != 0;
    }
}
