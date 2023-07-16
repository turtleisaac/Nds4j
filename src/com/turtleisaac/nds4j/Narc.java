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

import com.turtleisaac.nds4j.framework.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.turtleisaac.nds4j.framework.StringFormatter.formatOutputString;

// smell ya later Narctowl

public class Narc
{
    public static final int NARC_HEADER_SIZE = 0x10;
    public static final int FATB_HEADER_SIZE = 0x0C;
    public static final int FIMG_HEADER_SIZE = 8;
    public static final int FNTB_HEADER_SIZE = 8;



    Fnt.Folder filenames; // represents the root folder of the filesystem
    ArrayList<byte[]> files;
    Endianness endiannessOfBeginning;

    public Narc()
    {
        filenames = new Fnt.Folder();
        files = new ArrayList<>();
        endiannessOfBeginning = Endianness.LITTLE;
    }

    /**
     * Read NARC data, and create a filename table and a list of files.
     * @param data a <code>byte[]</code> representation of a <code>Narc</code>
     */
    public Narc(byte[] data)
    {
        filenames = new Fnt.Folder();
        endiannessOfBeginning = Endianness.LITTLE;

        MemBuf buf = MemBuf.create();
        buf.writer().write(data);
        MemBuf.MemBufReader reader = buf.reader();

        String magic = reader.readString(4);

        if (!magic.equals("NARC")) {
            throw new RuntimeException("Not a NARC file.");
        }

        int bom = reader.readUInt16();
        int version = reader.readUInt16();
        long fileSize = reader.readUInt32();
        int headerSize = reader.readUInt16();
        int numBlocks = reader.readUInt16();

        // some games use big endian, some use little - NSMB uses big for example, but Spirit Tracks uses little
        if (bom == 0xFFFE) {
            endiannessOfBeginning = Endianness.BIG;
            version = (version & 0xFF) << 8 | version >> 8;
        }

        if (version != 1)
            throw new RuntimeException("Unsupported NARC version: " + version);

        // Read the file allocation block (current position is now 0x10)
        String fatbMagic = reader.readString(4);
        long fatbSize = reader.readUInt32();
        long numFiles = reader.readUInt32();

        int fatbStart = reader.getPosition();

        if (!fatbMagic.equals("BTAF")) {
            throw new RuntimeException("Incorrect NARC FATB magic: " + fatbMagic);
        }

        // read the file name block
        long fntbOffset = NARC_HEADER_SIZE + fatbSize;
        reader.setPosition(fntbOffset);
        String fntbMagic = reader.readString(4);
        long fntbSize = reader.readUInt32();

        if (!fntbMagic.equals("BTNF")) {
            throw new RuntimeException("Incorrect NARC FNTB magic: " + fntbMagic);
        }

        // get the data from the file data block before continuing
        long fimgOffset = fntbOffset + fntbSize;
        reader.setPosition(fimgOffset);
        String fimgMagic = reader.readString(4);
        long fimgSize = reader.readUInt32();

        if (!fimgMagic.equals("GMIF")) {
            throw new RuntimeException("Incorrect NARC FIMG magic: " + fimgMagic);
        }

        int rawDataOffset = reader.getPosition(); // fimgOffset + 8
        files = new ArrayList<>();

        // read the files' contents
        long startOffset;
        long endOffset;
        for (int i = 0; i < numFiles; i++)
        {
            reader.setPosition(fatbStart + 8*i);
            startOffset = reader.readUInt32();
            endOffset = reader.readUInt32();
            reader.setPosition(rawDataOffset + startOffset);
            files.add(reader.readTo(rawDataOffset + endOffset));
        }

        // parse the filenames
        reader.setPosition(fntbOffset + 8);
        filenames = Fnt.load(reader.readBytes((int) fntbSize));
    }

    /**
     * Load a NARC archive from a filesystem file
     * @param file a <code>String</code> containing the path to a NARC file on disk
     * @return a <code>Narc</code> object
     */
    public static Narc fromFile(String file)
    {
        return new Narc(Buffer.readFile(file));
    }

    /**
     * Load a NARC archive from a file on disk
     * @param file a <code>File</code> object representing the path to a NARC file on disk
     * @return a <code>Narc</code> object
     */
    public static Narc fromFile(File file)
    {
        return new Narc(Buffer.readFile(file.getAbsolutePath()));
    }


    /**
     * Load an unpacked NARC from a directory on disk
     * @param dir a <code>String</code> object containing the path to an unpacked NARC directory on disk
     * @param removeFilenames whether the NARC should have a Fnt (ignored if there are subfolders within)
     * @return a <code>Narc</code> object
     */
    public static Narc fromUnpacked(String dir, boolean removeFilenames, Endianness endiannessOfBeginning)
    {
        return fromUnpacked(new File(dir), removeFilenames, endiannessOfBeginning);
    }


    /**
     * Load an unpacked NARC from a directory on disk
     * @param dir a <code>File</code> object representing the path to an unpacked NARC directory on disk
     * @param removeFilenames whether the NARC should have a Fnt (ignored if there are subfolders within)
     * @return a <code>Narc</code> object
     * @exception RuntimeException if the specified path on disk does not exist or is not a directory
     */
    public static Narc fromUnpacked(File dir, boolean removeFilenames, Endianness endiannessOfBeginning)
    {
        Fnt.Folder root;
        ArrayList<byte[]> files = new ArrayList<>();

        int numFiles = Fnt.calculateNumFiles(dir);
        for (int i = 0; i < numFiles; i++)
        {
            files.add(null);
        }

        root = Fnt.loadFromDisk(dir, files);

        if (Objects.requireNonNull(dir.listFiles(File::isDirectory)).length == 0 && removeFilenames)
        {
            root = new Fnt.Folder("root");
        }

        return fromContentsAndNames(files, root, endiannessOfBeginning);
    }


    /**
     * Create a NARC archive from a list of files and (optionally) a filename table.
     * @param files an <code>ArrayList</code> of <code>byte[]</code>'s representing all the subfiles in the NARC
     * @param filenames an (OPTIONAL) <code>Folder</code> representing the NARC's filesystem
     * @return a <code>Narc</code> representation of the provided parameters
     */
    public static Narc fromContentsAndNames(ArrayList<byte[]> files, Fnt.Folder filenames, Endianness endiannessOfBeginning)
    {
        Narc narc = new Narc();
        narc.endiannessOfBeginning = endiannessOfBeginning;
        narc.files = files;
        if (filenames != null)
            narc.filenames = filenames;
        return narc;
    }

    /**
     * Unpacks this <code>Narc</code> to disk at the specified path
     * @param dir a <code>String</code> containing the target directory to unpack the NARC to
     * @exception RuntimeException if this <code>Narc</code> has an internal filesystem, the specified path
     * already exists, a new directory at the specified path could not be created, or a failure to write the
     * subfiles occurred
     * @exception IOException if the parent directory of the output subfiles does not exist
     */
    public void unpack(String dir) throws IOException
    {
        unpack(new File(dir));
    }

    /**
     * Unpacks this <code>Narc</code> to disk at the specified path
     * @param dir a <code>File</code> containing the target directory to unpack the NARC to
     * @exception RuntimeException if this <code>Narc</code> has an internal filesystem, the specified path
     * already exists, a new directory at the specified path could not be created, or a failure to write the
     * subfiles occurred
     * @exception IOException if the parent directory of the output subfiles does not exist
     */
    public void unpack(File dir) throws IOException
    {
        if (filenames.getFiles().size() > 0)
            throw new RuntimeException("Unpacking of NARCs with internal filesystems not yet supported");

        if (dir.exists())
            throw new RuntimeException("There is already a directory at the specified location");

        if (!dir.mkdir())
            throw new RuntimeException("Failed to create output directory, check write permissions.");

        for (int i = 0; i < files.size(); i++)
        {
            BinaryWriter.writeFile(Paths.get(dir.getAbsolutePath(), formatOutputString(i, files.size(), "", "")), files.get(i));
        }
    }

    /**
     * Generate a <code>byte[]</code> representing this NARC.
     * @return a <code>byte[]</code>
     */
    public byte[] save()
    {
        // Prepare the filedata and file allocation table block
        MemBuf fimgBuf = MemBuf.create();
        MemBuf.MemBufWriter fimgWriter = fimgBuf.writer();


        MemBuf fatbBuf = MemBuf.create();
        MemBuf.MemBufWriter fatbWriter = fatbBuf.writer();
        fatbWriter.writeString("BTAF");
        fatbWriter.writeInt(FATB_HEADER_SIZE + 8 * files.size());
        fatbWriter.writeInt(files.size());

        // Write data into the FIMG and FAT blocks
        long startOffset;
        long endOffset;
        for(byte[] data : files) {
            startOffset = fimgWriter.getPosition();
            fimgWriter.write(data);
            endOffset = fimgWriter.getPosition();
            fatbWriter.writeUInt32(startOffset).writeUInt32(endOffset);
            fimgWriter.align(4);
        }

        byte[] fimg = fimgBuf.reader().getBuffer();
        fimgBuf = MemBuf.create();
        fimgWriter = fimgBuf.writer();
        fimgWriter.writeString("GMIF");
        fimgWriter.writeUInt32(fimg.length + FIMG_HEADER_SIZE);
        fimgWriter.write(fimg);

        // Assemble the filename table block
        MemBuf nameTable = Fnt.save(filenames);
        nameTable.writer().align(4, (byte) 0xFF);
        MemBuf fntbBuf = MemBuf.create();
        fntbBuf.writer().writeString("BTNF");
        fntbBuf.writer().writeUInt32(nameTable.reader().getBuffer().length + FNTB_HEADER_SIZE);
        fntbBuf.writer().write(nameTable.reader().getBuffer());

        // Put everything together and return.
        MemBuf narcBuf = MemBuf.create();
        MemBuf.MemBufWriter narcWriter = narcBuf.writer();

        narcWriter.skip(NARC_HEADER_SIZE);
        narcWriter.write(fatbBuf.reader().getBuffer());
        narcWriter.write(fntbBuf.reader().getBuffer());
        narcWriter.write(fimgBuf.reader().getBuffer());

        int narcLength = narcWriter.getPosition();

        int bom = 0xFEFF;
        int version = 1;
        if (endiannessOfBeginning == Endianness.BIG)
        {
            bom = 0xFFFE;
            version = 0x100;
        }

        narcWriter.setPosition(0);
        narcWriter.writeString("NARC");
        narcWriter.writeShort((short) bom);
        narcWriter.writeShort((short) version);
        narcWriter.writeUInt32(narcLength);
        narcWriter.writeShort((short) NARC_HEADER_SIZE);
        narcWriter.writeShort((short) 3);

        narcWriter.setPosition(narcLength);
        return narcBuf.reader().getBuffer();
    }


    /**
     * Generate a <code>byte[]</code> representing this NARC, and save it to a file on disk
     * @param path a <code>String</code> containing the path to the file on disk to save as
     */
    public void saveToFile(String path) throws IOException
    {
        saveToFile(new File(path));
    }

    /**
     * Generate a <code>byte[]</code> representing this NARC, and save it to a file on disk
     * @param file a <code>File</code> representing the path to the file on disk to save as
     * @exception RuntimeException if the provided path leads to a directory
     * @exception IOException if the specified file's parent directory does not exist.
     */
    public void saveToFile(File file) throws IOException
    {
        if (file.exists() && file.isDirectory())
        {
            throw new RuntimeException("\"" + file.getAbsolutePath() + "\" is a directory. Save failed.");
        }
        BinaryWriter.writeFile(file, save());
    }

    /**
     * Return the contents of the file with the given filename (path).
     * @param filename a <code>String</code> containing the path to the requested NARC subfile
     * @return a <code>byte[]</code> containing the contents of the requested NARC subfile
     * @exception RuntimeException if file with given name is not found
     */
    public byte[] getFileByName(String filename)
    {
        int fid = filenames.getIdOf(filename);
        if (fid == -1)
        {
            throw new RuntimeException("Couldn't find file ID of \"" + filename + "\".");
        }
        return files.get(fid);
    }

    /**
     * Replace the contents of the NARC subfile with the given filename (path) with the given data.
     * @param filename a <code>String</code> containing the path to the specified NARC subfile
     * @param data a <code>byte[]</code> containing the contents to set for the specified NARC subfile
     * @exception RuntimeException if file with given name is not found
     */
    public void setFileByName(String filename, byte[] data)
    {
        int fid = filenames.getIdOf(filename);
        if (fid == -1)
        {
            throw new RuntimeException("Couldn't find file ID of \"" + filename + "\".");
        }
        files.set(fid, data);
    }


    public String toString()
    {
        return String.format("(%s) NARC with %d files", endiannessOfBeginning.symbol, files.size());
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

        Narc narc = (Narc) o;

        if (files.size() == narc.files.size())
        {
            for (int i = 0; i < files.size(); i++)
            {
                if (!Arrays.equals(files.get(i), narc.files.get(i)))
                {
                    return false;
                }
            }
        }
        else
        {
            return false;
        }


        return Objects.equals(filenames, narc.filenames) && endiannessOfBeginning == narc.endiannessOfBeginning;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(filenames, files, endiannessOfBeginning);
    }
}
