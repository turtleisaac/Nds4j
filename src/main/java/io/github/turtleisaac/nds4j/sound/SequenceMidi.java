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

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Converts a {@link Sequence SSEQ} into a Standard MIDI File (SMF, format 1). SSEQ already shares MIDI's
 * model — 48 ticks per quarter note, note-on/off, program change, tempo, volume/pan/expression, pitch
 * bend — so the mapping is direct: this walks the SSEQ bytecode as a per-track virtual machine and emits
 * timed MIDI events, one MIDI track per SSEQ track.
 * <p>
 * The point is <b>validation</b>: a MIDI file plays in any DAW/synth at the sequence's true tempo and
 * pitches, independent of Nds4j's own {@link SequencePlayer software synth}, so it isolates
 * "did we decode the notes/timing right?" from "does the synth render right?". It is also the interchange
 * for round-tripping edits ({@link MidiSequence} imports it back to SSEQ).
 * <p>
 * SSEQ-only constructs that MIDI can't express (per-count loops {@code 0xD4/0xFC}, calls {@code 0x95},
 * whole-track jumps {@code 0x94}) are <b>unrolled</b> into a single linear pass (bounded), so the MIDI is
 * finite and audibly faithful for one play-through.
 */
public final class SequenceMidi
{
    private SequenceMidi() {}

    private static final int PPQN = 48;      // SSEQ and this MIDI both use 48 ticks/quarter
    private static final int MAX_TICKS = 60 * PPQN * 200; // safety cap (~ very long) for unrolling loops

    /** @return a Standard MIDI File (format 1) rendering of the sequence. */
    public static byte[] convert(Sequence sequence)
    {
        Sim sim = new Sim(sequence.getEventData());
        sim.run();
        return sim.writeSmf();
    }

    /**
     * A <b>DAW-friendly, one-way</b> MIDI export: one track per instrument (grouped by the program that
     * was active when each note played), each with a single program change and a track name, and no
     * mid-track program changes. This is what makes the file drop cleanly into Logic Pro (or any DAW),
     * where one MIDI track maps to one sampler instrument — unlike {@link #convert}, DAWs don't honour the
     * mid-track program changes a real SSEQ track uses.
     * <p>
     * This regroups events, so it is <b>not</b> the round-trippable form — use {@link #convert} for
     * MIDI&harr;SSEQ round-tripping.
     * @return a Standard MIDI File with one named track per program
     */
    public static byte[] convertForDaw(Sequence sequence)
    {
        Sim sim = new Sim(sequence.getEventData());
        sim.run();
        return sim.writeSmfPerInstrument();
    }

    // ------------------------------------------------------------- simulation

    private static final class Ev
    {
        final long tick; final byte[] data; int program = -1; // program active when emitted (for per-instrument grouping)
        Ev(long tick, byte[] data) { this.tick = tick; this.data = data; }
    }

    private static final class TrackVM
    {
        boolean active;
        int pc, wait, program, channel;
        boolean noteWait = true;   // 0xC7 toggles; when off, timing comes from explicit rests
        int transpose = 0;         // 0xC3; MIDI has no per-track transpose, so bake it into note numbers
        int bendRange = 2;         // semitones (0xC5); MIDI default is 2
        boolean rpnEmitted;        // whether the pitch-bend-sensitivity RPN was written yet
        final int[] callStack = new int[8]; int callDepth;
        final int[] loopPc = new int[8]; final int[] loopCount = new int[8]; int loopDepth;
        final List<Ev> events = new ArrayList<>();
    }

    private static final class Sim
    {
        final byte[] ev;
        final TrackVM[] tracks = new TrackVM[16];
        final List<Ev> tempoEvents = new ArrayList<>();
        long tick;

        Sim(byte[] ev)
        {
            this.ev = ev;
            for (int i = 0; i < 16; i++) { tracks[i] = new TrackVM(); tracks[i].channel = i; }
            tracks[0].active = true;
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
                for (TrackVM tr : tracks) if (tr.active && tr.wait > 0) tr.wait--;
            }
        }

        boolean anyActive() { for (TrackVM t : tracks) if (t.active) return true; return false; }

        void exec(int id, TrackVM tr)
        {
            if (tr.pc >= ev.length) { tr.active = false; return; }
            int op = ev[tr.pc++] & 0xFF;

            if (op < 0x80) // note
            {
                int vel = ev[tr.pc++] & 0xFF;
                int dur = readVar(tr);
                int note = op + tr.transpose;                    // MIDI has no track transpose; bake it in
                if (note < 0) note = 0; if (note > 127) note = 127;
                add(tr, tick, new byte[]{(byte) (0x90 | tr.channel), (byte) note, (byte) (vel & 0x7F)});
                add(tr, tick + Math.max(1, dur), new byte[]{(byte) (0x80 | tr.channel), (byte) note, 0});
                if (tr.noteWait) tr.wait = dur; // advance only in note-wait mode; else rests provide timing
                return;
            }
            switch (op)
            {
                case 0x80: tr.wait = readVar(tr); break;
                case 0x81: tr.program = readVar(tr) & 0x7F;
                    add(tr, tick, new byte[]{(byte) (0xC0 | tr.channel), (byte) tr.program}); break;
                case 0x93: { int tn = ev[tr.pc++] & 0xFF; int off = readU24(tr);
                    if (tn < 16) { tracks[tn].active = true; tracks[tn].pc = off; } break; }
                case 0x94: { int off = readU24(tr);
                    // whole-track loop: stop here so the pass is finite (loop point preserved by import side)
                    tr.active = false; break; }
                case 0x95: { int off = readU24(tr);
                    if (tr.callDepth < tr.callStack.length) tr.callStack[tr.callDepth++] = tr.pc; tr.pc = off; break; }
                case 0xA0: case 0xA1: case 0xA2: skipPrefixed(tr, op); break;
                case 0xB0: case 0xB1: case 0xB2: case 0xB3: case 0xB4: case 0xB5: case 0xB6: case 0xB7:
                case 0xB8: case 0xB9: case 0xBA: case 0xBB: case 0xBC: case 0xBD: tr.pc += 1; readS16(tr); break;
                case 0xC0: emitCC(tr, 10, ev[tr.pc++] & 0xFF); break;              // pan -> CC10
                case 0xC1: emitCC(tr, 7,  ev[tr.pc++] & 0xFF); break;              // volume -> CC7
                case 0xC2: emitCC(tr, 11, ev[tr.pc++] & 0xFF); break;             // main volume -> CC11
                case 0xC3: tr.transpose = (byte) ev[tr.pc++]; break;              // transpose -> baked into notes
                case 0xC4: emitBend(tr, (byte) ev[tr.pc++]); break;               // pitch bend
                case 0xC5: tr.bendRange = ev[tr.pc++] & 0xFF; emitBendRangeRpn(tr); break; // bend range -> RPN
                case 0xC6: tr.pc++; break;                                        // priority
                case 0xC7: tr.noteWait = (ev[tr.pc++] & 0xFF) != 0; break;        // note-wait toggle
                case 0xCA: emitCC(tr, 1, ev[tr.pc++] & 0x7F); break;              // modulation depth -> CC1
                case 0xCE: emitCC(tr, 65, (ev[tr.pc++] & 0xFF) != 0 ? 127 : 0); break; // portamento on/off -> CC65
                case 0xCF: emitCC(tr, 5, ev[tr.pc++] & 0x7F); break;              // portamento time -> CC5
                case 0xC8: case 0xC9: case 0xCB: case 0xCC: case 0xCD:            // tie/porta-ctrl/mod speed/type/range: ignored (as VGMTrans)
                case 0xD0: case 0xD1: case 0xD2: case 0xD3: case 0xD5: case 0xD6: tr.pc++; break;
                case 0xD4: { int count = ev[tr.pc++] & 0xFF;                      // loop start
                    if (tr.loopDepth < tr.loopPc.length) { tr.loopPc[tr.loopDepth] = tr.pc; tr.loopCount[tr.loopDepth] = count; tr.loopDepth++; } break; }
                case 0xE0: readS16(tr); break;
                case 0xE1: { int bpm = readU16(tr);                              // tempo
                    long usPerQuarter = 60000000L / Math.max(1, bpm);
                    tempoEvents.add(new Ev(tick, new byte[]{(byte) 0xFF, 0x51, 0x03,
                        (byte) (usPerQuarter >> 16), (byte) (usPerQuarter >> 8), (byte) usPerQuarter})); break; }
                case 0xE3: readS16(tr); break;
                case 0xFC: { if (tr.loopDepth > 0) { int d = tr.loopDepth - 1; int c = tr.loopCount[d];
                    if (c == 0) { tr.loopDepth--; }                              // infinite loop: take once, then exit (finite MIDI)
                    else if (--tr.loopCount[d] > 0) tr.pc = tr.loopPc[d];
                    else tr.loopDepth--; } break; }
                case 0xFD: if (tr.callDepth > 0) tr.pc = tr.callStack[--tr.callDepth]; else tr.active = false; break;
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

        /** Add an event to a track, tagged with the program active now (for per-instrument regrouping). */
        void add(TrackVM tr, long t, byte[] data) { Ev e = new Ev(t, data); e.program = tr.program; tr.events.add(e); }

        void emitCC(TrackVM tr, int cc, int v) { add(tr, tick, new byte[]{(byte) (0xB0 | tr.channel), (byte) cc, (byte) (v & 0x7F)}); }

        /** Tell the DAW this channel's pitch-bend range (RPN 0,0 = pitch-bend sensitivity, in semitones). */
        void emitBendRangeRpn(TrackVM tr)
        {
            emitCC(tr, 101, 0);                 // RPN MSB
            emitCC(tr, 100, 0);                 // RPN LSB -> pitch bend sensitivity
            emitCC(tr, 6, tr.bendRange);        // data entry MSB = semitones
            emitCC(tr, 38, 0);                  // data entry LSB = cents
            tr.rpnEmitted = true;
        }

        void emitBend(TrackVM tr, int signed)
        {
            if (!tr.rpnEmitted) emitBendRangeRpn(tr);       // ensure the DAW knows the range first
            // DS bend is signed -128..127 spanning +/- bendRange; map to 14-bit MIDI centred at 8192
            int val = 8192 + signed * 64;
            if (val < 0) val = 0;
            if (val > 0x3FFF) val = 0x3FFF;
            add(tr, tick, new byte[]{(byte) (0xE0 | tr.channel), (byte) (val & 0x7F), (byte) ((val >> 7) & 0x7F)});
        }

        int readVar(TrackVM tr) { int v = 0, b; do { b = ev[tr.pc++] & 0xFF; v = (v << 7) | (b & 0x7F); } while ((b & 0x80) != 0); return v; }
        int readU16(TrackVM tr) { int v = (ev[tr.pc] & 0xFF) | ((ev[tr.pc + 1] & 0xFF) << 8); tr.pc += 2; return v; }
        int readS16(TrackVM tr) { return (short) readU16(tr); }
        int readU24(TrackVM tr) { int v = (ev[tr.pc] & 0xFF) | ((ev[tr.pc + 1] & 0xFF) << 8) | ((ev[tr.pc + 2] & 0xFF) << 16); tr.pc += 3; return v; }

        // ------------------------------------------------------------- SMF out

        byte[] writeSmf()
        {
            List<byte[]> trackChunks = new ArrayList<>();
            // conductor track 0: tempo map
            trackChunks.add(buildTrack(tempoEvents, true));
            // one MIDI track per SSEQ track that produced events
            for (TrackVM tr : tracks)
                if (!tr.events.isEmpty())
                    trackChunks.add(buildTrack(tr.events, false));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeStr(out, "MThd");
            writeU32(out, 6);
            writeU16(out, 1);                        // format 1
            writeU16(out, trackChunks.size());
            writeU16(out, PPQN);
            for (byte[] c : trackChunks) { writeStr(out, "MTrk"); writeU32(out, c.length); out.write(c, 0, c.length); }
            return out.toByteArray();
        }

        byte[] buildTrack(List<Ev> events, boolean conductor)
        {
            List<Ev> sorted = new ArrayList<>(events);
            // stable sort by tick; note-offs (0x80) before note-ons (0x90) at the same tick
            Collections.sort(sorted, new Comparator<Ev>() {
                public int compare(Ev a, Ev b)
                {
                    if (a.tick != b.tick) return Long.compare(a.tick, b.tick);
                    return Integer.compare(rank(a.data[0]), rank(b.data[0]));
                }
                int rank(byte status) { int s = status & 0xF0; return s == 0x80 ? 0 : (s == 0x90 ? 2 : 1); }
            });
            ByteArrayOutputStream trk = new ByteArrayOutputStream();
            long prev = 0;
            for (Ev e : sorted)
            {
                writeVar(trk, e.tick - prev);
                prev = e.tick;
                trk.write(e.data, 0, e.data.length);
            }
            // end of track
            writeVar(trk, 0); trk.write(0xFF); trk.write(0x2F); trk.write(0x00);
            return trk.toByteArray();
        }

        /** One MIDI track per instrument (grouped by active program), each cleanly patched and named. */
        byte[] writeSmfPerInstrument()
        {
            // group every event by the program that was active when it was emitted
            java.util.TreeMap<Integer, List<Ev>> byProgram = new java.util.TreeMap<>();
            for (TrackVM tr : tracks)
                for (Ev e : tr.events)
                {
                    int prog = e.program < 0 ? 0 : e.program;
                    List<Ev> l = byProgram.get(prog);
                    if (l == null) { l = new ArrayList<Ev>(); byProgram.put(prog, l); }
                    l.add(e);
                }

            List<byte[]> chunks = new ArrayList<>();
            chunks.add(buildTrack(tempoEvents, true)); // conductor: tempo map
            for (java.util.Map.Entry<Integer, List<Ev>> en : byProgram.entrySet())
                chunks.add(buildInstrumentTrack(en.getKey(), en.getValue()));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeStr(out, "MThd"); writeU32(out, 6); writeU16(out, 1); writeU16(out, chunks.size()); writeU16(out, PPQN);
            for (byte[] c : chunks) { writeStr(out, "MTrk"); writeU32(out, c.length); out.write(c, 0, c.length); }
            return out.toByteArray();
        }

        byte[] buildInstrumentTrack(int program, List<Ev> src)
        {
            List<Ev> evs = new ArrayList<>();
            String nm = "Prog " + program;
            byte[] name = new byte[3 + nm.length()];
            name[0] = (byte) 0xFF; name[1] = 0x03; name[2] = (byte) nm.length();
            for (int i = 0; i < nm.length(); i++) name[3 + i] = (byte) nm.charAt(i);
            evs.add(new Ev(0, name));                                           // track name
            evs.add(new Ev(0, new byte[]{(byte) 0xB0, 0x00, 0x00}));            // bank select MSB
            evs.add(new Ev(0, new byte[]{(byte) 0xB0, 0x20, 0x00}));            // bank select LSB
            evs.add(new Ev(0, new byte[]{(byte) 0xC0, (byte) (program & 0x7F)})); // one clean program change

            for (Ev e : src)
            {
                int st = e.data[0] & 0xFF;
                if ((st & 0xF0) == 0xC0) continue;                             // drop mid-track program changes
                byte[] d = e.data.clone();
                if (st >= 0x80 && st <= 0xEF) d[0] = (byte) (st & 0xF0);        // re-channel to 0
                evs.add(new Ev(e.tick, d));
            }
            return buildTrack(evs, false);
        }

        void writeVar(ByteArrayOutputStream o, long v)
        {
            if (v < 0) v = 0;
            byte[] buf = new byte[5]; int i = 0;
            buf[i++] = (byte) (v & 0x7F);
            while ((v >>= 7) > 0) buf[i++] = (byte) ((v & 0x7F) | 0x80);
            for (int j = i - 1; j >= 0; j--) o.write(buf[j]);
        }
        void writeStr(ByteArrayOutputStream o, String s) { for (int i = 0; i < s.length(); i++) o.write(s.charAt(i)); }
        void writeU32(ByteArrayOutputStream o, int v) { o.write((v >> 24) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >> 8) & 0xFF); o.write(v & 0xFF); }
        void writeU16(ByteArrayOutputStream o, int v) { o.write((v >> 8) & 0xFF); o.write(v & 0xFF); }
    }
}
