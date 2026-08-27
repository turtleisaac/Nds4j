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
import java.util.List;

/**
 * A Nitro 3D resource dictionary ({@code NNS_G3dResDict}) &mdash; the named-lookup structure shared by
 * every {@code NSB*} file. It maps a short name to a fixed-size record; the record's size (the
 * "element size") depends on what the dictionary indexes (8 bytes per texture, 4 per palette, and so
 * on). On disk it is a header, a Patricia search tree used only for fast lookup, then the record data
 * and finally the 16-byte names.
 * <p>
 * Only the names and the record bytes are meaningful to a reader; the Patricia tree is an acceleration
 * structure derived from the names, so it is preserved verbatim (as {@link #rawTree}) rather than
 * interpreted, which keeps a file byte-exact without this class needing to rebuild the tree.
 */
public class G3dDictionary
{
    /** The length of a name entry on disk, in bytes. */
    public static final int NAME_LENGTH = 16;

    private int revision;
    private int elementSize;
    private int ofsData; // the u16 that follows elementSize (offset from that field to the record data)

    // The bytes between the 2-byte header (revision, count) and the elementSize field: the dictionary
    // size half-word plus the Patricia tree. Preserved verbatim; see the class comment.
    private byte[] rawTree;

    private final List<byte[]> records = new ArrayList<>();
    private final List<String> names = new ArrayList<>();

    /**
     * Parses a dictionary from the reader, which must be positioned at the dictionary's first byte.
     * @param reader a {@link MemBuf.MemBufReader}
     */
    public G3dDictionary(MemBuf.MemBufReader reader)
    {
        revision = reader.readByte();
        int count = reader.readByte();

        // 10 fixed bytes (dictionary size half-word + Patricia tree header) then one 4-byte tree node
        // per entry. All of it is derived from the names, so it is kept as-is.
        rawTree = reader.readBytes(10 + count * 4);

        elementSize = reader.readUInt16();
        ofsData = reader.readUInt16();

        for (int i = 0; i < count; i++)
            records.add(reader.readBytes(elementSize));
        for (int i = 0; i < count; i++)
            names.add(readName(reader));
    }

    private static String readName(MemBuf.MemBufReader reader)
    {
        byte[] raw = reader.readBytes(NAME_LENGTH);
        int len = 0;
        while (len < raw.length && raw[len] != 0)
            len++;
        return new String(raw, 0, len, java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * Writes this dictionary at the writer's current position, reproducing it byte-for-byte.
     * @param writer a {@link MemBuf.MemBufWriter}
     */
    public void write(MemBuf.MemBufWriter writer)
    {
        writer.writeByte((byte) revision);
        writer.writeByte((byte) records.size());
        writer.write(rawTree);
        writer.writeShort((short) elementSize);
        writer.writeShort((short) ofsData);
        for (byte[] record : records)
            writer.write(record);
        for (String name : names)
        {
            byte[] field = new byte[NAME_LENGTH];
            byte[] bytes = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            System.arraycopy(bytes, 0, field, 0, Math.min(bytes.length, NAME_LENGTH));
            writer.write(field);
        }
    }

    /**
     * Gets the number of entries in this dictionary.
     * @return an <code>int</code>
     */
    public int size()
    {
        return records.size();
    }

    /**
     * Gets the name of entry <code>i</code>.
     * @param i the entry index
     * @return a <code>String</code>
     */
    public String getName(int i)
    {
        return names.get(i);
    }

    /**
     * Gets the raw fixed-size record bytes for entry <code>i</code>.
     * @param i the entry index
     * @return a <code>byte[]</code> of length {@link #getElementSize()}
     */
    public byte[] getRecord(int i)
    {
        return records.get(i);
    }

    /**
     * Gets the size, in bytes, of each record in this dictionary.
     * @return an <code>int</code>
     */
    public int getElementSize()
    {
        return elementSize;
    }
}
