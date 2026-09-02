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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports a DS {@link InstrumentBank SBNK} (plus its {@link WaveArchive SWAR}s) as a <b>SoundFont 2</b>
 * ({@code .sf2}) file — a standard, self-contained sampler instrument that imports straight into Logic
 * Pro's Sampler, and virtually every other DAW/soft-sampler.
 * <p>
 * The mapping is direct because SF2 and SBNK share the same idea of "a keyboard split into zones, each
 * playing a pitched sample with an envelope":
 * <ul>
 *   <li>each SBNK instrument (program) &rarr; one SF2 preset + instrument,</li>
 *   <li>each note region (single / drum-kit / key-split) &rarr; an instrument zone with a key range,</li>
 *   <li>the region's base note &rarr; {@code overridingRootKey}, its ADSR &rarr; the volume envelope,</li>
 *   <li>each referenced SWAV &rarr; a 16-bit PCM sample with its loop points.</li>
 * </ul>
 * Envelopes and the DS pitch model are faithful approximations (they land in the ballpark and are fully
 * editable once imported), not a hardware-exact emulation. Pure JVM, Java-8-clean (CheerpJ-safe).
 */
public final class SoundFontExporter
{
    private SoundFontExporter() {}

    // SF2 generator operators used here
    private static final int GEN_PAN = 17, GEN_INSTRUMENT = 41, GEN_KEYRANGE = 43,
            GEN_SAMPLE_MODES = 54, GEN_SAMPLE_ID = 53, GEN_ROOT_KEY = 58,
            GEN_ATTACK = 34, GEN_DECAY = 36, GEN_SUSTAIN = 37, GEN_RELEASE = 38;

    /** Build a SoundFont for one bank of a sound archive (resolving its wave archives from the SDAT). */
    public static byte[] fromBank(SoundArchive sdat, int bankId, String name)
    {
        byte[] bf = sdat.getFileFor(SoundArchive.RecordType.BANK, bankId);
        if (bf == null) throw new IllegalArgumentException("no bank " + bankId);
        InstrumentBank bank = InstrumentBank.fromBytes(bf);
        int[] slots = sdat.getBankWaveArchives(bankId);
        WaveArchive[] arcs = new WaveArchive[4];
        for (int i = 0; i < 4 && i < slots.length; i++)
        {
            int a = slots[i];
            if (a == 0xFFFF) continue;
            byte[] wf = sdat.getFileFor(SoundArchive.RecordType.WAVE_ARCHIVE, a);
            if (wf != null && wf.length >= 4 && wf[0] == 'S' && wf[1] == 'W' && wf[2] == 'A' && wf[3] == 'R')
                arcs[i] = WaveArchive.fromBytes(wf);
        }
        return export(name, bank, arcs);
    }

    // one decoded sample destined for the shdr/smpl chunks
    private static final class Sample
    {
        String name; short[] pcm; int rate; boolean loop; int loopStart, loopEnd;
        int startFrame; // filled in during smpl layout
    }

    /**
     * @param name SoundFont bank name (shown in the DAW)
     * @param bank the instrument bank
     * @param arcs the bank's up-to-four wave archives, indexed by wave-archive slot
     * @return a complete {@code .sf2} file
     */
    public static byte[] export(String name, InstrumentBank bank, WaveArchive[] arcs)
    {
        // 1) collect the unique samples the bank actually references
        Map<Long, Integer> sampleIndex = new LinkedHashMap<>();
        List<Sample> samples = new ArrayList<>();

        // 2) build instruments + zones, referencing samples by index
        List<int[]> igen = new ArrayList<>();       // {oper, amount}
        List<Integer> ibagGen = new ArrayList<>();   // per zone: starting igen index
        List<int[]> inst = new ArrayList<>();        // {nameId(index into instNames), bagNdx}
        List<String> instNames = new ArrayList<>();
        List<int[]> presetZone = new ArrayList<>();  // {presetNumber, instIndex, nameId}
        List<String> presetNames = new ArrayList<>();

        for (int p = 0; p < bank.getInstrumentCount(); p++)
        {
            InstrumentBank.Instrument in = bank.getInstrument(p);
            if (in.isEmpty()) continue;

            int instBagStart = ibagGen.size();
            int instIdx = inst.size();
            for (int rIdx = 0; rIdx < in.regions.size(); rIdx++)
            {
                InstrumentBank.NoteRegion region = in.regions.get(rIdx);
                int sIdx;      // index into samples
                int rootKey;   // overridingRootKey for this region
                if (region.isPsg)                 // synthesized square wave, unity A4 (69)
                {
                    long key = 0x100000000L | region.getDuty();
                    sIdx = internSample(sampleIndex, samples, key, makePsgSample(region.getDuty()));
                    rootKey = 69;
                }
                else if (region.isNoise)          // synthesized LFSR noise, unity 45
                {
                    sIdx = internSample(sampleIndex, samples, 0x200000000L, makeNoiseSample());
                    rootKey = 45;
                }
                else                              // PCM sample from a wave archive
                {
                    int arcIdx = region.waveArcIndex;
                    WaveArchive arc = (arcIdx >= 0 && arcIdx < arcs.length) ? arcs[arcIdx] : null;
                    if (arc == null || region.waveIndex >= arc.getWaveCount()) continue;
                    long key = ((long) arcIdx << 32) | (region.waveIndex & 0xFFFFFFFFL);
                    Integer got = sampleIndex.get(key);
                    if (got == null)
                    {
                        Wave w = arc.getWave(region.waveIndex);
                        Sample s = new Sample();
                        s.name = ("s" + samples.size() + "_a" + arcIdx + "w" + region.waveIndex);
                        s.pcm = w.decode();
                        s.rate = Math.max(400, w.getSampleRate());
                        int ls = w.getLoopStartSample();
                        s.loop = w.loops() && ls < s.pcm.length - 8;
                        s.loopStart = s.loop ? ls : 0;
                        s.loopEnd = s.pcm.length; // exclusive loop end
                        got = samples.size();
                        sampleIndex.put(key, got);
                        samples.add(s);
                    }
                    sIdx = got;
                    rootKey = region.baseNote;
                }

                int[] range = keyRange(in, rIdx);
                ibagGen.add(igen.size());
                if (range != null) igen.add(new int[]{ GEN_KEYRANGE, (range[0] & 0xFF) | ((range[1] & 0xFF) << 8) });
                igen.add(new int[]{ GEN_PAN, panToGen(region.pan) });
                igen.add(new int[]{ GEN_ATTACK, timecents(attackSeconds(region.attack)) });
                igen.add(new int[]{ GEN_DECAY, timecents(decaySeconds(region.decay)) });
                igen.add(new int[]{ GEN_SUSTAIN, sustainCentibels(region.sustain) });
                igen.add(new int[]{ GEN_RELEASE, timecents(releaseSeconds(region.release)) });
                Sample s = samples.get(sIdx);
                if (s.loop) igen.add(new int[]{ GEN_SAMPLE_MODES, 1 });
                igen.add(new int[]{ GEN_ROOT_KEY, rootKey });
                igen.add(new int[]{ GEN_SAMPLE_ID, sIdx });   // sampleID MUST be the terminal generator
            }
            if (ibagGen.size() == instBagStart) continue; // no usable zones; skip empty instrument

            instNames.add(instName(p));
            inst.add(new int[]{ instNames.size() - 1, instBagStart });
            presetNames.add(instName(p));
            presetZone.add(new int[]{ p, instIdx, presetNames.size() - 1 });
        }

        return writeSf2(name, samples, igen, ibagGen, inst, instNames, presetZone, presetNames);
    }

    /** Dedup a synthesized (PSG/noise) sample by key; add it if new. @return its index. */
    private static int internSample(Map<Long, Integer> index, List<Sample> samples, long key, Sample s)
    {
        Integer got = index.get(key);
        if (got != null) return got;
        int idx = samples.size();
        index.put(key, idx);
        samples.add(s);
        return idx;
    }

    /**
     * A band-limited PSG square wave, matching VGMTrans: a 32768-sample full-loop at 32768&nbsp;Hz with
     * unity key A4 (440&nbsp;Hz). 32768 samples at 32768&nbsp;Hz is exactly one second, and 440&nbsp;Hz is
     * an integer number of cycles per second, so the additive (harmonic-summed) waveform loops seamlessly
     * and is free of the aliasing a naive square would have.
     */
    private static Sample makePsgSample(int duty)
    {
        int rate = 32768, len = 32768;
        double f = 440.0;                                   // A4, unity key 69
        double highFrac = (duty >= 7) ? 0.5 : (duty + 1) / 8.0;  // duty 0..6 -> 12.5%..87.5%
        int maxK = (int) ((rate / 2) / f);                  // harmonics up to Nyquist
        Sample s = new Sample();
        s.name = "psg_duty" + duty;
        s.pcm = new short[len];
        for (int i = 0; i < len; i++)
        {
            double t = i / (double) rate;
            double v = 2 * highFrac - 1;                    // DC term for an asymmetric pulse
            for (int k = 1; k <= maxK; k++)
                v += (2.0 / (k * Math.PI)) * Math.sin(k * Math.PI * highFrac) * Math.cos(2 * Math.PI * k * f * t);
            int sm = (int) Math.round(v * 24000);           // scale with headroom
            s.pcm[i] = (short) Math.max(-32767, Math.min(32767, sm));
        }
        s.rate = rate;
        s.loop = true; s.loopStart = 0; s.loopEnd = len;
        return s;
    }

    /** A looped LFSR-noise buffer (unity 45), generated with the DS noise polynomial. */
    private static Sample makeNoiseSample()
    {
        int len = 0x7FFF;
        Sample s = new Sample();
        s.name = "psg_noise";
        s.pcm = new short[len];
        int[] lfsr = { 0x7FFF };
        for (int i = 0; i < len; i++) s.pcm[i] = DsSynth.noiseStep(lfsr);
        s.rate = 32768;
        s.loop = true; s.loopStart = 0; s.loopEnd = len;
        return s;
    }

    /** low/high key for a region: full range for single instruments, per-note for drums, split for key-splits. */
    private static int[] keyRange(InstrumentBank.Instrument in, int rIdx)
    {
        if (in.type <= 15) return null; // full 0..127
        if (in.type == 16) { int k = in.lowNote + rIdx; return new int[]{ k, k }; }
        if (in.type == 17)
        {
            int lo = 0;
            for (int i = 0; i < rIdx; i++) lo = in.splitPoints[i] + 1;
            int hi = (rIdx < in.splitPoints.length) ? in.splitPoints[rIdx] : 127;
            if (hi <= 0) hi = 127;
            return new int[]{ Math.min(lo, 127), Math.min(hi, 127) };
        }
        return null;
    }

    // ---- DS register -> envelope conversions (the real hardware math; see DsEnvelope) ----
    private static double attackSeconds(int a)  { return DsEnvelope.attackSeconds(a); }
    private static double decaySeconds(int d)   { return DsEnvelope.decaySeconds(d); }
    private static double releaseSeconds(int r) { return DsEnvelope.releaseSeconds(r); }

    private static int timecents(double seconds)
    {
        if (seconds <= 0) return -12000;
        int tc = (int) Math.round(1200.0 * Math.log(seconds) / Math.log(2.0));
        return Math.max(-12000, Math.min(8000, tc));
    }
    private static int sustainCentibels(int dsSustain)
    {
        double level = DsEnvelope.sustainLevel(dsSustain); // logarithmic (dB) level, 0..1
        if (level >= 0.999) return 0;
        if (level <= 0.0) return 1440;                       // ~ -144 dB (silent)
        int cb = (int) Math.round(-200.0 * Math.log10(level));
        return Math.max(0, Math.min(1440, cb));
    }
    private static int panToGen(int dsPan) { return Math.max(-500, Math.min(500, (dsPan - 64) * 1000 / 128)); }

    private static String instName(int p) { return "Inst " + p; }

    // ------------------------------------------------------------------ SF2 RIFF

    private static byte[] writeSf2(String name, List<Sample> samples, List<int[]> igen, List<Integer> ibagGen,
                                   List<int[]> inst, List<String> instNames,
                                   List<int[]> presetZone, List<String> presetNames)
    {
        // --- sdta / smpl: concatenate all sample PCM with 46-frame guard padding ---
        ByteArrayOutputStream smpl = new ByteArrayOutputStream();
        int frame = 0;
        for (Sample s : samples)
        {
            s.startFrame = frame;
            for (short v : s.pcm) { smpl.write(v & 0xFF); smpl.write((v >> 8) & 0xFF); }
            for (int i = 0; i < 46; i++) { smpl.write(0); smpl.write(0); }
            frame += s.pcm.length + 46;
        }

        // --- pdta sub-chunks ---
        ByteArrayOutputStream shdr = new ByteArrayOutputStream();
        for (Sample s : samples)
        {
            int start = s.startFrame, end = s.startFrame + s.pcm.length;
            int ls = s.loop ? s.startFrame + s.loopStart : start;
            int le = s.loop ? s.startFrame + s.loopEnd : end;
            writeSampleHeader(shdr, s.name, start, end, ls, le, s.rate, 60);
        }
        writeSampleHeader(shdr, "EOS", 0, 0, 0, 0, 0, 0);

        // igen array + terminal
        ByteArrayOutputStream genBuf = new ByteArrayOutputStream();
        for (int[] g : igen) { u16(genBuf, g[0]); u16(genBuf, g[1]); }
        u16(genBuf, 0); u16(genBuf, 0);

        // ibag: one per zone, genNdx from ibagGen, modNdx 0; + terminal (genNdx = igen.size)
        ByteArrayOutputStream ibag = new ByteArrayOutputStream();
        for (int gi : ibagGen) { u16(ibag, gi); u16(ibag, 0); }
        u16(ibag, igen.size()); u16(ibag, 0);

        // inst: name + bagNdx; + terminal EOI
        ByteArrayOutputStream instBuf = new ByteArrayOutputStream();
        for (int[] in : inst) { name20(instBuf, instNames.get(in[0])); u16(instBuf, in[1]); }
        name20(instBuf, "EOI"); u16(instBuf, ibagGen.size());

        // imod: just the terminal record
        ByteArrayOutputStream imod = new ByteArrayOutputStream();
        writeMod(imod, 0, 0, 0, 0, 0);

        // presets: each preset has one zone with a single 'instrument' generator
        ByteArrayOutputStream pgen = new ByteArrayOutputStream();
        ByteArrayOutputStream pbag = new ByteArrayOutputStream();
        ByteArrayOutputStream phdr = new ByteArrayOutputStream();
        int pgenCount = 0;
        for (int i = 0; i < presetZone.size(); i++)
        {
            int[] pz = presetZone.get(i);
            u16(pbag, pgenCount); u16(pbag, 0);      // this preset's bag -> its pgen start
            u16(pgen, GEN_INSTRUMENT); u16(pgen, pz[1]); // instrument generator (terminal for preset zone)
            pgenCount++;
            name20(phdr, presetNames.get(pz[2]));
            u16(phdr, pz[0]);   // wPreset (program number)
            u16(phdr, 0);       // wBank
            u16(phdr, i);       // wPresetBagNdx
            u32(phdr, 0); u32(phdr, 0); u32(phdr, 0);
        }
        // terminal preset (EOP) + terminal pbag + terminal pgen
        name20(phdr, "EOP"); u16(phdr, 0); u16(phdr, 0); u16(phdr, presetZone.size()); u32(phdr, 0); u32(phdr, 0); u32(phdr, 0);
        u16(pbag, pgenCount); u16(pbag, 0);
        u16(pgen, 0); u16(pgen, 0);

        // pmod terminal
        ByteArrayOutputStream pmod = new ByteArrayOutputStream();
        writeMod(pmod, 0, 0, 0, 0, 0);

        // --- assemble LIST INFO ---
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        chunk(info, "ifil", new byte[]{ 2, 0, 1, 0 });          // SoundFont 2.01
        chunk(info, "isng", cstr("EMU8000"));
        chunk(info, "INAM", cstr(name));

        // --- LIST sdta ---
        ByteArrayOutputStream sdta = new ByteArrayOutputStream();
        chunk(sdta, "smpl", smpl.toByteArray());

        // --- LIST pdta ---
        ByteArrayOutputStream pdta = new ByteArrayOutputStream();
        chunk(pdta, "phdr", phdr.toByteArray());
        chunk(pdta, "pbag", pbag.toByteArray());
        chunk(pdta, "pmod", pmod.toByteArray());
        chunk(pdta, "pgen", pgen.toByteArray());
        chunk(pdta, "inst", instBuf.toByteArray());
        chunk(pdta, "ibag", ibag.toByteArray());
        chunk(pdta, "imod", imod.toByteArray());
        chunk(pdta, "igen", genBuf.toByteArray());
        chunk(pdta, "shdr", shdr.toByteArray());

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeStr(body, "sfbk");
        listChunk(body, "INFO", info.toByteArray());
        listChunk(body, "sdta", sdta.toByteArray());
        listChunk(body, "pdta", pdta.toByteArray());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeStr(out, "RIFF");
        u32(out, body.size());
        out.write(body.toByteArray(), 0, body.size());
        return out.toByteArray();
    }

    private static void writeSampleHeader(ByteArrayOutputStream o, String name, int start, int end,
                                          int loopStart, int loopEnd, int rate, int rootKey)
    {
        name20(o, name);
        u32(o, start); u32(o, end); u32(o, loopStart); u32(o, loopEnd); u32(o, rate);
        o.write(rootKey & 0xFF);   // byOriginalPitch
        o.write(0);                // chPitchCorrection
        u16(o, 0);                 // wSampleLink
        u16(o, 1);                 // sfSampleType = monoSample
    }

    private static void writeMod(ByteArrayOutputStream o, int src, int dest, int amt, int amtSrc, int trans)
    { u16(o, src); u16(o, dest); u16(o, amt); u16(o, amtSrc); u16(o, trans); }

    // ---- RIFF helpers ----
    private static void chunk(ByteArrayOutputStream o, String id, byte[] data)
    {
        writeStr(o, id); u32(o, data.length); o.write(data, 0, data.length);
        if ((data.length & 1) == 1) o.write(0); // word-align
    }
    private static void listChunk(ByteArrayOutputStream o, String type, byte[] data)
    {
        writeStr(o, "LIST"); u32(o, data.length + 4); writeStr(o, type); o.write(data, 0, data.length);
        if (((data.length + 4) & 1) == 1) o.write(0);
    }
    private static byte[] cstr(String s)
    {
        byte[] b = new byte[s.length() + 1 + ((s.length() + 1) & 1)];
        for (int i = 0; i < s.length(); i++) b[i] = (byte) s.charAt(i);
        return b;
    }
    private static void name20(ByteArrayOutputStream o, String s)
    {
        byte[] b = new byte[20];
        for (int i = 0; i < 19 && i < s.length(); i++) b[i] = (byte) s.charAt(i);
        o.write(b, 0, 20);
    }
    private static void writeStr(ByteArrayOutputStream o, String s) { for (int i = 0; i < s.length(); i++) o.write(s.charAt(i)); }
    private static void u16(ByteArrayOutputStream o, int v) { o.write(v & 0xFF); o.write((v >> 8) & 0xFF); }
    private static void u32(ByteArrayOutputStream o, int v) { o.write(v & 0xFF); o.write((v >> 8) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >> 24) & 0xFF); }
}
