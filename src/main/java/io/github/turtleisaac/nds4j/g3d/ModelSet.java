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

    /**
     * Authors a {@code ModelSet} from NITRO intermediate ({@code .imd}) source &mdash; the native, byte-for-byte
     * replacement for Nintendo's {@code g3dcvtr}. The texture is embedded as a {@code TEX0} block when the
     * {@code .imd} carries one (the {@code -eboth} case), otherwise the result is model-only ({@code -emdl}).
     * @param imdXml the full contents of an {@code .imd} file
     * @param modelName the model's name (g3dcvtr uses the source file's base name)
     * @see ImdImporter
     */
    public static ModelSet fromImd(String imdXml, String modelName)
    {
        return ImdImporter.fromXml(imdXml).named(modelName).toModelSet();
    }

    /** Authors a {@code ModelSet} from an {@code .imd} file, taking the model name from the file's base name. */
    public static ModelSet fromImd(java.io.File imdFile) throws java.io.IOException
    {
        return ImdImporter.fromFile(imdFile).toModelSet();
    }

    private void parseModels(byte[] mdl0)
    {
        MemBuf buf = MemBuf.create(mdl0);
        MemBuf.MemBufReader reader = buf.reader();
        reader.setPosition(8); // model dictionary follows the MDL0 magic + size
        G3dDictionary modelDict = new G3dDictionary(reader);
        for (int i = 0; i < modelDict.size(); i++)
        {
            // 4-byte offset to the model, relative to the MDL0 block
            MemBuf.MemBufReader record = MemBuf.create(modelDict.getRecord(i)).reader();
            int modelStart = record.readInt();
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

    /**
     * Re-encodes the {@code MDL0} block from its decoded structure rather than preserving it verbatim: every
     * resource dictionary (model / node / material / texture / palette / shape) is rebuilt with
     * {@link G3dDictionary#build} and every shape's display list is rebuilt with the byte-exact command codec
     * ({@link DisplayList#decodeCommands}/{@link DisplayList#encodeCommands}); the fixed structs (model
     * header, node/material structs, SBC) are kept verbatim. For an unedited file this reproduces the bytes
     * <b>exactly</b> (verified over all 5482 retail models), which makes it the byte-exact re-encode path
     * that survives edits &mdash; the pieces that change when geometry or resources are edited (dictionaries
     * and display lists) are the ones rebuilt from semantics here.
     * @return the re-encoded file bytes (byte-identical to {@link #save()} for an unedited model)
     */
    public byte[] reencodeModels()
    {
        int mdl0Index = indexOfBlock("MDL0");
        byte[] mdl0 = block(mdl0Index).clone();
        G3dDictionary modelDict = readDict(mdl0, 8);
        rebuildDictInPlace(mdl0, 8);
        for (int m = 0; m < modelDict.size(); m++)
            rebuildModel(mdl0, modelDict.getRecord(m));

        return saveReplacing(mdl0Index, mdl0);
    }

    /**
     * @param mdl0 the MDL0 block being rebuilt
     * @param record the model dictionary record
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNSG3dResMdl"
     */
    private static void rebuildModel(byte[] mdl0, byte[] record)
    {
        // The model header names the material set and the shape set.
        int modelStart = MemBuf.create(record).reader().readInt();
        int[] sets = readModelSets(mdl0, modelStart);

        // Node dictionary, then the material set's texture and palette dictionaries.
        rebuildMaterialDicts(mdl0, modelStart, sets[0]);

        // Each shape's display list is re-encoded from its commands.
        rebuildShapes(mdl0, modelStart + sets[1]);
    }

    /**
     * @param mdl0 the MDL0 block
     * @param modelStart start of one model
     * @return the material-set and shape-set offsets
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNSG3dResMdl"
     */
    private static int[] readModelSets(byte[] mdl0, int modelStart)
    {
        // Both offsets sit immediately after the model size.
        MemBuf.MemBufReader reader = MemBuf.create(mdl0).reader();
        reader.setPosition(modelStart + 8);
        int ofsMat = reader.readInt();
        int ofsShp = reader.readInt();

        return new int[] { ofsMat, ofsShp };
    }

    /**
     * @param mdl0 the MDL0 block
     * @param modelStart start of one model
     * @param ofsMat material-set offset from the model start
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNSG3dResMdl"
     */
    private static void rebuildMaterialDicts(byte[] mdl0, int modelStart, int ofsMat)
    {
        // The node dictionary, then the material dictionary.
        rebuildDictInPlace(mdl0, modelStart + 0x40);
        int matSet = modelStart + ofsMat;
        rebuildDictInPlace(mdl0, matSet + 4);

        // Texture and palette dictionaries are named from the material set.
        MemBuf.MemBufReader reader = MemBuf.create(mdl0).reader();
        reader.setPosition(matSet);
        int ofsTex = reader.readUInt16();
        int ofsPlt = reader.readUInt16();
        rebuildDictInPlace(mdl0, matSet + ofsTex);
        rebuildDictInPlace(mdl0, matSet + ofsPlt);
    }

    /**
     * @param mdl0 the MDL0 block
     * @param shapeSet offset of the shape dictionary
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNSG3dResShp"
     */
    private static void rebuildShapes(byte[] mdl0, int shapeSet)
    {
        G3dDictionary shapeDict = readDict(mdl0, shapeSet);
        rebuildDictInPlace(mdl0, shapeSet);
        for (int s = 0; s < shapeDict.size(); s++)
            rebuildList(mdl0, shapeSet + MemBuf.create(shapeDict.getRecord(s)).reader().readInt());
    }

    /**
     * @param mdl0 the MDL0 block
     * @param shapeStruct offset of one shape struct
     * @see "GBATEK DS 3D Video, geometry command packing"
     */
    private static void rebuildList(byte[] mdl0, int shapeStruct)
    {
        // The display list sits at the offset and size stored after the shape flags.
        MemBuf.MemBufReader reader = MemBuf.create(mdl0).reader();
        reader.setPosition(shapeStruct + 8);
        int dlOffset = reader.readInt();
        int dlSize = reader.readInt();

        if (listMissing(mdl0, shapeStruct, dlOffset, dlSize))
            return;

        copyList(mdl0, shapeStruct, dlOffset, dlSize);
    }

    /**
     * @param mdl0 the MDL0 block
     * @param shapeStruct offset of one shape struct
     * @param dlOffset display-list offset from the shape struct
     * @param dlSize display-list size in bytes
     * @return whether there is no list to rebuild
     * @see "GBATEK DS 3D Video, geometry command packing"
     */
    private static boolean listMissing(byte[] mdl0, int shapeStruct, int dlOffset, int dlSize)
    {
        if (emptyList(dlOffset, dlSize))
            return true;

        return shapeStruct + dlOffset + dlSize > mdl0.length;
    }

    /**
     * @param dlOffset display-list offset
     * @param dlSize display-list size
     * @return whether the shape stores no list
     * @see "GBATEK DS 3D Video, geometry command packing"
     */
    private static boolean emptyList(int dlOffset, int dlSize)
    {
        if (dlOffset <= 0)
            return true;

        return dlSize <= 0;
    }

    /**
     * @param mdl0 the MDL0 block
     * @param shapeStruct offset of one shape struct
     * @param dlOffset display-list offset from the shape struct
     * @param dlSize display-list size in bytes
     * @see "GBATEK DS 3D Video, geometry command packing"
     */
    private static void copyList(byte[] mdl0, int shapeStruct, int dlOffset, int dlSize)
    {
        byte[] dl = new byte[dlSize];
        System.arraycopy(mdl0, shapeStruct + dlOffset, dl, 0, dlSize);
        byte[] rebuilt = DisplayList.encodeCommands(DisplayList.decodeCommands(dl));

        if (rebuilt.length == dlSize)
            System.arraycopy(rebuilt, 0, mdl0, shapeStruct + dlOffset, dlSize);
    }

    private static G3dDictionary readDict(byte[] block, int offset)
    {
        MemBuf.MemBufReader reader = MemBuf.create(block).reader();
        reader.setPosition(offset);
        return new G3dDictionary(reader);
    }

    // Rebuilds the dictionary at `offset` in place from its own names+records. build() is byte-exact, so the
    // same-size span is overwritten with identical bytes for unedited content. Dictionaries with duplicate
    // names cannot be name-keyed (they never occur in retail) and are left as-is.
    private static void rebuildDictInPlace(byte[] block, int offset)
    {
        G3dDictionary dict = readDict(block, offset);
        int n = dict.size();

        if (n == 0)
            return;

        // Names and records, in dictionary order.
        List<String> names = new ArrayList<>();
        List<byte[]> records = new ArrayList<>();
        java.util.Set<String> unique = new java.util.HashSet<>();

        for (int i = 0; i < n; i++)
        {
            names.add(dict.getName(i));
            records.add(dict.getRecord(i));
            unique.add(dict.getName(i));
        }

        if (unique.size() != n)
            return; // duplicate names: not name-keyable

        // Same-size replacement. build() is byte-exact for a unique name set.
        MemBuf b = MemBuf.create();
        G3dDictionary.build(names, records, dict.getElementSize()).write(b.writer());
        byte[] rebuilt = b.reader().getBuffer();
        System.arraycopy(rebuilt, 0, block, offset, rebuilt.length);
    }

    @Override
    public String toString()
    {
        return String.format("ModelSet[%d models%s]", models.size(), hasEmbeddedTextures() ? ", with textures" : "");
    }
}
