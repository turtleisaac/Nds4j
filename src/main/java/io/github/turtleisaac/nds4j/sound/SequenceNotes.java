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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Walks an SSEQ's event bytecode and collects the sounding notes: track, tick, duration, key,
 * velocity, and the program that was active. This is the data a UI needs to draw a note track
 * (NitroViewer's piano-roll); it is not a renderer.
 * <p>
 * Timing matches {@link SequenceMidi} / {@link SequencePlayer}: 48 ticks per quarter, note-wait
 * honoured, counted loops unrolled (once, for a finite pass), infinite jumps ({@code 0x94}) stop
 * the track so the result is one play-through up to the loop point. Track-open / jump / call
 * offsets are indices into the event stream ({@link Sequence#getEventData()}), same as the player.
 */
public final class SequenceNotes
{
    private SequenceNotes() {}

    private static final int MAX_TICKS = 48 * 4 * 64; // ~64 bars, keeps a UI pass bounded

    public static final class Note
    {
        public final int track;
        public final int tick;
        public final int duration;
        public final int key;
        public final int velocity;
        public final int program;

        public Note(int track, int tick, int duration, int key, int velocity, int program)
        {
            this.track = track;
            this.tick = tick;
            this.duration = duration;
            this.key = key;
            this.velocity = velocity;
            this.program = program;
        }
    }

    public static final class Result
    {
        public final List<Note> notes;
        public final int ticks;
        public final int tempo;
        public final int trackCount;
        /** Tick the {@code 0x94} jump returns to, or -1 if the sequence has no loop. */
        public final int loopStartTick;
        /** Tick of the first playthrough's {@code 0x94} jump, or -1 if none. */
        public final int loopEndTick;

        public Result(List<Note> notes, int ticks, int tempo, int trackCount,
                      int loopStartTick, int loopEndTick)
        {
            this.notes = notes;
            this.ticks = ticks;
            this.tempo = tempo;
            this.trackCount = trackCount;
            this.loopStartTick = loopStartTick;
            this.loopEndTick = loopEndTick;
        }
    }

    public static Result extract(Sequence sequence)
    {
        Sim sim = new Sim(sequence.getEventData());
        sim.run();
        return sim.result();
    }

    private static final class TrackVM
    {
        boolean active;
        int pc, wait, program;
        boolean noteWait = true;
        int transpose;
        final int[] callStack = new int[8];
        int callDepth;
        final int[] loopPc = new int[8];
        final int[] loopCount = new int[8];
        int loopDepth;
    }

    private static final class Sim
    {
        final byte[] ev;
        final TrackVM[] tracks = new TrackVM[16];
        final List<Note> notes = new ArrayList<Note>();
        int tempo = 120;
        int tick;
        int lastNoteTick;
        int loopStartTick = -1;
        int loopEndTick = -1;
        final HashMap<Integer, Integer> firstTickAtPc = new HashMap<Integer, Integer>();

        Sim(byte[] ev)
        {
            this.ev = ev;
            for (int i = 0; i < 16; i++) tracks[i] = new TrackVM();
            tracks[0].active = true;
            tracks[0].pc = 0;
        }

        Result result()
        {
            int used = 1;
            for (int i = 0; i < 16; i++) if (tracks[i].pc != 0 || i == 0) used = i + 1;
            // a track that produced notes counts even if pc stayed 0 (shouldn't)
            boolean[] saw = new boolean[16];
            for (int i = 0; i < notes.size(); i++) saw[notes.get(i).track] = true;
            for (int i = 0; i < 16; i++) if (saw[i] && i + 1 > used) used = i + 1;
            int end = Math.max(lastNoteTick, tick);
            int ls = loopStartTick, le = loopEndTick;
            if (le >= 0 && le <= ls) { ls = -1; le = -1; }
            return new Result(notes, end, tempo, used, ls, le);
        }

        void run()
        {
            while (anyActive() && tick < MAX_TICKS)
            {
                for (int t = 0; t < 16; t++)
                {
                    TrackVM tr = tracks[t];
                    if (!tr.active || tr.wait > 0) continue;
                    int guard = 0;
                    while (tr.active && tr.wait == 0 && guard++ < 100000)
                        exec(t, tr);
                }
                tick++;
                for (int t = 0; t < 16; t++)
                    if (tracks[t].active && tracks[t].wait > 0) tracks[t].wait--;
            }
        }

        boolean anyActive()
        {
            for (int i = 0; i < 16; i++) if (tracks[i].active) return true;
            return false;
        }

        void exec(int id, TrackVM tr)
        {
            if (tr.pc < 0 || tr.pc >= ev.length) { tr.active = false; return; }
            // A truncated/malformed track can run a multi-byte read (note event, readVar/U16/U24,
            // skipPrefixed) off the end of ev; this is a best-effort piano-roll helper, so stop the
            // track gracefully rather than propagate an AIOOBE.
            try { execUnchecked(id, tr); }
            catch (ArrayIndexOutOfBoundsException e) { tr.active = false; }
        }

        void execUnchecked(int id, TrackVM tr)
        {
            if (!firstTickAtPc.containsKey(Integer.valueOf(tr.pc)))
                firstTickAtPc.put(Integer.valueOf(tr.pc), Integer.valueOf(tick));
            int op = ev[tr.pc++] & 0xFF;
            if (op < 0x80)
            {
                int vel = ev[tr.pc++] & 0xFF;
                int dur = readVar(tr);
                int note = op + tr.transpose;
                if (note < 0) note = 0;
                if (note > 127) note = 127;
                int gate = dur < 1 ? 1 : dur;
                notes.add(new Note(id, tick, gate, note, vel & 0x7F, tr.program));
                lastNoteTick = tick + gate;
                if (tr.noteWait) tr.wait = dur;
                return;
            }
            switch (op)
            {
                case 0x80: tr.wait = readVar(tr); break;
                case 0x81: tr.program = readVar(tr) & 0x7F; break;
                case 0x93:
                {
                    int tn = ev[tr.pc++] & 0xFF;
                    int off = readU24(tr);
                    if (tn < 16)
                    {
                        tracks[tn].active = true;
                        tracks[tn].pc = off;
                    }
                    break;
                }
                case 0x94:
                {
                    int off = readU24(tr);
                    Integer start = firstTickAtPc.get(Integer.valueOf(off));
                    int sf = start != null ? start.intValue() : 0;
                    if (loopStartTick < 0 || sf < loopStartTick) loopStartTick = sf;
                    if (tick > loopEndTick) loopEndTick = tick;
                    tr.active = false; // one play-through; the player wraps using loopStart/loopEnd
                    break;
                }
                case 0x95:
                {
                    int off = readU24(tr);
                    if (tr.callDepth < tr.callStack.length) tr.callStack[tr.callDepth++] = tr.pc;
                    tr.pc = off;
                    break;
                }
                case 0xA0: case 0xA1: case 0xA2: skipPrefixed(tr, op); break;
                case 0xB0: case 0xB1: case 0xB2: case 0xB3: case 0xB4: case 0xB5:
                case 0xB6: case 0xB7: case 0xB8: case 0xB9: case 0xBA: case 0xBB:
                case 0xBC: case 0xBD:
                    tr.pc += 1; readS16(tr); break;
                case 0xC0: case 0xC1: case 0xC2: case 0xC4: case 0xC5: case 0xC6:
                case 0xC8: case 0xC9: case 0xCA: case 0xCB: case 0xCC: case 0xCD:
                case 0xCE: case 0xCF:
                case 0xD0: case 0xD1: case 0xD2: case 0xD3: case 0xD5: case 0xD6:
                    tr.pc++; break;
                case 0xC3: tr.transpose = (byte) ev[tr.pc++]; break;
                case 0xC7: tr.noteWait = (ev[tr.pc++] & 0xFF) != 0; break;
                case 0xD4:
                {
                    int count = ev[tr.pc++] & 0xFF;
                    if (tr.loopDepth < tr.loopPc.length)
                    {
                        tr.loopPc[tr.loopDepth] = tr.pc;
                        tr.loopCount[tr.loopDepth] = count;
                        tr.loopDepth++;
                    }
                    break;
                }
                case 0xE0: readS16(tr); break;
                case 0xE1: tempo = Math.max(1, readU16(tr)); break;
                case 0xE3: readS16(tr); break;
                case 0xFC:
                    if (tr.loopDepth > 0)
                    {
                        int d = tr.loopDepth - 1;
                        int c = tr.loopCount[d];
                        if (c == 0) tr.loopDepth--; // infinite: take once
                        else if (--tr.loopCount[d] > 0) tr.pc = tr.loopPc[d];
                        else tr.loopDepth--;
                    }
                    break;
                case 0xFD:
                    if (tr.callDepth > 0) tr.pc = tr.callStack[--tr.callDepth];
                    else tr.active = false;
                    break;
                case 0xFE: readU16(tr); break;
                case 0xFF: tr.active = false; break;
                default: tr.active = false; break;
            }
        }

        void skipPrefixed(TrackVM tr, int prefix)
        {
            int inner = ev[tr.pc++] & 0xFF;
            if (inner < 0x80) tr.pc++;
            switch (inner)
            {
                case 0x80: case 0x81: break;
                case 0x93: tr.pc++; readU24(tr); break;
                case 0x94: case 0x95: readU24(tr); break;
                case 0xFC: case 0xFD: case 0xFF: return;
                default: break;
            }
            if (prefix == 0xA0) { readS16(tr); readS16(tr); }
            else if (prefix == 0xA1) tr.pc++;
        }

        int readVar(TrackVM tr)
        {
            int v = 0, b;
            do { b = ev[tr.pc++] & 0xFF; v = (v << 7) | (b & 0x7F); } while ((b & 0x80) != 0);
            return v;
        }
        int readU16(TrackVM tr)
        {
            int v = (ev[tr.pc] & 0xFF) | ((ev[tr.pc + 1] & 0xFF) << 8);
            tr.pc += 2;
            return v;
        }
        int readS16(TrackVM tr) { return (short) readU16(tr); }
        int readU24(TrackVM tr)
        {
            int v = (ev[tr.pc] & 0xFF) | ((ev[tr.pc + 1] & 0xFF) << 8) | ((ev[tr.pc + 2] & 0xFF) << 16);
            tr.pc += 3;
            return v;
        }
    }
}
