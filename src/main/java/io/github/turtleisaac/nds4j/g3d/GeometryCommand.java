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
 * Geometry-engine commands a shape display list uses. Names are the GBATEK command names.
 */
enum GeometryCommand
{
    /** No parameters. @see "GBATEK DS 3D Video, NOP" */
    NOP(0x00, false, false, true),

    /** One word, the matrix mode. @see "GBATEK DS 3D Video, MTX_MODE" */
    MTX_MODE(0x10, false, true, true),

    /** One word, the stack slot. @see "GBATEK DS 3D Video, MTX_RESTORE" */
    MTX_RESTORE(0x14, false, true, true),

    /** No parameters. @see "GBATEK DS 3D Video, MTX_IDENTITY" */
    MTX_IDENTITY(0x15, false, false, false),

    /** Three fixed-point words. @see "GBATEK DS 3D Video, MTX_SCALE" */
    MTX_SCALE(0x1B, false, false, true),

    /** Three fixed-point words. @see "GBATEK DS 3D Video, MTX_TRANS" */
    MTX_TRANS(0x1C, false, false, false),

    /** One colour word. @see "GBATEK DS 3D Video, COLOR" */
    COLOR(0x20, false, true, true),

    /** One normal word. @see "GBATEK DS 3D Video, NORMAL" */
    NORMAL(0x21, false, true, true),

    /** One texcoord word. @see "GBATEK DS 3D Video, TEXCOORD" */
    TEXCOORD(0x22, false, true, true),

    /** Two vertex words. @see "GBATEK DS 3D Video, VTX_16" */
    VTX_16(0x23, true, false, true),

    /** One packed vertex word. @see "GBATEK DS 3D Video, VTX_10" */
    VTX_10(0x24, true, false, true),

    /** One word, X and Y. @see "GBATEK DS 3D Video, VTX_XY" */
    VTX_XY(0x25, true, false, true),

    /** One word, X and Z. @see "GBATEK DS 3D Video, VTX_XZ" */
    VTX_XZ(0x26, true, false, true),

    /** One word, Y and Z. @see "GBATEK DS 3D Video, VTX_YZ" */
    VTX_YZ(0x27, true, false, true),

    /** One packed delta word. @see "GBATEK DS 3D Video, VTX_DIFF" */
    VTX_DIFF(0x28, true, false, true),

    /** One polygon-attribute word. @see "GBATEK DS 3D Video, POLYGON_ATTR" */
    POLYGON_ATTR(0x29, false, true, true),

    /** One texture-parameter word. @see "GBATEK DS 3D Video, TEXIMAGE_PARAM" */
    TEXIMAGE_PARAM(0x2A, false, true, true),

    /** One palette-base word. @see "GBATEK DS 3D Video, PLTT_BASE" */
    PLTT_BASE(0x2B, false, true, true),

    /** One primitive-type word. @see "GBATEK DS 3D Video, BEGIN_VTXS" */
    BEGIN_VTXS(0x40, false, true, true),

    /** No parameters. @see "GBATEK DS 3D Video, END_VTXS" */
    END_VTXS(0x41, false, false, true);

    final int code;

    /** True when the command emits a vertex. */
    final boolean emitsVertex;

    /** True when a command this decoder does not interpret still takes one parameter word. */
    final boolean parameterWord;

    /** True when a static mesh walk knows how to consume the command. */
    final boolean inMesh;

    /**
     * @param code geometry opcode
     * @param emitsVertex true when the command emits a vertex
     * @param parameterWord true when an ignored command still takes one word
     * @param inMesh true when the static mesh walk consumes the command
     */
    GeometryCommand(int code, boolean emitsVertex, boolean parameterWord, boolean inMesh)
    {
        this.code = code;
        this.emitsVertex = emitsVertex;
        this.parameterWord = parameterWord;
        this.inMesh = inMesh;
    }

    /**
     * @param opcode a geometry opcode read from a display list
     * @return the command, or null when the opcode is not one of these
     * @see "GBATEK DS 3D Video"
     */
    static GeometryCommand from(int opcode)
    {
        int code = opcode & 0xFF;
        for (GeometryCommand cmd : values())
        {
            if (cmd.code == code)
                return cmd;
        }

        return null;
    }
}
