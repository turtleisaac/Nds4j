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
 * Row-vector 4×3 matrices in the NitroSDK {@code MtxFx43} layout
 * ({@code v' = v · M + t}).
 */
final class NitroMtx
{
    private NitroMtx()
    {
    }

    /**
     * @return the identity 4×3
     * @see "NitroSDK fx_mtx43.c MTX_Identity43"
     */
    static double[] identity()
    {
        return new double[] { 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0 };
    }

    /**
     * @param x scale on X
     * @param y scale on Y
     * @param z scale on Z
     * @return a diagonal scale matrix
     * @see "NitroSDK gx g3.h G3OP_MTX_SCALE"
     */
    static double[] scale(double x, double y, double z)
    {
        return new double[] { x, 0, 0, 0, y, 0, 0, 0, z, 0, 0, 0 };
    }

    /**
     * @param a the matrix applied first
     * @param b the matrix applied second
     * @return {@code a} then {@code b}, so {@code (v · a) · b = v · mul(a, b)}
     * @see "NitroSDK fx_mtx43.c MTX_Concat43"
     */
    static double[] mul(double[] a, double[] b)
    {
        double[] m = new double[12];
        for (int col = 0; col < 3; col++)
        {
            m[col] = a[0] * b[col] + a[1] * b[3 + col] + a[2] * b[6 + col];
            m[3 + col] = a[3] * b[col] + a[4] * b[3 + col] + a[5] * b[6 + col];
            m[6 + col] = a[6] * b[col] + a[7] * b[3 + col] + a[8] * b[6 + col];
            m[9 + col] = a[9] * b[col] + a[10] * b[3 + col] + a[11] * b[6 + col] + b[9 + col];
        }
        return m;
    }

    /**
     * Maya joint {@code T · inv(parentScale) · R · S} as a row-vector matrix
     * (scale, then rotation, then inverse parent scale, then translation).
     * @param t translation
     * @param r row-major rotation, {@code v' = v · r}
     * @param s per-axis scale
     * @param invParent inverse of the parent's scale, or {@code (1,1,1)} when SSC does not apply
     * @return the joint matrix
     * @see "NitroSystem g3d/src/cgtool/maya.c NNSi_G3dSendJointSRTMaya"
     */
    static double[] mayaJoint(double[] t, double[] r, double[] s, double[] invParent)
    {
        double[] m = new double[12];
        for (int i = 0; i < 3; i++)
            for (int c = 0; c < 3; c++)
                m[i * 3 + c] = s[i] * r[i * 3 + c] * invParent[c];
        m[9] = t[0];
        m[10] = t[1];
        m[11] = t[2];
        return m;
    }

    /**
     * @param m a 4×3 matrix
     * @param x vertex X
     * @param y vertex Y
     * @param z vertex Z
     * @param dest output of three floats
     * @param o index of X in {@code dest}
     * @see "NitroSDK fx_mtx43.c MTX_MultVec43"
     */
    static void apply(double[] m, double x, double y, double z, float[] dest, int o)
    {
        dest[o] = (float) (x * m[0] + y * m[3] + z * m[6] + m[9]);
        dest[o + 1] = (float) (x * m[1] + y * m[4] + z * m[7] + m[10]);
        dest[o + 2] = (float) (x * m[2] + y * m[5] + z * m[8] + m[11]);
    }

    /**
     * @param sum accumulator, length 12
     * @param m matrix to add
     * @param w weight
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_NODEMIX"
     */
    static void mad(double[] sum, double[] m, double w)
    {
        for (int i = 0; i < 12; i++)
            sum[i] += w * m[i];
    }

    /**
     * @param s a scale vector
     * @return its component-wise inverse, with zero staying zero
     * @see "NitroSystem g3d/src/cgtool/maya.c NNSi_G3dGetJointScaleMaya"
     */
    static double[] safeInv(double[] s)
    {
        return new double[] { s[0] == 0 ? 0 : 1 / s[0], s[1] == 0 ? 0 : 1 / s[1], s[2] == 0 ? 0 : 1 / s[2] };
    }

    /**
     * Splits a joint into translation, rotation and positive scale.
     * A zero-length row becomes the corresponding identity axis so the rotation stays usable.
     * @param m a 4×3 joint matrix
     * @return {@code {translation[3], rotation[9], scale[3]}}
     * @see "NitroSystem g3d/src/cgtool/maya.c NNSi_G3dSendJointSRTMaya"
     */
    static double[][] decompose(double[] m)
    {
        double[] translation = { m[9], m[10], m[11] };
        double[] rotation = new double[9];
        double[] scale = new double[3];

        for (int i = 0; i < 3; i++)
            scale[i] = fillRow(m, rotation, i);
        return new double[][] { translation, rotation, scale };
    }

    /**
     * @param m a 4×3 joint matrix
     * @param rotation row-major rotation being filled
     * @param row row index
     * @return that row's length
     * @see "NitroSystem g3d/src/cgtool/maya.c NNSi_G3dSendJointSRTMaya"
     */
    private static double fillRow(double[] m, double[] rotation, int row)
    {
        double length = 0;
        for (int c = 0; c < 3; c++)
            length += m[row * 3 + c] * m[row * 3 + c];

        length = Math.sqrt(length);
        if (length < 1e-8)
            rotation[row * 3 + row] = 1;
        else
            copyRow(m, rotation, row, length);

        return length;
    }

    /**
     * @param m a 4×3 joint matrix
     * @param rotation destination rotation
     * @param row row index
     * @param length row length
     * @see "NitroSystem g3d/src/cgtool/maya.c NNSi_G3dSendJointSRTMaya"
     */
    private static void copyRow(double[] m, double[] rotation, int row, double length)
    {
        for (int c = 0; c < 3; c++)
            rotation[row * 3 + c] = m[row * 3 + c] / length;
    }

    /**
     * @param m a 4×3 matrix
     * @return its inverse, or null when the 3×3 is singular
     * @see "NitroSDK fx_mtx43.c MTX_Inverse43"
     * Let's be clear if i knew i'd be writing this much matrix
     * I wouldn't be doing this...
     */
    static double[] invert(double[] m)
    {
        double a00 = m[0], a01 = m[3], a02 = m[6];
        double a10 = m[1], a11 = m[4], a12 = m[7];
        double a20 = m[2], a21 = m[5], a22 = m[8];
        double det = a00 * (a11 * a22 - a12 * a21) - a01 * (a10 * a22 - a12 * a20) + a02 * (a10 * a21 - a11 * a20);

        if (Math.abs(det) < 1e-12)
            return null;

        double i00 = (a11 * a22 - a12 * a21) / det;
        double i01 = (a02 * a21 - a01 * a22) / det;
        double i02 = (a01 * a12 - a02 * a11) / det;
        double i10 = (a12 * a20 - a10 * a22) / det;
        double i11 = (a00 * a22 - a02 * a20) / det;
        double i12 = (a02 * a10 - a00 * a12) / det;
        double i20 = (a10 * a21 - a11 * a20) / det;
        double i21 = (a01 * a20 - a00 * a21) / det;
        double i22 = (a00 * a11 - a01 * a10) / det;
        double tx = m[9], ty = m[10], tz = m[11];

        return new double[] {
                i00, i10, i20,
                i01, i11, i21,
                i02, i12, i22,
                -(tx * i00 + ty * i01 + tz * i02),
                -(tx * i10 + ty * i11 + tz * i12),
                -(tx * i20 + ty * i21 + tz * i22) };
    }
}
