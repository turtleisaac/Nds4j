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

package io.github.turtleisaac.nds4j.binaries;

import io.github.turtleisaac.nds4j.framework.CodeCompression;
import io.github.turtleisaac.nds4j.framework.MemBuf;

import java.util.Arrays;

public abstract class CodeBinary
{
    private MemBuf physicalAddressBuffer;
    private MemBuf memoryAddressBuffer;

    private int bssSize;

    private boolean compressed;

    public CodeBinary(byte[] data, int ramStartAddress, int bssSize)
    {
        byte[] decompressed = CodeCompression.decompress(data);
        compressed = Arrays.equals(decompressed, data);
        physicalAddressBuffer = MemBuf.create(decompressed);
        memoryAddressBuffer = physicalAddressBuffer.derivative(ramStartAddress);
        this.bssSize = bssSize;
    }

    public MemBuf getPhysicalAddressBuffer()
    {
        return physicalAddressBuffer;
    }

    public MemBuf getMemoryAddressBuffer()
    {
        return memoryAddressBuffer;
    }

    void setPhysicalAddressBuffer(MemBuf physicalAddressBuffer)
    {
        this.physicalAddressBuffer = physicalAddressBuffer;
    }

    void setMemoryAddressBuffer(MemBuf memoryAddressBuffer)
    {
        this.memoryAddressBuffer = memoryAddressBuffer;
    }

    public int getRamStartAddress()
    {
        return memoryAddressBuffer.getBaseAddress();
    }
}
