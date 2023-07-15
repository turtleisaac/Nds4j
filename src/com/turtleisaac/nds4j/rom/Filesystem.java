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

package com.turtleisaac.nds4j.rom;

import com.turtleisaac.nds4j.framework.BinaryWriter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;

public class Filesystem
{
    public static final String outputDataDirName = "data";

    public static void writeFolder(String dir, Fnt.Folder folder)
    {

    }

    /**
     * writes the ROM's internal filesystem to disk at the specified path
     * @param dir a <code>File</code> representation of the write path
     * @param folder the <code>Folder</code>> object to write
     */
    public static void writeFolder(File dir, Fnt.Folder folder, ArrayList<byte[]> files) throws IOException
    {
        if (!dir.mkdir())
        {
            throw new RuntimeException("Could not create data dir, check write perms.");
        }

        for (String name : folder.getFolders().keySet())
        {
            writeFolder(Paths.get(dir.getAbsolutePath(), name).toFile(), folder.getFolders().get(name), files);
        }

        int counter = 0;
        for (String name : folder.getFiles())
        {
            BinaryWriter.writeFile(Paths.get(dir.getAbsolutePath(), name).toFile(), files.get(folder.getFirstId() + counter++));
        }
    }
}
