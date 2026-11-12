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
import java.util.HashMap;
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
    private static final Map<String, Integer> BANNER_VERSIONS = new HashMap<String, Integer>();
    private static final Map<Integer, Integer> BANNER_SIZES = new HashMap<Integer, Integer>();

    // dsrom build numbers a folder's files before its subfolders, each group in case-insensitive Shift-JIS order.
    private static final Comparator<File> FNT_ORDER = DsRomUnpacked::compareFntEntries;
    private static final Comparator<String> UTF8_ORDER = DsRomExtract::compareUtf8;

    static
    {
        BANNER_VERSIONS.put("Original", 1);
        BANNER_VERSIONS.put("China", 2);
        BANNER_SIZES.put(1, 0x840);
        BANNER_SIZES.put(2, 0x940);
        BANNER_SIZES.put(3, 0x1240);
    }

    private DsRomUnpacked()
    {
    }

    static boolean isLayout(File dir)
    {
        return dir != null && dir.isDirectory() && (new File(dir, CONFIG_YAML).isFile() || isHeaderOnlyLayout(dir));
    }

    private static boolean isHeaderOnlyLayout(File dir)
    {
        return new File(dir, HEADER_YAML).isFile() && (new File(dir, DEFAULT_FILES_DIR).isDirectory() || new File(dir, DEFAULT_ARM9).isFile());
    }

    static NintendoDsRom load(File dir)
    {
        Map<String, Object> config = readYamlMap(new File(dir, CONFIG_YAML));
        NintendoDsRom rom = new NintendoDsRom();
        rom.reserved1 = new byte[7];
        rom.capacityFromSize = true;

        Map<String, Object> header = readYamlMap(configured(dir, config, "header", HEADER_YAML));
        loadHeader(rom, header);
        loadLogo(rom, dir, config);
        loadArm9Section(rom, dir, config, header);
        loadArm7Section(rom, dir, config);
        loadBanner(rom, dir, config);
        loadMultiboot(rom, dir, config);
        loadFileSystem(rom, dir, config);

        return rom;
    }

    /** Writes {@code rom} to {@code dir} as {@code dsrom extract} would extract the ROM {@code rom} saves to. */
    static void save(NintendoDsRom rom, File dir) throws IOException
    {
        if (isNonEmptyDir(dir))
            throw new IOException("Unable to write ds-rom extract, target folder already exists");
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Failed to create ds-rom extract directory, check write perms.");

        DsRomExtract.write(rom.save(false), dir);
    }

    private static boolean isNonEmptyDir(File dir)
    {
        return dir.isDirectory() && Objects.requireNonNull(dir.listFiles()).length != 0;
    }

    private static File configured(File dir, Map<String, Object> config, String key, String fallback)
    {
        return new File(dir, SimpleYaml.getString(config, key, fallback));
    }

    private static File requireFile(File file, String what)
    {
        if (!file.isFile())
            throw new RuntimeException("ds-rom extract is missing " + what + ": " + file.getAbsolutePath());

        return file;
    }

    private static void loadHeader(NintendoDsRom rom, Map<String, Object> header)
    {
        rom.title = SimpleYaml.getString(header, "title", rom.title);
        rom.gameCode = truncate(SimpleYaml.getString(header, "gamecode", rom.gameCode), 4);
        rom.developerCode = truncate(SimpleYaml.getString(header, "makercode", rom.developerCode), 2);
        rom.unitCode = SimpleYaml.getInt(header, "unitcode", rom.unitCode);
        rom.encryptionSeed = SimpleYaml.getInt(header, "seed_select", rom.encryptionSeed);
        rom.systemRegion = SimpleYaml.getInt(header, "ds_flags", rom.systemRegion);
        rom.romVersion = SimpleYaml.getInt(header, "rom_version", rom.romVersion);
        rom.autoStartFlag = SimpleYaml.getInt(header, "autostart", rom.autoStartFlag);
        rom.normalCardControlRegisterSettings = SimpleYaml.getInt(header, "normal_cmd_setting", rom.normalCardControlRegisterSettings);
        rom.secureCardControlRegisterSettings = SimpleYaml.getInt(header, "key1_cmd_setting", rom.secureCardControlRegisterSettings);
        rom.secureTransferTimeout = (short) SimpleYaml.getInt(header, "secure_area_delay", rom.secureTransferTimeout);

        MemBuf nandEnds = MemBuf.create();
        nandEnds.writer().writeShort((short) SimpleYaml.getInt(header, "rom_nand_end", 0)).writeShort((short) SimpleYaml.getInt(header, "rw_nand_end", 0));
        patch(rom.padding_088h, 0x94 - 0x88, nandEnds);

        Object postDsi = header.get("ds_post_dsi");
        if (postDsi != null)
            loadPostDsiHeader(rom, SimpleYaml.asMap(postDsi));
    }

    private static String truncate(String value, int length)
    {
        return value.length() > length ? value.substring(0, length) : value;
    }

    private static void loadPostDsiHeader(NintendoDsRom rom, Map<String, Object> fields)
    {
        MemBuf flags = MemBuf.create();
        flags.writer().writeInt(SimpleYaml.getInt(fields, "dsi_flags_2", 0));
        patch(rom.padding_16Ch, 0x1BC - 0x16C, flags);

        patchList(rom.padding_200h, 0x33C - 0x200, fields.get("sha1_hmac_banner"));
        patchList(rom.padding_200h, 0x378 - 0x200, fields.get("sha1_hmac_unk1"));
        patchList(rom.padding_200h, 0x38C - 0x200, fields.get("sha1_hmac_unk2"));
        patchList(rom.padding_200h, 0xF80 - 0x200, fields.get("rsa_sha1"));
    }

    private static void loadLogo(NintendoDsRom rom, File dir, Map<String, Object> config)
    {
        Object logo = config.get("header_logo");
        if (logo != null)
            rom.nintendoLogo = NintendoLogo.compress(readLogo(new File(dir, SimpleYaml.asString(logo))));
    }

    private static void loadArm9Section(NintendoDsRom rom, File dir, Map<String, Object> config, Map<String, Object> header)
    {
        File arm9File = requireFile(configured(dir, config, "arm9_bin", DEFAULT_ARM9), "ARM9 binary");
        Map<String, Object> arm9cfg = readYamlMap(configured(dir, config, "arm9_config", DEFAULT_ARM9_YAML));
        rom.arm9LoadAddress = SimpleYaml.getInt(arm9cfg, "base_address", rom.arm9LoadAddress);
        rom.arm9EntryAddress = SimpleYaml.getInt(arm9cfg, "entry_function", rom.arm9EntryAddress);
        rom.arm9Autoload = SimpleYaml.getInt(arm9cfg, "autoload_callback", rom.arm9Autoload);
        rom.arm9 = loadArm9(dir, config, arm9cfg, Buffer.readFile(arm9File.getAbsolutePath()), rom.arm9LoadAddress);
        rom.arm9Length = rom.arm9.length;

        if (arm9cfg.containsKey("build_info"))
            loadArm9Footer(rom, arm9cfg, header);
    }

    private static void loadArm9Footer(NintendoDsRom rom, Map<String, Object> arm9cfg, Map<String, Object> header)
    {
        int buildInfo = SimpleYaml.getInt(arm9cfg, "build_info", 0);
        MemBuf footer = MemBuf.create();
        footer.writer().writeInt(DsRomExtract.NITROCODE).writeInt(buildInfo).writeInt(SimpleYaml.getInt(arm9cfg, "overlay_signatures", 0));
        rom.arm9PostData = unsignedBytes(footer.reader().getBuffer());

        if (SimpleYaml.getBoolean(header, "has_arm9_build_info_offset", false))
        {
            MemBuf offset = MemBuf.create();
            offset.writer().writeInt(SAVED_ARM9_OFFSET + buildInfo);
            patch(rom.padding_088h, 0, offset);
        }
    }

    private static int[] unsignedBytes(byte[] bytes)
    {
        int[] out = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++)
            out[i] = bytes[i] & 0xFF;

        return out;
    }

    private static void loadArm7Section(NintendoDsRom rom, File dir, Map<String, Object> config)
    {
        File arm7File = requireFile(configured(dir, config, "arm7_bin", DEFAULT_ARM7), "ARM7 binary");
        rom.arm7 = Buffer.readFile(arm7File.getAbsolutePath());
        rom.arm7Length = rom.arm7.length;

        Map<String, Object> arm7cfg = readYamlMap(configured(dir, config, "arm7_config", DEFAULT_ARM7_YAML));
        rom.arm7LoadAddress = SimpleYaml.getInt(arm7cfg, "base_address", rom.arm7LoadAddress);
        rom.arm7EntryAddress = SimpleYaml.getInt(arm7cfg, "entry_function", rom.arm7EntryAddress);
        rom.arm7Autoload = SimpleYaml.getInt(arm7cfg, "autoload_callback", rom.arm7Autoload);
    }

    /** Re-appends the autoloads and their info table to the ARM9 code and points the build info at them. */
    private static byte[] loadArm9(File dir, Map<String, Object> config, Map<String, Object> arm9cfg, byte[] code, int baseAddress)
    {
        if (!arm9cfg.containsKey("build_info") || !arm9cfg.containsKey("bss_start"))
            return code;

        MemBuf buf = MemBuf.create(code);
        MemBuf.MemBufWriter writer = buf.writer();
        List<Autoload> autoloads = collectAutoloads(dir, config);
        int autoloadBlocks = baseAddress + writer.getPosition();
        for (Autoload autoload : autoloads)
            writer.write(autoload.code);

        int infosStart = baseAddress + writer.getPosition();
        for (Autoload autoload : autoloads)
            writer.writeInt(autoload.baseAddress).writeInt(autoload.codeSize).writeInt(autoload.bssSize);

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

    /** ITCM, then DTCM, then the unknown autoloads by index: the order the info table lists them in. */
    private static List<Autoload> collectAutoloads(File dir, Map<String, Object> config)
    {
        List<Autoload> autoloads = new ArrayList<Autoload>();
        addAutoload(dir, config.get("itcm"), 0, autoloads);
        addAutoload(dir, config.get("dtcm"), 1, autoloads);
        Object unknown = config.get("unknown_autoloads");
        for (Object entry : unknown == null ? Collections.emptyList() : SimpleYaml.asList(unknown))
            addAutoload(dir, entry, 2 + SimpleYaml.getInt(SimpleYaml.asMap(entry), "index", 0), autoloads);

        Collections.sort(autoloads, Comparator.comparingInt(autoload -> autoload.order));

        return autoloads;
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
        requireSize(image, NintendoLogo.WIDTH, NintendoLogo.HEIGHT, file);
        boolean[][] pixels = new boolean[NintendoLogo.HEIGHT][NintendoLogo.WIDTH];
        for (int y = 0; y < NintendoLogo.HEIGHT; y++)
        {
            for (int x = 0; x < NintendoLogo.WIDTH; x++)
                pixels[y][x] = (image.getRGB(x, y) & 0xFFFFFF) == 0;
        }

        return pixels;
    }

    private static void requireSize(BufferedImage image, int width, int height, File file)
    {
        if (image.getWidth() != width || image.getHeight() != height)
            throw new RuntimeException(String.format("%s must be %dx%d pixels", file.getAbsolutePath(), width, height));
    }

    private static void loadBanner(NintendoDsRom rom, File dir, Map<String, Object> config)
    {
        File banner = configured(dir, config, "banner", "");
        if (banner.isFile())
            rom.iconBanner = readBanner(banner);
    }

    /** Builds the raw banner; an animated (DSi) banner comes back as version 3, as its animation is not extracted. */
    private static byte[] readBanner(File yaml)
    {
        Map<String, Object> banner = readYamlMap(yaml);
        Integer known = BANNER_VERSIONS.get(SimpleYaml.getString(banner, "version", "Original"));
        int version = known == null ? 3 : known;
        byte[] data = new byte[BANNER_SIZES.get(version)];
        data[0] = (byte) version;
        writeTitles(data, SimpleYaml.asMap(banner.get("title")));

        Map<String, Object> images = SimpleYaml.asMap(banner.get("images"));
        File dir = yaml.getParentFile();
        BufferedImage palette = readPng(new File(dir, SimpleYaml.getString(images, "palette_path", "palette.png")));
        BufferedImage bitmap = readPng(new File(dir, SimpleYaml.getString(images, "bitmap_path", "bitmap.png")));
        requireSize(palette, 16, 1, dir);
        requireSize(bitmap, 32, 32, dir);
        System.arraycopy(encodeBitmap(bitmap, palette), 0, data, 0x20, 0x200);
        patch(data, 0x220, encodePalette(palette));

        return new IconBanner(data).toBytes();
    }

    private static void writeTitles(byte[] data, Map<String, Object> titles)
    {
        for (int i = 0; i < BANNER_LANGUAGES.length; i++)
        {
            String title = SimpleYaml.getString(titles, BANNER_LANGUAGES[i], null);
            int offset = 0x240 + i * 0x100;
            if (title == null || offset + 0x100 > data.length)
                continue;
            byte[] utf16 = title.getBytes(StandardCharsets.UTF_16LE);
            System.arraycopy(utf16, 0, data, offset, Math.min(utf16.length, 0x100));
        }
    }

    private static MemBuf encodePalette(BufferedImage palette)
    {
        MemBuf out = MemBuf.create();
        for (int i = 0; i < 16; i++)
        {
            int rgb = palette.getRGB(i, 0);
            out.writer().writeShort((short) (((rgb >> 19) & 31) | (((rgb >> 11) & 31) << 5) | (((rgb >> 3) & 31) << 10)));
        }

        return out;
    }

    /** Packs the 32x32 bitmap into 8x8 tiles of 4-bit palette indices. */
    private static byte[] encodeBitmap(BufferedImage bitmap, BufferedImage palette)
    {
        byte[] out = new byte[0x200];
        for (int y = 0; y < 32; y++)
        {
            for (int x = 0; x < 32; x++)
                out[(y / 8 * 0x80) + (x / 8 * 0x20) + (y % 8 * 4) + (x / 2 % 4)] |= (byte) (paletteIndex(bitmap.getRGB(x, y), palette) << ((x % 2) * 4));
        }

        return out;
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

    private static void loadMultiboot(NintendoDsRom rom, File dir, Map<String, Object> config)
    {
        Object multiboot = config.get("multiboot_signature");
        if (multiboot != null)
            rom.rsaSignature = readMultibootSignature(readYamlMap(new File(dir, SimpleYaml.asString(multiboot))));
    }

    /** Rebuilds the 0x88-byte signature block that sits at the end of the ROM. */
    private static byte[] readMultibootSignature(Map<String, Object> yaml)
    {
        Object rsa = yaml.get("rsa_signature");
        if (!(rsa instanceof byte[]) || ((byte[]) rsa).length != 0x80)
            throw new RuntimeException("multiboot signature needs a 128-byte !!binary rsa_signature");

        MemBuf buf = MemBuf.create();
        buf.writer().writeInt(SimpleYaml.getInt(yaml, "magic", 0)).write((byte[]) rsa).writeInt(SimpleYaml.getInt(yaml, "key_seed", 0));

        return buf.reader().getBuffer();
    }

    private static void loadFileSystem(NintendoDsRom rom, File dir, Map<String, Object> config)
    {
        List<OverlayEntry> arm9Overlays = loadOverlays(dir, config.get("arm9_overlays"), DEFAULT_ARM9_OVERLAYS);
        List<OverlayEntry> arm7Overlays = loadOverlays(dir, config.get("arm7_overlays"), DEFAULT_ARM7_OVERLAYS);
        rom.y9 = buildOverlayTable(arm9Overlays);
        rom.y9Length = rom.y9.length;
        rom.y7 = buildOverlayTable(arm7Overlays);
        rom.y7Length = rom.y7.length;

        File filesDir = filesDir(dir, config);
        List<OverlayEntry> overlays = new ArrayList<OverlayEntry>(arm9Overlays);
        overlays.addAll(arm7Overlays);
        rom.files = emptyFileTable(maxFileId(overlays) + 1 + Fnt.calculateNumFiles(filesDir));
        for (OverlayEntry overlay : overlays)
            rom.files.set(overlay.fileId, readOverlay(overlay, rom.files.size()));

        rom.filenames = loadFolder(filesDir, rom.files);
        if (rom.files.contains(null))
            throw new RuntimeException("Internal file table not properly filled");

        rom.fnt = Fnt.save(rom.filenames).reader().getBuffer();
        rom.fntLength = rom.fnt.length;
        File pathOrder = configured(dir, config, "path_order", "path_order.txt");
        if (pathOrder.isFile())
            rom.sortedFileIDs = new FileOrder(rom.filenames).of(new String(Buffer.readFile(pathOrder.getAbsolutePath()), StandardCharsets.UTF_8).split("\n"));
    }

    private static File filesDir(File dir, Map<String, Object> config)
    {
        String rel = SimpleYaml.getString(config, "files_dir", DEFAULT_FILES_DIR);
        File filesDir = new File(dir, rel.replaceAll("[/\\\\]$", ""));
        if (!filesDir.isDirectory())
            throw new RuntimeException("ds-rom extract is missing files/ directory: " + filesDir.getAbsolutePath());

        return filesDir;
    }

    private static int maxFileId(List<OverlayEntry> overlays)
    {
        int max = -1;
        for (OverlayEntry overlay : overlays)
            max = Math.max(max, overlay.fileId);

        return max;
    }

    private static ArrayList<byte[]> emptyFileTable(int size)
    {
        return new ArrayList<byte[]>(Collections.<byte[]> nCopies(size, null));
    }

    private static byte[] readOverlay(OverlayEntry overlay, int fileCount)
    {
        if (overlay.fileId < 0 || overlay.fileId >= fileCount)
            throw new RuntimeException("overlay file_id " + overlay.fileId + " is outside the file table");

        return Buffer.readFile(requireFile(overlay.bin, "overlay binary").getAbsolutePath());
    }

    /** Reads {@code dir} into the next free file ids in the order {@code dsrom build} numbers files and folders. */
    private static Fnt.Folder loadFolder(File dir, ArrayList<byte[]> files)
    {
        Fnt.Folder folder = new Fnt.Folder(dir.getName());
        List<File> children = visibleChildren(dir);
        Collections.sort(children, FNT_ORDER);
        folder.firstId = files.indexOf(null) < 0 ? files.size() : files.indexOf(null);
        for (File child : children)
            addChild(folder, child, files);

        return folder;
    }

    private static List<File> visibleChildren(File dir)
    {
        List<File> children = new ArrayList<File>();
        for (File child : Objects.requireNonNull(dir.listFiles()))
        {
            if (!child.isHidden())
                children.add(child);
        }

        return children;
    }

    private static void addChild(Fnt.Folder folder, File child, ArrayList<byte[]> files)
    {
        if (child.isDirectory())
        {
            folder.folders.put(child.getName(), loadFolder(child, files));
            return;
        }

        int id = files.indexOf(null);
        if (id < 0)
            throw new RuntimeException("No available file IDs to allocate.");
        folder.files.add(child.getName());
        files.set(id, Buffer.readFile(child.getAbsolutePath()));
    }

    private static int compareFntEntries(File a, File b)
    {
        if (a.isDirectory() != b.isDirectory())
            return a.isDirectory() ? 1 : -1;

        return DsRomExtract.compareBytes(fntSortKey(a.getName()), fntSortKey(b.getName()));
    }

    /** Shift-JIS bytes lowercased byte by byte, the strcasecmp-like key ds-rom sorts FNT entries by. */
    private static byte[] fntSortKey(String name)
    {
        byte[] bytes = shiftJis(name);
        for (int i = 0; i < bytes.length; i++)
        {
            if (bytes[i] >= 'A' && bytes[i] <= 'Z')
                bytes[i] += 'a' - 'A';
        }

        return bytes;
    }

    private static byte[] shiftJis(String name)
    {
        try
        {
            return name.getBytes(Charset.forName("windows-31j"));
        }
        catch (IllegalArgumentException e)
        {
            return name.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static void patch(byte[] target, int offset, MemBuf bytes)
    {
        byte[] data = bytes.reader().getBuffer();
        System.arraycopy(data, 0, target, offset, data.length);
    }

    private static void patchList(byte[] target, int offset, Object list)
    {
        if (list == null)
            return;
        List<Object> values = SimpleYaml.asList(list);
        for (int i = 0; i < values.size(); i++)
            target[offset + i] = (byte) SimpleYaml.asInt(values.get(i));
    }

    private static Map<String, Object> readYamlMap(File file)
    {
        if (!file.isFile())
            return Collections.emptyMap();
        Object parsed = SimpleYaml.parse(new String(Buffer.readFile(file.getAbsolutePath()), StandardCharsets.UTF_8));

        return parsed == null ? Collections.<String, Object> emptyMap() : SimpleYaml.asMap(parsed);
    }

    private static BufferedImage readPng(File file)
    {
        try
        {
            return Objects.requireNonNull(ImageIO.read(file), "not a readable image: " + file.getAbsolutePath());
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    private static List<OverlayEntry> loadOverlays(File root, Object configured, String defaultRel)
    {
        File yaml = new File(root, configured == null ? defaultRel : String.valueOf(configured));
        Object overlays = readYamlMap(yaml).get("overlays");
        if (overlays == null)
            return Collections.emptyList();

        List<Object> entries = SimpleYaml.asList(overlays);
        List<OverlayEntry> out = new ArrayList<OverlayEntry>(entries.size());
        for (int i = 0; i < entries.size(); i++)
            out.add(new OverlayEntry(SimpleYaml.asMap(entries.get(i)), i, yaml.getParentFile()));

        Collections.sort(out, Comparator.comparingInt(overlay -> overlay.id));

        return out;
    }

    private static byte[] buildOverlayTable(List<OverlayEntry> overlays)
    {
        MemBuf buf = MemBuf.create();
        MemBuf.MemBufWriter writer = buf.writer();
        for (OverlayEntry e : overlays)
        {
            writer.writeInt(e.id).writeInt(e.baseAddress).writeInt(e.codeSize).writeInt(e.bssSize);
            writer.writeInt(e.ctorStart).writeInt(e.ctorEnd).writeInt(e.fileId);
            // Overlay bins are stored decompressed, so the compressed flag and size stay clear.
            writer.writeInt(e.signed ? 0x02000000 : 0);
        }

        return buf.reader().getBuffer();
    }

    /** The order {@code dsrom build} lays files out in: each path_order entry, a folder's unvisited files by name. */
    private static final class FileOrder
    {
        final Fnt.Folder root;
        final ArrayList<Integer> order = new ArrayList<Integer>();
        final Set<Integer> visitedFiles = new HashSet<Integer>();
        final Set<Fnt.Folder> visitedFolders = Collections.newSetFromMap(new IdentityHashMap<Fnt.Folder, Boolean>());

        FileOrder(Fnt.Folder root)
        {
            this.root = root;
        }

        ArrayList<Integer> of(String[] pathOrder)
        {
            for (String line : pathOrder)
                addPath(line.startsWith("/") ? line.substring(1) : line);

            return order;
        }

        private void addPath(String path)
        {
            if (path.trim().isEmpty())
            {
                addFolder(root);
                return;
            }

            String[] parts = path.split("/");
            Fnt.Folder parent = parentOf(parts);
            if (parent != null)
                addEntry(parent, parts[parts.length - 1]);
        }

        private Fnt.Folder parentOf(String[] parts)
        {
            Fnt.Folder parent = root;
            for (int i = 0; i < parts.length - 1 && parent != null; i++)
                parent = parent.getFolders().get(parts[i]);

            return parent;
        }

        private void addEntry(Fnt.Folder parent, String name)
        {
            Fnt.Folder sub = parent.getFolders().get(name);
            if (sub != null)
                addFolder(sub);
            else if (parent.getFiles().contains(name))
                addFile(parent.getFirstId() + parent.getFiles().indexOf(name));
        }

        private void addFolder(Fnt.Folder folder)
        {
            if (!visitedFolders.add(folder))
                return;
            List<String> names = new ArrayList<String>(folder.getFiles());
            names.addAll(folder.getFolders().keySet());
            Collections.sort(names, UTF8_ORDER);
            for (String name : names)
                addEntry(folder, name);
        }

        private void addFile(int id)
        {
            if (visitedFiles.add(id))
                order.add(id);
        }
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
        final int id;
        final int baseAddress;
        final int codeSize;
        final int bssSize;
        final int ctorStart;
        final int ctorEnd;
        final int fileId;
        final boolean signed;
        final File bin;

        OverlayEntry(Map<String, Object> m, int index, File dir)
        {
            id = SimpleYaml.getInt(m, "id", index);
            baseAddress = SimpleYaml.getInt(m, "base_address", 0);
            codeSize = SimpleYaml.getInt(m, "code_size", 0);
            bssSize = SimpleYaml.getInt(m, "bss_size", 0);
            ctorStart = SimpleYaml.getInt(m, "ctor_start", 0);
            ctorEnd = SimpleYaml.getInt(m, "ctor_end", 0);
            fileId = SimpleYaml.getInt(m, "file_id", id);
            signed = SimpleYaml.getBoolean(m, "signed", false);
            bin = new File(dir, SimpleYaml.getString(m, "file_name", String.format("ov%03d.bin", id)));
        }
    }
}
