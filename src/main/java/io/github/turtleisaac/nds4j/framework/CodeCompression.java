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

public class CodeCompression
{
    /**
     * Attempt to decompress data that was compressed using code compression. This is
     *     the inverse of compress().
     * @param data a <code>byte[]</code>
     * @return a <code>byte[]</code> containing the decompressed data, or the same data if the data was not compressed
     */
    public static byte[] decompress(byte[] data)
    {
        int appendedDataAmount = detectAppendedData(data);

        if (appendedDataAmount == -1) // probably isn't compressed
            return data;

        MemBuf dataBuf = MemBuf.create(data);
        MemBuf.MemBufReader reader = dataBuf.reader();

        int dataSize = data.length;
        byte[] appendedData;
        if (appendedDataAmount == 0)
        {
            appendedData = new byte[] {};
        }
        else
        {
            reader.setPosition(dataBuf.writer().getPosition() - appendedDataAmount);
            appendedData = reader.readBytes(appendedDataAmount);
            dataBuf.writer().skip(-appendedDataAmount);
            dataSize -= appendedDataAmount;
        }

        reader.setPosition(dataSize-4);
        // if extraSize (last value in header) is 0, the data is not actually compressed
        if (reader.readInt() == 0)
            return data;

        // read the header
        reader.setPosition(data.length - 8);
        int composite = reader.readInt();
        int headerLength = composite >> 24;
        int compressedLength = composite & 0xFFFFFF;
        int extraSize = reader.readInt();

        if (dataSize < headerLength)
            throw new RuntimeException(String.format("File is too small for header (%d < %d)", dataSize, headerLength));

        if (compressedLength > dataSize)
            throw new RuntimeException("Compressed length doesn't fit in the input file");

        reader.setPosition(dataSize - headerLength);
        for (Byte b : reader.readTo(data.length - 8))
        {
            if ((b & 0xFF) != 0xFF)
                throw new RuntimeException("Header padding isn't entirely 0xFF");
        }

        /* Format description:
         *
         * Code LZ compression is basically just LZ-0x10 compression.
         * However, the order of reading is reversed: the compression starts
         * at the end of the file. Assuming we start reading at the end
         * towards the beginning, the format is:
         *
         * u32 extraSize            | decompressed data size = file length
         *                          | (including header) + this value
         * u8 headerLen             |
         * u24 compressedLen        | Can be less than file size (without
         *                          | header): if so, the rest of the file is
         *                          | uncompressed. It may also be the file
         *                          | size.
         * u8[headerSize-8] padding | 0xFF's
         *
         * 0x10-like-compressed data follows (without the usual 4-byte
         * header). The only difference is that 2 should be added to the DISP
         * value in compressed blocks to get the proper value. The u32 and
         * u24 are read most significant byte first. If extraSize is 0, there
         * is no headerSize, decompressedLength or padding: the data starts
         * immediately, and is uncompressed.
         *
         * arm9.bin has 3 extra u32 values at the 'start' (ie: end of the
         * file), which may be ignored (and are ignored here). These 12 bytes
         * also should not be included in the computation of the output size.
         */

        // The compressed size is sometimes the file size
        if (compressedLength >= dataSize)
            compressedLength = dataSize;

        // The first part of the file, not included in compressedLength, is not compressed, and should be ignored.
        int passthroughLength = dataSize - compressedLength;
        reader.setPosition(0);
        byte[] passthroughData = reader.readBytes(passthroughLength);

        // Then there's the compressed data. Also make a bytearray where we'll be putting the decompressed data.
        byte[] compressedData = reader.readBytes(compressedLength - headerLength);
        byte[] decompressedData = new byte[dataSize + extraSize - passthroughLength];

        int currentOutSize = 0;
        int decompressedLength = decompressedData.length;
        int readBytes = 0;
        int flags = 0;
        int mask = 1;

        while (currentOutSize < decompressedLength)
        {
            // Update the mask. If all flag bits have been read, get a new set.
            if (mask == 1)
            {
                if (readBytes >= compressedLength)
                    throw new RuntimeException("Not enough data to decompress");
                flags = compressedData[compressedData.length - 1 - readBytes++];
                mask = 0x80;
            }
            else
            {
                mask >>= 1;
            }

            // Bit = 1 means it's compressed
            if ((flags & mask) != 0)
            {
                // Get length and displacement ("disp") values from the next 2 bytes
                if (readBytes + 1 >= dataSize)
                    throw new RuntimeException("Not enough data to decompress");

                byte byte1 = compressedData[compressedData.length - 1 - readBytes++];
                byte byte2 = compressedData[compressedData.length - 1 - readBytes++];

                // The number of bytes to copy
                int length = (((byte1 & 0xFF) >> 4) + 3) & 0xff;

                // Where the bytes should be copied from (relatively)
                // ((((byte1 & 0x0F) << 8)) | (byte2)) + 3
                int disp = ((((byte1 & 0x0F) << 8) & 0xFF) | (byte2 & 0xFF)) + 3;

                if (disp > currentOutSize)
                {
                    if (currentOutSize < 2)
                        throw new RuntimeException(String.format("Cannot go back more than already written: attempted to go" +
                                "back %dx bytes when only %dx bytes have been written.", disp, currentOutSize));
                    /*
                     * HACK. This seems to produce valid files, but isn't the
                     * most elegant solution. Although this *could* be the
                     * actual way to use a disp of 2 in this format, as,
                     * otherwise, the minimum would be 3 (and 0 is undefined,
                     * and 1 is less useful).
                     */
                    disp = 2;
                }

                int bufIdx = currentOutSize - disp;
                for (int i = 0; i < length; i++)
                {
                    int next = decompressedData[decompressedData.length - 1 - bufIdx++];
                    decompressedData[decompressedData.length - 1 - currentOutSize++] = (byte) next;
                }
            }
            else
            {
                if (readBytes >= dataSize)
                    throw new RuntimeException("Not enough data to decompress");

                int next = compressedData[compressedData.length - 1 - readBytes++];
                decompressedData[decompressedData.length - 1 - currentOutSize++] = (byte) next;
            }
        }

        MemBuf outputBuf = MemBuf.create(passthroughData);
        outputBuf.writer().write(decompressedData).write(appendedData);
        return outputBuf.reader().getBuffer();
    }

    /**
     * Attempt to check if there's any appended data at the end of the given data
     * @param data a <code>byte[]</code> containing LZ compressed data
     * @return an <code>int</code> representing the amount of such data if so, or -1 if the data doesn't seem to be compressed.
     */
    private static int detectAppendedData(byte[] data)
    {
        for (int possibleAmt = 0; possibleAmt < 0x20; possibleAmt+= 4)
        {
            int headerLength;
            try {
                headerLength = data[data.length - 5 - possibleAmt];
            } catch (IndexOutOfBoundsException e) {
                return -1;
            }

            MemBuf dataBuf = MemBuf.create(data);
            MemBuf.MemBufReader reader = dataBuf.reader();

            reader.setPosition(data.length - possibleAmt - 8);
            int composite = reader.readInt();
            headerLength = composite >> 24;
            int compressedLength = composite & 0xFFFFFF;
            int extraSize = reader.readInt();

            if (headerLength < 8)
                continue;
            if (compressedLength > data.length)
                continue;

            reader.setPosition(data.length - possibleAmt - headerLength);
            boolean invalidFound = false;
            for (Byte b : reader.readTo(data.length - possibleAmt - 8))
            {
                if ((b & 0xFF) != 0xFF)
                {
                    invalidFound = true;
                    break;
                }
            }
            if (invalidFound)
                continue;
            return possibleAmt;
        }
        return -1;
    }
}
