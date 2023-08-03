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
    private byte[][] pixels;
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
    private NcgrUtils.ScanMode scanMode;
    private int colsPerChunk = -1;
    private int rowsPerChunk = -1;
    private int numTiles;
    private int mappingType;
    private boolean vram;
    private int encryptionKey = -1;

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

        if (tilesWidth == 0) //0x1A
        {
            tilesWidth = reader.readShort();
            if (tilesWidth < 0)
                tilesWidth = 1;
        }
        else
        {
            reader.skip(2);
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
        reader.skip(2);

        this.mappingType = reader.readUInt16(); // 0x22

        boolean scanned = reader.readByte() == 1; // 0x24
        reader.skip(2);

        this.scanMode = NcgrUtils.ScanMode.getMode(scanned, scanFrontToBack);
        this.vram = reader.readByte() == 1;

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
        this.pixels = new byte[this.height][this.width];
        this.palette = Palette.defaultPalette;
        this.rowsPerChunk = rowsPerChunk;
        this.colsPerChunk = colsPerChunk;

        int chunksWide = tilesWidth / colsPerChunk;

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
                    NcgrUtils.convertFromTiles4Bpp(imageData, this, chunksWide, 0);
                    break;
                case 8:
                    NcgrUtils.convertFromTiles8Bpp(imageData, this, numTiles, chunksWide, colsPerChunk, rowsPerChunk);
                    break;
            }
        }

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
//        pixels = new byte[height][width];
//        this.palette = palette;
//
//        update = true;
//    }

    /**
     * Creates an <code>IndexedImage</code> with the provided height, width, bit-depth, and palette
     * @param height an <code>int</code>
     * @param width an <code>int</code>
     * @param bitDepth an <code>int</code> with a value of 4 or 8 (defaults to 4 if another value is provided)
     * @param palette a <code>Palette</code> object
     */
    public IndexedImage(int height, int width, int bitDepth, Palette palette)
    {
        super("RGCN");
        this.height = height;
        this.width = width;

        if (bitDepth != 4 && bitDepth != 8)
            bitDepth = 4;
        this.bitDepth = bitDepth;

        pixels = new byte[height][width];
        this.palette = palette;

        update = true;
    }

    /**
     * Creates an <code>IndexedImage</code> using a predetermined assignment of color to pixel and the palette itself
     * @param pixels a <code>byte[][]</code> representing the index in the palette to pull the color from for each pixel in the sprite
     * @param palette a <code>Palette</code> containing the colors to be used in the image
     * @throws ImageException if the provided byte[][] does not contain rows of equal width
     */
    @Deprecated
    public IndexedImage(byte[][] pixels, Palette palette) throws ImageException
    {
        super("RGCN");
        height = pixels.length;
        if (allRowsHaveSameWidth(pixels))
            width = pixels[0].length;
        else
            throw new ImageException("The provided byte[][] does not contain rows of equal width");
        this.pixels = Arrays.copyOf(pixels, pixels.length);
        this.palette = palette.copyOf();

        update = true;
    }

    private boolean allRowsHaveSameWidth(byte[][] pixels)
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
        pixels = new byte[height][width];

        for (int row = 0; row < height; row++)
        {
            for (int col = 0; col < width; col++)
            {
                pixels[row][col] = (byte) colorList.indexOf(new Color(bufferedImage.getRGB(col,row)));
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
            numTiles = maxNumTiles;
        else if (numTiles > maxNumTiles)
            throw new RuntimeException(String.format("The specified number of tiles (%d) is greater than the maximum possible value (%d).", numTiles, maxNumTiles));

        int bufferSize = numTiles * tileSize;
        MemBuf pixelsBuf = MemBuf.create();
        MemBuf.MemBufWriter writer = pixelsBuf.writer();

        int chunksWide = tilesWidth / colsPerChunk;

        if (scanMode != NcgrUtils.ScanMode.NOT_SCANNED)
        {
            switch (bitDepth)
            {
                case 4:
                    writer.write(NcgrUtils.convertToScanned4Bpp(this, bufferSize));
                    break;
                case 8:
                    throw new RuntimeException("8bpp not supported yet.");
            }
        }
        else
        {
            switch (bitDepth)
            {
                case 4:
                    writer.write(NcgrUtils.convertToTiles4Bpp(this, numTiles, chunksWide));
                    break;
                case 8:
//                    NcgrUtils.ConvertToTiles8Bpp(image->pixels, pixelBuffer, numTiles, chunksWide, colsPerChunk, rowsPerChunk,
//                            invertColors);
                    break;
            }
        }

        MemBuf dataBuf = MemBuf.create();
        writer = dataBuf.writer();

        writeGenericNtrHeader(writer, bufferSize + (sopc ? 0x30 : 0x20), sopc ? 2 : 1);

        writer.write(NcgrUtils.charHeader);
        writer.setPosition(NcgrUtils.charHeaderPos + 8);

        if (mappingType == 0)
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
                        pixels[row][col] = (byte) -colorGuideList.indexOf(palette.getColor(i));
                }
            }
        }

        for (int row = 0; row < height; row++)
        {
            for (int col = 0; col < width; col++)
            {
                pixels[row][col] = (byte) Math.abs(pixels[row][col]);
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
                        pixels[row][col] = (byte) -colorGuideList.indexOf(palette.getColor(i));
                }
            }
        }

        for (int row = 0; row < height; row++)
        {
            for (int col = 0; col < width; col++)
            {
                pixels[row][col] = (byte) Math.abs(pixels[row][col]);
            }
        }

        update = true;
        return this;
    }

    // TODO figure out what is going on with the following three methods - why did I write these and give them such shitty names

    /**
     * Creates a copy of the provided image using the palette of the <code>IndexedImage</code> object this method is executed from
     * @param image a <code>IndexedImage</code> to apply a palette to
     * @return a <code>IndexedImage</code> identical to the provided one except with a different palette
     */
    @Deprecated
    public IndexedImage createCopyWithPalette(IndexedImage image)
    {
        //realistically this should never throw because image.getIndexGuide() was checked when image was created
        try {
            return new IndexedImage(image.getPixels(),palette);
        }
        catch(ImageException ignored) {

        }
        return null;
    }

    /**
     * Creates a copy of this <code>IndexedImage</code>
     * @return an <code>IndexedImage</code> identical to this one
     */
    @Deprecated
    public IndexedImage copyOfSelf()
    {
        //realistically this should never throw because indexGuide was checked when this was created
        try {
            return new IndexedImage(pixels, palette);
        }
        catch(ImageException ignored) {

        }
        return null;
    }

    /**
     * Creates a copy of the provided image using the provided palette and the <code>IndexedImage</code> the method is executed from
     * @param palette a Color[] to apply to the copy of this <code>IndexedImage</code>
     * @return a <code>IndexedImage</code> with the image of the <code>IndexedImage</code> object this method is executed from but the provided palette applied
     */
    @Deprecated
    public IndexedImage createCopyWithImage(Color[] palette)
    {
        //realistically this should never throw because indexGuide was checked when this was created
//        try {
//            return new IndexedImage(pixels,palette);
//        }
//        catch(ImageException ignored) {
//
//        }
        return null;
    }

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
     * @return a <code>byte[][]</code>
     */
    public byte[][] getPixels()
    {
        return pixels;
    }

    /**
     * Sets the pixels of this <code>IndexedImage</code>
     * @param pixels a <code>byte[][]</code>
     */
    public void setPixels(byte[][] pixels)
    {
        if (height != pixels.length)
            throw new RuntimeException("Height does not match");
        if (!allRowsHaveSameWidth(pixels))
            throw new RuntimeException("Not all rows have the same width");
        this.pixels = pixels;
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
        pixels[y][x] = (byte) colorIdx;
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
     * Sets the scanning mode of this <code>IndexedImage</code>
     * @param scanMode a <code>NcgrUtils.ScanMode</code>
     */
    public void setScanMode(NcgrUtils.ScanMode scanMode)
    {
        this.scanMode = scanMode;
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
    }

    /**
     * Combines two <code>IndexedImage</code> objects of equal height to product a single <code>IndexedImage</code>
     * @param image1 the primary <code>IndexedImage</code>, its palette is to be used by the composite <code>IndexedImage</code>
     * @param image2 the secondary <code>IndexedImage</code>, its palette is thrown out
     * @return a composite <code>IndexedImage</code> composed of <code>image1</code> and <code>image2</code> side by side
     */
    public static IndexedImage getCompositeImage(IndexedImage image1, IndexedImage image2) throws ImageException
    {
        if (image1.height != image2.height) //todo revisit this and see if you can make it address this discrepancy
            throw new ImageException("The two images you are trying to composite do not have the same height");

        byte[][] ret = new byte[image1.getHeight()][image1.getWidth() + image2.getWidth()];

        for (int row = 0; row < image1.getHeight(); row++)
        {
            System.arraycopy(image1.getPixels()[row],0,ret[row],0,image1.getWidth());
            System.arraycopy(image2.getPixels()[row],0,ret[row],image1.getWidth(),image2.getWidth());
        }

        return new IndexedImage(ret,image1.getPalette());
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

            int[] data = new int[width*height/4];
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
            for (int i = 0; i < arr.length/4; i++)
            {
                arr[i*4] = (byte) (data[i] & 0xf);
                arr[i*4+1] = (byte) ((data[i] >> 4) & 0xf);
                arr[i*4+2] = (byte) ((data[i] >> 8) & 0xf);
                arr[i*4+3] = (byte)((data[i] >> 12) & 0xf);
            }

            byte[][] pixelTable= new byte[height][width];
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

            int[] data = new int[width*height/4];
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
                encValue = data[width*height];

                for(int i = width*height - 1; i >= 0; i--)
                {
                    data[i] = data[i] ^ (encValue & 0xffff);
                    encValue *= 1103515245;
                    encValue += 24691;
                }
            }

            byte[] arr = new byte[width*height];
            for (int i = 0; i < arr.length/2; i++)
            {
                arr[i*2] = (byte) (data[i] & 0xff);
                arr[i*2+1] = (byte) ((data[i] >> 8) & 0xff);
            }

            byte[][] pixelTable= new byte[height][width];
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

        protected static void convertFromTiles4Bpp(byte[] src, IndexedImage image, int chunksWide, int startOffset)
        {
            if (startOffset != 0)
            {
                byte[] newTiles = new byte[src.length - startOffset];
                System.arraycopy(src, startOffset, newTiles, 0, newTiles.length);
                src = newTiles;
            }

            ChunkManager chunkManager = new ChunkManager();
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
                        byte leftPixel = (byte) (srcPixelPair & 0xF);
                        byte rightPixel = (byte) ((srcPixelPair >> 4) & 0xF);

//                        image.setPixelValue(destX, destY, i % 127);
//                        image.setPixelValue(destX + 1, destY, i % 127);
                        image.setPixelValue(destX, destY, leftPixel);
                        image.setPixelValue(destX + 1, destY, rightPixel);
//                        testImage(image);
                    }
                }

                chunkManager.advanceTilePosition(chunksWide, image.colsPerChunk, image.rowsPerChunk);
            }
        }

        private static void convertFromTiles8Bpp(byte[] src, IndexedImage image, int numTiles, int chunksWide, int colsPerChunk, int rowsPerChunk)
        {
            ChunkManager chunkManager = new ChunkManager();
            int pitch = (chunksWide * colsPerChunk) * 4;

            byte[] dest = new byte[image.height * image.width];
            int idx = 0;
            for (int i = 0; i < numTiles; i++)
            {
                for (int j = 0; j < 8; j++)
                {
                    int destY = (chunkManager.chunkStartY * rowsPerChunk + chunkManager.rowsSoFar) * 8 + j;

                    for (int k = 0; k < 8; k++)
                    {
                        int destX = (chunkManager.chunkStartX * colsPerChunk + chunkManager.tilesSoFar) * 4 + k;
                        byte srcPixel = src[idx++];

                        dest[destY * pitch + destX] = srcPixel;
                    }
                }

                chunkManager.advanceTilePosition(chunksWide, colsPerChunk, rowsPerChunk);
            }

            idx = 0;
            byte[][] pixels = new byte[image.height][image.width];
            for (int row = 0; row < image.height; row++)
            {
                for (int col = 0; col < image.width; col++)
                {
                    pixels[row][col] = dest[idx++];
                }
            }
            image.setPixels(pixels);
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

        //todo convertToScanned8Bpp()

        protected static byte[] convertToTiles4Bpp(IndexedImage image, int numTiles, int chunksWide)
        {
            ChunkManager chunkManager = new ChunkManager();
            int pitch = (chunksWide * image.colsPerChunk) * 4;

            byte[] src = new byte[image.height * image.width];
            int idx = 0;
            for (int row = 0; row < image.height; row++)
            {
                for (int col = 0; col < image.width; col++)
                {
                    src[idx++] = image.pixels[row][col];
                }
            }

            byte[] dest = new byte[src.length / 2];
            idx = 0;
            for (int i = 0; i < numTiles; i++) {
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

        protected static void convertFromTiles4BppAlternate(byte[] src, IndexedImage image, int startOffset)
        {
            if (startOffset != 0)
            {
                byte[] newTiles = new byte[src.length - startOffset];
                System.arraycopy(src, startOffset, newTiles, 0, newTiles.length);
                src = newTiles;
            }


            int bitDepth = 4;

            byte[] tilePal = new byte[src.length * (8 / bitDepth)];
//            if (tilesHeight < 8)
//                tilesHeight = 8;
            byte[] img_tiles = NcgrUtils.linealToHorizontal(src, image.width, image.height, bitDepth, 8);
            tilePal = NcgrUtils.linealToHorizontal(tilePal, image.width, image.height, 8, 8);

//            System.out.println(src.length);
//            for (int i = 0; i < img_tiles.length; i++)
//            {
//                if (img_tiles[i] != 0)
//                    System.out.println(i);
//            }

            byte[] output = new byte[image.height * image.width];

            byte[][] pixels = new byte[image.height][image.width];

            int pos = 0;
            for (int row= 0; row < image.height; row++)
            {
                for (int col= 0; col < image.width; col++)
                {
                    int num_pal = 0;
                    if(tilePal.length > col + row * image.width)
                    {
                        num_pal = tilePal[col + row * image.width];
                    }

                    if(num_pal >= image.palette.getNumColors())
                    {
                        num_pal = 0;
                    }

                    int colorIdx = getColor(img_tiles, image.palette.getNumColors(), pos++);

//                    output[row * image.width + col] = (byte) colorIdx;
                    pixels[row][col] = (byte) colorIdx;
                }
            }

            image.pixels = pixels;
            image.update = true;
        }

        private static byte[] linealToHorizontal(byte[] lineal, int width, int height, int bpp, int tile_size)
        {
            byte[] horizontal = new byte[lineal.length];
            int tile_width = tile_size * bpp / 8;   // Calculate the number of byte per line in the tile
            // pixels per line * bits per pixel / 8 bits per byte
            int tilesX = width / tile_size;
            int tilesY = height / tile_size;

            int pos = 0;
            for (int ht = 0; ht < tilesY; ht++)
            {
                for (int wt = 0; wt < tilesX; wt++)
                {
                    // Get the tile data
                    for (int h = 0; h < tile_size; h++)
                    {
                        for (int w = 0; w < tile_width; w++)
                        {
                            final int value = (w + h * tile_width * tilesX) + wt * tile_width + ht * tilesX * tile_size * tile_width;
                            if (value >= lineal.length)
                                continue;
                            if (pos >= lineal.length)
                                continue;

                            horizontal[value] = lineal[pos++];
                        }
                    }
                }
            }

            return horizontal;
        }

        private static int getColor(byte[] data, int paletteLength, int pos)
        {
            int color = 0;
            int alpha, index;

            if (data.length <= (pos / 2))
                return color;
            int bit4 = data[pos / 2] & 0xff;
            index = byteToBit4(bit4)[pos % 2];
            if (paletteLength > index)
                color = index;

            return color;
        }

        public static byte[] byteToBit4(int data)
        {
            byte[] bit4 = new byte[2];

            bit4[0] = (byte)(data & 0x0F);
            bit4[1] = (byte)((data & 0xF0) >> 4);

            return bit4;
        }

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

        private static void prepareImageTest(IndexedImage image)
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
        private static Color[] testColors = {Color.MAGENTA, Color.CYAN, Color.RED, Color.YELLOW, Color.PINK, Color.ORANGE};

        protected static void convertOffsetToCoordinate(int startByte, int numPixels, IndexedImage image, int numTiles, int chunksWide, int colsPerChunk, int rowsPerChunk)
        {
            ChunkManager chunkManager = new ChunkManager();
            int pitch = (chunksWide * colsPerChunk) * 4;


            image.palette.setColor(120 + (called % testColors.length), testColors[(called % testColors.length)]);

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
                        if (idx >= startByte && numCounted < numPixels && called != 0)
                        {
                            numCounted += 2;
                            image.setPixelValue(destX, destY, 120 + (called % testColors.length));
                            image.setPixelValue(destX + 1, destY, 120 + (called % testColors.length));
                            testImage(image);
                            try
                            {
                                Thread.sleep(100);
                            }
                            catch(InterruptedException e)
                            {
                                e.printStackTrace();
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
        int imageDataIdx = 0;

        for (int i = 0; i < fileContents.length - 4; i++)
        {
            byte[] thisFour = Arrays.copyOfRange(fileContents,i,i+4);

            if (Arrays.equals(thisFour,paletteChunkHeader))
            {
                paletteIdx = i;
            }

            if (Arrays.equals(thisFour,dataChunkHeader))
            {
                imageDataIdx = i;
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

        buffer.skipTo(imageDataIdx-4);
        chunkLength = swapEndianness(buffer.readInt());
        buffer.skipBytes(4);

        byte[] imageData = buffer.readBytes(chunkLength);
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

        if (palette.size() > 16)
        {
            bitDepth = 8;
        }
        else if (palette.size() > 4)
        {
            bitDepth = 4;
        }
        else
        {
            bitDepth = 2;
        }

        int colorType = 3;
        int compressionMethod = 0;
        int filterMethod = 0;
        int interlaceMethod = 0;

        writer.writeInt(swapEndianness(width));
        writer.writeInt(swapEndianness(height));
        writer.writeBytes(bitDepth,colorType,compressionMethod,filterMethod,interlaceMethod);

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

        byte[] imageData = PngUtils.convertScanlines(pixels,bitDepth,filterMethod);
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

        private static byte[] convertScanlines(byte[][] table, int bitDepth, int filterMethod)
        {
            ArrayList<Byte> retList = new ArrayList<>();

            for (byte[] scanline : table)
            {
                if(scanline.length % 2 != 0)
                    scanline = Arrays.copyOf(scanline,scanline.length+1);

                retList.add((byte) filterMethod);

                for (int x = 0; x < scanline.length; x+= 2)
                {
                    switch (bitDepth)
                    {
                        case 2:
                            retList.add((byte) ( ( (scanline[x] << 2) | (scanline[x + 1] & 0x3) ) << 4) );
                            break;

                        case 4:
                            retList.add((byte) ( (scanline[x] << 4) | (scanline[x + 1] & 0xf) ) );
                            break;

                        case 8:
                            retList.add(scanline[x]);
                            x -= 1;
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

        private static byte[][] createScanlines(byte[] arr, int bitDepth, int filterMethod,int width, int height)
        {
            byte[][] ret = new byte[height][width];
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
                            int section = (b >> 4) & 0xf;
                            byteList.add((byte) ((section >> 2) & 0x3));
                            byteList.add((byte) (section & 0x3));
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


                scanline = new byte[byteList.size()];
                for (int x = 0; x < byteList.size(); x++)
                {
                    scanline[x] = byteList.get(x);
                }

                ret[i] = scanline;

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
