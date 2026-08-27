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

    // The raw 16-bit BGR555 values as read from a file, kept so an unedited colour round-trips byte-for-byte.
    // BGR555 only uses 15 bits, but retail palettes set the unused bit 15 and java.awt.Color cannot carry it,
    // so without this a plain load/save would clear it (0xFFFF -> 0x7FFF). Null for palettes not read from a file;
    // an individual entry of NO_SOURCE means that slot has been edited/replaced and must not restore an old bit 15.
    // Kept in sync by every method that mutates colors (setColor/setColors/setNumColors) and copied by copyOf.
    private int[] sourceColors;
    private static final int NO_SOURCE = -1;

    /**
     * Parses an NCLR file and returns a <code>Palette</code> representation of it
     * @param data a <code>byte[]</code> containing a binary representation of an NCLR file
     * @param bitDepth an <code>int</code> containing a bit-depth value to enforce (use <code>0</code> if you don't have one)
     */
    public Palette(byte[] data, int bitDepth)
    {
        super("RLCN", "RPCN");

        MemBuf dataBuf = MemBuf.create(data);
        MemBuf.MemBufReader reader = dataBuf.reader();
        int fileSize = dataBuf.writer().getPosition();

        readGenericNtrHeader(reader);

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

        this.numColors = numColors;
        colors = new Color[numColors];
        this.bitDepth = bitDepth;
        this.compNum = compNum;

        sourceColors = new int[numColors];
        reader.setPosition(0x18 + colorStartOffset);
        for (int i = 0; i < numColors; i++)
        {
            int lo = reader.readByte() & 0xff;
            int hi = reader.readByte() & 0xff;
            sourceColors[i] = (hi << 8) | lo;
            colors[i] = NclrUtils.bgr555ToColor((byte) lo, (byte) hi);
        }

        if (numColors > 0 && colors[numColors - 1].equals(NclrUtils.irColor)) //honestly no clue why this is a thing
        {
            this.ir = true;
        }
    }

    /**
     * Parses an NCLR file on disk and returns a <code>Palette</code> representation of it
     * @param file a <code>File</code> containing a path to an NCLR file on disk
     * @param bitDepth an <code>int</code> containing a bit-depth value to enforce (use <code>0</code> if you don't have one)
     * @return a <code>Palette</code> representation of the specified NCLR file
     */
    public static Palette fromFile(File file, int bitDepth)
    {
        return fromFile(file.getAbsolutePath(), bitDepth);
    }

    /**
     * Parses an NCLR file on disk and returns a <code>Palette</code> representation of it
     * @param file a <code>String</code> containing a path to an NCLR file on disk
     * @param bitDepth an <code>int</code> containing a bit-depth value to enforce (use <code>0</code> if you don't have one)
     * @return a <code>Palette</code> representation of the specified NCLR file
     */
    public static Palette fromFile(String file, int bitDepth)
    {
        return new Palette(Buffer.readFile(file), bitDepth);
    }



    /**
     * Creates a default grayscale palette with the given number of colors
     * @param numColors an <code>int</code>
     */
    public Palette(int numColors)
    {
        super("RLCN", "RPCN");
        if (numColors <= 0)
            throw new RuntimeException(String.format("%d was provided as a palette size, but it must be positive.", numColors));
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
            Color[] arr2 = new Color[arr.length + (16 - arr.length % 16)];
            Arrays.fill(arr2, Color.black);
            System.arraycopy(arr, 0, arr2, 0, arr.length);
            arr = arr2;
        }

        numColors = arr.length;
        colors = arr;
    }


    /**
     * Generate a <code>byte[]</code> representation of this <code>Palette</code> as an NCLR
     * @return a <code>byte[]</code>
     */
    public byte[] save()
    {
        MemBuf dataBuf = MemBuf.create();
        MemBuf.MemBufWriter writer = dataBuf.writer();

        int numColors = colors.length;

        int size = numColors * 2; // two bytes per color
        // 0x18 for both magics: NclrUtils.palHeader is the same 24 bytes either way and the
        // colour data starts at 0x28 regardless, so an RPCN file used to declare a size eight
        // bytes short of itself. Nothing in this library reads the field back, which is why it
        // went unnoticed, but it is what an external tool would trust.
        int extSize = size + 0x18 + NTR_HEADER_SIZE;

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

        for (int i = 0; i < colors.length; i++) {
            byte[] packed = NclrUtils.colorToBGR555(colors[i]);
            int value = (packed[0] & 0xff) | ((packed[1] & 0xff) << 8);
            // If this colour is unchanged since it was read (its repacked 15 bits still match the source),
            // re-emit the original 16-bit value so bit 15 is preserved. An edited colour won't match and
            // falls through to the freshly packed value (bit 15 = 0), which is correct for new data.
            if (sourceColors != null && i < sourceColors.length && sourceColors[i] != NO_SOURCE && (sourceColors[i] & 0x7FFF) == (value & 0x7FFF))
                value = sourceColors[i];
            writer.writeByte((byte) (value & 0xff));
            writer.writeByte((byte) ((value >> 8) & 0xff));
        }

        return dataBuf.reader().getBuffer();
    }

    /**
     * Exports an NCLR file to disk from this <code>Palette</code>
     * @param file a <code>File</code> containing the path to the target file on disk
     * @throws IOException if an I/O error occurs
     */
    public void saveToFile(File file) throws IOException
    {
        BinaryWriter.writeFile(file, save());
    }

    /**
     * Exports an NCLR file to disk from this <code>Palette</code>
     * @param file a <code>String</code> containing the path to the target file on disk
     * @throws IOException if an I/O error occurs
     */
    public void saveToFile(String file) throws IOException
    {
        BinaryWriter.writeFile(file, save());
    }




    /**
     * Returns a <code>Color[]</code> containing this <code>Palette</code>'s colors
     * @return a <code>Color[]</code>
     */
    public Color[] getColors()
    {
        return Arrays.copyOf(colors, colors.length);
    }

    /**
     * Sets this <code>Palette</code>'s internal <code>Color[]</code>
     * @param colors a <code>Color[]</code>
     */
    public void setColors(Color[] colors)
    {
        this.colors = colors;
        // The whole array was replaced with caller-supplied colours that have no relation to the file that
        // was read, so drop the source values - none of them may restore a stale bit 15.
        this.sourceColors = null;
    }

    /**
     * Gets the <code>Color</code> at index <code>i</code> in the palette.
     * @param i an <code>int</code>
     * @return a <code>Color</code>
     * @exception RuntimeException if the specified index does not exist
     */
    public Color getColor(int i)
    {
        if (i < 0 || i >= colors.length)
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
        if (i < 0 || i >= colors.length)
            throw new RuntimeException("Invalid index: " + i);
        colors[i] = color;
        // This slot has been edited, so its original 16-bit value no longer applies - a subsequent save must
        // pack the new colour fresh (bit 15 = 0) rather than restore the byte that used to be here.
        if (sourceColors != null && i < sourceColors.length)
            sourceColors[i] = NO_SOURCE;
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
        System.arraycopy(this.colors, 0, colors, 0, Math.min(this.colors.length, numColors));
        // Growing a palette leaves the new entries null, and a null reaches colorToBGR555 on
        // save as a NullPointerException from inside the writer. Black is the conventional
        // filler and is what an unused palette slot holds in practice.
        for (int i = this.colors.length; i < numColors; i++)
            colors[i] = java.awt.Color.BLACK;
        this.colors = colors;
        // Keep the source values aligned with the resized array: preserve the surviving prefix (so unedited
        // colours still round-trip) and mark any newly-added slots as having no source.
        if (sourceColors != null)
        {
            int[] resized = new int[numColors];
            Arrays.fill(resized, NO_SOURCE);
            System.arraycopy(sourceColors, 0, resized, 0, Math.min(sourceColors.length, numColors));
            sourceColors = resized;
        }
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
        p.numColors = numColors;
        p.bitDepth = bitDepth;
        p.compNum = compNum;
        p.ir = ir;

        int idx = 0;
        for (Color c : colors)
        {
            p.colors[idx++] = new Color(c.getRGB());
        }
        // Carry the source values so a copied palette still round-trips bit 15 (the copied colours are
        // identical, so the same restoration applies). Without this, copy-then-save re-clears bit 15.
        p.sourceColors = (sourceColors != null ? sourceColors.clone() : null);

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
        // Deliberately not comparing sourceColors: two palettes with the same colours are equal even if one was
        // read from a file (and so carries the unused BGR555 bit 15) and the other was built in memory. Bit 15
        // is ignored by the DS 2D engine, so it is below the granularity of colour equality; it only affects the
        // exact bytes save() emits. copyOf carries sourceColors, so a copy still both equals and serialises like its original.
        return numColors == palette.numColors && bitDepth == palette.bitDepth && compNum == palette.compNum && ir == palette.ir && Arrays.equals(colors, palette.colors);
    }

    @Override
    public int hashCode()
    {
        int result = Objects.hash(numColors, bitDepth, compNum, ir);
        result = 31 * result + Arrays.hashCode(colors);
        return result;
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
     * @throws RuntimeException if the provided file is not a PNG, or if it is not indexed
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
     * @throws RuntimeException if the provided file is not a PNG, or if it is not indexed
     */
    public static Palette fromIndexedPngFile(String file) throws IOException
    {
        return IndexedImage.fromIndexedPngFile(file).getPalette();
    }

    /**
     * Generate a <code>byte[]</code> representation of this <code>Palette</code> as an indexed PNG
     * @return a <code>byte[]</code>
     * @throws IOException if an I/O error occurs
     */
    public byte[] saveAsIndexedPng() throws IOException
    {
        // ceil, not floor: a palette that does not fill a whole row still needs that row. And
        // IndexedImage requires a height that is a multiple of 8, so the row count is rounded
        // up to one - which is why this used to work only for 128 and 256 colours and threw for
        // every other size, including the 16 colour palette that is the norm for 4bpp graphics.
        int rows = (numColors + 15) / 16;
        int paddedRows = ((rows + 7) / 8) * 8;
        IndexedImage image = new IndexedImage(paddedRows, 16, bitDepth, this);

        int idx = 0;
        int row;
        int col;
        for (row = 0; row < numColors / 16; row++)
        {
            for (col = 0; col < 16; col++)
            {
                image.setPixelValue(col, row, idx++);
            }
        }

        if (numColors % 16 != 0)
        {
            for (col = 0; col < numColors % 16; col++)
            {
                image.setPixelValue(col, row, idx++);
            }
        }

        return image.saveAsIndexedPng();
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

    /* END SECTION: PNG */

    public static final Palette defaultPalette = new Palette(256);
}
