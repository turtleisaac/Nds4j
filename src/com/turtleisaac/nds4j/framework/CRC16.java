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

package com.turtleisaac.nds4j.framework;

public class CRC16
{
    public static int calculateCrc(byte... arr)
    {
        CRC16 crc16= new CRC16();

        for(byte b : arr)
        {
            crc16.update(b);
        }

        return crc16.getValue();
    }

    private int value = 0;

    public CRC16() {
    }

    public void update(byte var1) {
        int var2 = var1;

        for(int var4 = 7; var4 >= 0; --var4) {
            var2 <<= 1;
            int var3 = var2 >>> 8 & 1;
            if ((this.value & '耀') != 0) {
                this.value = (this.value << 1) + var3 ^ 4129;
            } else {
                this.value = (this.value << 1) + var3;
            }
        }

        this.value &= 65535;
    }

    public int getValue()
    {
        return value;
    }

    public void reset() {
        this.value = 0;
    }
}
