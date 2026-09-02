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

package io.github.turtleisaac.nds4j.sound;

import io.github.turtleisaac.nds4j.framework.GenericNtrFile;
import io.github.turtleisaac.nds4j.framework.MemBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * A wave archive ("SWAR"): a table of {@link Wave sampled waveforms} that instrument banks
 * ({@link InstrumentBank SBNK}) draw their samples from. After the NTR header comes a single
 * {@code "DATA"} block: 32 reserved bytes, a {@code u32} sample count, then that many {@code u32}
 * offsets (from the archive start) to each wave's 12-byte info struct; the encoded sample data follows
 * each struct and runs up to the next offset (or the end of the archive).
 * <p>
 * The archive is preserved verbatim, so {@link #save()} is byte-exact; the waves are a decoded view.
 */
public class WaveArchive extends GenericNtrFile
{
    private byte[] raw;
    private final List<Wave> waves = new ArrayList<>();

    public WaveArchive()
    {
        super("SWAR");
    }

    public static WaveArchive fromBytes(byte[] data)
    {
        WaveArchive swar = new WaveArchive();
        swar.read(data);
        return swar;
    }

    private void read(byte[] data)
    {
        raw = data;
        MemBuf buf = MemBuf.create(data);
        MemBuf.MemBufReader reader = buf.reader();
        readGenericNtrHeader(reader); // "SWAR" + bom/ver/size/hdr/blocks
        // "DATA"(4) + blockSize(4) + 32 reserved bytes => sample count at 0x38
        reader.setPosition(0x38);
        int count = (int) reader.readUInt32();

        int[] offsets = new int[count];
        for (int i = 0; i < count; i++)
            offsets[i] = (int) reader.readUInt32();

        for (int i = 0; i < count; i++)
        {
            int infoPos = offsets[i];
            if (infoPos == 0) { continue; }
            int dataStart = infoPos + 0x0C;
            int dataEnd = (i + 1 < count && offsets[i + 1] != 0) ? offsets[i + 1] : data.length;
            waves.add(Wave.fromInfoStruct(data, infoPos, dataStart, dataEnd));
        }
    }

    /** @return the archive reproduced byte-for-byte (preserved verbatim until a wave is replaced). */
    public byte[] save() { return raw; }

    public int getWaveCount() { return waves.size(); }

    public Wave getWave(int index) { return waves.get(index); }

    public List<Wave> getWaves() { return waves; }

    /**
     * Replace wave {@code index} and rebuild the archive. Other waves are preserved verbatim
     * (their encoded payload, not a re-encode).
     */
    public void replaceWave(int index, Wave wave)
    {
        if (index < 0 || index >= waves.size())
            throw new IndexOutOfBoundsException("wave " + index + " of " + waves.size());
        if (wave == null) throw new IllegalArgumentException("wave is null");
        waves.set(index, wave);
        rebuild();
    }

    /**
     * Import a PCM WAV over wave {@code index}, encoding it as that wave's existing type and
     * keeping its loop flag (the whole imported sample loops when the original did).
     */
    public void importWav(int index, byte[] wavBytes)
    {
        Wave old = getWave(index);
        replaceWave(index, Wave.fromWav(wavBytes, old.getWaveType(), old.loops()));
    }

    private void rebuild()
    {
        int n = waves.size();
        int table = 0x3C + n * 4;
        int pos = table;
        byte[][] blobs = new byte[n][];
        int[] offsets = new int[n];
        for (int i = 0; i < n; i++)
        {
            Wave w = waves.get(i);
            byte[] info = w.infoBytes();
            byte[] data = w.getSampleData();
            blobs[i] = new byte[info.length + data.length];
            System.arraycopy(info, 0, blobs[i], 0, info.length);
            System.arraycopy(data, 0, blobs[i], info.length, data.length);
            offsets[i] = pos;
            pos += blobs[i].length;
        }

        byte[] out = new byte[pos];
        // preserve BOM/version/endianness from the original header when we have one
        if (raw != null && raw.length >= 16)
            System.arraycopy(raw, 0, out, 0, 16);
        else
        {
            writeAscii(out, 0, "SWAR");
            writeU16(out, 4, 0xFEFF);
            writeU16(out, 6, 0x0100);
            writeU16(out, 12, 0x10);
            writeU16(out, 14, 1);
        }
        writeAscii(out, 0, "SWAR");
        writeU32(out, 8, out.length);
        writeAscii(out, 16, "DATA");
        writeU32(out, 20, out.length - 16);
        if (raw != null && raw.length >= 0x38)
            System.arraycopy(raw, 0x18, out, 0x18, 32); // reserved
        writeU32(out, 0x38, n);
        for (int i = 0; i < n; i++)
        {
            writeU32(out, 0x3C + i * 4, offsets[i]);
            System.arraycopy(blobs[i], 0, out, offsets[i], blobs[i].length);
        }
        raw = out;
        fileSize = out.length;
    }

    private static void writeAscii(byte[] b, int o, String s)
    {
        for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i);
    }
    private static void writeU16(byte[] b, int o, int v)
    {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
    }
    private static void writeU32(byte[] b, int o, int v)
    {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }
}
