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

import io.github.turtleisaac.nds4j.framework.MemBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * An object representation of an NSBMD file (a Nitro 3D model set, magic {@code BMD0}).
 * <p>
 * An NSBMD holds an {@code MDL0} block naming one or more {@link Model}s and, in the common case, an
 * embedded {@code TEX0} texture block (5274 of the 5482 models in the Gen IV ROMs embed their
 * textures). The container is read and preserved by {@link G3dFile}, so an unedited file round-trips
 * byte-for-byte; the {@link Model} geometry inside {@code MDL0} is decoded on top of that.
 */
public class ModelSet extends G3dFile
{
    private final List<Model> models = new ArrayList<>();

    /**
     * Generates an object representation of an NSBMD file.
     * @param data a <code>byte[]</code> representation of an NSBMD file
     */
    public ModelSet(byte[] data)
    {
        super("BMD0");
        readContainer(data);
        int mdl0Index = indexOfBlock("MDL0");
        if (mdl0Index < 0)
            throw new RuntimeException("Not a valid BMD0 file: missing MDL0 block.");
        parseModels(block(mdl0Index));
    }

    private void parseModels(byte[] mdl0)
    {
        MemBuf buf = MemBuf.create(mdl0);
        MemBuf.MemBufReader reader = buf.reader();
        reader.setPosition(8); // model dictionary follows the MDL0 magic + size
        G3dDictionary modelDict = new G3dDictionary(reader);
        for (int i = 0; i < modelDict.size(); i++)
        {
            byte[] rec = modelDict.getRecord(i); // 4-byte offset to the model, relative to the MDL0 block
            int modelStart = (rec[0] & 0xFF) | ((rec[1] & 0xFF) << 8) | ((rec[2] & 0xFF) << 16) | ((rec[3] & 0xFF) << 24);
            models.add(new Model(mdl0, modelStart, modelDict.getName(i)));
        }
    }

    /**
     * Gets the models in this file.
     * @return a <code>List</code> of {@link Model}
     */
    public List<Model> getModels()
    {
        return models;
    }

    /**
     * Gets whether this file embeds its own textures (a {@code TEX0} block).
     * @return a <code>boolean</code>
     */
    public boolean hasEmbeddedTextures()
    {
        return indexOfBlock("TEX0") >= 0;
    }

    /**
     * Gets a decoder over this model's embedded textures, or null if it has none. The returned
     * {@link TextureSet} decodes and exports the {@code TEX0} textures the model's materials reference
     * by name; it is a read-only view (the byte-exact round-trip stays with this {@link ModelSet}).
     * @return a {@link TextureSet} or null
     */
    public TextureSet getEmbeddedTextures()
    {
        int tex0Index = indexOfBlock("TEX0");
        return tex0Index < 0 ? null : TextureSet.fromTex0Block(block(tex0Index));
    }

    @Override
    public String toString()
    {
        return String.format("ModelSet[%d models%s]", models.size(), hasEmbeddedTextures() ? ", with textures" : "");
    }
}
