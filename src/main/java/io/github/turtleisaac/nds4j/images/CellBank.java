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

import io.github.turtleisaac.nds4j.framework.Endianness;
import io.github.turtleisaac.nds4j.framework.GenericNtrFile;
import io.github.turtleisaac.nds4j.framework.MemBuf;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * An object representation of an NCER file
 */
public class CellBank extends GenericNtrFile
{
    /**
     * An individual "Cell", or "Bank" within an NCER.
     * In theory, this represents one assembled image.
     */
    public static class Cell {
        static class CellAttribute {
            boolean hFlip;
            boolean vFlip;
            boolean hvFlip;
            boolean boundingRectangle;
            int boundingSphereRadius;
        }

        /**
         * An individual OAM within an NCER (CellBank).
         * This represents the sub-images that make up a Cell/Bank, or more accurately,
         * the data used to generate them from an NCGR (IndexedImage).
         */
        public static class OAM {
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


            public CellImage getImage(IndexedImage parent, int mappingType, int[] index, int i, int partitionDataOffset)
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

                int tileOffset = this.tileOffset;
                tileOffset = (tileOffset << (byte) mappingType);

                int num_pal = palette;
                if (num_pal >= parent.getPalette().getNumColors())
                    num_pal = 0;
//                Arrays.fill(cell_img.tilePal, num_pal);

                return new CellImage(parent, getOamSize(this)[0], getOamSize(this)[1], tileOffset, partitionDataOffset);
            }

            public static int[] getOamSize(OAM oam)
            {
                return oamSize[oam.shape][oam.size];
            }

            // format is (width, height)
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
        }

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

        public CellImage[] getImages(IndexedImage parent, int mappingType, int partitionOffset)
        {
            int[] index = null;

            CellImage[] images = new CellImage[oams.length];

            for (int i = 0; i < oams.length; i++)
            {
                OAM oam = oams[i];

                if (oam == null)
                    break;

                images[i] = oam.getImage(parent, mappingType, index, i, partitionOffset);
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
    }

    private int bankType;
    private int mappingType;
    private boolean vramTransfer;
    private boolean tacu;

    private Cell[] cells;
    private IndexedImage image;

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
        long bankDataOffset = reader.readUInt32(); // 0x1C

        mappingType = (int) (reader.readUInt32() & 0xFF); // 0x20

        int partitionDataOffset = reader.readInt(); // 0x24
        reader.skip(4);
        int tacuOffset = reader.readInt();

        vramTransfer = partitionDataOffset != 0;
        tacu = tacuOffset != 0;

        int storedPos = reader.getPosition();

        int maxPartitionSize = 0;
        int firstPartitionDataOffset = 0;
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

            cells[i].maxX = reader.readShort();
            cells[i].maxY = reader.readShort();
            cells[i].minX = reader.readShort();
            cells[i].minY = reader.readShort();

            storedPos = reader.getPosition();

            if (bankType == 0)
                reader.setPosition(reader.getPosition() + (numBanks - (i+1)) * 8 + cellOffset);
            else
                reader.setPosition(reader.getPosition() + (numBanks - (i+1)) * 0x10 + cellOffset);

            // read OAMs
            for (int x = 0; x < cellCount; x++)
            {
                cells[i].oams[x].yCoord = reader.readByte(); //bits 0-7
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

        if (!labelEnabled)
            return;

        reader.setPosition(NTR_HEADER_SIZE + cellBankSectionSize);

        //label data
        String labelMagic = reader.readString(4); // 0x10

        if (!labelMagic.equals("LBAL")) {
            throw new RuntimeException("Not a valid RECN file.");
        }

        long[] stringOffsets = new long[numBanks + 1];
        int labelSectionSize = reader.readInt();
        stringOffsets[stringOffsets.length - 1] = labelSectionSize - 8 - (4L *numBanks);
        for (int i = 0; i < numBanks; i++)
        {
            long offset = reader.readUInt32();
            if (offset >= labelSectionSize - 8)
            {
                reader.setPosition(reader.getPosition() - 4);
                offset = -1;
            }
            stringOffsets[i] = offset;
        }

        for (int i = 0; i < stringOffsets.length - 1; i++)
        {
            if (stringOffsets[i] != -1)
            {
                cells[i].name = reader.readString((int) (stringOffsets[i+1] - stringOffsets[i])).trim();
            }
        }

        //uext data
        String uextMagic = reader.readString(4); // (note: this isn't guaranteed to be 4-byte aligned)

        if (!uextMagic.equals("TXEU")) {
            throw new RuntimeException("Not a valid RECN file.");
        }

        int uextSectionSize = reader.readInt();
        int uextUnknown = reader.readInt();
    }

    public byte[] save()
    {
        MemBuf dataBuf = MemBuf.create();
        MemBuf.MemBufWriter writer = dataBuf.writer();

        writer.skip(NTR_HEADER_SIZE);
        writer.write(NcerUtils.kbecHeader);
        int storedPos = writer.getPosition();

        writer.setPosition(NTR_HEADER_SIZE + 8);
//        writer.writeUInt32(cellBankSectionSize); // 0x14
        writer.writeShort((short) cells.length); // 0x18
        writer.writeShort((short) bankType); // 0x1A
        writer.skip(4); // bankData offset goes here - todo
        writer.writeInt(mappingType); // 0x20

        writer.setPosition(storedPos);
        MemBuf bankBuf = MemBuf.create();
        MemBuf.MemBufWriter bankWriter = bankBuf.writer();

        int oamCount = 0;
        // write banks
        for (Cell cell : cells)
        {
            NcerUtils.writeCell(bankWriter, cell, oamCount);
            oamCount += cell.oams.length;
        }

        // write OAMs
        for (Cell cell : cells)
        {
            NcerUtils.writeOams(bankWriter, cell);
        }

        int partitionDataOffset = writer.getPosition() + bankWriter.getPosition();

        if (vramTransfer)
        {
            bankWriter.writeInt(0xC80); // maxPartitionSize
            bankWriter.writeInt(8); // first partitionData entry offset (relative to partitionDataOffset)

            // write partition data
            for (Cell cell : cells)
            {
                bankWriter.writeInt(cell.partitionOffset);
                bankWriter.writeInt(cell.partitionSize);
            }
        }

        writer.write(bankBuf.reader().getBuffer());

        if (vramTransfer)
        {
            storedPos = writer.getPosition();
            writer.setPosition(NTR_HEADER_SIZE + 0x14);
            writer.writeInt(partitionDataOffset - 8 - NTR_HEADER_SIZE); // writes where the partition data (vram transfer) data section starts
            writer.setPosition(storedPos);
        }

        if (tacu)
        {
            storedPos = writer.getPosition();
            writer.setPosition(NTR_HEADER_SIZE + 0x1C);
            writer.writeInt(storedPos - 8 - NTR_HEADER_SIZE); // writes where the TACU section starts
            writer.setPosition(storedPos);
        }

        if (tacu)
        {
            writer.writeString("TACU");
            int tacuSize = 8 + (4 * cells.length);
            tacuSize += 16 - (tacuSize % 16);
            writer.writeInt(tacuSize + 8);
            writer.writeShort((short) cells.length); //todo verify this?
            writer.writeShort((short) 1); //todo verify this?
            writer.writeInt(8); // pointer to attributes data (relative to start of TACU + 4)

            for (Cell cell : cells) {
                writer.writeInt(cell.tacuData);
            }

            writer.skip(tacuSize + 8 - (writer.getPosition() - storedPos)); // this should be the end of TACU
        }

        int bankSectionEnd = writer.getPosition();
        writer.setPosition(NTR_HEADER_SIZE + 4); // writes the length of the bank section
        writer.writeInt(bankSectionEnd - NTR_HEADER_SIZE);
        writer.setPosition(bankSectionEnd);

        if (numBlocks > 1)
        {
            // write label section

            MemBuf labelBuf = MemBuf.create();
            MemBuf.MemBufWriter labelWriter = labelBuf.writer();

            labelWriter.writeString("LBAL");
            NcerUtils.writeLabelSection(labelWriter, cells);
            writer.write(labelBuf.reader().getBuffer());

            // write UEXT section

            writer.writeString("TXEU");
            writer.writeInt(12); // seems to always be the size?
            writer.writeInt(0);
        }

        storedPos = writer.getPosition();
        writer.setPosition(0); //total file size

        writeGenericNtrHeader(writer, storedPos, numBlocks);

        writer.setPosition(storedPos);

        return dataBuf.reader().getBuffer();
    }

    private static class NcerUtils {
        private static final byte[] kbecHeader =
                {
                        0x4B, 0x42, 0x45, 0x43, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x18, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
                };

        private static final int oamSize = 6;

        private static void writeCell(MemBuf.MemBufWriter writer, Cell cell, int oamCount)
        {
            writer.writeShort((short) cell.oams.length);
            writer.writeShort(writeCellAttributes(cell));
            writer.writeInt(oamCount * oamSize);
            writer.writeShort(cell.maxX);
            writer.writeShort(cell.maxY);
            writer.writeShort(cell.minX);
            writer.writeShort(cell.minY);
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

        private static void writeLabelSection(MemBuf.MemBufWriter writer, Cell[] cells)
        {
            int stringStartOffset = 8 + (4 * cells.length);
            writer.setPosition(stringStartOffset);

            long[] offsets = new long[cells.length];
            for (int i = 0; i < cells.length; i++)
            {
                offsets[i] = writer.getPosition() - stringStartOffset;
                writer.writeString(cells[i].name + "\0");
            }

            int labelEnd = writer.getPosition();

            writer.setPosition(8); // start of offsets section
            for (int i = 0; i < cells.length; i++)
            {
                writer.writeUInt32(offsets[i]);
            }

            writer.setPosition(4); // section size offset
            writer.writeInt(labelEnd);
            writer.setPosition(labelEnd);
        }
    }

    public void setImage(IndexedImage image)
    {
        this.image = image;
    }

    public CellImage[] getCellImages(int i)
    {
        Cell cell = cells[i];
        return cell.getImages(image, mappingType, cell.partitionOffset);
    }

    public BufferedImage getNcerImage(int i)
    {
        Cell cell = cells[i];

        //todo undo this being 80
//        BufferedImage output = new BufferedImage(cell.maxX - cell.minX, cell.maxY - cell.minY, BufferedImage.TYPE_INT_RGB);
        BufferedImage output = new BufferedImage(80, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = (Graphics2D) output.getGraphics();

        CellImage[] images = cell.getImages(image, mappingType, cell.partitionOffset);
        for (int x = 0; x < images.length; x++)
        {
            Cell.OAM oam = cell.oams[x];

            g.drawImage(images[x].getImage(), oam.xCoord + output.getWidth() / 2, oam.yCoord + output.getHeight() / 2, null);
        }

//        for (int x = 0; x < images.length; x++)
//        {
//            Cell.OAM oam = cell.oams[x];
//
//            int xOrigin = oam.xCoord + output.getWidth() / 2;
//            int yOrigin = oam.yCoord + output.getHeight() / 2;
//
//            g.setColor(Color.black);
//            g.drawLine(xOrigin, yOrigin, xOrigin, yOrigin + images[x].getHeight());
//            g.drawLine(xOrigin, yOrigin, xOrigin + images[x].getImage().getWidth(), yOrigin);
//
//            g.drawLine(xOrigin + images[x].getImage().getWidth(), yOrigin, xOrigin + images[x].getImage().getWidth(),yOrigin + images[x].getHeight());
//            g.drawLine(xOrigin, yOrigin + images[x].getHeight(), xOrigin + images[x].getImage().getWidth(), yOrigin + images[x].getHeight());
//        }
        g.dispose();

        return output;
    }


    /**
     * This is the image constructed by the data contained in an OAM, the Cell it is inside of,
     * and its parent NCGR (IndexedImage).
     * <p> NOTE: This is basically a shadow of an <code>IndexedImage</code>. It lacks much of the original functionality of
     * the <code>IndexedImage</code> class, as it isn't really its own image, but a subsection of a specific <code>IndexedImage</code>.
     */
    public static class CellImage extends IndexedImage
    {
        private IndexedImage parent;
        /**
         * A <code>byte[]</code> representing a 4bpp or 8bpp stream of the parent NCGR (IndexedImage) data.
         */
        private byte[] imageData;
        int startByte;

        public CellImage(IndexedImage image, int width, int height, int tileOffset, int partitionOffset)
        {
            super(height, width, image.getBitDepth(), image.getPalette());
            int metatilesWide = (image.getWidth() / 8) / image.getMetatileWidth();
            imageData = IndexedImage.NcgrUtils.convertToTiles4Bpp(image, image.getNumTiles(), metatilesWide, image.getMetatileWidth(), image.getMetatileHeight());
            startByte = tileOffset * (image.getBitDepth() * 8) + partitionOffset;
            NcgrUtils.convertFromTiles4BppAlternate(imageData, this, startByte);
        }



        @Override
        public String toString()
        {
            return String.format("%dx%d shadow with start byte %d of %s", super.getHeight(), super.getHeight(), startByte, super.toString());
        }

        // The following methods are all not supported by this subclass

        @Override
        public BufferedImage getResizedImage()
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public BufferedImage getResizedImage(int newWidth, int newHeight)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public IndexedImage indexSelf(JPanel parent)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public IndexedImage updateColor(int index, Color replacement)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public IndexedImage replaceColor(Color toReplace, Color replacement)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public IndexedImage replacePalette(Color[] paletteGuide, Color[] newPalette, JPanel parent)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public IndexedImage alignPalette(Color[] paletteGuide, JPanel parent)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public IndexedImage createCopyWithPalette(IndexedImage image)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public IndexedImage copyOfSelf()
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public IndexedImage createCopyWithImage(Color[] palette)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public Palette getPalette()
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public void setPalette(Palette palette)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public int getEncryptionKey()
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public void setEncryptionKey(int encryptionKey)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public void readGenericNtrHeader(MemBuf.MemBufReader reader)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public void writeGenericNtrHeader(MemBuf.MemBufWriter writer, long length, int numSections)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        protected void copyValuesFromTemp(GenericNtrFile file)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public Endianness.EndiannessType getEndiannessOfBeginning()
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public void setEndiannessOfBeginning(Endianness.EndiannessType endiannessOfBeginning)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public int getBom()
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public void setBom(int bom)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public int getVersion()
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public void setVersion(int version)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public long getFileSize()
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public void setFileSize(long fileSize)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public int getHeaderSize()
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public void setHeaderSize(int headerSize)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public int getNumBlocks()
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public void setNumBlocks(int numBlocks)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public int getBitDepth()
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public void setBitDepth(int bitDepth)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public NcgrUtils.ScanMode getScanMode()
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public void setScanMode(NcgrUtils.ScanMode scanMode)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public int getMetatileWidth()
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public void setMetatileWidth(int metatileWidth)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public int getMetatileHeight()
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public void setMetatileHeight(int metatileHeight)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public int getNumTiles()
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public void setNumTiles(int numTiles)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public int getMappingType()
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public void setMappingType(int mappingType)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public boolean isVram()
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public void setVram(boolean vram)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public boolean hasSopc()
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }

        @Override
        public void setSopc(boolean sopc)
        {
            throw new UnsupportedOperationException("This operation is not supported for CellImage objects, as they are merely a shadow of an existing IndexedImage.");
        }
    }
}
