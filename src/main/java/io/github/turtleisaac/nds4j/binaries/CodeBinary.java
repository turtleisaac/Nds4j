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

package io.github.turtleisaac.nds4j.binaries;

import io.github.turtleisaac.nds4j.framework.CodeCompression;
import io.github.turtleisaac.nds4j.framework.MemBuf;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An abstract class representing a code binary of a Nintendo DS game, that being either an overlay or an ARM9/ARM7 file.
 * Please note that for improved support of tools developed using Nds4j and Nds4j-ToolUI, this class (and all of its subclasses)
 * extend <code>ReentrantLock</code>. If developing a tool with a GUI, please obey the rules of using locks to ensure
 * thread-safety, as this is shared mutable data.
 * @see ReentrantLock
 */
public abstract class CodeBinary extends ReentrantLock
{
    private MemBuf physicalAddressBuffer;

    private int ramStartAddress;
    private int bssSize;

    private boolean compressed;
    private int size;

    public CodeBinary(byte[] data, int ramStartAddress, int bssSize)
    {
        super();
        byte[] decompressed = CodeCompression.decompress(data);
        compressed = Arrays.equals(decompressed, data);
        physicalAddressBuffer = MemBuf.create(decompressed);
        this.bssSize = bssSize;
        this.size = decompressed.length;
        this.ramStartAddress = ramStartAddress;
    }

    /**
     * Obtains the <code>MemBuf</code> containing this code binary's data.
     * <p>Access to this code binary's data should be done through physical addresses, not memory addresses.</p>
     * <p>If you wish to access memory addresses, subtract the return value of <code>getRamStartAddress()</code> from your memory
     * address in order to obtain the physical address in this file.</p>
     * @return a <code>MemBuf</code> containing the data in this code binary
     */
    public MemBuf getPhysicalAddressBuffer()
    {
        return physicalAddressBuffer;
    }

    void setPhysicalAddressBuffer(MemBuf physicalAddressBuffer)
    {
        this.physicalAddressBuffer = physicalAddressBuffer;
    }

    public int getRamStartAddress()
    {
        return ramStartAddress;
    }

    public int getSize()
    {
        return size;
    }

    /**
     * Returns the data contained within this code binary according to the current bounds of its physicalAddressBuffer object.
     * @return a <code>byte[]</code> containing the contents of this code binary.
     */
    public byte[] getData()
    {
        return physicalAddressBuffer.reader().getBuffer();
    }

    private void resetBufferPositions()
    {
        physicalAddressBuffer.reader().setPosition(0);
        physicalAddressBuffer.writer().setPosition(size);
    }

    /**
     * Acquires the lock on this CodeBinary object if available, waits until available otherwise.
     * <p>Will also reset the physicalAddressBuffer's readPos and writePos to their defaults</p>
     * @see ReentrantLock#lock()
     */
    @Override
    public void lock()
    {
        super.lock();
        resetBufferPositions();
    }

    /**
     * Acquires the lock on this <code>CodeBinary</code> object if available.
     * <p>Will also reset the physicalAddressBuffer's readPos and writePos to their defaults</p>
     * @return true if successful, false otherwise
     * @see ReentrantLock#tryLock()
     */
    @Override
    public boolean tryLock()
    {
        boolean locked = super.tryLock();
        if (locked)
        {
            resetBufferPositions();
        }
        return locked;
    }

    /**
     * Relinquishes the lock on this <code>CodeBinary</code> object if it is held by the current thread.
     * <p>Will also reset the physicalAddressBuffer's readPos and writePos to their defaults</p>
     * @see ReentrantLock#unlock()
     */
    @Override
    public void unlock()
    {
        resetBufferPositions();
        super.unlock();
    }

    @Override
    public void lockInterruptibly() throws InterruptedException
    {
        super.lockInterruptibly();
        resetBufferPositions();
    }
}
