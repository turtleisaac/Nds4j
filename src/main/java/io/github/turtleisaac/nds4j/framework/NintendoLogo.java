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

package io.github.turtleisaac.nds4j.framework;

/** Codec for the 0x9C-byte Huffman-compressed logo bitmap in a ROM header (104x16, one bit per pixel). */
public final class NintendoLogo
{
    public static final int WIDTH = 104;
    public static final int HEIGHT = 16;
    public static final int COMPRESSED_SIZE = 0x9C;

    private static final int SIZE = WIDTH * HEIGHT / 8;
    private static final int HEADER = 0x0000D082;
    private static final int FOOTER = 0xFFF4C307;

    // Huffman code for every 4-pixel nibble, indexed by nibble value.
    private static final int[] CODE_LENGTHS = { 1, 4, 5, 4, 5, 6, 6, 6, 5, 6, 6, 6, 4, 5, 5, 4 };
    private static final int[] CODE_BITS = { 0b1, 0b0110, 0b01010, 0b0100, 0b00010, 0b011110, 0b010110, 0b000110, 0b00110, 0b011111, 0b010111, 0b000111, 0b0010, 0b01110, 0b00111, 0b0000 };

    private NintendoLogo()
    {
    }

    /** Decodes a compressed header logo into {@code [y][x]} pixels, {@code true} where the pixel is set. */
    public static boolean[][] decompress(byte[] logo)
    {
        if (logo.length != COMPRESSED_SIZE)
            throw new IllegalArgumentException("header logo must be 0x9C bytes, got 0x" + Integer.toHexString(logo.length));

        byte[] data = reverse32(logo);
        byte[] bytes = new byte[SIZE + 8];
        int bitPos = 0;
        for (int i = 0; i < bytes.length; i++)
        {
            int low = readNibble(data, bitPos);
            bitPos += Math.min(CODE_LENGTHS[low], data.length * 8 - bitPos);
            int high = readNibble(data, bitPos);
            bitPos += Math.min(CODE_LENGTHS[high], data.length * 8 - bitPos);
            bytes[i] = (byte) ((high << 4) | low);
        }

        MemBuf.MemBufReader reader = MemBuf.create(bytes).reader();
        int header = reader.readInt();
        if (header != HEADER)
            throw new IllegalArgumentException(String.format("invalid logo header, expected %08x but got %08x", HEADER, header));
        reader.setPosition(SIZE + 4);
        int footer = reader.readInt();
        if (footer != FOOTER)
            throw new IllegalArgumentException(String.format("invalid logo footer, expected %08x but got %08x", FOOTER, footer));

        undiff16(bytes, 4, SIZE);
        boolean[][] pixels = new boolean[HEIGHT][WIDTH];
        for (int y = 0; y < HEIGHT; y++)
        {
            for (int x = 0; x < WIDTH; x++)
            {
                pixels[y][x] = (bytes[4 + tileIndex(x, y)] & (1 << (x & 7))) != 0;
            }
        }

        return pixels;
    }

    /** Encodes {@code [y][x]} pixels back into a compressed header logo. */
    public static byte[] compress(boolean[][] pixels)
    {
        byte[] diff = new byte[SIZE + 8];
        for (int y = 0; y < HEIGHT; y++)
        {
            for (int x = 0; x < WIDTH; x++)
            {
                if (pixels[y][x])
                    diff[4 + tileIndex(x, y)] |= (byte) (1 << (x & 7));
            }
        }

        diff16(diff, 4, SIZE);
        MemBuf ends = MemBuf.create();
        ends.writer().writeInt(HEADER).writeInt(FOOTER);
        byte[] endBytes = ends.reader().getBuffer();
        System.arraycopy(endBytes, 0, diff, 0, 4);
        System.arraycopy(endBytes, 4, diff, SIZE + 4, 4);

        byte[] out = new byte[COMPRESSED_SIZE];
        int bitPos = 0;
        for (byte b : diff)
        {
            bitPos = writeCode(out, bitPos, b & 0xF);
            bitPos = writeCode(out, bitPos, (b >> 4) & 0xF);
        }

        return reverse32(out);
    }

    private static int tileIndex(int x, int y)
    {
        return (y / 8) * WIDTH + (x / 8) * 8 + y % 8;
    }

    /** Reads one nibble, treating bits past the end of {@code data} as zero. */
    private static int readNibble(byte[] data, int bitPos)
    {
        for (int value = 0; value < CODE_LENGTHS.length; value++)
        {
            int code = 0;
            for (int i = 0; i < CODE_LENGTHS[value]; i++)
            {
                code = (code << 1) | bit(data, bitPos + i);
            }

            if (code == CODE_BITS[value])
                return value;
        }

        throw new IllegalArgumentException("invalid logo Huffman code at bit " + bitPos);
    }

    private static int bit(byte[] data, int pos)
    {
        if (pos >= data.length * 8)
            return 0;

        return (data[pos >> 3] >> (7 - (pos & 7))) & 1;
    }

    /** Writes the code for {@code value} MSB first, dropping bits that run past the end of {@code out}. */
    private static int writeCode(byte[] out, int bitPos, int value)
    {
        for (int i = CODE_LENGTHS[value] - 1; i >= 0; i--)
        {
            if (bitPos < out.length * 8 && ((CODE_BITS[value] >> i) & 1) != 0)
                out[bitPos >> 3] |= (byte) (0x80 >> (bitPos & 7));
            bitPos++;
        }

        return bitPos;
    }

    private static void diff16(byte[] data, int start, int length)
    {
        int prev = 0;
        for (int i = start; i < start + length; i += 2)
        {
            int curr = (data[i] & 0xFF) | ((data[i + 1] & 0xFF) << 8);
            int value = (curr - prev) & 0xFFFF;
            data[i] = (byte) value;
            data[i + 1] = (byte) (value >> 8);
            prev = curr;
        }
    }

    private static void undiff16(byte[] data, int start, int length)
    {
        int prev = 0;
        for (int i = start; i < start + length; i += 2)
        {
            int curr = (data[i] & 0xFF) | ((data[i + 1] & 0xFF) << 8);
            int value = (curr + prev) & 0xFFFF;
            data[i] = (byte) value;
            data[i + 1] = (byte) (value >> 8);
            prev = value;
        }
    }

    /** Byte-swaps every whole 32-bit word. */
    private static byte[] reverse32(byte[] data)
    {
        byte[] out = data.clone();
        for (int i = 0; i + 4 <= out.length; i += 4)
        {
            for (int j = 0; j < 4; j++)
            {
                out[i + j] = data[i + 3 - j];
            }
        }

        return out;
    }
}
