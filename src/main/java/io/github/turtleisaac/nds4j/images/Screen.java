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

import java.awt.Color;
import java.awt.image.BufferedImage;
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

    /* BEGIN SECTION: rendering and write-back across the NCGR/NCLR layers */

    /**
     * Assembles this screen into a visible image, drawing each tile from the supplied graphics.
     * <p>
     * A screen only names tiles; the pixels come from a companion NCGR ({@link IndexedImage}) and the
     * colours from a companion NCLR ({@link Palette}). Each 8x8 entry is looked up in the NCGR by its
     * tile index, mirrored per its flip flags, and coloured through the entry's 16-colour sub-palette
     * (for 4bpp graphics) or the palette directly (for 8bpp). Colour index 0 is drawn opaque here; use
     * {@link #getTransparentImage(IndexedImage, Palette)} to treat it as transparent.
     *
     * @param ncgr the tile graphics this screen indexes into, loaded with the default 1-tile chunking
     * @param palette the colours to draw the tiles with
     * @return a <code>BufferedImage</code> the size of the screen
     */
    public BufferedImage getImage(IndexedImage ncgr, Palette palette)
    {
        return render(ncgr, palette, false);
    }

    /**
     * Same as {@link #getImage(IndexedImage, Palette)}, but colour index 0 of each tile is left
     * transparent, as the DS 2D engine treats it.
     * @param ncgr the tile graphics this screen indexes into
     * @param palette the colours to draw the tiles with
     * @return a <code>BufferedImage</code> the size of the screen, with an alpha channel
     */
    public BufferedImage getTransparentImage(IndexedImage ncgr, Palette palette)
    {
        return render(ncgr, palette, true);
    }

    /**
     * Renders a single 8x8 tile entry (transparent on colour index 0), as it appears on the screen.
     * @param ncgr the tile graphics this screen indexes into
     * @param palette the colours to draw the tile with
     * @param entryIndex the index of the entry, in row-major order
     * @return an 8x8 <code>BufferedImage</code> with an alpha channel
     */
    public BufferedImage getTileImage(IndexedImage ncgr, Palette palette, int entryIndex)
    {
        BufferedImage tile = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        drawEntry(tile, ncgr, palette, entryIndex, 0, 0, true);
        return tile;
    }

    private BufferedImage render(IndexedImage ncgr, Palette palette, boolean transparent)
    {
        int type = transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage output = new BufferedImage(width, height, type);

        int columns = width / 8;
        for (int i = 0; i < entries.length; i++)
        {
            int col = i % columns;
            int row = i / columns;
            drawEntry(output, ncgr, palette, i, col * 8, row * 8, transparent);
        }
        return output;
    }

    // Draws entry i into dest with its top-left corner at (destX, destY). Shared by the whole-screen and
    // single-tile renderers so the tile lookup, flip and sub-palette handling live in exactly one place.
    private void drawEntry(BufferedImage dest, IndexedImage ncgr, Palette palette, int i, int destX, int destY, boolean transparent)
    {
        int tilesPerRow = ncgr.getWidth() / 8;
        if (tilesPerRow == 0)
            throw new RuntimeException("The NCGR is narrower than one tile.");

        int tileIndex = getTileIndex(i);
        int paletteIndex = getPaletteIndex(i);
        boolean hFlip = isHorizontalFlip(i);
        boolean vFlip = isVerticalFlip(i);
        int tileCol = tileIndex % tilesPerRow;
        int tileRow = tileIndex / tilesPerRow;

        for (int y = 0; y < 8; y++)
        {
            for (int x = 0; x < 8; x++)
            {
                int srcX = tileCol * 8 + (hFlip ? 7 - x : x);
                int srcY = tileRow * 8 + (vFlip ? 7 - y : y);

                // A tile index that runs past the end of the NCGR leaves that cell blank rather than
                // throwing, so a screen paired with a smaller-than-expected tileset still renders.
                if (srcX >= ncgr.getWidth() || srcY >= ncgr.getHeight())
                    continue;

                int value = ncgr.getPixelValue(srcX, srcY);
                if (transparent && value == 0)
                    continue;

                int colorIndex = ncgr.getBitDepth() == 4 ? paletteIndex * 16 + value : value;
                Color color = colorIndex < palette.getNumColors() ? palette.getColor(colorIndex) : Color.BLACK;
                dest.setRGB(destX + x, destY + y, transparent ? (0xFF000000 | (color.getRGB() & 0xFFFFFF)) : color.getRGB());
            }
        }
    }

    /**
     * Writes a colour index into the NCGR tile that backs a given screen pixel, mirroring the entry's
     * flip flags so the edit lands on the correct source pixel. Because several entries can name the
     * same tile, editing one changes every entry that shares that tile — the NCGR is the single source
     * of pixels. Persist the change by saving the NCGR.
     *
     * @param ncgr the tile graphics this screen indexes into
     * @param screenX the x coordinate on the assembled screen, in pixels
     * @param screenY the y coordinate on the assembled screen, in pixels
     * @param value the colour index to write (0-15 for 4bpp, 0-255 for 8bpp; the raw tile value, not
     *              offset by the entry's sub-palette)
     */
    public void setSourcePixel(IndexedImage ncgr, int screenX, int screenY, int value)
    {
        if (screenX < 0 || screenX >= width || screenY < 0 || screenY >= height)
            throw new RuntimeException(String.format("Pixel (%d, %d) is outside this %dx%d screen.", screenX, screenY, width, height));

        int columns = width / 8;
        int i = (screenY / 8) * columns + (screenX / 8);
        int tilesPerRow = ncgr.getWidth() / 8;
        int tileIndex = getTileIndex(i);
        int tileCol = tileIndex % tilesPerRow;
        int tileRow = tileIndex / tilesPerRow;

        int inTileX = screenX % 8;
        int inTileY = screenY % 8;
        int srcX = tileCol * 8 + (isHorizontalFlip(i) ? 7 - inTileX : inTileX);
        int srcY = tileRow * 8 + (isVerticalFlip(i) ? 7 - inTileY : inTileY);
        ncgr.setPixelValue(srcX, srcY, value);
    }

    /* END SECTION: rendering and write-back */

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
