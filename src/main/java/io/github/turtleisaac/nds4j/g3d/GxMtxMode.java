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

/**
 * Parameter of {@link GeometryCommand#MTX_MODE}. The geometry engine starts a shape in position-vector mode.
 */
enum GxMtxMode
{
    /** Projection matrix. @see "NitroSDK gx g3.h GX_MTXMODE_PROJECTION" */
    GX_MTXMODE_PROJECTION(0),

    /** Position matrix. @see "NitroSDK gx g3.h GX_MTXMODE_POSITION" */
    GX_MTXMODE_POSITION(1),

    /** Position and direction matrices. @see "NitroSDK gx g3.h GX_MTXMODE_POSITION_VECTOR" */
    GX_MTXMODE_POSITION_VECTOR(2),

    /** Texture matrix. @see "NitroSDK gx g3.h GX_MTXMODE_TEXTURE" */
    GX_MTXMODE_TEXTURE(3);

    final int code;

    /**
     * @param code the MTX_MODE parameter
     */
    GxMtxMode(int code)
    {
        this.code = code;
    }

    /**
     * @param raw the MTX_MODE parameter word
     * @return the mode in the low two bits
     * @see "GBATEK DS 3D Video, MTX_MODE"
     */
    static GxMtxMode from(int raw)
    {
        switch (raw & 3)
        {
            case 0:
                return GX_MTXMODE_PROJECTION;
            case 1:
                return GX_MTXMODE_POSITION;
            case 2:
                return GX_MTXMODE_POSITION_VECTOR;
            default:
                return GX_MTXMODE_TEXTURE;
        }
    }
}
