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

package io.github.turtleisaac.nds4j.images;

import io.github.turtleisaac.nds4j.framework.GenericNtrFile;
import io.github.turtleisaac.nds4j.framework.MemBuf;

import java.util.Arrays;
import java.util.Objects;

/**
 * An object representation of an NSCR file (a tilemap "screen").
 * <p>
 * An NSCR ("RCSN") is a background layer: a grid of 8x8 tile cells laid out left-to-right,
 * top-to-bottom. Each cell is a 16-bit entry that names a tile in a companion {@link IndexedImage}
 * (NCGR), the palette to draw it with, and whether to flip it. The screen is meaningless without
 * that NCGR and an {@link Palette} (NCLR) to supply the colours.
 * <p>
 * Entries are exposed as a raw {@code short[]}; the bitfields of an individual entry can be read and
 * written with {@link #getTileIndex(int)}, {@link #getPaletteIndex(int)},
 * {@link #isHorizontalFlip(int)}, {@link #isVerticalFlip(int)} and their setters.
 */
public class Screen extends GenericNtrFile
{
    private int width;
    private int height;

    // The 32-bit word following the dimensions in the NRCS block. It selects the screen's colour mode
    // and format (retail files use a handful of distinct values); nothing here needs to interpret it,
    // so it is preserved raw to keep an unedited screen byte-for-byte identical.
    private long screenFormat;

    // One 16-bit entry per 8x8 tile cell, in reading order (row-major). Bit layout:
    //   bits 0-9   tile index into the companion NCGR
    //   bit 10     horizontal flip
    //   bit 11     vertical flip
    //   bits 12-15 palette index (16-colour sub-palette)
    private short[] entries;

    private static final int TILE_INDEX_MASK = 0x03FF;
    private static final int H_FLIP_BIT = 0x0400;
    private static final int V_FLIP_BIT = 0x0800;
    private static final int PALETTE_SHIFT = 12;
    private static final int PALETTE_MASK = 0xF;

    /**
     * Generates an object representation of an NSCR file
     * @param data a <code>byte[]</code> representation of an NSCR file
     */
    public Screen(byte[] data)
    {
        super("RCSN");
        MemBuf dataBuf = MemBuf.create(data);
        MemBuf.MemBufReader reader = dataBuf.reader();

        readGenericNtrHeader(reader);

        // reader position is now 0x10

        String screenMagic = reader.readString(4); // 0x10
        if (!screenMagic.equals("NRCS"))
            throw new RuntimeException("Not a valid RCSN file.");

        long screenSectionSize = reader.readUInt32(); // 0x14
        width = reader.readUInt16(); // 0x18
        height = reader.readUInt16(); // 0x1A
        screenFormat = reader.readUInt32(); // 0x1C
        long dataSize = reader.readUInt32(); // 0x20

        if (dataSize % 2 != 0)
            throw new RuntimeException("Not a valid RCSN file: screen data size " + dataSize + " is not a multiple of 2.");

        entries = new short[(int) (dataSize / 2)];
        for (int i = 0; i < entries.length; i++)
            entries[i] = reader.readShort();

        // Any bytes between the declared data and the end of the block are alignment padding (retail
        // files pad the screen data to a 4-byte boundary with zeros). They carry no screen data and
        // save() regenerates them, so they are not stored; screenSectionSize is otherwise unused.
    }

    /**
     * Generate a <code>byte[]</code> representation of this <code>Screen</code> as an NSCR
     * @return a <code>byte[]</code>
     */
    public byte[] save()
    {
        MemBuf dataBuf = MemBuf.create();
        MemBuf.MemBufWriter writer = dataBuf.writer();

        writer.skip(NTR_HEADER_SIZE);

        writer.writeString("NRCS");
        int sizePos = writer.getPosition();
        writer.skip(4); // section size, filled in below
        writer.writeShort((short) width);
        writer.writeShort((short) height);
        writer.writeUInt32(screenFormat);
        writer.writeUInt32((long) entries.length * 2);

        for (short entry : entries)
            writer.writeShort(entry);

        // Pad the screen data back to a 4-byte boundary with zeros, matching retail files.
        writer.align(4, (byte) 0);

        int sectionEnd = writer.getPosition();
        writer.setPosition(sizePos);
        writer.writeInt(sectionEnd - NTR_HEADER_SIZE);
        writer.setPosition(sectionEnd);

        int fileSize = writer.getPosition();
        writer.setPosition(0);
        writeGenericNtrHeader(writer, fileSize, numBlocks);
        writer.setPosition(fileSize);

        return dataBuf.reader().getBuffer();
    }

    /**
     * Gets the width of this screen, in pixels.
     * @return an <code>int</code>
     */
    public int getWidth()
    {
        return width;
    }

    /**
     * Sets the width of this screen, in pixels.
     * @param width an <code>int</code>
     */
    public void setWidth(int width)
    {
        this.width = width;
    }

    /**
     * Gets the height of this screen, in pixels.
     * @return an <code>int</code>
     */
    public int getHeight()
    {
        return height;
    }

    /**
     * Sets the height of this screen, in pixels.
     * @param height an <code>int</code>
     */
    public void setHeight(int height)
    {
        this.height = height;
    }

    /**
     * Gets the number of 8x8 tile entries in this screen.
     * @return an <code>int</code>
     */
    public int getNumEntries()
    {
        return entries.length;
    }

    /**
     * Gets a copy of this screen's raw 16-bit tile entries, in row-major order.
     * @return a <code>short[]</code>
     */
    public short[] getEntries()
    {
        return Arrays.copyOf(entries, entries.length);
    }

    /**
     * Sets this screen's raw 16-bit tile entries.
     * @param entries a <code>short[]</code>
     */
    public void setEntries(short[] entries)
    {
        this.entries = entries;
    }

    /**
     * Gets the raw 32-bit screen format/colour-mode word.
     * @return a <code>long</code>
     */
    public long getScreenFormat()
    {
        return screenFormat;
    }

    /**
     * Sets the raw 32-bit screen format/colour-mode word.
     * @param screenFormat a <code>long</code>
     */
    public void setScreenFormat(long screenFormat)
    {
        this.screenFormat = screenFormat;
    }

    private int entry(int i)
    {
        if (i < 0 || i >= entries.length)
            throw new RuntimeException("Invalid screen entry index: " + i);
        return entries[i] & 0xFFFF;
    }

    /**
     * Gets the NCGR tile index named by the entry at position <code>i</code>.
     * @param i an <code>int</code>
     * @return an <code>int</code>
     */
    public int getTileIndex(int i)
    {
        return entry(i) & TILE_INDEX_MASK;
    }

    /**
     * Sets the NCGR tile index named by the entry at position <code>i</code>.
     * @param i an <code>int</code>
     * @param tileIndex an <code>int</code>
     */
    public void setTileIndex(int i, int tileIndex)
    {
        entries[i] = (short) ((entry(i) & ~TILE_INDEX_MASK) | (tileIndex & TILE_INDEX_MASK));
    }

    /**
     * Gets the palette index (16-colour sub-palette) used by the entry at position <code>i</code>.
     * @param i an <code>int</code>
     * @return an <code>int</code>
     */
    public int getPaletteIndex(int i)
    {
        return (entry(i) >> PALETTE_SHIFT) & PALETTE_MASK;
    }

    /**
     * Sets the palette index (16-colour sub-palette) used by the entry at position <code>i</code>.
     * @param i an <code>int</code>
     * @param paletteIndex an <code>int</code>
     */
    public void setPaletteIndex(int i, int paletteIndex)
    {
        entries[i] = (short) ((entry(i) & ~(PALETTE_MASK << PALETTE_SHIFT)) | ((paletteIndex & PALETTE_MASK) << PALETTE_SHIFT));
    }

    /**
     * Gets whether the entry at position <code>i</code> is flipped horizontally.
     * @param i an <code>int</code>
     * @return a <code>boolean</code>
     */
    public boolean isHorizontalFlip(int i)
    {
        return (entry(i) & H_FLIP_BIT) != 0;
    }

    /**
     * Sets whether the entry at position <code>i</code> is flipped horizontally.
     * @param i an <code>int</code>
     * @param flip a <code>boolean</code>
     */
    public void setHorizontalFlip(int i, boolean flip)
    {
        entries[i] = (short) (flip ? (entry(i) | H_FLIP_BIT) : (entry(i) & ~H_FLIP_BIT));
    }

    /**
     * Gets whether the entry at position <code>i</code> is flipped vertically.
     * @param i an <code>int</code>
     * @return a <code>boolean</code>
     */
    public boolean isVerticalFlip(int i)
    {
        return (entry(i) & V_FLIP_BIT) != 0;
    }

    /**
     * Sets whether the entry at position <code>i</code> is flipped vertically.
     * @param i an <code>int</code>
     * @param flip a <code>boolean</code>
     */
    public void setVerticalFlip(int i, boolean flip)
    {
        entries[i] = (short) (flip ? (entry(i) | V_FLIP_BIT) : (entry(i) & ~V_FLIP_BIT));
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Screen screen = (Screen) o;
        return width == screen.width
                && height == screen.height
                && screenFormat == screen.screenFormat
                && Arrays.equals(entries, screen.entries);
    }

    @Override
    public int hashCode()
    {
        int result = Objects.hash(width, height, screenFormat);
        result = 31 * result + Arrays.hashCode(entries);
        return result;
    }

    @Override
    public String toString()
    {
        return String.format("Screen[%dx%d, %d entries]", width, height, entries.length);
    }
}
