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

import io.github.turtleisaac.nds4j.framework.Buffer;
import io.github.turtleisaac.nds4j.framework.MemBuf;
import io.github.turtleisaac.nds4j.framework.NintendoLogo;
import io.github.turtleisaac.nds4j.framework.SimpleYaml;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reads and writes the ds-rom v0.8.0 ({@code dsrom extract}) folder layout, not {@link NintendoDsRom#unpack}'s ndstool one. */
final class DsRomUnpacked
{
    static final String CONFIG_YAML = "config.yaml";
    static final String HEADER_YAML = "header.yaml";
    static final String DEFAULT_ARM9 = "arm9/arm9.bin";
    static final String DEFAULT_ARM7 = "arm7/arm7.bin";
    static final String DEFAULT_ARM9_YAML = "arm9/arm9.yaml";
    static final String DEFAULT_ARM7_YAML = "arm7/arm7.yaml";
    static final String DEFAULT_ARM9_OVERLAYS = "arm9_overlays/overlays.yaml";
    static final String DEFAULT_ARM7_OVERLAYS = "arm7_overlays/overlays.yaml";
    static final String DEFAULT_FILES_DIR = "files";

    // Where NintendoDsRom.save places the ARM9, which the header's build info offset is relative to.
    private static final int SAVED_ARM9_OFFSET = 0x4000;
    private static final String[] BANNER_LANGUAGES = { "japanese", "english", "french", "german", "italian", "spanish", "chinese", "korean" };

    private DsRomUnpacked()
    {
    }

    static boolean isLayout(File dir)
    {
        if (dir == null || !dir.isDirectory())
            return false;
        if (new File(dir, CONFIG_YAML).isFile())
            return true;
        if (!new File(dir, HEADER_YAML).isFile())
            return false;

        return new File(dir, DEFAULT_FILES_DIR).isDirectory() || new File(dir, DEFAULT_ARM9).isFile();
    }

    static NintendoDsRom load(File dir)
    {
        Map<String, Object> config = readYamlMap(new File(dir, CONFIG_YAML));
        String filesRel = SimpleYaml.getString(config, "files_dir", DEFAULT_FILES_DIR);
        if (filesRel.endsWith("/") || filesRel.endsWith("\\"))
            filesRel = filesRel.substring(0, filesRel.length() - 1);

        NintendoDsRom rom = new NintendoDsRom();
        rom.reserved1 = new byte[7];
        rom.capacityFromSize = true;
        Map<String, Object> header = readYamlMap(new File(dir, SimpleYaml.getString(config, "header", HEADER_YAML)));
        loadHeader(rom, header);
        Object logo = config.get("header_logo");
        if (logo != null)
            rom.nintendoLogo = NintendoLogo.compress(readLogo(new File(dir, SimpleYaml.asString(logo))));

        File arm9File = new File(dir, SimpleYaml.getString(config, "arm9_bin", DEFAULT_ARM9));
        if (!arm9File.isFile())
            throw new RuntimeException("ds-rom extract is missing ARM9 binary: " + arm9File.getAbsolutePath());
        Map<String, Object> arm9cfg = readYamlMap(new File(dir, SimpleYaml.getString(config, "arm9_config", DEFAULT_ARM9_YAML)));
        rom.arm9LoadAddress = SimpleYaml.getInt(arm9cfg, "base_address", rom.arm9LoadAddress);
        rom.arm9EntryAddress = SimpleYaml.getInt(arm9cfg, "entry_function", rom.arm9EntryAddress);
        rom.arm9Autoload = SimpleYaml.getInt(arm9cfg, "autoload_callback", rom.arm9Autoload);
        rom.arm9 = loadArm9(dir, config, arm9cfg, Buffer.readFile(arm9File.getAbsolutePath()), rom.arm9LoadAddress);
        rom.arm9Length = rom.arm9.length;
        if (arm9cfg.containsKey("build_info"))
        {
            int buildInfo = SimpleYaml.getInt(arm9cfg, "build_info", 0);
            MemBuf footer = MemBuf.create();
            footer.writer().writeInt(DsRomExtract.NITROCODE).writeInt(buildInfo).writeInt(SimpleYaml.getInt(arm9cfg, "overlay_signatures", 0));
            byte[] footerBytes = footer.reader().getBuffer();
            rom.arm9PostData = new int[footerBytes.length];
            for (int i = 0; i < footerBytes.length; i++)
            {
                rom.arm9PostData[i] = footerBytes[i] & 0xFF;
            }

            if (SimpleYaml.getBoolean(header, "has_arm9_build_info_offset", false))
                putInt(rom.padding_088h, 0, SAVED_ARM9_OFFSET + buildInfo);
        }

        File arm7File = new File(dir, SimpleYaml.getString(config, "arm7_bin", DEFAULT_ARM7));
        if (!arm7File.isFile())
            throw new RuntimeException("ds-rom extract is missing ARM7 binary: " + arm7File.getAbsolutePath());
        rom.arm7 = Buffer.readFile(arm7File.getAbsolutePath());
        rom.arm7Length = rom.arm7.length;
        Map<String, Object> arm7cfg = readYamlMap(new File(dir, SimpleYaml.getString(config, "arm7_config", DEFAULT_ARM7_YAML)));
        rom.arm7LoadAddress = SimpleYaml.getInt(arm7cfg, "base_address", rom.arm7LoadAddress);
        rom.arm7EntryAddress = SimpleYaml.getInt(arm7cfg, "entry_function", rom.arm7EntryAddress);
        rom.arm7Autoload = SimpleYaml.getInt(arm7cfg, "autoload_callback", rom.arm7Autoload);

        Object banner = config.get("banner");
        if (banner != null && new File(dir, SimpleYaml.asString(banner)).isFile())
            rom.iconBanner = loadBanner(new File(dir, SimpleYaml.asString(banner)));
        Object multiboot = config.get("multiboot_signature");
        if (multiboot != null)
            rom.rsaSignature = loadMultibootSignature(readYamlMap(new File(dir, SimpleYaml.asString(multiboot))));

        List<OverlayEntry> arm9Overlays = loadOverlays(dir, config.get("arm9_overlays"), DEFAULT_ARM9_OVERLAYS);
        List<OverlayEntry> arm7Overlays = loadOverlays(dir, config.get("arm7_overlays"), DEFAULT_ARM7_OVERLAYS);
        rom.y9 = buildOverlayTable(arm9Overlays);
        rom.y9Length = rom.y9.length;
        rom.y7 = buildOverlayTable(arm7Overlays);
        rom.y7Length = rom.y7.length;

        File filesDir = new File(dir, filesRel);
        if (!filesDir.isDirectory())
            throw new RuntimeException("ds-rom extract is missing files/ directory: " + filesDir.getAbsolutePath());

        List<OverlayEntry> allOverlays = new ArrayList<OverlayEntry>(arm9Overlays);
        allOverlays.addAll(arm7Overlays);
        int maxOverlayFileId = -1;
        for (OverlayEntry ov : allOverlays)
        {
            maxOverlayFileId = Math.max(maxOverlayFileId, ov.fileId);
        }

        int numFiles = maxOverlayFileId + 1 + Fnt.calculateNumFiles(filesDir);
        rom.files = new ArrayList<byte[]>();
        for (int i = 0; i < numFiles; i++)
        {
            rom.files.add(null);
        }

        for (OverlayEntry ov : allOverlays)
        {
            if (ov.fileId < 0 || ov.fileId >= rom.files.size())
                throw new RuntimeException("overlay file_id " + ov.fileId + " is outside the file table");
            if (!ov.bin.isFile())
                throw new RuntimeException("ds-rom overlay binary missing: " + ov.bin.getAbsolutePath());
            rom.files.set(ov.fileId, Buffer.readFile(ov.bin.getAbsolutePath()));
        }

        rom.filenames = loadFolder(filesDir, rom.files);
        if (rom.files.contains(null))
            throw new RuntimeException("Internal file table not properly filled");

        MemBuf fntBuf = Fnt.save(rom.filenames);
        rom.fnt = fntBuf.reader().getBuffer();
        rom.fntLength = rom.fnt.length;

        File pathOrder = new File(dir, SimpleYaml.getString(config, "path_order", "path_order.txt"));
        if (pathOrder.isFile())
            rom.sortedFileIDs = fileOrder(rom.filenames, new String(Buffer.readFile(pathOrder.getAbsolutePath()), StandardCharsets.UTF_8).split("\n"));
        return rom;
    }

    /** Reads {@code dir} into the next free file ids in the order {@code dsrom build} numbers files and folders. */
    private static Fnt.Folder loadFolder(File dir, ArrayList<byte[]> files)
    {
        Fnt.Folder folder = new Fnt.Folder(dir.getName());
        List<File> children = new ArrayList<File>();
        for (File child : Objects.requireNonNull(dir.listFiles()))
        {
            if (!child.isHidden())
                children.add(child);
        }

        Collections.sort(children, new Comparator<File>() {
            @Override
            public int compare(File a, File b)
            {
                if (a.isDirectory() != b.isDirectory())
                    return a.isDirectory() ? 1 : -1;

                return compareBytes(fntSortKey(a.getName()), fntSortKey(b.getName()));
            }
        });
        folder.firstId = files.indexOf(null) < 0 ? files.size() : files.indexOf(null);
        for (File child : children)
        {
            if (child.isDirectory())
                continue;
            int id = files.indexOf(null);
            if (id < 0)
                throw new RuntimeException("No available file IDs to allocate.");
            folder.files.add(child.getName());
            files.set(id, Buffer.readFile(child.getAbsolutePath()));
        }

        for (File child : children)
        {
            if (child.isDirectory())
                folder.folders.put(child.getName(), loadFolder(child, files));
        }

        return folder;
    }

    /** Shift-JIS bytes lowercased byte by byte, the strcasecmp-like key ds-rom sorts FNT entries by. */
    private static byte[] fntSortKey(String name)
    {
        byte[] bytes;
        try
        {
            bytes = name.getBytes(Charset.forName("windows-31j"));
        }
        catch (IllegalArgumentException e)
        {
            bytes = name.getBytes(StandardCharsets.UTF_8);
        }

        for (int i = 0; i < bytes.length; i++)
        {
            if (bytes[i] >= 'A' && bytes[i] <= 'Z')
                bytes[i] += 'a' - 'A';
        }

        return bytes;
    }

    /** The order {@code dsrom build} lays files out in: each path_order entry, a folder's unvisited files by name. */
    private static ArrayList<Integer> fileOrder(Fnt.Folder root, String[] pathOrder)
    {
        ArrayList<Integer> order = new ArrayList<Integer>();
        Set<Integer> visitedFiles = new HashSet<Integer>();
        Set<Fnt.Folder> visitedFolders = Collections.newSetFromMap(new IdentityHashMap<Fnt.Folder, Boolean>());
        for (String line : pathOrder)
        {
            String path = line.startsWith("/") ? line.substring(1) : line;
            if (path.trim().isEmpty())
            {
                addFolderFiles(root, order, visitedFiles, visitedFolders);
                continue;
            }

            Fnt.Folder parent = root;
            String[] parts = path.split("/");
            for (int i = 0; i < parts.length - 1 && parent != null; i++)
            {
                parent = parent.getFolders().get(parts[i]);
            }

            if (parent == null)
                continue;
            String last = parts[parts.length - 1];
            if (parent.getFolders().containsKey(last))
                addFolderFiles(parent.getFolders().get(last), order, visitedFiles, visitedFolders);
            else if (parent.getFiles().contains(last) && visitedFiles.add(parent.getFirstId() + parent.getFiles().indexOf(last)))
                order.add(parent.getFirstId() + parent.getFiles().indexOf(last));
        }

        return order;
    }

    private static void addFolderFiles(Fnt.Folder folder, List<Integer> order, Set<Integer> visitedFiles, Set<Fnt.Folder> visitedFolders)
    {
        if (!visitedFolders.add(folder))
            return;
        List<String> names = new ArrayList<String>(folder.getFiles());
        names.addAll(folder.getFolders().keySet());
        Collections.sort(names, new Comparator<String>() {
            @Override
            public int compare(String a, String b)
            {
                return compareUtf8(a, b);
            }
        });
        for (String name : names)
        {
            Fnt.Folder sub = folder.getFolders().get(name);
            if (sub != null)
            {
                addFolderFiles(sub, order, visitedFiles, visitedFolders);
                continue;
            }

            int id = folder.getFirstId() + folder.getFiles().indexOf(name);
            if (visitedFiles.add(id))
                order.add(id);
        }
    }

    private static int compareUtf8(String a, String b)
    {
        return compareBytes(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static int compareBytes(byte[] x, byte[] y)
    {
        for (int i = 0; i < Math.min(x.length, y.length); i++)
        {
            if (x[i] != y[i])
                return (x[i] & 0xFF) - (y[i] & 0xFF);
        }

        return x.length - y.length;
    }

    /** Writes {@code rom} to {@code dir} as {@code dsrom extract} would extract the ROM {@code rom} saves to. */
    static void save(NintendoDsRom rom, File dir) throws IOException
    {
        if (dir.exists() && dir.isDirectory() && Objects.requireNonNull(dir.listFiles()).length != 0)
            throw new IOException("Unable to write ds-rom extract, target folder already exists");
        else if (!dir.exists() && !dir.mkdirs())
            throw new IOException("Failed to create ds-rom extract directory, check write perms.");
        DsRomExtract.write(rom.save(false), dir);
    }

    private static void loadHeader(NintendoDsRom rom, Map<String, Object> header)
    {
        rom.title = SimpleYaml.getString(header, "title", rom.title);
        String gamecode = SimpleYaml.getString(header, "gamecode", rom.gameCode);
        if (gamecode != null && !gamecode.isEmpty())
            rom.gameCode = gamecode.length() >= 4 ? gamecode.substring(0, 4) : gamecode;
        String maker = SimpleYaml.getString(header, "makercode", rom.developerCode);
        if (maker != null)
            rom.developerCode = maker.length() >= 2 ? maker.substring(0, 2) : maker;
        rom.unitCode = SimpleYaml.getInt(header, "unitcode", rom.unitCode);
        rom.encryptionSeed = SimpleYaml.getInt(header, "seed_select", rom.encryptionSeed);
        rom.systemRegion = SimpleYaml.getInt(header, "ds_flags", rom.systemRegion);
        rom.romVersion = SimpleYaml.getInt(header, "rom_version", rom.romVersion);
        rom.autoStartFlag = SimpleYaml.getInt(header, "autostart", rom.autoStartFlag);
        rom.normalCardControlRegisterSettings = SimpleYaml.getInt(header, "normal_cmd_setting", rom.normalCardControlRegisterSettings);
        rom.secureCardControlRegisterSettings = SimpleYaml.getInt(header, "key1_cmd_setting", rom.secureCardControlRegisterSettings);
        rom.secureTransferTimeout = (short) SimpleYaml.getInt(header, "secure_area_delay", rom.secureTransferTimeout);
        putShort(rom.padding_088h, 0x94 - 0x88, SimpleYaml.getInt(header, "rom_nand_end", 0));
        putShort(rom.padding_088h, 0x96 - 0x88, SimpleYaml.getInt(header, "rw_nand_end", 0));

        Object postDsi = header.get("ds_post_dsi");
        if (postDsi == null)
            return;
        Map<String, Object> fields = SimpleYaml.asMap(postDsi);
        putInt(rom.padding_16Ch, 0x1BC - 0x16C, SimpleYaml.getInt(fields, "dsi_flags_2", 0));
        putBytes(rom.padding_200h, 0x33C - 0x200, fields.get("sha1_hmac_banner"));
        putBytes(rom.padding_200h, 0x378 - 0x200, fields.get("sha1_hmac_unk1"));
        putBytes(rom.padding_200h, 0x38C - 0x200, fields.get("sha1_hmac_unk2"));
        putBytes(rom.padding_200h, 0xF80 - 0x200, fields.get("rsa_sha1"));
    }

    /** Re-appends the autoloads and their info table to the ARM9 code and points the build info at them. */
    private static byte[] loadArm9(File dir, Map<String, Object> config, Map<String, Object> arm9cfg, byte[] code, int baseAddress)
    {
        if (!arm9cfg.containsKey("build_info") || !arm9cfg.containsKey("bss_start"))
            return code;

        List<Autoload> autoloads = new ArrayList<Autoload>();
        addAutoload(dir, config.get("itcm"), 0, autoloads);
        addAutoload(dir, config.get("dtcm"), 1, autoloads);
        Object unknown = config.get("unknown_autoloads");
        if (unknown != null)
        {
            for (Object entry : SimpleYaml.asList(unknown))
            {
                addAutoload(dir, entry, 2 + SimpleYaml.getInt(SimpleYaml.asMap(entry), "index", 0), autoloads);
            }
        }

        Collections.sort(autoloads, new Comparator<Autoload>() {
            @Override
            public int compare(Autoload a, Autoload b)
            {
                return Integer.compare(a.order, b.order);
            }
        });

        MemBuf buf = MemBuf.create();
        MemBuf.MemBufWriter writer = buf.writer();
        writer.write(code);
        int autoloadBlocks = baseAddress + writer.getPosition();
        for (Autoload autoload : autoloads)
        {
            writer.write(autoload.code);
        }

        int infosStart = baseAddress + writer.getPosition();
        for (Autoload autoload : autoloads)
        {
            writer.writeInt(autoload.baseAddress).writeInt(autoload.codeSize).writeInt(autoload.bssSize);
        }

        int infosEnd = baseAddress + writer.getPosition();
        writer.setPosition(SimpleYaml.getInt(arm9cfg, "build_info", 0));
        writer.writeInt(infosStart).writeInt(infosEnd).writeInt(autoloadBlocks);
        writer.writeInt(SimpleYaml.getInt(arm9cfg, "bss_start", 0)).writeInt(SimpleYaml.getInt(arm9cfg, "bss_end", 0));
        // compressed_code_end: the code is stored decompressed
        writer.writeInt(0);
        writer.writeInt(SimpleYaml.getInt(arm9cfg, "sdk_version", 0));
        writer.setPosition(infosEnd - baseAddress);

        return buf.reader().getBuffer();
    }

    private static void addAutoload(File dir, Object paths, int order, List<Autoload> out)
    {
        if (paths == null)
            return;
        Map<String, Object> map = SimpleYaml.asMap(paths);
        File bin = new File(dir, SimpleYaml.getString(map, "bin", ""));
        File yaml = new File(dir, SimpleYaml.getString(map, "config", ""));
        if (!bin.isFile() || !yaml.isFile())
            return;
        Map<String, Object> info = readYamlMap(yaml);
        Autoload autoload = new Autoload();
        autoload.order = order;
        autoload.code = Buffer.readFile(bin.getAbsolutePath());
        autoload.baseAddress = SimpleYaml.getInt(info, "base_address", 0);
        autoload.codeSize = SimpleYaml.getInt(info, "code_size", autoload.code.length);
        autoload.bssSize = SimpleYaml.getInt(info, "bss_size", 0);
        out.add(autoload);
    }

    private static boolean[][] readLogo(File file)
    {
        BufferedImage image = readPng(file);
        if (image.getWidth() != NintendoLogo.WIDTH || image.getHeight() != NintendoLogo.HEIGHT)
            throw new RuntimeException("header logo must be 104x16 pixels: " + file.getAbsolutePath());
        boolean[][] pixels = new boolean[NintendoLogo.HEIGHT][NintendoLogo.WIDTH];
        for (int y = 0; y < NintendoLogo.HEIGHT; y++)
        {
            for (int x = 0; x < NintendoLogo.WIDTH; x++)
            {
                pixels[y][x] = (image.getRGB(x, y) & 0xFFFFFF) == 0;
            }
        }

        return pixels;
    }

    /** Builds the raw banner; an animated (DSi) banner comes back as version 3, as its animation is not extracted. */
    private static byte[] loadBanner(File yaml)
    {
        Map<String, Object> banner = readYamlMap(yaml);
        String version = SimpleYaml.getString(banner, "version", "Original");
        int versionNumber = "China".equals(version) ? 2 : "Original".equals(version) ? 1 : 3;
        byte[] data = new byte[versionNumber == 1 ? 0x840 : versionNumber == 2 ? 0x940 : 0xA40];
        putShort(data, 0, versionNumber);

        Map<String, Object> titles = SimpleYaml.asMap(banner.get("title"));
        for (int i = 0; i < BANNER_LANGUAGES.length; i++)
        {
            String title = SimpleYaml.getString(titles, BANNER_LANGUAGES[i], null);
            int offset = 0x240 + i * 0x100;
            if (title == null || offset + 0x100 > data.length)
                continue;
            byte[] utf16 = title.getBytes(StandardCharsets.UTF_16LE);
            System.arraycopy(utf16, 0, data, offset, Math.min(utf16.length, 0x100));
        }

        Map<String, Object> images = SimpleYaml.asMap(banner.get("images"));
        File dir = yaml.getParentFile();
        BufferedImage palette = readPng(new File(dir, SimpleYaml.getString(images, "palette_path", "palette.png")));
        BufferedImage bitmap = readPng(new File(dir, SimpleYaml.getString(images, "bitmap_path", "bitmap.png")));
        if (palette.getWidth() != 16 || palette.getHeight() != 1 || bitmap.getWidth() != 32 || bitmap.getHeight() != 32)
            throw new RuntimeException("banner bitmap must be 32x32 and its palette 16x1: " + dir.getAbsolutePath());
        for (int i = 0; i < 16; i++)
        {
            int rgb = palette.getRGB(i, 0);
            putShort(data, 0x220 + i * 2, ((rgb >> 19) & 31) | (((rgb >> 11) & 31) << 5) | (((rgb >> 3) & 31) << 10));
        }

        for (int y = 0; y < 32; y++)
        {
            for (int x = 0; x < 32; x++)
            {
                int index = paletteIndex(bitmap.getRGB(x, y), palette);
                int at = 0x20 + (y / 8 * 0x80) + (x / 8 * 0x20) + (y % 8 * 4) + (x / 2 % 4);
                data[at] |= (byte) (index << ((x % 2) * 4));
            }
        }

        return new IconBanner(data).toBytes();
    }

    private static int paletteIndex(int argb, BufferedImage palette)
    {
        if ((argb >>> 24) == 0)
            return 0;
        for (int i = 0; i < 16; i++)
        {
            if (palette.getRGB(i, 0) == argb)
                return i;
        }

        throw new RuntimeException(String.format("banner pixel color %08x is not in its palette", argb));
    }

    /** Rebuilds the 0x88-byte signature block that sits at the end of the ROM. */
    private static byte[] loadMultibootSignature(Map<String, Object> yaml)
    {
        Object rsa = yaml.get("rsa_signature");
        if (!(rsa instanceof byte[]) || ((byte[]) rsa).length != 0x80)
            throw new RuntimeException("multiboot signature needs a 128-byte !!binary rsa_signature");
        MemBuf buf = MemBuf.create();
        buf.writer().writeInt(SimpleYaml.getInt(yaml, "magic", 0)).write((byte[]) rsa).writeInt(SimpleYaml.getInt(yaml, "key_seed", 0));

        return buf.reader().getBuffer();
    }

    private static BufferedImage readPng(File file)
    {
        try
        {
            BufferedImage image = ImageIO.read(file);
            if (image == null)
                throw new RuntimeException("not a readable image: " + file.getAbsolutePath());

            return image;
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    private static void putShort(byte[] data, int offset, int value)
    {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >> 8);
    }

    private static void putInt(byte[] data, int offset, int value)
    {
        putShort(data, offset, value);
        putShort(data, offset + 2, value >> 16);
    }

    private static void putBytes(byte[] data, int offset, Object list)
    {
        if (list == null)
            return;
        List<Object> values = SimpleYaml.asList(list);
        for (int i = 0; i < values.size(); i++)
        {
            data[offset + i] = (byte) SimpleYaml.asInt(values.get(i));
        }
    }

    private static Map<String, Object> readYamlMap(File file)
    {
        if (file == null || !file.isFile())
            return Collections.emptyMap();
        byte[] bytes = Buffer.readFile(file.getAbsolutePath());
        Object parsed = SimpleYaml.parse(new String(bytes, StandardCharsets.UTF_8));
        if (parsed == null)
            return Collections.emptyMap();
        return SimpleYaml.asMap(parsed);
    }

    private static List<OverlayEntry> loadOverlays(File root, Object configured, String defaultRel)
    {
        String rel = null;
        if (configured instanceof String)
            rel = (String) configured;
        else if (configured == null && new File(root, defaultRel).isFile())
            rel = defaultRel;
        if (rel == null || rel.isEmpty())
            return Collections.emptyList();

        File yaml = new File(root, rel);
        if (!yaml.isFile())
            return Collections.emptyList();
        File overlayDir = yaml.getParentFile() == null ? root : yaml.getParentFile();
        Object overlaysObj = readYamlMap(yaml).get("overlays");
        if (overlaysObj == null)
            return Collections.emptyList();
        List<Object> overlays = SimpleYaml.asList(overlaysObj);
        List<OverlayEntry> out = new ArrayList<OverlayEntry>(overlays.size());
        for (int i = 0; i < overlays.size(); i++)
        {
            Map<String, Object> m = SimpleYaml.asMap(overlays.get(i));
            OverlayEntry e = new OverlayEntry();
            e.id = SimpleYaml.getInt(m, "id", i);
            e.baseAddress = SimpleYaml.getInt(m, "base_address", 0);
            e.codeSize = SimpleYaml.getInt(m, "code_size", 0);
            e.bssSize = SimpleYaml.getInt(m, "bss_size", 0);
            e.ctorStart = SimpleYaml.getInt(m, "ctor_start", 0);
            e.ctorEnd = SimpleYaml.getInt(m, "ctor_end", 0);
            e.fileId = SimpleYaml.getInt(m, "file_id", e.id);
            e.signed = SimpleYaml.getBoolean(m, "signed", false);
            e.bin = new File(overlayDir, SimpleYaml.getString(m, "file_name", String.format("ov%03d.bin", e.id)));
            out.add(e);
        }

        Collections.sort(out, new Comparator<OverlayEntry>() {
            @Override
            public int compare(OverlayEntry a, OverlayEntry b)
            {
                return Integer.compare(a.id, b.id);
            }
        });
        return out;
    }

    private static byte[] buildOverlayTable(List<OverlayEntry> overlays)
    {
        MemBuf buf = MemBuf.create();
        MemBuf.MemBufWriter w = buf.writer();
        for (OverlayEntry e : overlays)
        {
            w.writeInt(e.id);
            w.writeInt(e.baseAddress);
            w.writeInt(e.codeSize);
            w.writeInt(e.bssSize);
            w.writeInt(e.ctorStart);
            w.writeInt(e.ctorEnd);
            w.writeInt(e.fileId);
            // Overlay bins are stored decompressed, so the compressed flag and size stay clear.
            w.writeInt(e.signed ? 0x02000000 : 0);
        }

        return buf.reader().getBuffer();
    }

    private static final class Autoload
    {
        int order;
        int baseAddress;
        int codeSize;
        int bssSize;
        byte[] code;
    }

    private static final class OverlayEntry
    {
        int id;
        int baseAddress;
        int codeSize;
        int bssSize;
        int ctorStart;
        int ctorEnd;
        int fileId;
        boolean signed;
        File bin;
    }
}
