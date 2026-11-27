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
 * An axis-aligned box in model space.
 * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNSG3dResMdlInfo box"
 */
final class Box
{
    final Vec3 min;
    final Vec3 max;

    private Box(Vec3 min, Vec3 max)
    {
        this.min = min;
        this.max = max;
    }

    /**
     * @param min the minimum corner
     * @param max the maximum corner
     * @return that box
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNSG3dResMdlInfo"
     */
    static Box of(Vec3 min, Vec3 max)
    {
        // Header boxes and decoded bounds share this shape.
        return new Box(min, max);
    }

    /**
     * @param axis 0, 1 or 2
     * @return the minimum on that axis, at float precision
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNSG3dResMdlInfo"
     */
    float min(int axis)
    {
        // The header stores the corner in single precision.
        return (float) min.at(axis);
    }

    /**
     * @param axis 0, 1 or 2
     * @return the maximum on that axis, at float precision
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNSG3dResMdlInfo"
     */
    float max(int axis)
    {
        // Decoded bounds are reduced to the same precision as the header.
        double value = max.at(axis);
        float corner = (float) value;

        return corner;
    }
}
