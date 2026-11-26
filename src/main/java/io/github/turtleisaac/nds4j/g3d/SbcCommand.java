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
 * SBC opcodes. The command byte's low five bits select one of these; the high three bits are
 * {@link SbcFlag}s.
 */
enum SbcCommand
{
    /** Does nothing. @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_NOP" */
    NNS_G3D_SBC_NOP(0x00),

    /** Ends the SBC stream. @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_RET" */
    NNS_G3D_SBC_RET(0x01),

    /** Sets the current node. @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_NODE" */
    NNS_G3D_SBC_NODE(0x02),

    /** Restores the current matrix from the stack. @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_MTX" */
    NNS_G3D_SBC_MTX(0x03),

    /** Sets the current material. @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_MAT" */
    NNS_G3D_SBC_MAT(0x04),

    /** Draws a shape. @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_SHP" */
    NNS_G3D_SBC_SHP(0x05),

    /** Computes one joint. @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_NODEDESC" */
    NNS_G3D_SBC_NODEDESC(0x06),

    /** Billboard about the node. @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_BB" */
    NNS_G3D_SBC_BB(0x07),

    /** Billboard about Y. @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_BBY" */
    NNS_G3D_SBC_BBY(0x08),

    /** Blends stack matrices. @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_NODEMIX" */
    NNS_G3D_SBC_NODEMIX(0x09),

    /** Sends a display list. @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_CALLDL" */
    NNS_G3D_SBC_CALLDL(0x0A),

    /** Scales by the model position scale. @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_POSSCALE" */
    NNS_G3D_SBC_POSSCALE(0x0B),

    /** Environment-map texture matrix. @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_ENVMAP" */
    NNS_G3D_SBC_ENVMAP(0x0C),

    /** Projection-map texture matrix. @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_PRJMAP" */
    NNS_G3D_SBC_PRJMAP(0x0D);

    /** Opcode field of a command byte. The high three bits are flags. */
    private static final int OPCODE_MASK = 0x1F;

    private static final SbcCommand[] BY_CODE = new SbcCommand[32];

    static
    {
        for (SbcCommand cmd : values())
        {
            BY_CODE[cmd.code] = cmd;
        }
    }

    final int code;

    /**
     * @param code opcode in the low five bits of the command byte
     */
    SbcCommand(int code)
    {
        this.code = code;
    }

    /**
     * @param commandByte an SBC command byte read from the stream
     * @return the opcode, or null when that opcode is not defined
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNS_G3D_SBC_NOP"
     */
    static SbcCommand from(int commandByte)
    {
        return BY_CODE[commandByte & OPCODE_MASK];
    }
}
