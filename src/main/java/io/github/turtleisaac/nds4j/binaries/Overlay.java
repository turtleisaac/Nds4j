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

public class Overlay extends CodeBinary
{
    private int ramSize;
    private int staticInitStart;
    private int staticInitEnd;
    private int fileId;
    private int compressedSize;
    private int flags;

    public Overlay(byte[] data, int ramAddress, int ramSize, int bssSize, int staticInitStart, int staticInitEnd, int fileId, int compressedSize, int flags)
    {
        super(data, ramAddress, bssSize);
        this.ramSize = ramSize;
        this.staticInitStart = staticInitStart;
        this.staticInitEnd = staticInitEnd;
        this.fileId = fileId;
        this.compressedSize = compressedSize;


        if (isCompressed())
        {
            MemBuf physicalAddressBuffer = MemBuf.create(CodeCompression.decompress(data));
        }

    }

    public boolean isCompressed()
    {
        return (flags & 1) == 1;
    }

    public void setCompressed(boolean value)
    {
        if (value)
            flags |= 1;
        else
            flags &= ~1;
    }
}
