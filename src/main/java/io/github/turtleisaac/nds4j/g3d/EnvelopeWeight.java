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
 * One NODEMIX term: the joint it samples and that term's blend weight.
 * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_NODEMIX"
 */
final class EnvelopeWeight
{
    final int joint;
    final double weight;

    private EnvelopeWeight(int joint, double weight)
    {
        this.joint = joint;
        this.weight = weight;
    }

    /**
     * @param joint node index
     * @param weight blend weight, about {@code 1/256} steps from the SBC byte
     * @return that term
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_NODEMIX"
     */
    static EnvelopeWeight of(int joint, double weight)
    {
        // The SBC weight byte is shifted into 1.3.12 before it is stored.
        return new EnvelopeWeight(joint, weight);
    }
}
