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

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Objects;

/**
 * An object representation of an NCER file
 */
public class CellBank extends GenericNtrFile
{
    private int bankType;
    private int mappingType;
    private boolean vramTransfer;
    private boolean tacu;

    // The number of name entries in the LBAL section. This is independent of the cell count: a file may
    // label only the first few of its cells, or carry *more* names than it has cells. The first
    // min(labelCount, cells) names map onto cells (Cell.name); any beyond the cell count are kept in
    // extraLabels so the section round-trips exactly.
    private int labelCount;
    private String[] extraLabels = new String[0];

    // The VRAM-transfer partition header's two leading words (max partition size, and the offset to the
    // first per-cell entry), preserved so the section is reproduced exactly. Only meaningful when
    // vramTransfer is set. The per-cell partition offset/size pairs live on each Cell.
    private int maxPartitionSize;
    private int firstPartitionDataOffset;

    // Raw KBEC-header fields that are not otherwise reconstructed, kept so the file round-trips exactly:
    // the pointer to the cell data (0x1C), the reserved word at 0x28, the two section pointers (VRAM
    // partition at 0x24, TACU at 0x2C), and the trailing word of the UEXT section.
    private long bankDataOffset;
    private long kbecReserved0x28;
    private long partitionDataOffset;
    private long tacuOffset;
    // The single content word of the UEXT ("TXEU") section. It is 0 in every NCER and NANR across the
    // five retail ROMs, i.e. a reserved/unused word; preserved and reproduced regardless.
    private int uextReserved;

    // The partition (VRAM transfer) and TACU sections that follow the cell/OAM data inside the KBEC
    // block, captured verbatim. Their internal layout (alignment, padding, the exact size words) varies
    // and is not worth reconstructing field by field; preserving the bytes keeps the file byte-exact.
    // The parsed per-cell partition and TACU values are still exposed for reading.
    private byte[] auxiliaryData = new byte[0];

    private Cell[] cells;
    private IndexedImage image;

    // The size, in bytes, of one OAM entry on disk (three 16-bit attributes).
    private static final int OAM_SIZE = 6;

    /**
     * Generates an object representation of an NCER file
     * @param data a <code>byte[]</code> representation of an NCER file
     */
    public CellBank(byte[] data)
    {
        super("RECN");
        MemBuf dataBuf = MemBuf.create(data);
        MemBuf.MemBufReader reader = dataBuf.reader();
        int fileSize = dataBuf.writer().getPosition();

        readGenericNtrHeader(reader);

        // reader position is now 0x10

        boolean labelEnabled = numBlocks != 1;

        //cell bank data
        String cellBankMagic = reader.readString(4); // 0x10

        if (!cellBankMagic.equals("KBEC")) {
            throw new RuntimeException("Not a valid RECN file.");
        }

        long cellBankSectionSize = reader.readUInt32(); // 0x14
        int numBanks = reader.readUInt16(); // 0x18
        bankType = reader.readUInt16(); // 0x1A
        bankDataOffset = reader.readUInt32(); // 0x1C

        mappingType = (int) (reader.readUInt32() & 0xFF); // 0x20

        partitionDataOffset = reader.readUInt32(); // 0x24
        kbecReserved0x28 = reader.readUInt32(); // 0x28
        tacuOffset = reader.readUInt32(); // 0x2C

        vramTransfer = partitionDataOffset != 0;
        tacu = tacuOffset != 0;

        int storedPos = reader.getPosition();

        int[][] partitionData = new int[numBanks][2];
        if (vramTransfer)
        {
            reader.setPosition(NTR_HEADER_SIZE + partitionDataOffset + 8);
            maxPartitionSize = reader.readInt();
            firstPartitionDataOffset = reader.readInt();
            reader.skip(firstPartitionDataOffset - 8);
            for (int i= 0; i < numBanks; i++)
            {
                int partitionOffset = reader.readInt();
                int partitionSize = reader.readInt();
                partitionData[i] = new int[] {partitionOffset, partitionSize};
            }
        }

        int[] cellAttributes = new int[numBanks];
        if (tacu)
        {
            reader.setPosition(NTR_HEADER_SIZE + tacuOffset + 8);

            String tacuMagic = reader.readString(4);

            if (!tacuMagic.equals("TACU")) {
                throw new RuntimeException("Not a valid RECN file.");
            }

            int tacuSize = reader.readInt();
            int numTacuCells = reader.readUInt16();

            if (numTacuCells != numBanks)
                throw new RuntimeException("Idk what to do here - tacu cell stuff");

            cellAttributes = new int[numTacuCells];
            int numAttributes = reader.readUInt16();

            if (numAttributes != 1)
                throw new RuntimeException("Idk what to do here - tacu attribute not 1?");

            int cellAttributeOffset = reader.readInt();
            reader.skip(cellAttributeOffset - 8);

            for (int i = 0; i < numTacuCells; i++)
            {
                cellAttributes[i] = reader.readInt();
            }
        }

        reader.setPosition(storedPos);

        // reader is now at 0x30

        cells = new Cell[numBanks];

        for (int i = 0; i < numBanks; i++)
        {
            int cellCount = reader.readUInt16();
            cells[i] = new Cell(cellCount);
            cells[i].partitionOffset = partitionData[i][0];
            cells[i].partitionSize = partitionData[i][1];
            cells[i].tacuData = cellAttributes[i];

            int cellAttrs = reader.readUInt16();
            cells[i].attributes.hFlip = ((cellAttrs >> 8) & 1) == 1;
            cells[i].attributes.vFlip = ((cellAttrs >> 9) & 1) == 1;
            cells[i].attributes.hvFlip = ((cellAttrs >> 10) & 1) == 1;
            cells[i].attributes.boundingRectangle = ((cellAttrs >> 11) & 1) == 1;
            cells[i].attributes.boundingSphereRadius = cellAttrs & 0x3F;

            int cellOffset = reader.readInt();

            if (bankType != 0) // the 8-byte bounding rectangle only exists in bank type 1
            {
                cells[i].maxX = reader.readShort();
                cells[i].maxY = reader.readShort();
                cells[i].minX = reader.readShort();
                cells[i].minY = reader.readShort();
            }

            storedPos = reader.getPosition();

            if (bankType == 0)
                reader.setPosition(reader.getPosition() + (numBanks - (i+1)) * 8 + cellOffset);
            else
                reader.setPosition(reader.getPosition() + (numBanks - (i+1)) * 0x10 + cellOffset);

            // read OAMs
            for (int x = 0; x < cellCount; x++)
            {
                cells[i].oams[x].yCoord = (byte) reader.readByte(); //bits 0-7 (signed)
                byte attr0 = (byte) reader.readByte();
                cells[i].oams[x].rotation = (attr0 & 1) == 1; //bit 8
                cells[i].oams[x].sizeDisable = ((attr0 >> 1) & 1) == 1; //bit 9 Obj Size (if rotation) or Obj Disable (if not rotation)
                cells[i].oams[x].mode = (attr0 >> 2) & 3; //bits 10-11
                cells[i].oams[x].mosaic = ((attr0 >> 4) & 1) == 1; //bit 12
                cells[i].oams[x].colors = ((attr0 >> 5) & 1) == 0 ? 16 : 256; //bit 13
                cells[i].oams[x].shape = (attr0 >> 6) & 3; //bits 14-15

                short attr1 = reader.readShort();
                cells[i].oams[x].xCoord = (attr1 & 0x01ff) >= 0x100 ? (attr1 & 0x01ff) - 0x200 : (attr1 & 0x01ff);
                cells[i].oams[x].rotationScaling = (attr1 >> 9) & 0x1F;
                cells[i].oams[x].size = (attr1 >> 14) & 3;

                short attr2 = reader.readShort();
                cells[i].oams[x].tileOffset = attr2 & 0x3FF;
                cells[i].oams[x].priority = (attr2 >> 10) & 3;
                cells[i].oams[x].palette = (attr2 >> 12) & 0xF;
            }

            reader.setPosition(storedPos);
        }

        // Capture whatever follows the cell descriptors and their OAMs inside the KBEC block: the VRAM
        // partition and/or TACU sections, plus any alignment. The cell/OAM data is a fixed-size grid, so
        // its end is computable, and everything from there to the end of the block is preserved verbatim.
        int cellDescriptorSize = (bankType == 0) ? 8 : 0x10;
        int totalOams = 0;
        for (Cell cell : cells)
            totalOams += cell.oams.length;
        int cellDataEnd = NTR_HEADER_SIZE + 0x20 + numBanks * cellDescriptorSize + totalOams * OAM_SIZE;
        int cellBankSectionEnd = NTR_HEADER_SIZE + (int) cellBankSectionSize;
        reader.setPosition(cellDataEnd);
        auxiliaryData = reader.readBytes(cellBankSectionEnd - cellDataEnd);

        if (!labelEnabled)
            return;

        reader.setPosition(NTR_HEADER_SIZE + cellBankSectionSize);

        //label data
        String labelMagic = reader.readString(4); // 0x10

        if (!labelMagic.equals("LBAL")) {
            throw new RuntimeException("Not a valid RECN file.");
        }

        int labelSectionSize = reader.readInt();

        // The offset table has one entry per name, and there is no count for it: the number of names is
        // neither the cell count (a file can name only some cells, or carry more names than cells) nor
        // stored anywhere. The string data begins right after the table, so a genuine offset is always
        // smaller than the section payload, while the first bytes of the string data (ASCII) read back
        // as a large word. Read offsets until one is too large to be an offset.
        java.util.List<Long> offsets = new java.util.ArrayList<>();
        while (true)
        {
            long offset = reader.readUInt32();
            if (offset >= labelSectionSize - 8)
            {
                reader.setPosition(reader.getPosition() - 4);
                break;
            }
            offsets.add(offset);
        }
        labelCount = offsets.size();

        // Names are stored back-to-back after the table; each runs to the next name's offset, and the
        // last runs to the end of the string region. The first names map to cells; any surplus is kept
        // aside so it can be written back out.
        int stringRegionSize = labelSectionSize - 8 - 4 * labelCount;
        java.util.List<String> surplus = new java.util.ArrayList<>();
        for (int i = 0; i < labelCount; i++)
        {
            long end = (i + 1 < labelCount) ? offsets.get(i + 1) : stringRegionSize;
            String name = reader.readString((int) (end - offsets.get(i))).trim();
            if (i < cells.length)
                cells[i].name = name;
            else
                surplus.add(name);
        }
        extraLabels = surplus.toArray(new String[0]);

        //uext data
        String uextMagic = reader.readString(4); // (note: this isn't guaranteed to be 4-byte aligned)

        if (!uextMagic.equals("TXEU")) {
            throw new RuntimeException("Not a valid RECN file.");
        }

        int uextSectionSize = reader.readInt();
        uextReserved = reader.readInt();
    }


    /**
     * Generate a <code>byte[]</code> representation of this <code>CellBank</code> as an NCER
     * @return a <code>byte[]</code>
     */
    public byte[] save()
    {
        MemBuf dataBuf = MemBuf.create();
        MemBuf.MemBufWriter writer = dataBuf.writer();

        writer.skip(NTR_HEADER_SIZE);

        // KBEC header
        writer.writeString("KBEC"); // 0x10
        int sectionSizePos = writer.getPosition();
        writer.skip(4); // 0x14 section size, patched in below
        writer.writeShort((short) cells.length); // 0x18
        writer.writeShort((short) bankType); // 0x1A
        writer.writeUInt32(bankDataOffset); // 0x1C
        writer.writeUInt32(mappingType); // 0x20
        writer.writeUInt32(partitionDataOffset); // 0x24 (VRAM partition section pointer, 0 if none)
        writer.writeUInt32(kbecReserved0x28); // 0x28
        writer.writeUInt32(tacuOffset); // 0x2C (TACU section pointer, 0 if none)

        // cell descriptors, then their OAMs
        int oamCount = 0;
        for (Cell cell : cells)
        {
            NcerUtils.writeCell(writer, cell, oamCount, bankType);
            oamCount += cell.oams.length;
        }
        for (Cell cell : cells)
        {
            NcerUtils.writeOams(writer, cell);
        }

        // the preserved VRAM partition / TACU sections that follow, verbatim
        writer.write(auxiliaryData);

        int bankSectionEnd = writer.getPosition();
        writer.setPosition(sectionSizePos); // 0x14: length of the KBEC section
        writer.writeInt(bankSectionEnd - NTR_HEADER_SIZE);
        writer.setPosition(bankSectionEnd);

        if (numBlocks > 1)
        {
            // label section: labelCount names (the first ones the cells', any surplus preserved), then UEXT
            writer.writeString("LBAL");
            NcerUtils.writeLabelSection(writer, cells, labelCount, extraLabels);

            writer.writeString("TXEU");
            writer.writeInt(12); // section size (magic + size + one word of contents)
            writer.writeInt(uextReserved);
        }

        int storedPos = writer.getPosition();
        writer.setPosition(0); //total file size

        writeGenericNtrHeader(writer, storedPos, numBlocks);

        writer.setPosition(storedPos);

        return dataBuf.reader().getBuffer();
    }

    /**
     * Internal private class for actions relating to reading/writing NCER files
     */
    private static class NcerUtils {
        private static final int oamSize = OAM_SIZE;

        private static void writeCell(MemBuf.MemBufWriter writer, Cell cell, int oamCount, int bankType)
        {
            writer.writeShort((short) cell.oams.length);
            writer.writeShort(writeCellAttributes(cell));
            writer.writeInt(oamCount * oamSize);

            if (bankType != 0) // the 8-byte bounding rectangle only exists in bank type 1
            {
                writer.writeShort(cell.maxX);
                writer.writeShort(cell.maxY);
                writer.writeShort(cell.minX);
                writer.writeShort(cell.minY);
            }
        }

        private static short writeCellAttributes(Cell cell)
        {
            int attrs = 0;
            attrs |= (cell.attributes.hFlip ? 1 : 0) << 8;
            attrs |= (cell.attributes.vFlip ? 1 : 0) << 9;
            attrs |= (cell.attributes.hvFlip ? 1 : 0) << 10;
            attrs |= (cell.attributes.boundingRectangle ? 1 : 0) << 11;
            attrs |= cell.attributes.boundingSphereRadius & 0x3f;

            return (short) (attrs & 0xffff);
        }

        private static void writeOams(MemBuf.MemBufWriter writer, Cell cell)
        {
            for (int i = 0; i < cell.oams.length; i++)
            {
                Cell.OAM oam = cell.oams[i];
                writer.writeByte((byte) oam.yCoord);

                int attr0 = 0;
                attr0 |= (oam.rotation ? 1 : 0);
                attr0 |= (oam.sizeDisable ? 1 : 0) << 1;
                attr0 |= (oam.mode & 3) << 2;
                attr0 |= (oam.mosaic ? 1 : 0) << 4;
                attr0 |= (oam.colors == 16 ? 0 : 1) << 5;
                attr0 |= (oam.shape & 3) << 6;
                writer.writeByte((byte) attr0);

                int attr1 = 0;
                attr1 |= oam.xCoord & 0x1ff;
                attr1 |= (oam.rotationScaling & 0x1f) << 9;
                attr1 |= (oam.size & 3) << 14;
                writer.writeShort((short) attr1);

                int attr2 = 0;
                attr2 |= oam.tileOffset & 0x3ff;
                attr2 |= (oam.priority & 3) << 10;
                attr2 |= (oam.palette & 0xf) << 12;
                writer.writeShort((short) attr2);
            }
        }

        // Writes the LBAL body after its already-written magic: a table of per-name string offsets
        // followed by the NUL-terminated names, with the section size patched in. labelCount names are
        // emitted (retail files often label only the first few cells), matching what the reader detected.
        private static void writeLabelSection(MemBuf.MemBufWriter writer, Cell[] cells, int labelCount, String[] extraLabels)
        {
            int sectionStart = writer.getPosition() - 4; // back up to the "LBAL" magic
            int stringStartOffset = 8 + (4 * labelCount);
            writer.setPosition(sectionStart + stringStartOffset);

            long[] offsets = new long[labelCount];
            for (int i = 0; i < labelCount; i++)
            {
                offsets[i] = writer.getPosition() - (sectionStart + stringStartOffset);
                String name = (i < cells.length) ? cells[i].name : extraLabels[i - cells.length];
                writer.writeString(name + "\0");
            }

            int labelEnd = writer.getPosition();

            writer.setPosition(sectionStart + 8); // start of the offset table
            for (long offset : offsets)
                writer.writeUInt32(offset);

            writer.setPosition(sectionStart + 4); // section-size field
            writer.writeInt(labelEnd - sectionStart);
            writer.setPosition(labelEnd);
        }
    }

    /**
     * Sets the parent <code>IndexedImage</code> used to display image data from this <code>CellBank</code>
     * @param image a <code>IndexedImage</code>
     */
    public void setParentImage(IndexedImage image)
    {
        // The OAM composition addresses the parent as TILED character data (convertToTiles + tile
        // offsets). A scanned (bitmap) NCGR is laid out linearly, so its OAM offsets don't map onto a
        // re-tiled grid — composing it produces scrambled output. Bitmap-OBJ composition isn't
        // implemented yet, so refuse rather than emit garbage. Note: the scanned NCGR's own pixels are
        // a correct bitmap, so callers can still render it directly (e.g. DPPt trbgra.narc trainer
        // sprites are viewable as the raw NCGR). See IndexedImage#isScanned().
        if (image.getScanMode() != IndexedImage.NcgrUtils.ScanMode.NOT_SCANNED)
            throw new RuntimeException("Can't use a scanned image with an NCER");
        this.image = image;
    }

    public Cell.CellImage getCellImage(int i)
    {
        Cell cell = cells[i];
        return cell.getImage();
    }

    public Cell.OAM.OamImage[] getCellImages(int i)
    {
        Cell cell = cells[i];
        return cell.getImages();
    }

    public BufferedImage getNcerImage(int i)
    {
        return renderCell(i, false);
    }

    /**
     * Same as {@link #getNcerImage(int)}, but drawn on a transparent canvas (OAM colour index 0 is
     * left transparent) so the assembled cell can be composited &mdash; for instance under an
     * animation transform supplied by a {@link CellAnimation}.
     * @param i the index of the cell
     * @return a <code>BufferedImage</code> the size of the cell, with an alpha channel
     */
    public BufferedImage getTransparentNcerImage(int i)
    {
        return renderCell(i, true);
    }

    // Composites a cell's OAMs onto a canvas sized to the cell's own bounds, rather than a fixed
    // square. The bounds come from where the OAMs actually sit (their signed positions plus sizes), so
    // the image is neither clipped (as an oversized cell was against the old 80x80 canvas) nor padded
    // with dead space, and it works for both bank types (bank type 0 carries no stored bounding box).
    private BufferedImage renderCell(int i, boolean transparent)
    {
        Cell cell = cells[i];
        Rectangle bounds = cellBounds(cell);

        BufferedImage output = new BufferedImage(Math.max(1, bounds.width), Math.max(1, bounds.height),
                transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = output.createGraphics();

        Cell.OAM.OamImage[] images = cell.getImages();
        for (int x = 0; x < images.length; x++)
        {
            Cell.OAM oam = cell.oams[x];
            BufferedImage oamImage = transparent ? images[x].getTransparentImage() : images[x].getImage();
            g.drawImage(oamImage, oam.xCoord - bounds.x, oam.yCoord - bounds.y, null);
        }
        g.dispose();

        return output;
    }

    /**
     * Gets the bounding rectangle of a cell in its own coordinate space, where (0,0) is the origin the
     * OAM offsets are measured from (the point the DS rotates and scales the cell about). The rectangle
     * is derived from the OAMs' positions and sizes, so it is available for both bank types.
     * @param i the index of the cell
     * @return a {@link Rectangle}
     */
    public Rectangle getCellBounds(int i)
    {
        return cellBounds(cells[i]);
    }

    private Rectangle cellBounds(Cell cell)
    {
        if (cell.oams.length == 0)
            return new Rectangle(0, 0, 0, 0);

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (Cell.OAM oam : cell.oams)
        {
            int[] size = getOamSize(oam); // {width, height}
            minX = Math.min(minX, oam.xCoord);
            minY = Math.min(minY, oam.yCoord);
            maxX = Math.max(maxX, oam.xCoord + size[0]);
            maxY = Math.max(maxY, oam.yCoord + size[1]);
        }
        return new Rectangle(minX, minY, maxX - minX, maxY - minY);
    }

    /**
     * Gets the number of cells in this bank.
     * @return an <code>int</code>
     */
    public int getNumCells()
    {
        return cells.length;
    }

    /**
     * Gets this bank's cell layout type: 0 (no per-cell bounding rectangle) or 1 (each cell carries an
     * 8-byte bounding rectangle).
     * @return an <code>int</code>
     */
    public int getBankType()
    {
        return bankType;
    }

    /**
     * Gets the tile mapping type used to interpret OAM tile offsets (32, 64, 128 or 256).
     * @return an <code>int</code>
     */
    public int getMappingType()
    {
        return mappingType;
    }

    /**
     * Gets the cell at index <code>i</code>.
     * @param i the index of the cell
     * @return a {@link Cell}
     */
    public Cell getCell(int i)
    {
        return cells[i];
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        CellBank that = (CellBank) o;
        return bankType == that.bankType
                && mappingType == that.mappingType
                && vramTransfer == that.vramTransfer
                && tacu == that.tacu
                && numBlocks == that.numBlocks
                && bankDataOffset == that.bankDataOffset
                && partitionDataOffset == that.partitionDataOffset
                && tacuOffset == that.tacuOffset
                && kbecReserved0x28 == that.kbecReserved0x28
                && uextReserved == that.uextReserved
                && labelCount == that.labelCount
                && Arrays.equals(cells, that.cells)
                && Arrays.equals(auxiliaryData, that.auxiliaryData)
                && Arrays.equals(extraLabels, that.extraLabels);
    }

    @Override
    public int hashCode()
    {
        int result = Objects.hash(bankType, mappingType, vramTransfer, tacu, numBlocks, bankDataOffset,
                partitionDataOffset, tacuOffset, kbecReserved0x28, uextReserved, labelCount);
        result = 31 * result + Arrays.hashCode(cells);
        result = 31 * result + Arrays.hashCode(auxiliaryData);
        result = 31 * result + Arrays.hashCode(extraLabels);
        return result;
    }

    /**
     * An individual "Cell", or "Bank" within an NCER.
     * In theory, this represents one assembled image.
     */
    public class Cell {
        String name;
        int tacuData;

        CellAttribute attributes;
        short maxX;
        short maxY;
        short minX;
        short minY;
        OAM[] oams;

        private int partitionOffset;
        private int partitionSize;

        /**
         * Creates a new Cell for use in a CellBank
         * @param oamCount an <code>int</code> representing the number of OAMs in the cell
         */
        public Cell(int oamCount)
        {
            attributes = new CellAttribute();
            oams = new OAM[oamCount];
            for (int i = 0; i < oamCount; i++)
            {
                oams[i] = new OAM();
            }
            name = "";
            tacuData = -1;
        }

        public OAM.OamImage[] getImages()
        {
            int[] index = null;

            OAM.OamImage[] images = new OAM.OamImage[oams.length];

            for (int i = 0; i < oams.length; i++)
            {
                OAM oam = oams[i];

                if (oam == null)
                    break;

                images[i] = oam.getImage(i, index);
            }

            return images;
        }

        public String getName()
        {
            return name;
        }

        public void setName(String name)
        {
            this.name = name;
        }

        public int getTacuData()
        {
            return tacuData;
        }

        public void setTacuData(int tacuData)
        {
            this.tacuData = tacuData;
        }

        public CellAttribute getAttributes()
        {
            return attributes;
        }

        public void setAttributes(CellAttribute attributes)
        {
            this.attributes = attributes;
        }

        public short getMaxX()
        {
            return maxX;
        }

        public void setMaxX(short maxX)
        {
            this.maxX = maxX;
        }

        public short getMaxY()
        {
            return maxY;
        }

        public void setMaxY(short maxY)
        {
            this.maxY = maxY;
        }

        public short getMinX()
        {
            return minX;
        }

        public void setMinX(short minX)
        {
            this.minX = minX;
        }

        public short getMinY()
        {
            return minY;
        }

        public void setMinY(short minY)
        {
            this.minY = minY;
        }

        public OAM[] getOams()
        {
            return oams;
        }

        public void setOams(OAM[] oams)
        {
            this.oams = oams;
        }

        public String toString()
        {
            return name;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            Cell cell = (Cell) o;
            return tacuData == cell.tacuData
                    && maxX == cell.maxX && maxY == cell.maxY && minX == cell.minX && minY == cell.minY
                    && partitionOffset == cell.partitionOffset && partitionSize == cell.partitionSize
                    && Objects.equals(name, cell.name)
                    && Objects.equals(attributes, cell.attributes)
                    && Arrays.equals(oams, cell.oams);
        }

        @Override
        public int hashCode()
        {
            int result = Objects.hash(name, tacuData, maxX, maxY, minX, minY, attributes, partitionOffset, partitionSize);
            result = 31 * result + Arrays.hashCode(oams);
            return result;
        }

        class CellAttribute {
            boolean hFlip;
            boolean vFlip;
            boolean hvFlip;
            boolean boundingRectangle;
            int boundingSphereRadius;

            @Override
            public boolean equals(Object o)
            {
                if (this == o)
                    return true;
                if (o == null || getClass() != o.getClass())
                    return false;
                CellAttribute that = (CellAttribute) o;
                return hFlip == that.hFlip && vFlip == that.vFlip && hvFlip == that.hvFlip
                        && boundingRectangle == that.boundingRectangle
                        && boundingSphereRadius == that.boundingSphereRadius;
            }

            @Override
            public int hashCode()
            {
                return Objects.hash(hFlip, vFlip, hvFlip, boundingRectangle, boundingSphereRadius);
            }
        }

        public CellImage getImage()
        {
            return new CellImage();
        }

        /**
         * An individual OAM within an NCER (<code>CellBank</code>).
         * This represents the sub-images that make up a Cell/Bank, or more accurately,
         * the data used to generate them from an NCGR (<code>IndexedImage</code>).
         */
        public class OAM {
            // attr0
            int yCoord;
            boolean rotation;
            boolean sizeDisable;
            int mode;
            boolean mosaic;
            int colors;
            int shape;

            // attr1
            int xCoord;
            int rotationScaling;
            int size;

            // attr2
            int tileOffset;
            int priority;
            int palette;

            @Override
            public boolean equals(Object o)
            {
                if (this == o)
                    return true;
                if (o == null || getClass() != o.getClass())
                    return false;
                OAM oam = (OAM) o;
                return yCoord == oam.yCoord && rotation == oam.rotation && sizeDisable == oam.sizeDisable
                        && mode == oam.mode && mosaic == oam.mosaic && colors == oam.colors && shape == oam.shape
                        && xCoord == oam.xCoord && rotationScaling == oam.rotationScaling && size == oam.size
                        && tileOffset == oam.tileOffset && priority == oam.priority && palette == oam.palette;
            }

            @Override
            public int hashCode()
            {
                return Objects.hash(yCoord, rotation, sizeDisable, mode, mosaic, colors, shape,
                        xCoord, rotationScaling, size, tileOffset, priority, palette);
            }

            /** @return this OAM's y position, as a signed offset from the cell origin */
            public int getYCoord() { return yCoord; }
            /** @param yCoord this OAM's y position, as a signed offset from the cell origin */
            public void setYCoord(int yCoord) { this.yCoord = yCoord; }

            /** @return this OAM's x position, as a signed offset from the cell origin */
            public int getXCoord() { return xCoord; }
            /** @param xCoord this OAM's x position, as a signed offset from the cell origin */
            public void setXCoord(int xCoord) { this.xCoord = xCoord; }

            /** @return this OAM's shape index (0 square, 1 wide, 2 tall), which with the size selects its dimensions */
            public int getShape() { return shape; }
            /** @param shape this OAM's shape index (0 square, 1 wide, 2 tall) */
            public void setShape(int shape) { this.shape = shape; }

            /** @return this OAM's size index (0-3), which with the shape selects its dimensions */
            public int getSize() { return size; }
            /** @param size this OAM's size index (0-3) */
            public void setSize(int size) { this.size = size; }

            /** @return the tile offset into the NCGR this OAM draws from */
            public int getTileOffset() { return tileOffset; }
            /** @param tileOffset the tile offset into the NCGR this OAM draws from */
            public void setTileOffset(int tileOffset) { this.tileOffset = tileOffset; }

            /** @return the 16-colour sub-palette index this OAM uses */
            public int getPalette() { return palette; }
            /** @param palette the 16-colour sub-palette index this OAM uses */
            public void setPalette(int palette) { this.palette = palette; }

            /** @return this OAM's priority (0-3) */
            public int getPriority() { return priority; }
            /** @param priority this OAM's priority (0-3) */
            public void setPriority(int priority) { this.priority = priority; }

            /** @return this OAM's graphics mode (0 normal, 1 semi-transparent, 2 window) */
            public int getMode() { return mode; }
            /** @param mode this OAM's graphics mode (0 normal, 1 semi-transparent, 2 window) */
            public void setMode(int mode) { this.mode = mode; }

            /** @return whether this OAM uses mosaic */
            public boolean isMosaic() { return mosaic; }
            /** @param mosaic whether this OAM uses mosaic */
            public void setMosaic(boolean mosaic) { this.mosaic = mosaic; }

            /** @return the colour count of this OAM's tiles (16 or 256) */
            public int getColors() { return colors; }
            /** @param colors the colour count of this OAM's tiles (16 or 256) */
            public void setColors(int colors) { this.colors = colors; }

            /** @return whether this OAM is affine (rotation/scaling) rather than a plain sprite */
            public boolean isRotation() { return rotation; }
            /** @param rotation whether this OAM is affine (rotation/scaling) rather than a plain sprite */
            public void setRotation(boolean rotation) { this.rotation = rotation; }

            /** @return for an affine OAM, whether double-size is set; for a plain OAM, whether it is disabled */
            public boolean isSizeDisable() { return sizeDisable; }
            /** @param sizeDisable for an affine OAM, whether double-size is set; for a plain OAM, whether it is disabled */
            public void setSizeDisable(boolean sizeDisable) { this.sizeDisable = sizeDisable; }

            /** @return the affine (rotation/scaling) parameter set index this OAM uses */
            public int getRotationScaling() { return rotationScaling; }
            /** @param rotationScaling the affine (rotation/scaling) parameter set index this OAM uses */
            public void setRotationScaling(int rotationScaling) { this.rotationScaling = rotationScaling; }

            public OamImage getImage(int i, int[] index)
            {
                boolean draw = false;
                if (index == null)
                    draw = true;
                else
                    for (int j : index)
                        if (j == i)
                        {
                            draw = true;
                            break;
                        }

                if (!draw)
                    return null;

                int num_pal = palette;
                if (num_pal >= image.getPalette().getNumColors())
                    num_pal = 0;
//                Arrays.fill(cell_img.tilePal, num_pal);

                return new OamImage();
            }

            /**
             * This is a visual representation of a given OAM within its parent NCGR (<code>IndexedImage</code>) and <code>Cell</code>
             */
            public class OamImage
            {
                private IndexedImage oamImage;
                int storedWidth = 0;
                int storedHeight = 0;
                private boolean update;

                private OamImage()
                {
                    generateImageData();
                }

                private void generateImageData()
                {
                    if (oamSize[shape][size][0] != storedWidth || oamSize[shape][size][1] != storedHeight)
                    {
                        storedHeight = oamSize[shape][size][1];
                        storedWidth = oamSize[shape][size][0];
                        oamImage = new IndexedImage(storedHeight, storedWidth, image.getBitDepth(), image.getPalette());
                    }

                    int startByte = (tileOffset << (byte) mappingType) * (image.getBitDepth() * 8) + partitionOffset;
                    byte[] imageData;

                    switch (oamImage.getBitDepth())
                    {
                        case 4:
                            imageData = IndexedImage.NcgrUtils.convertToTiles4Bpp(image);
                            IndexedImage.NcgrUtils.convertFromTiles4Bpp(imageData, oamImage, startByte);
                            break;
                        case 8:
                            imageData = IndexedImage.NcgrUtils.convertToTiles8Bpp(image);
                            IndexedImage.NcgrUtils.convertFromTiles8Bpp(imageData, oamImage, startByte);
                            break;
                    }
//                    IndexedImage.NcgrUtils.convertOffsetToCoordinate(imageData, startByte, cell.getWidth() * cell.getHeight(), image, image.getNumTiles(), (image.getWidth() / 8) / image.getColsPerChunk(), image.getColsPerChunk(), image.getRowsPerChunk(), cell);
//                    IndexedImage.NcgrUtils.convertFromTiles4BppAlternate(imageData, cell, startByte);
                    update = false;
                }

                /**
                 * Takes any changes which have been made to the pixels of this <code>OamImage</code> and applies them
                 * onto the parent <code>IndexedImage</code> according to the positional data specified by the parent
                 * <code>OAM</code> and <code>CellBank</code>
                 */
                public void save()
                {
                    oamImage.setBitDepth(image.getBitDepth());

                    byte[] cellData = new byte[0];
                    byte[] imageData = new byte[0];
                    switch (image.getBitDepth())
                    {
                        case 4:
                            cellData = IndexedImage.NcgrUtils.convertToTiles4Bpp(oamImage);
                            imageData = IndexedImage.NcgrUtils.convertToTiles4Bpp(image);
                            break;
                        case 8:
                            cellData = IndexedImage.NcgrUtils.convertToTiles8Bpp(oamImage);
                            imageData = IndexedImage.NcgrUtils.convertToTiles8Bpp(image);
                            break;
                    }


                    int startByte = (tileOffset << (byte) mappingType) * (image.getBitDepth() * 8) + partitionOffset;

                    System.arraycopy(cellData, 0, imageData, startByte, cellData.length);

                    switch (image.getBitDepth())
                    {
                        case 4:
                            IndexedImage.NcgrUtils.convertFromTiles4Bpp(imageData, image, 0);
                            break;
                        case 8:
                            IndexedImage.NcgrUtils.convertFromTiles8Bpp(imageData, image, 0);
                            break;
                    }
                }

                /**
                 * Generates and returns a visual (image) representation of the parent <code>OAM</code> given the parent
                 * <code>IndexedImage</code> providing image data
                 * @return a <code>BufferedImage</code>
                 */
                public BufferedImage getImage()
                {
                    if (update)
                    {
                        generateImageData();
                    }
                    return oamImage.getImage();
                }

                /**
                 * Generates and returns a visual (image) representation of the parent <code>OAM</code> given the parent
                 * <code>IndexedImage</code> providing image data, with the color at index 0 replaced with transparency
                 * @return a <code>BufferedImage</code>
                 */
                public BufferedImage getTransparentImage()
                {
                    if (update)
                    {
                        generateImageData();
                    }
                    return oamImage.getTransparentImage();
                }

                public int[][] getPixels()
                {
                    return oamImage.getPixels();
                }

                public void setPixels(int[][] pixels)
                {
                    oamImage.setPixels(pixels);
                    update = true;
                }

                public int getPixelValue(int x, int y)
                {
                    return oamImage.getPixelValue(x, y);
                }

                public void setPixelValue(int x, int y, int colorIdx)
                {
                    oamImage.setPixelValue(x, y, colorIdx);
                    update = true;
                }

                public int getHeight()
                {
                    return oamImage.getHeight();
                }

                public int getWidth()
                {
                    return oamImage.getWidth();
                }

                @Override
                public boolean equals(Object o)
                {
                    return oamImage.equals(o);
                }

                @Override
                public int hashCode()
                {
                    return oamImage.hashCode();
                }

                @Override
                public String toString()
                {
                    return String.format("%dx%d shadow with tile offset %d of %s", oamImage.getHeight(), oamImage.getWidth(), tileOffset, oamImage.toString());
                }
            }
        }

        /**
         * This is a visual representation of a given <code>Cell</code> within its parent NCGR (<code>IndexedImage</code>).
         */
        public class CellImage {
            private IndexedImage cellImage;
            private boolean update;
            private OAM.OamImage[] oamImages;

            private CellImage()
            {
                generateImageData();
            }

            private int roundUpToMultipleOfEight(int value)
            {
                if (value <= 0)
                    return 8;
                return ((value + 7) / 8) * 8;
            }

            private void generateImageData()
            {
                // the IndexedImage constructor rejects dimensions which aren't a multiple of 8
                int cellHeight = roundUpToMultipleOfEight(maxY - minY);
                int cellWidth = roundUpToMultipleOfEight(maxX - minX);
                cellImage = new IndexedImage(cellHeight, cellWidth, image.getBitDepth(), image.getPalette());

                int startX;
                int startY;
                oamImages = getImages();

                for (int i = 0; i < oamImages.length; i++)
                {
                    Cell.OAM oam = oams[i];
                    startX = oam.xCoord - minX;
                    startY = oam.yCoord - minY;

                    for (int row = 0; row < oamImages[i].getHeight(); row++)
                    {
                        for (int col = 0; col < oamImages[i].getWidth(); col++)
                        {
                            cellImage.setPixelValue(startX + col, startY + row, oamImages[i].getPixelValue(col, row));
                        }
                    }
                }

                update = false;
            }

            public void save()
            {
                int startX;
                int startY;

                for (int i = 0; i < oamImages.length; i++)
                {
                    Cell.OAM oam = oams[i];
                    startX = oam.xCoord - minX;
                    startY = oam.yCoord - minY;

                    for (int row = 0; row < oamImages[i].getHeight(); row++)
                    {
                        for (int col = 0; col < oamImages[i].getWidth(); col++)
                        {
                            oamImages[i].setPixelValue(col, row,
                                    cellImage.getPixelValue(startX + col, startY + row));
                        }
                    }
                    oamImages[i].save();
                }
            }

            public BufferedImage getImage()
            {
                if (update)
                {
                    generateImageData();
                }
                return cellImage.getImage();
            }

            public BufferedImage getTransparentImage()
            {
                if (update)
                {
                    generateImageData();
                }
                return cellImage.getTransparentImage();
            }

            public int[][] getPixels()
            {
                return cellImage.getPixels();
            }

            public void setPixels(int[][] pixels)
            {
                cellImage.setPixels(pixels);
                update = true;
            }

            public int getPixelValue(int x, int y)
            {
                return cellImage.getPixelValue(x, y);
            }

            public void setPixelValue(int x, int y, int colorIdx)
            {
                cellImage.setPixelValue(x, y, colorIdx);
                update = true;
            }

            public int getHeight()
            {
                return cellImage.getHeight();
            }

            public int getWidth()
            {
                return cellImage.getWidth();
            }
        }
    }

    //format is (width, height)
    protected static final int[][][] oamSize = new int[][][] {
            { // square
                    {8, 8}, {16, 16}, {32, 32}, {64, 64}
            },
            { // horizontal
                    {16, 8}, {32, 8}, {32, 16}, {64, 32}
            },
            { // vertical
                    {8, 16}, {8, 32}, {16, 32}, {32, 64}
            }
    };

    public static int[] getOamSize(Cell.OAM oam)
    {
        return oamSize[oam.shape][oam.size];
    }
}
