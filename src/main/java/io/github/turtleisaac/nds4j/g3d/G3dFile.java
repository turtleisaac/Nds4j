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

import io.github.turtleisaac.nds4j.framework.GenericNtrFile;
import io.github.turtleisaac.nds4j.framework.MemBuf;

import java.util.Arrays;
import java.util.Objects;

/**
 * Common container for the Nitro 3D ("G3D") file family (NSBMD, NSBTX, NSBCA, and the rest of the
 * {@code NSB*} formats). Every one of them is a generic NTR file whose header is followed by a table
 * of block offsets and then the blocks themselves (e.g. {@code MDL0}, {@code TEX0}).
 * <p>
 * This base reads that container and preserves each block verbatim, so a subclass gets byte-exact
 * {@link #save()} and value {@code equals()} for free and only has to parse the block(s) it cares
 * about as a view over {@link #block(int)}. Offsets within a block are relative to that block's first
 * byte.
 */
public abstract class G3dFile extends GenericNtrFile
{
    private long[] blockOffsets;
    private byte[][] blocks;
    private long fileSize;

    /**
     * @param magic the accepted NTR magic string(s) for this format (e.g. {@code "BMD0"})
     */
    protected G3dFile(String... magic)
    {
        super(magic);
    }

    /**
     * Reads the NTR header, the block offset table, and each block (verbatim) from the file data. A
     * subclass calls this first, then parses the blocks it needs.
     * @param data the raw file bytes
     */
    protected void readContainer(byte[] data)
    {
        MemBuf buf = MemBuf.create(data);
        MemBuf.MemBufReader reader = buf.reader();
        fileSize = data.length;

        readGenericNtrHeader(reader);

        blockOffsets = new long[numBlocks];
        for (int i = 0; i < numBlocks; i++)
            blockOffsets[i] = reader.readUInt32();

        blocks = new byte[numBlocks][];
        for (int i = 0; i < numBlocks; i++)
        {
            long start = blockOffsets[i];
            long end = (i + 1 < numBlocks) ? blockOffsets[i + 1] : fileSize;
            reader.setPosition(start);
            blocks[i] = reader.readBytes((int) (end - start));
        }
    }

    /**
     * Generates the file's byte representation, reproducing the container exactly (blocks are preserved
     * verbatim, so an unedited file round-trips byte-for-byte).
     * @return a <code>byte[]</code>
     */
    public byte[] save()
    {
        MemBuf buf = MemBuf.create();
        MemBuf.MemBufWriter writer = buf.writer();

        writeGenericNtrHeader(writer, fileSize, numBlocks);
        for (long offset : blockOffsets)
            writer.writeUInt32(offset);
        for (int i = 0; i < numBlocks; i++)
        {
            writer.setPosition((int) blockOffsets[i]);
            writer.write(blocks[i]);
        }
        writer.setPosition((int) fileSize);
        return buf.reader().getBuffer();
    }

    /**
     * Gets the verbatim bytes of block <code>i</code>, starting at the block's magic. Offsets parsed
     * out of the block are relative to this array's first byte.
     * @param i the block index
     * @return a <code>byte[]</code>
     */
    protected byte[] block(int i)
    {
        return blocks[i];
    }

    /**
     * Writes a byte into block {@code i} at {@code offset}, in place. This is the primitive the writer
     * side is built on: because {@link #save()} emits each block verbatim, a same-size edit made through
     * here is reflected exactly, so an <em>unedited</em> file still round-trips byte-for-byte while an
     * <em>edited</em> one saves to a valid file the game loads. Offsets are relative to the block's first
     * byte. (Edits that change a block's size need the offset table rebuilt and are not done here.)
     * @param i the block index
     * @param offset the byte offset within the block
     * @param value the byte value (low 8 bits used)
     */
    protected void writeBlockU8(int i, int offset, int value)
    {
        blocks[i][offset] = (byte) value;
    }

    /**
     * Writes a little-endian 16-bit value into block {@code i} at {@code offset}, in place. See
     * {@link #writeBlockU8}.
     * @param i the block index
     * @param offset the byte offset within the block
     * @param value the 16-bit value (low 16 bits used)
     */
    protected void writeBlockU16(int i, int offset, int value)
    {
        blocks[i][offset] = (byte) value;
        blocks[i][offset + 1] = (byte) (value >> 8);
    }

    /**
     * Finds the index of the first block whose four-byte magic equals <code>magic</code>, or -1.
     * @param magic a four-character block magic (e.g. {@code "TEX0"})
     * @return an <code>int</code>
     */
    protected int indexOfBlock(String magic)
    {
        for (int i = 0; i < blocks.length; i++)
        {
            byte[] b = blocks[i];
            if (b.length >= 4 && new String(b, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals(magic))
                return i;
        }
        return -1;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        G3dFile that = (G3dFile) o;
        return numBlocks == that.numBlocks
                && fileSize == that.fileSize
                && Arrays.equals(blockOffsets, that.blockOffsets)
                && Arrays.deepEquals(blocks, that.blocks);
    }

    @Override
    public int hashCode()
    {
        int result = Objects.hash(numBlocks, fileSize);
        result = 31 * result + Arrays.hashCode(blockOffsets);
        result = 31 * result + Arrays.deepHashCode(blocks);
        return result;
    }
}
