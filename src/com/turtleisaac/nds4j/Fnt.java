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

package com.turtleisaac.nds4j;

import com.turtleisaac.nds4j.framework.BinaryWriter;
import com.turtleisaac.nds4j.framework.Buffer;
import com.turtleisaac.nds4j.framework.MemBuf;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Fnt
{
    public static class Folder {
        protected HashMap<String, Folder> folders;
        protected ArrayList<String> files;
        protected int firstId;
        protected String name;

        public Folder()
        {
            this.folders = new HashMap<>();
            this.files = new ArrayList<>();
            this.firstId = 0;
            this.name = "";
        }

        public Folder(String name)
        {
            this.folders = new HashMap<>();
            this.files = new ArrayList<>();
            this.firstId = 0;
            this.name = name;
        }

        public Folder(HashMap<String, Folder> folders, ArrayList<String> files, int firstId)
        {
            this.folders = Objects.requireNonNullElseGet(folders, HashMap::new);
            this.files = Objects.requireNonNullElseGet(files, ArrayList::new);
            this.firstId = firstId;
            this.name = "";
        }

        public ArrayList<String> getFiles()
        {
            return files;
        }

        public HashMap<String, Folder> getFolders()
        {
            return folders;
        }

        public int getFirstId()
        {
            return firstId;
        }


        /**
         * Find the file ID for the given filename, or for the given file path (using "/" as the separator) relative to this folder.
         * @param name a <code>String</code> representing the path to the file
         * @return an <code>int</code> representing the file ID of the specified file, or <int>-1</int> if not found
         */
        public int getIdOf(String name)
        {
            Stream<String> pathStream = Arrays.stream(name.split("/")).filter(s -> !s.equals(""));
            return findFilenameInFolder(pathStream.toArray(String[]::new), this);
        }

        /**
         * Attempt to find filename in the given folder.
         * @param requestedPath the path up through this point, as a <code>String[]</code>.
         * @param searchFolder the <code>Folder</code> to search in
         * @return the file ID of the requested file, or <int>-1</int> if not found
         */
        private int findFilenameInFolder(String[] requestedPath, Folder searchFolder)
        {
            String pathPart = requestedPath[0];
            if (requestedPath.length == 1)
            {
                // it's hopefully a file in this folder
                if (searchFolder.files.contains(pathPart))
                {
                    return searchFolder.firstId + searchFolder.files.indexOf(pathPart);
                }
                else
                {
                    //not here
                    return -1;
                }
            }

            // Hopefully we have the requested subfolder...
            for (String subfolderName : searchFolder.folders.keySet())
            {
                if (subfolderName.equals(pathPart))
                {
                    Folder subfolder = searchFolder.folders.get(subfolderName);
                    List<String> subPath = Arrays.stream(requestedPath).collect(Collectors.toList());
                    subPath.remove(0);

                    return findFilenameInFolder(subPath.toArray(new String[0]), subfolder);
                }
            }

            return -1;
        }


        /**
         * Find the Folder instance for the given subfolder name, or for the given folder path
         * (using "/" as the separator) relative to this folder.
         * @param path a <code>String</code> representing the path to the subfolder
         * @return a <code>Folder</code> representing the found subfolder, or <code>null</code> if not found
         */
        public Folder getSubfolder(String path)
        {
            Stream<String> pathStream = Arrays.stream(path.split("/")).filter(s -> !s.equals(""));
            return findSubfolderInFolder(pathStream.toArray(String[]::new), this);
        }

        /**
         * Attempt to find subfolder name in the given folder.
         * @param requestedPath the path up through this point, as a <code>String[]</code>.
         * @param searchFolder the <code>Folder</code> to search in
         * @return a <code>Folder</code> representing the found subfolder, or <code>null</code> if not found
         */
        private Folder findSubfolderInFolder(String[] requestedPath, Folder searchFolder)
        {
            String pathPart = requestedPath[0];

            for (String subfolderName : searchFolder.folders.keySet())
            {
                if (subfolderName.equals(pathPart))
                {
                    Folder subfolder = searchFolder.folders.get(subfolderName);
                    if (requestedPath.length == 1)
                    {
                        // found the actual requested subfolder
                        return subfolder;
                    }
                    else
                    {
                        // search another level down
                        List<String> subPath = Arrays.stream(requestedPath).collect(Collectors.toList());
                        subPath.remove(0);
                        return findSubfolderInFolder(subPath.toArray(new String[0]), subfolder);
                    }
                }
            }
            return null;
        }


        /**
         * Find the filename of the file with the given ID. If it exists in a
         * subfolder, the filename will be returned as a path separated by "/"s.
         * @param id an <code>int</code> representing the file ID
         * @return a <code>String</code> containing the path to the given file, or <code>null</code> if not found
         */
        public String getFilenameOf(int id)
        {
            String[] result = findIdInFolder(id, new String[0], this);

            if (result != null)
                return String.join("/", result);
            else
                return null;
        }

        /**
         * Attempt to find id in the given folder.
         * @param id an <code>int</code> representing the id to look for
         * @param pathSoFar the path up through this point, as a <code>String[]</code>.
         * @param searchFolder the <code>Folder</code> to search in
         * @return a <code>String[]</code> containing the path to the given file, or <code>null</code> if not found
         */
        private String[] findIdInFolder(int id, String[] pathSoFar, Folder searchFolder)
        {

            // check if it's in this folder
            int firstId = searchFolder.firstId;
            if (firstId <= id && id < firstId + searchFolder.files.size())
            {
                // found it
                String filename = searchFolder.files.get(id-firstId);
                String[] currentPath = new String[pathSoFar.length + 1];
                System.arraycopy(pathSoFar, 0, currentPath, 0, pathSoFar.length);
                currentPath[currentPath.length-1] = filename;
                return currentPath;
            }

            // check subfolders
            for (String subfolderName : searchFolder.folders.keySet())
            {
                Folder subfolder = searchFolder.folders.get(subfolderName);
                String[] currentPath = new String[pathSoFar.length + 1];
                System.arraycopy(pathSoFar, 0, currentPath, 0, pathSoFar.length);
                currentPath[currentPath.length-1] = subfolderName;
                String[] result = findIdInFolder(id, currentPath, subfolder);

                if (result != null)
                {
                    // found it in that folder
                    return result;
                }

                // otherwise, continue checking other subfolders
            }

            // didn't find it
            return null;
        }

        /**
         * Return a <code>String</code> containing all file positions
         * relative to this <code>Folder</code>, indented based on subfolder
         * @param indent an <code>int</code> specifying the base indent
         * @return a <code>String[]</code> containing the output
         */
        public String[] toStringList(int indent)
        {
            return toStringListHelper(indent).toArray(String[]::new);
        }

        private ArrayList<String> toStringListHelper(int indent)
        {
            ArrayList<String> l = new ArrayList<>();
            StringBuilder sb = new StringBuilder(" ");
            while (sb.length() != indent + 1)
                sb.append(" ");
            String indentString = sb.toString();

            // Print filenames first, since those have file IDs less than those of files contained in subfolders

            for (int i = 0; i < files.size(); i++)
            {
                String filename = files.get(i);
                int fid = firstId + i;
//                l.add(String.format("%s%d: %s", indentString, fid, filename));
                l.add(indentString + fid + ": " + filename);
            }

            for (String folderName : folders.keySet())
            {
                Folder folder = folders.get(folderName);
                l.add(indentString + folderName + "/");
                l.addAll(folder.toStringListHelper(indent + 4));
            }

            return l;
        }


        @Override
        public String toString()
        {
            return String.format("Folder{%s}", name);
        }

        @Override
        public boolean equals(Object o)
        {
            if(this == o) {
                return true;
            }
            if(o == null || getClass() != o.getClass()) {
                return false;
            }
            Folder folder = (Folder) o;
            return firstId == folder.firstId && folders.equals(folder.folders) && files.equals(folder.files) && Objects.equals(name, folder.name);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(folders, files, firstId, name);
        }
    }

    /**
     * Create a <code>Folder</code> from filename table data. This is the inverse of <code>save()</code>
     * @param fnt byte[] representation of the FNTB
     * @return a <code>Folder</code>
     */
    public static Folder load(byte[] fnt)
    {
        return loadFolder(fnt, 0xF000, "root"); // this is always root folder
    }

    /**
     * Load the folder with ID `folderId` and return it as a <code>Folder</code>.
     * @param fnt byte[] representation of the FNTB
     * @param folderId the ID of the folder to load
     * @return a <code>Folder</code>
     */
    private static Folder loadFolder(byte[] fnt, int folderId, String name)
    {
        MemBuf fntBuf = MemBuf.create();
        fntBuf.writer().write(fnt);
        MemBuf.MemBufReader reader = fntBuf.reader();
        Folder folder = new Folder(name);

        long offset = 8 * (folderId & 0xFFF);
        reader.setPosition(offset);
        long entriesTableOffset = reader.readUInt32();
        int fileId = reader.readUInt16();

        folder.firstId = fileId;

        reader.setPosition(entriesTableOffset);

        int control;
        int length;
        int isFolder;
        int subFolderId;
        // Read file and folder entries from the entries table
        while(true)
        {
            control = reader.readUShort8();
            if (control == 0)
                break;

            // That first byte is a control byte that includes the length
            // of the upcoming string and if this entry is a folder
            length = control & 0x7F;
            isFolder = control & 0x80;

            name = reader.readString(length);

            if (isFolder == 0x80)
            {
                // There's an additional 2-byte value with the subfolder ID. Get that and load the folder
                subFolderId = reader.readUInt16();
                folder.folders.put(name, loadFolder(fnt, subFolderId, name));
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
        MemBuf getFntEntry();
    }

    public static int nextFolderId;

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
        nextFolderId = 0xF000;
        // The root folder's parent's ID is the total number of folders.
        int rootParentId = countFoldersIn(root);

        // Ensure that the root folder has the proper folder ID.
        int rootId = parseFolder(root, rootParentId, folderEntries);
        assert (rootId == 0xF000);

        // in theory, (folderEntries.size() * 8) bytes are needed for the folders table at the beginning of the fnt
        int fntLen = folderEntries.size() * 8;
        MemBuf fntBuf = MemBuf.create();
        MemBuf.MemBufWriter fntBufWriter = fntBuf.writer();

        // We need to iterate over the folders in order of increasing ID.
        int storedPosition = folderEntries.size() * 8;
        for (int currentFolderId : folderEntries.keySet().stream().sorted().collect(Collectors.toList()))
        {
            FileProcessingData data = folderEntries.get(currentFolderId);

            //Add the folder entries to the folder table
            int offsetInFolderTable = 8 * (currentFolderId & 0xFFF);
            fntBufWriter.setPosition(offsetInFolderTable);
            fntBufWriter.writeInt(fntLen);
            fntBufWriter.writeShort((short) data.getFileId());
            fntBufWriter.writeShort((short) data.getParentFolderId());

            // And tack the folder's entries table onto the end of the file
            fntBufWriter.setPosition(storedPosition);
            fntBufWriter.write(data.getFntEntry().reader().getBuffer());
            fntLen += data.getFntEntry().reader().getBuffer().length;
            storedPosition = fntBufWriter.getPosition();
        }

        return fntBuf;
    }

    /**
     * Parse a Folder and add its entries to folderEntries.
     * @param folder a <code>Folder</code>
     * @param parentId an <code>int</code> containing the ID of the parent folder
     * @param folderEntries a <code>HashMap</code> containing information needed to write the fnt entry for each Folder in the rom
     * @return the ID number assigned to the parsed folder
     */
    private static int parseFolder(Folder folder, int parentId, HashMap<Integer, FileProcessingData> folderEntries)
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
            int otherId = parseFolder(sub, folderId, folderEntries);

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
            public MemBuf getFntEntry()
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
            folderCount += countFoldersIn(f);
        }
        return folderCount + 1;
    }

    /**
     * writes the ROM's internal filesystem to disk at the specified path
     * @param dir a <code>File</code> representation of the write path
     * @param folder the <code>Folder</code>> object to write
     */
    public static void writeFolderToDisk(File dir, Folder folder, ArrayList<byte[]> files) throws IOException
    {
        if (!dir.mkdir())
        {
            throw new RuntimeException("Could not create data dir, check write perms.");
        }

        for (String name : folder.getFolders().keySet())
        {
            writeFolderToDisk(Paths.get(dir.getAbsolutePath(), name).toFile(), folder.getFolders().get(name), files);
        }

        int counter = 0;
        for (String name : folder.getFiles())
        {
            BinaryWriter.writeFile(Paths.get(dir.getAbsolutePath(), name).toFile(), files.get(folder.getFirstId() + counter++));
        }
    }

    /**
     * Create a <code>Folder</code> from an unpacked filesystem on disk;
     * This also grabs all the binary data for each file.
     * @param dir a <code>File</code> representing the path to the unpacked data dir on disk to process
     * @param files an <code>ArrayList</code> of <code>byte[]</code>'s to fill with the contents of a directory
     *              on disk
     * @return a <code>Folder</code>
     * @exception RuntimeException if the specified path on disk does not exist or is not a directory
     */
    public static Folder loadFromDisk(File dir, ArrayList<byte[]> files)
    {
        if (!dir.exists())
            throw new RuntimeException("\"" + dir.getAbsolutePath() + "\" does not exist.");
        else if(!dir.isDirectory())
            throw new RuntimeException("\"" + dir.getAbsolutePath() + "\" is not a directory.");
        return loadFolderFromDisk(dir, files); // this is always root folder
    }

    /**
     * Loads a given folder from disk
     * @param dir a <code>File</code> representing the path to an unpacked dir on disk to process
     * @param files an <code>ArrayList</code> of <code>byte[]</code>'s to fill with the contents of a directory
     *              on disk
     * @return a <code>Folder</code>
     */
    private static Folder loadFolderFromDisk(File dir, ArrayList<byte[]> files)
    {
        Folder folder = new Folder(dir.getName());
        folder.firstId = findLowestAvailableFileId(files);

        String name;
        // Read file and folders entries from the entries table
        for (File sub : Arrays.stream(Objects.requireNonNull(dir.listFiles())).sorted().collect(Collectors.toList()))
        {
            name = sub.getName();
            if (sub.isDirectory())
            {
                folder.folders.put(name, loadFolderFromDisk(sub, files));
            }
            else if (!sub.isHidden()) // to make sure it doesn't grab stray files like .DS_STORE thanks to macOS
            {
                folder.files.add(name);
                files.set(findLowestAvailableFileId(files), Buffer.readFile(sub.getAbsolutePath()));
            }
        }
        return folder;
    }

    protected static int findLowestAvailableFileId(ArrayList<byte[]> files)
    {
        for (int i = 0; i < files.size(); i++)
        {
            if (files.get(i) == null)
                return i;
        }

        throw new RuntimeException("No available file IDs to allocate.");
    }

    protected static int calculateNumFiles(File dir)
    {
        int fileCount = 0;
        for (File f : Objects.requireNonNull(dir.listFiles()))
        {
            if (f.isDirectory())
                fileCount += calculateNumFiles(f);
            else if (!f.isHidden())  // to make sure it doesn't grab stray files like .DS_STORE thanks to macOS
                fileCount += 1;
        }
        return fileCount;
    }
}
