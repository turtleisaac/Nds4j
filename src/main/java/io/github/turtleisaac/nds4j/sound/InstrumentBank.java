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
        public final int recordType; // 1 = PCM sample, 2 = PSG square, 3 = PSG noise
        public final int waveIndex; // PCM: index into the wave archive; PSG square: duty cycle 0..7
        public final int waveArcIndex; // which of the bank's 4 wave archives (PCM only)
        public final int baseNote; // MIDI note the sample is recorded at
        public final int attack, decay, sustain, release, pan;

        NoteRegion(int recordType, int waveIndex, int waveArcIndex, int baseNote,
                int attack, int decay, int sustain, int release, int pan)
        {
            this.recordType = recordType;
            this.waveIndex = waveIndex;
            this.waveArcIndex = waveArcIndex;
            this.baseNote = baseNote;
            this.attack = attack;
            this.decay = decay;
            this.sustain = sustain;
            this.release = release;
            this.pan = pan;
            this.isPcm = recordType == 1; // only type 1 references a real wave archive sample
            this.isPsg = recordType == 2; // synthesized square wave; waveIndex is the duty cycle
            this.isNoise = recordType == 3; // synthesized LFSR noise
        }

        public final boolean isPcm;
        public final boolean isPsg;
        public final boolean isNoise;

        /** PSG square-wave duty cycle 0..7 (12.5%, 25%, 37.5%, 50%, 62.5%, 75%, 87.5%, 0%). */
        public int getDuty()
        {
            return waveIndex & 0x7;
        }
    }

    /** One program entry. {@code type} is the raw SBNK instrument type; {@code regions} are its parts. */
    public static class Instrument
    {
        public final int type;
        public final int lowNote; // drum set: first mapped note (else 0)
        public final int[] splitPoints; // key-split: up to 8 upper note bounds (else null)
        public final List<NoteRegion> regions = new ArrayList<>();

        Instrument(int type, int lowNote, int[] splitPoints)
        {
            this.type = type;
            this.lowNote = lowNote;
            this.splitPoints = splitPoints;
        }
        public boolean isEmpty()
        {
            return type == 0 || regions.isEmpty();
        }
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
        readGenericNtrHeader(reader); // "SBNK" + NTR header
        // "DATA"(4) + blockSize(4) + 32 reserved => instrument count at 0x38
        reader.setPosition(0x38);
        int count = (int) reader.readUInt32();
        int tablePos = 0x3C;
        for (int i = 0; i < count; i++)
        {
            int type = data[tablePos] & 0xFF;
            MemBuf.MemBufReader field0Reader = MemBuf.create(data).reader();
            field0Reader.setPosition(tablePos + 1);
            int field0 = field0Reader.readUInt16();
            int off = field0;
            tablePos += 4; // u8 type, u16 offset, u8 padding
            instruments.add(parseInstrument(data, type, off));
        }
    }

    private Instrument parseInstrument(byte[] d, int type, int off)
    {
        if (absent(type, off))
            return new Instrument(0, 0, null);

        return present(d, type, off);
    }

    /** @param type the instrument type @param off its offset @return whether the slot is empty */
    private static boolean absent(int type, int off)
    {
        if (type == 0)
            return true;

        return off == 0;
    }

    /** @param d the bank @param type the instrument type @param off its offset @return the instrument */
    private Instrument present(byte[] d, int type, int off)
    {
        if (direct(type))
            return directInstrument(d, type, off);

        return ranged(d, type, off);
    }

    /** @param type the instrument type @return whether it is a single note region */
    private static boolean direct(int type)
    {
        if (type < 1)
            return false;

        return type <= 15;
    }

    /** @param d the bank @param type the instrument type @param off its offset @return one note region */
    private Instrument directInstrument(byte[] d, int type, int off)
    {
        Instrument inst = new Instrument(type, 0, null);
        inst.regions.add(readBody(d, off, type));

        return inst;
    }

    /** @param d the bank @param type the instrument type @param off its offset @return a drum set, key split, or unknown */
    private Instrument ranged(byte[] d, int type, int off)
    {
        if (type == 16)
            return drum(d, type, off);

        return keyed(d, type, off);
    }

    /**
     * Drum set: u8 lowNote, u8 highNote, then one record per note.
     * @param d the bank
     * @param type the instrument type
     * @param off its offset
     * @return the drum set
     */
    private Instrument drum(byte[] d, int type, int off)
    {
        int low = d[off] & 0xFF;
        int high = d[off + 1] & 0xFF;
        Instrument inst = new Instrument(type, low, null);
        fillDrum(d, inst, high, off + 2, low);

        return inst;
    }

    /** @param d the bank @param inst the drum set @param high the last note @param p the next record @param note the note being read */
    private void fillDrum(byte[] d, Instrument inst, int high, int p, int note)
    {
        if (note > high)
            return;

        addDrum(d, inst, high, p, note);
    }

    /** @param d the bank @param inst the drum set @param high the last note @param p the next record @param note the note being read */
    private void addDrum(byte[] d, Instrument inst, int high, int p, int note)
    {
        if (p + 12 > d.length)
            return;

        MemBuf.MemBufReader field1Reader = MemBuf.create(d).reader();
        field1Reader.setPosition(p);
        int field1 = field1Reader.readUInt16();
        int subType = field1;
        inst.regions.add(readBody(d, p + 2, subType));
        fillDrum(d, inst, high, p + 12, note + 1);
    }

    /** @param d the bank @param type the instrument type @param off its offset @return a key split, or the raw type when it is unknown */
    private Instrument keyed(byte[] d, int type, int off)
    {
        if (type == 17)
            return keySplit(d, type, off);

        return new Instrument(type, 0, null);
    }

    /**
     * Key split: eight split points, then one record per non-zero point.
     * @param d the bank
     * @param type the instrument type
     * @param off its offset
     * @return the key split
     */
    private Instrument keySplit(byte[] d, int type, int off)
    {
        int[] splits = new int[8];
        int regionCount = countSplits(d, off, splits, 0, 0);
        Instrument inst = new Instrument(type, 0, splits);
        fillSplit(d, inst, regionCount, off + 8, 0);

        return inst;
    }

    /** @param d the bank @param off the split-point table @param splits where the points are stored @param i which point @param count non-zero points so far @return the non-zero count */
    private static int countSplits(byte[] d, int off, int[] splits, int i, int count)
    {
        if (i >= 8)
            return count;

        return oneSplit(d, off, splits, i, count);
    }

    /** @param d the bank @param off the split-point table @param splits where the points are stored @param i which point @param count non-zero points so far @return the non-zero count */
    private static int oneSplit(byte[] d, int off, int[] splits, int i, int count)
    {
        splits[i] = d[off + i] & 0xFF;

        if (splits[i] != 0)
            return countSplits(d, off, splits, i + 1, count + 1);

        return countSplits(d, off, splits, i + 1, count);
    }

    /** @param d the bank @param inst the key split @param regionCount how many records follow @param p the next record @param i which record */
    private void fillSplit(byte[] d, Instrument inst, int regionCount, int p, int i)
    {
        if (i >= regionCount)
            return;

        addSplit(d, inst, regionCount, p, i);
    }

    /** @param d the bank @param inst the key split @param regionCount how many records follow @param p the next record @param i which record */
    private void addSplit(byte[] d, Instrument inst, int regionCount, int p, int i)
    {
        if (p + 12 <= d.length)
        {
            MemBuf.MemBufReader field2Reader = MemBuf.create(d).reader();
            field2Reader.setPosition(p);
            int field2 = field2Reader.readUInt16();
            int subType = field2;
            NoteRegion region = readBody(d, p + 2, subType);
            inst.regions.add(region);
            fillSplit(d, inst, regionCount, p + 12, i + 1);
        }
    }

    /** 10-byte instrument body: u16 wave, u16 waveArc, u8 note, u8 attack, u8 decay, u8 sustain, u8 release, u8 pan. */
    private NoteRegion readBody(byte[] d, int p, int recordType)
    {
        MemBuf.MemBufReader field3Reader = MemBuf.create(d).reader();
        field3Reader.setPosition(p);
        int field3 = field3Reader.readUInt16();
        int wave = field3;
        MemBuf.MemBufReader field4Reader = MemBuf.create(d).reader();
        field4Reader.setPosition(p + 2);
        int field4 = field4Reader.readUInt16();
        int waveArc = field4;
        int note = d[p + 4] & 0xFF;
        int attack = d[p + 5] & 0xFF;
        int decay = d[p + 6] & 0xFF;
        int sustain = d[p + 7] & 0xFF;
        int release = d[p + 8] & 0xFF;
        int pan = d[p + 9] & 0xFF;
        return new NoteRegion(recordType, wave, waveArc, note, attack, decay, sustain, release, pan);
    }

    /**
     * Pick the note region a (program, note) pair plays — the one lookup the synthesizer needs.
     * @return the region, or null if the program is empty or the note is out of range
     */
    public NoteRegion resolve(int program, int note)
    {
        if (program < 0 || program >= instruments.size())
            return null;
        Instrument inst = instruments.get(program);
        if (inst.isEmpty())
            return null;
        if (inst.type <= 15)
            return inst.regions.get(0);
        if (inst.type == 16) // drum set: region index = note - lowNote
        {
            int idx = note - inst.lowNote;
            return (idx >= 0 && idx < inst.regions.size()) ? inst.regions.get(idx) : null;
        }
        if (inst.type == 17) // key-split: first split point >= note
        {
            for (int i = 0; i < inst.splitPoints.length && i < inst.regions.size(); i++)
                if (note <= inst.splitPoints[i])
                    return inst.regions.get(i);
            return null;
        }
        return null;
    }

    public byte[] save()
    {
        return raw;
    }
    public int getInstrumentCount()
    {
        return instruments.size();
    }
    public Instrument getInstrument(int program)
    {
        return instruments.get(program);
    }
}
