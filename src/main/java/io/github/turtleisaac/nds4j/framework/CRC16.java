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

public class CRC16
{
    public static int calculateCrc(byte... arr)
    {
        CRC16 crc16 = new CRC16();

        for (byte b : arr)
        {
            crc16.update(b);
        }

        return crc16.getValue();
    }

    private int value = 0xFFFF;

    public CRC16() {
    }

    public void update(byte b) {
        value ^= (b & 0xFF);
        for (int i = 0; i < 8; i++)
        {
            value = ((value & 1) != 0) ? (value >>> 1) ^ 0xA001 : (value >>> 1);
        }
        value &= 0xFFFF;
    }

    public int getValue()
    {
        return value;
    }

    public void reset() {
        this.value = 0xFFFF;
    }
}
