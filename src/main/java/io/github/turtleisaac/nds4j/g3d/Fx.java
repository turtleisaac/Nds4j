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

/**
 * Nitro fixed-point conversions used by geometry commands and node records.
 * @see "NitroSDK include/nitro/fx/fx.h"
 */
final class Fx
{
    /** 1.0 in fx32 and fx16. */
    static final double ONE = 4096.0;

    /** Fixed-point helpers are static. */
    private Fx()
    {
    }

    /**
     * @param raw a signed fx32 word
     * @return the value in model units
     * @see "NitroSDK include/nitro/fx/fx.h fx32"
     */
    static double fx32(int raw)
    {
        // 1.19.12 fixed point.
        return raw / ONE;
    }

    /**
     * @param raw the low 16 bits of an fx16
     * @return the value in model units
     * @see "NitroSDK include/nitro/fx/fx.h fx16"
     */
    static double fx16(int raw)
    {
        // The high half of a packed word is shifted down before this call.
        return (short) (raw & 0xFFFF) / ONE;
    }

    /**
     * @param data a byte array
     * @param offset index of one byte
     * @return that byte, 0-255
     * @see "NitroSDK include/nitro/fx/fx.h"
     */
    static int uint8(byte[] data, int offset)
    {
        // Hand the one byte to MemBuf instead of masking it here.
        return slice(data, offset, 1).readByte();
    }

    /**
     * @param data a byte array
     * @param offset index of a little-endian u16
     * @return that value
     * @see "NitroSDK include/nitro/fx/fx.h"
     */
    static int uint16(byte[] data, int offset)
    {
        // Hand the two bytes to MemBuf instead of shifting them here.
        return slice(data, offset, 2).readUInt16();
    }

    /**
     * @param data a byte array
     * @param offset index of a little-endian u32
     * @return that value
     * @see "NitroSDK include/nitro/fx/fx.h"
     */
    static long uint32(byte[] data, int offset)
    {
        // Hand the four bytes to MemBuf instead of shifting them here.
        return slice(data, offset, 4).readUInt32();
    }

    /**
     * @param data source bytes
     * @param offset first byte
     * @param length how many bytes to decode
     * @return a reader on just those bytes
     * @see "NitroSDK include/nitro/fx/fx.h"
     */
    private static MemBuf.MemBufReader slice(byte[] data, int offset, int length)
    {
        byte[] word = new byte[length];
        System.arraycopy(data, offset, word, 0, length);

        return MemBuf.create(word).reader();
    }

    /**
     * @param reader a reader on an fx32 word
     * @return that word in model units
     * @see "NitroSDK include/nitro/fx/fx.h fx32"
     */
    static double readFx32(MemBuf.MemBufReader reader)
    {
        // One little-endian word.
        return fx32(reader.readInt());
    }

    /**
     * @param reader a reader on three fx32 words
     * @return those words as a vector
     * @see "NitroSDK include/nitro/fx/fx.h fx32"
     */
    static Vec3 readVec3(MemBuf.MemBufReader reader)
    {
        // Translation and scale are stored x, then y, then z.
        double x = readFx32(reader);
        double y = readFx32(reader);
        double z = readFx32(reader);

        return Vec3.of(x, y, z);
    }

    /**
     * @param bits a word whose low 10 bits are the component
     * @return the signed 10-bit value
     * @see "GBATEK DS 3D Video, VTX_10 component packing"
     */
    static int signed10(int bits)
    {
        // Bit 9 is the sign of the 1.3.6 or 1.0.9 field.
        int v = bits & 0x3FF;
        if ((v & 0x200) == 0)
            return v;

        return v - 0x400;
    }

    /**
     * @param xy the packed X and Y word
     * @param zWord the word whose low half is Z
     * @return the vertex
     * @see "GBATEK DS 3D Video, VTX_16"
     */
    static Vec3 vtx16(int xy, int zWord)
    {
        // Two fx16 components share the first word. Z is the low half of the second.
        return Vec3.of(fx16(xy), fx16(xy >> 16), fx16(zWord));
    }

    /**
     * @param word a packed XY, XZ or YZ word
     * @param cmd which pair of axes the word replaces
     * @param vtx the running vertex
     * @return the vertex with those axes replaced
     * @see "GBATEK DS 3D Video, VTX_XY, VTX_XZ and VTX_YZ"
     */
    static Vec3 vertexPair(int word, GeometryCommand cmd, Vec3 vtx)
    {
        // The low half is the first named axis and the high half is the second.
        double a = fx16(word);
        double b = fx16(word >> 16);
        if (cmd == GeometryCommand.VTX_XY)
            return vtx.with(0, a).with(1, b);

        return otherPair(cmd, vtx, a, b);
    }

    /**
     * @param cmd XZ or YZ
     * @param vtx the running vertex
     * @param a the low half
     * @param b the high half
     * @return the vertex with those axes replaced
     * @see "GBATEK DS 3D Video, VTX_XZ and VTX_YZ"
     */
    private static Vec3 otherPair(GeometryCommand cmd, Vec3 vtx, double a, double b)
    {
        if (cmd == GeometryCommand.VTX_XZ)
            return vtx.with(0, a).with(2, b);

        return vtx.with(1, a).with(2, b);
    }

    /**
     * @param word a packed 10-bit vertex or delta
     * @param divisor 64 for an absolute vertex, 4096 for a delta
     * @return the three components
     * @see "GBATEK DS 3D Video, VTX_10 and VTX_DIFF"
     */
    static Vec3 vtx10(int word, double divisor)
    {
        // X, Y and Z occupy bits 0-9, 10-19 and 20-29.
        double x = signed10(word) / divisor;
        double y = signed10(word >> 10) / divisor;
        double z = signed10(word >> 20) / divisor;

        return Vec3.of(x, y, z);
    }

    /**
     * @param word a packed S and T word
     * @return the texcoord in texel units
     * @see "GBATEK DS 3D Video, TEXCOORD"
     */
    static Uv texcoord(int word)
    {
        // Each half is 1.11.4 fixed point.
        double s = (short) (word & 0xFFFF) / 16.0;
        double t = (short) ((word >> 16) & 0xFFFF) / 16.0;

        return new Uv(s, t);
    }
}
