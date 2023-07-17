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

package io.github.turtleisaac.nds4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.turtleisaac.nds4j.framework.*;
import io.github.turtleisaac.nds4j.Fnt.Folder;

import static io.github.turtleisaac.nds4j.framework.StringFormatter.formatOutputString;

public class NintendoDsRom
{
    // Header Section

    String title;
    String gameCode;
    String developerCode;
    int unitCode;
    int encryptionSeed;
    byte deviceCapacity;
    byte[] reserved1;
    byte reserved2;
    int systemRegion;
    int romVersion;
    int autoStartFlag;

    int arm9Offset;
    int arm9EntryAddress;
    int arm9LoadAddress;
    int arm9Length;
    int arm7Offset;
    int arm7EntryAddress;
    int arm7LoadAddress;
    int arm7Length;

    int fntOffset;
    int fntLength;

    int fatOffset;
    int fatLength;

    int y9Offset;
    int y9Length;

    int y7Offset;
    int y7Length;

    int normalCardControlRegisterSettings;
    int secureCardControlRegisterSettings;

    int iconBannerOffset;
    short secureAreaCrc; //recalculated upon saving
    short secureTransferTimeout;
    int arm9Autoload;
    int arm7Autoload;
    byte[] secureDisable;

    long romSizeOrRsaSigOffset;
    long headerLength;

    byte[] padding_088h;
    byte[] nintendoLogo;
    short nintendoLogoCrc;
    short headerCrc;
    int debugRomOffset;
    int debugRomLength;
    int debugRomAddress;
    byte[] padding_16Ch;
    byte[] padding_200h;

    // Misc Section

    byte[] rsaSignature;

    byte[] arm9;
    byte[] arm7;

    byte[] fnt;
    byte[] fat;

    byte[] y9;
    byte[] y7;

    byte[] iconBanner;
    byte[] debugRom;

    int[] arm9PostData;

    // Files Stuff

    Folder filenames; // represents the root folder of the filesystem
    ArrayList<byte[]> files;
    ArrayList<Integer> sortedFileIDs;

    private static final HashMap<Integer, Integer> ICON_BANNER_LENGTHS = new HashMap<Integer, Integer>() {
        {
            put(0x0001, 0x840);
            put(0x0002, 0x940);
            put(0x0003, 0x1240);
            put(0x0103, 0x23C0);
        }
    };

    /**
     * Reads a <code>NintendoDsRom</code> from a ROM file on disk
     * @param file a <code>String</code> containing the path to a ROM file on disk
     * @return a <code>NintendoDsRom</code>
     */
    public static NintendoDsRom fromFile(String file)
    {
        return fromFile(new File(file));
    }

    /**
     * Reads a <code>NintendoDsRom</code> from a ROM file on disk
     * @param file a <code>File</code> containing the path to a ROM file on disk
     * @return a <code>NintendoDsRom</code>
     */
    public static NintendoDsRom fromFile(File file)
    {
        return new NintendoDsRom(Buffer.readFile(file.getAbsolutePath()));
    }

    /**
     * Creates a <code>NintendoDsRom</code> object from a provided <code>byte[]</code> representing the bytes of a ROM file
     * @param data a <code>byte[]</code>
     */
    public NintendoDsRom(byte[] data)
    {
        MemBuf romBuf = MemBuf.create();
        romBuf.writer().write(data);
        MemBuf.MemBufReader reader = romBuf.reader();

        int fileLength = romBuf.writer().getPosition();

        // read the ROM header
        readHeader(reader, fileLength, false);

        // RSA signature file
        long realSigOffset = 0;
        if (fileLength >= 0x1004)
        {
            reader.setPosition(0x1000);
            realSigOffset = reader.readUInt32();
        }
        if (realSigOffset == 0 && fileLength > romSizeOrRsaSigOffset)
        {
            realSigOffset = romSizeOrRsaSigOffset;
        }
        if (realSigOffset != 0)
        {
            reader.setPosition(realSigOffset);
            rsaSignature = reader.readTo(Math.min(fileLength, realSigOffset + 0x88));
        }
        else
        {
            rsaSignature = new byte[] {};
        }

        // arm9, arm7, FNT, FAT, overlay tables, icon banner
        reader.setPosition(arm9Offset);
        readArm9(reader, arm9Length);

        reader.setPosition(arm7Offset);
        readArm7(reader, arm7Length);

        reader.setPosition(fntOffset);
        fnt = reader.readBytes(fntLength);

        reader.setPosition(fatOffset);
        fat = reader.readBytes(fatLength);

        reader.setPosition(y9Offset);
        readY9(reader, y9Length);

        reader.setPosition(y7Offset);
        readY7(reader, y7Length);

        readIconBanner(reader);

        readDebugRom(reader);

        int arm9PostDataOffset = arm9Offset + arm9Length;
        ArrayList<Integer> arm9PostData = new ArrayList<>();

        int[] extraData;
        while (Arrays.equals(new int[] {0x21, 0x06, 0xC0, 0xDE}, (extraData = reader.readBytesI(4)) ))
        {
            arm9PostData.addAll(Arrays.stream(extraData).boxed().collect(Collectors.toList()));
            arm9PostData.addAll(Arrays.stream(reader.readBytesI(8)).boxed().collect(Collectors.toList()));
        }

        this.arm9PostData = arm9PostData.stream().mapToInt(Integer::intValue).toArray();

        if (fnt.length != 0)
            filenames = Fnt.load(fnt);
        else
            filenames = new Folder();

        files = new ArrayList<>();
        sortedFileIDs = new ArrayList<>();
        if (fat.length != 0)
        {
            processFat(reader);
        }
    }

    private void readHeader(MemBuf.MemBufReader reader, int fileLength, boolean fromUnpacked)
    {
        title = reader.readString(12).trim();
        gameCode = reader.readString(4);
        developerCode = reader.readString(2);
        unitCode = reader.readByte();
        encryptionSeed = reader.readByte();
        deviceCapacity = reader.readBytes(1)[0];
        reserved1 = reader.readBytes(7);
        reserved2 = (byte)reader.readByte();
        systemRegion = reader.readByte();
        romVersion = reader.readByte();
        autoStartFlag = reader.readByte();

        arm9Offset = reader.readInt();
        if(arm9Offset < 0x4000) {
            throw new RuntimeException("Invalid ROM Header: ARM9 Offset");
        }
        arm9EntryAddress = reader.readInt();
        if(!(arm9EntryAddress >= 0x2000000 && arm9EntryAddress <= 0x23BFE00)) {
            throw new RuntimeException("Invalid ROM Header: ARM9 Entry Address");
        }
        arm9LoadAddress = reader.readInt();
        if(!(arm9LoadAddress >= 0x2000000 && arm9LoadAddress <= 0x23BFE00)) {
            throw new RuntimeException("Invalid ROM Header: ARM9 RAM Address");
        }
        arm9Length = reader.readInt();
        if(arm9Length > 0x3BFE00) {
            throw new RuntimeException("Invalid ROM Header: ARM9 Size");
        }
        arm7Offset = reader.readInt();
        if(arm7Offset < 0x8000) {
            throw new RuntimeException("Invalid ROM Header: ARM7 Offset");
        }
        arm7EntryAddress = reader.readInt();
        if(!((arm7EntryAddress >= 0x2000000 && arm7EntryAddress <= 0x23BFE00) || (arm7EntryAddress >= 0x37F8000 && arm7EntryAddress <= 0x3807E00))) {
            throw new RuntimeException("Invalid ROM Header: ARM7 Entry Address");
        }
        arm7LoadAddress = reader.readInt();
        if(!((arm7LoadAddress >= 0x2000000 && arm7LoadAddress <= 0x23BFE00) || (arm7LoadAddress >= 0x37F8000 && arm7LoadAddress <= 0x3807E00))) {
            throw new RuntimeException("Invalid ROM Header: ARM7 Load Address");
        }
        arm7Length = reader.readInt();
        if(arm7Length > 0x3BFE0) {
            throw new RuntimeException("Invalid ROM Header: ARM7 Size");
        }

        fntOffset = reader.readInt();
        fntLength = reader.readInt();

        fatOffset = reader.readInt();
        fatLength = reader.readInt();

        y9Offset = reader.readInt();
        y9Length = reader.readInt();

        y7Offset = reader.readInt();
        y7Length = reader.readInt();

        normalCardControlRegisterSettings = reader.readInt();
        secureCardControlRegisterSettings = reader.readInt();

        iconBannerOffset = reader.readInt();
        secureAreaCrc = reader.readShort();
        secureTransferTimeout = reader.readShort();
        arm9Autoload = reader.readInt();
        arm7Autoload = reader.readInt();
        secureDisable = reader.readBytes(8);

        romSizeOrRsaSigOffset = reader.readUInt32();
        headerLength = reader.readUInt32();

        padding_088h = reader.readBytes(0x38);
        nintendoLogo = reader.readBytes(0x9C);
        nintendoLogoCrc = reader.readShort();
        headerCrc = reader.readShort();
        debugRomOffset = reader.readInt();
        debugRomLength = reader.readInt();
        debugRomAddress = reader.readInt();
        padding_16Ch = reader.readBytes(0x94);
        if (!fromUnpacked)
            padding_200h = reader.readTo(Math.min(arm9Offset, fileLength));
        else
            padding_200h = reader.readTo(fileLength);
    }

    private void readArm9(MemBuf.MemBufReader reader, int length)
    {
        arm9 = reader.readBytes(length);
    }

    private void readArm7(MemBuf.MemBufReader reader, int length)
    {
        arm7 = reader.readBytes(length);
    }

    private void readY9(MemBuf.MemBufReader reader, int length)
    {
        y9 = reader.readBytes(length);
    }

    private void readY7(MemBuf.MemBufReader reader, int length)
    {
        y7 = reader.readBytes(length);
    }

    private void readIconBanner(MemBuf.MemBufReader reader)
    {
        if (iconBannerOffset != 0)
        {
            reader.setPosition(iconBannerOffset);
            int val = reader.readUInt16();
            int iconBannerLength;
            if (ICON_BANNER_LENGTHS.get(val) == null)
                iconBannerLength = ICON_BANNER_LENGTHS.get(1);
            else
                iconBannerLength = ICON_BANNER_LENGTHS.get(val);

            iconBanner = reader.readBytes(iconBannerLength);
        }
        else
        {
            iconBanner = new byte[ICON_BANNER_LENGTHS.get(1)];
        }
    }

    private void readDebugRom(MemBuf.MemBufReader reader)
    {
        if (debugRomOffset != 0)
        {
            reader.setPosition(debugRomOffset);
            debugRom = reader.readBytes(debugRomLength);
        }
        else
        {
            debugRom = new byte[] {};
        }
    }

    private void processFat(MemBuf.MemBufReader reader)
    {
        MemBuf fatBuf = MemBuf.create();
        fatBuf.writer().write(fat);
        MemBuf.MemBufReader fatBufReader = fatBuf.reader();
        HashMap<Long, Integer> offsetToId = new HashMap<>();
        ArrayList<Long> offsetToIdKeys = new ArrayList<>();

        long startOffset, endOffset;
        for (int i = 0; i < fat.length / 8; i++)
        {
            startOffset = fatBufReader.readUInt32();
            endOffset = fatBufReader.readUInt32();
            reader.setPosition(startOffset);
            files.add(reader.readTo(endOffset));
            offsetToId.put(startOffset, i);
            offsetToIdKeys.add(startOffset);
        }

        for (Long offset : offsetToIdKeys.stream().sorted().collect(Collectors.toList()))
        {
            sortedFileIDs.add(offsetToId.get(offset));
        }
    }

    //SAVE-RELATED FUNCTIONS

    private void align(MemBuf.MemBufWriter writer, int alignment)
    {
        align(writer, alignment, (byte) 0);
    }

    private void align(MemBuf.MemBufWriter writer, int alignment, byte fill)
    {
        if (writer.getPosition() % alignment != 0)
        {
            int extra = writer.getPosition() % alignment;
            int needed = alignment - extra;
            writer.writeByteNumTimes(fill, needed);
        }
    }

    /**
     * Generate a <code>byte[]</code> representation of this ROM
     * @param updateDeviceCapacity whether the rom capacity code in the header will be changed (boolean)
     * @return a <code>byte[]</code>
     */
    public byte[] save(boolean updateDeviceCapacity)
    {
        HashMap<Integer, Integer> fileOffsets = new HashMap<>();

        MemBuf romBuf = MemBuf.create();
        MemBuf.MemBufWriter writer = romBuf.writer();

        // to begin, assume header size of 0x200 (header will be filled in at end)
        int storedPosition = 0x200;
        // then add size of padding_200h (bytes between end of header and start of arm9)
        writer.setPosition(0x200);
        writer.write(padding_200h);
        // then align to 0x4000
        align(writer, 0x4000);

        // write the arm9 and post-arm9 data
        int arm9Offset = writer.getPosition();
        writer.write(arm9);
        writer.writeBytes(arm9PostData);
        storedPosition = writer.getPosition();
        // then align to 0x200 with padding of 0xFF
        align(writer, 0x200, (byte) 0xff);

        // write the arm9 overlay table (y9)
        int y9Offset;
        if (y9.length > 0)
        {
            y9Offset = writer.getPosition();
            writer.write(y9);
            // then align to 0x200 with padding of 0xFF
            align(writer, 0x200, (byte) 0xff);
        }
        else
        {
            y9Offset = 0;
        }

        // write the arm9 overlays
        MemBuf y9Buf = MemBuf.create();
        y9Buf.writer().write(y9);
        int fileId;
        for (int i = 0; i < y9.length / 32; i++)
        {
            y9Buf.reader().setPosition(i * 32 + 0x18);
            fileId = y9Buf.reader().readInt();
            fileOffsets.put(fileId, writer.getPosition());
            writer.write(files.get(fileId));
            // then align to 0x200 with padding of 0xFF
            align(writer, 0x200, (byte) 0xff);
        }

        // write the arm7
        int arm7Offset = writer.getPosition();
        writer.write(arm7);
        // then align to 0x200 with padding of 0xFF
        align(writer, 0x200, (byte) 0xff);

        // write the arm7 overlay table (y7)
        int y7Offset;
        if (y7.length > 0)
        {
            y7Offset = writer.getPosition();
            writer.write(y7);
            // then align to 0x200 with padding of 0xFF
            align(writer, 0x200, (byte) 0xff);
        }
        else
        {
            y7Offset = 0;
        }

        // write the arm7 overlays
        MemBuf y7Buf = MemBuf.create();
        y7Buf.writer().write(y7);
        for (int i = 0; i < y7.length / 32; i++)
        {
            y7Buf.reader().setPosition(i * 32 + 0x18);
            fileId = y7Buf.reader().readInt();
            fileOffsets.put(fileId, writer.getPosition());
            writer.write(files.get(fileId));
            // then align to 0x200 with padding of 0xFF
            align(writer, 0x200, (byte) 0xff);
        }

        // write the filename table
        int fntOffset = writer.getPosition();
        MemBuf fntBuf = Fnt.save(filenames);
        writer.write(fntBuf.reader().getBuffer());
        // then align to 0x200 with padding of 0xFF
        align(writer, 0x200, (byte) 0xff);

        // leave some empty space for the file allocation table -- we'll fill in the real values later
        int fatOffset = writer.getPosition();
        writer.writeByteNumTimes((byte) 0, 8 * files.size());
        // then align to 0x200 with padding of 0xFF
        align(writer, 0x200, (byte) 0xff);

        // write the icon/banner
        int iconBannerOffset;
        if (iconBanner.length > 0)
        {
            MemBuf iconBannerBuf = MemBuf.create();
            iconBannerBuf.writer().write(iconBanner);

            int version = ( (int) iconBannerBuf.reader().readShort()) & 0xFFFF;
            int iconBannerLength;
            if (ICON_BANNER_LENGTHS.get(version) == null)
                iconBannerLength = ICON_BANNER_LENGTHS.get(1);
            else
                iconBannerLength = ICON_BANNER_LENGTHS.get(version);

            assert (iconBanner.length == iconBannerLength);

            iconBannerOffset = writer.getPosition();
            writer.write(iconBanner);
            // then align to 0x200 with padding of 0xFF
            align(writer, 0x200, (byte) 0xff);
        }
        else
        {
            iconBannerOffset = 0;
        }

        // write the debug rom
        int debugRomOffset;
        if (debugRom.length > 0)
        {
            debugRomOffset = writer.getPosition();
            writer.write(debugRom);
            // then align to 0x200 with padding of 0xFF
            align(writer, 0x200, (byte) 0xff);
        }
        else
        {
            debugRomOffset = 0;
        }

        // write the rest of the files
        Integer f;
        while((f = getNextFile(fileOffsets)) != null)
        {
            // align before instead of after, so that there's no extra padding after the last file
            align(writer, 0x200, (byte) 0xff);
            fileOffsets.put(f, writer.getPosition());
            writer.write(files.get(f));
        }

        // write the file allocation table (fat)
        storedPosition = writer.getPosition();
        int startOffset;
        int endOffset;
        for (int i = 0; i < files.size(); i++)
        {
            byte[] file = files.get(i);
            assert (fileOffsets.containsKey(i));
            startOffset = fileOffsets.get(i);
            endOffset = startOffset + file.length;
            writer.setPosition(fatOffset + 8*i);
            writer.writeInt(startOffset);
            writer.writeInt(endOffset);
        }
        writer.setPosition(storedPosition);

        // write the RSA signature
        align(writer, 0x20);
        int rsaSignatureOffset = writer.getPosition();
        writer.write(rsaSignature);

        int romSize = writer.getPosition();

        // We need to do this for compatibility with NSMBe (idk why tho)
        writer.setPosition(0x1000);
        writer.writeInt(rsaSignatureOffset);

        // Now that we know how large the ROM data is, we can update the device capacity value
        if (updateDeviceCapacity)
        {
            deviceCapacity = (byte) (Math.ceil(Math.log(romSize) / Math.log(2)) - 17);
        }

        // Now that all the offsets and stuff are determined, write the header data
        writer.setPosition(0);

        writer.writeString(title, 12);
        writer.writeString(gameCode, 4);
        writer.writeString(developerCode, 2);
        writer.writeByte((byte) unitCode);
        writer.writeByte((byte) encryptionSeed);
        writer.writeByte(deviceCapacity);
        assert (writer.getPosition() == 0x15);
        writer.write(reserved1);
        writer.writeByte(reserved2);
        writer.writeByte((byte) systemRegion);
        writer.writeByte((byte) romVersion);
        writer.writeByte((byte) autoStartFlag);
        assert (writer.getPosition() == 0x20);

        writer.writeInt(arm9Offset);
        writer.writeInt(arm9EntryAddress);
        writer.writeInt(arm9LoadAddress);
        writer.writeInt(arm9.length);

        writer.writeInt(arm7Offset);
        writer.writeInt(arm7EntryAddress);
        writer.writeInt(arm7LoadAddress);
        writer.writeInt(arm7.length);
        assert (writer.getPosition() == 0x40);

        writer.writeInt(fntOffset);
        writer.writeInt(fntBuf.writer().getPosition());

        writer.writeInt(fatOffset);
        writer.writeInt(files.size() * 8);

        writer.writeInt(y9Offset);
        writer.writeInt(y9.length);

        writer.writeInt(y7Offset);
        writer.writeInt(y7.length);

        writer.writeInt(normalCardControlRegisterSettings);
        writer.writeInt(secureCardControlRegisterSettings);

        writer.writeInt(iconBannerOffset);
        writer.writeShort(secureAreaCrc); //todo wait when do I recalc this
        writer.writeShort(secureTransferTimeout);
        writer.writeInt(arm9Autoload);
        writer.writeInt(arm7Autoload);
        writer.write(secureDisable);
        writer.writeInt(rsaSignatureOffset);
        writer.writeInt(0x4000);
        writer.write(padding_088h);
        writer.write(nintendoLogo);
        writer.write(calculateCRC16(nintendoLogo));
        writer.write(calculateCRC16(romBuf.reader().readBytes(0x15e)));
        writer.writeInt(debugRomOffset);
        writer.writeInt(debugRom.length);
        writer.writeInt(debugRomAddress);
        writer.write(padding_16Ch);

        assert (writer.getPosition() == 0x200);

        writer.setPosition(romSize);
        romBuf.reader().setPosition(0);
        return romBuf.reader().getBuffer();
    }

    /**
     * Generate binary file representing this ROM, and save it to the file specified by filePath.
     * @param filePath <code>String</code> containing path to file on disk where the rom will be saved
     * @param updateDeviceCapacity whether the rom capacity code in the header will be changed (boolean)
     * @throws IOException if the specified file's parent directory does not exist.
     */
    public void saveToFile(String filePath, boolean updateDeviceCapacity) throws IOException
    {
        saveToFile(new File(filePath), updateDeviceCapacity);
    }

    /**
     * Generate binary file representing this ROM, and save it to the file specified by filePath.
     * @param filePath <code>File</code> containing path to file on disk where the rom will be saved
     * @param updateDeviceCapacity whether the rom capacity code in the header will be changed (boolean)
     * @throws IOException if the specified file's parent directory does not exist.
     */
    public void saveToFile(File filePath, boolean updateDeviceCapacity) throws IOException
    {
        BinaryWriter.writeFile(filePath, save(updateDeviceCapacity));
    }

    private short calculateCRC16(byte... arr)
    {
        CRC16 crc16 = new CRC16();

        for (byte b : arr) {
            crc16.update(b);
        }
        return (short) crc16.getValue();
    }

    private Integer getNextFile(HashMap<Integer, Integer> fileOffsets)
    {

        for (Integer fileNum : sortedFileIDs)
        {
            if (!fileOffsets.containsKey(fileNum) && fileNum < files.size())
            {
                return fileNum;
            }
        }

        for (int fileNum = 0; fileNum < files.size(); fileNum++)
        {
            if (!fileOffsets.containsKey(fileNum))
            {
                return fileNum;
            }
        }

        return null;
    }

    public enum UNPACKED_FILENAMES {
        ARM9("arm9.bin"),
        ARM7("arm7.bin"),
        Y9("y9.bin"),
        Y7("y7.bin"),
        HEADER("header.bin"),
        BANNER("banner.bin"),
        OVERLAY("overlay"),
        DATA("data");

        final String name;

        UNPACKED_FILENAMES(String s) {
            name = s;
        }
    }

    /**
     * Constructor for a <code>NintendoDsRom</code>, to be used when constructing the object from an unpacked ROM directory
     */
    private NintendoDsRom()
    {
        title = "";
        gameCode = "####";
        developerCode = new String(new byte[] {0, 0}, 0, 2);
        unitCode = 0;
        encryptionSeed = 0;
        deviceCapacity = 9;
//        reserved1;
//        reserved2;
        systemRegion = 0;
        romVersion = 0;
        autoStartFlag = 0;

        arm9EntryAddress = 0x2000800;
        arm9LoadAddress = 0x2000000;

        arm7EntryAddress = 0x2380000;
        arm7LoadAddress = 0x2380000;

        normalCardControlRegisterSettings = 0x0416657;
        secureCardControlRegisterSettings = 0x81808f8;

        secureAreaCrc = 0x0000;
        secureTransferTimeout = 0x0D7E;
        arm9Autoload = 0;
        arm7Autoload = 0;
        secureDisable = new byte[] {0, 0, 0, 0, 0, 0, 0, 0};

        //0x38 bytes
        padding_088h = new byte[] {0x38};

        nintendoLogo = new byte[] {
                (byte) 0x24, (byte) 0xFF, (byte) 0xAE, (byte) 0x51, (byte) 0x69, (byte) 0x9A, (byte) 0xA2,
                (byte) 0x21, (byte) 0x3D, (byte) 0x84, (byte) 0x82, (byte) 0x0A, (byte) 0x84, (byte) 0xE4, (byte) 0x09, (byte) 0xAD,
                (byte) 0x11, (byte) 0x24, (byte) 0x8B, (byte) 0x98, (byte) 0xC0, (byte) 0x81, (byte) 0x7F, (byte) 0x21, (byte) 0xA3,
                (byte) 0x52, (byte) 0xBE, (byte) 0x19, (byte) 0x93, (byte) 0x09, (byte) 0xCE, (byte) 0x20, (byte) 0x10, (byte) 0x46,
                (byte) 0x4A, (byte) 0x4A, (byte) 0xF8, (byte) 0x27, (byte) 0x31, (byte) 0xEC, (byte) 0x58, (byte) 0xC7, (byte) 0xE8,
                (byte) 0x33, (byte) 0x82, (byte) 0xE3, (byte) 0xCE, (byte) 0xBF, (byte) 0x85, (byte) 0xF4, (byte) 0xDF, (byte) 0x94,
                (byte) 0xCE, (byte) 0x4B, (byte) 0x09, (byte) 0xC1, (byte) 0x94, (byte) 0x56, (byte) 0x8A, (byte) 0xC0, (byte) 0x13,
                (byte) 0x72, (byte) 0xA7, (byte) 0xFC, (byte) 0x9F, (byte) 0x84, (byte) 0x4D, (byte) 0x73, (byte) 0xA3, (byte) 0xCA,
                (byte) 0x9A, (byte) 0x61, (byte) 0x58, (byte) 0x97, (byte) 0xA3, (byte) 0x27, (byte) 0xFC, (byte) 0x03, (byte) 0x98,
                (byte) 0x76, (byte) 0x23, (byte) 0x1D, (byte) 0xC7, (byte) 0x61, (byte) 0x03, (byte) 0x04, (byte) 0xAE, (byte) 0x56,
                (byte) 0xBF, (byte) 0x38, (byte) 0x84, (byte) 0x00, (byte) 0x40, (byte) 0xA7, (byte) 0x0E, (byte) 0xFD, (byte) 0xFF,
                (byte) 0x52, (byte) 0xFE, (byte) 0x03, (byte) 0x6F, (byte) 0x95, (byte) 0x30, (byte) 0xF1, (byte) 0x97, (byte) 0xFB,
                (byte) 0xC0, (byte) 0x85, (byte) 0x60, (byte) 0xD6, (byte) 0x80, (byte) 0x25, (byte) 0xA9, (byte) 0x63, (byte) 0xBE,
                (byte) 0x03, (byte) 0x01, (byte) 0x4E, (byte) 0x38, (byte) 0xE2, (byte) 0xF9, (byte) 0xA2, (byte) 0x34, (byte) 0xFF,
                (byte) 0xBB, (byte) 0x3E, (byte) 0x03, (byte) 0x44, (byte) 0x78, (byte) 0x00, (byte) 0x90, (byte) 0xCB, (byte) 0x88,
                (byte) 0x11, (byte) 0x3A, (byte) 0x94, (byte) 0x65, (byte) 0xC0, (byte) 0x7C, (byte) 0x63, (byte) 0x87, (byte) 0xF0,
                (byte) 0x3C, (byte) 0xAF, (byte) 0xD6, (byte) 0x25, (byte) 0xE4, (byte) 0x8B, (byte) 0x38, (byte) 0x0A, (byte) 0xAC,
                (byte) 0x72, (byte) 0x21, (byte) 0xD4, (byte) 0xF8, (byte) 0x07};

        debugRomAddress = 0;
        padding_16Ch = new byte[0x94];
        padding_200h = new byte[0x3E00];

        // Misc Section

        rsaSignature = new byte[] {};

        arm9 = new byte[] {};
        arm9PostData = new int[] {};
        arm7 = new byte[] {};
        y9 = new byte[] {};
        y7 = new byte[] {};
        iconBanner = new byte[] {};
        debugRom = new byte[] {};

        filenames = new Fnt.Folder();
        files = new ArrayList<>();
        sortedFileIDs = new ArrayList<>();
    }

    /**
     * Creates a <code>NintendoDsRom</code> from an unpacked ROM on disk
     * @param dir a <code>String</code> containing the path to an unpacked ROM on disk
     * @return a <code>NintendoDsRom</code>
     */
    public static NintendoDsRom fromUnpacked(String dir)
    {
        return fromUnpacked(new File(dir));
    }

    /**
     * Creates a <code>NintendoDsRom</code> from an unpacked ROM on disk
     * @param dir a <code>File</code> containing the path to an unpacked ROM on disk
     * @return a <code>NintendoDsRom</code>
     */
    public static NintendoDsRom fromUnpacked(File dir)
    {
        if (!dir.exists() || !dir.isDirectory())
        {
            throw new RuntimeException("\"" + dir.getAbsolutePath() + "\" does not exist");
        }

        NintendoDsRom rom = new NintendoDsRom();
        byte[] header = Buffer.readFile(Paths.get(dir.getAbsolutePath(), UNPACKED_FILENAMES.HEADER.name));
        MemBuf headerBuf = MemBuf.create();
        headerBuf.writer().write(header);
        rom.readHeader(headerBuf.reader(), headerBuf.writer().getPosition(), true);
        rom.arm9 = Buffer.readFile(Paths.get(dir.getAbsolutePath(), UNPACKED_FILENAMES.ARM9.name));
        rom.arm9Length = rom.arm9.length;
        rom.arm7 = Buffer.readFile(Paths.get(dir.getAbsolutePath(), UNPACKED_FILENAMES.ARM7.name));
        rom.arm7Length = rom.arm7.length;
        rom.y9 = Buffer.readFile(Paths.get(dir.getAbsolutePath(), UNPACKED_FILENAMES.Y9.name));
        rom.y9Length = rom.y9.length;
        rom.y7 = Buffer.readFile(Paths.get(dir.getAbsolutePath(), UNPACKED_FILENAMES.Y7.name));
        rom.y7Length = rom.y7.length;
        rom.iconBanner = Buffer.readFile(Paths.get(dir.getAbsolutePath(), UNPACKED_FILENAMES.BANNER.name));

        File overlayDir = Paths.get(dir.getAbsolutePath(), UNPACKED_FILENAMES.OVERLAY.name).toFile();
        File dataDir = Paths.get(dir.getAbsolutePath(), UNPACKED_FILENAMES.DATA.name).toFile();

        Stream<File> overlayStream = Arrays.stream(Objects.requireNonNull(overlayDir.listFiles(File::isFile)));
        List<File> overlays = overlayStream.sorted(Comparator.comparingInt(o -> Integer.parseInt(o.getName().split("_")[1].replace(".bin", "")))).filter(file -> !file.isHidden()).collect(Collectors.toList());

        int numFiles = Fnt.calculateNumFiles(overlayDir) + Fnt.calculateNumFiles(dataDir);
        for (int i = 0; i < numFiles; i++)
        {
            rom.files.add(null);
        }

        // read the overlays
        int fileId;
        MemBuf y9Buf = MemBuf.create();
        y9Buf.writer().write(rom.y9);
        for (int i = 0; i < rom.y9Length / 32; i++)
        {
            y9Buf.reader().setPosition(i * 32 + 0x18);
            fileId = y9Buf.reader().readInt();
            rom.files.set(fileId, Buffer.readFile(overlays.get(i).getAbsolutePath()));
        }

        rom.filenames = Fnt.loadFromDisk(dataDir, rom.files);

        if (rom.files.contains(null))
            throw new RuntimeException("Internal file table not properly filled");

        return rom;
    }

    /**
     * Unpacks the rom to the target directory on disk
     * @param dir a <code>String</code> containing the path to the target directory
     * @throws IOException if any of the output files fail to be written
     */
    public void unpack(String dir) throws IOException
    {
        unpack(new File(dir));
    }

    /**
     * Unpacks the rom to the target directory on disk
     * @param dir a <code>File</code> object containing the path to the target directory
     * @throws IOException if any of the output files fail to be written
     */
    public void unpack(File dir) throws IOException
    {
        if (dir.exists() && dir.isDirectory() && Objects.requireNonNull(dir.listFiles()).length != 0)
        {
            throw new RuntimeException("Unable to unpack rom, target folder already exists");
        }
        else if (!dir.mkdir())
        {
            throw new RuntimeException("Failed to create unpacked directory, check write perms.");
        }

        //todo figure out arm9 post data
        BinaryWriter.writeFile(Paths.get(dir.getAbsolutePath(), UNPACKED_FILENAMES.ARM9.name), arm9);
        BinaryWriter.writeFile(Paths.get(dir.getAbsolutePath(), UNPACKED_FILENAMES.ARM7.name), arm7);
        BinaryWriter.writeFile(Paths.get(dir.getAbsolutePath(), UNPACKED_FILENAMES.Y9.name), y9);
        BinaryWriter.writeFile(Paths.get(dir.getAbsolutePath(), UNPACKED_FILENAMES.Y7.name), y7);
        BinaryWriter.writeFile(Paths.get(dir.getAbsolutePath(), UNPACKED_FILENAMES.BANNER.name), iconBanner);

        MemBuf headerBuf = MemBuf.create();
        MemBuf.MemBufWriter headerWriter = headerBuf.writer();

        headerWriter.writeString(title, 12);
        headerWriter.writeString(gameCode, 4);
        headerWriter.writeString(developerCode, 2);
        headerWriter.writeByte((byte) unitCode);
        headerWriter.writeByte((byte) encryptionSeed);
        headerWriter.writeByte(deviceCapacity);
        headerWriter.write(reserved1);
        headerWriter.writeByte(reserved2);
        headerWriter.writeByte((byte) systemRegion);
        headerWriter.writeByte((byte) romVersion);
        headerWriter.writeByte((byte) autoStartFlag);

        headerWriter.writeInt(arm9Offset);
        headerWriter.writeInt(arm9EntryAddress);
        headerWriter.writeInt(arm9LoadAddress);
        headerWriter.writeInt(arm9.length);

        headerWriter.writeInt(arm7Offset);
        headerWriter.writeInt(arm7EntryAddress);
        headerWriter.writeInt(arm7LoadAddress);
        headerWriter.writeInt(arm7.length);

        headerWriter.writeInt(fntOffset);
        headerWriter.writeInt(fntLength);

        headerWriter.writeInt(fatOffset);
        headerWriter.writeInt(files.size() * 8);

        headerWriter.writeInt(y9Offset);
        headerWriter.writeInt(y9.length);

        headerWriter.writeInt(y7Offset);
        headerWriter.writeInt(y7.length);

        headerWriter.writeInt(normalCardControlRegisterSettings);
        headerWriter.writeInt(secureCardControlRegisterSettings);

        headerWriter.writeInt(iconBannerOffset);
        headerWriter.writeShort(secureAreaCrc); //todo wait when do I recalc this
        headerWriter.writeShort(secureTransferTimeout);
        headerWriter.writeInt(arm9Autoload);
        headerWriter.writeInt(arm7Autoload);
        headerWriter.write(secureDisable);
        headerWriter.writeUInt32(romSizeOrRsaSigOffset);
        headerWriter.writeUInt32(headerLength);
        headerWriter.write(padding_088h);
        headerWriter.write(nintendoLogo);
        headerWriter.write(calculateCRC16(nintendoLogo));
        headerWriter.write(calculateCRC16(headerBuf.reader().readBytes(0x15e)));
        headerWriter.writeInt(debugRomOffset);
        headerWriter.writeInt(debugRom.length);
        headerWriter.writeInt(debugRomAddress);
        headerWriter.write(padding_16Ch);

        assert (headerWriter.getPosition() == 0x200);
        headerWriter.write(padding_200h);

        headerBuf.reader().setPosition(0);
        BinaryWriter.writeFile(Paths.get(dir.getAbsolutePath(), UNPACKED_FILENAMES.HEADER.name), headerBuf.reader().getBuffer());

        // write the filesystem
        Fnt.writeFolderToDisk(Paths.get(dir.getAbsolutePath(), UNPACKED_FILENAMES.DATA.name).toFile(), filenames, files);

        File overlayDir = Paths.get(dir.getAbsolutePath(), UNPACKED_FILENAMES.OVERLAY.name).toFile();

        if (!overlayDir.mkdir())
        {
            throw new RuntimeException("Failed to create overlay directory, check write perms.");
        }

        // write the overlays
        MemBuf y9Buf = MemBuf.create();
        y9Buf.writer().write(y9);
        int fileId;
        for (int i = 0; i < y9.length / 32; i++)
        {
            y9Buf.reader().setPosition(i * 32 + 0x18);
            fileId = y9Buf.reader().readInt();
            BinaryWriter.writeFile(Paths.get(overlayDir.getAbsolutePath(), formatOutputString(i, y9.length / 32, "overlay_", ".bin")), files.get(fileId));
        }
    }

    /**
     * Return the data for the file with the given filename (path).
     * @param filename a <code>String</code> path to a file in the ROM
     * @return a byte[] representing the file contents
     */
    public byte[] getFileByName(String filename)
    {
        int fid = filenames.getIdOf(filename);
        if (fid == -1)
        {
            throw new RuntimeException("Cannot find file ID of \"" + filename + "\"");
        }
        return files.get(fid);
    }

    /**
     * Set the data for the file with the given filename (path).
     * @param filename a <code>String</code> path to a file in the ROM
     * @param data a <code>byte[]</code> containing the new file contents
     */
    public void setFileByName(String filename, byte[] data)
    {
        int fid = filenames.getIdOf(filename);
        if (fid == -1)
        {
            throw new RuntimeException("Cannot find file ID of \"" + filename + "\"");
        }
        files.set(fid, data);
    }


    public String toString()
    {
        return String.format("ROM \"%s\" (%s)", title, gameCode);
    }
}
