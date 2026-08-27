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
 * An object representation of an NSBMD file (a Nitro 3D model, magic {@code BMD0}).
 * <p>
 * An NSBMD holds an {@code MDL0} model block and, in the common case, an embedded {@code TEX0}
 * texture block (5274 of the 5482 models in the Gen IV ROMs embed their textures). The container is
 * read and preserved by {@link G3dFile}, so an unedited file round-trips byte-for-byte; the model
 * geometry, materials and skeleton inside {@code MDL0} are decoded on top of that in later work.
 */
public class ModelSet extends G3dFile
{
    /**
     * Generates an object representation of an NSBMD file.
     * @param data a <code>byte[]</code> representation of an NSBMD file
     */
    public ModelSet(byte[] data)
    {
        super("BMD0");
        readContainer(data);
        if (indexOfBlock("MDL0") < 0)
            throw new RuntimeException("Not a valid BMD0 file: missing MDL0 block.");
    }

    /**
     * Gets whether this model embeds its own textures (a {@code TEX0} block).
     * @return a <code>boolean</code>
     */
    public boolean hasEmbeddedTextures()
    {
        return indexOfBlock("TEX0") >= 0;
    }

    @Override
    public String toString()
    {
        return "ModelSet" + (hasEmbeddedTextures() ? "[with textures]" : "[no textures]");
    }
}
