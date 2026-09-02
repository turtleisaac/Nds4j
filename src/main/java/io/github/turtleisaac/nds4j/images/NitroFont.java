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

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * An object representation of an NFTR file (a Nitro FonT Resource, magic {@code "RTFN"} on disk).
 * <p>
 * An NFTR is a bitmap font: a bank of fixed-cell glyph tiles plus the tables that turn a Unicode (or
 * Shift-JIS / CP1252) code point into a glyph and give each glyph its proportional advance. The file is
 * a chain of NTR blocks after the standard 16-byte header, each block a four-character tag (stored
 * byte-reversed on disk, so {@code FINF} reads {@code "FNIF"}, {@code CGLP}&rarr;{@code "PLGC"},
 * {@code CWDH}&rarr;{@code "HDWC"}, {@code CMAP}&rarr;{@code "PAMC"}) followed by a {@code u32} size:
 * <ul>
 *   <li><b>FINF</b> (font info) &mdash; line feed, default glyph metrics, encoding, and the offsets of the
 *       other three block kinds. Fully decoded.</li>
 *   <li><b>CGLP</b> (glyphs) &mdash; the cell dimensions, bit depth, and the raw MSB-first glyph bitmaps.
 *       Decoded to {@link BufferedImage}s via {@link #getGlyphImage(int)}.</li>
 *   <li><b>CWDH</b> (widths) &mdash; per-glyph {left, glyph-width, advance}, as a linked chain of index
 *       ranges. Decoded where the range is well-formed; always preserved verbatim.</li>
 *   <li><b>CMAP</b> (maps) &mdash; a linked chain of code-point&rarr;glyph-index ranges in three encodings
 *       (direct / table / sparse-pairs). Decoded into a lookup via {@link #getGlyphIndex(int)}.</li>
 * </ul>
 * Because a font's blocks reference one another by absolute file offset (and some retail headers even
 * carry a {@code fileSize} field that disagrees with the real length), the safe correctness bar is a
 * <b>byte-for-byte</b> round trip: every block is kept verbatim and re-emitted in place, and the original
 * header is reproduced exactly. The decode above is a read-only view laid on top of those preserved bytes.
 */
public class NitroFont extends GenericNtrFile
{
    // Everything after the 16-byte header, verbatim: the whole block chain plus any inter-block or
    // trailing padding. save() re-emits this unchanged, so an unedited font round-trips exactly
    // regardless of any field we do or don't decode. (Block sizes are content-exact and can be unaligned;
    // successive blocks start 4-byte-aligned, leaving gap bytes that belong to neither block's size.)
    private byte[] blockRegion;
    // Decoded block start offsets within blockRegion (parsed with 4-byte alignment), one per block.
    private final List<Integer> blockStarts = new ArrayList<>();

    // Decoded views (read-only) built from the preserved block bytes.
    private FontInfo fontInfo;
    private GlyphData glyphData;
    private final List<WidthGroup> widthGroups = new ArrayList<>();
    private final List<CharMap> charMaps = new ArrayList<>();

    /**
     * Generates an object representation of an NFTR file.
     * @param data a <code>byte[]</code> representation of an NFTR file
     */
    public NitroFont(byte[] data)
    {
        super("RTFN", "NFTR");
        MemBuf dataBuf = MemBuf.create(data);
        MemBuf.MemBufReader reader = dataBuf.reader();

        readGenericNtrHeader(reader);

        int headerBytes = headerSize != 0 ? headerSize : NTR_HEADER_SIZE;
        blockRegion = new byte[data.length - headerBytes];
        System.arraycopy(data, headerBytes, blockRegion, 0, blockRegion.length);

        // Walk the block chain within the preserved region. Each block is a tag + u32 size; the size is
        // content-exact and may be unaligned, so the next block starts at the 4-byte-aligned offset.
        int pos = 0;
        for (int i = 0; i < numBlocks; i++)
        {
            if (pos + 8 > blockRegion.length)
                throw new RuntimeException("Malformed NFTR: block " + i + " runs past end of file");
            long blockSize = u32(blockRegion, pos + 4);
            if (blockSize < 8 || pos + blockSize > blockRegion.length)
                throw new RuntimeException("Malformed NFTR: block " + i + " has an out-of-range size (" + blockSize + ")");
            blockStarts.add(pos);
            pos += (int) blockSize;
            pos = (pos + 3) & ~3; // successive blocks are 4-byte aligned
        }

        decode();
    }

    /* BEGIN SECTION: decode (a read-only view over the preserved block region) */

    private void decode()
    {
        // Decode by iterating the block list in file order, keyed on each block's tag. This is
        // deliberately independent of the FINF pointer offsets and the CWDH/CMAP "next" chain fields:
        // some retail (Game Freak) fonts store bogus chain pointers (e.g. a CWDH "next" of 0x10) that
        // would walk off into garbage if followed. Block order is authoritative and equivalent.
        for (int start : blockStarts)
        {
            String tag = new String(blockRegion, start, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            switch (tag)
            {
                case "FNIF":
                case "FINF":
                    fontInfo = new FontInfo(blockRegion, start);
                    break;
                case "PLGC":
                case "CGLP":
                    glyphData = new GlyphData(blockRegion, start);
                    break;
                case "HDWC":
                case "CWDH":
                    widthGroups.add(new WidthGroup(blockRegion, start));
                    break;
                case "PAMC":
                case "CMAP":
                    charMaps.add(new CharMap(blockRegion, start));
                    break;
                default:
                    break;
            }
        }
        if (fontInfo == null)
            throw new RuntimeException("NFTR is missing its FINF (font info) block");
    }

    private static int u8(byte[] d, int o) { return d[o] & 0xFF; }
    private static int s8(byte[] d, int o) { return d[o]; }
    private static int u16(byte[] d, int o) { return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8); }
    private static long u32(byte[] d, int o)
    {
        return (d[o] & 0xFFL) | ((d[o + 1] & 0xFFL) << 8) | ((d[o + 2] & 0xFFL) << 16) | ((d[o + 3] & 0xFFL) << 24);
    }

    /* END SECTION: decode */

    /**
     * Generate a <code>byte[]</code> representation of this <code>NitroFont</code> as an NFTR.
     * <p>
     * Every block is written back verbatim in its original order, and the header is reproduced exactly
     * (including the retail {@code fileSize} field, which need not equal the real byte length), so an
     * unedited font round-trips byte-for-byte.
     * @return a <code>byte[]</code>
     */
    public byte[] save()
    {
        MemBuf dataBuf = MemBuf.create();
        MemBuf.MemBufWriter writer = dataBuf.writer();

        int headerBytes = headerSize != 0 ? headerSize : NTR_HEADER_SIZE;
        writer.skip(headerBytes);
        writer.write(blockRegion);
        int end = writer.getPosition();

        writer.setPosition(0);
        // Preserve the original fileSize field verbatim: some retail NFTR headers store a value that
        // disagrees with the true length, and recomputing it would break the byte-exact round trip.
        writeGenericNtrHeader(writer, fileSize, numBlocks);
        writer.setPosition(end); // so getBuffer() returns the whole file, not just the header

        return dataBuf.reader().getBuffer();
    }

    /* BEGIN SECTION: font-level accessors */

    /**
     * Gets the decoded font-info (FINF) block.
     * @return a {@link FontInfo}
     */
    public FontInfo getFontInfo()
    {
        return fontInfo;
    }

    /**
     * Gets the decoded glyph (CGLP) block, or null if the font has none.
     * @return a {@link GlyphData}
     */
    public GlyphData getGlyphData()
    {
        return glyphData;
    }

    /**
     * Gets the width (CWDH) groups, in chain order.
     * @return a <code>List</code> of {@link WidthGroup}
     */
    public List<WidthGroup> getWidthGroups()
    {
        return widthGroups;
    }

    /**
     * Gets the character-map (CMAP) blocks, in chain order.
     * @return a <code>List</code> of {@link CharMap}
     */
    public List<CharMap> getCharMaps()
    {
        return charMaps;
    }

    /**
     * Gets the number of decoded glyph tiles in this font.
     * @return an <code>int</code>
     */
    public int getNumGlyphs()
    {
        return glyphData == null ? 0 : glyphData.numGlyphs;
    }

    /**
     * Resolves a code point to a glyph index by walking the CMAP chain.
     * @param codePoint the code point (Unicode, or encoding-specific per {@link FontInfo#getEncoding()})
     * @return the glyph index, or {@code -1} if the font maps no glyph to this code point
     */
    public int getGlyphIndex(int codePoint)
    {
        for (CharMap map : charMaps)
        {
            int idx = map.glyphFor(codePoint);
            if (idx >= 0)
                return idx;
        }
        return -1;
    }

    /**
     * Renders one glyph tile to a black-on-transparent image, at 1&times; scale. Pixel intensity comes
     * from the glyph's bit depth (a 2bpp glyph has four alpha levels).
     * @param glyphIndex the glyph index (0 &le; index &lt; {@link #getNumGlyphs()})
     * @return a <code>BufferedImage</code> with an alpha channel
     */
    public BufferedImage getGlyphImage(int glyphIndex)
    {
        if (glyphData == null)
            throw new IllegalStateException("font has no CGLP glyph block");
        return glyphData.render(glyphIndex);
    }

    /* END SECTION: font-level accessors */

    /* BEGIN SECTION: rendering helpers */

    /**
     * Renders every glyph tile into a single contact sheet, laid out left-to-right, top-to-bottom, on a
     * transparent canvas &mdash; a quick way to eyeball a whole font.
     * @param columns the number of glyphs per row
     * @param scale an integer magnification (1 = native size)
     * @return a <code>BufferedImage</code> with an alpha channel
     */
    public BufferedImage renderGlyphSheet(int columns, int scale)
    {
        if (glyphData == null)
            throw new IllegalStateException("font has no CGLP glyph block");
        int cw = glyphData.cellWidth, ch = glyphData.cellHeight;
        int count = glyphData.numGlyphs;
        int rows = (count + columns - 1) / columns;
        int cellW = (cw + 1) * scale, cellH = (ch + 1) * scale;
        BufferedImage sheet = new BufferedImage(Math.max(1, columns * cellW), Math.max(1, rows * cellH),
                BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < count; i++)
        {
            int gx = (i % columns) * cellW;
            int gy = (i / columns) * cellH;
            BufferedImage glyph = glyphData.render(i);
            for (int y = 0; y < ch; y++)
                for (int x = 0; x < cw; x++)
                {
                    int argb = glyph.getRGB(x, y);
                    for (int sy = 0; sy < scale; sy++)
                        for (int sx = 0; sx < scale; sx++)
                            sheet.setRGB(gx + x * scale + sx, gy + y * scale + sy, argb);
                }
        }
        return sheet;
    }

    /**
     * Renders a run of text using this font's glyph tiles, code-point mapping, and per-glyph advances.
     * Unmapped code points fall back to the font's default glyph. Ink is black on a transparent canvas.
     * @param text the string to render
     * @param scale an integer magnification (1 = native size)
     * @return a <code>BufferedImage</code> with an alpha channel
     */
    public BufferedImage renderString(String text, int scale)
    {
        if (glyphData == null)
            throw new IllegalStateException("font has no CGLP glyph block");
        // First pass: total advance.
        int totalW = 0;
        int ch = glyphData.cellHeight;
        for (int i = 0; i < text.length(); i++)
            totalW += advanceOf(text.charAt(i));

        BufferedImage out = new BufferedImage(Math.max(1, totalW * scale), Math.max(1, ch * scale),
                BufferedImage.TYPE_INT_ARGB);
        int penX = 0;
        for (int i = 0; i < text.length(); i++)
        {
            int cp = text.charAt(i);
            int glyphIndex = getGlyphIndex(cp);
            if (glyphIndex < 0)
                glyphIndex = fontInfo.defaultCharIndex;
            int[] w = widthsFor(glyphIndex);
            int left = w != null ? w[0] : 0;
            if (glyphIndex >= 0 && glyphIndex < glyphData.numGlyphs)
            {
                BufferedImage glyph = glyphData.render(glyphIndex);
                for (int y = 0; y < ch; y++)
                    for (int x = 0; x < glyphData.cellWidth; x++)
                    {
                        int argb = glyph.getRGB(x, y);
                        if ((argb >>> 24) == 0)
                            continue;
                        int dx = (penX + left + x) * scale;
                        int dy = y * scale;
                        for (int sy = 0; sy < scale; sy++)
                            for (int sx = 0; sx < scale; sx++)
                            {
                                int px = dx + sx, py = dy + sy;
                                if (px >= 0 && px < out.getWidth() && py >= 0 && py < out.getHeight())
                                    out.setRGB(px, py, argb);
                            }
                    }
            }
            penX += advanceOf(cp);
        }
        return out;
    }

    private int advanceOf(int codePoint)
    {
        int glyphIndex = getGlyphIndex(codePoint);
        if (glyphIndex < 0)
            glyphIndex = fontInfo.defaultCharIndex;
        int[] w = widthsFor(glyphIndex);
        if (w != null)
            return w[2];
        return fontInfo.defaultCharWidth != 0 ? fontInfo.defaultCharWidth : glyphData.cellWidth;
    }

    /**
     * Gets the {left, glyph-width, advance} width triple for a glyph, from the CWDH chain, or null if no
     * width group covers it.
     * @param glyphIndex the glyph index
     * @return a length-3 <code>int[]</code> {left, glyphWidth, advance}, or null
     */
    public int[] widthsFor(int glyphIndex)
    {
        for (WidthGroup group : widthGroups)
        {
            int[] w = group.widthsFor(glyphIndex);
            if (w != null)
                return w;
        }
        return null;
    }

    /* END SECTION: rendering helpers */

    @Override
    public String toString()
    {
        return String.format("NitroFont[%d glyphs, %dx%d, %dbpp, %d blocks]",
                getNumGlyphs(),
                glyphData == null ? 0 : glyphData.cellWidth,
                glyphData == null ? 0 : glyphData.cellHeight,
                glyphData == null ? 0 : glyphData.bpp,
                blockStarts.size());
    }

    /**
     * The decoded FINF (font info) block: line feed, default glyph metrics, text encoding, and the file
     * offsets of the glyph / width / map blocks.
     */
    public static class FontInfo
    {
        private final int fontType;
        private final int lineFeed;
        private final int defaultCharIndex;
        private final int defaultLeft;
        private final int defaultGlyphWidth;
        private final int defaultCharWidth;
        private final int encoding;
        private final long glyphOffset;
        private final long widthOffset;
        private final long mapOffset;
        // Present only in version 0x0102 headers (chunk length 0x20); otherwise unset (-1).
        private final int height;
        private final int width;
        private final int ascent;

        FontInfo(byte[] d, int blockStart)
        {
            int b = blockStart + 8; // body after tag + size
            fontType = u8(d, b);
            lineFeed = u8(d, b + 1);
            defaultCharIndex = u16(d, b + 2);
            defaultLeft = s8(d, b + 4);
            defaultGlyphWidth = u8(d, b + 5);
            defaultCharWidth = u8(d, b + 6);
            encoding = u8(d, b + 7);
            glyphOffset = u32(d, b + 8);
            widthOffset = u32(d, b + 12);
            mapOffset = u32(d, b + 16);
            long blockLen = u32(d, blockStart + 4);
            if (blockLen >= 0x20)
            {
                height = u8(d, b + 20);
                width = u8(d, b + 21);
                ascent = u8(d, b + 22);
            }
            else
            {
                height = width = ascent = -1;
            }
        }

        /** @return the font type (0 = bitmap glyph font, the only kind decoded here) */
        public int getFontType() { return fontType; }
        /** @return the line feed (vertical advance between lines), in pixels */
        public int getLineFeed() { return lineFeed; }
        /** @return the glyph index used for code points the font does not map */
        public int getDefaultCharIndex() { return defaultCharIndex; }
        /** @return the default left bearing applied when a glyph has no CWDH entry */
        public int getDefaultLeft() { return defaultLeft; }
        /** @return the default glyph (ink) width */
        public int getDefaultGlyphWidth() { return defaultGlyphWidth; }
        /** @return the default character advance width */
        public int getDefaultCharWidth() { return defaultCharWidth; }
        /** @return the text encoding: 0=UTF-8, 1=Unicode (UTF-16), 2=Shift-JIS, 3=CP1252 */
        public int getEncoding() { return encoding; }
        /** @return the file offset (to block body) of the CGLP glyph block */
        public long getGlyphOffset() { return glyphOffset; }
        /** @return the file offset (to block body) of the first CWDH width block */
        public long getWidthOffset() { return widthOffset; }
        /** @return the file offset (to block body) of the first CMAP map block */
        public long getMapOffset() { return mapOffset; }
        /** @return the font height (version 0x0102 only), or -1 */
        public int getHeight() { return height; }
        /** @return the font width (version 0x0102 only), or -1 */
        public int getWidth() { return width; }
        /** @return the underline/ascent position (version 0x0102 only), or -1 */
        public int getAscent() { return ascent; }
    }

    /**
     * The decoded CGLP glyph block: fixed-cell tile metrics and the raw MSB-first glyph bitmaps.
     */
    public static class GlyphData
    {
        private final byte[] file;
        private final int cellWidth;
        private final int cellHeight;
        private final int cellSize;   // bytes per glyph tile (the stride; may exceed the packed pixel bytes)
        private final int baselinePos;
        private final int maxCharWidth;
        private final int bpp;        // bits per pixel (1, 2, or 3)
        private final int flags;
        private final int bitmapStart;
        private final int numGlyphs;

        GlyphData(byte[] d, int blockStart)
        {
            file = d;
            int b = blockStart + 8;
            cellWidth = u8(d, b);
            cellHeight = u8(d, b + 1);
            cellSize = u16(d, b + 2);
            baselinePos = s8(d, b + 4);
            maxCharWidth = u8(d, b + 5);
            bpp = u8(d, b + 6);
            flags = u8(d, b + 7);
            bitmapStart = b + 8;
            long blockLen = u32(d, blockStart + 4);
            int dataBytes = (int) blockLen - 0x10;
            numGlyphs = cellSize > 0 ? dataBytes / cellSize : 0;
        }

        /** @return the glyph cell width, in pixels */
        public int getCellWidth() { return cellWidth; }
        /** @return the glyph cell height, in pixels */
        public int getCellHeight() { return cellHeight; }
        /** @return the number of bytes stored per glyph tile */
        public int getCellSize() { return cellSize; }
        /** @return the baseline position within the cell */
        public int getBaselinePos() { return baselinePos; }
        /** @return the maximum proportional character width */
        public int getMaxCharWidth() { return maxCharWidth; }
        /** @return the bit depth (bits per pixel): 1, 2, or 3 */
        public int getBpp() { return bpp; }
        /** @return the glyph flags/rotation byte */
        public int getFlags() { return flags; }
        /** @return the number of glyph tiles */
        public int getNumGlyphs() { return numGlyphs; }

        /**
         * Reads a glyph's raw pixel values (0..2^bpp-1), row-major, {@code cellWidth*cellHeight} entries.
         * @param glyphIndex the glyph index
         * @return an <code>int[]</code> of pixel intensities
         */
        public int[] getGlyphPixels(int glyphIndex)
        {
            if (glyphIndex < 0 || glyphIndex >= numGlyphs)
                throw new IndexOutOfBoundsException("glyph " + glyphIndex + " of " + numGlyphs);
            int[] px = new int[cellWidth * cellHeight];
            int base = bitmapStart + glyphIndex * cellSize;
            // Continuous MSB-first bitstream: bpp bits per pixel, no row padding.
            int bitPos = 0;
            int max = (1 << bpp) - 1;
            for (int i = 0; i < px.length; i++)
            {
                int value = 0;
                for (int bit = 0; bit < bpp; bit++)
                {
                    int byteIdx = base + (bitPos >> 3);
                    int bitInByte = 7 - (bitPos & 7);
                    int b = (u8(file, byteIdx) >> bitInByte) & 1;
                    value = (value << 1) | b;
                    bitPos++;
                }
                px[i] = value * 255 / max;
            }
            return px;
        }

        BufferedImage render(int glyphIndex)
        {
            int[] px = getGlyphPixels(glyphIndex);
            BufferedImage img = new BufferedImage(cellWidth, cellHeight, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < cellHeight; y++)
                for (int x = 0; x < cellWidth; x++)
                {
                    int a = px[y * cellWidth + x];
                    img.setRGB(x, y, (a << 24)); // black ink, intensity = alpha
                }
            return img;
        }
    }

    /**
     * A single CWDH width group: a range of glyph indices [{@code indexBegin}, {@code indexEnd}] and the
     * {left, glyph-width, advance} triple for each. Groups form a linked chain; a lookup falls through to
     * the next group when the requested index is outside this one's range.
     */
    public static class WidthGroup
    {
        private final int indexBegin;
        private final int indexEnd;
        private final long nextOffset;
        private final byte[][] entries; // each = {left, glyphWidth, charWidth}, or empty if the range is ill-formed

        WidthGroup(byte[] d, int blockStart)
        {
            int b = blockStart + 8;
            indexBegin = u16(d, b);
            indexEnd = u16(d, b + 2);
            nextOffset = u32(d, b + 4);
            int entryBase = b + 8;
            // Some retail (Game Freak) fonts store an ill-formed range (indexEnd < indexBegin); treat that
            // as "no decodable entries" rather than reading garbage. The block is still preserved verbatim.
            int count = indexEnd >= indexBegin ? (indexEnd - indexBegin + 1) : 0;
            long blockLen = u32(d, blockStart + 4);
            int available = ((int) blockLen - 0x10) / 3;
            if (count > available)
                count = Math.max(available, 0);
            entries = new byte[count][];
            for (int i = 0; i < count; i++)
                entries[i] = new byte[]{d[entryBase + i * 3], d[entryBase + i * 3 + 1], d[entryBase + i * 3 + 2]};
        }

        /** @return the first glyph index this group covers */
        public int getIndexBegin() { return indexBegin; }
        /** @return the last glyph index this group covers */
        public int getIndexEnd() { return indexEnd; }
        /** @return the number of decoded width entries */
        public int getNumEntries() { return entries.length; }

        /**
         * @param glyphIndex the glyph index
         * @return {left, glyph-width, advance} for the glyph, or null if this group does not cover it
         */
        public int[] widthsFor(int glyphIndex)
        {
            int local = glyphIndex - indexBegin;
            if (local < 0 || local >= entries.length)
                return null;
            byte[] e = entries[local];
            return new int[]{e[0], e[1] & 0xFF, e[2] & 0xFF};
        }
    }

    /**
     * A single CMAP block: a code-point range and its mapping to glyph indices, in one of three encodings
     * &mdash; direct (a base index incremented across the range), table (a per-code-point index array), or
     * sparse pairs (explicit code&rarr;glyph pairs). Blocks form a linked chain.
     */
    public static class CharMap
    {
        private final int codeBegin;
        private final int codeEnd;
        private final int mapType;
        private final long nextOffset;
        // For direct: entries[0] = base index. For table: one entry per code point. For pairs: 2 per pair
        // (code, glyph). Kept flat and interpreted by mapType.
        private final int directBase;
        private final int[] tableIndices;
        private final int[] pairCodes;
        private final int[] pairGlyphs;

        CharMap(byte[] d, int blockStart)
        {
            int b = blockStart + 8;
            codeBegin = u16(d, b);
            codeEnd = u16(d, b + 2);
            mapType = u16(d, b + 4); // low half of the u32 map-type field
            nextOffset = u32(d, b + 8);
            int mapBase = b + 12;
            switch (mapType)
            {
                case 0: // direct: a single base index, incremented per code point
                    directBase = u16(d, mapBase);
                    tableIndices = null; pairCodes = null; pairGlyphs = null;
                    break;
                case 1: // table: one u16 glyph index per code point in [begin, end]
                {
                    int n = codeEnd - codeBegin + 1;
                    tableIndices = new int[Math.max(0, n)];
                    for (int i = 0; i < tableIndices.length; i++)
                        tableIndices[i] = u16(d, mapBase + i * 2);
                    directBase = -1; pairCodes = null; pairGlyphs = null;
                    break;
                }
                case 2: // sparse pairs: a count, then (code, glyph) u16 pairs
                {
                    int n = u16(d, mapBase);
                    pairCodes = new int[n];
                    pairGlyphs = new int[n];
                    for (int i = 0; i < n; i++)
                    {
                        pairCodes[i] = u16(d, mapBase + 2 + i * 4);
                        pairGlyphs[i] = u16(d, mapBase + 2 + i * 4 + 2);
                    }
                    directBase = -1; tableIndices = null;
                    break;
                }
                default:
                    directBase = -1; tableIndices = null; pairCodes = null; pairGlyphs = null;
            }
        }

        /** @return the first code point this block covers */
        public int getCodeBegin() { return codeBegin; }
        /** @return the last code point this block covers */
        public int getCodeEnd() { return codeEnd; }
        /** @return the mapping method: 0 = direct, 1 = table, 2 = sparse pairs */
        public int getMapType() { return mapType; }

        /**
         * @param codePoint the code point
         * @return the glyph index this block maps the code point to, or {@code -1} if unmapped here
         */
        public int glyphFor(int codePoint)
        {
            switch (mapType)
            {
                case 0:
                    if (codePoint < codeBegin || codePoint > codeEnd)
                        return -1;
                    return directBase + (codePoint - codeBegin);
                case 1:
                {
                    if (codePoint < codeBegin || codePoint > codeEnd)
                        return -1;
                    int idx = tableIndices[codePoint - codeBegin];
                    return idx == 0xFFFF ? -1 : idx;
                }
                case 2:
                    for (int i = 0; i < pairCodes.length; i++)
                        if (pairCodes[i] == codePoint)
                            return pairGlyphs[i];
                    return -1;
                default:
                    return -1;
            }
        }
    }
}
