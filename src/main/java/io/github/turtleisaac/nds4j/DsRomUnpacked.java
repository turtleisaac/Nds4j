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
import io.github.turtleisaac.nds4j.framework.SimpleYaml;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Loader for an extract directory produced by
 * <a href="https://github.com/AetiasHax/ds-rom">ds-rom</a> ({@code dsrom extract}):
 * {@code config.yaml} + {@code header.yaml} + {@code arm9/arm9.bin} + {@code files/} + optional
 * {@code arm9_overlays/overlays.yaml}. Distinct from {@link NintendoDsRom#unpack}'s ndstool-style
 * {@code header.bin}/{@code data/}/{@code overlay/} layout.
 */
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

    private DsRomUnpacked() {}

    static boolean isLayout(File dir)
    {
        if (dir == null || !dir.isDirectory()) return false;
        if (new File(dir, CONFIG_YAML).isFile()) return true;
        if (!new File(dir, HEADER_YAML).isFile()) return false;
        return new File(dir, DEFAULT_FILES_DIR).isDirectory()
                || new File(dir, DEFAULT_ARM9).isFile();
    }

    static NintendoDsRom load(File dir)
    {
        Map<String, Object> config = readYamlMap(new File(dir, CONFIG_YAML));
        String headerRel = SimpleYaml.getString(config, "header", HEADER_YAML);
        String arm9Rel = SimpleYaml.getString(config, "arm9_bin", DEFAULT_ARM9);
        String arm7Rel = SimpleYaml.getString(config, "arm7_bin", DEFAULT_ARM7);
        String arm9CfgRel = SimpleYaml.getString(config, "arm9_config", DEFAULT_ARM9_YAML);
        String arm7CfgRel = SimpleYaml.getString(config, "arm7_config", DEFAULT_ARM7_YAML);
        String filesRel = SimpleYaml.getString(config, "files_dir", DEFAULT_FILES_DIR);
        if (filesRel.endsWith("/") || filesRel.endsWith("\\"))
            filesRel = filesRel.substring(0, filesRel.length() - 1);

        NintendoDsRom rom = new NintendoDsRom();
        rom.reserved1 = new byte[7];

        Map<String, Object> header = readYamlMap(new File(dir, headerRel));
        // ds-rom flattens HeaderOriginal into header.yaml
        rom.title = SimpleYaml.getString(header, "title", rom.title);
        String gamecode = SimpleYaml.getString(header, "gamecode", rom.gameCode);
        if (gamecode != null && !gamecode.isEmpty())
            rom.gameCode = gamecode.length() >= 4 ? gamecode.substring(0, 4) : gamecode;
        String maker = SimpleYaml.getString(header, "makercode", rom.developerCode);
        if (maker != null) rom.developerCode = maker.length() >= 2 ? maker.substring(0, 2) : maker;
        rom.unitCode = SimpleYaml.getInt(header, "unitcode", rom.unitCode);
        rom.encryptionSeed = SimpleYaml.getInt(header, "seed_select", rom.encryptionSeed);
        rom.romVersion = SimpleYaml.getInt(header, "rom_version", rom.romVersion);
        rom.autoStartFlag = SimpleYaml.getInt(header, "autostart", rom.autoStartFlag);
        rom.normalCardControlRegisterSettings = SimpleYaml.getInt(header, "normal_cmd_setting",
                rom.normalCardControlRegisterSettings);
        rom.secureCardControlRegisterSettings = SimpleYaml.getInt(header, "key1_cmd_setting",
                rom.secureCardControlRegisterSettings);

        File arm9File = new File(dir, arm9Rel);
        if (!arm9File.isFile())
            throw new RuntimeException("ds-rom extract is missing ARM9 binary: " + arm9File.getAbsolutePath());
        rom.arm9 = Buffer.readFile(arm9File.getAbsolutePath());
        rom.arm9Length = rom.arm9.length;

        File arm7File = new File(dir, arm7Rel);
        if (!arm7File.isFile())
            throw new RuntimeException("ds-rom extract is missing ARM7 binary: " + arm7File.getAbsolutePath());
        rom.arm7 = Buffer.readFile(arm7File.getAbsolutePath());
        rom.arm7Length = rom.arm7.length;

        Map<String, Object> arm9cfg = readYamlMap(new File(dir, arm9CfgRel));
        rom.arm9LoadAddress = SimpleYaml.getInt(arm9cfg, "base_address", rom.arm9LoadAddress);
        rom.arm9EntryAddress = SimpleYaml.getInt(arm9cfg, "entry_function", rom.arm9EntryAddress);
        rom.arm9Autoload = SimpleYaml.getInt(arm9cfg, "autoload_callback", rom.arm9Autoload);

        Map<String, Object> arm7cfg = readYamlMap(new File(dir, arm7CfgRel));
        rom.arm7LoadAddress = SimpleYaml.getInt(arm7cfg, "base_address", rom.arm7LoadAddress);
        rom.arm7EntryAddress = SimpleYaml.getInt(arm7cfg, "entry_function", rom.arm7EntryAddress);
        rom.arm7Autoload = SimpleYaml.getInt(arm7cfg, "autoload_callback", rom.arm7Autoload);

        List<OverlayEntry> arm9Overlays = loadOverlays(dir, config.get("arm9_overlays"), DEFAULT_ARM9_OVERLAYS);
        List<OverlayEntry> arm7Overlays = loadOverlays(dir, config.get("arm7_overlays"), DEFAULT_ARM7_OVERLAYS);

        rom.y9 = buildOverlayTable(arm9Overlays);
        rom.y9Length = rom.y9.length;
        rom.y7 = buildOverlayTable(arm7Overlays);
        rom.y7Length = rom.y7.length;

        File filesDir = new File(dir, filesRel);
        if (!filesDir.isDirectory())
            throw new RuntimeException("ds-rom extract is missing files/ directory: " + filesDir.getAbsolutePath());

        int maxOverlayFileId = -1;
        List<OverlayEntry> allOverlays = new ArrayList<OverlayEntry>();
        allOverlays.addAll(arm9Overlays);
        allOverlays.addAll(arm7Overlays);
        for (int i = 0; i < allOverlays.size(); i++)
            maxOverlayFileId = Math.max(maxOverlayFileId, allOverlays.get(i).fileId);

        int dataFiles = Fnt.calculateNumFiles(filesDir);
        int numFiles = Math.max(maxOverlayFileId + 1, 0) + dataFiles;
        if (numFiles < dataFiles) numFiles = dataFiles;
        rom.files = new ArrayList<byte[]>();
        for (int i = 0; i < numFiles; i++)
            rom.files.add(null);

        for (int i = 0; i < allOverlays.size(); i++)
        {
            OverlayEntry ov = allOverlays.get(i);
            if (ov.fileId < 0 || ov.fileId >= rom.files.size())
                throw new RuntimeException("overlay file_id " + ov.fileId + " is outside the file table");
            if (!ov.bin.isFile())
                throw new RuntimeException("ds-rom overlay binary missing: " + ov.bin.getAbsolutePath());
            rom.files.set(ov.fileId, Buffer.readFile(ov.bin.getAbsolutePath()));
        }

        rom.filenames = Fnt.loadFromDisk(filesDir, rom.files);
        if (rom.files.contains(null))
            throw new RuntimeException("Internal file table not properly filled");

        MemBuf fntBuf = Fnt.save(rom.filenames);
        rom.fnt = fntBuf.reader().getBuffer();
        rom.fntLength = rom.fnt.length;
        return rom;
    }

    private static Map<String, Object> readYamlMap(File file)
    {
        if (file == null || !file.isFile())
            return Collections.emptyMap();
        byte[] bytes = Buffer.readFile(file.getAbsolutePath());
        String text = new String(bytes, StandardCharsets.UTF_8);
        Object parsed = SimpleYaml.parse(text);
        if (parsed == null) return Collections.emptyMap();
        return SimpleYaml.asMap(parsed);
    }

    private static List<OverlayEntry> loadOverlays(File root, Object configured, String defaultRel)
    {
        String rel = null;
        if (configured instanceof String)
            rel = (String) configured;
        else if (configured == null)
        {
            File fallback = new File(root, defaultRel);
            if (fallback.isFile()) rel = defaultRel;
        }
        if (rel == null || rel.isEmpty()) return Collections.emptyList();

        File yaml = new File(root, rel);
        if (!yaml.isFile()) return Collections.emptyList();
        File overlayDir = yaml.getParentFile() == null ? root : yaml.getParentFile();
        Map<String, Object> table = readYamlMap(yaml);
        Object overlaysObj = table.get("overlays");
        if (overlaysObj == null) return Collections.emptyList();
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
            e.compressed = SimpleYaml.getBoolean(m, "compressed", false);
            String name = SimpleYaml.getString(m, "file_name", String.format("ov%03d.bin", e.id));
            e.bin = new File(overlayDir, name);
            out.add(e);
        }
        Collections.sort(out, new Comparator<OverlayEntry>()
        {
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
        if (overlays == null || overlays.isEmpty()) return new byte[0];
        MemBuf buf = MemBuf.create();
        MemBuf.MemBufWriter w = buf.writer();
        for (int i = 0; i < overlays.size(); i++)
        {
            OverlayEntry e = overlays.get(i);
            w.writeInt(e.id);
            w.writeInt(e.baseAddress);
            w.writeInt(e.codeSize);
            w.writeInt(e.bssSize);
            w.writeInt(e.ctorStart);
            w.writeInt(e.ctorEnd);
            w.writeInt(e.fileId);
            // gbatek: bits 0-23 compressed size, bit 24 compressed. Extracted overlay bins are
            // decompressed; mark uncompressed so a later save writes the bytes we loaded.
            w.writeInt(0);
        }
        return buf.reader().getBuffer();
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
        boolean compressed;
        File bin;
    }
}
