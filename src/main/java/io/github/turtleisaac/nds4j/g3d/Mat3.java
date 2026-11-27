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
 * A row-major 3×3 matrix in the Nitro row-vector convention {@code v' = v · M}.
 * @see "NitroSDK include/nitro/fx/fx_mtx33.h MtxFx33"
 */
final class Mat3
{
    final double m00;
    final double m01;
    final double m02;
    final double m10;
    final double m11;
    final double m12;
    final double m20;
    final double m21;
    final double m22;

    private Mat3(double m00, double m01, double m02, double m10, double m11, double m12, double m20, double m21, double m22)
    {
        this.m00 = m00;
        this.m01 = m01;
        this.m02 = m02;
        this.m10 = m10;
        this.m11 = m11;
        this.m12 = m12;
        this.m20 = m20;
        this.m21 = m21;
        this.m22 = m22;
    }

    /**
     * @return the identity rotation
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SRTFLAG_ROT_ZERO"
     */
    static Mat3 identity()
    {
        // An omitted rotation is the identity.
        return new Mat3(1, 0, 0, 0, 1, 0, 0, 0, 1);
    }

    /**
     * @param m00 row 0, column 0
     * @param m01 row 0, column 1
     * @param m02 row 0, column 2
     * @param m10 row 1, column 0
     * @param m11 row 1, column 1
     * @param m12 row 1, column 2
     * @param m20 row 2, column 0
     * @param m21 row 2, column 1
     * @param m22 row 2, column 2
     * @return that matrix
     * @see "NitroSDK include/nitro/fx/fx_mtx33.h MtxFx33"
     */
    static Mat3 of(double m00, double m01, double m02, double m10, double m11, double m12, double m20, double m21, double m22)
    {
        // Node records store the nine cells in this row-major order.
        return new Mat3(m00, m01, m02, m10, m11, m12, m20, m21, m22);
    }

    /**
     * @param v a row vector
     * @return {@code v · this}
     * @see "NitroSDK fx_mtx33.c MTX_MultVec33"
     */
    Vec3 mul(Vec3 v)
    {
        // Row-vector product, one output component per column.
        double x = v.x * m00 + v.y * m10 + v.z * m20;
        double y = v.x * m01 + v.y * m11 + v.z * m21;
        double z = v.x * m02 + v.y * m12 + v.z * m22;

        return Vec3.of(x, y, z);
    }

    /**
     * @param b the matrix applied second
     * @return {@code this · b}
     * @see "NitroSDK fx_mtx33.c MTX_Concat33"
     */
    Mat3 mul(Mat3 b)
    {
        // Each cell is a dot of this row with that column of b.
        return new Mat3(
                dot(m00, m01, m02, b.m00, b.m10, b.m20),
                dot(m00, m01, m02, b.m01, b.m11, b.m21),
                dot(m00, m01, m02, b.m02, b.m12, b.m22),
                dot(m10, m11, m12, b.m00, b.m10, b.m20),
                dot(m10, m11, m12, b.m01, b.m11, b.m21),
                dot(m10, m11, m12, b.m02, b.m12, b.m22),
                dot(m20, m21, m22, b.m00, b.m10, b.m20),
                dot(m20, m21, m22, b.m01, b.m11, b.m21),
                dot(m20, m21, m22, b.m02, b.m12, b.m22));
    }

    /**
     * @param b the matrix at the end of the segment
     * @param t fraction from this matrix toward {@code b}
     * @return the interpolated rotation
     * @see "NitroSystem g3d/src/anm/nsbca.c getRotData_"
     */
    Mat3 lerp(Mat3 b, double t)
    {
        // Each cell is interpolated on its own. The result is not re-orthonormalised.
        return new Mat3(
                m00 + (b.m00 - m00) * t, m01 + (b.m01 - m01) * t, m02 + (b.m02 - m02) * t,
                m10 + (b.m10 - m10) * t, m11 + (b.m11 - m11) * t, m12 + (b.m12 - m12) * t,
                m20 + (b.m20 - m20) * t, m21 + (b.m21 - m21) * t, m22 + (b.m22 - m22) * t);
    }

    /**
     * @param a0 first factor
     * @param a1 second factor
     * @param a2 third factor
     * @param b0 first factor
     * @param b1 second factor
     * @param b2 third factor
     * @return the dot product
     * @see "NitroSDK fx_mtx33.c MTX_Concat33"
     */
    private static double dot(double a0, double a1, double a2, double b0, double b1, double b2)
    {
        // One cell of a 3×3 product.
        return a0 * b0 + a1 * b1 + a2 * b2;
    }

}
