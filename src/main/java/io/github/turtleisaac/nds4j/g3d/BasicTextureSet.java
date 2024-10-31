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

import java.util.ArrayList;

/**
 * An object representation of an NSBTX (BTX0) file
 */
public class BasicTextureSet extends GenericG3dFile
{
    public BasicTextureSet(byte[] data)
    {
        super("BTX0");

        MemBuf dataBuf = MemBuf.create(data);
        MemBuf.MemBufReader reader = dataBuf.reader();
        int fileSize = dataBuf.writer().getPosition();

        readGenericNtrHeader(reader);

        // reader position is now 0x10

        ArrayList<Long> offsets = readDataOffsets(reader);

        String textureMagic = reader.readString(4);

        if (!textureMagic.equals("TEX0")) {
            throw new RuntimeException("Not a valid NSBTX file.");
        }

        long textureSectionSize = reader.readUInt32();

        reader.skip(4);

        int textureDataSize = reader.readShort() << 3;
    }
}
