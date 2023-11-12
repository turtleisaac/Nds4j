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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class MemBuf {

    private byte[] buf;
    private int capacity;
    private int readPos;
    private int writePos;
    private MemBufReader reader;
    private MemBufWriter writer;

    private static final int INITIAL_SIZE = 1024*1024;

    public static MemBuf create() {
        return new MemBuf();
    }

    public static MemBuf create(byte[] data) {
        MemBuf buf = new MemBuf();
        buf.writer.write(data);
        return buf;
    }

    public MemBuf() {
        this.buf = new byte[INITIAL_SIZE];
        this.capacity = INITIAL_SIZE;
        reader = new MemBufReader();
        writer = new MemBufWriter();
    }

    private MemBuf(MemBuf memBuf, int baseAddress)
    {
        this.buf = memBuf.buf;
        this.capacity = memBuf.capacity;
        this.readPos = baseAddress;
        this.writePos = baseAddress;
        reader = new MemBufReader();
        writer = new MemBufWriter();
    }

    public MemBufReader reader() {
        return reader;
    }

    public MemBufWriter writer() {
        return writer;
    }

    public class MemBufReader {

        private void require(int space) {
            if (writePos - readPos < space) {
                throw new IllegalStateException("Not enough room to read. need "+space+" bytes, have "+(writePos-readPos));
            }
        }

        public int getPosition() {
            return readPos;
        }

        public void setPosition(int pos) {
            readPos = pos;
        }

        public void setPosition(long pos) {
            readPos = (int) pos;
        }

        public byte[] getBuffer() {
            byte[] ret = new byte[writePos-readPos];
            System.arraycopy(buf, readPos, ret, 0, writePos-readPos);
            return ret;
        }

        public int readByte() {
            require(1);
            return buf[readPos++];
        }

        public int readInt() {
            require(4);
            int ret = readUInt8();
            ret |= (readUInt8() << 8);
            ret |= (readUInt8() << 16);
            ret |= (readUInt8() << 24);
            return ret;
        }

        public long readUInt32() {
            return ((long) readInt()) & 0xFFFFFFFFL;
        }

        public short readShort() {
            require(2);
            int ret = readUInt8() | (readUInt8() << 8);
            return (short)ret;
        }

        public int readUInt16() {
            return ((int) readShort()) & 0xffff;
        }

        public short readUInt8()
        {
            return (short) ((short)readByte() & 0xff);
        }

        public String readString(int size) {
            require(size);
            String ret = new String(Arrays.copyOfRange(buf, readPos, readPos + size), StandardCharsets.UTF_8);
            readPos += size;
            return ret;
        }

        public byte[] readBytes(int size) {
            require(size);
            byte[] ret = new byte[size];
            System.arraycopy(buf, readPos, ret, 0, size);
            readPos += size;
            return ret;
        }

        public byte[] readTo(int addrs) {
            int size = addrs - readPos;
            return readBytes(size);
        }

        public byte[] readTo(long addrs) {
            int size = (int) (addrs - readPos);
            require(size);
            byte[] ret = new byte[size];
            System.arraycopy(buf, readPos, ret, 0, size);
            readPos += size;
            return ret;
        }

        public int[] readBytesI(int size) {
            byte[] arr = readBytes(size);
            int[] ret = new int[arr.length];

            for(int i = 0; i < arr.length; i++)
            {
                ret[i] = arr[i] & 0xff;
            }

            return ret;
        }

        public void skip(int num) {
            readBytes(num);
        }
    }


    public class MemBufWriter {

        private void require(int space) {
            if (capacity - (writePos) < space) {
                int newSize = Math.max((writePos)+space, capacity + INITIAL_SIZE);
                buf = Arrays.copyOf(buf, newSize);
                capacity = buf.length;
            }
        }

        public int getPosition() {
            return writePos;
        }

        public void setPosition(int pos) {
            writePos = pos;
        }

        public void skip(int n) {
            writePos += n;
        }

        public MemBufWriter writeInt(int i) {
            require(4);
            buf[writePos++] = (byte) (i & 0xff);
            buf[writePos++] = (byte) ((i >> 8) & 0xff);
            buf[writePos++] = (byte) ((i >> 16) & 0xff);
            buf[writePos++] = (byte) ((i >> 24) & 0xff);
            return this;
        }

        public MemBufWriter writeUInt32(long i) {
            return writeInt((int) i);
        }

        public MemBufWriter writeShort(short s) {
            require(2);
            buf[writePos++] = (byte) (s & 0xff);
            buf[writePos++] = (byte) ((s >> 8) & 0xff);
            return this;
        }

        public MemBufWriter writeByte(byte b) {
            require(1);
            buf[writePos++] = b;
            return this;
        }

        public MemBufWriter writeBytes(int... bytes) {
            require(bytes.length);
            for (int b : bytes) {
                buf[writePos++] = (byte)b;
            }
            return this;
        }

        public MemBufWriter write(byte... bytes)  {
            require(bytes.length);
            for (byte b : bytes) {
                buf[writePos++] = b;
            }
            return this;
        }

        public MemBufWriter write(short... shorts) {
            for(short s : shorts)
            {
                require(2);
                buf[writePos++] = (byte) (s & 0xff);
                buf[writePos++] = (byte) ((s >> 8) & 0xff);
            }
            return this;
        }

        public MemBufWriter writeString(String s) {
            byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
            return write(b);
        }

        public MemBufWriter writeString(String s, int len) {
            byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
            byte[] toWrite = new byte[len];
            System.arraycopy(b, 0, toWrite, 0, b.length);
            return write(toWrite);
        }

        /**
         * writes provided bytes at current offset
         */
        public MemBufWriter write(byte[] bytes, int srcPos, int length) {
            require(length);
            for (int i=srcPos; length > 0; srcPos++,length--) {
                buf[writePos++] = bytes[i];
            }
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
        public MemBufWriter writeAt(byte[] bytes, int srcPos, int writeOffset, int length) {
            require(length);
            setPosition(writeOffset);
            for (int i=srcPos; length > 0; srcPos++,length--) {
                buf[writePos++] = bytes[i];
            }
            return this;
        }

        public MemBufWriter writeByteNumTimes(byte b, int numTimes) {
            require(numTimes);
            for (int i = 0; i < numTimes; i++) {
                buf[writePos++] = b;
            }
            return this;
        }

        public MemBufWriter writeByteNumTimesAt(byte b, int numTimes, int writeOffset) {
            require(numTimes);
            setPosition(writeOffset);
            for (int i = 0; i < numTimes; i++) {
                buf[writePos++] = b;
            }
            return this;
        }

        public MemBufWriter align(int alignment)
        {
            skip(alignment - (writePos) % alignment);
            return this;
        }

        public MemBufWriter align(int alignment, byte fill)
        {
            return writeByteNumTimes(fill, alignment - (writePos) % alignment);
        }

    }

}
