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

public class IndexedImage extends GenericNtrFile
{
    private byte[][] pixels;
    private Palette palette;

    private final int height;
    private final int width;

    private int bitDepth;
    private NcgrUtils.ScanMode scanMode;
    private int metatileWidth;
    private int metatileHeight;
    private int numTiles;
    private int mappingType;
    private boolean vram;
    private int encryptionKey = -1;

    private boolean sopc;

    private BufferedImage storedImage;
    boolean update;

    /**
     * Creates an <code>IndexedImage</code> of size 80x80
     * (this image will default to all pixels with a value of 0)
     * @param palette a <code>Color[]</code> containing the palette of the image
     */
    public IndexedImage(Palette palette)
    {
        super("RGCN");
        height = 80;
        width = 80;

        pixels = new byte[height][width];
        byte[] arr = new byte[width];
        Arrays.fill(arr, (byte) 0);
        Arrays.fill(pixels,arr);

        this.palette = palette;

        update = true;
    }

    public IndexedImage(int height, int width, int bitDepth, Palette palette)
    {
        super("RGCN");
        this.height = height;
        this.width = width;
        this.bitDepth = bitDepth;

        pixels = new byte[height][width];
        byte[] arr = new byte[width];
        Arrays.fill(arr, (byte) 0);
        Arrays.fill(pixels,arr);

        this.palette = palette;

        update = true;
    }

    /**
     * Creates an <code>IndexedImage</code> using a predetermined assignment of color to pixel and the palette itself
     * @param pixels a <code>byte[][]</code> representing the index in the palette to pull the color from for each pixel in the sprite
     * @param palette a <code>Color[]</code> of length 16 or less containing the colors to be used in the image
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

    private boolean allRowsHaveSameWidth(byte[][] indexGuide)
    {
        if (indexGuide.length == 0)
            return false;
        if (indexGuide.length == 1)
            return true;

        int width = indexGuide[0].length;
        for (int i = 1; i < indexGuide.length; i++)
        {
            if (indexGuide[i].length != width)
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
                    storedImage.setRGB(col,row,palette.getColor(getCoordinateValue(col, row)).getRGB());
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
                    ret.setRGB(col,row,palette.getColor(pixels[row][col]).getRGB());
            }
        }

        return ret;
    }

    //todo make it so this isn't restricted to 160x160

    /**
     * Creates a scaled <code>BufferedImage</code> using this <code>IndexedImage</code>
     * @return a scaled <code>BufferedImage</code> representation of this <code>IndexedImage</code>
     */
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

        BufferedImage resizedImage = new BufferedImage(160, 160, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics2D = resizedImage.createGraphics();
        graphics2D.drawImage(image, 0, 0, 160, 160, null);
        graphics2D.dispose();
        return resizedImage;
    }

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
     * Gets the palette index specified at coordinate (x,y) in this <code>IndexedImage</code>, where (0,0) is the top-left corner
     * @param x an <code>int</code> containing the column value
     * @param y an <code>int</code> containing the row value
     * @return an <code>int</code>
     */
    public int getCoordinateValue(int x, int y)
    {
        return pixels[y][x];
    }

    /**
     * Sets the palette index specified at coordinate (x,y) in this <code>IndexedImage</code>, where (0,0) is the top-left corner
     * @param x an <code>int</code> containing the column value
     * @param y an <code>int</code> containing the row value
     * @param colorIdx an <code>int</code> containing the color index in the palette
     */
    public void setCoordinateValue(int x, int y, int colorIdx)
    {
        pixels[y][x] = (byte) colorIdx;
    }

    public int getMetatileWidth()
    {
        return metatileWidth;
    }

    public void setMetatileWidth(int metatileWidth)
    {
        this.metatileWidth = metatileWidth;
    }

    public int getMetatileHeight()
    {
        return metatileHeight;
    }

    public void setMetatileHeight(int metatileHeight)
    {
        this.metatileHeight = metatileHeight;
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

        return bitDepth == image.bitDepth && metatileWidth == image.metatileWidth && metatileHeight == image.metatileHeight && numTiles == image.numTiles && mappingType == image.mappingType && vram == image.vram && encryptionKey == image.encryptionKey && sopc == image.sopc && palette.equals(image.palette) && scanMode == image.scanMode;
    }

    @Override
    public int hashCode()
    {
        int result = Objects.hash(palette, height, width, bitDepth, scanMode, metatileWidth, metatileHeight, numTiles, mappingType, vram, encryptionKey, sopc);
        result = 31 * result + Arrays.hashCode(pixels);
        return result;
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

        System.out.println("Bit Depth: " + bitDepth);
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

    /* BEGIN SECTION: NCGR */

    /**
     * Parses an NCGR file and returns an <code>IndexedImage</code> representation of it
     * @param data a <code>byte[]</code> containing a binary representation of an NCGR file
     * @param tilesWidth an <code>int</code> containing a tile width value to enforce (use <code>0</code> if you don't have one)
     * @param bitDepth an <code>int</code> containing a bit-depth value to enforce (use <code>0</code> if you don't have one)
     * @param metatileWidth an <code>int</code> containing a metatile width value to enforce (use <code>0</code> if you don't have one)
     * @param metatileHeight an <code>int</code> containing a metatile height value to enforce (use <code>0</code> if you don't have one)
     * @param scanFrontToBack
     * @return an <code>IndexedImage</code> representation of the provided NCLR file
     */
    public static IndexedImage fromNcgr(byte[] data, int tilesWidth, int bitDepth, int metatileWidth, int metatileHeight, boolean scanFrontToBack)
    {
        MemBuf dataBuf = MemBuf.create(data);
        MemBuf.MemBufReader reader = dataBuf.reader();
        int fileSize = dataBuf.writer().getPosition();

        GenericNtrFile tempData = new GenericNtrFile("RGCN");
        tempData.readGenericNtrHeader(reader);

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

        int numColors = 256;
        if (bitDepth == 4)
        {
            numColors = 16;
        }
        reader.skip(2);

        int mappingType = reader.readUInt16(); // 0x22

        boolean scanned = reader.readByte() == 1; // 0x24
        boolean vram = reader.readByte() == 1;
        reader.skip(2);
        int tileSize = bitDepth * 8;

        int numTiles = reader.readInt() / (64 / (8 / bitDepth)); // 0x28

        if (tilesHeight < 0)
            tilesHeight = (numTiles + tilesWidth - 1) / tilesWidth;

        if (tilesWidth % metatileWidth != 0)
            throw new RuntimeException(String.format("The width in tiles (%d) isn't a multiple of the specified metatile width (%d)", tilesWidth, metatileWidth));

        if (tilesHeight % metatileHeight != 0)
            throw new RuntimeException(String.format("The height in tiles (%d) isn't a multiple of the specified metatile height (%d)", tilesHeight, metatileHeight));

        IndexedImage image = new IndexedImage(tilesHeight * 8, tilesWidth * 8, bitDepth, new Palette(numColors));
        image.scanMode = NcgrUtils.ScanMode.getMode(scanned, scanFrontToBack);
        image.metatileHeight = metatileHeight;
        image.metatileWidth = metatileWidth;
        image.numTiles = numTiles;
        image.mappingType = mappingType;
        image.vram = vram;
        image.copyValuesFromTemp(tempData);
        image.sopc = image.numBlocks == 2;

        int metatilesWide = tilesWidth / metatileWidth;

        reader.setPosition(0x30);
        byte[] imageData = reader.getBuffer();

        int key;
        if (scanned)
        {
            switch (bitDepth)
            {
                case 4:
                    key = NcgrUtils.convertFromScanned4Bpp(imageData, image, tilesWidth * 8, tilesHeight * 8, scanFrontToBack);
                    image.setEncryptionKey(key);
                    break;
                case 8:
                    key = NcgrUtils.convertFromScanned8Bpp(imageData, image, tilesWidth * 8, tilesHeight * 8, scanFrontToBack);
                    image.setEncryptionKey(key);
            }
        }
        else
        {
            switch (bitDepth)
            {
                case 4:
                    NcgrUtils.convertFromTiles4Bpp(imageData, image, numTiles, metatilesWide, metatileWidth, metatileHeight);
                    break;
                case 8:
                    break;
            }
        }

        image.update = true;
        return image;
    }

    /**
     * Exports an NCGR file to disk from this <code>IndexedImage</code>
     * @param file a <code>File</code> containing the path to the target file on disk
     * @throws IOException if an I/O error occurs
     */
    public void saveToNcgrFile(File file) throws IOException
    {
        saveToNcgrFile(file.getAbsolutePath());
    }

    /**
     * Exports an NCGR file to disk from this <code>IndexedImage</code>
     * @param file a <code>String</code> containing the path to the target file on disk
     * @throws IOException if an I/O error occurs
     */
    public void saveToNcgrFile(String file) throws IOException
    {
        BinaryWriter.writeFile(file, saveAsNcgr());
    }

    /**
     * Generate a <code>byte[]</code> representation of this <code>IndexedImage</code> as an NCGR
     * @return a <code>byte[]</code>
     */
    public byte[] saveAsNcgr()
    {
        int tileSize = bitDepth * 8;

        if (width % 8 != 0)
            throw new RuntimeException(String.format("The width in pixels (%d) isn't a multiple of 8.", width));

        if (height % 8 != 0)
            throw new RuntimeException(String.format("The height in pixels (%d) isn't a multiple of 8.", height));

        int tilesWidth = width / 8;
        int tilesHeight = height / 8;

        if (tilesWidth % metatileWidth != 0)
            throw new RuntimeException(String.format("The width in tiles (%d) isn't a multiple of the specified metatile width (%d)", tilesWidth, metatileWidth));

        if (tilesHeight % metatileHeight != 0)
            throw new RuntimeException(String.format("The height in tiles (%d) isn't a multiple of the specified metatile height (%d)", tilesHeight, metatileHeight));

        int maxNumTiles = tilesWidth * tilesHeight;

        if (numTiles == 0)
            numTiles = maxNumTiles;
        else if (numTiles > maxNumTiles)
            throw new RuntimeException(String.format("The specified number of tiles (%d) is greater than the maximum possible value (%d).", numTiles, maxNumTiles));

        int bufferSize = numTiles * tileSize;
        MemBuf pixelsBuf = MemBuf.create();
        MemBuf.MemBufWriter writer = pixelsBuf.writer();

        int metatilesWide = tilesWidth / metatileWidth;

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
                    writer.write(NcgrUtils.convertToTiles4Bpp(this, numTiles, metatilesWide, metatileWidth, metatileHeight));
                    break;
                case 8:
//                    NcgrUtils.ConvertToTiles8Bpp(image->pixels, pixelBuffer, numTiles, metatilesWide, metatileWidth, metatileHeight,
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

    protected static class NcgrUtils {
        // reading ncgr code

        private static int convertFromScanned4Bpp(byte[] src, IndexedImage image, int width, int height, boolean scanFrontToBack)
        {
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

        private static int convertFromScanned8Bpp(byte[] src, IndexedImage image, int width, int height, boolean scanFrontToBack)
        {
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

        private static class MetatileManager {
            int subTileX = 0;
            int subTileY = 0;
            int metatileX = 0;
            int metatileY = 0;

            MetatileManager() {}

            void advanceMetatilePosition(int metatilesWide, int metatileWidth, int metatileHeight)
            {
                subTileX++;
                if (subTileX == metatileWidth)
                {
                    subTileX = 0;
                    subTileY++;
                    if (subTileY == metatileHeight)
                    {
                        subTileY = 0;
                        metatileX++;
                        if (metatileX == metatilesWide)
                        {
                            metatileX = 0;
                            metatileY++;
                        }
                    }
                }
            }
        }

        private static void convertFromTiles4Bpp(byte[] src, IndexedImage image, int numTiles, int metatilesWide, int metatileWidth, int metatileHeight)
        {
            MetatileManager metatileManager = new MetatileManager();
            int pitch = (metatilesWide * metatileWidth) * 4;

            byte[] dest = new byte[image.height * image.width];
            int idx = 0;
            for (int i = 0; i < numTiles; i++)
            {
                for (int j = 0; j < 8; j++)
                {
                    int destY = (metatileManager.metatileY * metatileHeight + metatileManager.subTileY) * 8 + j;

                    for (int k = 0; k < 4; k++)
                    {
                        int destX = (metatileManager.metatileX * metatileWidth + metatileManager.subTileX) * 4 + k;
                        byte srcPixelPair = src[idx++];
                        byte leftPixel = (byte) (srcPixelPair & 0xF);
                        byte rightPixel = (byte) ((srcPixelPair >> 4) & 0xF);

                        dest[2 * (destY * pitch + destX)] = leftPixel;
                        dest[2 * (destY * pitch + destX) + 1] = rightPixel;
                    }
                }

                metatileManager.advanceMetatilePosition(metatilesWide, metatileWidth, metatileHeight);
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

        private static void convertFromTiles8Bpp(byte[] src, IndexedImage image, int numTiles, int metatilesWide, int metatileWidth, int metatileHeight)
        {
            MetatileManager metatileManager = new MetatileManager();
            int pitch = (metatilesWide * metatileWidth) * 4;

            byte[] dest = new byte[image.height * image.width];
            int idx = 0;
            for (int i = 0; i < numTiles; i++)
            {
                for (int j = 0; j < 8; j++)
                {
                    int destY = (metatileManager.metatileY * metatileHeight + metatileManager.subTileY) * 8 + j;

                    for (int k = 0; k < 4; k++)
                    {
                        int destX = (metatileManager.metatileX * metatileWidth + metatileManager.subTileX) * 4 + k;
                        byte srcPixel = src[idx++];

                        dest[destY * pitch + destX] = srcPixel;
                    }
                }

                metatileManager.advanceMetatilePosition(metatilesWide, metatileWidth, metatileHeight);
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

        //todo not done with figuring out signature - method is incomplete
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
                arr[i] = (short) (( ((data[i * 2] & 0xF) << 4) | ((data[i * 2 + 1] & 0xF) << 4)) & 0xff);
//                arr[i] = (short) (( (data[i * 2] & 0xF) | ((data[i * 2 + 1] & 0xF) << 4)) & 0xff);
//                arr[i] = (short) (( (data[i * 4] & 0xF) | ((data[i * 4 + 1] & 0xF) << 4) | ((data[i * 4 + 2] & 0xF) << 8) | ((data[i * 4 + 3] & 0xF) << 12)) & 0xffff);
            }

            byte[] dest = new byte[bufferSize];
            if (image.scanMode == ScanMode.FRONT_TO_BACK)
            {
                for (int i = bufferSize - 1; i > 0; i -= 2)
                {
                    encValue = (encValue - 24691) * 4005161829L;
                    arr[i] ^= (encValue & 0xFFFF);
                    dest[i] = (byte) ((arr[i] >> 8) & 0xff);
                    dest[i - 1] = (byte) (arr[i] & 0xff);
                }
            }
            else if (image.scanMode == ScanMode.BACK_TO_FRONT)
            {
                for (int i = 1; i < bufferSize; i += 2)
                {
                    encValue = (int) ((encValue - 24691) * 4005161829L);
                    arr[i] ^= (encValue & 0xFFFF);
                    dest[i] = (byte) ((arr[i] >> 8) & 0xff);
                    dest[i - 1] = (byte) (arr[i] & 0xff);
                }
            }

            return dest;
        }

        //todo convertToScanned8Bpp()

        private static byte[] convertToTiles4Bpp(IndexedImage image, int numTiles, int metatilesWide, int metatileWidth, int metatileHeight)
        {
            MetatileManager metatileManager = new MetatileManager();
            int pitch = (metatilesWide * metatileWidth) * 4;

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
                    int srcY = (metatileManager.metatileY * metatileHeight + metatileManager.subTileY) * 8 + j;

                    for (int k = 0; k < 4; k++) {
                        int srcX = (metatileManager.metatileX * metatileWidth + metatileManager.subTileX) * 4 + k;
                        byte leftPixel = (byte) (src[2 * (srcY * pitch + srcX)] & 0xF);
                        byte rightPixel = (byte) (src[2 * (srcY * pitch + srcX) + 1] & 0xF);

                        dest[idx++] = (byte) (((rightPixel << 4) & 0xF0) | leftPixel);
                    }
                }

                metatileManager.advanceMetatilePosition(metatilesWide, metatileWidth, metatileHeight);
            }

            return dest;
        }

//        private static void convertFromTiles4Bpp(byte[] src, IndexedImage image)
//        {
//            int bitDepth = 4;
//            image.width = 32;
//            image.height = 64;
//
//            byte[] tilePal = new byte[src.length * (8 / bitDepth)];
////            if (tilesHeight < 8)
////                tilesHeight = 8;
//            byte[] img_tiles = NcgrUtils.linealToHorizontal(src, image.width, image.height, bitDepth, 8);
//            tilePal = NcgrUtils.linealToHorizontal(tilePal, image.width, image.height, 8, 8);
//
//            System.out.println(src.length);
//            for (int i = 0; i < img_tiles.length; i++)
//            {
//                if (img_tiles[i] != 0)
//                    System.out.println(i);
//            }
//
//            byte[] output = new byte[image.height * image.width];
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
//                    if(num_pal >= image.palette.length)
//                    {
//                        num_pal = 0;
//                    }
//
//                    int colorIdx = getColor(img_tiles, image.palette.length, pos++);
//
//                    if (colorIdx != 0)
//                        System.out.println("moo");
//
//                    output[row * image.width + col] = (byte) colorIdx;
//                }
//            }
//
//
//            image.original = output;
//            image.update = true;
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

    }

    /* END SECTION: NCGR */
}
