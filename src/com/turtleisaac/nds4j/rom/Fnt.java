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

import com.turtleisaac.nds4j.framework.Buffer;
import com.turtleisaac.nds4j.framework.MemBuf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.stream.Collectors;

public class Fnt
{
    public static class Folder {
        private HashMap<String, Folder> folders;
        private ArrayList<String> files;
        private int firstId;

        public Folder()
        {
            this.folders = new HashMap<>();
            this.files = new ArrayList<>();
            this.firstId = 0;
        }

        public Folder(HashMap<String, Folder> folders, ArrayList<String> files, int firstId)
        {
            this.folders = Objects.requireNonNullElseGet(folders, HashMap::new);
            this.files = Objects.requireNonNullElseGet(files, ArrayList::new);
            this.firstId = firstId;
        }
    }

    /**
     * Create a <code>Folder</code> from filename table data. This is the inverse of <code>save()</code>
     * @param fnt byte[] representation of the FNTB
     * @return a <code>Folder</code>
     */
    public static Folder load(byte[] fnt)
    {
        return loadFolder(fnt, 0xF000); // this is always root folder
    }

    /**
     * Load the folder with ID `folderId` and return it as a <code>Folder</code>.
     * @param fnt byte[] representation of the FNTB
     * @param folderId the ID of the folder to load
     * @return a <code>Folder</code>
     */
    private static Folder loadFolder(byte[] fnt, int folderId)
    {
        Buffer buffer = new Buffer(fnt);
        Folder folder = new Folder();

        long offset = 8 * (folderId & 0xFFF);
        buffer.seekGlobal(offset);
        long entriesTableOffset = buffer.readUInt32();
        int fileId = buffer.readUInt16();

        folder.firstId = fileId;

        buffer.seekGlobal(entriesTableOffset);

        int control;
        int length;
        int isFolder;
        String name;
        int subFolderId;
        // Read file and folder entries from the entries table
        while(true)
        {
            control = buffer.readUShort8();
            if (control == 0)
                break;

            // That first byte is a control byte that includes the length
            // of the upcoming string and if this entry is a folder
            length = control & 0x7F;
            isFolder = control & 0x80;

            name = buffer.readString(length);

            if (isFolder == 0x80)
            {
                // There's an additional 2-byte value with the subfolder ID. Get that and load the folder
                subFolderId = buffer.readUInt16();
                folder.folders.put(name, loadFolder(fnt, subFolderId));
            }
            else
            {
                folder.files.add(name);
            }
        }

        return folder;
    }

    public interface FileProcessingData
    {
        int getFileId();
        int getParentFolderId();
        MemBuf getFileContents();
    }

    /**
     * Generates a MemBuf representing the root folder as a filename table. This is the inverse of <code>load()</code>
     * @param root a Folder object for the root folder
     * @return a MemBuf
     */
    public static MemBuf save(Folder root)
    {
        HashMap<Integer, FileProcessingData> folderEntries = new HashMap<>();

        // nextFolderId allows us to assign folder IDs in sequential order.
        // The root folder always has ID 0xF000.
        int nextFolderId = 0xF000;
        // The root folder's parent's ID is the total number of folders.
        int rootParentId = countFoldersIn(root);

        // Ensure that the root folder has the proper folder ID.
        int rootId = parseFolder(root, rootParentId, folderEntries, nextFolderId);
        assert (rootId == 0xF000);

        // in theory, (folderEntries.size() * 8) bytes are needed
        MemBuf fntBuf = MemBuf.create();
        MemBuf.MemBufWriter fntBufWriter = fntBuf.writer();

        // We need to iterate over the folders in order of increasing ID.
        for (int currentFolderId : folderEntries.keySet().stream().sorted().collect(Collectors.toList()))
        {
            FileProcessingData data = folderEntries.get(currentFolderId);

            //Add the folder entries to the folder table
            int offsetInFolderTable = 8 * (currentFolderId & 0xFFF);
            fntBufWriter.setPosition(offsetInFolderTable);
            fntBufWriter.writeInt(offsetInFolderTable);
            fntBufWriter.writeShort((short) data.getFileId());
            fntBufWriter.writeShort((short) data.getParentFolderId());

            // And tack the folder's entries table onto the end of the file
            fntBufWriter.write(data.getFileContents().reader().getBuffer());
        }

        return fntBuf;
    }

    /**
     * Parse a Folder and add its entries to folderEntries.
     * @param folder
     * @param parentId
     * @param folderEntries
     * @param nextFolderId
     * @return the ID number assigned to the parsed folder
     */
    private static int parseFolder(Folder folder, int parentId, HashMap<Integer, FileProcessingData> folderEntries, int nextFolderId)
    {
        int folderId = nextFolderId;
        nextFolderId += 1;

        // Create an entries table and add filenames and folders to it
        MemBuf entriesTable = MemBuf.create();
        MemBuf.MemBufWriter entriesTableWriter = entriesTable.writer();
        for (String file : folder.files)
        {
            if (file.length() > 127)
                throw new RuntimeException("Filename \"" + file + "\" is " + file.length() + " characters long (maximum is 127)!");
            entriesTableWriter.writeBytes(file.length());
            entriesTableWriter.writeString(file);
        }

        for (String folderName : folder.folders.keySet())
        {
            Folder sub = folder.folders.get(folderName);

            // First, parse the subfolder and get its ID, so we can save that to the entries table.
            int otherId = parseFolder(folder, folderId, folderEntries, nextFolderId);

            if (folderName.length() > 127)
                throw new RuntimeException("Folder name \"" + folderName + "\" is " + folderName.length() + " characters long (maximum is 127)!");

            // Folder name is preceded by a 1-byte length value, OR'ed with 0x80 to mark it as a folder.
            entriesTableWriter.writeBytes(folderName.length() | 0x80);
            entriesTableWriter.writeString(folderName);

            // And the ID of the subfolder goes after its name, as a 2-byte value
            entriesTableWriter.writeShort((short) otherId);
        }

        // entries table needs to end with a null byte to mark its end
        entriesTableWriter.writeBytes(0);
        folderEntries.put(folderId, new FileProcessingData()
        {
            @Override
            public int getFileId()
            {
                return folder.firstId;
            }

            @Override
            public int getParentFolderId()
            {
                return parentId;
            }

            @Override
            public MemBuf getFileContents()
            {
                return entriesTable;
            }
        });

        return folderId;
    }

    /**
     * Counts the number of folders inside a given folder
     * @param folder a Folder
     * @return an int
     */
    private static int countFoldersIn(Folder folder)
    {
        int folderCount = 0;
        for (Folder f : folder.folders.values())
        {
            folderCount += 1;
        }
        return folderCount + 1;
    }
}
