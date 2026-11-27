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

import io.github.turtleisaac.nds4j.framework.MemBuf;

/**
 * Four geometry opcodes packed into one display-list word.
 * @see "GBATEK DS 3D Video, geometry command packing"
 */
final class DlWord
{
    private final int first;
    private final int second;
    private final int third;
    private final int fourth;

    private DlWord(int first, int second, int third, int fourth)
    {
        this.first = first;
        this.second = second;
        this.third = third;
        this.fourth = fourth;
    }

    /**
     * @param reader a reader on the opcode word
     * @return the four opcodes, low byte first
     * @see "GBATEK DS 3D Video, geometry command packing"
     */
    static DlWord read(MemBuf.MemBufReader reader)
    {
        // The GPU consumes the four opcodes before any of their parameters.
        int first = reader.readUInt8();
        int second = reader.readUInt8();
        int third = reader.readUInt8();
        int fourth = reader.readUInt8();

        return new DlWord(first, second, third, fourth);
    }

    /**
     * @param index 0 through 3
     * @return that opcode
     * @see "GBATEK DS 3D Video, geometry command packing"
     */
    int at(int index)
    {
        // Parameters follow the whole word, in this same order.
        if (index == 0)
            return first;

        if (index == 1)
            return second;

        if (index == 2)
            return third;

        return fourth;
    }
}
