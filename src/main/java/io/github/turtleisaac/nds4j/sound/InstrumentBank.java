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
 * An instrument bank ("SBNK"): the table a {@link Sequence SSEQ} indexes by program number to decide
 * <em>which</em> {@link Wave sample} (out of the bank's up-to-four {@link WaveArchive wave archives})
 * plays for a given note, and with what envelope (attack/decay/sustain/release) and pan.
 * <p>
 * Each program is an {@link Instrument}. Simple instruments have a single note region (one sample,
 * pitch-shifted across the keyboard from a base note); a <b>drum set</b> ({@code type 16}) maps each
 * note in a range to its own region; a <b>key-split</b> ({@code type 17}) divides the keyboard into up
 * to eight ranges. {@link #resolve(int, int)} picks the right region for a (program, note) pair — the
 * one thing the synth needs. The bank is preserved verbatim, so {@link #save()} is byte-exact.
 * RE'd against ndspy ({@code soundBank.py}) and GBATEK.
 */
public class InstrumentBank extends GenericNtrFile
{
    /** One playable region: a sample reference plus its envelope. PSG/noise regions have no sample. */
    public static class NoteRegion
    {
        public final int recordType;   // 1 = PCM sample, 2 = PSG square, 3 = PSG noise
        public final int waveIndex;    // PCM: index into the wave archive; PSG square: duty cycle 0..7
        public final int waveArcIndex; // which of the bank's 4 wave archives (PCM only)
        public final int baseNote;     // MIDI note the sample is recorded at
        public final int attack, decay, sustain, release, pan;

        NoteRegion(int recordType, int waveIndex, int waveArcIndex, int baseNote,
                   int attack, int decay, int sustain, int release, int pan)
        {
            this.recordType = recordType;
            this.waveIndex = waveIndex;
            this.waveArcIndex = waveArcIndex;
            this.baseNote = baseNote;
            this.attack = attack; this.decay = decay; this.sustain = sustain;
            this.release = release; this.pan = pan;
            this.isPcm = recordType == 1;      // only type 1 references a real wave archive sample
            this.isPsg = recordType == 2;      // synthesized square wave; waveIndex is the duty cycle
            this.isNoise = recordType == 3;    // synthesized LFSR noise
        }
        public final boolean isPcm;
        public final boolean isPsg;
        public final boolean isNoise;
        /** PSG square-wave duty cycle 0..7 (12.5%, 25%, 37.5%, 50%, 62.5%, 75%, 87.5%, 0%). */
        public int getDuty() { return waveIndex & 0x7; }
    }

    /** One program entry. {@code type} is the raw SBNK instrument type; {@code regions} are its parts. */
    public static class Instrument
    {
        public final int type;
        public final int lowNote;                 // drum set: first mapped note (else 0)
        public final int[] splitPoints;           // key-split: up to 8 upper note bounds (else null)
        public final List<NoteRegion> regions = new ArrayList<>();

        Instrument(int type, int lowNote, int[] splitPoints)
        {
            this.type = type; this.lowNote = lowNote; this.splitPoints = splitPoints;
        }
        public boolean isEmpty() { return type == 0 || regions.isEmpty(); }
    }

    private byte[] raw;
    private final List<Instrument> instruments = new ArrayList<>();

    public InstrumentBank()
    {
        super("SBNK");
    }

    public static InstrumentBank fromBytes(byte[] data)
    {
        InstrumentBank bank = new InstrumentBank();
        bank.read(data);
        return bank;
    }

    private void read(byte[] data)
    {
        raw = data;
        MemBuf buf = MemBuf.create(data);
        MemBuf.MemBufReader reader = buf.reader();
        readGenericNtrHeader(reader);       // "SBNK" + NTR header
        // "DATA"(4) + blockSize(4) + 32 reserved => instrument count at 0x38
        reader.setPosition(0x38);
        int count = (int) reader.readUInt32();
        int tablePos = 0x3C;
        for (int i = 0; i < count; i++)
        {
            int type = data[tablePos] & 0xFF;
            int off = u16(data, tablePos + 1);
            tablePos += 4;                  // u8 type, u16 offset, u8 padding
            instruments.add(parseInstrument(data, type, off));
        }
    }

    private Instrument parseInstrument(byte[] d, int type, int off)
    {
        if (type == 0 || off == 0)
            return new Instrument(0, 0, null);

        if (type >= 1 && type <= 15)
        {
            Instrument inst = new Instrument(type, 0, null);
            inst.regions.add(readBody(d, off, type));
            return inst;
        }
        if (type == 16) // drum set: u8 lowNote, u8 highNote, then (high-low+1) x (u16 type + 10-byte body)
        {
            int low = d[off] & 0xFF, high = d[off + 1] & 0xFF;
            Instrument inst = new Instrument(type, low, null);
            int p = off + 2;
            for (int n = low; n <= high && p + 12 <= d.length; n++)
            {
                int subType = u16(d, p);
                inst.regions.add(readBody(d, p + 2, subType));
                p += 12;
            }
            return inst;
        }
        if (type == 17) // key-split: u8[8] split points, then N x (u16 type + 10-byte body)
        {
            int[] splits = new int[8];
            int regionCount = 0;
            for (int i = 0; i < 8; i++)
            {
                splits[i] = d[off + i] & 0xFF;
                if (splits[i] != 0) regionCount++;
            }
            Instrument inst = new Instrument(type, 0, splits);
            int p = off + 8;
            for (int i = 0; i < regionCount && p + 12 <= d.length; i++)
            {
                int subType = u16(d, p);
                inst.regions.add(readBody(d, p + 2, subType));
                p += 12;
            }
            return inst;
        }
        return new Instrument(type, 0, null); // unknown; kept for byte-exactness via raw
    }

    /** 10-byte instrument body: u16 wave, u16 waveArc, u8 note, u8 attack, u8 decay, u8 sustain, u8 release, u8 pan. */
    private NoteRegion readBody(byte[] d, int p, int recordType)
    {
        int wave    = u16(d, p);
        int waveArc = u16(d, p + 2);
        int note    = d[p + 4] & 0xFF;
        int attack  = d[p + 5] & 0xFF;
        int decay   = d[p + 6] & 0xFF;
        int sustain = d[p + 7] & 0xFF;
        int release = d[p + 8] & 0xFF;
        int pan     = d[p + 9] & 0xFF;
        return new NoteRegion(recordType, wave, waveArc, note, attack, decay, sustain, release, pan);
    }

    /**
     * Pick the note region a (program, note) pair plays — the one lookup the synthesizer needs.
     * @return the region, or null if the program is empty or the note is out of range
     */
    public NoteRegion resolve(int program, int note)
    {
        if (program < 0 || program >= instruments.size()) return null;
        Instrument inst = instruments.get(program);
        if (inst.isEmpty()) return null;
        if (inst.type <= 15) return inst.regions.get(0);
        if (inst.type == 16) // drum set: region index = note - lowNote
        {
            int idx = note - inst.lowNote;
            return (idx >= 0 && idx < inst.regions.size()) ? inst.regions.get(idx) : null;
        }
        if (inst.type == 17) // key-split: first split point >= note
        {
            for (int i = 0; i < inst.splitPoints.length && i < inst.regions.size(); i++)
                if (note <= inst.splitPoints[i]) return inst.regions.get(i);
            return null;
        }
        return null;
    }

    public byte[] save() { return raw; }
    public int getInstrumentCount() { return instruments.size(); }
    public Instrument getInstrument(int program) { return instruments.get(program); }

    private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
}
