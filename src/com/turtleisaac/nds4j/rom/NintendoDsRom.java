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
import com.turtleisaac.nds4j.framework.Buffer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.stream.Collectors;

import com.turtleisaac.nds4j.framework.CRC16;
import com.turtleisaac.nds4j.framework.MemBuf;
import com.turtleisaac.nds4j.rom.Fnt.Folder;

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

    int romSizeOrRsaSigOffset;
    int headerLength;

    byte[] padding_088h;
    byte[] reserved4;
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

    private static final HashMap<Integer, Integer> ICON_BANNER_LENGTHS = new HashMap<>() {
        {
            put(0x0001, 0x840);
            put(0x0002, 0x940);
            put(0x0003, 0x1240);
            put(0x0103, 0x23C0);
        }
    };


//    public NintendoDsRom()
//    {
//        String title = "";
//        String gameCode = "####";
//        String makerCode = "\0\0";
//        int deviceCode = 0;
//        int encryptionSeed = 0;
//        byte romChipCapacity = 9;
//        byte[] reserved1 = {0, 0, 0, 0, 0, 0, 0};
//        byte reserved2 = 0;
//        int systemRegion = 0;
//        int romVersion = 0;
//        int autoStartFlag = 0;
//
//        int arm9EntryAddress = 0x2000800;
//        int arm9LoadAddress = 0x2000000;
//        int arm7EntryAddress = 0x2380000;
//        int arm7LoadAddress = 0x2380000;
//
//        int fntbOffset;
//        int fntbLength;
//
//        int fatbOffset;
//        int fatbLength;
//
//        int arm9OverlayOffset;
//        int amr9OverlayLength;
//
//        int arm7OverlayOffset;
//        int arm7OverlayLength;
//
//        int normalCardControlRegisterSettings;
//        int secureCardControlRegisterSettings;
//
//        int iconBannerOffset;
//        short secureAreaCrc;
//        short secureTransferTimeout;
//        int arm9Autoload;
//        int arm7Autoload;
//        byte[] secureDisable;
//
//        int romLength;
//        int headerLength;
//
//        byte[] reserved3;
//        byte[] reserved4;
//        byte[] nintendoLogo;
//        short nintendoLogoCrc;
//        short headerCrc;
//        int debugRomOffset;
//        int debugLength;
//        int debugRamOffset;
//        int reserved5;
//        byte[] reserved6;
//    }

    public static NintendoDsRom fromFile(String file)
    {
        return fromFile(new File(file));
    }

    public static NintendoDsRom fromFile(Path file)
    {
        return fromFile(file.toFile());
    }

    public static NintendoDsRom fromFile(File file)
    {
        Buffer buffer = new Buffer(file);

    }


    public NintendoDsRom(String file)
    {
        this(Buffer.getVirtualBuffer(file));
    }

    public NintendoDsRom(Buffer buffer)
    {
        title = buffer.readString(12).trim();
        gameCode = buffer.readString(4);
        developerCode = buffer.readString(2);
        unitCode = buffer.readByte();
        encryptionSeed = buffer.readByte();
        deviceCapacity = buffer.readBytes(1)[0];
        reserved1 = buffer.readBytes(7);
        reserved2 = (byte)buffer.readByte();
        systemRegion = buffer.readByte();
        romVersion = buffer.readByte();
        autoStartFlag = buffer.readByte();

        arm9Offset = buffer.readInt();
        if(arm9Offset < 0x4000) {
            throw new RuntimeException("Invalid ROM Header: ARM9 Offset");
        }
        arm9EntryAddress = buffer.readInt();
        if(!(arm9EntryAddress >= 0x2000000 && arm9EntryAddress <= 0x23BFE00)) {
            throw new RuntimeException("Invalid ROM Header: ARM9 Entry Address");
        }
        arm9LoadAddress = buffer.readInt();
        if(!(arm9LoadAddress >= 0x2000000 && arm9LoadAddress <= 0x23BFE00)) {
            throw new RuntimeException("Invalid ROM Header: ARM9 RAM Address");
        }
        arm9Length = buffer.readInt();
        if(arm9Length > 0x3BFE00) {
            throw new RuntimeException("Invalid ROM Header: ARM9 Size");
        }
        arm7Offset = buffer.readInt();
        if(arm7Offset < 0x8000) {
            throw new RuntimeException("Invalid ROM Header: ARM7 Offset");
        }
        arm7EntryAddress = buffer.readInt();
        if(!((arm7EntryAddress >= 0x2000000 && arm7EntryAddress <= 0x23BFE00) || (arm7EntryAddress >= 0x37F8000 && arm7EntryAddress <= 0x3807E00))) {
            throw new RuntimeException("Invalid ROM Header: ARM7 Entry Address");
        }
        arm7LoadAddress = buffer.readInt();
        if(!((arm7LoadAddress >= 0x2000000 && arm7LoadAddress <= 0x23BFE00) || (arm7LoadAddress >= 0x37F8000 && arm7LoadAddress <= 0x3807E00))) {
            throw new RuntimeException("Invalid ROM Header: ARM7 Load Address");
        }
        arm7Length = buffer.readInt();
        if(arm7Length > 0x3BFE0) {
            throw new RuntimeException("Invalid ROM Header: ARM7 Size");
        }

        System.out.println(buffer.getPosition());
        fntOffset = buffer.readInt();
        fntLength = buffer.readInt();

        fatOffset = buffer.readInt();
        fatLength = buffer.readInt();

        y9Offset = buffer.readInt();
        y9Length = buffer.readInt();

        y7Offset = buffer.readInt();
        y7Length = buffer.readInt();

        normalCardControlRegisterSettings = buffer.readInt();
        secureCardControlRegisterSettings = buffer.readInt();

        iconBannerOffset = buffer.readInt();
        secureAreaCrc = buffer.readShort();
        secureTransferTimeout = buffer.readShort();
        arm9Autoload = buffer.readInt();
        arm7Autoload = buffer.readInt();
        secureDisable = buffer.readBytes(8);

        romSizeOrRsaSigOffset = buffer.readInt();
        headerLength = buffer.readInt();

        padding_088h = buffer.readBytes(0x38);
        nintendoLogo = buffer.readBytes(0x9C);
        nintendoLogoCrc = buffer.readShort();
        headerCrc = buffer.readShort();
        debugRomOffset = buffer.readInt();
        debugRomLength = buffer.readInt();
        debugRomAddress = buffer.readInt();
        padding_16Ch = buffer.readBytes(0x94);
        padding_200h = buffer.readBytes(Math.min(arm9Offset, buffer.getLength()));

        // RSA signature file
        int realSigOffset = 0;
        if (buffer.getLength() >= 0x1004)
        {
            buffer.seekGlobal(0x1000);
            realSigOffset = buffer.readInt();
        }
        if (realSigOffset == 0 && buffer.getLength() > romSizeOrRsaSigOffset)
        {
            realSigOffset = romSizeOrRsaSigOffset;
        }
        if (realSigOffset != 0)
        {
            buffer.seekGlobal(realSigOffset);
            rsaSignature = buffer.readBytes(Math.min(buffer.getPosition(), realSigOffset + 0x88));
        }
        else
        {
            rsaSignature = new byte[] {};
        }

        // arm9, arm7, FNT, FAT, overlay tables, icon banner
        buffer.seekGlobal(arm9Offset);
        arm9 = buffer.readBytes(arm9Length);

        buffer.seekGlobal(arm7Offset);
        arm7 = buffer.readBytes(arm7Length);

        buffer.seekGlobal(fntOffset);
        fnt = buffer.readBytes(fntLength);

        buffer.seekGlobal(fatOffset);
        fat = buffer.readBytes(fatLength);

        buffer.seekGlobal(y9Offset);
        y9 = buffer.readBytes(y9Length);

        buffer.seekGlobal(y7Offset);
        y7 = buffer.readBytes(y7Length);

        if (iconBannerOffset != 0)
        {
            buffer.seekGlobal(iconBannerOffset);
            int val = buffer.readUInt16();
            int iconBannerLength;
            if (ICON_BANNER_LENGTHS.get(val) == null)
                iconBannerLength = ICON_BANNER_LENGTHS.get(1);
            else
                iconBannerLength = ICON_BANNER_LENGTHS.get(val);

            iconBanner = buffer.readBytes(iconBannerLength);
        }
        else
        {
            iconBanner = new byte[ICON_BANNER_LENGTHS.get(1)];
        }

        if (debugRomOffset != 0)
        {
            buffer.seekGlobal(debugRomOffset);
            debugRom = buffer.readBytes(debugRomLength);
        }
        else
        {
            debugRom = new byte[1];
        }

        int arm9PostDataOffset = arm9Offset + arm9Length;
        ArrayList<Integer> arm9PostData = new ArrayList<>();

        int[] extraData;
        while (Arrays.equals(new int[] {0x21, 0x06, 0xC0, 0xDE}, (extraData = buffer.readBytesI(4)) ))
        {
            arm9PostData.addAll(Arrays.stream(extraData).boxed().collect(Collectors.toList()));
            arm9PostData.addAll(Arrays.stream(buffer.readBytesI(8)).boxed().collect(Collectors.toList()));
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
            readFat(buffer);
        }
    }

    private void readFat(Buffer romBuffer)
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
            romBuffer.seekGlobal(startOffset);
            files.add(romBuffer.readTo(endOffset));
            offsetToId.put(startOffset, i);
            offsetToIdKeys.add(startOffset);
        }

        for (Long offset : offsetToIdKeys.stream().sorted().collect(Collectors.toList()))
        {
            sortedFileIDs.add(offsetToId.get(offset));
        }
    }

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

    public byte[] save()
    {
        return save(false);
    }

    public byte[] save(boolean updateDeviceCapacity)
    {
        HashMap<Integer, Integer> fileOffsets = new HashMap<>();

        MemBuf romBuf = MemBuf.create();
        MemBuf.MemBufWriter writer = romBuf.writer();

        // to begin, assume header size of 0x200 (header will be filled in at end)
        int storedPosition = 0x200;
        // then add size of padding_200h (bytes between end of header and start of arm9)
        writer.writeAt(padding_200h, 0, 0x200, padding_200h.length);
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

        // write the arm9 overlays
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

        writer.writeString(title);
        writer.writeString(gameCode);
        writer.writeString(developerCode);
        writer.writeByte((byte) unitCode);
        writer.writeByte((byte) encryptionSeed);
        writer.writeByte(deviceCapacity);
        writer.write(reserved1);
        writer.writeByte(reserved2);
        writer.writeByte((byte) systemRegion);
        writer.writeByte((byte) romVersion);
        writer.writeByte((byte) autoStartFlag);

        writer.writeInt(arm9Offset);
        writer.writeInt(arm9EntryAddress);
        writer.writeInt(arm9LoadAddress);
        writer.writeInt(arm9.length);

        writer.writeInt(arm7Offset);
        writer.writeInt(arm7EntryAddress);
        writer.writeInt(arm7LoadAddress);
        writer.writeInt(arm7.length);

        writer.writeInt(fntOffset);
        writer.writeInt(fntBuf.len);

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
     * Generate binary file representing this ROM, and save it to the file specified by filePath, and does not update device capacity code
     * @param filePath <code>String</code> path to file where the rom will be saved
     */
    public void saveToFile(String filePath) throws IOException
    {
        saveRomFile(new File(filePath), false);
    }

    /**
     * Generate binary file representing this ROM, and save it to the file specified by filePath.
     * @param filePath <code>String</code> to file where the rom will be saved
     * @param updateDeviceCapacity whether the rom capacity code in the header will be changed (boolean)
     */
    public void saveToFile(String filePath, boolean updateDeviceCapacity) throws IOException
    {
        saveRomFile(new File(filePath), updateDeviceCapacity);
    }

    /**
     * Generate binary file representing this ROM, and save it to the file specified by filePath, and does not update device capacity code
     * @param filePath <code>Path</code> path to file where the rom will be saved
     */
    public void saveToFile(Path filePath) throws IOException
    {
        saveRomFile(filePath.toFile(), false);
    }

    /**
     * Generate binary file representing this ROM, and save it to the file specified by filePath.
     * @param filePath <code>Path</code> to file where the rom will be saved
     * @param updateDeviceCapacity whether the rom capacity code in the header will be changed (boolean)
     */
    public void saveToFile(Path filePath, boolean updateDeviceCapacity) throws IOException
    {
        saveRomFile(filePath.toFile(), updateDeviceCapacity);
    }

    /**
     * Generate binary file representing this ROM, and save it to the file specified by filePath, and does not update device capacity code
     * @param filePath <code>File</code> path to file where the rom will be saved
     */
    public void saveToFile(File filePath) throws IOException
    {
        saveRomFile(filePath, false);
    }

    /**
     * Generate binary file representing this ROM, and save it to the file specified by filePath.
     * @param filePath <code>File</code> to file where the rom will be saved
     * @param updateDeviceCapacity whether the rom capacity code in the header will be changed (boolean)
     */
    public void saveToFile(File filePath, boolean updateDeviceCapacity) throws IOException
    {
        saveRomFile(filePath, updateDeviceCapacity);
    }

    private void saveRomFile(File filePath, boolean updateDeviceCapacity) throws IOException
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

        for (byte[] fileNum : files)
        {
            //TODO finish this and figure out wtf is going on with the contents of "files" in the ndspy src
        }

        return null;
    }

    public String toString()
    {
        return String.format("ROM \"%s\" (%s)", title, gameCode);
    }



    public static void main(String[] args)
    {
        NintendoDsRom rom = new NintendoDsRom("HeartGold.nds");
        System.out.println(rom.title);
    }
}
