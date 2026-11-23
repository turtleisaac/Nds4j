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
 * Whether NNS submits a shape after the material command.
 * <p>
 * {@code NNSi_G3dFuncSbc_MAT} in NitroSystem {@code g3d/src/sbc.c} (the SDK pokeheartgold
 * links as nnsys) reads polygon alpha after {@code funcBlendMat}. Alpha 0 sets
 * {@code NNS_G3D_RSFLAG_MAT_TRANSPARENT} and emits no material commands;
 * {@code NNSi_G3dFuncSbc_SHP} then skips the display list. {@code NNS_G3D_MATFLAG_WIREFRAME}
 * ({@code 0x0020}) is not consulted on that path. It only clears a non-zero alpha before the
 * commands are sent, so the geometry engine draws edges (GBATEK, DS 3D polygon attributes:
 * alpha 0 is wireframe). An authored alpha of 0 is never submitted.
 */
public final class PolygonDraw
{
    /** {@code NNS_G3D_MATFLAG_WIREFRAME} in {@code NNSG3dResMatData.flag}. */
    public static final int WIREFRAME_FLAG = 0x0020;

    /** Not instantiated. */
    private PolygonDraw()
    {
    }

    /**
     * @param polygonAlpha the 5-bit polygon alpha after the material-color blend, 0..31
     * @return false when NNS skips the shape
     */
    public static boolean submitsShape(int polygonAlpha)
    {
        return polygonAlpha != 0;
    }

    /**
     * @param polygonAlpha the 5-bit polygon alpha after the material-color blend, 0..31
     * @param wireframe true when the material's flag word has {@link #WIREFRAME_FLAG}
     * @return true when a submitted shape is drawn as edges
     */
    public static boolean wireframeEdges(int polygonAlpha, boolean wireframe)
    {
        if (!submitsShape(polygonAlpha))
            return false;
        return wireframe;
    }
}
