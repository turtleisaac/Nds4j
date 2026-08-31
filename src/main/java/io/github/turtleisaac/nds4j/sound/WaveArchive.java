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

    /** @return the archive reproduced byte-for-byte (preserved verbatim). */
    public byte[] save() { return raw; }

    public int getWaveCount() { return waves.size(); }

    public Wave getWave(int index) { return waves.get(index); }

    public List<Wave> getWaves() { return waves; }
}
