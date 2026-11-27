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

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class MemBuf
{

    private byte[] buf;
    private int capacity;
    private int readPos;
    private int writePos;
    private MemBufReader reader;
    private MemBufWriter writer;

    private static final int INITIAL_SIZE = 4096;

    public static MemBuf create()
    {
        return new MemBuf();
    }

    public static MemBuf create(byte[] data)
    {
        MemBuf buf = new MemBuf();
        buf.writer.write(data);
        return buf;
    }

    public MemBuf()
    {
        this.buf = new byte[INITIAL_SIZE];
        this.capacity = INITIAL_SIZE;
        reader = new MemBufReader();
        writer = new MemBufWriter();
    }

    public MemBufReader reader()
    {
        return reader;
    }

    public MemBufWriter writer()
    {
        return writer;
    }

    /**
     * An {@link OutputStream} that appends each byte through {@link MemBufWriter}.
     * Image encoders that only accept a stream write into the same buffer as every other field.
     * @return a stream positioned at the current write cursor
     */
    public OutputStream outputStream()
    {
        return new OutputStream() {
            @Override
            public void write(int b)
            {
                writer.writeByte((byte) b);
            }

            @Override
            public void write(byte[] bytes, int off, int len)
            {
                writer.write(bytes, off, len);
            }
        };
    }

    /**
     * The low {@code bits} of {@code value}, sign-extended.
     * @param value a word whose low field is signed
     * @param bits the field width, from 1 to 32
     * @return the signed field
     * @see "GBATEK DS 3D Video, VTX_10 component packing"
     */
    public static int signExtend(int value, int bits)
    {
        int shift = 32 - bits;

        return (value << shift) >> shift;
    }

    /**
     * @param value a 1.3.12 quantity
     * @return the rounded fx16 bits, in the low 16
     * @see "NitroSDK include/nitro/fx/fx.h fx16"
     */
    public static int fx16Bits(double value)
    {
        return (int) Math.round(value * 4096.0) & 0xFFFF;
    }

    /**
     * @param value a 1.19.12 quantity
     * @return the rounded fx32 bits
     * @see "NitroSDK include/nitro/fx/fx.h fx32"
     */
    public static int fx32Bits(double value)
    {
        return (int) Math.round(value * 4096.0);
    }

    /**
     * @param value a little-endian unsigned 32-bit value
     * @return the four bytes, low byte first
     */
    public static byte[] le32(long value)
    {
        MemBuf buf = create();
        buf.writer().writeUInt32(value);

        return buf.reader().getBuffer();
    }

    /**
     * @param out a stream that accepts the two little-endian bytes
     * @param value a little-endian unsigned 16-bit value
     */
    public static void writeUInt16(java.io.OutputStream out, int value)
    {
        writeBytes(out, le16(value));
    }

    /**
     * @param out a stream that accepts the four little-endian bytes
     * @param value a little-endian unsigned 32-bit value
     */
    public static void writeUInt32(java.io.OutputStream out, long value)
    {
        writeBytes(out, le32(value));
    }

    private static void writeBytes(java.io.OutputStream out, byte[] bytes)
    {
        try
        {
            out.write(bytes);
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * @param value a little-endian unsigned 16-bit value
     * @return the two bytes, low byte first
     */
    public static byte[] le16(int value)
    {
        MemBuf buf = create();
        buf.writer().writeShort((short) value);

        return buf.reader().getBuffer();
    }

    /**
     * @param dest the array to patch
     * @param offset index of a little-endian u16
     * @param value the value
     */
    public static void putUInt16(byte[] dest, int offset, int value)
    {
        System.arraycopy(le16(value), 0, dest, offset, 2);
    }

    /**
     * @param dest the array to patch
     * @param offset index of a little-endian u32
     * @param value the value
     */
    public static void putUInt32(byte[] dest, int offset, long value)
    {
        System.arraycopy(le32(value), 0, dest, offset, 4);
    }

    /**
     * @param data the array
     * @param offset index of a little-endian u16
     * @return that value
     */
    public static int uint16(byte[] data, int offset)
    {
        return slice(data, offset, 2).readUInt16();
    }

    /**
     * @param data the array
     * @param offset index of a little-endian u32
     * @return that value
     */
    public static long uint32(byte[] data, int offset)
    {
        return slice(data, offset, 4).readUInt32();
    }

    /**
     * @param data the array
     * @param offset index of a little-endian s16
     * @return that value
     */
    public static int int16(byte[] data, int offset)
    {
        return slice(data, offset, 2).readShort();
    }

    /**
     * @param data the array
     * @param offset index of a little-endian s32
     * @return that value
     */
    public static int int32(byte[] data, int offset)
    {
        return slice(data, offset, 4).readInt();
    }

    /**
     * @param data the array
     * @param offset index of a big-endian u16
     * @return that value
     */
    public static int uint16be(byte[] data, int offset)
    {
        byte[] swapped = new byte[2];
        swapped[0] = data[offset + 1];
        swapped[1] = data[offset];

        return create(swapped).reader().readUInt16();
    }

    /**
     * @param data the array
     * @param offset index of a big-endian u32
     * @return that value
     */
    public static long uint32be(byte[] data, int offset)
    {
        byte[] swapped = new byte[4];
        int index = 0;

        while (index < 4)
        {
            swapped[index] = data[offset + 3 - index];
            index++;
        }

        return create(swapped).reader().readUInt32();
    }

    /**
     * @param data the array
     * @param offset index of a u32
     * @param bigEndian whether the word is stored high byte first
     * @return that value
     */
    public static long uint32(byte[] data, int offset, boolean bigEndian)
    {
        if (bigEndian)
            return uint32be(data, offset);

        return uint32(data, offset);
    }

    /**
     * @param data the array
     * @param offset index of a u16
     * @param bigEndian whether the halfword is stored high byte first
     * @return that value
     */
    public static int uint16(byte[] data, int offset, boolean bigEndian)
    {
        if (bigEndian)
            return uint16be(data, offset);

        return uint16(data, offset);
    }

    private static MemBufReader slice(byte[] data, int offset, int length)
    {
        byte[] word = new byte[length];
        System.arraycopy(data, offset, word, 0, length);

        return create(word).reader();
    }

    public class MemBufReader
    {

        private void require(int space)
        {
            if (readPos < 0)
            {
                throw new IllegalStateException("Invalid read position: " + readPos);
            }
            if (writePos > buf.length)
            {
                throw new IllegalStateException("Invalid write position: " + writePos + ", buffer length is " + buf.length);
            }
            if (writePos - readPos < space)
            {
                throw new IllegalStateException("Not enough room to read. need " + space + " bytes, have " + (writePos - readPos));
            }
        }

        public int getPosition()
        {
            return readPos;
        }

        public void setPosition(int pos)
        {
            readPos = pos;
        }

        public void setPosition(long pos)
        {
            readPos = (int) pos;
        }

        public byte[] getBuffer()
        {
            byte[] ret = new byte[writePos - readPos];
            System.arraycopy(buf, readPos, ret, 0, writePos - readPos);
            return ret;
        }

        public int readByte()
        {
            require(1);
            return buf[readPos++] & 0xFF;
        }

        public int readInt()
        {
            require(4);
            int ret = readUInt8();
            ret |= (readUInt8() << 8);
            ret |= (readUInt8() << 16);
            ret |= (readUInt8() << 24);
            return ret;
        }

        /**
         * @return the next little-endian IEEE-754 binary32 value
         * @see "GBATEK DS Memory Map, little-endian"
         */
        public float readFloat()
        {
            return Float.intBitsToFloat(readInt());
        }

        /**
         * A 1.3.12 fixed-point halfword.
         * @return the value in whole units
         * @see "NitroSDK include/nitro/fx/fx.h fx16"
         */
        public double readFx16()
        {
            return readShort() / 4096.0;
        }

        /**
         * A 1.19.12 fixed-point word.
         * @return the value in whole units
         * @see "NitroSDK include/nitro/fx/fx.h fx32"
         */
        public double readFx32()
        {
            return readInt() / 4096.0;
        }

        public long readUInt32()
        {
            return ((long) readInt()) & 0xFFFFFFFFL;
        }

        public short readShort()
        {
            require(2);
            int ret = readUInt8() | (readUInt8() << 8);
            return (short) ret;
        }

        public int readUInt16()
        {
            return ((int) readShort()) & 0xffff;
        }

        /**
         * @param offset index of a little-endian u16
         * @return that value
         */
        public int readUInt16At(int offset)
        {
            setPosition(offset);

            return readUInt16();
        }

        /**
         * @param offset index of a little-endian s16
         * @return that value
         */
        public int readInt16At(int offset)
        {
            setPosition(offset);

            return readShort();
        }

        /**
         * @param offset index of a little-endian u32
         * @return that value
         */
        public long readUInt32At(int offset)
        {
            setPosition(offset);

            return readUInt32();
        }

        public short readUInt8()
        {
            return (short) ((short) readByte() & 0xff);
        }

        public String readString(int size)
        {
            require(size);
            String ret = new String(Arrays.copyOfRange(buf, readPos, readPos + size), StandardCharsets.ISO_8859_1);
            readPos += size;
            return ret;
        }

        public byte[] readBytes(int size)
        {
            if (size < 0)
                throw new IllegalArgumentException("Cannot read a negative number of bytes: " + size);
            require(size);
            byte[] ret = new byte[size];
            System.arraycopy(buf, readPos, ret, 0, size);
            readPos += size;
            return ret;
        }

        public byte[] readTo(int addrs)
        {
            int size = addrs - readPos;
            return readBytes(size);
        }

        public byte[] readTo(long addrs)
        {
            int size = (int) (addrs - readPos);
            require(size);
            byte[] ret = new byte[size];
            System.arraycopy(buf, readPos, ret, 0, size);
            readPos += size;
            return ret;
        }

        public int[] readBytesI(int size)
        {
            byte[] arr = readBytes(size);
            int[] ret = new int[arr.length];

            for (int i = 0; i < arr.length; i++)
            {
                ret[i] = arr[i] & 0xff;
            }

            return ret;
        }

        public void skip(int num)
        {
            readBytes(num);
        }
    }


    public class MemBufWriter
    {

        private void require(int space)
        {
            if (capacity - (writePos) < space)
            {
                int newSize = Math.max((writePos) + space, capacity * 2);
                buf = Arrays.copyOf(buf, newSize);
                capacity = buf.length;
            }
        }

        public int getPosition()
        {
            return writePos;
        }

        public void setPosition(int pos)
        {
            writePos = pos;
        }

        public void skip(int n)
        {
            // A negative skip silently rewinds the write cursor, which truncates everything
            // written so far the next time the buffer is read back.
            if (n < 0)
                throw new IllegalArgumentException("Cannot skip a negative number of bytes: " + n);
            require(n);
            writePos += n;
        }

        public MemBufWriter writeInt(int i)
        {
            require(4);
            buf[writePos++] = (byte) (i & 0xff);
            buf[writePos++] = (byte) ((i >> 8) & 0xff);
            buf[writePos++] = (byte) ((i >> 16) & 0xff);
            buf[writePos++] = (byte) ((i >> 24) & 0xff);
            return this;
        }

        /**
         * @param value an IEEE-754 binary32 value
         * @return this writer
         * @see "GBATEK DS Memory Map, little-endian"
         */
        public MemBufWriter writeFloat(float value)
        {
            return writeInt(Float.floatToIntBits(value));
        }

        /**
         * @param value a 1.3.12 quantity
         * @return this writer
         * @see "NitroSDK include/nitro/fx/fx.h fx16"
         */
        public MemBufWriter writeFx16(double value)
        {
            return writeShort((short) fx16Bits(value));
        }

        /**
         * @param value a 1.19.12 quantity
         * @return this writer
         * @see "NitroSDK include/nitro/fx/fx.h fx32"
         */
        public MemBufWriter writeFx32(double value)
        {
            return writeInt(fx32Bits(value));
        }

        public MemBufWriter writeUInt32(long i)
        {
            return writeInt((int) i);
        }

        public MemBufWriter writeShort(short s)
        {
            require(2);
            buf[writePos++] = (byte) (s & 0xff);
            buf[writePos++] = (byte) ((s >> 8) & 0xff);
            return this;
        }

        public MemBufWriter writeByte(byte b)
        {
            require(1);
            buf[writePos++] = b;
            return this;
        }

        public MemBufWriter writeBytes(int... bytes)
        {
            require(bytes.length);
            for (int b : bytes)
            {
                buf[writePos++] = (byte) b;
            }
            return this;
        }

        public MemBufWriter write(byte... bytes)
        {
            require(bytes.length);
            for (byte b : bytes)
            {
                buf[writePos++] = b;
            }
            return this;
        }

        public MemBufWriter write(short... shorts)
        {
            for (short s : shorts)
            {
                require(2);
                buf[writePos++] = (byte) (s & 0xff);
                buf[writePos++] = (byte) ((s >> 8) & 0xff);
            }
            return this;
        }

        public MemBufWriter writeString(String s)
        {
            byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
            return write(b);
        }

        public MemBufWriter writeString(String s, int len)
        {
            if (len < 0)
                throw new IllegalArgumentException("Cannot write a string into a negative-width field: " + len);
            byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
            byte[] toWrite = new byte[len];
            System.arraycopy(b, 0, toWrite, 0, Math.min(b.length, len));
            return write(toWrite);
        }

        /**
         * writes provided bytes at current offset
         */
        public MemBufWriter write(byte[] bytes, int srcPos, int length)
        {
            require(length);
            System.arraycopy(bytes, srcPos, buf, writePos, length);
            writePos += length;
            return this;
        }

        /**
         * writes provided bytes at specified offset
         * @param bytes
         * @param srcPos
         * @param writeOffset
         * @param length
         * @return
         */
        public MemBufWriter writeAt(byte[] bytes, int srcPos, int writeOffset, int length)
        {
            int saved = writePos;
            setPosition(writeOffset);
            require(length);
            System.arraycopy(bytes, srcPos, buf, writePos, length);
            writePos += length;
            writePos = Math.max(saved, writePos);
            return this;
        }

        public MemBufWriter writeByteNumTimes(byte b, int numTimes)
        {
            require(numTimes);
            for (int i = 0; i < numTimes; i++)
            {
                buf[writePos++] = b;
            }
            return this;
        }

        public MemBufWriter writeByteNumTimesAt(byte b, int numTimes, int writeOffset)
        {
            int saved = writePos;
            setPosition(writeOffset);
            require(numTimes);
            for (int i = 0; i < numTimes; i++)
            {
                buf[writePos++] = b;
            }
            writePos = Math.max(saved, writePos);
            return this;
        }

        public MemBufWriter align(int alignment)
        {
            int rem = (writePos) % alignment;
            if (rem != 0)
                skip(alignment - rem);
            return this;
        }

        public MemBufWriter align(int alignment, byte fill)
        {
            int rem = (writePos) % alignment;
            if (rem != 0)
                return writeByteNumTimes(fill, alignment - rem);
            return this;
        }

    }

}
