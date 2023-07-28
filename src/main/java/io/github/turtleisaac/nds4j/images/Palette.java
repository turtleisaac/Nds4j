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

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * An object representation of an NCLR file
 */
public class Palette extends GenericNtrFile
{
    private Color[] colors;
    private int numColors;
    private int bitDepth;
    private int compNum = 0;
    private boolean ir = false;

    /**
     * Creates a default grayscale palette with the given number of colors
     * @param numColors an <code>int</code>
     */
    public Palette(int numColors)
    {
        super("RLCN", "RPCN");
        this.numColors = numColors;
        colors = new Color[numColors];
        for (int i = 0; i < colors.length; i++)
        {
            colors[i] = new Color((i*8) % 256, (i*8) % 256, (i*8) % 256);
        }
    }

    /**
     * Creates a palette from a given <code>Color[]</code>.
     * @param arr a <code>Color[]</code>
     *      <b>NOTE:</b> The bit depth of the palette will be 4 if the <code>Color[]</code>'s length is than or equal to 16, and 8 otherwise.
     *
     *      <b>NOTE:</b> The length of the <code>Color[]</code> can't be higher than 256, and must be a multiple of 16
     */
    public Palette(Color[] arr)
    {
        super("RLCN", "RPCN");
        if (arr.length > 256)
            throw new RuntimeException(String.format("Not a valid Color[]: %d is greater than 256.", arr.length));
        if (arr.length % 16 != 0)
        {
            Color[] arr2 = new Color[arr.length + arr.length % 16];
            Arrays.fill(arr2, Color.black);
            System.arraycopy(arr, 0, arr2, 0, arr.length);
            arr = arr2;
        }

        numColors = arr.length;
        colors = arr;
    }

    /**
     * Returns a <code>Color[]</code> containing this <code>Palette</code>'s colors
     * @return a <code>Color[]</code>
     */
    public Color[] getColors()
    {
        return colors;
    }

    /**
     * Sets this <code>Palette</code>'s internal <code>Color[]</code>
     * @param colors a <code>Color[]</code>
     */
    public void setColors(Color[] colors)
    {
        this.colors = colors;
    }

    /**
     * Gets the <code>Color</code> at index <code>i</code> in the palette.
     * @param i an <code>int</code>
     * @return a <code>Color</code>
     * @exception RuntimeException if the specified index does not exist
     */
    public Color getColor(int i)
    {
        if (i >= colors.length)
            throw new RuntimeException("Invalid index: " + i);
        return colors[i];
    }

    /**
     * Sets the <code>Color</code> at index <code>i</code> in the palette to the provided <code>Color</code>
     * @param i an <code>int</code>
     * @param color  a <code>Color</code>
     * @exception RuntimeException if the specified index does not exist
     */
    public void setColor(int i, Color color)
    {
        if (i >= colors.length)
            throw new RuntimeException("Invalid index: " + i);
        colors[i] = color;
    }

    /**
     * Gets the <code>Color</code> at index <code>i</code> in the specified sub-palette <code>palIndex</code> in the palette.
     * @param i an <code>int</code>
     * @param palIndex an <code>int</code>
     * @return a <code>Color</code>
     * @exception RuntimeException if the specified index does not exist
     */
    public Color getColor(int i, int palIndex)
    {
        i += 16*palIndex;
        return getColor(i);
    }

    /**
     * Sets the <code>Color</code> at index <code>i</code> in the specified sub-palette <code>palIndex</code> in the palette to the provided <code>Color</code>
     * @param i an <code>int</code>
     * @param palIndex an <code>int</code>
     * @param color a <code>Color</code>
     * @exception RuntimeException if the specified index does not exist
     */
    public void setColor(int i, int palIndex, Color color)
    {
        i += 16*palIndex;
        setColor(i, color);
    }

    /**
     * Gets this <code>Palette</code>'s number of colors
     * @return an <code>int</code>
     */
    public int getNumColors()
    {
        return numColors;
    }

    /**
     * Sets this <code>Palette</code>'s number of colors
     * (multiples of <code>16</code> only)
     * @param numColors an <code>int</code>
     */
    public void setNumColors(int numColors)
    {
        if (numColors % 16 != 0)
            throw new RuntimeException(String.format("Invalid number of colors: %d is not a multiple of 16", numColors));
        this.numColors = numColors;
        Color[] colors = new Color[numColors];
        System.arraycopy(this.colors, 0, colors, 0, numColors);
        this.colors = colors;
    }

    /**
     * Gets this <code>Palette</code>'s bit depth
     * @return an <code>int</code>
     */
    public int getBitDepth()
    {
        return bitDepth;
    }

    /**
     * Sets this <code>Palette</code>'s bit depth value
     * (<code>4</code> or <code>8</code> only)
     * @param bitDepth an <code>int</code>
     */
    public void setBitDepth(int bitDepth)
    {
        if (bitDepth != 4 && bitDepth != 8)
            throw new RuntimeException("Not a valid bit depth");

        this.bitDepth = bitDepth;
    }

    /**
     * Gets the number of colors in this palette
     * @return an <code>int</code>
     */
    public int size()
    {
        return colors.length;
    }

    /**
     * Creates a copy of this <code>Palette</code>
     * @return a <code>Palette</code>
     */
    public Palette copyOf()
    {
        Palette p = new Palette(numColors);
        p.colors = Arrays.copyOf(colors, colors.length);
        return p;
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
        Palette palette = (Palette) o;
        return numColors == palette.numColors && bitDepth == palette.bitDepth && compNum == palette.compNum && ir == palette.ir && Arrays.equals(colors, palette.colors);
    }

    @Override
    public int hashCode()
    {
        int result = Objects.hash(numColors, bitDepth, compNum, ir);
        result = 31 * result + Arrays.hashCode(colors);
        return result;
    }

    /* BEGIN SECTION: NCLR */

    /**
     * Parses an NCLR file on disk and returns a <code>Palette</code> representation of it
     * @param file a <code>File</code> containing a path to an NCLR file on disk
     * @param bitDepth an <code>int</code> containing a bit-depth value to enforce (use <code>0</code> if you don't have one)
     * @return a <code>Palette</code> representation of the specified NCLR file
     */
    public static Palette fromNclrFile(File file, int bitDepth)
    {
        return fromNclrFile(file.getAbsolutePath(), bitDepth);
    }

    /**
     * Parses an NCLR file on disk and returns a <code>Palette</code> representation of it
     * @param file a <code>String</code> containing a path to an NCLR file on disk
     * @param bitDepth an <code>int</code> containing a bit-depth value to enforce (use <code>0</code> if you don't have one)
     * @return a <code>Palette</code> representation of the specified NCLR file
     */
    public static Palette fromNclrFile(String file, int bitDepth)
    {
        return fromNclr(Buffer.readFile(file), bitDepth);
    }

    /**
     * Parses an NCLR file and returns a <code>Palette</code> representation of it
     * @param data a <code>byte[]</code> containing a binary representation of an NCLR file
     * @param bitDepth an <code>int</code> containing a bit-depth value to enforce (use <code>0</code> if you don't have one)
     * @return a <code>Palette</code> representation of the provided NCLR file
     */
    public static Palette fromNclr(byte[] data, int bitDepth)
    {
        MemBuf dataBuf = MemBuf.create(data);
        MemBuf.MemBufReader reader = dataBuf.reader();
        int fileSize = dataBuf.writer().getPosition();

        GenericNtrFile tempData = new GenericNtrFile("RLCN", "RPCN");
        tempData.readGenericNtrHeader(reader);

        // reader position is now 0x10

        //palette data
        String paletteMagic = reader.readString(4);

        if (!paletteMagic.equals("TTLP")) {
            throw new RuntimeException("Not a valid NCLR or NCPR file.");
        }

        if ((fileSize - 0x28) % 2 != 0)
            throw new RuntimeException(String.format("The file size (%d) is not a multiple of 2.\n", fileSize));

        long paletteSectionSize = reader.readUInt32();

        if (bitDepth == 0)
        {
            bitDepth = reader.readUInt16() == 3 ? 4 : 8;  //4bpp if == 3, 8bpp if == 4
        }
        else
        {
            reader.skip(2);
        }

        int compNum = reader.readByte();
        reader.skip(1);


        int paletteUnknown1 = reader.readInt();
        long paletteLength= reader.readUInt32();

        if(paletteLength == 0 || paletteLength > paletteSectionSize)
            paletteLength= paletteSectionSize - 0x18;

        long colorStartOffset= reader.readUInt32();

        int numColors = 256;

        if (paletteLength / 2 < numColors)
            numColors = (int) (paletteLength / 2);

        Palette palette = new Palette(numColors);
        palette.copyValuesFromTemp(tempData);
        palette.bitDepth = bitDepth;
        palette.compNum = compNum;

        reader.setPosition(0x18 + colorStartOffset);
        for (int i = 0; i < paletteLength / 2; i++)
        {
            palette.setColor(i, NclrUtils.bgr555ToColor((byte) reader.readByte(), (byte) reader.readByte()));
        }

        if (palette.getColor((int) (paletteLength / 2) - 1).equals(NclrUtils.irColor)) //honestly no clue why this is a thing
        {
            palette.ir = true;
        }

        return palette;
    }

    /**
     * Exports an NCLR file to disk from this <code>Palette</code>
     * @param file a <code>File</code> containing the path to the target file on disk
     * @throws IOException if an I/O error occurs
     */
    public void saveToNclrFile(File file) throws IOException
    {
        BinaryWriter.writeFile(file, saveAsNclr());
    }

    /**
     * Exports an NCLR file to disk from this <code>Palette</code>
     * @param file a <code>String</code> containing the path to the target file on disk
     * @throws IOException if an I/O error occurs
     */
    public void saveToNclrFile(String file) throws IOException
    {
        BinaryWriter.writeFile(file, saveAsNclr());
    }

    /**
     * Generate a <code>byte[]</code> representation of this <code>Palette</code> as an NCLR
     * @return a <code>byte[]</code>
     */
    public byte[] saveAsNclr()
    {
        MemBuf dataBuf = MemBuf.create();
        MemBuf.MemBufWriter writer = dataBuf.writer();

        int numColors = colors.length;

        int size = numColors * 2; // two bytes per color
        int extSize = size + (whichMagic == 1 ? 0x10 : 0x18) + NTR_HEADER_SIZE;

        writeGenericNtrHeader(writer, extSize, 1);

        // writer position is now 0x10

        writer.write(NclrUtils.palHeader);
        int storedPos = writer.getPosition();

        writer.setPosition(NTR_HEADER_SIZE + 4);
        writer.writeInt(extSize - NTR_HEADER_SIZE); // 0x14

        if (bitDepth <= 0)
            bitDepth = 4;

        writer.writeShort((short) (bitDepth == 4 ? 0x3 : 0x4)); // 0x18
        writer.writeByte((byte) (compNum)); // 0x1A

        writer.setPosition(NTR_HEADER_SIZE + 0x10);
        writer.writeInt(size);

        writer.setPosition(storedPos);

        for(Color color : colors) {
            writer.write(NclrUtils.colorToBGR555(color));
        }

        return dataBuf.reader().getBuffer();
    }

    private static class NclrUtils {
        protected static final byte[] palHeader = new byte[] {
            0x54, 0x54, 0x4C, 0x50, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00
        };

        protected static final Color irColor = new Color(72, 144, 160);

        private static Color bgr555ToColor(byte byte1, byte byte2)
        {
            int r, b, g;

            int bgr = ((byte2 & 0xff) << 8) | (byte1 & 0xff);

            r = (bgr & 0x001F) << 3;
            g = ((bgr & 0x03E0) >> 2);
            b = ((bgr & 0x7C00) >> 7);

            return new Color(r, g, b);
        }

        public static byte[] colorToBGR555(Color color)
        {
            byte[] d = new byte[2];

            int r = color.getRed() / 8;
            int g = (color.getGreen() / 8) << 5;
            int b = (color.getBlue() / 8) << 10;

            int bgr= r + g + b;

            d[0] = (byte) (bgr & 0xff);
            d[1] = (byte) ((bgr >> 8) & 0xff);

            return d;
        }
    }

    /* BEGIN SECTION: PNG */

    /**
     * Parses an indexed PNG file on disk and creates a <code>Palette</code> representation of its palette
     * @param file a <code>File</code> containing a path to an indexed PNG file on disk
     * @return a <code>Palette</code> matching that of the original indexed PNG file
     * @throws IOException if the parent directory of the specified target input file does not exist
     * @exception IndexedImage.PngUtils.PngParseException can occur if the provided file is not a PNG, or if it is not indexed
     */
    public static Palette fromIndexedPngFile(File file) throws IOException
    {
        return fromIndexedPngFile(file.getAbsolutePath());
    }

    /**
     * Parses an indexed PNG file on disk and creates a <code>Palette</code> representation of its palette
     * @param file a <code>String</code> containing a path to an indexed PNG file on disk
     * @return a <code>Palette</code> matching that of the original indexed PNG file
     * @throws IOException if the parent directory of the specified target input file does not exist
     * @exception IndexedImage.PngUtils.PngParseException can occur if the provided file is not a PNG, or if it is not indexed
     */
    public static Palette fromIndexedPngFile(String file) throws IOException
    {
        return IndexedImage.fromIndexedPngFile(file).getPalette();
    }

    /**
     * Exports an indexed PNG file to disk from this <code>Palette</code>
     * @param file a <code>File</code> containing the path to the target file on disk
     * @throws IOException if an I/O error occurs
     */
    public void saveToIndexedPngFile(File file) throws IOException
    {
        saveToIndexedPngFile(file.getAbsolutePath());
    }

    /**
     * Exports an indexed PNG file to disk from this <code>Palette</code>
     * @param file a <code>String</code> containing the path to the target file on disk
     * @throws IOException if an I/O error occurs
     */
    public void saveToIndexedPngFile(String file) throws IOException
    {
        BinaryWriter.writeFile(file, saveAsIndexedPng());
    }

    /**
     * Generate a <code>byte[]</code> representation of this <code>Palette</code> as an indexed PNG
     * @return a <code>byte[]</code>
     * @throws IOException if an I/O error occurs
     */
    public byte[] saveAsIndexedPng() throws IOException
    {
        IndexedImage image = new IndexedImage(numColors / 16, 16, bitDepth, this);

        int idx = 0;
        int row;
        int col;
        for (row = 0; row < numColors / 16; row++)
        {
            for (col = 0; col < 16; col++)
            {
                image.setCoordinateValue(col, row, idx++);
            }
        }

        if (numColors % 16 != 0)
        {
            for (col = 0; col < numColors % 16; col++)
            {
                image.setCoordinateValue(col, row, idx++);
            }
        }

        return image.saveAsIndexedPng();
    }

    /* END SECTION: PNG */
}
