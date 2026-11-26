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
 * Option byte of {@link SbcCommand#NNS_G3D_SBC_NODEDESC} when the model scaling rule is Maya.
 */
enum NodeDescFlag
{
    /**
     * Multiply this joint by the inverse of its parent's scale.
     * @see "NitroSystem g3d/src/cgtool/maya.c NNSi_G3dGetJointScaleMaya"
     */
    NNS_G3D_SBC_NODEDESC_FLAG_MAYASSC_APPLY(0x01),

    /**
     * Remember this joint's scale so a child can cancel it.
     * @see "NitroSystem g3d/src/cgtool/maya.c NNSi_G3dGetJointScaleMaya"
     */
    NNS_G3D_SBC_NODEDESC_FLAG_MAYASSC_PARENT(0x02);

    final int mask;

    /**
     * @param mask bits this option occupies in the NODEDESC option byte
     */
    NodeDescFlag(int mask)
    {
        this.mask = mask;
    }

}
