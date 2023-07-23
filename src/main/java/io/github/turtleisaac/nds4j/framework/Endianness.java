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

package io.github.turtleisaac.nds4j.framework;

public class Endianness
{
    public enum EndiannessType
    {
        LITTLE("<"),
        BIG(">");

        public final String symbol;

        EndiannessType(String s)
        {
            symbol = s;
        }
    }

    /**
     * Swaps the endianness of the provided <code>int</code>
     * @param num an <code>int</code>
     * @return an <code>int</code> with its endianness swapped from the original
     */
    public static int swapEndianness(int num)
    {
        byte[] bytes = new byte[] {
                (byte) (num & 0xff),
                (byte) ((num >> 8) & 0xff),
                (byte) ((num >> 16) & 0xff),
                (byte) ((num >> 24) & 0xff)
        };

        return (bytes[3] & 0xff) | ( (bytes[2] & 0xff) << 8) | ( (bytes[1] & 0xff) << 16) | ( (bytes[0] & 0xff) << 24);
    }

    /**
     * Swaps the endianness of the provided <code>short</code>
     * @param num a <code>short</code>
     * @return a <code>short</code> with its endianness swapped from the original
     */
    public static short swapEndianness(short num)
    {
        byte[] bytes = new byte[] {
                (byte) (num & 0xff),
                (byte) ((num >> 8) & 0xff)
        };

        return (short) (( (bytes[1] & 0xff) << 16) | ( (bytes[0] & 0xff) << 24));
    }
}


