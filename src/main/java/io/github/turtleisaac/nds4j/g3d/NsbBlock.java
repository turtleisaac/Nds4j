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

package io.github.turtleisaac.nds4j.g3d;

import io.github.turtleisaac.nds4j.framework.MemBuf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Little-endian block assembly for Nitro 3D containers.
 * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h"
 */
final class NsbBlock
{
    private NsbBlock()
    {
    }

    /**
     * @param magic four-character block magic
     * @param size block size stored after the magic
     * @param dictionary the resource dictionary
     * @param payloads the sections that follow the dictionary
     * @return magic, size, dictionary, then each payload
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h"
     */
    static byte[] block(String magic, int size, byte[] dictionary, byte[][] payloads)
    {
        // Magic and the little-endian size, then the dictionary.
        MemBuf buf = MemBuf.create();
        MemBuf.MemBufWriter writer = buf.writer();
        writer.write(magic.getBytes(StandardCharsets.US_ASCII));
        writer.writeInt(size);
        writer.write(dictionary);

        // Each payload is already padded by its own writer.
        append(writer, payloads);

        return buf.reader().getBuffer();
    }

    /**
     * @param writer the block being assembled
     * @param payloads sections to append
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h"
     */
    private static void append(MemBuf.MemBufWriter writer, byte[][] payloads)
    {
        for (byte[] payload : payloads)
        {
            writer.write(payload);
        }
    }

    /**
     * @param payloads sections laid out in order
     * @param start offset of the first payload
     * @return a 4-byte offset record per payload
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h"
     */
    static List<byte[]> offsets(byte[][] payloads, int start)
    {
        List<byte[]> records = new ArrayList<>();
        int cursor = start;

        for (byte[] payload : payloads)
        {
            MemBuf word0Buf = MemBuf.create();
            word0Buf.writer().writeUInt32(cursor);
            byte[] word0 = word0Buf.reader().getBuffer();
            records.add(word0);
            cursor += payload.length;
        }

        return records;
    }

    /**
     * @param payloads sections laid out in order
     * @param start offset of the first payload
     * @return the offset just past the last payload
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h"
     */
    static int end(byte[][] payloads, int start)
    {
        int cursor = start;

        for (byte[] payload : payloads)
        {
            cursor += payload.length;
        }

        return cursor;
    }
}
