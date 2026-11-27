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
 * A three-component vector. Node translation, scale and a running vertex all use one.
 * @see "NitroSDK include/nitro/fx/fx.h VecFx32"
 */
final class Vec3
{
    final double x;
    final double y;
    final double z;

    private Vec3(double x, double y, double z)
    {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /**
     * @param x the X component
     * @param y the Y component
     * @param z the Z component
     * @return that vector
     * @see "NitroSDK include/nitro/fx/fx.h VecFx32"
     */
    static Vec3 of(double x, double y, double z)
    {
        // Callers already converted fixed point.
        return new Vec3(x, y, z);
    }

    /**
     * @return the zero vector
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SRTFLAG_TRANS_ZERO"
     */
    static Vec3 zero()
    {
        // An omitted translation is stored as zero.
        return new Vec3(0, 0, 0);
    }

    /**
     * @return the vector {@code (1, 1, 1)}
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SRTFLAG_SCALE_ONE"
     */
    static Vec3 one()
    {
        // An omitted scale is stored as one.
        return new Vec3(1, 1, 1);
    }

    /**
     * @param index 0, 1 or 2
     * @return that component
     * @see "NitroSDK include/nitro/fx/fx.h VecFx32"
     */
    double at(int index)
    {
        // Bounding-box code still walks axes by index.
        if (index == 0)
            return x;

        if (index == 1)
            return y;

        return z;
    }

    /**
     * @param o the vector to add
     * @return the sum
     * @see "NitroSDK include/nitro/fx/fx.h VEC_Add"
     */
    Vec3 add(Vec3 o)
    {
        // VTX_DIFF adds a packed delta to the running vertex.
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    /**
     * @param o per-component scale
     * @return the product
     * @see "NitroSDK gx g3.h G3OP_MTX_SCALE"
     */
    Vec3 mul(Vec3 o)
    {
        // Parent and child scales multiply per axis.
        return new Vec3(x * o.x, y * o.y, z * o.z);
    }

    /**
     * @param index the component to replace
     * @param value the new component
     * @return a copy with that component replaced
     * @see "GBATEK DS 3D Video, VTX_XY, VTX_XZ and VTX_YZ"
     */
    Vec3 with(int index, double value)
    {
        // A pair command rewrites two axes and leaves the third.
        if (index == 0)
            return new Vec3(value, y, z);

        if (index == 1)
            return new Vec3(x, value, z);

        return new Vec3(x, y, value);
    }
}
