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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An object representation of an NSCR file (a tilemap "screen").
 * <p>
 * An NSCR ("RCSN") is a background layer: a grid of 8x8 tile cells laid out left-to-right,
 * top-to-bottom. Each cell is a 16-bit entry that names a tile in a companion {@link IndexedImage}
 * (NCGR), the palette to draw it with, and whether to flip it. The screen is meaningless without
 * that NCGR and an {@link Palette} (NCLR) to supply the colors.
 * <p>
 * Entries are exposed as a raw {@code short[]}; the bitfields of an individual entry can be read and
 * written with {@link #getTileIndex(int)}, {@link #getPaletteIndex(int)},
 * {@link #isHorizontalFlip(int)}, {@link #isVerticalFlip(int)} and their setters.
 */
public class Screen extends GenericNtrFile
{
    private int width;
    private int height;

    // The 32-bit word following the dimensions in the NRCS block. It selects the screen's color mode
    // and format (retail files use a handful of distinct values); nothing here needs to interpret it,
    // so it is preserved raw to keep an unedited screen byte-for-byte identical.
    private long screenFormat;

    // One 16-bit entry per 8x8 tile cell, in reading order (row-major). Bit layout:
    //   bits 0-9   tile index into the companion NCGR
    //   bit 10     horizontal flip
    //   bit 11     vertical flip
    //   bits 12-15 palette index (16-color sub-palette)
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
     * Creates a blank screen of the given pixel dimensions, with every entry cleared (tile 0, no flip,
     * sub-palette 0). Useful for authoring a screen from scratch — e.g. importing a background image via
     * {@link #applyImage} without an existing NSCR to start from.
     * @param width the screen width in pixels (a multiple of 8)
     * @param height the screen height in pixels (a multiple of 8)
     * @param screenFormat the raw 32-bit color-mode/format word to store (see {@link #getScreenFormat()})
     */
    public Screen(int width, int height, long screenFormat)
    {
        super("RCSN");
        if (width <= 0 || height <= 0)
            throw new RuntimeException("A screen's dimensions must be positive.");
        if (width % 8 != 0 || height % 8 != 0)
            throw new RuntimeException(String.format("A screen is a grid of 8x8 tiles, so %dx%d must be multiples of 8.", width, height));
        this.width = width;
        this.height = height;
        this.screenFormat = screenFormat;
        this.entries = new short[(width / 8) * (height / 8)];
        this.numBlocks = 1; // one NRCS block, so save() writes a valid single-section NTR header
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
     * Gets the raw 32-bit screen format/color-mode word.
     * @return a <code>long</code>
     */
    public long getScreenFormat()
    {
        return screenFormat;
    }

    /**
     * Sets the raw 32-bit screen format/color-mode word.
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
     * Gets the palette index (16-color sub-palette) used by the entry at position <code>i</code>.
     * @param i an <code>int</code>
     * @return an <code>int</code>
     */
    public int getPaletteIndex(int i)
    {
        return (entry(i) >> PALETTE_SHIFT) & PALETTE_MASK;
    }

    /**
     * Sets the palette index (16-color sub-palette) used by the entry at position <code>i</code>.
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
     * colors from a companion NCLR ({@link Palette}). Each 8x8 entry is looked up in the NCGR by its
     * tile index, mirrored per its flip flags, and colored through the entry's 16-color sub-palette
     * (for 4bpp graphics) or the palette directly (for 8bpp). Color index 0 is drawn opaque here; use
     * {@link #getTransparentImage(IndexedImage, Palette)} to treat it as transparent.
     *
     * @param ncgr the tile graphics this screen indexes into, loaded with the default 1-tile chunking
     * @param palette the colors to draw the tiles with
     * @return a <code>BufferedImage</code> the size of the screen
     */
    public BufferedImage getImage(IndexedImage ncgr, Palette palette)
    {
        return render(ncgr, palette, false);
    }

    /**
     * Same as {@link #getImage(IndexedImage, Palette)}, but color index 0 of each tile is left
     * transparent, as the DS 2D engine treats it.
     * @param ncgr the tile graphics this screen indexes into
     * @param palette the colors to draw the tiles with
     * @return a <code>BufferedImage</code> the size of the screen, with an alpha channel
     */
    public BufferedImage getTransparentImage(IndexedImage ncgr, Palette palette)
    {
        return render(ncgr, palette, true);
    }

    /**
     * Renders a single 8x8 tile entry (transparent on color index 0), as it appears on the screen.
     * @param ncgr the tile graphics this screen indexes into
     * @param palette the colors to draw the tile with
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
     * Writes a color index into the NCGR tile that backs a given screen pixel, mirroring the entry's
     * flip flags so the edit lands on the correct source pixel. Because several entries can name the
     * same tile, editing one changes every entry that shares that tile — the NCGR is the single source
     * of pixels. Persist the change by saving the NCGR.
     *
     * @param ncgr the tile graphics this screen indexes into
     * @param screenX the x coordinate on the assembled screen, in pixels
     * @param screenY the y coordinate on the assembled screen, in pixels
     * @param value the color index to write (0-15 for 4bpp, 0-255 for 8bpp; the raw tile value, not
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

    /**
     * The outputs of {@link #applyImage}: the rebuilt tileset and how the decomposition went.
     */
    public static final class ImportResult
    {
        /** The rebuilt NCGR tileset (unique tiles only). Save it alongside the mutated screen. */
        public final IndexedImage ncgr;
        /** The palette the tiles were indexed against: the input NCLR when matching, or the freshly-built
         *  one when {@link #applyImageRebuildingPalette} was used. Write it back to the NCLR after a rebuild. */
        public final Palette palette;
        /** How many distinct tiles the image reduced to (equals the NCGR's tile count). */
        public final int uniqueTiles;
        /** Pixels whose color wasn't an exact match in the chosen (sub-)palette (0 = perfect fit). */
        public final int unmatchedPixels;

        private ImportResult(IndexedImage ncgr, Palette palette, int uniqueTiles, int unmatchedPixels)
        {
            this.ncgr = ncgr;
            this.palette = palette;
            this.uniqueTiles = uniqueTiles;
            this.unmatchedPixels = unmatchedPixels;
        }
    }

    /**
     * Decomposes a full screen-sized image back into a tilemap + tileset — the inverse of
     * {@link #getImage(IndexedImage, Palette)}. This <b>mutates this screen</b> (its entries and
     * dimensions are rebuilt to describe {@code image}) and <b>returns a fresh {@link IndexedImage}</b>
     * holding the deduplicated tiles; the screen's {@code screenFormat} and the palette are preserved.
     * The classic "import a background PNG" write-back that a screen editor needs.
     * <p>
     * How it works: the image is cut into 8x8 cells (both dimensions must be multiples of 8). Each cell
     * is matched to the palette — for 4bpp graphics the 16-color sub-palette that fits the cell best is
     * chosen, for 8bpp the whole palette — and reduced to raw tile values (fully-transparent pixels map
     * to color index 0, as the DS treats it). Identical tiles are shared, and (when {@code dedupFlips})
     * a cell that is the horizontal/vertical mirror of an existing tile reuses it with the matching flip
     * flags, exactly as the DS 2D engine would. Colors are matched to the EXISTING palette (nearest
     * color when not exact); {@link ImportResult#unmatchedPixels} reports the fit, and the caller can
     * rebuild the NCLR separately if it's poor.
     *
     * @param image       the assembled background image to import (width and height multiples of 8)
     * @param templateNcgr an existing NCGR supplying the bit depth and the tileset's storage width
     *                     (tiles-per-row) for the rebuilt tileset
     * @param palette     the NCLR to match colors against (unchanged)
     * @param dedupFlips  whether to share tiles across horizontal/vertical mirrors (smaller tileset)
     * @return the rebuilt tileset and the decomposition stats ({@link ImportResult#palette} is {@code palette})
     */
    public ImportResult applyImage(BufferedImage image, IndexedImage templateNcgr, Palette palette, boolean dedupFlips)
    {
        return decompose(image, templateNcgr, palette, dedupFlips);
    }

    /**
     * Like {@link #applyImage}, but BUILDS a new palette from the image instead of matching an existing
     * one — the NSCR analog of {@code importPng}'s "rebuild palette" mode. For 4bpp it assigns each 8x8
     * cell to one of {@code numSubPalettes} 16-color sub-palettes (greedy: a cell joins a sub-palette it
     * fits within, else opens a new one, else the closest is quantised down) so tiles keep their
     * one-sub-palette-per-tile constraint; for 8bpp it median-cuts the whole image into a single palette.
     * The new palette is returned as {@link ImportResult#palette} — write it back to the NCLR.
     *
     * @param image          the assembled background image to import (dimensions multiples of 8)
     * @param templateNcgr   an existing NCGR supplying the bit depth and tileset storage width
     * @param numSubPalettes how many 16-color sub-palettes the new NCLR may use (4bpp; ignored for 8bpp)
     * @param dedupFlips     whether to share tiles across horizontal/vertical mirrors
     * @return the rebuilt tileset, the NEW palette, and the decomposition stats
     */
    public ImportResult applyImageRebuildingPalette(BufferedImage image, IndexedImage templateNcgr, int numSubPalettes, boolean dedupFlips)
    {
        Palette rebuilt = buildPalette(image, templateNcgr.getBitDepth(), Math.max(1, numSubPalettes));
        return decompose(image, templateNcgr, rebuilt, dedupFlips);
    }

    private ImportResult decompose(BufferedImage image, IndexedImage templateNcgr, Palette palette, boolean dedupFlips)
    {
        int bitDepth = templateNcgr.getBitDepth();
        if (bitDepth != 4 && bitDepth != 8)
            throw new RuntimeException("The template NCGR has no usable bit depth (" + bitDepth + ").");

        int w = image.getWidth();
        int h = image.getHeight();
        if (w % 8 != 0 || h % 8 != 0)
            throw new RuntimeException(String.format("The image is %dx%d; a screen needs both dimensions to be multiples of 8.", w, h));

        int columns = w / 8;
        int rows = h / 8;

        Color[] colors = palette.getColors();
        int subSize = bitDepth == 4 ? 16 : 256;
        int numSubPals = Math.max(1, colors.length / subSize);

        List<int[]> tiles = new ArrayList<>();          // each tile: int[64] raw values, row-major
        Map<String, Integer> tileLookup = new HashMap<>(); // identity serialisation -> tile ordinal
        short[] newEntries = new short[columns * rows];
        int unmatched = 0;

        int[] argb = new int[64];
        for (int cellRow = 0; cellRow < rows; cellRow++)
        {
            for (int cellCol = 0; cellCol < columns; cellCol++)
            {
                for (int y = 0; y < 8; y++)
                    for (int x = 0; x < 8; x++)
                        argb[y * 8 + x] = image.getRGB(cellCol * 8 + x, cellRow * 8 + y);

                // Pick the sub-palette that fits this cell best (least total nearest-color error).
                int bestSub = 0;
                int[] bestVals = null;
                long bestErr = Long.MAX_VALUE;
                int bestUnmatched = 0;
                for (int p = 0; p < numSubPals; p++)
                {
                    int base = p * subSize;
                    int[] vals = new int[64];
                    long err = 0;
                    int un = 0;
                    for (int k = 0; k < 64; k++)
                    {
                        if (((argb[k] >>> 24) & 0xFF) == 0) // fully transparent -> index 0
                        {
                            vals[k] = 0;
                            continue;
                        }
                        int r = (argb[k] >> 16) & 0xFF, g = (argb[k] >> 8) & 0xFF, b = argb[k] & 0xFF;
                        int bestIdx = 0;
                        long bestD = Long.MAX_VALUE;
                        for (int c = 0; c < subSize && base + c < colors.length; c++)
                        {
                            long d = colorDistanceSq(r, g, b, colors[base + c]);
                            if (d < bestD) { bestD = d; bestIdx = c; }
                        }
                        vals[k] = bestIdx;
                        err += bestD;
                        if (bestD != 0) un++;
                    }
                    if (err < bestErr) { bestErr = err; bestSub = p; bestVals = vals; bestUnmatched = un; }
                }
                unmatched += bestUnmatched;

                // Share this tile with an existing one, trying the four flip orientations.
                int ordinal = -1;
                boolean hFlip = false, vFlip = false;
                boolean[] options = dedupFlips ? new boolean[]{false, true} : new boolean[]{false};
                search:
                for (boolean tryV : options)
                    for (boolean tryH : options)
                    {
                        Integer ord = tileLookup.get(serializeTile(flipTile(bestVals, tryH, tryV)));
                        if (ord != null) { ordinal = ord; hFlip = tryH; vFlip = tryV; break search; }
                    }
                if (ordinal < 0)
                {
                    ordinal = tiles.size();
                    tiles.add(bestVals);
                    tileLookup.put(serializeTile(bestVals), ordinal);
                }
                if (ordinal > TILE_INDEX_MASK)
                    throw new RuntimeException("The image needs more than " + (TILE_INDEX_MASK + 1)
                            + " unique tiles, which a screen's 10-bit tile index can't address.");

                int entry = (ordinal & TILE_INDEX_MASK)
                        | (hFlip ? H_FLIP_BIT : 0)
                        | (vFlip ? V_FLIP_BIT : 0)
                        | ((bestSub & PALETTE_MASK) << PALETTE_SHIFT);
                newEntries[cellRow * columns + cellCol] = (short) entry;
            }
        }

        // Lay the unique tiles out at the template's storage width (tiles-per-row) into a fresh NCGR.
        int tilesPerRow = Math.max(1, templateNcgr.getWidth() / 8);
        int outTileRows = Math.max(1, (tiles.size() + tilesPerRow - 1) / tilesPerRow);
        IndexedImage ncgr = new IndexedImage(outTileRows * 8, tilesPerRow * 8, bitDepth, palette);
        for (int t = 0; t < tiles.size(); t++)
        {
            int tc = t % tilesPerRow, tr = t / tilesPerRow;
            int[] tile = tiles.get(t);
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 8; x++)
                    ncgr.setPixelValue(tc * 8 + x, tr * 8 + y, tile[y * 8 + x]);
        }
        ncgr.setNumTiles(tiles.size());   // write exactly the unique tiles (trailing grid cells are padding)
        ncgr.setMappingType(32);          // 32 = the header carries explicit tile dimensions

        // Commit the rebuilt tilemap onto this screen (screenFormat preserved).
        this.entries = newEntries;
        this.width = w;
        this.height = h;

        return new ImportResult(ncgr, palette, tiles.size(), unmatched);
    }

    // Builds a palette that fits the image, for applyImageRebuildingPalette. 8bpp: one median-cut palette
    // of up to 256 colors. 4bpp: greedily pack each cell's colors into up to numSubPalettes 16-color
    // sub-palettes (a cell must fit one sub-palette, mirroring the DS constraint), quantising a sub-palette
    // down with median-cut only if it overflows. The sub-palettes are concatenated into one Palette.
    private static Palette buildPalette(BufferedImage image, int bitDepth, int numSubPalettes)
    {
        if (bitDepth != 4 && bitDepth != 8)
            throw new RuntimeException("The template NCGR has no usable bit depth (" + bitDepth + ").");
        int w = image.getWidth(), h = image.getHeight();
        if (w % 8 != 0 || h % 8 != 0)
            throw new RuntimeException(String.format("The image is %dx%d; a screen needs both dimensions to be multiples of 8.", w, h));

        if (bitDepth == 8)
        {
            List<int[]> opaque = new ArrayList<>();
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                {
                    int argb = image.getRGB(x, y);
                    if (((argb >>> 24) & 0xFF) == 0) continue;
                    opaque.add(new int[]{(argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF});
                }
            List<Color> cut = IndexedImage.medianCut(opaque, 256);
            Color[] colors = new Color[256];
            for (int i = 0; i < 256; i++)
                colors[i] = i < cut.size() ? cut.get(i) : Color.BLACK;
            return new Palette(colors);
        }

        // 4bpp: one color set per sub-palette bucket (packed 0xRRGGBB, so equal colors collapse).
        int columns = w / 8, rows = h / 8;
        List<java.util.LinkedHashSet<Integer>> buckets = new ArrayList<>();
        for (int cellRow = 0; cellRow < rows; cellRow++)
        {
            for (int cellCol = 0; cellCol < columns; cellCol++)
            {
                java.util.LinkedHashSet<Integer> cell = new java.util.LinkedHashSet<>();
                for (int y = 0; y < 8; y++)
                    for (int x = 0; x < 8; x++)
                    {
                        int argb = image.getRGB(cellCol * 8 + x, cellRow * 8 + y);
                        if (((argb >>> 24) & 0xFF) == 0) continue; // transparent -> index 0, not a palette color
                        cell.add(argb & 0xFFFFFF);
                    }
                if (cell.isEmpty()) continue;

                // Join the first bucket this cell fits within 16 colors; else open a new bucket; else the
                // bucket whose union is smallest (it may overflow 16 and get quantised at the end).
                int fit = -1, best = -1, bestUnion = Integer.MAX_VALUE;
                for (int b = 0; b < buckets.size(); b++)
                {
                    java.util.LinkedHashSet<Integer> merged = new java.util.LinkedHashSet<>(buckets.get(b));
                    merged.addAll(cell);
                    if (merged.size() <= 16) { fit = b; break; }
                    if (merged.size() < bestUnion) { bestUnion = merged.size(); best = b; }
                }
                if (fit >= 0)
                    buckets.get(fit).addAll(cell);
                else if (buckets.size() < numSubPalettes)
                    buckets.add(new java.util.LinkedHashSet<>(cell));
                else
                    buckets.get(best).addAll(cell);
            }
        }

        Color[] colors = new Color[numSubPalettes * 16];
        Arrays.fill(colors, Color.BLACK);
        for (int p = 0; p < buckets.size() && p < numSubPalettes; p++)
        {
            List<Integer> packed = new ArrayList<>(buckets.get(p));
            List<Color> sub;
            if (packed.size() <= 16)
            {
                sub = new ArrayList<>();
                for (int rgb : packed)
                    sub.add(new Color(rgb));
            }
            else
            {
                List<int[]> rgbs = new ArrayList<>();
                for (int rgb : packed)
                    rgbs.add(new int[]{(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF});
                sub = IndexedImage.medianCut(rgbs, 16);
            }
            for (int i = 0; i < 16 && i < sub.size(); i++)
                colors[p * 16 + i] = sub.get(i);
        }
        return new Palette(colors);
    }

    private static long colorDistanceSq(int r, int g, int b, Color c)
    {
        long dr = r - c.getRed(), dg = g - c.getGreen(), db = b - c.getBlue();
        return dr * dr + dg * dg + db * db;
    }

    // Serialise a 64-value tile to a hashable/equatable key (each value 0-255 fits one char).
    private static String serializeTile(int[] tile)
    {
        char[] cs = new char[64];
        for (int i = 0; i < 64; i++)
            cs[i] = (char) (tile[i] & 0xFF);
        return new String(cs);
    }

    // Mirror a tile horizontally and/or vertically. flipTile(D,h,v)[a][b] = D[v?7-a:a][h?7-b:b] — the
    // stored tile T for which drawing D at flags (h,v) reproduces D (the flip flags are self-inverse).
    private static int[] flipTile(int[] tile, boolean hFlip, boolean vFlip)
    {
        int[] out = new int[64];
        for (int a = 0; a < 8; a++)
            for (int b = 0; b < 8; b++)
                out[a * 8 + b] = tile[(vFlip ? 7 - a : a) * 8 + (hFlip ? 7 - b : b)];
        return out;
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
