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

package io.github.turtleisaac.nds4j.text;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * An object representation of a <b>BMG</b> (Binary MessaGe) file, magic {@code "MESGbmg1"} &mdash; the
 * Nintendo message/text container shared across GameCube/Wii/DS titles, distinct from any single game's
 * bespoke text encoding (e.g. the mainline Pokemon games' own scripted-text format). Layout cross-checked
 * against real files from Phantom Hourglass, New Super Mario Bros, Mario Kart DS, Animal Crossing, and the
 * mainline Pokemon ROMs (all little-endian, all but one UTF-16) &mdash; and against <a
 * href="https://github.com/RoadrunnerWMC/ndspy">ndspy</a>, an actively-maintained DS-specific BMG
 * reader/writer this class's algorithm is a faithful port of, including its endianness-sniffing heuristic
 * (one known title, Super Princess Peach, uses big-endian BMGs).
 * <p>
 * A BMG is a small header ({@code MESGbmg1} + total length + section count + a 1-byte encoding selector +
 * three reserved u32 words) followed by 2-4 sections:
 * <ul>
 *   <li><b>INF1</b> (required): one fixed-size record per message &mdash; a u32 offset into DAT1's string
 *   data (0 means "no text at all", distinct from an empty string) followed by a handful of opaque,
 *   game-specific "info" bytes (speaker id, sound effect, etc. &mdash; never interpreted here, only
 *   preserved).</li>
 *   <li><b>DAT1</b> (required): every message's text back-to-back, NUL-terminated in the file's own
 *   encoding (UTF-16, UTF-8, Shift-JIS, or Windows-1252/CP1252). A NUL can appear mid-string as part of an
 *   {@code 0x1A} <em>escape sequence</em> (a length byte, a type byte, then raw parameter bytes &mdash;
 *   used for things like inserting the player's name or an icon glyph); {@link Message.Escape} preserves
 *   these as opaque {@code (type, data)} pairs rather than interpreting them, since their meaning is
 *   entirely game-specific.</li>
 *   <li><b>FLW1</b> / <b>FLI1</b> (optional, seen in the DS Zelda titles): a small scripting layer over a
 *   set of BMGs (branching/conditional message flow) that this class stores as opaque records &mdash;
 *   decoding the instruction bytecode itself is out of scope, matching ndspy's own stance.</li>
 * </ul>
 * Unlike ndspy (which silently drops all-zero FLW1 instructions/labels on read and resynthesizes a
 * default padding scheme on write), every field here is preserved exactly as parsed and replayed
 * unmodified on {@link #save()}, so an unedited file round-trips byte-for-byte without relying on a
 * particular padding convention actually matching the original.
 */
public class BinaryMessage
{
    private static final byte[] MAGIC = "MESGbmg1".getBytes(StandardCharsets.US_ASCII);
    private static final String[] ENCODING_NAMES = {null, "windows-1252", "UTF-16", "Shift_JIS", "UTF-8"};

    private boolean bigEndian;
    private int encoding; // 1=cp1252, 2=utf-16, 3=shift-jis, 4=utf-8
    private long unk14, unk18, unk1C;
    // The outer header's declared total-file-size field is decorative in the same way several other
    // Nitro/Nintendo formats' size fields turned out to be (see Palette/CellBank/IndexedImage's
    // identical fix elsewhere in this project): real Phantom Hourglass files carrying FLW1/FLI1
    // declare a length covering only header+INF1+DAT1, silently omitting the (very much physically
    // present) trailing sections -- apparently a back-compat leftover from before those sections
    // existed. Preserved and re-emitted verbatim rather than recomputed; 0 for a from-scratch BMG
    // (fileSize never parsed), which falls back to the real computed size on save().
    private long declaredTotalLength;
    // The 3 bytes between the 29-byte header proper and the first section at offset 0x20. Zero in every
    // real file seen, but preserved rather than assumed.
    private byte[] headerPadding = new byte[3];

    private long inf1Id;
    private int inf1EntryLength; // 4 (offset only) + however many opaque info bytes each message carries
    private final List<Message> messages = new ArrayList<>();

    private boolean hasFlw1;
    private long flw1Unk0C;
    private List<byte[]> instructions = new ArrayList<>();      // raw, 8 bytes each, unfiltered
    private short[] labelIndices = new short[0];
    private byte[] labelBmgIds = new byte[0];

    private boolean hasFli1;
    private long fli1Unk0C;
    private List<Script> scripts = new ArrayList<>();

    /** Creates an empty, from-scratch BMG (little-endian, UTF-16, no messages). */
    public BinaryMessage()
    {
        bigEndian = false;
        encoding = 2;
        inf1EntryLength = 4;
    }

    /**
     * Parses a BMG file.
     * @param data a <code>byte[]</code> containing a binary representation of a BMG file
     */
    public BinaryMessage(byte[] data)
    {
        if (data.length < 0x20 || !Arrays.equals(Arrays.copyOfRange(data, 0, 8), MAGIC))
            throw new RuntimeException("Not a valid BMG file.");

        // Endianness isn't declared anywhere -- sniffed the same way ndspy does: read the total-length
        // field both ways and trust whichever interpretation is the smaller (i.e. plausible) value. Every
        // real BMG length is tiny compared to 2^32, so the wrong endianness reads as an enormous number.
        long lenLe = u32(data, 8, false);
        long lenBe = u32(data, 8, true);
        bigEndian = lenBe < lenLe;
        declaredTotalLength = bigEndian ? lenBe : lenLe;

        long sectionCount = u32(data, 12, bigEndian);
        encoding = data[16] & 0xFF;
        if (encoding <= 0 || encoding >= ENCODING_NAMES.length)
            throw new RuntimeException("Unknown BMG encoding value: " + encoding);
        unk14 = u32(data, 17, bigEndian);
        unk18 = u32(data, 21, bigEndian);
        unk1C = u32(data, 25, bigEndian);
        headerPadding = Arrays.copyOfRange(data, 29, 32);

        List<long[]> inf1Entries = new ArrayList<>(); // {offset, infoStart, infoEnd}
        int dat1PayloadStart = -1, dat1PayloadEnd = -1;

        int off = 0x20;
        for (int s = 0; s < sectionCount; s++)
        {
            String sectionMagic = new String(data, off, 4, StandardCharsets.US_ASCII);
            long sectionLen = u32(data, off + 4, bigEndian);
            switch (sectionMagic)
            {
                case "INF1":
                {
                    int count = (int) u16(data, off + 8, bigEndian);
                    inf1EntryLength = (int) u16(data, off + 10, bigEndian);
                    inf1Id = u32(data, off + 12, bigEndian);
                    for (int i = 0; i < count; i++)
                    {
                        int entryOff = off + 16 + i * inf1EntryLength;
                        long msgOffset = u32(data, entryOff, bigEndian);
                        inf1Entries.add(new long[]{msgOffset, entryOff + 4, entryOff + inf1EntryLength});
                    }
                    break;
                }
                case "DAT1":
                    dat1PayloadStart = off + 8;
                    dat1PayloadEnd = (int) (off + sectionLen);
                    break;
                case "FLW1":
                {
                    hasFlw1 = true;
                    int instructionsCount = (int) u16(data, off + 8, bigEndian);
                    int labelsCount = (int) u16(data, off + 10, bigEndian);
                    flw1Unk0C = u32(data, off + 12, bigEndian);
                    int instTableStart = off + 16;
                    instructions = new ArrayList<>();
                    for (int i = 0; i < instructionsCount; i++)
                        instructions.add(Arrays.copyOfRange(data, instTableStart + i * 8, instTableStart + i * 8 + 8));
                    int indicesStart = instTableStart + instructionsCount * 8;
                    int bmgIdsStart = indicesStart + labelsCount * 2;
                    labelIndices = new short[labelsCount];
                    labelBmgIds = new byte[labelsCount];
                    for (int i = 0; i < labelsCount; i++)
                    {
                        labelIndices[i] = (short) u16(data, indicesStart + i * 2, bigEndian);
                        labelBmgIds[i] = data[bmgIdsStart + i];
                    }
                    break;
                }
                case "FLI1":
                {
                    hasFli1 = true;
                    int count = (int) u16(data, off + 8, bigEndian);
                    int entryLength = (int) u16(data, off + 10, bigEndian);
                    fli1Unk0C = u32(data, off + 12, bigEndian);
                    scripts = new ArrayList<>();
                    for (int i = 0; i < count; i++)
                    {
                        int entryOff = off + 16 + i * entryLength;
                        long id = u32(data, entryOff, bigEndian);
                        short index = (short) u16(data, entryOff + 4, bigEndian);
                        byte[] padding = Arrays.copyOfRange(data, entryOff + 6, entryOff + entryLength);
                        scripts.add(new Script(id, index, padding));
                    }
                    break;
                }
                default:
                    throw new RuntimeException("Unknown BMG section: " + sectionMagic);
            }
            if (sectionLen <= 0)
                throw new RuntimeException("Invalid BMG section length for " + sectionMagic);
            off += sectionLen;
        }

        if (dat1PayloadStart < 0)
            throw new RuntimeException("Not a valid BMG file: missing DAT1 section.");

        String charsetName = charsetName();
        int charWidth = "UTF-16".equalsIgnoreCase(ENCODING_NAMES[encoding]) ? 2 : 1;
        byte[] nullMarker = charWidth == 2 ? (bigEndian ? new byte[]{0, 0} : new byte[]{0, 0}) : new byte[]{0};
        byte[] escMarker = charWidth == 2 ? (bigEndian ? new byte[]{0, 0x1A} : new byte[]{0x1A, 0}) : new byte[]{0x1A};

        for (long[] entry : inf1Entries)
        {
            long declaredOffset = entry[0];
            byte[] info = Arrays.copyOfRange(data, (int) entry[1], (int) entry[2]);
            if (declaredOffset == 0)
            {
                messages.add(new Message(info, new ArrayList<>(), true));
                continue;
            }

            List<Object> parts = new ArrayList<>();
            int p = dat1PayloadStart + (int) declaredOffset;
            int stringStart = p;
            while (!regionEquals(data, p, nullMarker))
            {
                if (regionEquals(data, p, escMarker))
                {
                    if (stringStart != p)
                        parts.add(decodeString(data, stringStart, p, charsetName));
                    int escLen = data[p + charWidth] & 0xFF;
                    int escType = data[p + charWidth + 1] & 0xFF;
                    byte[] escData = Arrays.copyOfRange(data, p + charWidth + 2, p + escLen);
                    parts.add(new Message.Escape(escType, escData));
                    p += escLen;
                    stringStart = p;
                }
                else
                {
                    p += charWidth;
                }
            }
            if (stringStart != p)
                parts.add(decodeString(data, stringStart, p, charsetName));
            messages.add(new Message(info, parts, false));
        }
    }

    /**
     * Generate a <code>byte[]</code> representation of this <code>BinaryMessage</code> as a BMG file.
     * @return a <code>byte[]</code>
     */
    public byte[] save()
    {
        String charsetName = charsetName();

        ByteArrayOutputStream inf1 = new ByteArrayOutputStream();
        ByteArrayOutputStream dat1Body = new ByteArrayOutputStream();
        byte[] nullChar = encodeChar('\0', charsetName);
        dat1Body.writeBytes(nullChar);

        int entryLen = messages.isEmpty() ? Math.max(4, inf1EntryLength) : 4 + messages.get(0).info.length;
        for (int i = 0; i < messages.size(); i++)
        {
            Message m = messages.get(i);
            if (m.info.length != entryLen - 4)
                throw new RuntimeException(String.format(
                        "Message info values are presumed to be %d bytes long, but message %d has %d bytes.",
                        entryLen - 4, i, m.info.length));
            // dat1Body excludes the 8-byte DAT1 section header (unlike the read side's absolute
            // in-file offsets), so unlike ndspy's "len(DAT1) - 8" this needs no extra subtraction --
            // dat1Body.size() is already payload-relative, and starts past the leading nullChar.
            long msgOffset = m.isNull ? 0 : dat1Body.size();
            writeU32(inf1, msgOffset, bigEndian);
            inf1.writeBytes(m.info);
            if (!m.isNull)
                dat1Body.writeBytes(m.save(charsetName));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        int totalLenPos = out.size();
        writeU32(out, 0, bigEndian); // patched at the end
        int numSections = 2;
        int sectionCountPos = out.size();
        writeU32(out, numSections, bigEndian); // patched below once FLW1/FLI1 presence is known
        out.write(encoding);
        writeU32(out, unk14, bigEndian);
        writeU32(out, unk18, bigEndian);
        writeU32(out, unk1C, bigEndian);
        out.writeBytes(headerPadding);

        writeSection(out, "INF1", true, sec ->
        {
            writeU16(sec, messages.size(), bigEndian);
            writeU16(sec, entryLen, bigEndian);
            writeU32(sec, inf1Id, bigEndian);
            sec.writeBytes(inf1.toByteArray());
        });

        writeSection(out, "DAT1", true, sec -> sec.writeBytes(dat1Body.toByteArray()));

        if (hasFlw1)
        {
            numSections++;
            writeSection(out, "FLW1", true, sec ->
            {
                writeU16(sec, instructions.size(), bigEndian);
                writeU16(sec, labelIndices.length, bigEndian);
                writeU32(sec, flw1Unk0C, bigEndian);
                for (byte[] inst : instructions) sec.writeBytes(inst);
                for (short idx : labelIndices) writeU16(sec, idx & 0xFFFF, bigEndian);
                for (byte id : labelBmgIds) sec.write(id);
            });
        }
        if (hasFli1)
        {
            numSections++;
            // FLI1 is the odd one out: its declared section length is rounded up to a 32-byte
            // multiple like every other section, but (verified against retail files) the physical
            // bytes are NOT actually padded to match -- it's always the last section, so nothing
            // after it depends on the declared length being physically accurate.
            writeSection(out, "FLI1", false, sec ->
            {
                int scriptEntryLen = scripts.isEmpty() ? 8 : 6 + scripts.get(0).padding.length;
                writeU16(sec, scripts.size(), bigEndian);
                writeU16(sec, scriptEntryLen, bigEndian);
                writeU32(sec, fli1Unk0C, bigEndian);
                for (Script s : scripts)
                {
                    writeU32(sec, s.id, bigEndian);
                    writeU16(sec, s.index & 0xFFFF, bigEndian);
                    sec.writeBytes(s.padding);
                }
            });
        }

        byte[] result = out.toByteArray();
        patchU32(result, sectionCountPos, numSections, bigEndian);
        long computedTotalLen = result.length;
        while (computedTotalLen % 32 != 0) computedTotalLen++;
        patchU32(result, totalLenPos, declaredTotalLength != 0 ? declaredTotalLength : computedTotalLen, bigEndian);
        return result;
    }

    // --- section helpers ---------------------------------------------------------------------

    private interface SectionBody { void write(ByteArrayOutputStream sec); }

    // physicallyPad: true for INF1/DAT1/FLW1 (the section's own bytes are zero-padded to a 32-byte
    // multiple); false for FLI1 (only the declared length in its header is rounded up -- see save()).
    private void writeSection(ByteArrayOutputStream out, String magic, boolean physicallyPad, SectionBody body)
    {
        ByteArrayOutputStream sec = new ByteArrayOutputStream();
        sec.writeBytes(magic.getBytes(StandardCharsets.US_ASCII));
        writeU32(sec, 0, bigEndian); // patched below
        body.write(sec);
        byte[] bytes = sec.toByteArray();
        int declaredLen = bytes.length;
        while (declaredLen % 32 != 0) declaredLen++;
        if (physicallyPad && declaredLen != bytes.length)
            bytes = Arrays.copyOf(bytes, declaredLen);
        patchU32(bytes, 4, declaredLen, bigEndian);
        out.writeBytes(bytes);
    }

    private String charsetName()
    {
        String base = ENCODING_NAMES[encoding];
        if ("UTF-16".equalsIgnoreCase(base))
            return bigEndian ? "UTF-16BE" : "UTF-16LE";
        return base;
    }

    private static boolean regionEquals(byte[] data, int off, byte[] pattern)
    {
        if (off + pattern.length > data.length) return false;
        for (int i = 0; i < pattern.length; i++)
            if (data[off + i] != pattern[i]) return false;
        return true;
    }

    private static String decodeString(byte[] data, int from, int to, String charsetName)
    {
        try
        {
            return new String(data, from, to - from, charsetName);
        }
        catch (UnsupportedEncodingException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static byte[] encodeChar(char c, String charsetName)
    {
        return Charset.forName(charsetName).encode(String.valueOf(c)).array();
    }

    // --- little/big-endian primitives (data carries its own endianness flag, so these take it as a param) ---

    private static long u32(byte[] d, int off, boolean be)
    {
        if (be)
            return ((d[off] & 0xFFL) << 24) | ((d[off + 1] & 0xFFL) << 16) | ((d[off + 2] & 0xFFL) << 8) | (d[off + 3] & 0xFFL);
        return (d[off] & 0xFFL) | ((d[off + 1] & 0xFFL) << 8) | ((d[off + 2] & 0xFFL) << 16) | ((d[off + 3] & 0xFFL) << 24);
    }

    private static long u16(byte[] d, int off, boolean be)
    {
        if (be)
            return ((d[off] & 0xFFL) << 8) | (d[off + 1] & 0xFFL);
        return (d[off] & 0xFFL) | ((d[off + 1] & 0xFFL) << 8);
    }

    private static void writeU32(ByteArrayOutputStream out, long v, boolean be)
    {
        if (be)
        {
            out.write((int) ((v >> 24) & 0xFF)); out.write((int) ((v >> 16) & 0xFF));
            out.write((int) ((v >> 8) & 0xFF)); out.write((int) (v & 0xFF));
        }
        else
        {
            out.write((int) (v & 0xFF)); out.write((int) ((v >> 8) & 0xFF));
            out.write((int) ((v >> 16) & 0xFF)); out.write((int) ((v >> 24) & 0xFF));
        }
    }

    private static void writeU16(ByteArrayOutputStream out, int v, boolean be)
    {
        if (be) { out.write((v >> 8) & 0xFF); out.write(v & 0xFF); }
        else { out.write(v & 0xFF); out.write((v >> 8) & 0xFF); }
    }

    private static void patchU32(byte[] data, int off, long v, boolean be)
    {
        if (be)
        {
            data[off] = (byte) (v >> 24); data[off + 1] = (byte) (v >> 16);
            data[off + 2] = (byte) (v >> 8); data[off + 3] = (byte) v;
        }
        else
        {
            data[off] = (byte) v; data[off + 1] = (byte) (v >> 8);
            data[off + 2] = (byte) (v >> 16); data[off + 3] = (byte) (v >> 24);
        }
    }

    // --- accessors -----------------------------------------------------------------------------

    /** @return the messages in this file, in INF1 order. */
    public List<Message> getMessages() { return messages; }

    /** @return true if this file's multi-byte fields are big-endian (every known title except Super Princess Peach is little-endian). */
    public boolean isBigEndian() { return bigEndian; }

    /** Sets whether this file's multi-byte fields are written big-endian. @param bigEndian the byte order */
    public void setBigEndian(boolean bigEndian) { this.bigEndian = bigEndian; }

    /** @return the text encoding: 1 = Windows-1252, 2 = UTF-16, 3 = Shift-JIS, 4 = UTF-8. */
    public int getEncoding() { return encoding; }

    /**
     * Sets the text encoding used to serialise message strings.
     * @param encoding one of 1 = Windows-1252, 2 = UTF-16, 3 = Shift-JIS, 4 = UTF-8
     * @throws IllegalArgumentException if {@code encoding} is not one of the four known selectors
     */
    public void setEncoding(int encoding)
    {
        if (encoding < 1 || encoding > 4)
            throw new IllegalArgumentException("encoding must be 1 (cp1252), 2 (UTF-16), 3 (Shift-JIS), or 4 (UTF-8)");
        this.encoding = encoding;
    }

    /** @return true if this file carries the (DS Zelda-specific) FLW1 script-flow section. */
    public boolean hasFlw1() { return hasFlw1; }

    /** @return true if this file carries the (DS Zelda-specific) FLI1 script-index section. */
    public boolean hasFli1() { return hasFli1; }

    /**
     * A single message: a list of interleaved text runs and {@link Escape} sequences, plus a
     * game-specific opaque "info" record from its INF1 entry.
     */
    public static class Message
    {
        private byte[] info;
        private List<Object> parts; // String or Escape
        private final boolean isNull;

        /**
         * @param info the message's opaque per-entry info bytes (speaker id, sound effect, etc. -- game-specific)
         * @param parts the message content: a list of {@code String} and {@link Escape} objects, in order
         * @param isNull true if this message has no text at all (distinct from an empty string)
         */
        public Message(byte[] info, List<Object> parts, boolean isNull)
        {
            this.info = info;
            this.parts = parts;
            this.isNull = isNull;
        }

        byte[] save(String charsetName)
        {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Object part : parts)
            {
                if (part instanceof String)
                {
                    String s = (String) part;
                    if (s.indexOf('\0') >= 0 || s.indexOf('\u001A') >= 0)
                        throw new RuntimeException("A BMG message string may not contain a NUL or 0x1A character directly.");
                    out.writeBytes(Charset.forName(charsetName).encode(s).array());
                }
                else
                {
                    Escape esc = (Escape) part;
                    byte[] start = encodeChar('\u001A', charsetName);
                    int escLen = esc.data.length + 2 + start.length;
                    // The escape is length-prefixed by a single byte, so escLen must fit 0..255. Parsed
                    // escapes always fit (their length came from this same byte); this can only be exceeded
                    // by an over-long Escape built through the API, or by re-encoding under a wider 0x1A
                    // marker after a setEncoding() change. Fail loudly rather than let out.write() silently
                    // truncate the prefix and corrupt the file.
                    if (escLen > 0xFF)
                        throw new RuntimeException("BMG escape too long to encode: " + escLen
                                + " bytes (max 255) under the current encoding; shorten the escape data.");
                    out.writeBytes(start);
                    out.write(escLen);
                    out.write(esc.type);
                    out.writeBytes(esc.data);
                }
            }
            out.writeBytes(encodeChar('\0', charsetName));
            return out.toByteArray();
        }

        /** @return this message's opaque per-entry info bytes. */
        public byte[] getInfo() { return info; }
        /** @return the message content: a list of {@code String} and {@link Escape} objects, in order. */
        public List<Object> getParts() { return parts; }
        /** @return true if this message has no text at all (distinct from an empty string). */
        public boolean isNull() { return isNull; }

        /**
         * Replaces this message's opaque per-entry info bytes (speaker id, sound effect, etc.). The
         * replacement must be the same length as the current info, since every message in a BMG shares
         * one fixed INF1 entry size; {@link BinaryMessage#save()} enforces that too.
         * @param info the new info bytes
         * @throws IllegalArgumentException if {@code info} is a different length than the current info
         */
        public void setInfo(byte[] info)
        {
            if (info == null || info.length != this.info.length)
                throw new IllegalArgumentException("info must be " + this.info.length + " bytes to preserve the INF1 entry size");
            this.info = info.clone();
        }

        /**
         * Replaces this message's content. Each part must be a {@code String} or an {@link Escape}; the
         * new text is re-encoded into DAT1 by {@link BinaryMessage#save()} (message offsets are rebuilt,
         * so a length change is fine).
         * @param parts the new content, a list of {@code String} and {@link Escape} objects in order
         * @throws IllegalArgumentException if any element is neither a {@code String} nor an {@link Escape}
         */
        public void setParts(List<Object> parts)
        {
            for (Object p : parts)
                if (!(p instanceof String) && !(p instanceof Escape))
                    throw new IllegalArgumentException("a message part must be a String or an Escape, got " + p);
            this.parts = new ArrayList<>(parts);
        }

        /**
         * Replaces this message's content with a single run of plain text (a convenience over
         * {@link #setParts}). Discards any embedded escapes.
         * @param text the new text
         */
        public void setText(String text)
        {
            List<Object> p = new ArrayList<>();
            p.add(text);
            this.parts = p;
        }

        @Override
        public String toString()
        {
            StringBuilder sb = new StringBuilder();
            for (Object p : parts) sb.append(p instanceof String ? (String) p : p.toString());
            return sb.toString();
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) return true;
            if (!(o instanceof Message)) return false;
            Message message = (Message) o;
            return isNull == message.isNull && Arrays.equals(info, message.info) && parts.equals(message.parts);
        }

        @Override
        public int hashCode()
        {
            int result = Objects.hash(isNull, parts);
            return 31 * result + Arrays.hashCode(info);
        }

        /** An {@code 0x1A} escape sequence embedded in a message: an opaque {@code (type, data)} pair. */
        public static class Escape
        {
            private int type;
            private byte[] data;

            /** @param type the escape's 1-byte type selector @param data the escape's raw parameter bytes */
            public Escape(int type, byte[] data)
            {
                this.type = type;
                this.data = data;
            }

            /** @return the escape's 1-byte type selector */
            public int getType() { return type; }
            /** @return the escape's raw parameter bytes */
            public byte[] getData() { return data; }

            /** Sets the escape's 1-byte type selector. @param type 0..255 */
            public void setType(int type) { this.type = type & 0xFF; }
            /**
             * Sets the escape's raw parameter bytes. The whole escape is length-prefixed by a single
             * byte holding {@code data.length + 2 + (1..2 for the 0x1A marker)}, so {@code data} may be at
             * most 251 bytes (safe for every encoding, including UTF-16's 2-byte marker).
             * @param data the new parameter bytes
             * @throws IllegalArgumentException if {@code data} is too long to encode
             */
            public void setData(byte[] data)
            {
                if (data == null || data.length > 251)
                    throw new IllegalArgumentException("escape data must be at most 251 bytes");
                this.data = data.clone();
            }

            @Override
            public boolean equals(Object o)
            {
                if (this == o) return true;
                if (!(o instanceof Escape)) return false;
                Escape escape = (Escape) o;
                return type == escape.type && Arrays.equals(data, escape.data);
            }

            @Override
            public int hashCode() { return 31 * type + Arrays.hashCode(data); }

            @Override
            public String toString() { return "[" + type + ":" + bytesToHex(data) + "]"; }

            private static String bytesToHex(byte[] b)
            {
                StringBuilder sb = new StringBuilder();
                for (byte x : b) sb.append(String.format("%02x", x));
                return sb.toString();
            }
        }
    }

    /** A single FLI1 entry: a script id paired with the FLW1 instruction index it starts at. */
    public static class Script
    {
        private final long id;
        private final short index;
        private final byte[] padding;

        Script(long id, short index, byte[] padding)
        {
            this.id = id;
            this.index = index;
            this.padding = padding;
        }

        /** @return this script's id */
        public long getId() { return id; }
        /** @return the FLW1 instruction index this script starts at */
        public short getIndex() { return index; }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) return true;
            if (!(o instanceof Script)) return false;
            Script script = (Script) o;
            return id == script.id && index == script.index && Arrays.equals(padding, script.padding);
        }

        @Override
        public int hashCode() { return Objects.hash(id, index) * 31 + Arrays.hashCode(padding); }
    }
}
