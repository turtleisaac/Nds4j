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
 * One texture coordinate, in texel units.
 * @see "GBATEK DS 3D Video, TEXCOORD"
 */
final class Uv
{
    final double s;
    final double t;

    /**
     * @param s the S coordinate
     * @param t the T coordinate
     */
    Uv(double s, double t)
    {
        this.s = s;
        this.t = t;
    }

    /**
     * @return S and T at the origin
     * @see "GBATEK DS 3D Video, TEXCOORD"
     */
    static Uv zero()
    {
        // A vertex emitted before any TEXCOORD uses the origin.
        return new Uv(0, 0);
    }
}
