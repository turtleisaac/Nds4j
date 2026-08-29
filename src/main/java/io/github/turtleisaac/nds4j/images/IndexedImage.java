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

import io.github.turtleisaac.nds4j.framework.BinaryWriter;
import io.github.turtleisaac.nds4j.framework.Buffer;
import io.github.turtleisaac.nds4j.framework.GenericNtrFile;
import io.github.turtleisaac.nds4j.framework.MemBuf;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import static io.github.turtleisaac.nds4j.framework.Endianness.swapEndianness;

/**
 * An object representation of an NCGR file. <p>
 * An NCGR file is a Nintendo proprietary DS format used for storing graphics (images).
 */
public class IndexedImage extends GenericNtrFile
{
    private int[][] pixels;
    private Palette palette;
    /**
     * Based on how an NCER or NSCR is set to read an NCGR file, or how the game is programmed to read an NCGR file,
     * it may use a specific palette index within the NCLR (palette) file. <p>
     * For example, elements of the opening sequence
     * in Pokémon HeartGold share a single NCLR file with multiple 16 color palettes stored consecutively inside it.
     * The tiles within the NSCR used to display them contain the palette index information.
     */
    private int paletteIdx = 0;

    //todo evaluate whether these really need to be final
    private final int height;
    private final int width;

    private int bitDepth;
    // Defaults to NOT_SCANNED so that an image built in memory (rather than parsed from an
    // NCGR) serializes down the plain tiled path. Leaving this null made save() take the
    // scanned branch, where neither direction matched and the pixel buffer came out empty.
    private NcgrUtils.ScanMode scanMode = NcgrUtils.ScanMode.NOT_SCANNED;
    private int colsPerChunk = 1;
    private int rowsPerChunk = 1;
    private int numTiles;
    /**
     * The tile mapping type, in the normalised form the reader produces: 32, 64, 128 or 256.
     * <p>
     * Defaults to 32 because that is what a file carrying 0 in the header means - the reader
     * maps a raw 0 to 32. Leaving the field at 0 for an image built in memory made save() take
     * its "not 32" branch and write 0xFFFFFFFF over the tile grid, while still writing 0 at
     * 0x22 for the reader to normalise back to 32. The file then declared a grid it did not
     * contain: a 160x80 image saved and reparsed as 8x1600.
     */
    private int mappingType = 32;
    private boolean vram;
    private int encryptionKey = -1;

    // Raw NCGR character-header fields that save() otherwise recomputes. Preserved so an unedited image
    // round-trips byte-for-byte: some NCGRs store 0xFFFF "unspecified" tile width/height, which the
    // reader replaces with a computed layout. Only restored when the image has not been resized (its
    // pixel dimensions still match what was read); an edited/resized image writes fresh values. Absent
    // for images not read from a file.
    //
    // srcUnspecifiedSizeFlag is the u16 at char-header offset 0x20: across all five retail ROMs it is
    // exactly 0x10 when the tile width/height are the 0xFFFF "unspecified" sentinel and 0x0 otherwise,
    // i.e. it marks a character set with no explicit size (dimensions supplied by the consumer).
    private boolean hasSourceHeader = false;
    private int srcCharHeightField, srcCharWidthField, srcUnspecifiedSizeFlag, srcWidthPx, srcHeightPx;

    private boolean sopc;

    private BufferedImage storedImage;
    boolean update;

    /**
     * Parses an NCGR file and returns an <code>IndexedImage</code> representation of it
     * @param data a <code>byte[]</code> containing a binary representation of an NCGR file
     * @param tilesWidth an <code>int</code> containing a tile width value to enforce (use <code>0</code> if you don't have one)
     * @param bitDepth an <code>int</code> containing a bit-depth value to enforce (use <code>0</code> if you don't have one)
     * @param colsPerChunk an <code>int</code> containing the number of tiles per row (columns per row) for each chunk. (use <code>0</code> if you don't have one)
     * @param rowsPerChunk an <code>int</code> containing the number of rows per chunk. (use <code>0</code> if you don't have one)
     * @param scanFrontToBack a <code>boolean</code> representing whether (only if this image is scanned) this image should be scanned <b>front-to-back</b> or <b>back-to-front</b>
     */
    public IndexedImage(byte[] data, int tilesWidth, int bitDepth, int colsPerChunk, int rowsPerChunk, boolean scanFrontToBack)
    {
        super("RGCN");

        // the javadoc documents 0 as "I don't have one" - treat it as the 1-tile-per-chunk default
        if (colsPerChunk == 0)
            colsPerChunk = 1;
        if (rowsPerChunk == 0)
            rowsPerChunk = 1;

        MemBuf dataBuf = MemBuf.create(data);
        MemBuf.MemBufReader reader = dataBuf.reader();
        int fileSize = dataBuf.writer().getPosition();

        readGenericNtrHeader(reader);

        this.sopc = this.numBlocks == 2;

        // reader position is now 0x10

        //character data
        String charMagic = reader.readString(4);

        if (!charMagic.equals("RAHC")) {
            throw new RuntimeException("Not a valid NCGR file.");
        }

        long charSectionSize = reader.readUInt32();

        int tilesHeight = reader.readShort(); //0x18
        this.srcCharHeightField = tilesHeight & 0xFFFF; // captured raw so save() can round-trip it exactly

        int rawWidthField = reader.readShort(); //0x1A - always read, so the raw field can be captured
        this.srcCharWidthField = rawWidthField & 0xFFFF;
        if (tilesWidth == 0)
        {
            tilesWidth = rawWidthField;
            if (tilesWidth < 0)
                tilesWidth = 1;
        }

        //0x1C
        if (bitDepth == 0)
        {
            bitDepth = reader.readInt() == 3 ? 4 : 8;  //4bpp if == 3, 8bpp if == 4
        }
        else
        {
            reader.skip(4);
        }

        this.bitDepth = bitDepth;

        int numColors = 256;
        if (bitDepth == 4)
        {
            numColors = 16;
        }
        this.srcUnspecifiedSizeFlag = reader.readUInt16(); // 0x20 - captured raw so save() can round-trip it exactly

        this.mappingType = reader.readUInt16(); // 0x22

        switch(mappingType) {
            case 0:
                mappingType = 32;
                break;
            case 0x10:
                mappingType = 64;
                break;
            case 0x20:
                mappingType = 128;
                break;
            case 0x30:
                mappingType = 256;
                break;
            default:
                throw new RuntimeException(String.format("Invalid mapping type %d", mappingType));
        }

        boolean scanned = reader.readByte() == 1; // 0x24
        this.vram = reader.readByte() == 1; // 0x25
        reader.skip(2);

        this.scanMode = NcgrUtils.ScanMode.getMode(scanned, scanFrontToBack);

        int tileSize = bitDepth * 8;

        this.numTiles = reader.readInt() / (64 / (8 / bitDepth)); // 0x28

        if (tilesHeight < 0)
            tilesHeight = (numTiles + tilesWidth - 1) / tilesWidth;

        if (tilesWidth % colsPerChunk != 0)
            throw new RuntimeException(String.format("The width in tiles (%d) isn't a multiple of the specified columns per chunk (%d)", tilesWidth, colsPerChunk));

        if (tilesHeight % rowsPerChunk != 0)
            throw new RuntimeException(String.format("The height in tiles (%d) isn't a multiple of the specified rows per chunk (%d)", tilesHeight, rowsPerChunk));

        this.height = tilesHeight * 8;
        this.width = tilesWidth * 8;
        this.pixels = new int[this.height][this.width];
        this.palette = Palette.defaultPalette.copyOf();
        this.rowsPerChunk = rowsPerChunk;
        this.colsPerChunk = colsPerChunk;

        reader.setPosition(0x30);
        byte[] imageData = reader.getBuffer();

        if (scanned) // scanned images
        {
            switch (bitDepth)
            {
                case 4:
                    this.encryptionKey = NcgrUtils.convertFromScanned4Bpp(imageData, this, scanFrontToBack);
                    break;
                case 8:
                    this.encryptionKey = NcgrUtils.convertFromScanned8Bpp(imageData, this, scanFrontToBack);
                    break;
            }
        }
        else // tiled images
        {
            switch (bitDepth)
            {
                case 4:
                    NcgrUtils.convertFromTiles4Bpp(imageData, this, 0);
                    break;
                case 8:
                    NcgrUtils.convertFromTiles8Bpp(imageData, this, 0);
                    break;
            }
        }

        this.srcWidthPx = this.width;
        this.srcHeightPx = this.height;
        this.hasSourceHeader = true;

        this.update = true;
    }

    /**
     * Generates an object representation of an NCER file from a file on disk
     * @param file a <code>File</code> containing the path to a NCGR file on disk
     * @return an <code>IndexedImage</code> object
     */
    public static IndexedImage fromFile(File file, int tilesWidth, int bitDepth, int colsPerChunk, int rowsPerChunk, boolean scanFrontToBack)
    {
        return fromFile(file.getAbsolutePath(), tilesWidth, bitDepth, colsPerChunk, rowsPerChunk, scanFrontToBack);
    }

    /**
     * Generates an object representation of an NCER file from a file on disk
     * @param file a <code>String</code> containing the path to a NCGR file on disk
     * @return an <code>IndexedImage</code> object
     */
    public static IndexedImage fromFile(String file, int tilesWidth, int bitDepth, int colsPerChunk, int rowsPerChunk, boolean scanFrontToBack)
    {
        return new IndexedImage(Buffer.readFile(file), tilesWidth, bitDepth, colsPerChunk, rowsPerChunk, scanFrontToBack);
    }

//    /**
//     * Creates an <code>IndexedImage</code> of size 80x80
//     * (this image will default to all pixels with a value of 0)
//     * @param palette a <code>Palette</code> containing the palette of the image
//     */
//    public IndexedImage(Palette palette)
//    {
//        super("RGCN");
//        height = 80;
//        width = 80;
//
//        pixels = new int[height][width];
//        this.palette = palette;
//
//        update = true;
//    }

    /**
     * Creates an <code>IndexedImage</code> with the provided height, width, bit-depth, and palette
     * @param height an <code>int</code> which is a multiple of 8
     * @param width an <code>int</code> which is a multiple of 8
     * @param bitDepth an <code>int</code> with a value of 4 or 8 (defaults to 4 if another value is provided)
     * @param palette a <code>Palette</code> object
     */
    public IndexedImage(int height, int width, int bitDepth, Palette palette)
    {
        super("RGCN");

        // Guard the degenerate arguments first: without these, a negative dimension surfaces as
        // a bare NegativeArraySizeException from the pixel allocation, and 0 slips through the
        // multiple-of-8 test to produce an image with no pixels at all.
        if (height <= 0)
            throw new RuntimeException(String.format("%d was provided for image height, but it must be positive.", height));

        if (width <= 0)
            throw new RuntimeException(String.format("%d was provided for image width, but it must be positive.", width));

        if (palette == null)
            throw new RuntimeException("An IndexedImage requires a palette, but null was provided.");

        if (height % 8 != 0)
            throw new RuntimeException(String.format("%d was provided for image height, but a multiple of 8 is required.", height));

        if (width % 8 != 0)
            throw new RuntimeException(String.format("%d was provided for image width, but a multiple of 8 is required.", width));

        this.height = height;
        this.width = width;

        if (bitDepth != 4 && bitDepth != 8)
            bitDepth = 4;
        this.bitDepth = bitDepth;

        numTiles = (height / 8) * (width / 8);

        pixels = new int[height][width];
        this.palette = palette;

        update = true;
    }

    /**
     * Creates an <code>IndexedImage</code> using a predetermined assignment of color to pixel and the palette itself
     * @param pixels a <code>int[][]</code> representing the index in the palette to pull the color from for each pixel in the sprite
     * @param palette a <code>Palette</code> containing the colors to be used in the image
     * @throws ImageException if the provided int[][] does not contain rows of equal width
     */
    @Deprecated
    public IndexedImage(int[][] pixels, Palette palette) throws ImageException
    {
        super("RGCN");
        height = pixels.length;
        if (allRowsHaveSameWidth(pixels))
            width = pixels[0].length;
        else
            throw new ImageException("The provided int[][] does not contain rows of equal width");
        this.pixels = Arrays.copyOf(pixels, pixels.length);
        this.palette = palette.copyOf();

        update = true;
    }

    private boolean allRowsHaveSameWidth(int[][] pixels)
    {
        if (pixels.length == 0)
            return false;
        if (pixels.length == 1)
            return true;

        int width = pixels[0].length;
        for (int i = 1; i < pixels.length; i++)
        {
            if (pixels[i].length != width)
                return false;
        }

        return true;
    }

    /**
     * Attempts to create an <code>IndexedImage</code> from a provided <code>Image</code> without any indexing
     * @param image an <code>Image</code> to attempt to convert
     * @param parent
     */
    @Deprecated
    public IndexedImage(Image image, JPanel parent)
    {
        super("RGCN");
        if (image.getWidth(null) != image.getHeight(null) && image.getWidth(null) != 80)
        {
            throw new RuntimeException("This image is not 80x80");
//            JOptionPane.showMessageDialog(parent,"This image is not 80x80","PokEditor",JOptionPane.ERROR_MESSAGE);
//            return;
        }

        BufferedImage bufferedImage;
        if (image instanceof BufferedImage)
        {
            bufferedImage = (BufferedImage) image;
        }
        else
        {
            bufferedImage = new BufferedImage(image.getWidth(null),image.getHeight(null),BufferedImage.TYPE_INT_RGB);
            Graphics2D bGr = bufferedImage.createGraphics();
            bGr.drawImage(image, 0, 0, null);
            bGr.dispose();
        }

        ArrayList<Color> colorList = new ArrayList<>();
        for (int row = 0; row < bufferedImage.getHeight(); row++)
        {
            for (int col = 0; col < bufferedImage.getWidth(); col++)
            {
                Color color = new Color(bufferedImage.getRGB(col,row));
                if (!colorList.contains(color))
                    colorList.add(color);
            }
        }

        if (colorList.size() > 16)
        {
            throw new RuntimeException("This image is not indexed to 16 colors");
//            JOptionPane.showMessageDialog(parent,"This image is not indexed to 16 colors","PokEditor",JOptionPane.ERROR_MESSAGE);
//            return;
        }
        else
        {
            while (colorList.size() < 16)
            {
                colorList.add(Color.MAGENTA);
            }
        }

        height = 80;
        width = 80;
        pixels = new int[height][width];

        for (int row = 0; row < height; row++)
        {
            for (int col = 0; col < width; col++)
            {
                pixels[row][col] = colorList.indexOf(new Color(bufferedImage.getRGB(col,row)));
            }
        }

        //todo fix and uncomment
//        palette = colorList.toArray(new Color[0]);
        update = true;
    }

    /**
     * Generate a <code>byte[]</code> representation of this <code>IndexedImage</code> as an NCGR
     * @return a <code>byte[]</code>
     */
    public byte[] save()
    {
        // An image whose bit depth was never set has no pixel format, and the writer would emit
        // a header with no character data at all - a 48 byte file that parses cleanly as an
        // empty image, losing every pixel without a word. Two deprecated constructors leave it
        // unset. Refuse rather than produce something that looks valid and holds nothing.
        if (bitDepth != 4 && bitDepth != 8)
        {
            throw new RuntimeException("This image has no bit depth set (" + bitDepth + "), so"
                    + " there is no pixel format to write it in. It was most likely built with one"
                    + " of the deprecated constructors, which never set one.");
        }

        int tileSize = bitDepth * 8;

        if (width % 8 != 0)
            throw new RuntimeException(String.format("The width in pixels (%d) isn't a multiple of 8.", width));

        if (height % 8 != 0)
            throw new RuntimeException(String.format("The height in pixels (%d) isn't a multiple of 8.", height));

        int tilesWidth = width / 8;
        int tilesHeight = height / 8;

        if (tilesWidth % colsPerChunk != 0)
            throw new RuntimeException(String.format("The width in tiles (%d) isn't a multiple of the specified columns per chunk (%d)", tilesWidth, colsPerChunk));

        if (tilesHeight % rowsPerChunk != 0)
            throw new RuntimeException(String.format("The height in tiles (%d) isn't a multiple of the specified rows per chunk (%d)", tilesHeight, rowsPerChunk));

        int maxNumTiles = tilesWidth * tilesHeight;
        int numTiles = this.numTiles;

        if (numTiles == 0)
        {
            numTiles = maxNumTiles;
            this.numTiles = numTiles;
        }
        else if (numTiles > maxNumTiles)
            throw new RuntimeException(String.format("The specified number of tiles (%d) is greater than the maximum possible value (%d).", numTiles, maxNumTiles));

        int bufferSize = numTiles * tileSize;
        MemBuf pixelsBuf = MemBuf.create();
        MemBuf.MemBufWriter writer = pixelsBuf.writer();

        if (scanMode != NcgrUtils.ScanMode.NOT_SCANNED)
        {
            switch (bitDepth)
            {
                case 4:
                    writer.write(NcgrUtils.convertToScanned4Bpp(this, bufferSize));
                    break;
                case 8:
                    throw new RuntimeException("8bpp not supported yet.");
//                    writer.write(NcgrUtils.convertToScanned8Bpp(this, bufferSize));
//                    break;
            }
        }
        else
        {
            switch (bitDepth)
            {
                case 4:
                    writer.write(NcgrUtils.convertToTiles4Bpp(this));
                    break;
                case 8:
                    writer.write(NcgrUtils.convertToTiles8Bpp(this));
                    break;
            }
        }

        MemBuf dataBuf = MemBuf.create();
        writer = dataBuf.writer();

        writeGenericNtrHeader(writer, bufferSize + (sopc ? 0x40 : 0x30), sopc ? 2 : 1);

        writer.write(NcgrUtils.charHeader);

        writer.setPosition(NcgrUtils.charHeaderPos + 4);
        writer.writeInt(bufferSize + 0x20); // 0x14

        writer.setPosition(NcgrUtils.charHeaderPos + 8);

        if (mappingType == 32)
        {
            writer.writeShort((short) tilesHeight); // 0x18
            writer.writeShort((short) tilesWidth); // 0x1A
        }
        else // if mappingType > 0
        {
            writer.writeBytes(0xFF, 0xFF, 0xFF, 0xFF);
            writer.skip(4);
            writer.writeByte((byte) 0x10);
        }

        writer.setPosition(NcgrUtils.charHeaderPos + 12);
        writer.writeByte((byte) (bitDepth == 4 ? 3 : 4));

        writer.setPosition(NcgrUtils.charHeaderPos + 18);
        if (mappingType != 0) {
            short val = 0;
            switch (mappingType) {
                case 32:
                    break;
                case 64:
                    val = 0x10;
                    break;
                case 128:
                    val = 0x20;
                    break;
                case 256:
                    val = 0x30;
                    break;
                default:
                    throw new RuntimeException(String.format("Invalid mapping type %d", mappingType));
            }

            writer.writeShort(val); // 0x22
        }
        else
        {
            writer.skip(2);
        }

        writer.writeByte((byte) (scanMode != NcgrUtils.ScanMode.NOT_SCANNED ? 1 : 0)); // 0x24
        writer.writeByte((byte) (vram ? 1 : 0)); // 0x25
        writer.skip(2);

        writer.writeInt(bufferSize);
        writer.setPosition(NcgrUtils.pixelsPos);

        writer.write(pixelsBuf.reader().getBuffer());

        if (sopc)
        {
            MemBuf sopcBuf = MemBuf.create(NcgrUtils.sopcBuffer);
            MemBuf.MemBufWriter sopcWriter = sopcBuf.writer();
            int endPos = sopcWriter.getPosition();

            sopcWriter.setPosition(12);
            sopcWriter.writeShort((short) tilesWidth);
            sopcWriter.writeShort((short) tilesHeight);
            sopcWriter.setPosition(endPos);

            writer.write(sopcBuf.reader().getBuffer());
        }

        // Restore the raw character-header fields for an unedited (same-size) image so it round-trips exactly.
        // For an image with real dimensions (e.g. battle sprites) these equal what was just written, so this is
        // a no-op; for party-icon NCGRs it puts back the 0xFFFF "unspecified" width/height and the 0x20 flag.
        if (hasSourceHeader && width == srcWidthPx && height == srcHeightPx)
        {
            int endPos = writer.getPosition(); // getBuffer() returns up to the writer position, so seek back after
            writer.setPosition(NcgrUtils.charHeaderPos + 8);
            writer.writeShort((short) srcCharHeightField); // 0x18
            writer.writeShort((short) srcCharWidthField);  // 0x1A
            writer.setPosition(NcgrUtils.charHeaderPos + 16);
            writer.writeShort((short) srcUnspecifiedSizeFlag);     // 0x20
            writer.setPosition(endPos);
        }

        return dataBuf.reader().getBuffer();
    }

    /**
     * Exports an NCGR file to disk from this <code>IndexedImage</code>
     * @param file a <code>File</code> containing the path to the target file on disk
     * @throws IOException if an I/O error occurs
     */
    public void saveToFile(File file) throws IOException
    {
        saveToFile(file.getAbsolutePath());
    }

    /**
     * Exports an NCGR file to disk from this <code>IndexedImage</code>
     * @param file a <code>String</code> containing the path to the target file on disk
     * @throws IOException if an I/O error occurs
     */
    public void saveToFile(String file) throws IOException
    {
        BinaryWriter.writeFile(file, save());
    }




    /**
     * Creates a <code>BufferedImage</code> using this <code>IndexedImage</code>
     * @return a <code>BufferedImage</code> representation of this <code>IndexedImage</code>
     */
    public BufferedImage getImage()
    {
        if (update)
        {
            storedImage = new BufferedImage(width, height,BufferedImage.TYPE_INT_RGB);
            for (int row = 0; row < height; row++)
            {
                for (int col = 0; col < width; col++)
                {
                    storedImage.setRGB(col,row,palette.getColor(getPixelValue(col, row), paletteIdx).getRGB());
                }
            }
            update = false;
        }

        return storedImage;
    }

    /**
     * Creates a <code>BufferedImage</code> with a transparent background using this <code>IndexedImage</code>
     * @return a <code>BufferedImage</code> representation of this <code>IndexedImage</code>
     */
    public BufferedImage getTransparentImage()
    {
        BufferedImage ret = new BufferedImage(width, height,BufferedImage.TYPE_INT_ARGB);
        for (int row = 0; row < height; row++)
        {
            for (int col = 0; col < width; col++)
            {
                if (pixels[row][col] != 0)
                    ret.setRGB(col,row,palette.getColor(pixels[row][col], paletteIdx).getRGB());
            }
        }

        return ret;
    }

    /**
     * Creates a <code>IndexedImage</code> based on the data in this <code>IndexedImage</code> given the specified coordinates and boundaries,
     * <p>Note: (0,0) is the top left corner of the image</p>
     * @param x an <code>int</code> containing the column value to start from
     * @param y an <code>int</code> containing the row value to start from
     * @param width an <code>int</code> containing the number of columns the resulting image will contain
     * @param height an <code>int</code> containing the number of rows the resulting image will contain
     * @return a <code>IndexedImage</code> containing a subspace of the original
     * @throws ImageException if the specified coordinates, width, or height are invalid
     */
    public IndexedImage getSubImage(int x, int y, int width, int height) throws ImageException
    {
        if (x < 0)
            throw new ImageException(String.format("Invalid x-coordinate provided for creating sub-image: %d < 0", x));
        if (x >= getWidth())
            throw new ImageException(String.format("Invalid x-coordinate provided for creating sub-image: %d >= %d (image width)", x, getWidth()));
        if (y < 0)
            throw new ImageException(String.format("Invalid y-coordinate provided for creating sub-image: %d < 0", y));
        if (y >= getHeight())
            throw new ImageException(String.format("Invalid y-coordinate provided for creating sub-image: %d >= %d (image height)", y, getHeight()));
        if (width > getWidth())
            throw new ImageException(String.format("Invalid width provided for creating sub-image: %d >= %d (image width)", width, getWidth()));
        if (height > getHeight())
            throw new ImageException(String.format("Invalid height provided for creating sub-image: %d >= %d (image height)", height, getHeight()));
        if (x + width > getWidth())
            throw new ImageException(String.format("Invalid width provided for creating sub-image from given x-coordinate: %d + %d >= %d (image width)", x, width, getWidth()));
        if (y + height > getHeight())
            throw new ImageException(String.format("Invalid height provided for creating sub-image from given y-coordinate: %d + %d >= %d (image height)", y, height, getHeight()));

        IndexedImage output = new IndexedImage(height, width, bitDepth, palette);
        for (int row = 0; row < height; row++)
        {
            for (int col = 0; col < width; col++)
            {
                output.setPixelValue(col, row, pixels[row + y][col + x]);
            }
        }

        output.paletteIdx = paletteIdx;
        output.scanMode = scanMode;
        output.colsPerChunk = colsPerChunk;
        output.rowsPerChunk = rowsPerChunk;
        output.numTiles = (height / 8) * (width / 8);
        output.mappingType = mappingType;
        output.vram = vram;
        output.encryptionKey = encryptionKey;
        output.sopc = sopc;

        return output;
    }

    //todo make it so this isn't restricted to 160x160

    /**
     * Creates a scaled <code>BufferedImage</code> using this <code>IndexedImage</code>
     * @return a scaled <code>BufferedImage</code> representation of this <code>IndexedImage</code>
     */
    @Deprecated
    public BufferedImage getResizedImage()
    {
        BufferedImage image = new BufferedImage(width, height,BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < height; row++)
        {
            for (int col = 0; col < width; col++)
            {
                image.setRGB(col,row,palette.getColor(pixels[row][col]).getRGB());
            }
        }

        BufferedImage resizedImage = new BufferedImage(width * 2, height * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics2D = resizedImage.createGraphics();
        graphics2D.drawImage(image, 0, 0, width * 2, height * 2, null);
        graphics2D.dispose();
        return resizedImage;
    }

    @Deprecated
    public BufferedImage getResizedImage(int newWidth, int newHeight)
    {
        BufferedImage image = new BufferedImage(width, height,BufferedImage.TYPE_INT_RGB);

        for (int row = 0; row < height; row++)
        {
            for (int col = 0; col < width; col++)
            {
                image.setRGB(col,row,palette.getColor(pixels[row][col]).getRGB());
            }
        }

        BufferedImage resizedImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics2D = resizedImage.createGraphics();
        graphics2D.drawImage(image, 0, 0, newWidth, newHeight, null);
        graphics2D.dispose();
        return resizedImage;
    }

    @Deprecated
    public IndexedImage indexSelf(JPanel parent)
    {
        IndexedImage newSprite = new IndexedImage(getImage(),parent);
        pixels = newSprite.getPixels();
        setPalette(newSprite.getPalette());
        return this;
    }

    /**
     * Replaces the <code>Color</code> at the specified index in the palette with the specified replacement <code>Color</code>
     * @param index an <code>int</code>
     * @param replacement a <code>Color</code>
     * @return this
     */
    @Deprecated
    public IndexedImage updateColor(int index, Color replacement)
    {
        palette.setColor(index, replacement);
        update = true;
        return this;
    }

    /**
     * Replaces all instances of the specified <code>Color</code> with the specified replacement <code>Color</code>
     * @param toReplace a <code>Color</code>
     * @param replacement a <code>Color</code>
     * @return this
     */
    @Deprecated
    public IndexedImage replaceColor(Color toReplace, Color replacement)
    {
        for (int i = 0; i < palette.size(); i++)
        {
            if (palette.getColor(i).equals(toReplace))
            {
                palette.setColor(i, replacement);
                break;
            }
        }
        return this;
    }

    @Deprecated
    public IndexedImage replacePalette(Color[] paletteGuide, Color[] newPalette, JPanel parent)
    {
        indexSelf(parent);

        ArrayList<Color> colorGuideList = new ArrayList<>(Arrays.asList(paletteGuide));

        for (int i = 0; i < palette.size(); i++)
        {
            for (int row = 0; row < height; row++)
            {
                for (int col = 0; col < width; col++)
                {
                    if (pixels[row][col] == i)
                        pixels[row][col] = -colorGuideList.indexOf(palette.getColor(i));
                }
            }
        }

        for (int row = 0; row < height; row++)
        {
            for (int col = 0; col < width; col++)
            {
                pixels[row][col] = Math.abs(pixels[row][col]);
            }
        }

        //todo uncomment and fix
//        palette = newPalette;
        update = true;
        return this;
    }

    @Deprecated
    public IndexedImage alignPalette(Color[] paletteGuide, JPanel parent)
    {
        indexSelf(parent);

        ArrayList<Color> colorGuideList= new ArrayList<>(Arrays.asList(paletteGuide));

        for (int i = 0; i < palette.size(); i++)
        {
            for (int row = 0; row < height; row++)
            {
                for (int col = 0; col < width; col++)
                {
                    if (pixels[row][col] == i)
                        pixels[row][col] = -colorGuideList.indexOf(palette.getColor(i));
                }
            }
        }

        for (int row = 0; row < height; row++)
        {
            for (int col = 0; col < width; col++)
            {
                pixels[row][col] = Math.abs(pixels[row][col]);
            }
        }

        update = true;
        return this;
    }

    // --- Headless PNG import ------------------------------------------------------------------------
    // Clean replacements for the deprecated IndexedImage(Image, JPanel) / indexSelf(JPanel) path, which
    // was a half-finished port from PokEditor: it hardcoded 80x80, capped at 16 exact-match colours,
    // padded with magenta, took a Swing JPanel it never used (only fed commented-out JOptionPane
    // dialogs), and left the palette assignment commented out ("//todo fix and uncomment"). These
    // operate on an existing image so its geometry (dimensions, bit depth, tiling) is preserved and the
    // result re-encodes cleanly with save(); they are headless (no Swing) and support any size + 4/8bpp.

    /**
     * Overwrite this image's pixels by matching every pixel of {@code src} to the nearest colour in
     * this image's current palette (squared-RGB distance). Fully transparent pixels (alpha &lt; 128)
     * map to palette index 0, the DS transparent slot. This image's dimensions, bit depth and tiling
     * are unchanged, so the result re-encodes with {@link #save()}.
     *
     * @param src an image whose dimensions equal this image's
     * @return the number of pixels whose nearest palette colour was not an exact match (a measure of
     *         how well the image fits the existing palette)
     * @throws RuntimeException if {@code src}'s dimensions differ, or this image has no palette
     */
    public int applyImageMatched(BufferedImage src)
    {
        requireSameSize(src);
        if (palette == null)
            throw new RuntimeException("This image has no palette to match against.");
        Color[] pal = palette.getColors();
        // A 4bpp NCGR indexes into a single 16-colour sub-palette; never emit an index past 15.
        int limit = (bitDepth == 4) ? Math.min(16, pal.length) : pal.length;
        if (limit < 1)
            throw new RuntimeException("The palette is empty.");
        int unmatched = 0;
        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                int argb = src.getRGB(x, y);
                if ((argb >>> 24) < 128) { pixels[y][x] = 0; continue; } // transparent -> index 0
                int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                int best = 0, bestDist = Integer.MAX_VALUE;
                for (int i = 0; i < limit; i++)
                {
                    Color c = pal[i];
                    int dr = c.getRed() - r, dg = c.getGreen() - g, db = c.getBlue() - b;
                    int d = dr * dr + dg * dg + db * db;
                    if (d < bestDist) { bestDist = d; best = i; }
                }
                if (bestDist != 0) unmatched++;
                pixels[y][x] = best;
            }
        }
        update = true;
        return unmatched;
    }

    /**
     * Rebuild this image's palette from {@code src} (median-cut to at most {@code maxColors} entries,
     * with index 0 reserved as the transparent slot) and re-index every pixel against the new palette.
     * Sets this image's palette and returns it. Headless.
     *
     * @param src an image whose dimensions equal this image's
     * @param maxColors the palette size to fill (16 for a 4bpp NCGR, 256 for 8bpp)
     * @return the newly built {@link Palette} (also now this image's palette)
     */
    public Palette applyImageQuantized(BufferedImage src, int maxColors)
    {
        requireSameSize(src);
        if (maxColors < 2) maxColors = 2;

        boolean[][] transparent = new boolean[height][width];
        java.util.List<int[]> opaque = new ArrayList<>();
        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                int argb = src.getRGB(x, y);
                if ((argb >>> 24) < 128) { transparent[y][x] = true; continue; }
                opaque.add(new int[]{ (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF });
            }
        }

        // Slot 0 is transparent; median-cut the opaque colours into the remaining slots.
        java.util.List<Color> boxed = medianCut(opaque, maxColors - 1);
        Color[] colors = new Color[maxColors];
        colors[0] = Color.MAGENTA; // rendered transparent when index-0 transparency is enabled
        for (int i = 1; i < maxColors; i++)
            colors[i] = (i - 1 < boxed.size()) ? boxed.get(i - 1) : Color.BLACK;
        Palette newPal = new Palette(colors);

        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                if (transparent[y][x]) { pixels[y][x] = 0; continue; }
                int argb = src.getRGB(x, y);
                int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                int best = 1, bestDist = Integer.MAX_VALUE;
                for (int i = 1; i < maxColors; i++)
                {
                    Color c = colors[i];
                    int dr = c.getRed() - r, dg = c.getGreen() - g, db = c.getBlue() - b;
                    int d = dr * dr + dg * dg + db * db;
                    if (d < bestDist) { bestDist = d; best = i; }
                }
                pixels[y][x] = best;
            }
        }

        setPalette(newPal);
        update = true;
        return newPal;
    }

    private void requireSameSize(BufferedImage src)
    {
        if (src == null)
            throw new RuntimeException("No image was provided.");
        if (src.getWidth() != width || src.getHeight() != height)
            throw new RuntimeException(String.format(
                    "Imported image is %dx%d but this image is %dx%d; they must match.",
                    src.getWidth(), src.getHeight(), width, height));
    }

    /**
     * Median-cut {@code colors} (each an {@code int[]{r,g,b}}) into {@code count} representative colours
     * (the average of each final box). Splits the box with the widest single-channel range each step.
     * <p>Package-private so {@link Screen#applyImageRebuildingPalette} can reuse it when quantising a
     * background's sub-palettes.
     */
    static java.util.List<Color> medianCut(java.util.List<int[]> colors, int count)
    {
        java.util.List<Color> out = new ArrayList<>();
        if (count < 1) count = 1;
        if (colors.isEmpty()) { out.add(Color.BLACK); return out; }

        java.util.List<java.util.List<int[]>> boxes = new ArrayList<>();
        boxes.add(new ArrayList<>(colors));
        while (boxes.size() < count)
        {
            int bi = -1, bestRange = -1, bestCh = 0;
            for (int i = 0; i < boxes.size(); i++)
            {
                java.util.List<int[]> bx = boxes.get(i);
                if (bx.size() < 2) continue;
                for (int ch = 0; ch < 3; ch++)
                {
                    int mn = 255, mx = 0;
                    for (int[] c : bx) { mn = Math.min(mn, c[ch]); mx = Math.max(mx, c[ch]); }
                    if (mx - mn > bestRange) { bestRange = mx - mn; bi = i; bestCh = ch; }
                }
            }
            if (bi < 0) break; // nothing left worth splitting
            java.util.List<int[]> bx = boxes.remove(bi);
            final int ch = bestCh;
            bx.sort((a, b) -> Integer.compare(a[ch], b[ch]));
            int mid = bx.size() / 2;
            boxes.add(new ArrayList<>(bx.subList(0, mid)));
            boxes.add(new ArrayList<>(bx.subList(mid, bx.size())));
        }

        for (java.util.List<int[]> bx : boxes)
        {
            long r = 0, g = 0, b = 0;
            for (int[] c : bx) { r += c[0]; g += c[1]; b += c[2]; }
            int n = Math.max(1, bx.size());
            out.add(new Color((int) (r / n), (int) (g / n), (int) (b / n)));
        }
        return out;
    }

    // TODO figure out what is going on with the following three methods - why did I write these and give them such shitty names

//    /**
//     * Creates a copy of the provided image using the palette of the <code>IndexedImage</code> object this method is executed from
//     * @param image a <code>IndexedImage</code> to apply a palette to
//     * @return a <code>IndexedImage</code> identical to the provided one except with a different palette
//     */
//    @Deprecated
//    public IndexedImage createCopyWithPalette(IndexedImage image)
//    {
//        //realistically this should never throw because image.getIndexGuide() was checked when image was created
//        try {
//            return new IndexedImage(image.getPixels(),palette);
//        }
//        catch(ImageException ignored) {
//
//        }
//        return null;
//    }
//
//    /**
//     * Creates a copy of this <code>IndexedImage</code>
//     * @return an <code>IndexedImage</code> identical to this one
//     */
//    @Deprecated
//    public IndexedImage copyOfSelf()
//    {
//        //realistically this should never throw because indexGuide was checked when this was created
//        try {
//            return new IndexedImage(pixels, palette);
//        }
//        catch(ImageException ignored) {
//
//        }
//        return null;
//    }
//
//    /**
//     * Creates a copy of the provided image using the provided palette and the <code>IndexedImage</code> the method is executed from
//     * @param palette a Color[] to apply to the copy of this <code>IndexedImage</code>
//     * @return a <code>IndexedImage</code> with the image of the <code>IndexedImage</code> object this method is executed from but the provided palette applied
//     */
//    @Deprecated
//    public IndexedImage createCopyWithImage(Color[] palette)
//    {
//        //realistically this should never throw because indexGuide was checked when this was created
////        try {
////            return new IndexedImage(pixels,palette);
////        }
////        catch(ImageException ignored) {
////
////        }
//        return null;
//    }

    /**
     * Gets the width of this <code>IndexedImage</code>
     * @return an <code>int</code>
     */
    public int getWidth()
    {
        return width;
    }

    /**
     * Gets the height of this <code>IndexedImage</code>
     * @return an <code>int</code>
     */
    public int getHeight()
    {
        return height;
    }

    /**
     * Gets the palette of this <code>IndexedImage</code>
     * @return a <code>Palette</code>
     */
    public Palette getPalette()
    {
        return palette;
    }

    /**
     * Sets the palette of this <code>IndexedImage</code>
     * @param palette a <code>Palette</code> to change this image's palette to
     */
    public void setPalette(Palette palette)
    {
        this.palette = palette;
        update = true;
    }

    /**
     * Get the encryption key value of this <code>IndexedImage</code>
     * @return an <code>int</code>
     * @exception RuntimeException if there is no key
     */
    public int getEncryptionKey()
    {
        if (encryptionKey != -1)
            return encryptionKey;
        else
            throw new RuntimeException("There isn't a key to return");
    }

    /**
     * Sets the encryption key value of this <code>IndexedImage</code>
     * @param encryptionKey an <code>int</code>
     */
    public void setEncryptionKey(int encryptionKey)
    {
        this.encryptionKey = encryptionKey;
    }

    /**
     * Gets the pixels of this <code>IndexedImage</code>
     * @return a <code>int[][]</code>
     */
    public int[][] getPixels()
    {
        int[][] ret = new int[pixels.length][];
        for (int row = 0; row < pixels.length; row++)
        {
            ret[row] = Arrays.copyOf(pixels[row], pixels[row].length);
        }
        return ret;
    }

    /**
     * Sets the pixels of this <code>IndexedImage</code>
     * @param pixels a <code>int[][]</code>
     */
    public void setPixels(int[][] pixels)
    {
        if (height != pixels.length)
            throw new RuntimeException("Height does not match");
        if (!allRowsHaveSameWidth(pixels))
            throw new RuntimeException("Not all rows have the same width");
        for (int[] row : pixels)
        {
            if (row.length != width)
                throw new RuntimeException("Width does not match");
        }

        int[][] copy = new int[pixels.length][];
        for (int row = 0; row < pixels.length; row++)
        {
            copy[row] = Arrays.copyOf(pixels[row], pixels[row].length);
        }
        this.pixels = copy;
        update = true;
    }

    /**
     * Gets the palette index specified at coordinate (x,y) in this <code>IndexedImage</code>, where (0,0) is the top-left corner.
     * <p>NOTE: Was previously named getCoordinateValue()
     * @param x an <code>int</code> containing the column value
     * @param y an <code>int</code> containing the row value
     * @return an <code>int</code>
     */
    public int getPixelValue(int x, int y)
    {
        return pixels[y][x];
    }

    /**
     * Sets the palette index specified at coordinate (x,y) in this <code>IndexedImage</code>, where (0,0) is the top-left corner
     * <p>NOTE: Was previously named setCoordinateValue()
     * @param x an <code>int</code> containing the column value
     * @param y an <code>int</code> containing the row value
     * @param colorIdx an <code>int</code> containing the color index in the palette
     */
    public void setPixelValue(int x, int y, int colorIdx)
    {
        // The array index would throw on its own; this only makes the failure say which pixel
        // and how big the image is. It used to print "moo" and let the throw happen anyway.
        if (y < 0 || y >= pixels.length || x < 0 || x >= pixels[y].length)
        {
            throw new ArrayIndexOutOfBoundsException(String.format(
                    "Pixel (%d, %d) is outside this %dx%d image.", x, y, width, height));
        }
        pixels[y][x] = colorIdx;
        update = true;
    }

    /**
     * Gets the bit depth of this <code>IndexedImage</code>
     * @return an <code>int</code>
     */
    public int getBitDepth()
    {
        return bitDepth;
    }

    /**
     * Sets the bit depth of this <code>IndexedImage</code>
     * @param bitDepth an <code>int</code>
     */
    public void setBitDepth(int bitDepth)
    {
        this.bitDepth = bitDepth;
    }

    /**
     * Gets the scanning mode of this <code>IndexedImage</code>
     * @return a <code>NcgrUtils.ScanMode</code>
     */
    public NcgrUtils.ScanMode getScanMode()
    {
        return scanMode;
    }

    /**
     * Whether this NCGR is stored as a scanned (linear bitmap) image rather than tiled character data.
     * A scanned image's {@link #getPixels() pixels} are a correct bitmap, but it can't be composed
     * through an NCER (whose OAM offsets assume tiled data) — see {@link CellBank#setParentImage}.
     * Exposed publicly so callers in other packages can branch without reaching the protected
     * {@link NcgrUtils.ScanMode} enum.
     * @return {@code true} if scanned (front-to-back or back-to-front)
     */
    public boolean isScanned()
    {
        return scanMode != NcgrUtils.ScanMode.NOT_SCANNED;
    }

    /**
     * Sets the scanning mode of this <code>IndexedImage</code>
     * @param scanMode a <code>NcgrUtils.ScanMode</code>
     */
    public void setScanMode(NcgrUtils.ScanMode scanMode)
    {
        this.scanMode = scanMode == null ? NcgrUtils.ScanMode.NOT_SCANNED : scanMode;
    }

    /**
     * Get the columns per chunk for this image
     * @return an <code>int</code>
     */
    public int getColsPerChunk()
    {
        return colsPerChunk;
    }

    public void setColsPerChunk(int colsPerChunk)
    {
        this.colsPerChunk = colsPerChunk;
    }

    public int getRowsPerChunk()
    {
        return rowsPerChunk;
    }

    public void setRowsPerChunk(int rowsPerChunk)
    {
        this.rowsPerChunk = rowsPerChunk;
    }

    public int getNumTiles()
    {
        return numTiles;
    }

    public void setNumTiles(int numTiles)
    {
        this.numTiles = numTiles;
    }

    public int getMappingType()
    {
        return mappingType;
    }

    public void setMappingType(int mappingType)
    {
        this.mappingType = mappingType;
    }

    public boolean isVram()
    {
        return vram;
    }

    public void setVram(boolean vram)
    {
        this.vram = vram;
    }

    public boolean hasSopc()
    {
        return sopc;
    }

    public void setSopc(boolean sopc)
    {
        this.sopc = sopc;
    }

    public int getPaletteIdx()
    {
        return paletteIdx;
    }

    public void setPaletteIdx(int paletteIdx)
    {
        this.paletteIdx = paletteIdx;
        update = true;
    }

    /**
     * Combines two <code>IndexedImage</code> objects of equal height to product a single <code>IndexedImage</code>
     * @param leftImage the primary <code>IndexedImage</code>, its palette is to be used by the composite <code>IndexedImage</code>
     * @param rightImage the secondary <code>IndexedImage</code>, its palette is thrown out
     * @return a composite <code>IndexedImage</code> composed of <code>leftImage</code> and <code>rightImage</code> side by side
     */
    public static IndexedImage getHorizontalCompositeImage(IndexedImage leftImage, IndexedImage rightImage) throws ImageException
    {
        if (leftImage.height != rightImage.height) //todo revisit this and see if you can make it address this discrepancy
            throw new ImageException("The two images you are trying to composite do not have the same height");

        int[][] ret = new int[leftImage.getHeight()][leftImage.getWidth() + rightImage.getWidth()];

        for (int row = 0; row < leftImage.getHeight(); row++)
        {
            System.arraycopy(leftImage.getPixels()[row],0,ret[row],0,leftImage.getWidth());
            System.arraycopy(rightImage.getPixels()[row],0,ret[row],leftImage.getWidth(),rightImage.getWidth());
        }

        IndexedImage image = new IndexedImage(leftImage.height, rightImage.width + leftImage.width, leftImage.bitDepth, leftImage.palette);

        image.setPixels(ret);

        image.paletteIdx = leftImage.paletteIdx;
        image.scanMode = leftImage.scanMode;
        image.colsPerChunk = leftImage.colsPerChunk;
        image.rowsPerChunk = leftImage.rowsPerChunk;
        // numTiles is left as the constructor computed it from the composite's own dimensions.
        // Summing the two inputs is wrong whenever either was a partial grid: the sum under-
        // counts, save() emits only that many tiles, and the image reparses shorter than it is.
        image.mappingType = leftImage.mappingType;
        image.vram = leftImage.vram;
        image.encryptionKey = leftImage.encryptionKey;
        image.sopc = leftImage.sopc;

        return image;
    }

    @Override
    public boolean equals(Object o)
    {
        if(this == o) {
            return true;
        }

        if(o == null || getClass() != o.getClass()) {
            return false;
        }

        IndexedImage image = (IndexedImage) o;

        if (height != image.height || width != image.width)
            return false;

        for (int row = 0; row < height; row++)
        {
            if (!Arrays.equals(pixels[row], image.pixels[row]))
            {
                return false;
            }
        }

        return bitDepth == image.bitDepth && colsPerChunk == image.colsPerChunk && rowsPerChunk == image.rowsPerChunk && numTiles == image.numTiles && mappingType == image.mappingType && vram == image.vram && encryptionKey == image.encryptionKey && sopc == image.sopc && scanMode == image.scanMode;
    }

    @Override
    public int hashCode()
    {
        int result = Objects.hash(palette, height, width, bitDepth, scanMode, colsPerChunk, rowsPerChunk, numTiles, mappingType, vram, encryptionKey, sopc);
        result = 31 * result + Arrays.hashCode(pixels);
        return result;
    }

    public String toString()
    {
        String s;
        switch (scanMode)
        {
            case NOT_SCANNED:
                s = "tiled";
                break;
            case FRONT_TO_BACK:
                s = "scanned front-to-back";
                break;
            case BACK_TO_FRONT:
                s = "scanned back-to-front";
                break;
            default:
                s = "";
        }

        return String.format("%dbpp %s indexed image with size %dx%d", bitDepth, s, height, width);
    }

    /**
     * A checked exception to be thrown when errors relating to <code>IndexedImage</code> objects occur.
     */
    public static class ImageException extends Exception
    {
        public ImageException(String message, Throwable cause)
        {
            super(message, cause);
        }

        public ImageException(String message)
        {
            super(message);
        }

        public ImageException(Throwable cause)
        {
            super(cause);
        }
    }


    protected static class NcgrUtils {
        // reading ncgr code

        private static int convertFromScanned4Bpp(byte[] src, IndexedImage image, boolean scanFrontToBack)
        {
            int width = image.width;
            int height = image.height;

            int encValue;

            MemBuf dataBuf = MemBuf.create(src);
            MemBuf.MemBufReader reader = dataBuf.reader();

            // 4bpp packs two pixels per byte, so a tile is 32 bytes = 16 u16 words. Sized from
            // the tile count rather than from width*height: the header's tile grid can be larger
            // than the char data it actually carries, and deriving the length from the grid then
            // reads past the end of the file. This also removes the depth-dependent divisor that
            // was the original mistake - it was set to width*height/2, which is the 8bpp figure.
            int[] data = new int[image.numTiles * 16];
            for (int i = 0; i < data.length; i++)
            {
                data[i] = reader.readUInt16();
            }

            if (scanFrontToBack)
            {
                encValue = data[0];
                for(int i = 0; i < data.length; i++)
                {
                    data[i] = data[i] ^ (encValue & 0xffff);
                    encValue *= 1103515245;
                    encValue += 24691;
                }
            }
            else
            {
                encValue = data[data.length - 1];

                for(int i = data.length - 1; i >= 0; i--)
                {
                    data[i] = data[i] ^ (encValue & 0xffff);
                    encValue *= 1103515245;
                    encValue += 24691;
                }
            }

            byte[] arr = new byte[width*height];
            // bounded by both: a partial file supplies fewer words than the grid has room for,
            // and the remainder stays as palette index 0
            for (int i = 0; i < arr.length/4 && i < data.length; i++)
            {
                arr[i*4] = (byte) (data[i] & 0xf);
                arr[i*4+1] = (byte) ((data[i] >> 4) & 0xf);
                arr[i*4+2] = (byte) ((data[i] >> 8) & 0xf);
                arr[i*4+3] = (byte)((data[i] >> 12) & 0xf);
            }

            int[][] pixelTable= new int[height][width];
            int idx = 0;
            for (int row = 0; row < height; row++)
            {
                for (int col = 0; col < width; col++)
                {
                    pixelTable[row][col] = arr[idx++];
                }
            }

            image.setPixels(pixelTable);

            return encValue;
        }

        private static int convertFromScanned8Bpp(byte[] src, IndexedImage image, boolean scanFrontToBack)
        {
            int width = image.width;
            int height = image.height;

            int encValue;

            MemBuf dataBuf = MemBuf.create(src);
            MemBuf.MemBufReader reader = dataBuf.reader();

            // 8bpp is one byte per pixel, so a tile is 64 bytes = 32 u16 words. This was
            // width*height/4 - the 4bpp figure - so it under-allocated by half and the unpack
            // below indexed straight off the end.
            int[] data = new int[image.numTiles * 32];
            for (int i = 0; i < data.length; i++)
            {
                data[i] = reader.readUInt16();
            }

            if (scanFrontToBack)
            {
                encValue = data[0];
                for(int i = 0; i < data.length; i++)
                {
                    data[i] = data[i] ^ (encValue & 0xffff);
                    encValue *= 1103515245;
                    encValue += 24691;
                }
            }
            else
            {
                encValue = data[data.length - 1];

                for(int i = data.length - 1; i >= 0; i--)
                {
                    data[i] = data[i] ^ (encValue & 0xffff);
                    encValue *= 1103515245;
                    encValue += 24691;
                }
            }

            byte[] arr = new byte[width*height];
            // see the 4bpp path: bounded by the data actually present as well as by the grid
            for (int i = 0; i < arr.length/2 && i < data.length; i++)
            {
                arr[i*2] = (byte) (data[i] & 0xff);
                arr[i*2+1] = (byte) ((data[i] >> 8) & 0xff);
            }

            int[][] pixelTable= new int[height][width];
            int idx = 0;
            for (int row = 0; row < height; row++)
            {
                for (int col = 0; col < width; col++)
                {
                    pixelTable[row][col] = arr[idx++] & 0xFF;
                }
            }

            image.setPixels(pixelTable);

            return encValue;
        }

        private static class ChunkManager
        {
            int tilesSoFar = 0;
            int rowsSoFar = 0;
            int chunkStartX = 0;
            int chunkStartY = 0;

            ChunkManager() {}

            void advanceTilePosition(int chunksWide, int colsPerChunk, int rowsPerChunk)
            {
                tilesSoFar++;
                if (tilesSoFar == colsPerChunk)
                {
                    tilesSoFar = 0;
                    rowsSoFar++;
                    if (rowsSoFar == rowsPerChunk)
                    {
                        rowsSoFar = 0;
                        chunkStartX++;
                        if (chunkStartX == chunksWide)
                        {
                            chunkStartX = 0;
                            chunkStartY++;
                        }
                    }
                }
            }
        }

        protected static void convertFromTiles4Bpp(byte[] src, IndexedImage image, int startOffset)
        {
            if (startOffset != 0)
            {
                byte[] newTiles = new byte[src.length - startOffset];
                System.arraycopy(src, startOffset, newTiles, 0, newTiles.length);
                src = newTiles;
            }

            ChunkManager chunkManager = new ChunkManager();
            int chunksWide = (image.getWidth() / 8) / image.getColsPerChunk();
            int pitch = (chunksWide * image.colsPerChunk) * 4;

//            image.palette.setColor(127, Color.MAGENTA);
//            for (int row = 0; row < image.getHeight(); row++)
//            {
//                for (int col = 0; col < image.getWidth(); col++)
//                {
//                    image.setPixelValue(col, row, 127);
//                }
//            }
//
//            for (int i = 0; i < 127; i++)
//            {
//                image.palette.setColor(i, new Color((int) (Math.random() * 255), (int) (Math.random() * 255), (int) (Math.random() * 255)));
//            }

//            prepareImageTest(image);

            int idx = 0;
            for (int i = 0; i < image.numTiles; i++)
            {
                for (int j = 0; j < 8; j++)
                {
                    int idxComponentY = (chunkManager.chunkStartY * image.rowsPerChunk + chunkManager.rowsSoFar) * 8 + j;
                    for (int k = 0; k < 4; k++)
                    {
                        int idxComponentX = (chunkManager.chunkStartX * image.colsPerChunk + chunkManager.tilesSoFar) * 4 + k;

                        int compositeIdx = 2 * (idxComponentY * pitch + idxComponentX);

                        int destX = compositeIdx % image.getWidth();
                        int destY = compositeIdx / image.getWidth();

                        byte srcPixelPair = src[idx++];
                        int leftPixel = srcPixelPair & 0xF;
                        int rightPixel = (srcPixelPair >> 4) & 0xF;

                        // A destination outside the image means the tile grid and the declared
                        // dimensions disagree, which a malformed or truncated file can produce.
                        // This used to print "moo" and then write anyway; setPixelValue now
                        // reports the offending pixel itself, so the pair is simply skipped.
                        if (destX >= image.getWidth() || destY >= image.getHeight())
                            continue;

                        image.setPixelValue(destX, destY, leftPixel);
                        image.setPixelValue(destX + 1, destY, rightPixel);
//                        System.out.printf("(%d, %d), (%d, %d)\n", destX, destY, destX + 1, destY);

//                        testImage(image);
                    }
                }

                chunkManager.advanceTilePosition(chunksWide, image.colsPerChunk, image.rowsPerChunk);
            }
        }

        protected static void convertFromTiles8Bpp(byte[] src, IndexedImage image, int startOffset)
        {
            if (startOffset != 0)
            {
                byte[] newTiles = new byte[src.length - startOffset];
                System.arraycopy(src, startOffset, newTiles, 0, newTiles.length);
                src = newTiles;
            }

            ChunkManager chunkManager = new ChunkManager();
            int chunksWide = (image.getWidth() / 8) / image.getColsPerChunk();
            int pitch = (chunksWide * image.colsPerChunk) * 8;

            int idx = 0;
            for (int i = 0; i < image.numTiles; i++)
            {
                for (int j = 0; j < 8; j++)
                {
                    int idxComponentY = (chunkManager.chunkStartY * image.rowsPerChunk + chunkManager.rowsSoFar) * 8 + j;

                    for (int k = 0; k < 8; k++)
                    {
                        int idxComponentX = (chunkManager.chunkStartX * image.colsPerChunk + chunkManager.tilesSoFar) * 8 + k;
                        int srcPixel = src[idx++] & 0xFF;

                        int compositeIdx = idxComponentY * pitch + idxComponentX;
                        int destX = compositeIdx % image.getWidth();
                        int destY = compositeIdx / image.getWidth();

                        image.setPixelValue(destX, destY, srcPixel);
                    }
                }

                chunkManager.advanceTilePosition(chunksWide, image.colsPerChunk, image.rowsPerChunk);
            }
        }

        // writing ncgr code

        private static final int charHeaderPos = NTR_HEADER_SIZE;
        private static final int pixelsPos = NTR_HEADER_SIZE + 0x20; // 0x30
        private static final byte[] charHeader = new byte[] { 0x52, 0x41, 0x48, 0x43, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x18, 0x00, 0x00, 0x00 };
        private static final byte[] sopcBuffer = new byte[] { 0x53, 0x4F, 0x50, 0x43, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };


        private static byte[] convertToScanned4Bpp(IndexedImage image, int bufferSize)
        {
            long encValue = image.encryptionKey;

            int idx = 0;
            int[] data = new int[image.height*image.width];

            for (int row = 0; row < image.height; row++)
            {
                for (int col = 0; col < image.width; col++)
                {
                    data[idx++] = image.pixels[row][col];
                }
            }

            short[] arr = new short[data.length/2];
            for (int i = 0; i < arr.length; i++)
            {
                arr[i] = (short) (( (data[i * 2] & 0xF) | ((data[i * 2 + 1] & 0xF) << 4)) & 0xff);
            }

            if (bufferSize != arr.length)
                throw new RuntimeException("Invalid buffer length: does not match height * width / 2");

            byte[] dest = new byte[bufferSize];
            if (image.scanMode == ScanMode.FRONT_TO_BACK)
            {
                for (int i = bufferSize - 1; i > 0; i -= 2)
                {
                    int val = arr[i - 1] | (arr[i] << 8);
                    encValue = (encValue - 24691) * 4005161829L;
                    val ^= (encValue & 0xFFFF);
                    dest[i] = (byte) ((val >> 8) & 0xff);
                    dest[i - 1] = (byte) (val & 0xff);
                }
            }
            else if (image.scanMode == ScanMode.BACK_TO_FRONT)
            {
                for (int i = 1; i < bufferSize; i += 2)
                {
                    int val = (arr[i] << 8) | arr[i - 1];
                    encValue = (int) ((encValue - 24691) * 4005161829L);
                    val ^= (encValue & 0xFFFF);
                    dest[i] = (byte) ((val >> 8) & 0xff);
                    dest[i - 1] = (byte) (val & 0xff);
                }
            }

            return dest;
        }

//        private static byte[] convertToScanned8Bpp(IndexedImage image, int bufferSize)
//        {
//            long encValue = image.encryptionKey;
//
//            int idx = 0;
//            int[] data = new int[image.height*image.width];
//
//            for (int row = 0; row < image.height; row++)
//            {
//                for (int col = 0; col < image.width; col++)
//                {
//                    data[idx++] = image.pixels[row][col];
//                }
//            }
//
//            if (bufferSize != data.length)
//                throw new RuntimeException("Invalid buffer length: does not match height * width / 2");
//
//            byte[] dest = new byte[bufferSize];
//            if (image.scanMode == ScanMode.FRONT_TO_BACK)
//            {
//                for (int i = bufferSize - 1; i > 0; i -= 2)
//                {
//                    int val = data[i - 1] | (data[i] << 8);
//                    encValue = (encValue - 24691) * 4005161829L;
//                    val ^= (encValue & 0xFFFF);
//                    dest[i] = (byte) ((val >> 8) & 0xff);
//                    dest[i - 1] = (byte) (val & 0xff);
//                }
//            }
//            else if (image.scanMode == ScanMode.BACK_TO_FRONT)
//            {
//                for (int i = 1; i < bufferSize; i += 2)
//                {
//                    int val = (data[i] << 8) | data[i - 1];
//                    encValue = (int) ((encValue - 24691) * 4005161829L);
//                    val ^= (encValue & 0xFFFF);
//                    dest[i] = (byte) ((val >> 8) & 0xff);
//                    dest[i - 1] = (byte) (val & 0xff);
//                }
//            }
//
//            return dest;
//        }

        protected static byte[] convertToTiles4Bpp(IndexedImage image)
        {
            ChunkManager chunkManager = new ChunkManager();
            int chunksWide = (image.getWidth() / 8) / image.getColsPerChunk();
            int pitch = (chunksWide * image.colsPerChunk) * 4;

            byte[] src = new byte[image.height * image.width];
            int idx = 0;
            for (int row = 0; row < image.height; row++)
            {
                for (int col = 0; col < image.width; col++)
                {
                    src[idx++] = (byte) (image.pixels[row][col] & 0xff);
                }
            }

            byte[] dest = new byte[image.numTiles * 32]; // 4bpp tile is 32 bytes
            idx = 0;
            for (int i = 0; i < image.numTiles; i++) {
                for (int j = 0; j < 8; j++) {
                    int srcY = (chunkManager.chunkStartY * image.rowsPerChunk + chunkManager.rowsSoFar) * 8 + j;

                    for (int k = 0; k < 4; k++) {
                        int srcX = (chunkManager.chunkStartX * image.colsPerChunk + chunkManager.tilesSoFar) * 4 + k;
                        byte leftPixel = (byte) (src[2 * (srcY * pitch + srcX)] & 0xF);
                        byte rightPixel = (byte) (src[2 * (srcY * pitch + srcX) + 1] & 0xF);

                        dest[idx++] = (byte) (((rightPixel << 4) & 0xF0) | leftPixel);
                    }
                }

                chunkManager.advanceTilePosition(chunksWide, image.colsPerChunk, image.rowsPerChunk);
            }

            return dest;
        }

        protected static byte[] convertToTiles8Bpp(IndexedImage image)
        {
            ChunkManager chunkManager = new ChunkManager();
            int chunksWide = (image.getWidth() / 8) / image.getColsPerChunk();
            int pitch = (chunksWide * image.colsPerChunk) * 8;

            byte[] src = new byte[image.height * image.width];
            int idx = 0;
            for (int row = 0; row < image.height; row++)
            {
                for (int col = 0; col < image.width; col++)
                {
                    src[idx++] = (byte) (image.pixels[row][col] & 0xff);
                }
            }

            byte[] dest = new byte[image.numTiles * 64]; // 8bpp tile is 64 bytes
            idx = 0;
            for (int i = 0; i < image.numTiles; i++) {
                for (int j = 0; j < 8; j++) {
                    int srcY = (chunkManager.chunkStartY * image.rowsPerChunk + chunkManager.rowsSoFar) * 8 + j;

                    for (int k = 0; k < 8; k++) {
                        int srcX = (chunkManager.chunkStartX * image.colsPerChunk + chunkManager.tilesSoFar) * 8 + k;
                        byte pixel = (byte) (src[srcY * pitch + srcX] & 0xFF);

                        dest[idx++] = pixel;
                    }
                }

                chunkManager.advanceTilePosition(chunksWide, image.colsPerChunk, image.rowsPerChunk);
            }

            return dest;
        }

//        protected static void convertFromTiles4BppAlternate(byte[] src, IndexedImage image, int startOffset)
//        {
//            if (startOffset != 0)
//            {
//                byte[] newTiles = new byte[src.length - startOffset];
//                System.arraycopy(src, startOffset, newTiles, 0, newTiles.length);
//                src = newTiles;
//            }
//
//
//            int bitDepth = 4;
//
//            byte[] tilePal = new byte[src.length * (8 / bitDepth)];
////            if (tilesHeight < 8)
////                tilesHeight = 8;
//            byte[] img_tiles = NcgrUtils.linealToHorizontal(src, image.width, image.height, bitDepth, 8);
//            tilePal = NcgrUtils.linealToHorizontal(tilePal, image.width, image.height, 8, 8);
//
////            System.out.println(src.length);
////            for (int i = 0; i < img_tiles.length; i++)
////            {
////                if (img_tiles[i] != 0)
////                    System.out.println(i);
////            }
//
//            byte[] output = new byte[image.height * image.width];
//
//            int[][] pixels = new int[image.height][image.width];
//
//            int pos = 0;
//            for (int row= 0; row < image.height; row++)
//            {
//                for (int col= 0; col < image.width; col++)
//                {
//                    int num_pal = 0;
//                    if(tilePal.length > col + row * image.width)
//                    {
//                        num_pal = tilePal[col + row * image.width];
//                    }
//
//                    if(num_pal >= image.palette.getNumColors())
//                    {
//                        num_pal = 0;
//                    }
//
//                    int colorIdx = getColor(img_tiles, image.palette.getNumColors(), pos++);
//
////                    output[row * image.width + col] = (byte) colorIdx;
//                    pixels[row][col] = colorIdx;
//                }
//            }
//
//            image.pixels = pixels;
//            image.update = true;
//            called++;
//        }
//
//        private static byte[] linealToHorizontal(byte[] lineal, int width, int height, int bpp, int tile_size)
//        {
//            byte[] horizontal = new byte[lineal.length];
//            int tile_width = tile_size * bpp / 8;   // Calculate the number of byte per line in the tile
//            // pixels per line * bits per pixel / 8 bits per byte
//            int tilesX = width / tile_size;
//            int tilesY = height / tile_size;
//
//            int pos = 0;
//            for (int ht = 0; ht < tilesY; ht++)
//            {
//                for (int wt = 0; wt < tilesX; wt++)
//                {
//                    // Get the tile data
//                    for (int h = 0; h < tile_size; h++)
//                    {
//                        for (int w = 0; w < tile_width; w++)
//                        {
//                            final int value = (w + h * tile_width * tilesX) + wt * tile_width + ht * tilesX * tile_size * tile_width;
//                            if (value >= lineal.length)
//                                continue;
//                            if (pos >= lineal.length)
//                                continue;
//
//                            horizontal[value] = lineal[pos++];
//                        }
//                    }
//                }
//            }
//
//            return horizontal;
//        }
//
//        private static int getColor(byte[] data, int paletteLength, int pos)
//        {
//            int color = 0;
//            int alpha, index;
//
//            if (data.length <= (pos / 2))
//                return color;
//            int bit4 = data[pos / 2] & 0xff;
//            index = byteToBit4(bit4)[pos % 2];
//            if (paletteLength > index)
//                color = index;
//
//            return color;
//        }
//
//        public static byte[] byteToBit4(int data)
//        {
//            byte[] bit4 = new byte[2];
//
//            bit4[0] = (byte)(data & 0x0F);
//            bit4[1] = (byte)((data & 0xF0) >> 4);
//
//            return bit4;
//        }

        protected enum ScanMode {
            NOT_SCANNED,
            FRONT_TO_BACK,
            BACK_TO_FRONT;

            static ScanMode getMode(boolean scanned, boolean frontToBack)
            {
                if (!scanned)
                {
                    return NOT_SCANNED;
                }
                else
                {
                    return frontToBack ? FRONT_TO_BACK : BACK_TO_FRONT;
                }
            }

            static boolean isScanned(ScanMode mode)
            {
                return mode != NOT_SCANNED;
            }
        }

        private static JFrame frame;
        private static JLabel label;

        public static void prepareImageTest(IndexedImage image)
        {
            frame = new JFrame("Test");
            frame.setSize(image.getWidth(), image.getHeight());
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            label = new JLabel();
            testImage(image);
            frame.getContentPane().add(label, BorderLayout.CENTER);
            frame.setLocationRelativeTo(null);
            frame.pack();
            frame.setVisible(true);
            frame.repaint();
        }

        protected static void testImage(IndexedImage image)
        {
            BufferedImage resizedImage = new BufferedImage(image.getWidth() * 4, image.getHeight() * 4, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics2D = resizedImage.createGraphics();
            graphics2D.drawImage(image.getImage(), 0, 0, image.getWidth() * 4, image.getHeight() * 4, null);
            graphics2D.dispose();

            label.setIcon(new ImageIcon(resizedImage));

            frame.repaint();
        }

        private static int called = 0;
        private static Color[] testColors = {Color.MAGENTA, Color.CYAN, Color.RED, Color.YELLOW, Color.PINK, Color.ORANGE, Color.GRAY, Color.GREEN, Color.BLUE, Color.DARK_GRAY,
                new Color(82, 102, 180),
                new Color(17, 30, 100),
                new Color(152, 89, 66),
                new Color(132, 200, 100),
                new Color(54, 19, 206),
                new Color(119, 61, 97)};

        protected static void convertOffsetToCoordinate(byte[] src, int startByte, int numPixels, IndexedImage image, int numTiles, int chunksWide, int colsPerChunk, int rowsPerChunk, IndexedImage cell)
        {
            ChunkManager chunkManager = new ChunkManager();
            int pitch = (chunksWide * colsPerChunk) * 4;


            image.palette.setColor(120 + (called % testColors.length), testColors[(called % testColors.length)]);

            int startX = 0;
            int startY = 0;

            int idx = 0;
            int numCounted = 0;
            for (int i = 0; i < numTiles; i++)
            {
                for (int j = 0; j < 8; j++)
                {
                    int idxComponentY = (chunkManager.chunkStartY * rowsPerChunk + chunkManager.rowsSoFar) * 8 + j;
                    for (int k = 0; k < 4; k++)
                    {
                        int idxComponentX = (chunkManager.chunkStartX * colsPerChunk + chunkManager.tilesSoFar) * 4 + k;

                        int compositeIdx = 2 * (idxComponentY * pitch + idxComponentX);

                        int destX = compositeIdx % image.getWidth();
                        int destY = compositeIdx / image.getWidth();
                        idx++;

                        if (idx > startByte && numCounted < numPixels && called != 0)
                        {
                            if (numCounted == 0)
                            {
                                startX = destX;
                                startY = destY;
                            }

                            numCounted += 2;

//                            image.setPixelValue(destX, destY, 120 + (called % testColors.length));
//                            image.setPixelValue(destX + 1, destY, 120 + (called % testColors.length));

                            cell.setPixelValue(destX - startX, destY - startY, image.getPixelValue(destX, destY));
                            cell.setPixelValue(destX - startX + 1, destY - startY, image.getPixelValue(destX + 1, destY));

                            testImage(image);
                            if (called != 0)
                            {
                                try
                                {
                                    Thread.sleep(100);
                                }
                                catch(InterruptedException e)
                                {
                                    e.printStackTrace();
                                }
                            }
                        }
                        else if (numCounted >= numPixels)
                        {
                            called++;
                            return;
                        }
                    }
                }

                chunkManager.advanceTilePosition(chunksWide, colsPerChunk, rowsPerChunk);
            }

            called++;
        }
    }


    /* BEGIN SECTION: PNG */

    private static final byte[] imageChunkHeader = new byte[] {0x49,0x48,0x44,0x52}; //IHDR
    private static final byte[] paletteChunkHeader = new byte[] {0x50,0x4C,0x54,0x45}; //PLTE
    private static final byte[] dataChunkHeader = new byte[] {0x49,0x44,0x41,0x54}; //IDAT
    private static final byte[] endChunkHeader = new byte[] {0x49,0x45,0x4E,0x44}; //IEND
    private static final byte[] pngHeader = new byte[] {(byte) 0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A}; //PNG

    // PNG importing code

    /**
     * Parses an indexed PNG file on disk and creates a <code>IndexedImage</code> representation of it
     * @param file a <code>File</code> containing the path to an indexed PNG file on disk
     * @return an <code>IndexedImage</code> containing an exact representation of the original indexed PNG file
     * @throws IOException if an I/O error occurs
     * @exception PngUtils.PngParseException can occur if the provided file is not a PNG, or if it is not indexed
     */
    public static IndexedImage fromIndexedPngFile(File file) throws IOException
    {
        return fromIndexedPngFile(file.getAbsolutePath());
    }

    /**
     * Parses an indexed PNG file on disk and creates a <code>IndexedImage</code> representation of it
     * If you're interested in how this works, please read the <a href="https://www.w3.org/TR/png/">PNG Technical Specification</a>
     * @param file a <code>String</code> containing a path to an indexed PNG file on disk
     * @return an <code>IndexedImage</code> containing an exact representation of the original indexed PNG file
     * @throws IOException if an I/O error occurs
     * @exception PngUtils.PngParseException can occur if the provided file is not a PNG, or if it is not indexed
     */
    public static IndexedImage fromIndexedPngFile(String file) throws IOException
    {
        byte[] fileContents = Buffer.readFile(file);

        int paletteIdx = 0;
        ArrayList<Integer> imageDataIdxList = new ArrayList<>();

        for (int i = 0; i < fileContents.length - 4; i++)
        {
            byte[] thisFour = Arrays.copyOfRange(fileContents,i,i+4);

            if (Arrays.equals(thisFour,paletteChunkHeader))
            {
                paletteIdx = i;
            }

            if (Arrays.equals(thisFour,dataChunkHeader))
            {
                imageDataIdxList.add(i);
            }

            if (Arrays.equals(thisFour,endChunkHeader))
            {
                break;
            }
        }

        Buffer buffer = new Buffer(file);
        byte[] first8 = buffer.readBytes(8);
        if (!Arrays.equals(pngHeader, first8))
        {
            throw new PngUtils.PngParseException("\"" + file + "\" is not a PNG file");
        }

        buffer.skipBytes(8); //jumps to IHDR chunk

        int width = swapEndianness(buffer.readInt());
        int height = swapEndianness(buffer.readInt());

        int bitDepth = buffer.readByte();
        int colorType = buffer.readByte();
        int compressionMethod = buffer.readByte();
        int filterMethod = buffer.readByte();
        int interlaceMethod = buffer.readByte();


        //todo create enums for these to increase readability
        if (colorType != 3)
        {
            throw new PngUtils.PngParseException("Not an indexed image: " + colorType);
        }

        if (compressionMethod != 0)
        {
            throw new PngUtils.PngParseException("Invalid image compression method: " + compressionMethod);
        }

        if (filterMethod != 0)
        {
            throw new PngUtils.PngParseException("Invalid filter method: " + filterMethod);
        }

        if (interlaceMethod < 0 || interlaceMethod > 1)
        {
            throw new PngUtils.PngParseException("Invalid interlace method: " + interlaceMethod);
        }

//        System.out.println("Bit Depth: " + bitDepth);
        ArrayList<Color> colorList = new ArrayList<>();
        buffer.skipTo(paletteIdx-4);

        int chunkLength = swapEndianness(buffer.readInt());
        buffer.skipBytes(4);

        for (int i = 0; i < chunkLength/3; i++)
        {
            int r = buffer.readByte();
            r -= r%8;

            int g = buffer.readByte();
            g -= g%8;

            int b = buffer.readByte();
            b -= b%8;

            colorList.add(new Color(r,g,b));
        }

        // a PNG may split its image data across any number of IDAT chunks - all of them together form one zlib stream
        MemBuf imageDataBuf = MemBuf.create();
        for (int imageDataIdx : imageDataIdxList)
        {
            buffer.skipTo(imageDataIdx-4);
            chunkLength = swapEndianness(buffer.readInt());
            buffer.skipBytes(4);
            imageDataBuf.writer().write(buffer.readBytes(chunkLength));
        }

        byte[] imageData = imageDataBuf.reader().getBuffer();
        imageData = PngUtils.decompress(imageData);

        Palette palette = new Palette(colorList.toArray(new Color[0]));
        IndexedImage ret = new IndexedImage(height, width, bitDepth, palette);
        ret.setPixels(PngUtils.createScanlines(imageData,bitDepth,filterMethod,width,height));
        ret.bitDepth = bitDepth;
        ret.scanMode = NcgrUtils.ScanMode.NOT_SCANNED;

        return ret;
    }

    // PNG exporting code

    /**
     * Generate a <code>byte[]</code> representation of this <code>IndexedImage</code> as a PNG
     * @return a <code>byte[]</code>
     * @throws IOException if an I/O error occurs
     */
    public byte[] saveAsIndexedPng() throws IOException
    {
        //Image Header Chunk (IHDR)
        MemBuf imageHeaderBuf = MemBuf.create();
        MemBuf.MemBufWriter writer = imageHeaderBuf.writer().write(imageChunkHeader);

        // export depth is local - mutating this.bitDepth here would corrupt the next save() as an NCGR
        int exportBitDepth;
        if (palette.size() > 16)
        {
            exportBitDepth = 8;
        }
        else if (palette.size() > 4)
        {
            exportBitDepth = 4;
        }
        else
        {
            exportBitDepth = 2;
        }

        int colorType = 3;
        int compressionMethod = 0;
        int filterMethod = 0;
        int interlaceMethod = 0;

        writer.writeInt(swapEndianness(width));
        writer.writeInt(swapEndianness(height));
        writer.writeBytes(exportBitDepth,colorType,compressionMethod,filterMethod,interlaceMethod);

        //Palette Chunk (PLTE)
        MemBuf paletteBuf = MemBuf.create();
        writer = paletteBuf.writer().write(paletteChunkHeader);

        for (Color c : palette.getColors())
        {
            writer.writeBytes(c.getRed(),c.getGreen(),c.getBlue());
        }

        //Image Data Chunk (IDAT)
        MemBuf dataBuf = MemBuf.create();
        writer = dataBuf.writer().write(dataChunkHeader);

        byte[] imageData = PngUtils.convertScanlines(pixels,exportBitDepth,filterMethod);
        imageData = PngUtils.compress(imageData);

        writer.write(imageData);

        //Image End Chunk (IEND)
        MemBuf endBuf = MemBuf.create();
        writer = endBuf.writer();

        writer.write(endChunkHeader);

        //Writing image
        MemBuf imageBuf = MemBuf.create();
        MemBuf.MemBufWriter imageWriter = imageBuf.writer();

        imageWriter.write(pngHeader);

        imageWriter.writeInt(swapEndianness(imageHeaderBuf.reader().getBuffer().length-4));
        imageWriter.write(imageHeaderBuf.reader().getBuffer());
        imageWriter.writeInt(PngUtils.getCrc32(imageHeaderBuf.reader().getBuffer()));

        imageWriter.writeInt(swapEndianness(paletteBuf.reader().getBuffer().length-4));
        imageWriter.write(paletteBuf.reader().getBuffer());
        imageWriter.writeInt(PngUtils.getCrc32(paletteBuf.reader().getBuffer()));

        imageWriter.writeInt(swapEndianness(dataBuf.reader().getBuffer().length-4));
        imageWriter.write(dataBuf.reader().getBuffer());
        imageWriter.writeInt(PngUtils.getCrc32(dataBuf.reader().getBuffer()));

        imageWriter.writeInt(swapEndianness(endBuf.reader().getBuffer().length-4));
        imageWriter.write(endBuf.reader().getBuffer());
        imageWriter.writeInt(PngUtils.getCrc32(endBuf.reader().getBuffer()));

        return imageBuf.reader().getBuffer();
    }

    /**
     * Exports an indexed PNG file to disk from this <code>IndexedImage</code>
     * @param file a <code>File</code> containing the path to the target file on disk
     * @throws IOException if an I/O error occurs
     */
    public void saveToIndexedPngFile(File file) throws IOException
    {
        saveToIndexedPngFile(file.getAbsolutePath());
    }

    /**
     * Exports an indexed PNG file to disk from this <code>IndexedImage</code>
     * @param file a <code>String</code> containing the path to the target file on disk
     * @throws IOException if an I/O error occurs
     */
    public void saveToIndexedPngFile(String file) throws IOException
    {
        BinaryWriter.writeFile(file, saveAsIndexedPng());
    }

    private static class PngUtils {
        private static byte[] compress(byte[] arr) throws IOException
        {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            DeflaterOutputStream deflaterOutputStream = new DeflaterOutputStream(byteArrayOutputStream);
            deflaterOutputStream.write(arr);
            deflaterOutputStream.flush();
            deflaterOutputStream.close();

            return byteArrayOutputStream.toByteArray();
        }

        private static byte[] convertScanlines(int[][] pixels, int bitDepth, int filterMethod)
        {
            ArrayList<Byte> retList = new ArrayList<>();

            int pixelsPerByte = 8 / bitDepth;

            for (int[] scanline : pixels)
            {
                if(scanline.length % pixelsPerByte != 0)
                    scanline = Arrays.copyOf(scanline,scanline.length + (pixelsPerByte - scanline.length % pixelsPerByte));

                retList.add((byte) filterMethod);

                for (int x = 0; x < scanline.length; x += pixelsPerByte)
                {
                    switch (bitDepth)
                    {
                        case 2:
                            retList.add((byte) ( ((scanline[x] & 0x3) << 6) | ((scanline[x + 1] & 0x3) << 4)
                                    | ((scanline[x + 2] & 0x3) << 2) | (scanline[x + 3] & 0x3) ) );
                            break;

                        case 4:
                            retList.add((byte) ( ((scanline[x] & 0xff) << 4) | ((scanline[x + 1] & 0xff) & 0xf) ) );
                            break;

                        case 8:
                            retList.add((byte) (scanline[x] & 0xff));
                            break;
                    }
                }
            }

            byte[] ret = new byte[retList.size()];

            for (int x = 0; x < ret.length; x++)
            {
                ret[x]= retList.get(x);
            }

            return ret;
        }

        private static int getCrc32(byte[] arr)
        {
            CRC32 crc32 = new CRC32();

            crc32.reset();
            crc32.update(arr);

            return swapEndianness((int) crc32.getValue());
        }

        // PNG importing code

        private static byte[] decompress(byte[] arr) throws IOException
        {
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(arr);
            InflaterInputStream inflaterInputStream = new InflaterInputStream(byteArrayInputStream);

            byte[] ret = new byte[0];
            byte[] buf = new byte[1024];
            int rlen = -1;
            while ((rlen = inflaterInputStream.read(buf)) != -1)
            {
                int current = ret.length;
                ret = Arrays.copyOf(ret,ret.length + rlen);
                System.arraycopy(buf,0,ret,current,rlen);
            }

            return ret;
        }

        private static int[][] createScanlines(byte[] arr, int bitDepth, int filterMethod,int width, int height)
        {
            int[][] ret = new int[height][width];
            int numBytes = (int) Math.ceil((double) bitDepth*width/8);

            int idx = 0;
            for (int i = 0; i < ret.length; i++)
            {
                byte[] scanline = Arrays.copyOfRange(arr,idx,idx+numBytes+1);
//            System.out.println(hexToString(scanline));
                idx += numBytes+1;

                ArrayList<Byte> byteList = new ArrayList<>();

                if (filterMethod != 0)
                {
                    byteList.add(scanline[0]);
                }


                for (byte b : Arrays.copyOfRange(scanline,1,scanline.length))
                {
                    switch (bitDepth)
                    {
                        case 2:
                            byteList.add((byte) ((b >> 6) & 0x3));
                            byteList.add((byte) ((b >> 4) & 0x3));
                            byteList.add((byte) ((b >> 2) & 0x3));
                            byteList.add((byte) (b & 0x3));
                            break;

                        case 4:
                            byteList.add((byte) ((b >> 4) & 0xf));
                            byteList.add((byte) (b & 0xf));
                            break;

                        case 8:
                            byteList.add(b);
                            break;
                    }
                }


                int[] line = new int[byteList.size()];
                for (int x = 0; x < byteList.size(); x++)
                {
                    line[x] = byteList.get(x) & 0xff;
                }

                ret[i] = line;

                if (ret[i].length > width)
                {
                    ret[i] = Arrays.copyOf(ret[i],width);
                }
            }

            return ret;
        }

        private static String hexToString(byte[] arr)
        {
            StringBuilder ret = new StringBuilder("[");
            for (byte b : arr)
            {
                String s = Integer.toHexString(b & 0xff);
                if (s.length() == 1)
                    s = 0 + s;

                ret.append("0x").append(s).append(",");
            }
            ret.deleteCharAt(ret.length()-1);
            ret.append("]");

            return ret.toString();
        }

        /**
         * An unchecked exception to be used when errors occur during the process of parsing a PNG file
         */
        public static class PngParseException extends RuntimeException
        {
            public PngParseException(String message, Throwable cause)
            {
                super(message, cause);
            }

            public PngParseException(String message)
            {
                super(message);
            }

            public PngParseException(Throwable cause)
            {
                super(cause);
            }
        }
    }

    /* END SECTION: PNG */
}
