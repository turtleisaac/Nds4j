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
    NOP(0x00, false, false, true, 0),

    /** One word, the matrix mode. @see "GBATEK DS 3D Video, MTX_MODE" */
    MTX_MODE(0x10, false, true, true, 1),

    /** No parameters. @see "GBATEK DS 3D Video, MTX_PUSH" */
    MTX_PUSH(0x11, false, false, false, 0),

    /** One word. @see "GBATEK DS 3D Video, MTX_POP" */
    MTX_POP(0x12, false, true, false, 1),

    /** One word, the stack slot. @see "GBATEK DS 3D Video, MTX_STORE" */
    MTX_STORE(0x13, false, true, false, 1),

    /** One word, the stack slot. @see "GBATEK DS 3D Video, MTX_RESTORE" */
    MTX_RESTORE(0x14, false, true, true, 1),

    /** No parameters. @see "GBATEK DS 3D Video, MTX_IDENTITY" */
    MTX_IDENTITY(0x15, false, false, false, 0),

    /** Sixteen words. @see "GBATEK DS 3D Video, MTX_LOAD_4x4" */
    MTX_LOAD_4x4(0x16, false, false, false, 16),

    /** Twelve words. @see "GBATEK DS 3D Video, MTX_LOAD_4x3" */
    MTX_LOAD_4x3(0x17, false, false, false, 12),

    /** Sixteen words. @see "GBATEK DS 3D Video, MTX_MULT_4x4" */
    MTX_MULT_4x4(0x18, false, false, false, 16),

    /** Twelve words. @see "GBATEK DS 3D Video, MTX_MULT_4x3" */
    MTX_MULT_4x3(0x19, false, false, false, 12),

    /** Nine words. @see "GBATEK DS 3D Video, MTX_MULT_3x3" */
    MTX_MULT_3x3(0x1A, false, false, false, 9),

    /** Three fixed-point words. @see "GBATEK DS 3D Video, MTX_SCALE" */
    MTX_SCALE(0x1B, false, false, true, 3),

    /** Three fixed-point words. @see "GBATEK DS 3D Video, MTX_TRANS" */
    MTX_TRANS(0x1C, false, false, false, 3),

    /** One colour word. @see "GBATEK DS 3D Video, COLOR" */
    COLOR(0x20, false, true, true, 1),

    /** One normal word. @see "GBATEK DS 3D Video, NORMAL" */
    NORMAL(0x21, false, true, true, 1),

    /** One texcoord word. @see "GBATEK DS 3D Video, TEXCOORD" */
    TEXCOORD(0x22, false, true, true, 1),

    /** Two vertex words. @see "GBATEK DS 3D Video, VTX_16" */
    VTX_16(0x23, true, false, true, 2),

    /** One packed vertex word. @see "GBATEK DS 3D Video, VTX_10" */
    VTX_10(0x24, true, false, true, 1),

    /** One word, X and Y. @see "GBATEK DS 3D Video, VTX_XY" */
    VTX_XY(0x25, true, false, true, 1),

    /** One word, X and Z. @see "GBATEK DS 3D Video, VTX_XZ" */
    VTX_XZ(0x26, true, false, true, 1),

    /** One word, Y and Z. @see "GBATEK DS 3D Video, VTX_YZ" */
    VTX_YZ(0x27, true, false, true, 1),

    /** One packed delta word. @see "GBATEK DS 3D Video, VTX_DIFF" */
    VTX_DIFF(0x28, true, false, true, 1),

    /** One polygon-attribute word. @see "GBATEK DS 3D Video, POLYGON_ATTR" */
    POLYGON_ATTR(0x29, false, true, true, 1),

    /** One texture-parameter word. @see "GBATEK DS 3D Video, TEXIMAGE_PARAM" */
    TEXIMAGE_PARAM(0x2A, false, true, true, 1),

    /** One palette-base word. @see "GBATEK DS 3D Video, PLTT_BASE" */
    PLTT_BASE(0x2B, false, true, true, 1),

    /** One word. @see "GBATEK DS 3D Video, DIF_AMB" */
    DIF_AMB(0x30, false, true, false, 1),

    /** One word. @see "GBATEK DS 3D Video, SPE_EMI" */
    SPE_EMI(0x31, false, true, false, 1),

    /** One word. @see "GBATEK DS 3D Video, LIGHT_VECTOR" */
    LIGHT_VECTOR(0x32, false, true, false, 1),

    /** One word. @see "GBATEK DS 3D Video, LIGHT_COLOR" */
    LIGHT_COLOR(0x33, false, true, false, 1),

    /** Thirty-two words. @see "GBATEK DS 3D Video, SHININESS" */
    SHININESS(0x34, false, false, false, 32),

    /** One primitive-type word. @see "GBATEK DS 3D Video, BEGIN_VTXS" */
    BEGIN_VTXS(0x40, false, true, true, 1),

    /** No parameters. @see "GBATEK DS 3D Video, END_VTXS" */
    END_VTXS(0x41, false, false, true, 0),

    /** One word. @see "GBATEK DS 3D Video, SWAP_BUFFERS" */
    SWAP_BUFFERS(0x50, false, true, false, 1),

    /** One word. @see "GBATEK DS 3D Video, VIEWPORT" */
    VIEWPORT(0x60, false, true, false, 1),

    /** Three words. @see "GBATEK DS 3D Video, BOX_TEST" */
    BOX_TEST(0x70, false, false, false, 3),

    /** Two words. @see "GBATEK DS 3D Video, POS_TEST" */
    POS_TEST(0x71, false, false, false, 2),

    /** One word. @see "GBATEK DS 3D Video, VEC_TEST" */
    VEC_TEST(0x72, false, true, false, 1);

    final int code;

    /** True when the command emits a vertex. */
    final boolean emitsVertex;

    /** True when a command this decoder does not interpret still takes one parameter word. */
    final boolean parameterWord;

    /** True when a static mesh walk knows how to consume the command. */
    final boolean inMesh;

    /** Parameter words that follow this opcode. @see "GBATEK DS 3D Video" */
    final int words;

    /**
     * @param code geometry opcode
     * @param emitsVertex true when the command emits a vertex
     * @param parameterWord true when an ignored command still takes one word
     * @param inMesh true when the static mesh walk consumes the command
     * @param words parameter words that follow the opcode
     */
    GeometryCommand(int code, boolean emitsVertex, boolean parameterWord, boolean inMesh, int words)
    {
        this.code = code;
        this.emitsVertex = emitsVertex;
        this.parameterWord = parameterWord;
        this.inMesh = inMesh;
        this.words = words;
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
