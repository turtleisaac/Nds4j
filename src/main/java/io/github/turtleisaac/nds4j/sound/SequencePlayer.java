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

/**
 * A pure-JVM software synthesizer that renders a {@link Sequence SSEQ} into PCM audio, using its
 * {@link InstrumentBank SBNK} and the bank's {@link WaveArchive SWAR}s.
 * <p>
 * Playback, envelopes, pitch, LFO, portamento, and the 16-channel mixer match GotaSequenceLib /
 * NitroStudio2 Deluxe (the same player Nitro Studio 2 uses): a 192&nbsp;Hz driver with a tempo stack,
 * Gota {@code AttackTable}/{@code DecayTable}/{@code GetChannelTimer}, sequence-tick LFO, and a
 * 65456&nbsp;Hz drop-sample mixer.
 */
public class SequencePlayer
{
    private static final int TICKS_PER_BEAT = 48;
    private static final int MAX_TRACKS = 16;

    private final byte[] ev;                 // SSEQ event bytecode
    private final InstrumentBank bank;
    private final WaveArchive[] waveArcs;    // the bank's up-to-4 wave archives (by bank wave-arc slot)

    private int tempo = 120;                 // BPM
    /** Player-level main volume (SSEQ {@code 0xC2}); 127 = unity. Combined in the dB domain. */
    private int playerVolume = 127;
    /** Debug/AB knob: multiplies the effective tempo (1.0 = the sequence's own tempo). */
    public double tempoScale = 1.0;
    /** Debug: per-track enable. A muted track still runs (keeps timing) but produces no voices. */
    public final boolean[] trackEnabled = {
        true, true, true, true, true, true, true, true,
        true, true, true, true, true, true, true, true };
    private int renderRate = 32768;          // output sample rate of the current render
    /** Diagnostic counters (not part of the public contract; used by tests to confirm notes played). */
    public int dbgNotes, dbgResolveNull, dbgNotPcm, dbgNoArc, dbgVoices, dbgMaxVoices, dbgDropped;
    /** 16 DS hardware channels. NitroStudio2 / GotaSequenceLib steal among these; stacking 64
     *  live voices into a tanh limiter is what made dense songs a late, conflicting wall of sound. */
    private final Voice[] channels = new Voice[16];
    private final Track[] tracks = new Track[MAX_TRACKS];
    private int allocSerial;

    public SequencePlayer(Sequence sequence, InstrumentBank bank, WaveArchive[] waveArcs)
    {
        this.ev = sequence.getEventData();
        this.bank = bank;
        this.waveArcs = waveArcs;
    }

    /**
     * Wire up a player for one sequence in a sound archive: resolve the sequence's {@link InstrumentBank}
     * and that bank's up-to-four {@link WaveArchive}s straight from the SDAT's INFO records. This is the
     * one-call entry point for playing a song out of an SDAT (what NitroViewer's audio preview needs).
     * @return a ready player, or {@code null} if the sequence has no valid bank
     */
    public static SequencePlayer forSequence(SoundArchive sdat, int seqIndex)
    {
        byte[] seqFile = sdat.getFileFor(SoundArchive.RecordType.SEQUENCE, seqIndex);
        if (seqFile == null || !isMagic(seqFile, "SSEQ")) return null;
        int bankId = sdat.getSequenceBankId(seqIndex);
        byte[] bankFile = sdat.getFileFor(SoundArchive.RecordType.BANK, bankId);
        if (bankFile == null || !isMagic(bankFile, "SBNK")) return null;
        InstrumentBank bank = InstrumentBank.fromBytes(bankFile);
        int[] slots = sdat.getBankWaveArchives(bankId);
        WaveArchive[] arcs = new WaveArchive[4];
        for (int i = 0; i < 4 && i < slots.length; i++)
        {
            int a = slots[i];
            if (a == 0xFFFF) continue;
            byte[] wf = sdat.getFileFor(SoundArchive.RecordType.WAVE_ARCHIVE, a);
            if (wf != null && isMagic(wf, "SWAR")) arcs[i] = WaveArchive.fromBytes(wf);
        }
        return new SequencePlayer(Sequence.fromBytes(seqFile), bank, arcs);
    }

    private static boolean isMagic(byte[] f, String m)
    {
        if (f.length < 4) return false;
        for (int i = 0; i < 4; i++) if ((f[i] & 0xFF) != m.charAt(i)) return false;
        return true;
    }

    // -------------------------------------------------------------- track VM

    private static final class Track
    {
        boolean allocated;        // 0xFE bitmask; OpenTrack only enables allocated tracks
        boolean enabled;          // Gota Enabled (OpenTrack); Fin does not clear this
        boolean stopped;          // Gota Stopped (0xFF); Tick still runs so gates/LFO finish
        int pc;
        int wait;                 // ticks to wait before next command
        int program;
        int volume = 127;
        int expression = 127;
        int pan = 64;             // 0..127, 64 = center
        int transpose;
        int pitchBend;            // -128..127
        int bendRange = 2;        // semitones
        int priority = 64;        // 0xC6 track priority (DS voice-allocation priority)
        // modulation LFO lives on the track (Gota Track.Tick), shared by every voice on it
        int modDepth = 0, modSpeed = 16, modType = 0, modRange = 1, modDelay = 0;
        int lfoPhase, lfoDelayCount;
        int sweepPitch = 0;       // 0xE3, added to the next note's sweep
        int portamentoKey = 60;   // Gota PortamentoKey; 0xC9 sets it and turns portamento on
        boolean portamentoOn = false; int portamentoTime = 0;
        boolean noteWait = true;  // note-ons block the track for their duration
        boolean tie;
        boolean waitingForNote;   // note-wait + duration 0: hold until the voice is gone
        int attackOv = 0xFF, decayOv = 0xFF, sustainOv = 0xFF, releaseOv = 0xFF; // 0xFF = use instrument
        // Gota/hardware: one 3-deep stack shared by call and loop
        final int[] callStack = new int[3];
        final int[] callStackLoops = new int[3];
        int callDepth;
    }

    private static final class Voice
    {
        short[] samples;
        boolean isPsg, isNoise;
        int duty;                 // PSG duty cycle 0..7
        final int[] noiseLfsr = { 0x7FFF };
        boolean loop;
        int loopStart;            // sample index to loop back to
        int loopEnd;              // exclusive end of the looping region (decoded index)
        int velocity;             // note velocity 0..127
        int key;                  // MIDI key after transpose (Gota Channel.Key)
        int baseKey;              // region unity key (Gota Channel.BaseKey)
        int baseTimer = 16;       // DS SOUND CNT timer reload at unity pitch
        int timer = 16;           // current timer (pitch-scaled)
        int timerPos;             // fractional PCM/PSG clock, Gota (_pos)
        int waveIndex;            // integer sample index (drop-sample, no interpolation)
        short lastSamp;           // held when this output frame consumes 0 source samples
        int psgCounter;           // 8-step PSG duty counter
        boolean autoSweep;        // portamentoTime != 0: sweep advances at 192 Hz, else on sequence ticks
        int sweepPitch, sweepLength, sweepCounter;
        int envState;             // 0 attack, 2 decay, 3 sustain, 4 release
        int envVelocity;          // 0 = full, -92544 = silent
        int envAtk, envDec, envSus, envRel;
        int noteDuration;         // Gota NoteDuration; -1 holds until released
        boolean dead;
        int trackId;
        int priority = 64;
        int regionPan;            // instrument pan as Gota StartingPan (region.pan - 64)
        int volByte;              // 0..127, Gota Channel.Volume
        int pan;                  // -64..63, Gota Channel.Pan
        int serial;               // allocation order, for tie = last channel on the track
    }

    private static final int MAX_VOICES = 16;
    private static final short[] EMPTY = new short[0]; // placeholder for synthesized (PSG/noise) voices

    // ---------------------------------------------------------------- render

    /**
     * Render the sequence to interleaved stereo signed-16-bit PCM.
     * @param outRate output sample rate (Hz)
     * @param maxSeconds hard cap on render length
     * @return interleaved L,R samples
     */
    public short[] renderStereo(int outRate, double maxSeconds)
    {
        renderRate = outRate;
        for (int i = 0; i < MAX_TRACKS; i++)
        {
            tracks[i] = new Track();
            tracks[i].allocated = tracks[i].enabled = (i == 0);
        }
        tracks[0].pc = 0;
        playerVolume = 127;
        tempo = 120;
        allocSerial = 0;
        for (int i = 0; i < MAX_VOICES; i++) channels[i] = null;

        int maxFrames = (int) (outRate * maxSeconds);
        short[] out = new short[maxFrames * 2];

        // Gota Player.Tick: sequence ticks, then ChannelTick, then 341 mixer samples at 65456 Hz.
        // At another output rate we emit 341 * outRate / 65456 samples per driver frame (remainder
        // carried). Each output sample adds 65456*256 / outRate to the channel timer (511.375 at
        // 32768 Hz, not round(512)).
        int tempoStack = 0;
        long emitAcc = 0;
        long mixerIncAcc = 0;
        int frame = 0;
        int silentFrames = 0;

        while (frame < maxFrames)
        {
            while (tempoStack >= 240)
            {
                tempoStack -= 240;
                stepTick();
            }
            int t = (int) Math.round(tempo * tempoScale);
            if (t < 1) t = 1;
            tempoStack += t;
            channelTick();

            emitAcc += (long) DsSynth.MIX_SAMPLES_PER_FRAME * renderRate;
            int n = (int) (emitAcc / DsSynth.MIX_RATE);
            emitAcc %= DsSynth.MIX_RATE;
            if (n < 1) n = 1;
            boolean done = false;
            for (int s = 0; s < n && frame < maxFrames; s++, frame++)
            {
                mixerIncAcc += (long) DsSynth.MIX_RATE << 8;
                int mixerInc = (int) (mixerIncAcc / renderRate);
                mixerIncAcc %= renderRate;
                if (mixerInc < 1) mixerInc = 1;

                int left = 0, right = 0;
                for (int vi = 0; vi < MAX_VOICES; vi++)
                {
                    Voice v = channels[vi];
                    if (v == null || v.dead) continue;
                    int samp = sampleVoice(v, mixerInc);
                    if (v.dead) continue;
                    int vol = v.volByte;
                    int pan = v.pan;
                    int l = samp * vol / 0x7F;
                    int r = samp * vol / 0x7F;
                    left += l * (-pan + 0x40) / 0x80;
                    right += r * (pan + 0x40) / 0x80;
                }
                out[frame * 2]     = clip16(left);
                out[frame * 2 + 1] = clip16(right);

                for (int vi = 0; vi < MAX_VOICES; vi++)
                    if (channels[vi] != null && channels[vi].dead) channels[vi] = null;

                if (allTracksDone())
                {
                    if (Math.abs(left) < 4 && Math.abs(right) < 4)
                    {
                        if (++silentFrames > outRate / 2) { done = true; frame++; break; }
                    }
                    else silentFrames = 0;
                }
            }
            if (done || (allTracksDone() && liveVoices() == 0))
                break;
        }
        if (frame < maxFrames)
        {
            short[] trimmed = new short[frame * 2];
            System.arraycopy(out, 0, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return out;
    }

    public byte[] toWav(int outRate, double maxSeconds)
    {
        return WavFile.pcm16(renderStereo(outRate, maxSeconds), 2, outRate);
    }

    private int sampleVoice(Voice v, int inc)
    {
        if (v.dead) return 0;
        int tim = v.timer < 16 ? 16 : v.timer;
        int num = (v.timerPos + inc) / tim;
        v.timerPos = (v.timerPos + inc) % tim;
        for (int n = 0; n < num; n++)
        {
            if (v.isPsg)
            {
                v.lastSamp = DsSynth.psgSquare(v.psgCounter, v.duty);
                v.psgCounter = (v.psgCounter + 1) & 7;
            }
            else if (v.isNoise)
            {
                v.lastSamp = DsSynth.noiseStep(v.noiseLfsr);
            }
            else
            {
                if (v.samples.length == 0) { v.lastSamp = 0; break; }
                int end = v.samples.length;
                if (v.loop && v.loopEnd > v.loopStart && v.loopEnd <= v.samples.length)
                    end = v.loopEnd;
                if (v.waveIndex >= end)
                {
                    if (v.loop) v.waveIndex = v.loopStart;
                    else { v.lastSamp = 0; v.dead = true; return 0; }
                }
                if (v.waveIndex >= 0 && v.waveIndex < v.samples.length)
                    v.lastSamp = v.samples[v.waveIndex];
                v.waveIndex++;
            }
        }
        return v.lastSamp;
    }

    /**
     * One 192-Hz Gota {@code Mixer.ChannelTick}: envelope, auto-sweep, volume, timer, pan.
     * LFO and non-auto sweep advance on sequence ticks in {@link #stepTick()}.
     */
    private void channelTick()
    {
        for (int vi = 0; vi < MAX_VOICES; vi++)
        {
            Voice v = channels[vi];
            if (v == null || v.dead) continue;
            Track tr = (v.trackId >= 0 && v.trackId < MAX_TRACKS) ? tracks[v.trackId] : null;
            stepEnvelope(v);
            if (v.noteDuration == 0 && (tr == null || !tr.waitingForNote))
                v.envState = 4;

            int lfoPitch = 0, lfoVol = 0, lfoPan = 0;
            if (tr != null)
            {
                long lfoRaw = (tr.modDepth != 0)
                        ? (long) tr.modRange * DsSynth.sin(tr.lfoPhase >> 8) * tr.modDepth : 0;
                if (tr.modType == 0) lfoPitch = (int) ((lfoRaw * 60) >> 14);
                else if (tr.modType == 1) lfoVol = scaleLfoVolPan(lfoRaw);
                else if (tr.modType == 2) lfoPan = scaleLfoVolPan(lfoRaw);
            }

            int units = ((v.key - v.baseKey) << 6) + sweepMain(v)
                    + (tr == null ? 0 : tr.pitchBend * tr.bendRange / 2) + lfoPitch;
            v.timer = DsSynth.channelTimer(v.baseTimer, units);

            int vol = tr == null ? 127 : tr.volume, expr = tr == null ? 127 : tr.expression;
            int atten = v.envVelocity + DsEnvelope.SUSTAIN_TABLE[clamp7(v.velocity)]
                    + DsEnvelope.SUSTAIN_TABLE[clamp7(vol)] + DsEnvelope.SUSTAIN_TABLE[clamp7(expr)]
                    + DsEnvelope.SUSTAIN_TABLE[clamp7(playerVolume)] + lfoVol;
            if (v.envState == 4 && atten <= DsEnvelope.ENV_SILENT) { v.dead = true; continue; }
            v.volByte = DsEnvelope.channelVolume(atten);

            int panPot = v.regionPan + (tr == null ? 0 : tr.pan - 64) + lfoPan;
            if (panPot < -64) panPot = -64;
            if (panPot > 63) panPot = 63;
            v.pan = panPot;
        }
    }

    /** Gota {@code Channel.SweepMain}: remainder of the glide; AutoSweep steps at 192 Hz. */
    private static int sweepMain(Voice v)
    {
        if (v.sweepPitch != 0 && v.sweepCounter < v.sweepLength)
        {
            int sweep = (int) ((long) v.sweepPitch * (v.sweepLength - v.sweepCounter) / v.sweepLength);
            if (v.autoSweep) v.sweepCounter++;
            return sweep;
        }
        return 0;
    }

    private static int clamp7(int x) { return x < 0 ? 0 : (x > 127 ? 127 : x); }

    /** The DS volume/pan LFO scaling (from Gota7 GetVolume/GetPan). */
    private static int scaleLfoVolPan(long lfoL)
    {
        int lfo = (int) lfoL;
        return ((lfo & ~0xFC000000) >> 8) | ((lfo < 0 ? -1 : 0) << 6) | ((lfo >>> 26) << 18);
    }

    /** One 192-Hz step of the DS envelope state machine (attenuation domain), matching GotaSequenceLib. */
    private void stepEnvelope(Voice v)
    {
        switch (v.envState)
        {
            case 0: // attack: multiply the (negative) attenuation toward 0 (full volume)
                v.envVelocity = v.envAtk * v.envVelocity / 0xFF;
                if (v.envVelocity == 0) v.envState = 2;
                break;
            case 2: // decay: fall linearly in attenuation until the sustain level
                v.envVelocity -= v.envDec;
                if (v.envVelocity <= v.envSus) { v.envVelocity = v.envSus; v.envState = 3; }
                break;
            case 3: break; // sustain: hold
            case 4: // release: fall linearly to silence (Gota clamps; ChannelTick Stop()s)
                v.envVelocity -= v.envRel;
                if (v.envVelocity < DsEnvelope.ENV_SILENT) v.envVelocity = DsEnvelope.ENV_SILENT;
                break;
        }
    }

    // ------------------------------------------------------------ tick logic

    private void stepTick()
    {
        for (int t = 0; t < MAX_TRACKS; t++)
        {
            Track tr = tracks[t];
            if (!tr.enabled) continue;
            if (tr.wait > 0) tr.wait--;
            int live = 0;
            for (int vi = 0; vi < MAX_VOICES; vi++)
            {
                Voice v = channels[vi];
                if (v == null || v.dead || v.trackId != t) continue;
                live++;
                if (v.noteDuration > 0) v.noteDuration--;
                if (!v.autoSweep && v.sweepCounter < v.sweepLength) v.sweepCounter++;
            }
            if (live != 0)
            {
                if (tr.lfoDelayCount > tr.modDelay)
                    tr.lfoPhase = DsSynth.advanceLfoPhase(tr.lfoPhase, tr.modSpeed);
                else
                    tr.lfoDelayCount++;
            }
            else
            {
                tr.waitingForNote = false;
                tr.lfoPhase = 0;
                tr.lfoDelayCount = tr.modDelay;
            }
            int guard = 0;
            while (tr.enabled && !tr.stopped && tr.wait == 0 && !tr.waitingForNote && guard++ < 100000)
                execOne(t, tr);
        }
    }

    private boolean allTracksDone()
    {
        for (Track t : tracks)
            if (t.enabled && !t.stopped) return false;
        return true;
    }

    /** Execute one command on a track. Sets {@code tr.wait} (>0) when the command consumes time. */
    private void execOne(int trackId, Track tr)
    {
        if (tr.pc >= ev.length) { tr.stopped = true; return; }
        int op = ev[tr.pc++] & 0xFF;

        if (op < 0x80) // note on
        {
            int velocity = ev[tr.pc++] & 0xFF;
            int duration = readVarLen(tr);
            int key = op + tr.transpose;
            if (key < 0) key = 0; else if (key > 0x7F) key = 0x7F;
            startNote(trackId, tr, key, velocity, duration);
            tr.portamentoKey = key;
            if (tr.noteWait)
            {
                tr.wait = duration;
                if (duration == 0) tr.waitingForNote = true;
            }
            return;
        }

        switch (op)
        {
            case 0x80: tr.wait = readVarLen(tr); break;               // rest
            case 0x81: tr.program = readVarLen(tr); break;            // program change
            case 0x93: {                                             // open track (track 0 only; must be allocated)
                int tn = ev[tr.pc++] & 0xFF;
                int off = readU24(tr);
                if (trackId == 0 && tn < MAX_TRACKS && tracks[tn].allocated && !tracks[tn].enabled)
                {
                    tracks[tn].enabled = true;
                    tracks[tn].stopped = false;
                    tracks[tn].pc = off;
                }
                break;
            }
            case 0x94: tr.pc = readU24(tr); break;                    // jump
            case 0x95: {                                             // call (ignored if the 3-deep stack is full)
                int off = readU24(tr);
                if (tr.callDepth < 3)
                {
                    tr.callStack[tr.callDepth] = tr.pc;
                    tr.callDepth++;
                    tr.pc = off;
                }
                break;
            }
            case 0xA0: case 0xA1: case 0xA2:                          // random / fromvar / if prefixes
                execPrefixed(trackId, tr, op);
                break;
            case 0xB0: case 0xB1: case 0xB2: case 0xB3: case 0xB4: case 0xB5:
            case 0xB6: case 0xB7: case 0xB8: case 0xB9: case 0xBA: case 0xBB:
            case 0xBC: case 0xBD:
                tr.pc += 1; readS16(tr);                              // var op: u8 var + s16 (effect ignored)
                break;
            case 0xC0: tr.pan = ev[tr.pc++] & 0xFF; break;
            case 0xC1: tr.volume = ev[tr.pc++] & 0xFF; break;
            case 0xC2: playerVolume = ev[tr.pc++] & 0xFF; break;      // main (player) volume
            case 0xC3: tr.transpose = (byte) ev[tr.pc++]; break;
            case 0xC4: tr.pitchBend = (byte) ev[tr.pc++]; break;
            case 0xC5: tr.bendRange = ev[tr.pc++] & 0xFF; break;
            case 0xC6: tr.priority = ev[tr.pc++] & 0xFF; break;       // track priority
            case 0xC7: tr.noteWait = (ev[tr.pc++] & 0xFF) != 0; break;
            case 0xC8: tr.tie = (ev[tr.pc++] & 0xFF) != 0; stopTrackVoices(trackId); break;
            case 0xC9: {                                             // portamento control: from-note + enable
                int k = (ev[tr.pc++] & 0xFF) + tr.transpose;
                if (k < 0) k = 0; else if (k > 0x7F) k = 0x7F;
                tr.portamentoKey = k;
                tr.portamentoOn = true;
                break;
            }
            case 0xCA: tr.modDepth = ev[tr.pc++] & 0xFF; break;       // modulation depth
            case 0xCB: tr.modSpeed = ev[tr.pc++] & 0xFF; break;       // modulation speed
            case 0xCC: tr.modType = ev[tr.pc++] & 0xFF; break;        // modulation type (0 pitch,1 vol,2 pan)
            case 0xCD: tr.modRange = ev[tr.pc++] & 0xFF; break;       // modulation range
            case 0xCE: tr.portamentoOn = (ev[tr.pc++] & 0xFF) != 0; break;
            case 0xCF: tr.portamentoTime = ev[tr.pc++] & 0xFF; break;
            case 0xD0: tr.attackOv = ev[tr.pc++] & 0xFF; break;
            case 0xD1: tr.decayOv = ev[tr.pc++] & 0xFF; break;
            case 0xD2: tr.sustainOv = ev[tr.pc++] & 0xFF; break;
            case 0xD3: tr.releaseOv = ev[tr.pc++] & 0xFF; break;
            case 0xD4: {                                             // loop start (shares the 3-deep call stack)
                int count = ev[tr.pc++] & 0xFF;
                if (tr.callDepth < 3)
                {
                    tr.callStack[tr.callDepth] = tr.pc;
                    tr.callStackLoops[tr.callDepth] = count;
                    tr.callDepth++;
                }
                break;
            }
            case 0xD5: tr.expression = ev[tr.pc++] & 0xFF; break;     // expression
            case 0xD6: tr.pc++; break;                                // print var
            case 0xE0: tr.modDelay = readU16(tr); break;             // modulation delay (u16)
            case 0xE1: tempo = readU16(tr); break;                    // tempo
            case 0xE3: tr.sweepPitch = readS16(tr); break;           // sweep pitch (s16)
            case 0xFC: {                                             // loop end
                if (tr.callDepth != 0)
                {
                    int count = tr.callStackLoops[tr.callDepth - 1] & 0xFF;
                    if (count != 0)
                    {
                        count--;
                        if (count == 0) { tr.callDepth--; break; }
                    }
                    tr.callStackLoops[tr.callDepth - 1] = count;
                    tr.pc = tr.callStack[tr.callDepth - 1];
                }
                break;
            }
            case 0xFD:                                               // return (empty stack is a no-op)
                if (tr.callDepth != 0)
                {
                    tr.callDepth--;
                    tr.pc = tr.callStack[tr.callDepth];
                }
                break;
            case 0xFE: {                                             // allocate tracks (bitmask, track 0 only)
                int mask = readU16(tr);
                if (trackId == 0)
                {
                    for (int i = 0; i < MAX_TRACKS; i++)
                        if ((mask & (1 << i)) != 0) tracks[i].allocated = true;
                }
                break;
            }
            case 0xFF: tr.stopped = true; break;                     // end of track
            default: tr.stopped = true; break;                       // unknown: stop this track (avoid desync noise)
        }
    }

    /** 0xA0/0xA1/0xA2 prefix an inner command; consume its bytes so the stream stays in sync. */
    private void execPrefixed(int trackId, Track tr, int prefix)
    {
        int inner = ev[tr.pc++] & 0xFF;
        // consume the inner command's fixed operands, then the prefix's replacement for the final operand
        if (inner < 0x80) { tr.pc++; }                    // note: skip velocity, duration replaced below
        // most control ops take a single u8 that the prefix replaces; a few take none/u16/u24
        boolean finalIsWide = false;
        switch (inner)
        {
            case 0x80: case 0x81: break;                  // varlen final, replaced
            case 0x93: tr.pc++; readU24(tr); break;       // fully consumed here (rare under prefix)
            case 0x94: case 0x95: readU24(tr); break;
            case 0xE0: case 0xE1: case 0xE3: finalIsWide = true; break;
            case 0xFC: case 0xFD: case 0xFF: return;      // no operand
            default: break;                               // u8 final, replaced
        }
        if (prefix == 0xA0) { readS16(tr); readS16(tr); }  // random: min,max
        else if (prefix == 0xA1) tr.pc++;                  // from var: var index
        else if (prefix == 0xA2) { /* if: no extra bytes */ }
        // effect not modelled; bytes consumed so subsequent commands stay aligned
        if (finalIsWide) { /* wide inner already handled by A0 reading two s16 approximates */ }
    }

    // ----------------------------------------------------------------- notes

    private void startNote(int trackId, Track tr, int note, int velocity, int durationTicks)
    {
        dbgNotes++;
        if (trackId >= 0 && trackId < 16 && !trackEnabled[trackId]) return; // muted: keep timing, no sound

        // Gota PlayNote: tie reuses the last channel on this track (update key/velocity, don't restart).
        if (tr.tie)
        {
            Voice existing = lastVoiceOnTrack(trackId);
            if (existing != null)
            {
                existing.key = note;
                existing.velocity = velocity;
                applySweep(existing, tr, note, durationTicks);
                return;
            }
            durationTicks = -1;
        }

        InstrumentBank.NoteRegion region = (bank == null) ? null : bank.resolve(tr.program, note);
        if (region == null) { dbgResolveNull++; return; }

        int relReg = region.release;
        if (relReg == 0xFF) { durationTicks = -1; relReg = 0; }

        Voice v = new Voice();
        v.trackId = trackId;
        v.key = note;
        if (region.isPsg)
        {
            v.isPsg = true;
            v.duty = region.getDuty();
            v.samples = EMPTY;
            v.baseTimer = 8006;
            v.baseKey = region.baseNote == 0x7F ? 60 : region.baseNote;
        }
        else if (region.isNoise)
        {
            v.isNoise = true;
            v.samples = EMPTY;
            v.baseTimer = 8006;
            v.baseKey = region.baseNote == 0x7F ? 60 : region.baseNote;
        }
        else
        {
            WaveArchive arc = (region.waveArcIndex >= 0 && region.waveArcIndex < waveArcs.length)
                    ? waveArcs[region.waveArcIndex] : null;
            if (arc == null || region.waveIndex >= arc.getWaveCount()) { dbgNoArc++; return; }
            Wave wave = arc.getWave(region.waveIndex);
            short[] pcm = wave.decode();
            if (pcm.length == 0) return;
            v.samples = pcm;
            int bt = wave.getTimer();
            v.baseTimer = bt > 0 ? bt : (int) Math.round(16756991.0 / Math.max(1, wave.getSampleRate()));
            v.baseKey = region.baseNote;
            v.loop = wave.loops();
            v.loopStart = wave.getLoopStartSample();
            v.loopEnd = wave.getLoopEndSample();
            if (v.loopStart >= pcm.length) v.loopStart = 0;
            if (v.loopEnd > pcm.length || v.loopEnd <= v.loopStart) v.loopEnd = pcm.length;
        }

        int a = tr.attackOv != 0xFF ? tr.attackOv : region.attack;
        int d = tr.decayOv != 0xFF ? tr.decayOv : region.decay;
        int s = tr.sustainOv != 0xFF ? tr.sustainOv : region.sustain;
        if (tr.releaseOv != 0xFF) relReg = tr.releaseOv;
        v.envAtk = DsEnvelope.attackRate(a);
        v.envDec = DsEnvelope.getFallingRate(d);
        v.envSus = DsEnvelope.SUSTAIN_TABLE[clamp7(s)];
        v.envRel = DsEnvelope.getFallingRate(relReg);
        v.envVelocity = DsEnvelope.ENV_SILENT;
        v.envState = 0;
        v.velocity = velocity;
        v.noteDuration = durationTicks;
        v.priority = tr.priority;
        v.regionPan = region.pan - 64;
        v.timer = DsSynth.channelTimer(v.baseTimer, (v.key - v.baseKey) << 6);
        applySweep(v, tr, note, durationTicks);
        int slot = allocateChannel(v);
        if (slot < 0) { dbgDropped++; return; }
        v.serial = ++allocSerial;
        channels[slot] = v;
        dbgVoices++;
        int live = liveVoices();
        if (live > dbgMaxVoices) dbgMaxVoices = live;
    }

    /** Gota PlayNote sweep: SweepPitch + (PortamentoKey - key)*64; length from porta time or duration. */
    private static void applySweep(Voice v, Track tr, int key, int duration)
    {
        int sp = tr.sweepPitch;
        if (tr.portamentoOn) sp += (tr.portamentoKey - key) << 6;
        v.sweepPitch = sp;
        if (tr.portamentoTime != 0)
        {
            v.sweepLength = (tr.portamentoTime * tr.portamentoTime * Math.abs(v.sweepPitch)) >> 11;
            v.autoSweep = true;
        }
        else
        {
            v.sweepLength = duration;
            v.autoSweep = false;
        }
        v.sweepCounter = 0;
    }

    private Voice lastVoiceOnTrack(int trackId)
    {
        Voice best = null;
        for (int i = 0; i < MAX_VOICES; i++)
        {
            Voice c = channels[i];
            if (c == null || c.dead || c.trackId != trackId) continue;
            if (best == null || c.serial >= best.serial) best = c;
        }
        return best;
    }

    private void stopTrackVoices(int trackId)
    {
        for (int i = 0; i < MAX_VOICES; i++)
            if (channels[i] != null && channels[i].trackId == trackId)
                channels[i] = null;
    }

    /**
     * GotaSequenceLib Mixer.AllocateChannel: 16 hardware slots, type-restricted (PCM any, PSG 8–13,
     * noise 14–15). Prefer free, then releasing, then lowest track priority / quietest. The new note
     * is dropped if every candidate outranks it.
     */
    private int allocateChannel(Voice incoming)
    {
        int allowed = incoming.isNoise ? 0xC000 : incoming.isPsg ? 0x3F00 : 0xFFFF;
        int best = -1;
        int bestScore = Integer.MAX_VALUE;
        double bestVol = Double.MAX_VALUE;
        for (int i = 0; i < MAX_VOICES; i++)
        {
            if ((allowed & (1 << i)) == 0) continue;
            Voice c = channels[i];
            int score;
            double vol;
            if (c == null || c.dead) { score = -2; vol = 0; }
            else if (c.envState == 4) { score = -1; vol = c.volByte; }
            else { score = c.priority; vol = c.volByte; }
            if (best < 0 || score < bestScore || (score == bestScore && vol <= bestVol))
            {
                best = i;
                bestScore = score;
                bestVol = vol;
            }
        }
        if (best < 0) return -1;
        if (bestScore >= 0 && incoming.priority < bestScore) return -1;
        return best;
    }

    private int liveVoices()
    {
        int n = 0;
        for (int i = 0; i < MAX_VOICES; i++)
            if (channels[i] != null && !channels[i].dead) n++;
        return n;
    }

    // --------------------------------------------------------------- readers

    private int readVarLen(Track tr)
    {
        int value = 0, b;
        do { b = ev[tr.pc++] & 0xFF; value = (value << 7) | (b & 0x7F); } while ((b & 0x80) != 0);
        return value;
    }
    private int readU16(Track tr) { int v = (ev[tr.pc] & 0xFF) | ((ev[tr.pc + 1] & 0xFF) << 8); tr.pc += 2; return v; }
    private int readS16(Track tr) { return (short) readU16(tr); }
    private int readU24(Track tr) { int v = (ev[tr.pc] & 0xFF) | ((ev[tr.pc + 1] & 0xFF) << 8) | ((ev[tr.pc + 2] & 0xFF) << 16); tr.pc += 3; return v; }

    private static short clip16(int v)
    {
        if (v > 32767) v = 32767;
        if (v < -32768) v = -32768;
        return (short) v;
    }
}
