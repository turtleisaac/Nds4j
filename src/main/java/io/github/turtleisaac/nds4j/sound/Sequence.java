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

/**
 * A sequenced music track ("SSEQ") — the DS equivalent of a MIDI file. After the NTR header, a single
 * {@code "DATA"} block holds a {@code u32} offset to the event stream followed by the events: a compact
 * bytecode of note-ons (note + velocity + variable-length duration), rests, program changes, tempo,
 * volume/pan, per-track opens, jumps/calls/loops, pitch bend, and so on. A companion
 * {@link InstrumentBank SBNK} says which sample each program plays.
 * <p>
 * This class preserves the file verbatim ({@link #save()} is byte-exact) and exposes the event bytes
 * ({@link #getEventData()} / {@link #getEventDataOffset()}). Interpretation lives in the player;
 * {@link SequencePlayer} walks these bytes. RE'd against ndspy ({@code soundSequence.py}) and GBATEK.
 */
public class Sequence extends GenericNtrFile
{
    private byte[] raw;
    private int eventDataOffset;

    public Sequence()
    {
        super("SSEQ");
    }

    public static Sequence fromBytes(byte[] data)
    {
        Sequence seq = new Sequence();
        seq.read(data);
        return seq;
    }

    private void read(byte[] data)
    {
        raw = data;
        MemBuf buf = MemBuf.create(data);
        MemBuf.MemBufReader reader = buf.reader();
        readGenericNtrHeader(reader);   // "SSEQ" + NTR header
        // "DATA"(4) + blockSize(4) => u32 event-data offset at 0x18, events follow
        reader.setPosition(0x18);
        eventDataOffset = (int) reader.readUInt32();
    }

    public byte[] save() { return raw; }

    /** @return the whole file bytes (event offsets in the bytecode are absolute from here). */
    public byte[] getRaw() { return raw; }

    /** @return offset (from file start) to the first sequence event. */
    public int getEventDataOffset() { return eventDataOffset; }

    /** @return the sequence event bytecode. */
    public byte[] getEventData()
    {
        return java.util.Arrays.copyOfRange(raw, eventDataOffset, raw.length);
    }
}
