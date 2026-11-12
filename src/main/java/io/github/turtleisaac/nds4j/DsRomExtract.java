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

import io.github.turtleisaac.nds4j.framework.BinaryWriter;
import io.github.turtleisaac.nds4j.framework.CodeCompression;
import io.github.turtleisaac.nds4j.framework.MemBuf;
import io.github.turtleisaac.nds4j.framework.NintendoLogo;
import io.github.turtleisaac.nds4j.framework.SimpleYaml;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Writes a ROM image as the folder {@code dsrom extract} (ds-rom v0.8.0) produces, file for file. */
final class DsRomExtract
{
    static final int NITROCODE = 0xDEC00621;
    static final int ROOT_DIR_ID = 0xF000;

    private static final int HEADER_SIZE = 0x1000;
    private static final int MULTIBOOT_MAGIC = 0x00016361;
    private static final int DEFAULT_ALIGNMENT = 4;
    private static final int DEFAULT_PADDING = 0xFF;
    private static final byte[] SECURE_AREA_ID = { (byte) 0xFF, (byte) 0xDE, (byte) 0xFF, (byte) 0xE7, (byte) 0xFF, (byte) 0xDE, (byte) 0xFF, (byte) 0xE7 };
    private static final int SECURE_AREA_UNENCRYPTED_THRESHOLD = 256;
    private static final String[] BANNER_LANGUAGES = { "japanese", "english", "french", "german", "italian", "spanish", "chinese", "korean" };

    private final byte[] rom;
    private final MemBuf.MemBufReader header;
    private final int[] fatStart;
    private final int[] fatEnd;
    private final List<int[]> arm9Overlays;
    private final List<int[]> arm7Overlays;

    private DsRomExtract(byte[] rom)
    {
        if (rom.length < HEADER_SIZE)
            throw new IllegalArgumentException("ROM image is smaller than its 0x1000-byte header");
        this.rom = rom;
        this.header = MemBuf.create(Arrays.copyOf(rom, HEADER_SIZE)).reader();

        int fatOffset = u32At(0x48);
        int fatCount = u32At(0x4C) / 8;
        MemBuf.MemBufReader fat = slice(fatOffset, fatCount * 8);
        fatStart = new int[fatCount];
        fatEnd = new int[fatCount];
        for (int i = 0; i < fatCount; i++)
        {
            fatStart[i] = fat.readInt();
            fatEnd[i] = fat.readInt();
        }

        arm9Overlays = readOverlayTable(u32At(0x50), u32At(0x54));
        arm7Overlays = readOverlayTable(u32At(0x58), u32At(0x5C));
    }

    /** Writes the ROM image {@code rom} to {@code dir} in the {@code dsrom extract} layout. */
    static void write(byte[] rom, File dir) throws IOException
    {
        new DsRomExtract(rom).write(dir);
    }

    private void write(File dir) throws IOException
    {
        Arm9 arm9 = readArm9();
        FileTree tree = readFileTree();
        List<String> pathOrder = tree.computePathOrder();

        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("header", "header.yaml");
        config.put("header_logo", "header_logo.png");
        config.put("arm9_bin", "arm9/arm9.bin");
        config.put("arm9_config", "arm9/arm9.yaml");
        config.put("arm7_bin", "arm7/arm7.bin");
        config.put("arm7_config", "arm7/arm7.yaml");
        config.put("itcm", autoloadPaths("arm9/itcm"));
        config.put("dtcm", autoloadPaths("arm9/dtcm"));
        List<Object> unknownAutoloads = new ArrayList<Object>();
        for (Autoload autoload : arm9.autoloads)
        {
            if (autoload.kind.equals("Unknown"))
            {
                Map<String, Object> entry = new LinkedHashMap<String, Object>();
                entry.put("index", autoload.index);
                entry.putAll(autoloadPaths("arm9/unk_autoload_" + autoload.index));
                unknownAutoloads.add(entry);
            }
        }

        if (!unknownAutoloads.isEmpty())
            config.put("unknown_autoloads", unknownAutoloads);
        config.put("arm9_overlays", arm9Overlays.isEmpty() ? null : "arm9_overlays/overlays.yaml");
        config.put("arm7_overlays", arm7Overlays.isEmpty() ? null : "arm7_overlays/overlays.yaml");
        config.put("banner", "banner/banner.yaml");
        config.put("files_dir", "files/");
        config.put("path_order", "path_order.txt");
        config.put("arm9_hmac_sha1_key", arm9.hmacKey == null ? null : "arm9/hmac_sha1_key.bin");
        byte[] multiboot = readMultibootSignature();
        config.put("multiboot_signature", multiboot == null ? null : "multiboot_signature.yaml");
        config.put("alignment", alignments());
        config.put("padding", paddingValues());
        writeYaml(new File(dir, "config.yaml"), config);

        writeYaml(new File(dir, "header.yaml"), headerYaml());
        writeLogo(new File(dir, "header_logo.png"));
        writeArm9(dir, arm9);
        if (!arm9Overlays.isEmpty())
            writeOverlays(new File(dir, "arm9_overlays"), arm9Overlays, arm9);
        writeArm7(dir);
        if (!arm7Overlays.isEmpty())
            writeOverlays(new File(dir, "arm7_overlays"), arm7Overlays, null);
        writeBanner(new File(dir, "banner"));
        tree.writeFiles(new File(dir, "files"));

        StringBuilder order = new StringBuilder();
        for (String path : pathOrder)
        {
            order.append(path).append('\n');
        }

        writeBinary(new File(dir, "path_order.txt"), order.toString().getBytes(StandardCharsets.UTF_8));
        if (multiboot != null)
            writeYaml(new File(dir, "multiboot_signature.yaml"), multibootYaml(multiboot));
    }

    private Map<String, Object> headerYaml()
    {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("title", ascii(0x00, 12));
        out.put("gamecode", ascii(0x0C, 4));
        out.put("makercode", ascii(0x10, 2));
        out.put("unitcode", u8At(0x12));
        out.put("seed_select", u8At(0x13));
        out.put("ds_flags", u8At(0x1D));
        out.put("rom_version", u8At(0x1E));
        out.put("autostart", u8At(0x1F));
        out.put("normal_cmd_setting", unsigned(u32At(0x60)));
        out.put("key1_cmd_setting", unsigned(u32At(0x64)));
        out.put("secure_area_delay", u16At(0x6E));
        out.put("rom_nand_end", u16At(0x94));
        out.put("rw_nand_end", u16At(0x96));
        out.put("has_arm9_build_info_offset", u32At(0x88) != 0);
        int dsiFlags2 = u32At(0x1BC);
        if (dsiFlags2 != 0)
        {
            Map<String, Object> postDsi = new LinkedHashMap<String, Object>();
            postDsi.put("dsi_flags_2", unsigned(dsiFlags2));
            postDsi.put("sha1_hmac_banner", byteList(0x33C, 0x14));
            postDsi.put("sha1_hmac_unk1", byteList(0x378, 0x14));
            postDsi.put("sha1_hmac_unk2", byteList(0x38C, 0x14));
            postDsi.put("rsa_sha1", byteList(0xF80, 0x80));
            out.put("ds_post_dsi", postDsi);
        }

        return out;
    }

    private void writeLogo(File file) throws IOException
    {
        boolean[][] pixels = NintendoLogo.decompress(Arrays.copyOfRange(rom, 0xC0, 0xC0 + NintendoLogo.COMPRESSED_SIZE));
        BufferedImage image = new BufferedImage(NintendoLogo.WIDTH, NintendoLogo.HEIGHT, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < NintendoLogo.HEIGHT; y++)
        {
            for (int x = 0; x < NintendoLogo.WIDTH; x++)
            {
                image.getRaster().setSample(x, y, 0, pixels[y][x] ? 0x00 : 0xFF);
            }
        }

        writePng(file, image);
    }

    private Arm9 readArm9() throws IOException
    {
        int offset = u32At(0x20);
        int size = u32At(0x2C);
        Arm9 arm9 = new Arm9();
        arm9.baseAddress = u32At(0x28);
        arm9.entryFunction = u32At(0x24);
        arm9.autoloadCallback = u32At(0x70);

        MemBuf.MemBufReader footer = slice(offset + size, 12);
        int nitrocode = footer.readInt();
        if (nitrocode != NITROCODE)
            throw new IOException(String.format("expected nitrocode 0x%08x in ARM9 footer but got 0x%08x", NITROCODE, nitrocode));
        int footerBuildInfo = footer.readInt();
        arm9.overlaySignatures = footer.readInt();
        int headerBuildInfo = u32At(0x88);
        if (headerBuildInfo == 0)
            arm9.buildInfo = footerBuildInfo;
        else if (Integer.compareUnsigned(headerBuildInfo, offset) > 0)
            arm9.buildInfo = headerBuildInfo - offset;
        else
            arm9.buildInfo = headerBuildInfo;

        byte[] data = Arrays.copyOfRange(rom, offset, offset + size);
        if (isEncrypted(data))
            throw new IOException("the ARM9 secure area is encrypted; ds-rom needs the Blowfish key to extract it");

        MemBuf.MemBufReader buildInfo = MemBuf.create(Arrays.copyOfRange(data, arm9.buildInfo, arm9.buildInfo + 0x24)).reader();
        int autoloadInfosStart = buildInfo.readInt();
        int autoloadInfosEnd = buildInfo.readInt();
        int autoloadBlocks = buildInfo.readInt();
        arm9.bssStart = buildInfo.readInt();
        arm9.bssEnd = buildInfo.readInt();
        arm9.compressed = buildInfo.readInt() != 0;
        arm9.sdkVersion = buildInfo.readInt();

        if (arm9.compressed)
        {
            data = CodeCompression.decompress(data);
            // compressed_code_end
            Arrays.fill(data, arm9.buildInfo + 0x14, arm9.buildInfo + 0x18, (byte) 0);
        }

        arm9.data = data;

        int infoCount = (autoloadInfosEnd - autoloadInfosStart) / 12;
        MemBuf.MemBufReader infos = MemBuf.create(Arrays.copyOfRange(data, autoloadInfosStart - arm9.baseAddress, autoloadInfosEnd - arm9.baseAddress)).reader();
        int loadOffset = autoloadBlocks - arm9.baseAddress;
        for (int i = 0; i < infoCount; i++)
        {
            Autoload autoload = new Autoload();
            autoload.index = i;
            autoload.baseAddress = infos.readInt();
            autoload.codeSize = infos.readInt();
            autoload.bssSize = infos.readInt();
            autoload.kind = autoloadKind(autoload.baseAddress);
            autoload.code = Arrays.copyOfRange(data, loadOffset, loadOffset + autoload.codeSize);
            loadOffset += autoload.codeSize;
            arm9.autoloads.add(autoload);
        }

        arm9.hmacKey = findHmacKey(data);

        return arm9;
    }

    private static String autoloadKind(int baseAddress)
    {
        switch (baseAddress)
        {
            case 0x1FF8000:
                return "Itcm";
            case 0x27E0000:
            case 0x27C0000:
            case 0x23C0000:
                return "Dtcm";
            default:
                return "Unknown";
        }
    }

    private static boolean isEncrypted(byte[] data)
    {
        if (data.length >= 8 && Arrays.equals(Arrays.copyOf(data, 8), SECURE_AREA_ID))
            return false;
        if (data.length < 0x800)
            return true;
        int zeroCount = 0;
        for (int i = 0; i < 0x800; i++)
        {
            if (data[i] == 0)
                zeroCount++;
        }

        return zeroCount < SECURE_AREA_UNENCRYPTED_THRESHOLD;
    }

    /** The 64-byte key that starts at the second word-aligned nitrocode in the ARM9 program. */
    private static byte[] findHmacKey(byte[] data)
    {
        int matches = 0;
        for (int i = 0; i + 4 <= data.length; i += 4)
        {
            if (data[i] == 0x21 && data[i + 1] == 0x06 && data[i + 2] == (byte) 0xC0 && data[i + 3] == (byte) 0xDE && ++matches == 2)
                return i + 64 <= data.length ? Arrays.copyOfRange(data, i, i + 64) : null;
        }

        return null;
    }

    private void writeArm9(File dir, Arm9 arm9) throws IOException
    {
        writeBinary(new File(dir, "arm9/arm9.bin"), Arrays.copyOf(arm9.data, arm9.bssStart - arm9.baseAddress));

        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("base_address", unsigned(arm9.baseAddress));
        config.put("entry_function", unsigned(arm9.entryFunction));
        config.put("build_info", unsigned(arm9.buildInfo));
        config.put("autoload_callback", unsigned(arm9.autoloadCallback));
        config.put("overlay_signatures", unsigned(arm9.overlaySignatures));
        config.put("encrypted", false);
        config.put("compressed", arm9.compressed);
        config.put("bss_start", unsigned(arm9.bssStart));
        config.put("bss_end", unsigned(arm9.bssEnd));
        config.put("sdk_version", unsigned(arm9.sdkVersion));
        writeYaml(new File(dir, "arm9/arm9.yaml"), config);

        if (arm9.hmacKey != null)
            writeBinary(new File(dir, "arm9/hmac_sha1_key.bin"), arm9.hmacKey);

        for (Autoload autoload : arm9.autoloads)
        {
            String name = autoload.kind.equals("Itcm") ? "itcm" : autoload.kind.equals("Dtcm") ? "dtcm" : "unk_autoload_" + autoload.index;
            writeBinary(new File(dir, "arm9/" + name + ".bin"), autoload.code);
            Map<String, Object> info = new LinkedHashMap<String, Object>();
            info.put("base_address", unsigned(autoload.baseAddress));
            info.put("code_size", unsigned(autoload.codeSize));
            info.put("bss_size", unsigned(autoload.bssSize));
            if (autoload.kind.equals("Unknown"))
                info.put("kind", Collections.<String, Object> singletonMap("Unknown", autoload.index));
            else
                info.put("kind", autoload.kind);
            writeYaml(new File(dir, "arm9/" + name + ".yaml"), info);
        }
    }

    private void writeArm7(File dir) throws IOException
    {
        int offset = u32At(0x30);
        writeBinary(new File(dir, "arm7/arm7.bin"), Arrays.copyOfRange(rom, offset, offset + u32At(0x3C)));
        int buildInfo = u32At(0x8C);

        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("base_address", unsigned(u32At(0x38)));
        config.put("entry_function", unsigned(u32At(0x34)));
        config.put("build_info", buildInfo == 0 ? 0L : unsigned(buildInfo - offset));
        config.put("autoload_callback", unsigned(u32At(0x74)));
        writeYaml(new File(dir, "arm7/arm7.yaml"), config);
    }

    /** Each entry is the eight words of a raw overlay table row. */
    private List<int[]> readOverlayTable(int offset, int size)
    {
        List<int[]> out = new ArrayList<int[]>();
        if (offset == 0 && size == 0)
            return out;
        MemBuf.MemBufReader table = slice(offset, size);
        for (int i = 0; i < size / 32; i++)
        {
            int[] row = new int[8];
            for (int j = 0; j < 8; j++)
            {
                row[j] = table.readInt();
            }

            out.add(row);
        }

        return out;
    }

    /** Writes {@code overlays.yaml} and the decompressed overlays; {@code arm9} is null for ARM7 overlays. */
    private void writeOverlays(File dir, List<int[]> overlays, Arm9 arm9) throws IOException
    {
        boolean hasSignatures = arm9 != null && arm9.overlaySignatures != 0 && arm9.overlaySignatures + 20L * overlays.size() <= arm9.data.length;
        boolean tableSigned = arm9 != null && arm9.overlaySignatures != 0 && arm9.overlaySignatures <= arm9.data.length;

        List<Object> configs = new ArrayList<Object>();
        for (int[] row : overlays)
        {
            boolean compressed = (row[7] & 0x01000000) != 0;
            boolean flaggedSigned = (row[7] & 0x02000000) != 0;
            if (flaggedSigned && arm9 == null)
                throw new IOException("ARM7 overlay " + row[0] + " is signed, which ds-rom does not support");

            Map<String, Object> config = new LinkedHashMap<String, Object>();
            config.put("id", unsigned(row[0]));
            config.put("base_address", unsigned(row[1]));
            config.put("code_size", unsigned(row[2]));
            config.put("bss_size", unsigned(row[3]));
            config.put("ctor_start", unsigned(row[4]));
            config.put("ctor_end", unsigned(row[5]));
            config.put("file_id", unsigned(row[6]));
            config.put("compressed", compressed);
            config.put("signed", flaggedSigned && (hasSignatures || arm9.hmacKey != null));
            String fileName = String.format("ov%03d.bin", row[0]);
            config.put("file_name", fileName);
            configs.add(config);

            byte[] data = Arrays.copyOfRange(rom, fatStart[row[6]], fatEnd[row[6]]);
            if (compressed)
                data = CodeCompression.decompress(data);
            writeBinary(new File(dir, fileName), Arrays.copyOf(data, row[2]));
        }

        Map<String, Object> table = new LinkedHashMap<String, Object>();
        table.put("table_signed", tableSigned);
        if (tableSigned)
            table.put("table_signature", hex(Arrays.copyOfRange(arm9.data, arm9.overlaySignatures - 20, arm9.overlaySignatures)));
        table.put("overlays", configs);
        writeYaml(new File(dir, "overlays.yaml"), table);
    }

    private void writeBanner(File dir) throws IOException
    {
        int offset = u32At(0x68);
        int version = u16At(offset);
        String versionName;
        switch (version)
        {
            case 0x0001:
                versionName = "Original";
                break;
            case 0x0002:
                versionName = "China";
                break;
            case 0x0003:
                versionName = "Korea";
                break;
            case 0x0103:
                versionName = "Animated";
                break;
            default:
                throw new IOException("unknown banner version 0x" + Integer.toHexString(version));
        }

        int languages = version >= 0x0003 ? 8 : version >= 0x0002 ? 7 : 6;
        Map<String, Object> titles = new LinkedHashMap<String, Object>();
        for (int i = 0; i < languages; i++)
        {
            titles.put(BANNER_LANGUAGES[i], bannerTitle(offset + 0x240 + i * 0x100));
        }

        Map<String, Object> images = new LinkedHashMap<String, Object>();
        images.put("bitmap_path", "bitmap.png");
        images.put("palette_path", "palette.png");
        Map<String, Object> banner = new LinkedHashMap<String, Object>();
        banner.put("version", versionName);
        banner.put("title", titles);
        banner.put("images", images);
        writeYaml(new File(dir, "banner.yaml"), banner);

        int[] palette = new int[16];
        BufferedImage paletteImage = new BufferedImage(16, 1, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < 16; i++)
        {
            int color = u16At(offset + 0x220 + i * 2);
            int r = (color & 31) << 3;
            int g = ((color >> 5) & 31) << 3;
            int b = ((color >> 10) & 31) << 3;
            palette[i] = (i > 0 ? 0xFF000000 : 0) | (r << 16) | (g << 8) | b;
            paletteImage.setRGB(i, 0, palette[i]);
        }

        BufferedImage bitmap = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++)
        {
            for (int x = 0; x < 32; x++)
            {
                int index = (y / 8 * 0x80) + (x / 8 * 0x20) + (y % 8 * 4) + (x / 2 % 4);
                bitmap.setRGB(x, y, palette[(rom[offset + 0x20 + index] >> ((x % 2) * 4)) & 0xF]);
            }
        }

        writePng(new File(dir, "bitmap.png"), bitmap);
        writePng(new File(dir, "palette.png"), paletteImage);
    }

    /** UTF-16 title up to the first NUL or unpaired surrogate. */
    private String bannerTitle(int offset)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 0x80; i++)
        {
            char c = (char) u16At(offset + i * 2);
            if (c == 0 || Character.isSurrogate(c))
                break;
            sb.append(c);
        }

        return sb.toString();
    }

    private byte[] readMultibootSignature()
    {
        int start = u32At(0x80);
        if (start < 0 || (long) start + 0x88 > rom.length)
            return null;
        byte[] signature = Arrays.copyOfRange(rom, start, start + 0x88);

        return MemBuf.create(signature).reader().readInt() == MULTIBOOT_MAGIC ? signature : null;
    }

    private static Map<String, Object> multibootYaml(byte[] signature)
    {
        MemBuf.MemBufReader reader = MemBuf.create(signature).reader();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("magic", unsigned(reader.readInt()));
        out.put("rsa_signature", reader.readBytes(0x80));
        out.put("key_seed", unsigned(reader.readInt()));

        return out;
    }

    private Map<String, Object> alignments()
    {
        Set<Integer> overlayFiles = overlayFileIds();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("arm9", alignment(u32At(0x20)));
        out.put("arm9_overlay_table", alignment(u32At(0x50)));
        out.put("arm9_overlay", overlayAlignment(arm9Overlays));
        out.put("arm7", alignment(u32At(0x30)));
        out.put("arm7_overlay_table", alignment(u32At(0x58)));
        out.put("arm7_overlay", overlayAlignment(arm7Overlays));
        out.put("file_name_table", alignment(u32At(0x40)));
        out.put("file_allocation_table", alignment(u32At(0x48)));
        out.put("banner", alignment(u32At(0x68)));
        long minStart = Long.MAX_VALUE;
        int fileAlignment = Integer.MAX_VALUE;
        for (int i = 0; i < fatStart.length; i++)
        {
            if (overlayFiles.contains(i))
                continue;
            minStart = Math.min(minStart, Integer.toUnsignedLong(fatStart[i]));
            fileAlignment = Math.min(fileAlignment, alignment(fatStart[i]));
        }

        out.put("file_image_block", minStart == Long.MAX_VALUE ? DEFAULT_ALIGNMENT : alignment((int) minStart));
        out.put("file", fileAlignment == Integer.MAX_VALUE ? DEFAULT_ALIGNMENT : fileAlignment);

        return out;
    }

    private int overlayAlignment(List<int[]> overlays)
    {
        int min = Integer.MAX_VALUE;
        for (int[] row : overlays)
        {
            min = Math.min(min, alignment(fatStart[row[6]]));
        }

        return min == Integer.MAX_VALUE ? DEFAULT_ALIGNMENT : min;
    }

    /** 0x200 when the next section starts on a 0x200 boundary, else 4. */
    private static int alignment(int nextSection)
    {
        return Integer.numberOfTrailingZeros(nextSection) >= 9 ? 0x200 : DEFAULT_ALIGNMENT;
    }

    private Map<String, Object> paddingValues()
    {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("arm9", byteBefore(u32At(0x20)));
        out.put("arm9_overlay_table", u32At(0x50) > 0 ? byteBefore(u32At(0x50)) : DEFAULT_PADDING);
        out.put("arm9_overlays", overlaysPadding(arm9Overlays));
        out.put("arm7", byteBefore(u32At(0x30)));
        out.put("arm7_overlay_table", u32At(0x58) > 0 ? byteBefore(u32At(0x58)) : DEFAULT_PADDING);
        out.put("arm7_overlays", overlaysPadding(arm7Overlays));
        out.put("fnt", byteBefore(u32At(0x40)));
        out.put("fat", byteBefore(u32At(0x48)));
        out.put("banner", byteBefore(u32At(0x68)));
        out.put("file_image", fileImagePadding());
        out.put("rom", rom[rom.length - 1] & 0xFF);

        return out;
    }

    private int byteBefore(int offset)
    {
        return rom[offset - 1] & 0xFF;
    }

    /** The byte before the first gap between overlay files; ds-rom indexes the FAT by overlay id here. */
    private int overlaysPadding(List<int[]> overlays)
    {
        List<Integer> files = new ArrayList<Integer>();
        for (int[] row : overlays)
        {
            files.add(row[0]);
        }

        sortByStart(files);
        for (int i = 1; i < files.size(); i++)
        {
            if (fatEnd[files.get(i - 1)] != fatStart[files.get(i)])
                return byteBefore(fatStart[files.get(i)]);
        }

        return DEFAULT_PADDING;
    }

    private int fileImagePadding()
    {
        Set<Integer> overlayFiles = overlayFileIds();
        List<Integer> files = new ArrayList<Integer>();
        for (int i = 0; i < fatStart.length; i++)
        {
            if (!overlayFiles.contains(i))
                files.add(i);
        }

        sortByStart(files);
        for (int i = 1; i < files.size(); i++)
        {
            if (fatEnd[files.get(i - 1)] != fatStart[files.get(i)])
                return rom[fatEnd[files.get(i - 1)]] & 0xFF;
        }

        return DEFAULT_PADDING;
    }

    private void sortByStart(List<Integer> files)
    {
        Collections.sort(files, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b)
            {
                return Integer.compareUnsigned(fatStart[a], fatStart[b]);
            }
        });
    }

    private Set<Integer> overlayFileIds()
    {
        Set<Integer> out = new TreeSet<Integer>();
        for (int[] row : arm9Overlays)
        {
            out.add(row[6]);
        }

        for (int[] row : arm7Overlays)
        {
            out.add(row[6]);
        }

        return out;
    }

    private FileTree readFileTree()
    {
        int fntOffset = u32At(0x40);
        int fntSize = u32At(0x44);
        byte[] fnt = Arrays.copyOfRange(rom, fntOffset, fntOffset + fntSize);
        FileTree tree = new FileTree();
        Dir root = new Dir(ROOT_DIR_ID, "/", 0);
        tree.dirs.put(ROOT_DIR_ID, root);
        tree.parseSubtable(fnt, MemBuf.create(fnt).reader(), root);

        return tree;
    }

    private static Map<String, Object> autoloadPaths(String stem)
    {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("bin", stem + ".bin");
        out.put("config", stem + ".yaml");

        return out;
    }

    private MemBuf.MemBufReader slice(int offset, int size)
    {
        return MemBuf.create(Arrays.copyOfRange(rom, offset, offset + size)).reader();
    }

    private int u32At(int offset)
    {
        header.setPosition(offset);
        return header.readInt();
    }

    private int u16At(int offset)
    {
        if (offset + 2 <= HEADER_SIZE)
        {
            header.setPosition(offset);
            return header.readUInt16();
        }

        return (rom[offset] & 0xFF) | ((rom[offset + 1] & 0xFF) << 8);
    }

    private int u8At(int offset)
    {
        return rom[offset] & 0xFF;
    }

    private String ascii(int offset, int length)
    {
        int end = offset;
        while (end < offset + length && rom[end] != 0)
        {
            end++;
        }

        return new String(rom, offset, end - offset, StandardCharsets.ISO_8859_1);
    }

    private List<Object> byteList(int offset, int length)
    {
        List<Object> out = new ArrayList<Object>(length);
        for (int i = 0; i < length; i++)
        {
            out.add(rom[offset + i] & 0xFF);
        }

        return out;
    }

    private static long unsigned(int value)
    {
        return Integer.toUnsignedLong(value);
    }

    private static String hex(byte[] bytes)
    {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes)
        {
            sb.append(String.format("%02x", b & 0xFF));
        }

        return sb.toString();
    }

    private static void writeYaml(File file, Map<String, Object> content) throws IOException
    {
        writeBinary(file, SimpleYaml.dump(content).getBytes(StandardCharsets.UTF_8));
    }

    private static void writePng(File file, BufferedImage image) throws IOException
    {
        mkParentDirs(file);
        if (!ImageIO.write(image, "png", file))
            throw new IOException("no PNG writer available for " + file);
    }

    static void writeBinary(File file, byte[] bytes) throws IOException
    {
        mkParentDirs(file);
        BinaryWriter.writeFile(file, bytes);
    }

    private static void mkParentDirs(File file) throws IOException
    {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("could not create " + parent.getAbsolutePath());
    }

    private static final class Arm9
    {
        int baseAddress;
        int entryFunction;
        int buildInfo;
        int autoloadCallback;
        int overlaySignatures;
        boolean compressed;
        int bssStart;
        int bssEnd;
        int sdkVersion;
        byte[] data;
        byte[] hmacKey;
        final List<Autoload> autoloads = new ArrayList<Autoload>();
    }

    private static final class Autoload
    {
        int index;
        int baseAddress;
        int codeSize;
        int bssSize;
        String kind;
        byte[] code;
    }

    private static final class Dir
    {
        final int id;
        final String name;
        final int parentId;
        final List<Integer> children = new ArrayList<Integer>();

        Dir(int id, String name, int parentId)
        {
            this.id = id;
            this.name = name;
            this.parentId = parentId;
        }
    }

    private static final class PathOrder
    {
        final int id;
        final int parentId;
        final String pathName;
        final long offset;

        PathOrder(int id, int parentId, String pathName, long offset)
        {
            this.id = id;
            this.parentId = parentId;
            this.pathName = pathName;
            this.offset = offset;
        }
    }

    /** The FNT's directory tree with children in FNT order, as ds-rom's {@code FileSystem::parse} builds it. */
    private final class FileTree
    {
        final Map<Integer, Dir> dirs = new HashMap<Integer, Dir>();
        final Map<Integer, String> fileNames = new HashMap<Integer, String>();

        void parseSubtable(byte[] fnt, MemBuf.MemBufReader main, Dir parent)
        {
            main.setPosition((parent.id & 0xFFF) * 8);
            int position = main.readInt();
            int fileId = main.readUInt16();
            while (true)
            {
                int length = fnt[position++] & 0xFF;
                if (length == 0)
                    break;
                String name = decodeName(Arrays.copyOfRange(fnt, position, position + (length & 0x7F)));
                position += length & 0x7F;
                if ((length & 0x80) != 0)
                {
                    int dirId = (fnt[position] & 0xFF) | ((fnt[position + 1] & 0xFF) << 8);
                    position += 2;
                    Dir dir = new Dir(dirId, name, parent.id);
                    dirs.put(dirId, dir);
                    parent.children.add(dirId);
                    parseSubtable(fnt, main, dir);
                }
                else
                {
                    fileNames.put(fileId, name);
                    parent.children.add(fileId++);
                }
            }
        }

        void writeFiles(File filesDir) throws IOException
        {
            writeDir(filesDir, dirs.get(ROOT_DIR_ID));
        }

        private void writeDir(File dir, Dir parent) throws IOException
        {
            for (int child : parent.children)
            {
                if (isDir(child))
                    writeDir(new File(dir, dirs.get(child).name), dirs.get(child));
                else
                    writeBinary(new File(dir, fileNames.get(child)), Arrays.copyOfRange(rom, fatStart[child], fatEnd[child]));
            }
        }

        /** Port of ds-rom's {@code FileSystem::compute_path_order}. */
        List<String> computePathOrder()
        {
            List<PathOrder> paths = new ArrayList<PathOrder>();
            collectPaths("", dirs.get(ROOT_DIR_ID), paths);
            Collections.sort(paths, new Comparator<PathOrder>() {
                @Override
                public int compare(PathOrder a, PathOrder b)
                {
                    return Long.compare(a.offset, b.offset);
                }
            });

            int childrenStart = 0;
            while (childrenStart < paths.size())
            {
                int parentId = paths.get(childrenStart).parentId;
                if (parentId == 0)
                {
                    childrenStart++;
                    continue;
                }

                int childrenEnd = childrenStart;
                while (childrenEnd < paths.size() && paths.get(childrenEnd).parentId == parentId)
                {
                    childrenEnd++;
                }

                while (childrenStart > 0 && paths.get(childrenStart - 1).parentId == parentId)
                {
                    childrenStart--;
                }

                Dir parent = dirs.get(parentId);
                Set<Integer> visited = new HashSet<Integer>();
                for (int i = 0; i < childrenStart; i++)
                {
                    visited.add(paths.get(i).id);
                }

                int unvisitedChildren = 0;
                for (int child : parent.children)
                {
                    if (!visited.contains(child))
                        unvisitedChildren++;
                }

                if (childrenEnd - childrenStart == unvisitedChildren && isSorted(paths.subList(childrenStart, childrenEnd)))
                {
                    PathOrder first = paths.get(childrenStart);
                    int slash = first.pathName.lastIndexOf('/');
                    String pathName = slash <= 0 ? "/" : first.pathName.substring(0, slash);
                    paths.subList(childrenStart, childrenEnd).clear();
                    paths.add(childrenStart, new PathOrder(parentId, parent.parentId, pathName, first.offset));
                }
                else
                {
                    childrenStart = childrenEnd;
                }
            }

            List<String> out = new ArrayList<String>(paths.size());
            for (PathOrder path : paths)
            {
                out.add(path.pathName);
            }

            return out;
        }

        private void collectPaths(String path, Dir parent, List<PathOrder> out)
        {
            for (int child : parent.children)
            {
                String childPath = path + "/" + name(child);
                if (isDir(child))
                    collectPaths(childPath, dirs.get(child), out);
                else
                    out.add(new PathOrder(child, parent.id, childPath, Integer.toUnsignedLong(fatStart[child])));
            }
        }

        private boolean isSorted(List<PathOrder> paths)
        {
            for (int i = 1; i < paths.size(); i++)
            {
                if (compareUtf8(name(paths.get(i - 1).id), name(paths.get(i).id)) >= 0)
                    return false;
            }

            return true;
        }

        private String name(int id)
        {
            return isDir(id) ? dirs.get(id).name : fileNames.get(id);
        }

        private boolean isDir(int id)
        {
            return id >= ROOT_DIR_ID;
        }
    }

    private static int compareUtf8(String a, String b)
    {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < Math.min(x.length, y.length); i++)
        {
            if (x[i] != y[i])
                return (x[i] & 0xFF) - (y[i] & 0xFF);
        }

        return x.length - y.length;
    }

    /** FNT names are Shift-JIS, which ds-rom decodes the WHATWG (windows-31j) way. */
    private static String decodeName(byte[] bytes)
    {
        try
        {
            return new String(bytes, Charset.forName("windows-31j"));
        }
        catch (IllegalArgumentException e)
        {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }
}
