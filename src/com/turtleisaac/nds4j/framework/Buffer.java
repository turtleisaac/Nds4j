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

package com.turtleisaac.nds4j.framework;

import java.io.*;
import java.nio.file.Path;

public class Buffer {

    private static final int INITIAL_SIZE = 1024*64;

    private byte[] bytes = new byte[1024*64];
    private int position = 0;
    private int truePosition = 0;
    private int limit = 0;
    private String file;
    private final BufferedInputStream in;
    private boolean endOfFile;
    private final boolean virtual;

    public Buffer(String file) {
        this.file= file;
        this.endOfFile = false;
        try {
            in = new BufferedInputStream(new FileInputStream(file));
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        virtual = false;
    }

    public Buffer(Path file) {
        this.file= file.toString();
        this.endOfFile = false;
        try {
            in = new BufferedInputStream(new FileInputStream(file.toString()));
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        virtual = false;
    }

    public Buffer(byte[] arr)
    {
        this.file = null;
        this.endOfFile = false;
        bytes = arr;
        in = null;
        virtual = true;
        limit = arr.length;
    }

    /**
     * produces a special variation of a Buffer class where the entire file gets loaded into memory at once, which allows for free position adjustment
     * @param file a String path to a file
     * @return a <code>Buffer</code>
     */
    public static Buffer getVirtualBuffer(String file)
    {
        return new Buffer(readFile(file));
    }

    public static byte[] readFile(String file)
    {
        Buffer buffer = new Buffer(file);
        return buffer.readRemainder();
    }

    public int readInt() {
        require(4);

        int ret = readByte();
        ret |= readByte() << 8;
        ret |= readByte() << 16;
        ret |= readByte() << 24;

        return ret;
    }

    public int[] readSelectiveInt(int max, int alt)
    {
        int ret= readInt();
        if(ret > max)
        {
            System.out.println("mooo");
        }
        return ret <= max ? new int[]{ret, 0} : new int[]{alt, 1};
    }

    public short[] readSelectiveShort(int max, short alt)
    {
        short ret= readShort();
        if(ret > max)
        {
            System.out.println("mooo");
        }
        return ret <= max ? new short[]{ret, 0} : new short[]{alt, 1};
    }

    public int[] readSelectiveByte(int max, int alt)
    {
        int ret= readByte();
        if(ret > max)
        {
            System.out.println("mooo");
        }
        return ret <= max ? new int[]{ret, 0} : new int[]{alt, 1};
    }

    public long readUInt32() {
        return (long)readInt() & 0xffffffffL;
    }
    public int readUInt16() { return (int)readShort() & 0xffff; }


    public long readLong() {
        require(8);

        long ret = readByte();
        ret |= readByte() << 8;
        ret |= readByte() << 16;
        ret |= readByte() << 24;
        ret |= (long)readByte() << 32;
        ret |= (long)readByte() << 40;
        ret |= (long)readByte() << 48;
        ret |= (long)readByte() << 56;

        return ret;
    }

    public short readShort() {
        require(2);

        int ret = readByte() | (readByte() << 8);

        return (short) (ret);
    }

    public short[] readShorts(int size) {
        short[] ret= new short[size];
        for(int i= 0; i < size; i++){
            ret[i]= readShort();
        }
        return ret;
    }

    public short[] readUnsignedShorts(int size) {
        short[] ret= new short[size];
        for(int i= 0; i < size; i++){
            ret[i]= readShort();
        }
        return ret;
    }


    public String readString(int size) {
        require(size);
        String ret = new String(bytes, position, size);
        position += size;
        truePosition+= size;
        return ret;
    }

    public byte[] readBytes(int size) {

        byte[] ret = new byte[size];
        int offset = 0;
        while (size > 0) {
            int toRead = Math.min(size, bytes.length);
            require(toRead);
            System.arraycopy(bytes, position, ret, offset, toRead);
            position += toRead;
            offset += toRead;
            truePosition+= toRead;
            size -= toRead;
        }

        return ret;
    }

    public int[] readBytesI(int size)
    {
        byte[] arr= readBytes(size);
        int[] ret= new int[arr.length];

        for(int i= 0; i < arr.length; i++)
        {
            ret[i]= arr[i] & 0xff;
        }

        return ret;
    }

    public int readByte() {
        require(1);
        truePosition++;
        return bytes[position++] & 0xff;
    }

    public short readUShort8()
    {
        return (short) ((short)readByte() & 0xff);
    }

    public int getPosition()
    {
        return truePosition;
    }

    private void require(int size) {
        if (limit - position < size) {
            if (!virtual) {
                refill();
            }
            if (limit - position < size) {
                throw new RuntimeException("Want "+size+" bytes but only "+limit+" bytes remain. Current position: " + truePosition);
            }
        }
    }

    private void refill() {
        int remaining = limit - position;
        System.arraycopy(bytes, position, bytes, 0, remaining);
        position = 0;
        limit = remaining;

        int free = bytes.length - remaining;
        int read = 0;
        try {
            read = in.read(bytes, remaining, free);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (read == -1) {
            endOfFile = true;
        }
        else
        {
            limit += read;
        }
    }

    public void close() throws IOException
    {
        if (!virtual)
        {
            in.close();
        }
    }

    public String getFile()
    {
        return file;
    }

    public boolean endOfFile() {
        return endOfFile;
    }

    public void skipBytes(int bytes) {
        byte[] throwAway= readBytes(bytes);
    }

    public void skipTo(int offset)
    {
        if(offset < truePosition)
            throw new RuntimeException("Already beyond this offset. Currently at: " + truePosition + ", Target was: " + offset);
        else if(offset != truePosition)
            readBytes(offset-truePosition);
    }

    public void skipTo(long offset)
    {
        if(offset < truePosition)
            throw new RuntimeException("Already beyond this offset. Currently at: " + truePosition + ", Target was: " + offset);
        else if(offset != truePosition)
            readBytes((int) (offset-truePosition));
    }

    public void seekGlobal(int offset)
    {
        if(offset > bytes.length)
            throw new RuntimeException("Buffer error: out of bounds address");
        else if (!virtual)
            throw new RuntimeException("Only available on virtual buffers");
        else {
            truePosition = offset;
            position = offset;
        }
    }

    public void seekGlobal(long offset)
    {
        if(offset > bytes.length)
            throw new RuntimeException("Buffer error: out of bounds address");
        else if (!virtual)
            throw new RuntimeException("Only available on virtual buffers");
        else {
            truePosition = (int) offset;
            position = (int) offset;
        }

    }

    public byte[] readTo(int offset)
    {
        if(offset < truePosition)
            throw new RuntimeException("Already beyond this offset. Currently at: " + truePosition + ", Target was: " + offset);
        else if(offset != truePosition)
            return readBytes((int) ((offset & 0xffffffffL)-(truePosition & 0xffffffffL)));
        return null;
    }

    public byte[] readTo(long offset)
    {
        if(offset < truePosition)
            throw new RuntimeException("Already beyond this offset. Currently at: " + truePosition + ", Target was: " + offset);
        else if(offset != truePosition)
            return readBytes((int) (offset-truePosition));
        return null;
    }

    public byte[] readRemainder() {
        return readBytes((int)(new File(file).length()-truePosition));
    }

    public int getLength()
    {
        if (!virtual)
            return (int) new File(file).length();
        else
            return bytes.length;
    }
}
