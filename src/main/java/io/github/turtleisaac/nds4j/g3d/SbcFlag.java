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
 * Flag bits in the high three bits of an SBC command byte. Which operand they add depends on the opcode.
 */
enum SbcFlag
{
    /**
     * For NODEDESC, BB and BBY, the next byte is the stack slot that stores the result.
     * For POSSCALE, multiply by the inverse position scale.
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBCFLG_001"
     */
    NNS_G3D_SBCFLG_001(0x20),

    /**
     * For NODEDESC, BB and BBY, the next byte is the stack slot restored before the command.
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBCFLG_010"
     */
    NNS_G3D_SBCFLG_010(0x40);

    final int mask;

    /**
     * @param mask bits this flag occupies in the command byte
     */
    SbcFlag(int mask)
    {
        this.mask = mask;
    }

}
