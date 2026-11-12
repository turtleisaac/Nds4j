package io.github.turtleisaac.nds4j;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The ds-rom writer and reader against synthetic ROMs, so they are covered without a retail ROM. */
class DsRomSyntheticTest
{
    private static final String CONFIG_YAML = "header: header.yaml\n"
            + "header_logo: header_logo.png\n"
            + "arm9_bin: arm9/arm9.bin\n"
            + "arm9_config: arm9/arm9.yaml\n"
            + "arm7_bin: arm7/arm7.bin\n"
            + "arm7_config: arm7/arm7.yaml\n"
            + "itcm:\n"
            + "  bin: arm9/itcm.bin\n"
            + "  config: arm9/itcm.yaml\n"
            + "dtcm:\n"
            + "  bin: arm9/dtcm.bin\n"
            + "  config: arm9/dtcm.yaml\n"
            + "unknown_autoloads:\n"
            + "  - index: 2\n"
            + "    bin: arm9/unk_autoload_2.bin\n"
            + "    config: arm9/unk_autoload_2.yaml\n"
            + "arm9_overlays: arm9_overlays/overlays.yaml\n"
            + "arm7_overlays: arm7_overlays/overlays.yaml\n"
            + "banner: banner/banner.yaml\n"
            + "files_dir: files/\n"
            + "path_order: path_order.txt\n"
            + "arm9_hmac_sha1_key: arm9/hmac_sha1_key.bin\n"
            + "multiboot_signature: multiboot_signature.yaml\n"
            + "alignment:\n"
            + "  arm9: 512\n"
            + "  arm9_overlay_table: 512\n"
            + "  arm9_overlay: 512\n"
            + "  arm7: 512\n"
            + "  arm7_overlay_table: 512\n"
            + "  arm7_overlay: 512\n"
            + "  file_name_table: 512\n"
            + "  file_allocation_table: 512\n"
            + "  banner: 512\n"
            + "  file_image_block: 512\n"
            + "  file: 512\n"
            + "padding:\n"
            + "  arm9: 0\n"
            + "  arm9_overlay_table: 255\n"
            + "  arm9_overlays: 255\n"
            + "  arm7: 255\n"
            + "  arm7_overlay_table: 105\n"
            + "  arm7_overlays: 255\n"
            + "  fnt: 255\n"
            + "  fat: 255\n"
            + "  banner: 255\n"
            + "  file_image: 255\n"
            + "  rom: 18\n";

    private static final String ARM9_OVERLAYS_YAML = "table_signed: true\n"
            + "table_signature: a0a7aeb5bcc3cad1d8dfe6edf4fb020910171e25\n"
            + "overlays:\n"
            + "  - id: 0\n"
            + "    base_address: 34603008\n"
            + "    code_size: 256\n"
            + "    bss_size: 32\n"
            + "    ctor_start: 34603024\n"
            + "    ctor_end: 34603028\n"
            + "    file_id: 0\n"
            + "    compressed: false\n"
            + "    signed: false\n"
            + "    file_name: ov000.bin\n"
            + "  - id: 1\n"
            + "    base_address: 34607104\n"
            + "    code_size: 128\n"
            + "    bss_size: 32\n"
            + "    ctor_start: 34607120\n"
            + "    ctor_end: 34607124\n"
            + "    file_id: 1\n"
            + "    compressed: false\n"
            + "    signed: true\n"
            + "    file_name: ov001.bin\n";

    @TempDir
    Path temp;

    @Test
    void nintendoDsRomReparsesEverySyntheticRomByteForByte()
    {
        for (SyntheticRom variant : variants())
        {
            byte[] rom = variant.build();
            assertThat(new NintendoDsRom(rom).save(false))
                    .isEqualTo(rom);
        }
    }

    @Test
    void loadingTheExtractRebuildsTheSameRom() throws IOException
    {
        for (SyntheticRom variant : variants())
        {
            if (variant.compressed)
                continue;
            byte[] rom = variant.build();
            Path dir = extract(rom);
            assertThat(NintendoDsRom.fromUnpacked(dir.toFile()).save(false))
                    .isEqualTo(rom);
        }
    }

    @Test
    void unpackDsRomWritesTheSameFolderAsTheExtract() throws IOException
    {
        byte[] rom = SyntheticRom.plain().build();
        Path unpacked = temp.resolve("unpacked");
        new NintendoDsRom(rom).unpackDsRom(unpacked.toFile());

        Path extracted = extract(rom);
        assertThat(read(unpacked, "config.yaml"))
                .isEqualTo(read(extracted, "config.yaml"));
        assertThat(Files.readAllBytes(unpacked.resolve("files/data/sub/z.bin")))
                .isEqualTo(Files.readAllBytes(extracted.resolve("files/data/sub/z.bin")));
        assertThatThrownBy(() -> new NintendoDsRom(rom).unpackDsRom(unpacked.toFile()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void anNdstoolUnpackLoadsBackWithTheSameFiles() throws IOException
    {
        NintendoDsRom original = new NintendoDsRom(SyntheticRom.plain().build());
        Path dir = temp.resolve("ndstool");
        original.unpack(dir.toFile());
        NintendoDsRom loaded = NintendoDsRom.fromUnpacked(dir.toFile());

        // The ndstool layout keeps only ARM9 overlays and numbers files case-sensitively, so ids can shift.
        assertThat(loaded.getTitle())
                .isEqualTo("SYNTHETIC");
        assertThat(loaded.getFile(0))
                .isEqualTo(SyntheticRom.arm9Overlay());
        assertThat(loaded.getFileByName("data/sub/z.bin"))
                .isEqualTo(original.getFileByName("data/sub/z.bin"));
        assertThat(loaded.getFileByName("B.bin"))
                .isEqualTo(original.getFileByName("B.bin"));
    }

    @Test
    void fromUnpackedRejectsAMissingFolder()
    {
        assertThatThrownBy(() -> NintendoDsRom.fromUnpacked(temp.resolve("missing").toFile()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void compressedCodeLoadsBackAsTheUncompressedRom() throws IOException
    {
        SyntheticRom compressed = SyntheticRom.plain();
        compressed.compressed = true;
        Path dir = extract(compressed.build());

        assertThat(Files.readAllBytes(dir.resolve("arm9/arm9.bin")))
                .isEqualTo(Arrays.copyOf(SyntheticRom.arm9Code(), SyntheticRom.MAIN_CODE_END));
        assertThat(Files.readAllBytes(dir.resolve("arm9_overlays/ov000.bin")))
                .isEqualTo(SyntheticRom.arm9Overlay());
        assertThat(read(dir, "arm9/arm9.yaml"))
                .contains("compressed: true\n");
        assertThat(read(dir, "arm9_overlays/overlays.yaml"))
                .contains("compressed: true\n");
        assertThat(NintendoDsRom.fromUnpacked(dir.toFile()).save(false))
                .isEqualTo(SyntheticRom.plain().build());
    }

    @Test
    void extractWritesTheDsRomLayout() throws IOException
    {
        Path dir = extract(SyntheticRom.plain().build());

        assertThat(read(dir, "config.yaml"))
                .isEqualTo(CONFIG_YAML);
        assertThat(read(dir, "arm9_overlays/overlays.yaml"))
                .isEqualTo(ARM9_OVERLAYS_YAML);
        assertThat(read(dir, "path_order.txt"))
                .isEqualTo("/data/x.bin\n/data/y.bin\n/data/sub\n/B.bin\n/a.bin\n/c.bin\n/extra/e2.bin\n/extra/e1.bin\n");
        assertThat(read(dir, "arm9/unk_autoload_2.yaml"))
                .isEqualTo("base_address: 37748736\ncode_size: 16\nbss_size: 0\nkind:\n  Unknown: 2\n");
        assertThat(read(dir, "arm9/itcm.yaml"))
                .endsWith("kind: Itcm\n");
        assertThat(read(dir, "arm9/dtcm.yaml"))
                .endsWith("kind: Dtcm\n");
        assertThat(read(dir, "arm7_overlays/overlays.yaml"))
                .startsWith("table_signed: false\noverlays:\n");
        assertThat(read(dir, "multiboot_signature.yaml"))
                .startsWith("magic: 90977\nrsa_signature: !!binary ")
                .endsWith("key_seed: 305419896\n");
        assertThat(read(dir, "header.yaml"))
                .contains("rom_nand_end: 4660\nrw_nand_end: 22136\nhas_arm9_build_info_offset: false\n")
                .doesNotContain("ds_post_dsi");
        assertThat(Files.readAllBytes(dir.resolve("arm9/hmac_sha1_key.bin")))
                .hasSize(64)
                .startsWith((byte) 0x21, (byte) 0x06, (byte) 0xC0, (byte) 0xDE);
        assertThat(dir.resolve("files/data/sub/z.bin"))
                .hasContent("z.bin contents ");
    }

    @Test
    void dsiHeaderFieldsAndACollapsedPathOrderAreExtracted() throws IOException
    {
        Path dir = extract(variants()[2].build());

        assertThat(read(dir, "header.yaml"))
                .contains("unitcode: 2\n")
                .contains("has_arm9_build_info_offset: true\n")
                .contains("ds_post_dsi:\n  dsi_flags_2: 19\n  sha1_hmac_banner:\n    - 1\n    - 8\n");
        assertThat(read(dir, "path_order.txt"))
                .isEqualTo("/\n");
        assertThat(read(dir, "config.yaml"))
                .contains("multiboot_signature: null\n")
                .doesNotContain("multiboot_signature.yaml");
    }

    @Test
    void everyBannerVersionIsNamedAndKeepsItsTitles() throws IOException
    {
        int[] versions = { 1, 2, 3, 0x103 };
        String[] names = { "Original", "China", "Korea", "Animated" };
        String[] lastTitle = { "spanish", "chinese", "korean", "korean" };
        for (int i = 0; i < versions.length; i++)
        {
            SyntheticRom rom = SyntheticRom.plain();
            rom.bannerVersion = versions[i];
            String banner = read(extract(rom.build()), "banner/banner.yaml");
            assertThat(banner)
                    .startsWith("version: " + names[i] + "\n")
                    .contains("  " + lastTitle[i] + ": \"Synthetic ");
        }
    }

    @Test
    void anUnknownBannerVersionIsRejected()
    {
        byte[] rom = SyntheticRom.plain().build();
        int banner = u32(rom, 0x68);
        rom[banner] = 4;

        assertThatThrownBy(() -> extract(rom))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("banner version 0x4");
    }

    @Test
    void anArm9WithoutItsFooterIsRejected()
    {
        byte[] rom = SyntheticRom.plain().build();
        rom[u32(rom, 0x20) + u32(rom, 0x2C)] = 0;

        assertThatThrownBy(() -> extract(rom))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("nitrocode");
    }

    @Test
    void anEncryptedSecureAreaIsRejected()
    {
        byte[] rom = SyntheticRom.plain().build();
        Arrays.fill(rom, 0x4000, 0x4800, (byte) 0x5A);

        assertThatThrownBy(() -> extract(rom))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("encrypted");
    }

    @Test
    void aSecureAreaWithoutItsIdIsStillPlainWhenMostlyZero() throws IOException
    {
        byte[] rom = SyntheticRom.plain().build();
        Arrays.fill(rom, 0x4000, 0x4008, (byte) 0);

        assertThat(Files.readAllBytes(extract(rom).resolve("arm9/arm9.bin")))
                .startsWith(new byte[8]);
    }

    @Test
    void aSignedArm7OverlayIsRejected()
    {
        SyntheticRom rom = SyntheticRom.plain();
        rom.signedArm7Overlay = true;

        assertThatThrownBy(() -> extract(rom.build()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ARM7 overlay 0 is signed");
    }

    @Test
    void aRomSmallerThanItsHeaderIsRejected()
    {
        assertThatThrownBy(() -> extract(new byte[0x200]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("header");
    }

    private Path extract(byte[] rom) throws IOException
    {
        Path dir = Files.createTempDirectory(temp, "extract");
        DsRomExtract.write(rom, dir.toFile());

        return dir;
    }

    private static String read(Path dir, String name) throws IOException
    {
        return new String(Files.readAllBytes(dir.resolve(name)), StandardCharsets.UTF_8);
    }

    private static int u32(byte[] rom, int offset)
    {
        return (rom[offset] & 0xFF) | (rom[offset + 1] & 0xFF) << 8 | (rom[offset + 2] & 0xFF) << 16 | (rom[offset + 3] & 0xFF) << 24;
    }

    private static SyntheticRom[] variants()
    {
        SyntheticRom compressed = SyntheticRom.plain();
        compressed.compressed = true;
        SyntheticRom dsi = SyntheticRom.plain();
        dsi.dsi = true;
        dsi.buildInfoInHeader = true;
        dsi.simpleTree = true;
        dsi.multiboot = false;
        dsi.bannerVersion = 2;
        SyntheticRom korean = SyntheticRom.plain();
        korean.bannerVersion = 3;

        return new SyntheticRom[] { SyntheticRom.plain(), compressed, dsi, korean };
    }
}
