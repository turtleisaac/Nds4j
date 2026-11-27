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

import io.github.turtleisaac.nds4j.framework.MemBuf;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Imports a Standard MIDI File back into an SSEQ ({@link Sequence}) — the return leg of
 * {@link SequenceMidi}. Together they give a MIDI ⇄ SSEQ interchange: edit a song in any DAW, import it,
 * and get a valid SSEQ; or round-trip an existing SSEQ through MIDI to validate the note/timing decode.
 * <p>
 * The emitted SSEQ uses the straightforward encoding every channel maps to its own track, tracks run in
 * <b>note-wait-off</b> mode with explicit rests carrying the timing, and each MIDI note becomes one SSEQ
 * note with an encoded gate duration (from its note-off). Track 0 holds the tempo map and the
 * {@code NODEDESC}-style {@code 0x93} open-track commands that spawn the rest. Pure JVM, Java-8-clean
 * (CheerpJ-safe). Parses SMF directly (no {@code javax.sound}).
 */
public final class MidiSequence
{
    private MidiSequence()
    {
    }

    // -------------------------------------------------------------- MIDI model

    private static final class MEvent
    {
        long tick;
        int status;
        int d1, d2;
        int tempoBpm = -1;

        MEvent(long tick, int status, int d1, int d2)
        {
            this.tick = tick;
            this.status = status;
            this.d1 = d1;
            this.d2 = d2;
        }
    }

    /** @return SSEQ file bytes equivalent to the given Standard MIDI File. */
    public static byte[] toSseq(byte[] smf)
    {
        Parsed p = parse(smf);
        return buildSseq(p);
    }

    /** @return a ready-to-use {@link Sequence} from a Standard MIDI File. */
    public static Sequence toSequence(byte[] smf)
    {
        return Sequence.fromBytes(toSseq(smf));
    }

    // ------------------------------------------------------------- SMF parsing

    private static final class Parsed
    {
        int ppqn;
        final List<MEvent> tempo = new ArrayList<>();
        // per channel (0..15): events sorted by tick
        @SuppressWarnings("unchecked")
        final List<MEvent>[] channels = new List[16];

        Parsed()
        {
            for (int i = 0; i < 16; i++)
                channels[i] = new ArrayList<>();
        }
    }

    private static Parsed parse(byte[] d)
    {
        Parsed p = new Parsed();
        int pos = midiHeader(d, p);

        while (pos + 8 <= d.length)
            pos = trackOrSkip(d, p, pos);

        sortParsed(p);

        return p;
    }

    /** @param d the MIDI file @param p the parsed result @return the first byte after the header */
    private static int midiHeader(byte[] d, Parsed p)
    {
        expect(d, 0, "MThd");
        int pos = 4;
        int hdrLen = (int) MemBuf.uint32be(d, pos);
        pos += 4;
        int headerEnd = pos + hdrLen;
        p.ppqn = MemBuf.uint16be(d, pos + 4);

        return headerEnd;
    }

    /** @param d the MIDI file @param p the parsed result @param pos a candidate track @return the next position */
    private static int trackOrSkip(byte[] d, Parsed p, int pos)
    {
        if (!isStr(d, pos, "MTrk"))
            return pos + 1;

        return readTrack(d, p, pos + 4);
    }

    /** @param d the MIDI file @param p the parsed result @param pos the track length @return the first byte after the track */
    private static int readTrack(byte[] d, Parsed p, int pos)
    {
        int len = (int) MemBuf.uint32be(d, pos);
        pos += 4;
        int end = pos + len;
        readEvents(d, p, pos, end, 0, 0);

        return end;
    }

    /** @param d the MIDI file @param p the parsed result @param pos the next event @param end the track end @param tick the current tick @param running the running status @return the track end */
    private static int readEvents(byte[] d, Parsed p, int pos, int end, long tick, int running)
    {
        if (pos >= end)
            return pos;

        long[] dt = readVar(d, pos);
        tick += dt[0];

        return oneEvent(d, p, (int) dt[1], end, tick, running);
    }

    /** @param d the MIDI file @param p the parsed result @param pos the status byte @param end the track end @param tick the event tick @param running the running status */
    private static int oneEvent(byte[] d, Parsed p, int pos, int end, long tick, int running)
    {
        int status = d[pos] & 0xFF;

        if (status < 0x80)
            return dispatch(d, p, pos, end, tick, running, running);

        return dispatch(d, p, pos + 1, end, tick, status, status);
    }

    /** @param status the event status, already resolved */
    private static int dispatch(byte[] d, Parsed p, int pos, int end, long tick, int running, int status)
    {
        if (status == 0xFF)
            return meta(d, p, pos, end, tick, running);

        return sysexOrChannel(d, p, pos, end, tick, running, status);
    }

    /** @param status the event status, already resolved */
    private static int sysexOrChannel(byte[] d, Parsed p, int pos, int end, long tick, int running, int status)
    {
        if (systemExclusive(status))
            return sysex(d, p, pos, end, tick, running);

        return channel(d, p, pos, end, tick, running, status);
    }

    /** @param status a MIDI status byte @return whether it is a sysex event */
    private static boolean systemExclusive(int status)
    {
        return (status == 0xF0) | (status == 0xF7);
    }

    /** @param pos the meta type */
    private static int meta(byte[] d, Parsed p, int pos, int end, long tick, int running)
    {
        int type = d[pos++] & 0xFF;
        long[] ml = readVar(d, pos);
        int mlen = (int) ml[0];
        pos = (int) ml[1];

        if (tempoMeta(type, mlen))
            recordTempo(d, p, pos, tick);

        return readEvents(d, p, pos + mlen, end, tick, running);
    }

    /** @param type the meta type @param length the meta payload length @return whether this meta event is a tempo */
    private static boolean tempoMeta(int type, int length)
    {
        if (type != 0x51)
            return false;

        return length == 3;
    }

    /** @param pos the three tempo bytes */
    private static void recordTempo(byte[] d, Parsed p, int pos, long tick)
    {
        int us = ((d[pos] & 0xFF) << 16) | ((d[pos + 1] & 0xFF) << 8) | (d[pos + 2] & 0xFF);
        MEvent te = new MEvent(tick, 0xFF, 0, 0);
        te.tempoBpm = (int) Math.round(60000000.0 / us);
        p.tempo.add(te);
    }

    /** @param pos the sysex length */
    private static int sysex(byte[] d, Parsed p, int pos, int end, long tick, int running)
    {
        long[] sl = readVar(d, pos);
        pos = (int) sl[1] + (int) sl[0];

        return readEvents(d, p, pos, end, tick, running);
    }

    /** @param status a channel voice status */
    private static int channel(byte[] d, Parsed p, int pos, int end, long tick, int running, int status)
    {
        int hi = status & 0xF0;
        int ch = status & 0x0F;
        int d1 = d[pos++] & 0xFF;
        int d2 = secondData(d, pos, hi);
        pos = advanced(pos, hi);
        p.channels[ch].add(new MEvent(tick, hi, d1, d2));

        return readEvents(d, p, pos, end, tick, running);
    }

    /** @param pos the second data byte, when the status has one @param hi the status high nibble @return that byte, or 0 for program and channel pressure */
    private static int secondData(byte[] d, int pos, int hi)
    {
        if (oneDataByte(hi))
            return 0;

        return d[pos] & 0xFF;
    }

    /** @param pos the second data byte @param hi the status high nibble @return {@code pos} plus one when the status consumed that byte */
    private static int advanced(int pos, int hi)
    {
        int extra = oneDataByte(hi) ? 0 : 1;

        return pos + extra;
    }

    /** @param hi the status high nibble @return whether the event has only one data byte */
    private static boolean oneDataByte(int hi)
    {
        // Program change and channel pressure are the two status values with one data byte.
        return (hi ^ 0xC0) * (hi ^ 0xD0) == 0;
    }

    /** @param p the parsed result */
    private static void sortParsed(Parsed p)
    {
        for (int i = 0; i < 16; i++)
            Collections.sort(p.channels[i], new Comparator<MEvent>() {
                public int compare(MEvent a, MEvent b)
                {
                    return Long.compare(a.tick, b.tick);
                }
            });

        Collections.sort(p.tempo, new Comparator<MEvent>() {
            public int compare(MEvent a, MEvent b)
            {
                return Long.compare(a.tick, b.tick);
            }
        });
    }

    // --------------------------------------------------------------- SSEQ build

    private static byte[] buildSseq(Parsed p)
    {
        // which channels are used
        List<Integer> used = new ArrayList<>();
        for (int c = 0; c < 16; c++)
            if (!p.channels[c].isEmpty())
                used.add(c);
        if (used.isEmpty())
            used.add(0);

        // build each non-first track's bytecode
        // track 0 = the first used channel; it also carries tempo + the 0x93 open-track table
        int mainCh = used.get(0);
        List<byte[]> subTracks = new ArrayList<>();
        List<Integer> subChannels = new ArrayList<>();
        for (int i = 1; i < used.size(); i++)
        {
            subChannels.add(used.get(i));
            subTracks.add(buildTrackEvents(p.channels[used.get(i)], null));
        }

        // track 0 body: tempo map (only track 0 gets 0xE1) + main-channel events
        byte[] track0Events = buildTrackEvents(p.channels[mainCh], p.tempo);

        // assemble the event stream with a 2-pass offset fixup for 0x93
        ByteArrayOutputStream ev = new ByteArrayOutputStream();
        if (!subTracks.isEmpty())
        {
            // FE alloc-tracks bitmask (bit set per used track index)
            int mask = 0;
            for (int i = 0; i < used.size(); i++)
                mask |= (1 << i);
            ev.write(0xFE);
            ev.write(mask & 0xFF);
            ev.write((mask >> 8) & 0xFF);
        }
        // placeholder for 0x93 commands; we need offsets, so first compute track0 header size
        // 0x93 command = 5 bytes each (op, trackNo, u24 offset)
        int headerLen = (subTracks.isEmpty() ? 0 : 3) + subTracks.size() * 5;
        int track0Start = headerLen;
        int cursor = track0Start + track0Events.length + 1; // +1 for 0xFF end of track0
        int[] subOffsets = new int[subTracks.size()];
        for (int i = 0; i < subTracks.size(); i++)
        {
            subOffsets[i] = cursor;
            cursor += subTracks.get(i).length + 1;
        }

        // now emit 0x93 header (trackNo = i+1, offset)
        for (int i = 0; i < subTracks.size(); i++)
        {
            ev.write(0x93);
            ev.write(i + 1);
            ev.write(subOffsets[i] & 0xFF);
            ev.write((subOffsets[i] >> 8) & 0xFF);
            ev.write((subOffsets[i] >> 16) & 0xFF);
        }
        writeAll(ev, track0Events);
        ev.write(0xFF);
        for (byte[] st : subTracks)
        {
            writeAll(ev, st);
            ev.write(0xFF);
        }

        byte[] events = ev.toByteArray();
        return wrapSseq(events);
    }

    /** Emit one track's SSEQ command stream (note-wait-off; rests carry timing). */
    private static byte[] buildTrackEvents(List<MEvent> chEvents, List<MEvent> tempo)
    {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xC7);
        o.write(0x00); // note-wait off

        // merge tempo (track 0 only) with channel events by tick
        List<MEvent> merged = new ArrayList<>(chEvents);
        if (tempo != null)
            merged.addAll(tempo);
        Collections.sort(merged, new Comparator<MEvent>() {
            public int compare(MEvent a, MEvent b)
            {
                return Long.compare(a.tick, b.tick);
            }
        });

        long cur = 0;
        // to encode a note's gate we need its note-off; index note-ons to their offs
        for (int i = 0; i < merged.size(); i++)
        {
            MEvent e = merged.get(i);
            if (e.status == 0x90 && e.d2 == 0)
                continue; // note-on vel0 == note-off; skip (handled as duration)
            if (e.status == 0x80)
                continue; // note-off consumed by its note-on
            long delta = e.tick - cur;
            if (delta > 0)
            {
                o.write(0x80);
                writeVar(o, delta);
                cur = e.tick;
            }

            if (e.status == 0x90) // note on
            {
                long dur = findNoteOff(merged, i, e.d1) - e.tick;
                if (dur < 1)
                    dur = 1;
                o.write(e.d1 & 0x7F);
                o.write(e.d2 & 0x7F);
                writeVar(o, dur);
            }
            else if (e.status == 0xC0)
            {
                o.write(0x81);
                writeVar(o, e.d1 & 0x7F);
            } // program
            else if (e.status == 0xB0) // control change
            {
                if (e.d1 == 7)
                {
                    o.write(0xC1);
                    o.write(e.d2 & 0x7F);
                }
                else if (e.d1 == 10)
                {
                    o.write(0xC0);
                    o.write(e.d2 & 0x7F);
                }
                else if (e.d1 == 11)
                {
                    o.write(0xC2);
                    o.write(e.d2 & 0x7F);
                }
            }
            else if (e.status == 0xE0) // pitch bend: 14-bit MIDI (centre 8192) -> DS signed -128..127
            {
                int val = (e.d1 & 0x7F) | ((e.d2 & 0x7F) << 7); // 0..16383
                int signed = Math.round((val - 8192) / 64.0f);
                if (signed < -128)
                    signed = -128;
                if (signed > 127)
                    signed = 127;
                o.write(0xC4);
                o.write(signed & 0xFF);
            }
            else if (e.status == 0xFF && e.tempoBpm > 0)
            {
                o.write(0xE1);
                o.write(e.tempoBpm & 0xFF);
                o.write((e.tempoBpm >> 8) & 0xFF);
            }
        }
        return o.toByteArray();
    }

    private static long findNoteOff(List<MEvent> events, int from, int note)
    {
        for (int j = from + 1; j < events.size(); j++)
        {
            MEvent e = events.get(j);
            if ((e.status == 0x80 && e.d1 == note) || (e.status == 0x90 && e.d1 == note && e.d2 == 0))
                return e.tick;
        }
        return events.get(events.size() - 1).tick;
    }

    private static byte[] wrapSseq(byte[] events)
    {
        int fileSize = 0x1C + events.length;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeStr(o, "SSEQ");
        o.write(0xFF);
        o.write(0xFE); // BOM 0xFEFF
        o.write(0x00);
        o.write(0x01); // version
        writeU32le(o, fileSize);
        o.write(0x10);
        o.write(0x00); // header size 0x10
        o.write(0x01);
        o.write(0x00); // 1 block
        writeStr(o, "DATA");
        writeU32le(o, fileSize - 0x10); // DATA block size
        writeU32le(o, 0x1C); // event data offset
        writeAll(o, events);
        return o.toByteArray();
    }

    // ------------------------------------------------------------------ helpers

    private static void writeVar(ByteArrayOutputStream o, long v)
    {
        if (v < 0)
            v = 0;
        byte[] buf = new byte[5];
        int i = 0;
        buf[i++] = (byte) (v & 0x7F);
        while ((v >>= 7) > 0)
            buf[i++] = (byte) ((v & 0x7F) | 0x80);
        for (int j = i - 1; j >= 0; j--)
            o.write(buf[j] & 0xFF);
    }
    private static void writeAll(ByteArrayOutputStream o, byte[] b)
    {
        o.write(b, 0, b.length);
    }
    private static void writeStr(ByteArrayOutputStream o, String s)
    {
        for (int i = 0; i < s.length(); i++)
            o.write(s.charAt(i));
    }
    private static void writeU32le(ByteArrayOutputStream o, int v)
    {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 24) & 0xFF);
    }

    private static long[] readVar(byte[] d, int pos)
    {
        long v = 0;
        int b;
        do
        {
            b = d[pos++] & 0xFF;
            v = (v << 7) | (b & 0x7F);
        }
        while ((b & 0x80) != 0);
        return new long[] { v, pos };
    }
    private static boolean isStr(byte[] d, int o, String s)
    {
        if (o + s.length() > d.length)
            return false;
        for (int i = 0; i < s.length(); i++)
            if ((d[o + i] & 0xFF) != s.charAt(i))
                return false;
        return true;
    }
    private static void expect(byte[] d, int o, String s)
    {
        if (!isStr(d, o, s))
            throw new IllegalArgumentException("Not a MIDI file (expected " + s + ")");
    }
}
