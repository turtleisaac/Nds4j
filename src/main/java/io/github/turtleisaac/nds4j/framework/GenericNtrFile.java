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
    // Whether bom was actually read from a file (readGenericNtrHeader), as opposed to left at its
    // int-default 0 by an in-memory/from-scratch instance. Distinguishes "this file's declared BOM is
    // genuinely 0x0000" (seen on two loose, non-NARC RGCN/RPCN files in Mario Kart DS -- not a standard
    // 0xFEFF/0xFFFE mark, but still the byte-order the rest of the header was written in, LITTLE here)
    // from "never parsed, so there's nothing to preserve" -- the same convention Palette.fileSize uses.
    private boolean bomParsed;

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
        bomParsed = true;
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
        // Retail files almost always declare the canonical mark for their own byte order, so recomputing
        // it is usually a no-op -- except two loose (non-NARC) files in Mario Kart DS whose BOM field is
        // literally 0x0000. Re-emit what was actually parsed rather than the canonical value once there
        // is one to preserve.
        if (bomParsed)
            bom = this.bom;

        if (this.version != 0)
        {
            version = this.version;
            if (endiannessOfBeginning == Endianness.EndiannessType.BIG)
            {
                version = (version & 0xFF) << 8 | (version >> 8) & 0xFF;
            }
        }

        int headerSize = this.headerSize != 0 ? this.headerSize : NTR_HEADER_SIZE;

        writer.writeString(magic[whichMagic]);
        writer.writeShort((short) bom);
        writer.writeShort((short) version);
        writer.writeUInt32(length);
        writer.writeShort((short) headerSize);
        writer.writeShort((short) numSections);
    }

    protected void copyValuesFromTemp(GenericNtrFile file)
    {
        endiannessOfBeginning = file.endiannessOfBeginning;
        whichMagic = file.whichMagic;
        bom = file.bom;
        bomParsed = file.bomParsed;
        version = file.version;
        fileSize = file.fileSize;
        headerSize = file.headerSize;
        numBlocks = file.numBlocks;
    }

    public Endianness.EndiannessType getEndiannessOfBeginning()
    {
        return endiannessOfBeginning;
    }

    public void setEndiannessOfBeginning(Endianness.EndiannessType endiannessOfBeginning)
    {
        this.endiannessOfBeginning = endiannessOfBeginning;
    }

    public int getBom()
    {
        return bom;
    }

    public void setBom(int bom)
    {
        this.bom = bom;
        this.bomParsed = true;
    }

    public int getVersion()
    {
        return version;
    }

    public void setVersion(int version)
    {
        this.version = version;
    }

    public long getFileSize()
    {
        return fileSize;
    }

    public void setFileSize(long fileSize)
    {
        this.fileSize = fileSize;
    }

    public int getHeaderSize()
    {
        return headerSize;
    }

    public void setHeaderSize(int headerSize)
    {
        this.headerSize = headerSize;
    }

    public int getNumBlocks()
    {
        return numBlocks;
    }

    public void setNumBlocks(int numBlocks)
    {
        this.numBlocks = numBlocks;
    }
}
