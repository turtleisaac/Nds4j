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
 * Which shared rotation pool an NSBCA index addresses. Bit 15 selects the pool.
 * @see "NitroSystem g3d/src/anm/nsbca.c getRotData_"
 */
enum RotPool
{
    /** Five stored values and a reconstructed cross-product row. */
    FIVE,

    /** The six-byte pivot form. */
    PIVOT;

    /** Bit that selects {@link #PIVOT}. */
    private static final int PIVOT_BIT = 0x8000;

    /** Bits that index the chosen pool. */
    private static final int INDEX = 0x7FFF;

    /**
     * @param raw the u16 stored in a rotation track
     * @return the pool that index addresses
     * @see "NitroSystem g3d/src/anm/nsbca.c getRotData_"
     */
    static RotPool of(int raw)
    {
        // Bit 15 set means the 6-byte pivot pool.
        if ((raw & PIVOT_BIT) != 0)
            return PIVOT;

        return FIVE;
    }

    /**
     * @param raw the u16 stored in a rotation track
     * @return the entry index in {@link #of(int)}
     * @see "NitroSystem g3d/src/anm/nsbca.c getRotData_"
     */
    static int index(int raw)
    {
        // The low 15 bits are the entry number.
        return raw & INDEX;
    }
}
