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
 * Flag in a texture-to-material or palette-to-material dictionary record.
 * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNSG3dResMat"
 */
enum MatDictFlag
{
    /** The entry has no material list. */
    UNBOUND(0x01000000L);

    final long mask;

    /**
     * @param mask bit this flag occupies in the record word
     */
    MatDictFlag(long mask)
    {
        this.mask = mask;
    }

    /**
     * @param entry the record word
     * @return whether this bit is set
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNSG3dResMat"
     */
    boolean set(long entry)
    {
        // A set bit means the name is present but unused.
        return (entry & mask) != 0;
    }
}
