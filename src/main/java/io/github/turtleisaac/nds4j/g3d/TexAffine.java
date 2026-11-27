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
 * A 2×3 texture matrix, {@code (s', t') = (s, t, 1) · M}, from one NSBTA sample.
 * @see "NitroSystem g3d/src/anm/nsbta.c NNS_G3dAnmObjCalcTexSRT"
 */
final class TexAffine
{
    /** Column of S in the linear part. */
    final float a;

    /** Column of S, T row. */
    final float b;

    /** Column of T, S row. */
    final float c;

    /** Column of T. */
    final float d;

    /** S translation. */
    final float e;

    /** T translation. */
    final float f;

    private TexAffine(float a, float b, float c, float d, float e, float f)
    {
        this.a = a;
        this.b = b;
        this.c = c;
        this.d = d;
        this.e = e;
        this.f = f;
    }

    /**
     * @param scaleS scale along S
     * @param scaleT scale along T
     * @param radians rotation of the texture matrix
     * @param transS translation along S
     * @param transT translation along T
     * @return scale and rotation, then translation
     * @see "NitroSystem g3d/src/anm/nsbta.c NNS_G3dAnmObjCalcTexSRT"
     */
    static TexAffine of(float scaleS, float scaleT, double radians, float transS, float transT)
    {
        // Rotate the two scale axes, then translate.
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);

        return new TexAffine(
                (float) (scaleS * cos), (float) (-scaleT * sin),
                (float) (scaleS * sin), (float) (scaleT * cos),
                transS, transT);
    }
}
