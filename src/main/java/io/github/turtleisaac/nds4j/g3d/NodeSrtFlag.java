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
 * Flag word of an {@code NNSG3dResNodeData} record. A set bit omits the matching identity component.
 */
enum NodeSrtFlag
{
    /** Translation is zero and is not stored. @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData" */
    NNS_G3D_SRTFLAG_TRANS_ZERO(0x0001),

    /** Rotation is the identity and is not stored. @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData" */
    NNS_G3D_SRTFLAG_ROT_ZERO(0x0002),

    /** Scale is one and is not stored. @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData" */
    NNS_G3D_SRTFLAG_SCALE_ONE(0x0004),

    /** Rotation is the compressed pivot form. @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData" */
    NNS_G3D_SRTFLAG_PIVOT_EXIST(0x0008),

    /** The pivot cell is -1. @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData" */
    NNS_G3D_SRTFLAG_PIVOT_MINUS(0x0100),

    /** Negate the reconstructed pivot value c. @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData" */
    NNS_G3D_SRTFLAG_SIGN_REVC(0x0200),

    /** Negate the reconstructed pivot value d. @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData" */
    NNS_G3D_SRTFLAG_SIGN_REVD(0x0400);

    final int mask;

    /**
     * @param mask bits this flag occupies in the node flag word
     */
    NodeSrtFlag(int mask)
    {
        this.mask = mask;
    }

}
