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
 * A sequence archive ("SSAR"): several short sequences that share one block of event data and one bank —
 * used for jingles and sound-effect sequences. After the NTR header, a {@code "DATA"} block gives a
 * {@code u32} offset to the shared event data and a record table; each record points at where its
 * sequence begins within that data and names its bank and mixing parameters.
 * <p>
 * Preserved verbatim ({@link #save()} is byte-exact); the records are a decoded view. (None of the five
 * bundled Gen IV/V ROMs ship an SSAR, so this is validated structurally rather than against a corpus.)
 */
public class SequenceArchive extends GenericNtrFile
{
    public static class SequenceRecord
    {
        public final long eventOffset; // offset into the shared event data
        public final int bankId;
        public final int volume, channelPriority, playerPriority, playerId;

        SequenceRecord(long eventOffset, int bankId, int volume, int cpr, int ppr, int ply)
        {
            this.eventOffset = eventOffset; this.bankId = bankId;
            this.volume = volume; this.channelPriority = cpr; this.playerPriority = ppr; this.playerId = ply;
        }
    }

    private byte[] raw;
    private long dataOffset;
    private final List<SequenceRecord> records = new ArrayList<>();

    public SequenceArchive()
    {
        super("SSAR");
    }

    public static SequenceArchive fromBytes(byte[] data)
    {
        SequenceArchive ssar = new SequenceArchive();
        ssar.read(data);
        return ssar;
    }

    private void read(byte[] data)
    {
        raw = data;
        MemBuf buf = MemBuf.create(data);
        MemBuf.MemBufReader reader = buf.reader();
        readGenericNtrHeader(reader);   // "SSAR" + NTR header
        reader.setPosition(0x18);
        dataOffset = reader.readUInt32();
        int count = (int) reader.readUInt32();
        for (int i = 0; i < count; i++)
        {
            long ev = reader.readUInt32();
            int bank = reader.readUInt16();
            int vol = reader.readByte() & 0xFF;
            int cpr = reader.readByte() & 0xFF;
            int ppr = reader.readByte() & 0xFF;
            int ply = reader.readByte() & 0xFF;
            reader.skip(2); // reserved
            records.add(new SequenceRecord(ev, bank, vol, cpr, ppr, ply));
        }
    }

    public byte[] save() { return raw; }
    public byte[] getRaw() { return raw; }
    public long getDataOffset() { return dataOffset; }
    public int getRecordCount() { return records.size(); }
    public SequenceRecord getRecord(int i) { return records.get(i); }
}
