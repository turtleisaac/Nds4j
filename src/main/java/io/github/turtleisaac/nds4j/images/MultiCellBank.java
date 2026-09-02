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

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Objects;

/**
 * An object representation of an NMCR file.
 * <p>
 * An NMCR ("RCMN") is a <em>multi-cell</em> bank: each {@link MultiCell} composes several cells of a
 * companion {@link CellBank} (NCER) at fixed pixel offsets, so a larger object (a whole Pok&eacute;mon
 * overworld sprite, a menu graphic) can be assembled from smaller OAM cells. Like an NANR, an NMCR is
 * meaningless on its own; it references the cells of a {@link CellBank}, which in turn draws its pixels
 * from an NCGR/NCLR. An NMCR is itself the thing an NMAR ({@link MultiCellAnimation}) animates.
 * <p>
 * The file is a single {@code KBCM} ("MCBK") block: a header, an array of multi-cell descriptors, and a
 * flat array of cell-info entries the descriptors slice into. Every structural field is understood and
 * recomputed on write (the cell-info offsets are the running cumulative count, the two section offsets
 * and the {@code 0xBEEF} marker are fixed), so a loaded NMCR round-trips <b>byte-for-byte</b> while its
 * meaningful fields (each multi-cell's cells and their positions) stay fully editable.
 */
public class MultiCellBank extends GenericNtrFile
{
    // The 0xBEEF marker word that follows the multi-cell count, and the two reserved header words that
    // follow the section-offset table. All three are constant across the retail corpus (0xBEEF, 0, 0);
    // kept as fields and re-emitted so the file reproduces exactly regardless.
    private int beef;
    private long reserved0;
    private long reserved1;

    private MultiCell[] multiCells;

    // The companion cell bank (NCER) whose cells this file composes. Not part of the NMCR itself; set by
    // the consumer with setCellBank so a multi-cell can be rendered. Never serialised.
    private CellBank cellBank;

    // On-disk sizes of the two record types.
    private static final int MULTICELL_SIZE = 8;
    private static final int CELLINFO_SIZE = 8;

    /**
     * Generates an object representation of an NMCR file
     * @param data a <code>byte[]</code> representation of an NMCR file
     */
    public MultiCellBank(byte[] data)
    {
        super("RCMN");
        MemBuf dataBuf = MemBuf.create(data);
        MemBuf.MemBufReader reader = dataBuf.reader();

        readGenericNtrHeader(reader);

        // reader position is now 0x10
        String bankMagic = reader.readString(4); // 0x10
        if (!bankMagic.equals("KBCM"))
            throw new RuntimeException("Not a valid RCMN file.");

        long bankSectionSize = reader.readUInt32(); // 0x14
        int multiCellCount = reader.readUInt16(); // 0x18
        beef = reader.readUInt16(); // 0x1A (0xBEEF marker)

        // Both offsets are relative to 0x18 (the start of the count/offset table). Their values are
        // fully determined by the count, but they are read and used rather than assumed so a malformed
        // file is rejected instead of silently misparsed.
        long multiCellArrayOffset = reader.readUInt32(); // 0x1C
        long cellInfoArrayOffset = reader.readUInt32(); // 0x20
        reserved0 = reader.readUInt32(); // 0x24
        reserved1 = reader.readUInt32(); // 0x28

        int multiCellArrayBase = NTR_HEADER_SIZE + 8 + (int) multiCellArrayOffset;
        int cellInfoArrayBase = NTR_HEADER_SIZE + 8 + (int) cellInfoArrayOffset;

        multiCells = new MultiCell[multiCellCount];
        for (int i = 0; i < multiCellCount; i++)
        {
            reader.setPosition(multiCellArrayBase + i * MULTICELL_SIZE);
            int numCells = reader.readUInt16();
            int attribute = reader.readUInt16();
            long cellInfoOffset = reader.readUInt32(); // relative to the cell-info array base

            MultiCell multiCell = new MultiCell();
            multiCell.attribute = attribute;
            multiCell.cellInfos = new CellInfo[numCells];
            multiCells[i] = multiCell;

            int firstCellInfo = (int) (cellInfoOffset / CELLINFO_SIZE);
            for (int c = 0; c < numCells; c++)
            {
                reader.setPosition(cellInfoArrayBase + (firstCellInfo + c) * CELLINFO_SIZE);
                CellInfo info = new CellInfo();
                info.cellIndex = reader.readUInt16();
                info.x = reader.readShort();
                info.y = reader.readShort();
                info.attr = reader.readUInt16();
                multiCell.cellInfos[c] = info;
            }
        }
    }

    /**
     * Generate a <code>byte[]</code> representation of this <code>MultiCellBank</code> as an NMCR
     * @return a <code>byte[]</code>
     */
    public byte[] save()
    {
        MemBuf dataBuf = MemBuf.create();
        MemBuf.MemBufWriter writer = dataBuf.writer();

        writer.skip(NTR_HEADER_SIZE);

        // KBCM header
        writer.writeString("KBCM");
        int bankSizePos = writer.getPosition();
        writer.skip(4); // bank section size, filled in at the end
        writer.writeShort((short) multiCells.length);
        writer.writeShort((short) beef);

        // Both offsets are relative to 0x18. The multi-cell array follows the 0x14-byte header body
        // (count + marker + four words), and the cell-info array follows the multi-cell array.
        long multiCellArrayOffset = 0x14;
        long cellInfoArrayOffset = multiCellArrayOffset + (long) multiCells.length * MULTICELL_SIZE;
        writer.writeUInt32(multiCellArrayOffset);
        writer.writeUInt32(cellInfoArrayOffset);
        writer.writeUInt32(reserved0);
        writer.writeUInt32(reserved1);

        // multi-cell descriptors: the cell-info offset is the running cumulative count (no sharing)
        int cumulativeCells = 0;
        for (MultiCell multiCell : multiCells)
        {
            writer.writeShort((short) multiCell.cellInfos.length);
            writer.writeShort((short) multiCell.attribute);
            writer.writeUInt32((long) cumulativeCells * CELLINFO_SIZE);
            cumulativeCells += multiCell.cellInfos.length;
        }

        // flat cell-info array, in multi-cell order (matching how they were read)
        for (MultiCell multiCell : multiCells)
        {
            for (CellInfo info : multiCell.cellInfos)
            {
                writer.writeShort((short) info.cellIndex);
                writer.writeShort(info.x);
                writer.writeShort(info.y);
                writer.writeShort((short) info.attr);
            }
        }

        int bankSectionEnd = writer.getPosition();
        writer.setPosition(bankSizePos);
        writer.writeInt(bankSectionEnd - NTR_HEADER_SIZE);
        writer.setPosition(bankSectionEnd);

        int fileSize = writer.getPosition();
        writer.setPosition(0);
        writeGenericNtrHeader(writer, fileSize, numBlocks);
        writer.setPosition(fileSize);

        return dataBuf.reader().getBuffer();
    }

    /**
     * Gets the multi-cells contained in this bank.
     * @return a <code>MultiCell[]</code>
     */
    public MultiCell[] getMultiCells()
    {
        return multiCells;
    }

    /**
     * Gets the number of multi-cells in this bank.
     * @return an <code>int</code>
     */
    public int getNumMultiCells()
    {
        return multiCells.length;
    }

    /**
     * Gets the multi-cell at index <code>i</code>.
     * @param i the index of the multi-cell
     * @return a {@link MultiCell}
     */
    public MultiCell getMultiCell(int i)
    {
        return multiCells[i];
    }

    /* BEGIN SECTION: rendering across the NCER/NCGR layers */

    /**
     * Associates the companion cell bank (NCER) whose cells this multi-cell bank composes. The bank must
     * have its own parent NCGR set (via {@link CellBank#setParentImage}) before any multi-cell is
     * rendered, since that is where the pixels come from. Not stored in the file.
     * @param cellBank a {@link CellBank}
     */
    public void setCellBank(CellBank cellBank)
    {
        this.cellBank = cellBank;
    }

    /**
     * Gets the companion cell bank set with {@link #setCellBank}, or null if none has been set.
     * @return a {@link CellBank}
     */
    public CellBank getCellBank()
    {
        return cellBank;
    }

    /**
     * Gets the bounding rectangle of a multi-cell in its own coordinate space, where (0,0) is the origin
     * the cell offsets are measured from (the point an NMAR rotates and scales the multi-cell about). It
     * is the union of each composed cell's own bounds shifted by that cell's offset.
     * @param i the index of the multi-cell
     * @return a {@link Rectangle}
     */
    public Rectangle getMultiCellBounds(int i)
    {
        requireCellBank();
        return multiCellBounds(multiCells[i]);
    }

    private Rectangle multiCellBounds(MultiCell multiCell)
    {
        if (multiCell.cellInfos.length == 0)
            return new Rectangle(0, 0, 0, 0);

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (CellInfo info : multiCell.cellInfos)
        {
            Rectangle cb = cellBank.getCellBounds(info.cellIndex);
            int x = info.x + cb.x, y = info.y + cb.y;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + cb.width);
            maxY = Math.max(maxY, y + cb.height);
        }
        return new Rectangle(minX, minY, maxX - minX, maxY - minY);
    }

    /**
     * Renders a multi-cell: every cell it composes, assembled from the companion NCER/NCGR and placed at
     * its stored offset. The image is sized to the multi-cell's own bounds (no clipping, no dead space).
     * @param i the index of the multi-cell
     * @return a <code>BufferedImage</code>
     * @throws IllegalStateException if no cell bank has been associated with {@link #setCellBank}
     */
    public BufferedImage getMultiCellImage(int i)
    {
        return renderMultiCell(i, false);
    }

    /**
     * Same as {@link #getMultiCellImage(int)}, but drawn on a transparent canvas so the assembled
     * multi-cell can be composited &mdash; for instance under an animation transform supplied by a
     * {@link MultiCellAnimation}.
     * @param i the index of the multi-cell
     * @return a <code>BufferedImage</code> with an alpha channel
     */
    public BufferedImage getTransparentMultiCellImage(int i)
    {
        return renderMultiCell(i, true);
    }

    private BufferedImage renderMultiCell(int i, boolean transparent)
    {
        requireCellBank();
        MultiCell multiCell = multiCells[i];
        Rectangle bounds = multiCellBounds(multiCell);

        BufferedImage output = new BufferedImage(Math.max(1, bounds.width), Math.max(1, bounds.height),
                transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = output.createGraphics();

        // Draw each cell (always on a transparent cell canvas, so index-0 pixels don't paint over cells
        // beneath), positioned so its cell-space origin lands at the cell-info offset.
        for (CellInfo info : multiCell.cellInfos)
        {
            BufferedImage cell = cellBank.getTransparentNcerImage(info.cellIndex);
            Rectangle cb = cellBank.getCellBounds(info.cellIndex);
            int dx = (info.x + cb.x) - bounds.x;
            int dy = (info.y + cb.y) - bounds.y;
            g.drawImage(cell, dx, dy, null);
        }
        g.dispose();
        return output;
    }

    private void requireCellBank()
    {
        if (cellBank == null)
            throw new IllegalStateException("No cell bank set; call setCellBank(CellBank) before rendering multi-cells.");
    }

    /* END SECTION: rendering */

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        MultiCellBank that = (MultiCellBank) o;
        return beef == that.beef
                && reserved0 == that.reserved0
                && reserved1 == that.reserved1
                && numBlocks == that.numBlocks
                && Arrays.equals(multiCells, that.multiCells);
    }

    @Override
    public int hashCode()
    {
        int result = Objects.hash(beef, reserved0, reserved1, numBlocks);
        result = 31 * result + Arrays.hashCode(multiCells);
        return result;
    }

    @Override
    public String toString()
    {
        return String.format("MultiCellBank[%d multi-cells]", multiCells.length);
    }

    /**
     * A single multi-cell within an NMCR: an ordered list of {@link CellInfo} placements of a companion
     * {@link CellBank}'s cells, drawn first (bottom) to last (top).
     */
    public class MultiCell
    {
        // The second u16 of the descriptor. Its exact bit layout (some mix of a node/cell count and
        // flags — it ranges 0..35 across the retail corpus) isn't decoded field by field; carried
        // verbatim so the descriptor reproduces exactly.
        private int attribute;
        private CellInfo[] cellInfos;

        /**
         * Gets the placements composing this multi-cell, in draw order (first = bottom).
         * @return a <code>CellInfo[]</code>
         */
        public CellInfo[] getCellInfos()
        {
            return cellInfos;
        }

        /**
         * Gets the number of cells composing this multi-cell.
         * @return an <code>int</code>
         */
        public int getNumCells()
        {
            return cellInfos.length;
        }

        /**
         * Gets this multi-cell's raw attribute word.
         * @return an <code>int</code>
         */
        public int getAttribute()
        {
            return attribute;
        }

        /**
         * Sets this multi-cell's raw attribute word.
         * @param attribute an <code>int</code>
         */
        public void setAttribute(int attribute)
        {
            this.attribute = attribute;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            MultiCell that = (MultiCell) o;
            return attribute == that.attribute && Arrays.equals(cellInfos, that.cellInfos);
        }

        @Override
        public int hashCode()
        {
            return 31 * Objects.hash(attribute) + Arrays.hashCode(cellInfos);
        }

        @Override
        public String toString()
        {
            return String.format("MultiCell[%d cells]", cellInfos.length);
        }
    }

    /**
     * A single placement of a companion {@link CellBank} cell within a {@link MultiCell}: the cell's
     * index and the pixel offset it is drawn at (measured from the multi-cell's origin).
     */
    public class CellInfo
    {
        private int cellIndex;
        private short x;
        private short y;
        // Palette / priority / flip bits (the on-disk value, e.g. 0x20/0x21/0x120…). Not decoded field
        // by field; carried verbatim so the entry reproduces exactly.
        private int attr;

        /**
         * Gets the index of the {@link CellBank} cell this placement draws.
         * @return an <code>int</code>
         */
        public int getCellIndex()
        {
            return cellIndex;
        }

        /**
         * Sets the index of the {@link CellBank} cell this placement draws.
         * @param cellIndex an <code>int</code>
         */
        public void setCellIndex(int cellIndex)
        {
            this.cellIndex = cellIndex;
        }

        /**
         * Gets this placement's x offset from the multi-cell origin, in pixels.
         * @return an <code>int</code>
         */
        public int getX()
        {
            return x;
        }

        /**
         * Sets this placement's x offset from the multi-cell origin, in pixels.
         * @param x an <code>int</code>
         */
        public void setX(int x)
        {
            this.x = (short) x;
        }

        /**
         * Gets this placement's y offset from the multi-cell origin, in pixels.
         * @return an <code>int</code>
         */
        public int getY()
        {
            return y;
        }

        /**
         * Sets this placement's y offset from the multi-cell origin, in pixels.
         * @param y an <code>int</code>
         */
        public void setY(int y)
        {
            this.y = (short) y;
        }

        /**
         * Gets this placement's raw attribute word (packs palette/priority/flip bits).
         * @return an <code>int</code>
         */
        public int getAttr()
        {
            return attr;
        }

        /**
         * Sets this placement's raw attribute word.
         * @param attr an <code>int</code>
         */
        public void setAttr(int attr)
        {
            this.attr = attr;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            CellInfo that = (CellInfo) o;
            return cellIndex == that.cellIndex && x == that.x && y == that.y && attr == that.attr;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(cellIndex, x, y, attr);
        }

        @Override
        public String toString()
        {
            return String.format("CellInfo[cell=%d, (%d,%d)]", cellIndex, x, y);
        }
    }
}
