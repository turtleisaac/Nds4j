/*
 * Copyright (c) 2023 Turtleisaac.
 *
 * This file is part of Nds4j.
 */

package io.github.turtleisaac.nds4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ds-rom unpacked folder load")
class DsRomUnpackedTest
{
    @Test
    void looksLikeUnpackedRomDetectsBothLayouts() throws Exception
    {
        Path tmp = Files.createTempDirectory("nds4j-layout");
        try
        {
            Path nds4j = tmp.resolve("nds4j");
            Files.createDirectories(nds4j);
            Files.write(nds4j.resolve("header.bin"), new byte[] {1});
            assertThat(NintendoDsRom.looksLikeUnpackedRom(nds4j.toFile())).isTrue();

            Path dsrom = tmp.resolve("dsrom");
            Files.createDirectories(dsrom);
            Files.write(dsrom.resolve("config.yaml"), "header: header.yaml\n".getBytes(StandardCharsets.UTF_8));
            assertThat(NintendoDsRom.looksLikeUnpackedRom(dsrom.toFile())).isTrue();

            Path empty = tmp.resolve("empty");
            Files.createDirectories(empty);
            assertThat(NintendoDsRom.looksLikeUnpackedRom(empty.toFile())).isFalse();
        }
        finally
        {
            deleteTree(tmp);
        }
    }

    @Test
    void fromUnpackedLoadsDsRomExtract() throws Exception
    {
        Path root = Files.createTempDirectory("dsrom-extract");
        try
        {
            writeMinimalDsRomExtract(root);
            NintendoDsRom rom = NintendoDsRom.fromUnpacked(root.toFile());
            assertThat(rom.getTitle()).isEqualTo("TEST GAME");
            assertThat(rom.getGameCode()).isEqualTo("TEST");
            assertThat(rom.getNumFiles()).isEqualTo(2); // overlay file_id 0 + one data file
            assertThat(rom.getFnt()).isNotEmpty();
            assertThat(rom.getFile(1)).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
            assertThat(new String(rom.getFileByName("hello.txt"), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
        finally
        {
            deleteTree(root);
        }
    }

    @Test
    void fromUnpackedRejectsIncompleteDsRomExtract() throws Exception
    {
        Path root = Files.createTempDirectory("dsrom-bad");
        try
        {
            Files.write(root.resolve("config.yaml"),
                    "header: header.yaml\narm9_bin: arm9/arm9.bin\n".getBytes(StandardCharsets.UTF_8));
            Files.write(root.resolve("header.yaml"), "title: X\ngamecode: ABCD\n".getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> NintendoDsRom.fromUnpacked(root.toFile()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("ARM9");
        }
        finally
        {
            deleteTree(root);
        }
    }

    @Test
    void fromUnpackedDsRomRoundTripFilesystemAgainstNds4jUnpack() throws Exception
    {
        NintendoDsRom packed = TestRoms.require("HeartGold.nds");
        Path tmp = Files.createTempDirectory("dsrom-from-nds4j");
        try
        {
            Path nds4j = tmp.resolve("nds4j");
            packed.unpack(nds4j.toFile());
            Path dsrom = tmp.resolve("dsrom");
            convertNds4jUnpackToDsRom(nds4j, dsrom, packed);

            NintendoDsRom loaded = NintendoDsRom.fromUnpacked(dsrom.toFile());
            assertThat(loaded.getGameCode()).isEqualTo(packed.getGameCode());
            assertThat(loaded.getTitle().trim()).isEqualTo(packed.getTitle().trim());
            assertThat(loaded.getNumFiles()).isEqualTo(packed.getNumFiles());
            assertThat(Fnt.load(loaded.getFnt()).getFolders()).isNotEmpty();
        }
        finally
        {
            deleteTree(tmp);
        }
    }

    static void writeMinimalDsRomExtract(Path root) throws IOException
    {
        Files.createDirectories(root.resolve("arm9"));
        Files.createDirectories(root.resolve("arm7"));
        Files.createDirectories(root.resolve("arm9_overlays"));
        Files.createDirectories(root.resolve("files"));
        Files.write(root.resolve("config.yaml"), (
                "header: header.yaml\n"
                        + "arm9_bin: arm9/arm9.bin\n"
                        + "arm7_bin: arm7/arm7.bin\n"
                        + "arm9_config: arm9/arm9.yaml\n"
                        + "arm7_config: arm7/arm7.yaml\n"
                        + "arm9_overlays: arm9_overlays/overlays.yaml\n"
                        + "files_dir: files/\n").getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("header.yaml"),
                "title: TEST GAME\ngamecode: TEST\nmakercode: \"01\"\n".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("arm9/arm9.bin"), new byte[256]);
        Files.write(root.resolve("arm7/arm7.bin"), new byte[128]);
        Files.write(root.resolve("arm9/arm9.yaml"),
                "base_address: 0x2000000\nentry_function: 0x2000800\n".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("arm7/arm7.yaml"),
                "base_address: 0x2380000\nentry_function: 0x2380000\n".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("arm9_overlays/overlays.yaml"), (
                "table_signed: false\n"
                        + "overlays:\n"
                        + "- id: 0\n"
                        + "  base_address: 0x02100000\n"
                        + "  code_size: 4\n"
                        + "  bss_size: 0\n"
                        + "  ctor_start: 0\n"
                        + "  ctor_end: 0\n"
                        + "  file_id: 0\n"
                        + "  compressed: false\n"
                        + "  file_name: ov000.bin\n").getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("arm9_overlays/ov000.bin"), new byte[] {1, 2, 3, 4});
        Files.write(root.resolve("files/hello.txt"), "hello".getBytes(StandardCharsets.UTF_8));
    }

    static void convertNds4jUnpackToDsRom(Path nds4j, Path dest, NintendoDsRom packed) throws IOException
    {
        Files.createDirectories(dest.resolve("arm9"));
        Files.createDirectories(dest.resolve("arm7"));
        Files.createDirectories(dest.resolve("arm9_overlays"));
        Files.createDirectories(dest);
        copyTree(nds4j.resolve("data"), dest.resolve("files"));
        Files.copy(nds4j.resolve("arm9.bin"), dest.resolve("arm9/arm9.bin"));
        Files.copy(nds4j.resolve("arm7.bin"), dest.resolve("arm7/arm7.bin"));
        Files.write(dest.resolve("header.yaml"),
                ("title: \"" + packed.getTitle().trim() + "\"\n"
                        + "gamecode: " + packed.getGameCode() + "\n").getBytes(StandardCharsets.UTF_8));
        Files.write(dest.resolve("arm9/arm9.yaml"), "base_address: 0x2000000\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dest.resolve("arm7/arm7.yaml"), "base_address: 0x2380000\n".getBytes(StandardCharsets.UTF_8));

        byte[] y9 = packed.getY9();
        StringBuilder ov = new StringBuilder("table_signed: false\noverlays:\n");
        int n = y9.length / 32;
        for (int i = 0; i < n; i++)
        {
            int id = leInt(y9, i * 32);
            int fileId = leInt(y9, i * 32 + 0x18);
            ov.append("- id: ").append(id).append('\n')
                    .append("  file_id: ").append(fileId).append('\n')
                    .append("  file_name: ov").append(String.format("%03d", id)).append(".bin\n")
                    .append("  base_address: 0\n")
                    .append("  code_size: 0\n")
                    .append("  bss_size: 0\n")
                    .append("  ctor_start: 0\n")
                    .append("  ctor_end: 0\n")
                    .append("  compressed: false\n");
            Path src = nds4j.resolve("overlay").resolve(overlayBinName(nds4j.resolve("overlay"), i, n));
            Files.copy(src, dest.resolve("arm9_overlays").resolve(String.format("ov%03d.bin", id)));
        }
        Files.write(dest.resolve("arm9_overlays/overlays.yaml"), ov.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(dest.resolve("config.yaml"), (
                "header: header.yaml\n"
                        + "arm9_bin: arm9/arm9.bin\n"
                        + "arm7_bin: arm7/arm7.bin\n"
                        + "arm9_config: arm9/arm9.yaml\n"
                        + "arm7_config: arm7/arm7.yaml\n"
                        + "arm9_overlays: arm9_overlays/overlays.yaml\n"
                        + "files_dir: files/\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String overlayBinName(Path overlayDir, int index, int count) throws IOException
    {
        // Nds4j unpack names overlay_<padded>.bin; list in the same numeric order fromUnpacked uses.
        final int idx = index;
        try (java.util.stream.Stream<Path> stream = Files.list(overlayDir))
        {
            return stream
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("overlay_") && n.endsWith(".bin"))
                    .sorted((a, b) -> Integer.compare(overlayIndex(a), overlayIndex(b)))
                    .skip(idx)
                    .findFirst()
                    .orElseThrow(() -> new IOException("no overlay bin for index " + index + " (count " + count + ")"));
        }
    }

    private static int overlayIndex(String name)
    {
        return Integer.parseInt(name.split("_")[1].replace(".bin", ""));
    }

    private static int leInt(byte[] b, int off)
    {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static void copyTree(Path src, Path dest) throws IOException
    {
        Files.walk(src).forEach(p -> {
            try
            {
                Path rel = dest.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(rel);
                else
                {
                    Files.createDirectories(rel.getParent());
                    Files.copy(p, rel);
                }
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        });
    }

    private static void deleteTree(Path root) throws IOException
    {
        if (root == null || !Files.exists(root)) return;
        Files.walk(root)
                .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); }
                    catch (IOException ignored) {}
                });
    }
}
