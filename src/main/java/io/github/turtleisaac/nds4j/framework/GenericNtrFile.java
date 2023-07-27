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

import java.util.Arrays;

public class GenericNtrFile
{
    protected static final int NTR_HEADER_SIZE = 0x10;

    protected final String[] magic;
    protected int whichMagic;

    protected Endianness.EndiannessType endiannessOfBeginning;
    protected int bom;
    protected int version;
    protected long fileSize;
    protected int headerSize;
    protected int numBlocks;

    public GenericNtrFile(String... magic)
    {
        this.magic = magic;
        endiannessOfBeginning = Endianness.EndiannessType.LITTLE;
    }

    public void readGenericNtrHeader(MemBuf.MemBufReader reader)
    {
        String magic = reader.readString(4);

        boolean matches = false;
        int idx = 0;
        for (String s : this.magic)
        {
            if (magic.equals(s)) {
                whichMagic = idx;
                matches = true;
                break;
            }
            idx++;
        }

        if (!matches)
            throw new RuntimeException("Not a " + Arrays.toString(this.magic) + " file.");

        bom = reader.readUInt16();
        version = reader.readUInt16();
        fileSize = reader.readUInt32();
        headerSize = reader.readUInt16();
        numBlocks = reader.readUInt16();

        // some games use big endian, some use little - NSMB uses big for example, but Spirit Tracks uses little
        if (bom == 0xFFFE) {
            endiannessOfBeginning = Endianness.EndiannessType.BIG;
            version = (version & 0xFF) << 8 | version >> 8;
        }
    }

    public void writeGenericNtrHeader(MemBuf.MemBufWriter writer, long length, int numSections)
    {
        int bom = 0xFEFF;
        int version = 1;
        if (endiannessOfBeginning == Endianness.EndiannessType.BIG)
        {
            bom = 0xFFFE;
            version = 0x100;
        }

        writer.writeString(magic[whichMagic]);
        writer.writeShort((short) bom);
        writer.writeShort((short) version);
        writer.writeUInt32(length);
        writer.writeShort((short) NTR_HEADER_SIZE);
        writer.writeShort((short) numSections);
    }

    protected void copyValuesFromTemp(GenericNtrFile file)
    {
        endiannessOfBeginning = file.endiannessOfBeginning;
        whichMagic = file.whichMagic;
        bom = file.bom;
        version = file.version;
        fileSize = file.fileSize;
        headerSize = file.headerSize;
        numBlocks = file.numBlocks;
    }
}
