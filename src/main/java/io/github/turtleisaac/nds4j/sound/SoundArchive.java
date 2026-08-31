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
import java.util.Arrays;
import java.util.List;

/**
 * The Nintendo DS master sound archive ("SDAT"). One SDAT bundles every piece of a game's audio: the
 * sequenced music ({@link Sequence SSEQ}) and its sequence archives ({@code SSAR}), the instrument
 * banks ({@code SBNK}), the wave archives ({@code SWAR}) that hold the sampled waveforms, and the
 * streamed audio ({@code STRM}) — plus the symbol names, player/group tables, and a file-allocation
 * table that ties it all together.
 * <p>
 * The container is a generic NTR file whose header is followed by four block-offset/size pairs:
 * {@code SYMB} (symbol names, optional), {@code INFO} (a record per logical entry), {@code FAT}
 * (offset+size of every embedded file), and {@code FILE} (the embedded files themselves). This class
 * preserves the whole archive verbatim, so {@link #save()} is byte-exact, and parses the tables as a
 * read-only view: the symbol names, the eight kinds of INFO record, and the FAT. Embedded files are
 * sliced straight out of the archive by their FAT entry ({@link #getFileData(int)}).
 * <p>
 * References RE'd against: ndspy ({@code soundArchive.py}), GBATEK, and the retail SDATs in the five
 * Gen IV/V ROMs. Correctness bar is the project invariant: byte-identical round-trip
 * ({@code save()} reproduces the input).
 */
public class SoundArchive extends GenericNtrFile
{
    /** The eight record categories, in the fixed on-disk order used by both SYMB and INFO. */
    public enum RecordType
    {
        SEQUENCE,          // SSEQ
        SEQUENCE_ARCHIVE,  // SSAR
        BANK,              // SBNK
        WAVE_ARCHIVE,      // SWAR
        PLAYER,
        GROUP,
        STREAM_PLAYER,
        STREAM             // STRM
    }

    private static final int NUM_SECTIONS = 8;

    private byte[] raw;

    // block offset/size pairs
    private long symbOffset, symbSize;
    private long infoOffset, infoSize;
    private long fatOffset,  fatSize;
    private long fileOffset, fileSize;

    // FAT: absolute offset (from archive start) + size of every embedded file
    private long[] fatEntryOffset;
    private long[] fatEntrySize;

    // SYMB: per section, the symbol name of each entry (may be null / empty)
    private final List<List<String>> symbolNames = new ArrayList<>();
    // SYMB for SEQUENCE_ARCHIVE only: per archive, the names of its sub-sequences
    private final List<List<String>> sequenceArchiveSubNames = new ArrayList<>();

    // INFO: per section, one record per entry (raw bytes; null for an absent slot)
    private final List<List<byte[]>> infoRecords = new ArrayList<>();

    public SoundArchive()
    {
        super("SDAT");
    }

    public static SoundArchive fromBytes(byte[] data)
    {
        SoundArchive sdat = new SoundArchive();
        sdat.read(data);
        return sdat;
    }

    private void read(byte[] data)
    {
        raw = data;
        MemBuf buf = MemBuf.create(data);
        MemBuf.MemBufReader reader = buf.reader();

        readGenericNtrHeader(reader);
        // four (offset, size) block pairs follow the 0x10-byte NTR header
        symbOffset = reader.readUInt32(); symbSize = reader.readUInt32();
        infoOffset = reader.readUInt32(); infoSize = reader.readUInt32();
        fatOffset  = reader.readUInt32(); fatSize  = reader.readUInt32();
        fileOffset = reader.readUInt32(); fileSize = reader.readUInt32();

        readFat(reader);
        readInfo(reader);
        readSymb(reader);
    }

    // ------------------------------------------------------------------ FAT

    private void readFat(MemBuf.MemBufReader reader)
    {
        reader.setPosition(fatOffset + 8); // skip "FAT " + block size
        int count = (int) reader.readUInt32();
        fatEntryOffset = new long[count];
        fatEntrySize   = new long[count];
        for (int i = 0; i < count; i++)
        {
            fatEntryOffset[i] = reader.readUInt32();
            fatEntrySize[i]   = reader.readUInt32();
            reader.skip(8); // reserved (memory/load flags, unused on disk)
        }
    }

    // ----------------------------------------------------------------- INFO

    private void readInfo(MemBuf.MemBufReader reader)
    {
        int base = (int) infoOffset;
        for (int s = 0; s < NUM_SECTIONS; s++)
        {
            List<byte[]> recs = new ArrayList<>();
            infoRecords.add(recs);
            long sectionRel = readU32At(reader, base + 8 + s * 4);
            if (sectionRel == 0)
                continue;
            int sectionPos = base + (int) sectionRel;
            int count = (int) readU32At(reader, sectionPos);
            for (int i = 0; i < count; i++)
            {
                long entryRel = readU32At(reader, sectionPos + 4 + i * 4);
                if (entryRel == 0) { recs.add(null); continue; }
                recs.add(sliceRecord(base + (int) entryRel, s));
            }
        }
    }

    /**
     * INFO records have no length field. Each kind has a fixed layout; we capture exactly the bytes the
     * layout defines so the record can be re-inspected without re-reading the archive.
     */
    private byte[] sliceRecord(int pos, int section)
    {
        int len;
        switch (RecordType.values()[section])
        {
            case SEQUENCE:         len = 0x0C; break; // fileId,unk,bankId,vol,cpr,ppr,ply,pad
            case SEQUENCE_ARCHIVE: len = 0x02; break; // fileId
            case BANK:             len = 0x0C; break; // fileId, unk(u16), wave[4] (u16 each)
            case WAVE_ARCHIVE:     len = 0x04; break; // fileId,flags
            case PLAYER:           len = 0x08; break;
            case GROUP:            len = 0x04; break; // just the count header; groups are variable — see getGroupRecordRaw
            case STREAM_PLAYER:    len = 0x18; break;
            case STREAM:           len = 0x18; break; // fileId,vol,pri,ply,pad
            default:               len = 0x04; break;
        }
        return Arrays.copyOfRange(raw, pos, Math.min(pos + len, raw.length));
    }

    // ----------------------------------------------------------------- SYMB

    private void readSymb(MemBuf.MemBufReader reader)
    {
        for (int s = 0; s < NUM_SECTIONS; s++)
        {
            symbolNames.add(new ArrayList<String>());
            sequenceArchiveSubNames.add(new ArrayList<String>());
        }
        if (symbOffset == 0 || symbSize == 0)
            return; // no symbol block (valid — names are optional)

        int base = (int) symbOffset;
        for (int s = 0; s < NUM_SECTIONS; s++)
        {
            long sectionRel = readU32At(reader, base + 8 + s * 4);
            List<String> names = symbolNames.get(s);
            if (sectionRel == 0)
                continue;
            int sectionPos = base + (int) sectionRel;
            int count = (int) readU32At(reader, sectionPos);
            if (s == RecordType.SEQUENCE_ARCHIVE.ordinal())
            {
                // each entry: u32 nameOffset, u32 subTableOffset (both relative to SYMB start)
                List<String> subs = sequenceArchiveSubNames.get(s);
                for (int i = 0; i < count; i++)
                {
                    long nameRel = readU32At(reader, sectionPos + 4 + i * 8);
                    long subRel  = readU32At(reader, sectionPos + 8 + i * 8);
                    names.add(nameRel == 0 ? null : readCString(base + (int) nameRel));
                    subs.add(subRel == 0 ? null : String.valueOf(readU32At(reader, base + (int) subRel)));
                }
            }
            else
            {
                for (int i = 0; i < count; i++)
                {
                    long nameRel = readU32At(reader, sectionPos + 4 + i * 4);
                    names.add(nameRel == 0 ? null : readCString(base + (int) nameRel));
                }
            }
        }
    }

    // -------------------------------------------------------------- helpers

    private long readU32At(MemBuf.MemBufReader reader, int pos)
    {
        reader.setPosition(pos);
        return reader.readUInt32();
    }

    private String readCString(int pos)
    {
        int end = pos;
        while (end < raw.length && raw[end] != 0) end++;
        return new String(raw, pos, end - pos, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    // ----------------------------------------------------------- public API

    /** @return the archive reproduced byte-for-byte (the whole SDAT is preserved verbatim). */
    public byte[] save()
    {
        return raw;
    }

    /** @return number of embedded files (FAT entry count). */
    public int getFileCount() { return fatEntryOffset.length; }

    /** @return the raw bytes of embedded file {@code fatIndex} (an SSEQ/SSAR/SBNK/SWAR/STRM). */
    public byte[] getFileData(int fatIndex)
    {
        int off = (int) fatEntryOffset[fatIndex];
        int len = (int) fatEntrySize[fatIndex];
        return Arrays.copyOfRange(raw, off, off + len);
    }

    /** @return the number of INFO records of the given kind (some may be absent placeholders). */
    public int getRecordCount(RecordType type) { return infoRecords.get(type.ordinal()).size(); }

    /** @return the raw INFO record bytes for entry {@code index} of {@code type}, or null if absent. */
    public byte[] getInfoRecord(RecordType type, int index) { return infoRecords.get(type.ordinal()).get(index); }

    /** @return the symbol name for entry {@code index} of {@code type}, or null if unnamed / no SYMB block. */
    public String getName(RecordType type, int index)
    {
        List<String> names = symbolNames.get(type.ordinal());
        return index < names.size() ? names.get(index) : null;
    }

    public boolean hasSymbols() { return symbOffset != 0 && symbSize != 0; }

    /**
     * The FAT file index an INFO record points at. SEQUENCE/BANK/WAVE_ARCHIVE/STREAM records begin with a
     * {@code u16 fileId}; other kinds have no file and return -1.
     * @return the FAT index, or -1 if this record type owns no embedded file
     */
    public int getFileId(RecordType type, int index)
    {
        switch (type)
        {
            case SEQUENCE: case BANK: case WAVE_ARCHIVE: case STREAM:
                byte[] r = getInfoRecord(type, index);
                return (r == null) ? -1 : (r[0] & 0xFF) | ((r[1] & 0xFF) << 8);
            default:
                return -1;
        }
    }

    /** @return the embedded file bytes for INFO record {@code index} of {@code type}, or null. */
    public byte[] getFileFor(RecordType type, int index)
    {
        int fid = getFileId(type, index);
        return fid < 0 ? null : getFileData(fid);
    }

    // convenience typed accessors for records with useful secondary fields

    /** SEQUENCE record's bank id (index into the BANK section). */
    public int getSequenceBankId(int index)
    {
        byte[] r = getInfoRecord(RecordType.SEQUENCE, index);
        return r == null ? -1 : (r[4] & 0xFF) | ((r[5] & 0xFF) << 8);
    }

    /** BANK record's four wave-archive ids (index into the WAVE_ARCHIVE section; 0xFFFF = none). */
    public int[] getBankWaveArchives(int index)
    {
        byte[] r = getInfoRecord(RecordType.BANK, index);
        if (r == null || r.length < 0x0C) return new int[0];
        int[] out = new int[4];
        for (int i = 0; i < 4; i++)          // fileId(u16), unknown(u16), then 4x waveArc(u16)
            out[i] = (r[4 + i * 2] & 0xFF) | ((r[5 + i * 2] & 0xFF) << 8);
        return out;
    }
}
