package io.github.turtleisaac.nds4j;

import io.github.turtleisaac.nds4j.framework.MemBuf;
import io.github.turtleisaac.nds4j.framework.NintendoLogo;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Builds small ROM images, laid out the way {@link NintendoDsRom#save} lays them out, without a retail ROM. */
final class SyntheticRom
{
    static final int ARM9_BASE = 0x2000000;
    static final int BUILD_INFO = 0xA00;
    static final int HMAC_KEY = 0xB00;
    static final int OVERLAY_SIGNATURES = 0xC00;
    static final int COMPRESSED_FROM = 0x3C80;
    static final int MAIN_CODE_END = 0x4000;
    static final int ITCM_SIZE = 0x20;
    static final int DTCM_SIZE = 0x10;
    static final int UNKNOWN_SIZE = 0x10;
    static final int INFOS_START = MAIN_CODE_END + ITCM_SIZE + DTCM_SIZE + UNKNOWN_SIZE;
    static final int INFOS_END = INFOS_START + 3 * 12;
    static final int ARM9_OVERLAY_SIZE = 0x100;
    static final int MULTIBOOT_MAGIC = 0x00016361;
    static final int ARM9I_SIZE = 0x100;
    static final int ARM7I_SIZE = 0x80;
    static final int ARM9I_LOAD = 0x2400000;
    static final int ARM7I_LOAD = 0x2E80000;

    private static final byte[] SECURE_AREA_ID = { (byte) 0xFF, (byte) 0xDE, (byte) 0xFF, (byte) 0xE7, (byte) 0xFF, (byte) 0xDE, (byte) 0xFF, (byte) 0xE7 };
    private static final int ARM9_OVERLAY_FILES = 2;
    private static final int OVERLAY_FILES = 3;

    boolean compressed;
    boolean dsi;
    boolean buildInfoInHeader;
    boolean simpleTree;
    boolean signedArm7Overlay;
    boolean multiboot = true;
    boolean twlRegion;
    int bannerVersion = 1;

    static SyntheticRom plain()
    {
        return new SyntheticRom();
    }

    byte[] build()
    {
        NintendoDsRom rom = new NintendoDsRom();
        rom.title = "SYNTHETIC";
        rom.gameCode = "SYNT";
        rom.developerCode = "01";
        rom.unitCode = dsi ? 2 : 0;
        rom.romVersion = 1;
        rom.reserved1 = new byte[7];
        rom.arm9Autoload = ARM9_BASE + 0xA80;
        rom.arm7Autoload = 0x2380100;
        rom.nintendoLogo = NintendoLogo.compress(logoPixels());
        rom.padding_088h = header088();
        rom.padding_16Ch = header16C();
        rom.padding_200h = header200();

        byte[] code = arm9Code();
        rom.arm9 = compressed ? blz(markCompressed(code), COMPRESSED_FROM) : code;
        rom.arm9PostData = unsignedBytes(arm9Footer());
        rom.arm7 = pattern(0x200, 0x70);
        rom.y9 = overlayTable(true);
        rom.y7 = overlayTable(false);
        rom.iconBanner = new IconBanner(banner(bannerVersion)).toBytes();
        rom.rsaSignature = multiboot ? multibootSignature() : new byte[0];

        rom.files = new ArrayList<byte[]>();
        rom.files.add(compressed ? blz(arm9Overlay(), 0) : arm9Overlay());
        rom.files.add(pattern(0x80, 0x31));
        rom.files.add(pattern(0x40, 0x52));
        rom.filenames = simpleTree ? simpleTree(rom.files) : nestedTree(rom.files);
        rom.sortedFileIDs = simpleTree ? ids(0, 1, 2, 3, 4, 5) : ids(0, 1, 2, 6, 7, 8, 4, 3, 5, 10, 9);
        if (twlRegion)
            addTwlRegion(rom);

        return rom.save(true);
    }

    /** Puts an ARM9i and an ARM7i in a TWL data region past the NTR content, as a DSi-enhanced ROM has. */
    private static void addTwlRegion(NintendoDsRom rom)
    {
        int start = (rom.save(true).length + 0x3FF) & ~0x3FF;
        rom.dsi = true;
        rom.dsiRegionOffset = start;
        rom.dsiRegion = new byte[ARM9I_SIZE + ARM7I_SIZE];
        System.arraycopy(arm9i(), 0, rom.dsiRegion, 0, ARM9I_SIZE);
        System.arraycopy(arm7i(), 0, rom.dsiRegion, ARM9I_SIZE, ARM7I_SIZE);

        MemBuf buf = MemBuf.create(rom.padding_16Ch);
        MemBuf.MemBufWriter writer = buf.writer();
        writer.setPosition(0x1C0 - 0x16C);
        writer.writeInt(start).writeInt(ARM9I_LOAD + 0x800).writeInt(ARM9I_LOAD).writeInt(ARM9I_SIZE);
        writer.writeInt(start + ARM9I_SIZE).writeInt(ARM7I_LOAD + 0x400).writeInt(ARM7I_LOAD).writeInt(ARM7I_SIZE);
        writer.setPosition(rom.padding_16Ch.length);
        rom.padding_16Ch = buf.reader().getBuffer();
    }

    static byte[] arm9i()
    {
        return pattern(ARM9I_SIZE, 0xC3);
    }

    static byte[] arm7i()
    {
        return pattern(ARM7I_SIZE, 0xD5);
    }

    /** The decompressed ARM9: secure area, build info, HMAC key, signatures, then three autoloads and their infos. */
    static byte[] arm9Code()
    {
        MemBuf buf = MemBuf.create();
        MemBuf.MemBufWriter writer = buf.writer();
        writer.write(SECURE_AREA_ID).write(pattern(0x100, 0x10));
        padTo(writer, BUILD_INFO);
        writer.writeInt(ARM9_BASE + INFOS_START).writeInt(ARM9_BASE + INFOS_END).writeInt(ARM9_BASE + MAIN_CODE_END);
        writer.writeInt(ARM9_BASE + MAIN_CODE_END).writeInt(ARM9_BASE + MAIN_CODE_END + 0x100).writeInt(0).writeInt(0x02017531);
        padTo(writer, BUILD_INFO + 0x20);
        writer.writeInt(DsRomExtract.NITROCODE);
        padTo(writer, HMAC_KEY);
        writer.writeInt(DsRomExtract.NITROCODE).write(pattern(60, 0x90));
        padTo(writer, OVERLAY_SIGNATURES - 20);
        writer.write(pattern(20 + 20 * ARM9_OVERLAY_FILES, 0xA0));
        padTo(writer, MAIN_CODE_END);
        writer.write(pattern(ITCM_SIZE, 0x01)).write(pattern(DTCM_SIZE, 0x41)).write(pattern(UNKNOWN_SIZE, 0x61));
        writer.writeInt(0x1FF8000).writeInt(ITCM_SIZE).writeInt(0x40);
        writer.writeInt(0x27E0000).writeInt(DTCM_SIZE).writeInt(0x20);
        writer.writeInt(0x2400000).writeInt(UNKNOWN_SIZE).writeInt(0);

        return buf.reader().getBuffer();
    }

    /** Sets the build info's compressed_code_end, which is how ds-rom tells the ARM9 is compressed. */
    private static byte[] markCompressed(byte[] code)
    {
        MemBuf buf = MemBuf.create(code);
        buf.writer().setPosition(BUILD_INFO + 0x14);
        buf.writer().writeInt(ARM9_BASE + COMPRESSED_FROM);
        buf.writer().setPosition(code.length);

        return buf.reader().getBuffer();
    }

    static byte[] arm9Overlay()
    {
        byte[] out = new byte[ARM9_OVERLAY_SIZE];
        System.arraycopy(pattern(0x20, 0x21), 0, out, 0, 0x20);

        return out;
    }

    static byte[] pattern(int length, int seed)
    {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++)
            out[i] = (byte) (seed + i * 7);

        return out;
    }

    /** Compresses {@code plain} from {@code passthrough} on in the backwards LZ format ARM9 code uses. */
    static byte[] blz(byte[] plain, int passthrough)
    {
        int n = plain.length - passthrough;
        byte[] reversed = new byte[n];
        for (int k = 0; k < n; k++)
            reversed[k] = plain[plain.length - 1 - k];

        byte[] stream = reverse(encodeTokens(reversed));
        int compressedLength = stream.length + 8;
        if (compressedLength >= n)
            throw new IllegalArgumentException("synthetic data does not compress");

        MemBuf buf = MemBuf.create();
        buf.writer().write(Arrays.copyOf(plain, passthrough)).write(stream);
        buf.writer().writeInt((8 << 24) | compressedLength).writeInt(n - compressedLength);

        return buf.reader().getBuffer();
    }

    private static byte[] encodeTokens(byte[] reversed)
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int k = 0;
        while (k < reversed.length)
        {
            ByteArrayOutputStream group = new ByteArrayOutputStream();
            int flags = 0;
            for (int bit = 0x80; bit != 0 && k < reversed.length; bit >>= 1)
            {
                int length = runLength(reversed, k);
                if (length >= 3)
                {
                    flags |= bit;
                    group.write((length - 3) << 4);
                    group.write(0);
                    k += length;
                }
                else
                {
                    group.write(reversed[k++]);
                }
            }

            out.write(flags);
            byte[] tokens = group.toByteArray();
            out.write(tokens, 0, tokens.length);
        }

        return out.toByteArray();
    }

    /** How many bytes from {@code k} repeat the bytes three back, up to the 18 one reference can copy. */
    private static int runLength(byte[] data, int k)
    {
        if (k < 3)
            return 0;
        int length = 0;
        while (length < 18 && k + length < data.length && data[k + length] == data[k + length - 3])
            length++;

        return length;
    }

    private static byte[] reverse(byte[] data)
    {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++)
            out[i] = data[data.length - 1 - i];

        return out;
    }

    private byte[] arm9Footer()
    {
        MemBuf buf = MemBuf.create();
        buf.writer().writeInt(DsRomExtract.NITROCODE).writeInt(BUILD_INFO).writeInt(OVERLAY_SIGNATURES);

        return buf.reader().getBuffer();
    }

    private byte[] overlayTable(boolean arm9)
    {
        MemBuf buf = MemBuf.create();
        if (arm9)
        {
            int flags = compressed ? 0x01000000 | blz(arm9Overlay(), 0).length : 0;
            overlayRow(buf.writer(), 0, ARM9_OVERLAY_SIZE, 0, flags);
            overlayRow(buf.writer(), 1, 0x80, 1, 0x02000000);
        }
        else
        {
            overlayRow(buf.writer(), 0, 0x40, 2, signedArm7Overlay ? 0x02000000 : 0);
        }

        return buf.reader().getBuffer();
    }

    private static void overlayRow(MemBuf.MemBufWriter writer, int id, int codeSize, int fileId, int flags)
    {
        int base = 0x2100000 + id * 0x1000;
        writer.writeInt(id).writeInt(base).writeInt(codeSize).writeInt(0x20);
        writer.writeInt(base + 0x10).writeInt(base + 0x14).writeInt(fileId).writeInt(flags);
    }

    /** A banner with distinct palette colours, so pixels map back to one index, and titles that need YAML quoting. */
    static byte[] banner(int version)
    {
        MemBuf buf = MemBuf.create();
        MemBuf.MemBufWriter writer = buf.writer();
        writer.writeShort((short) version);
        padTo(writer, 0x20);
        for (int i = 0; i < 0x200; i++)
            writer.writeByte((byte) ((i % 16) | ((15 - i % 16) << 4)));

        for (int i = 0; i < 16; i++)
            writer.writeShort((short) (i | ((31 - i) << 5) | ((i * 2) << 10)));

        for (int i = 0; i < 8; i++)
        {
            byte[] title = ("Synthetic " + i + "\nTest: ROM").getBytes(StandardCharsets.UTF_16LE);
            writer.write(Arrays.copyOf(title, 0x100));
        }

        padTo(writer, bannerLength(version));

        return Arrays.copyOf(buf.reader().getBuffer(), bannerLength(version));
    }

    private static int bannerLength(int version)
    {
        switch (version)
        {
            case 0x0002:
                return 0x940;
            case 0x0003:
                return 0x1240;
            case 0x0103:
                return 0x23C0;
            default:
                return 0x840;
        }
    }

    static byte[] multibootSignature()
    {
        MemBuf buf = MemBuf.create();
        buf.writer().writeInt(MULTIBOOT_MAGIC).write(pattern(0x80, 0xE0)).writeInt(0x12345678);

        return buf.reader().getBuffer();
    }

    private static boolean[][] logoPixels()
    {
        boolean[][] pixels = new boolean[NintendoLogo.HEIGHT][NintendoLogo.WIDTH];
        for (int x = 4; x < 40; x++)
        {
            pixels[3][x] = true;
            pixels[12][x] = true;
        }

        return pixels;
    }

    private byte[] header088()
    {
        MemBuf buf = MemBuf.create();
        buf.writer().writeInt(buildInfoInHeader ? 0x4000 + BUILD_INFO : 0);
        padTo(buf.writer(), 0x94 - 0x88);
        buf.writer().writeShort((short) 0x1234).writeShort((short) 0x5678);
        padTo(buf.writer(), 0x38);

        return buf.reader().getBuffer();
    }

    private byte[] header16C()
    {
        byte[] out = new byte[0x94];
        if (dsi)
            out[0x1BC - 0x16C] = 0x13;

        return out;
    }

    private byte[] header200()
    {
        byte[] out = new byte[0x3E00];
        if (!dsi)
            return out;
        System.arraycopy(pattern(0x14, 0x01), 0, out, 0x33C - 0x200, 0x14);
        System.arraycopy(pattern(0x14, 0x02), 0, out, 0x378 - 0x200, 0x14);
        System.arraycopy(pattern(0x14, 0x03), 0, out, 0x38C - 0x200, 0x14);
        System.arraycopy(pattern(0x80, 0x04), 0, out, 0xF80 - 0x200, 0x80);

        return out;
    }

    /** Root a.bin, B.bin, c.bin; data/x.bin, data/y.bin, data/sub/z.bin; extra/e1.bin, extra/e2.bin. */
    private static Fnt.Folder nestedTree(List<byte[]> files)
    {
        Fnt.Folder root = folder("", files, "a.bin", "B.bin", "c.bin");
        Fnt.Folder data = folder("data", files, "x.bin", "y.bin");
        root.folders.put("data", data);
        data.folders.put("sub", folder("sub", files, "z.bin"));
        root.folders.put("extra", folder("extra", files, "e1.bin", "e2.bin"));

        return root;
    }

    /** Root a.bin, b.bin; d/c.bin. Laid out in name order, so ds-rom collapses the whole path order to "/". */
    private static Fnt.Folder simpleTree(List<byte[]> files)
    {
        Fnt.Folder root = folder("", files, "a.bin", "b.bin");
        root.folders.put("d", folder("d", files, "c.bin"));

        return root;
    }

    private static Fnt.Folder folder(String name, List<byte[]> files, String... fileNames)
    {
        Fnt.Folder folder = new Fnt.Folder(name);
        folder.firstId = files.size();
        for (String fileName : fileNames)
        {
            folder.files.add(fileName);
            files.add((fileName + " contents ").getBytes(StandardCharsets.US_ASCII));
        }

        return folder;
    }

    private static ArrayList<Integer> ids(int... ids)
    {
        ArrayList<Integer> out = new ArrayList<Integer>();
        for (int id : ids)
            out.add(id);

        return out;
    }

    private static int[] unsignedBytes(byte[] bytes)
    {
        int[] out = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++)
            out[i] = bytes[i] & 0xFF;

        return out;
    }

    private static void padTo(MemBuf.MemBufWriter writer, int position)
    {
        writer.writeByteNumTimes((byte) 0, Math.max(0, position - writer.getPosition()));
    }
}
