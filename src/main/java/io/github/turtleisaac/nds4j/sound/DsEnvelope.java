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
 * Nintendo DS envelope tables and conversions used by {@link SequencePlayer} and
 * {@link SoundFontExporter}.
 * <p>
 * The live synth ({@link #attackRate}, {@link #getFallingRate}, {@link #SUSTAIN_TABLE},
 * {@link #channelVolume}) matches GotaSequenceLib / NitroStudio2 Deluxe
 * ({@code AttackTable}, {@code DecayTable}, {@code SustainTable}, {@code GetChannelVolume}).
 * {@link #attackSeconds}/{@link #decaySeconds}/{@link #releaseSeconds} keep the VGMTrans
 * interrupt-tick conversion for SoundFont 2 generators (SF2 wants seconds, not the 192 Hz
 * attenuation step).
 */
final class DsEnvelope
{
    private DsEnvelope() {}

    /** Seconds per envelope interrupt tick (the sound driver's update rate). */
    static final double INTR_FREQUENCY = (2728.0 * 64.0) / 33513982.0; // ~= 0.0052095 s

    /** The envelope's internal full-scale value; A/D/R counts run from here. */
    private static final long FULL = 0x16980;

    /** Sustain level as tenths of a decibel of attenuation, indexed by the 0..127 sustain register. */
    private static final short[] DECIBEL_SQUARE_TABLE = {
        -481, -480, -480, -480, -480, -480, -480, -480, -480, -460, -442, -425, -410, -396, -383, -371,
        -360, -349, -339, -330, -321, -313, -305, -297, -289, -282, -276, -269, -263, -257, -251, -245,
        -239, -234, -229, -224, -219, -214, -210, -205, -201, -196, -192, -188, -184, -180, -176, -173,
        -169, -165, -162, -158, -155, -152, -149, -145, -142, -139, -136, -133, -130, -127, -125, -122,
        -119, -116, -114, -111, -109, -106, -103, -101, -99,  -96,  -94,  -91,  -89,  -87,  -85,  -82,
        -80,  -78,  -76,  -74,  -72,  -70,  -68,  -66,  -64,  -62,  -60,  -58,  -56,  -54,  -52,  -50,
        -49,  -47,  -45,  -43,  -42,  -40,  -38,  -36,  -35,  -33,  -31,  -30,  -28,  -27,  -25,  -23,
        -22,  -20,  -19,  -17,  -16,  -14,  -13,  -11,  -10,  -8,   -7,   -6,   -4,   -3,   -1,   0 };

    private static final int[] ATTACK_TIME_TABLE = {
        0x00, 0x01, 0x05, 0x0E, 0x1A, 0x26, 0x33, 0x3F, 0x49, 0x54,
        0x5C, 0x64, 0x6D, 0x74, 0x7B, 0x7F, 0x84, 0x89, 0x8F };

    /** @return attack duration in seconds for the 0..127 attack register value. */
    static double attackSeconds(int attack)
    {
        int a = attack & 0x7F;
        int realAttack = 0xFF - a;
        if (a >= 0x6D) realAttack = ATTACK_TIME_TABLE[0x7F - a];
        int count = 0;
        for (long i = FULL; i > FULL / 10; i = (i * realAttack) >> 8)
        {
            count++;
            if (count > 1000000) break; // safety; realAttack < 256 always makes this terminate
        }
        return count * INTR_FREQUENCY;
    }

    /** @return decay duration in seconds for the 0..127 decay register value. */
    static double decaySeconds(int decay)
    {
        int d = decay & 0x7F;
        if (d == 0x7F) return 0.001;
        int rate = getFallingRate(d);
        return (FULL / rate) * INTR_FREQUENCY;
    }

    /** @return release duration in seconds for the 0..127 release register value. */
    static double releaseSeconds(int release)
    {
        int rate = getFallingRate(release & 0x7F);
        return (FULL / rate) * INTR_FREQUENCY;
    }

    /** @return sustain amplitude (0..1, linear) for the 0..127 sustain register value. */
    static double sustainLevel(int sustain)
    {
        int s = sustain & 0x7F;
        if (s == 0x7F) return 1.0;
        if (s == 0) return 0.0;
        double dB = DECIBEL_SQUARE_TABLE[s] / 10.0;
        return Math.pow(10.0, dB / 20.0);
    }

    /** Fully-attenuated envelope value (silence); the envelope "velocity" runs from 0 (full) down to this. */
    static final int ENV_SILENT = -92544;

    /** Sustain register (0..127) → envelope attenuation "velocity" (0 = full, -92544 = silent). */
    static final int[] SUSTAIN_TABLE = {
        -92544, -92416, -92288, -83328, -76928, -71936, -67840, -64384,
        -61440, -58880, -56576, -54400, -52480, -50688, -49024, -47488,
        -46080, -44672, -43392, -42240, -41088, -40064, -39040, -38016,
        -36992, -36096, -35328, -34432, -33664, -32896, -32128, -31360,
        -30592, -29952, -29312, -28672, -28032, -27392, -26880, -26240,
        -25728, -25088, -24576, -24064, -23552, -23040, -22528, -22144,
        -21632, -21120, -20736, -20224, -19840, -19456, -19072, -18560,
        -18176, -17792, -17408, -17024, -16640, -16256, -16000, -15616,
        -15232, -14848, -14592, -14208, -13952, -13568, -13184, -12928,
        -12672, -12288, -12032, -11648, -11392, -11136, -10880, -10496,
        -10240, -9984, -9728, -9472, -9216, -8960, -8704, -8448,
        -8192, -7936, -7680, -7424, -7168, -6912, -6656, -6400,
        -6272, -6016, -5760, -5504, -5376, -5120, -4864, -4608,
        -4480, -4224, -3968, -3840, -3584, -3456, -3200, -2944,
        -2816, -2560, -2432, -2176, -2048, -1792, -1664, -1408,
        -1280, -1024, -896, -768, -512, -384, -128, 0 };

    /**
     * GotaSequenceLib {@code AttackTable}: attack register 0..127 → the DS attack multiplier.
     * Identical to {@code (a &gt;= 0x6D) ? ATTACK_TIME_TABLE[0x7F - a] : (0xFF - a)}.
     */
    static final int[] ATTACK_TABLE = {
        255, 254, 253, 252, 251, 250, 249, 248,
        247, 246, 245, 244, 243, 242, 241, 240,
        239, 238, 237, 236, 235, 234, 233, 232,
        231, 230, 229, 228, 227, 226, 225, 224,
        223, 222, 221, 220, 219, 218, 217, 216,
        215, 214, 213, 212, 211, 210, 209, 208,
        207, 206, 205, 204, 203, 202, 201, 200,
        199, 198, 197, 196, 195, 194, 193, 192,
        191, 190, 189, 188, 187, 186, 185, 184,
        183, 182, 181, 180, 179, 178, 177, 176,
        175, 174, 173, 172, 171, 170, 169, 168,
        167, 166, 165, 164, 163, 162, 161, 160,
        159, 158, 157, 156, 155, 154, 153, 152,
        151, 150, 149, 148, 147, 143, 137, 132,
        127, 123, 116, 109, 100, 92, 84, 73,
        63, 51, 38, 26, 14, 5, 1, 0
    };

    /**
     * GotaSequenceLib {@code DecayTable}: decay/release register 0..127 → attenuation step per 192 Hz
     * driver frame. Hardware uses this table; the VGMTrans {@code getFallingRate} formula is a close
     * approximation that differs by 1 in the mid range (e.g. 80 → 167 vs 166).
     */
    static final int[] DECAY_TABLE = {
        1, 3, 5, 7, 9, 11, 13, 15,
        17, 19, 21, 23, 25, 27, 29, 31,
        33, 35, 37, 39, 41, 43, 45, 47,
        49, 51, 53, 55, 57, 59, 61, 63,
        65, 67, 69, 71, 73, 75, 77, 79,
        81, 83, 85, 87, 89, 91, 93, 95,
        97, 99, 101, 102, 104, 105, 107, 108,
        110, 111, 113, 115, 116, 118, 120, 122,
        124, 126, 128, 130, 132, 135, 137, 140,
        142, 145, 148, 151, 154, 157, 160, 163,
        167, 171, 175, 179, 183, 187, 192, 197,
        202, 208, 213, 219, 226, 233, 240, 248,
        256, 265, 274, 284, 295, 307, 320, 334,
        349, 366, 384, 404, 427, 452, 480, 512,
        549, 591, 640, 698, 768, 853, 960, 1097,
        1280, 1536, 1920, 2560, 3840, 7680, 15360, 65535
    };

    /** Attack register (0..127) → the DS attack multiplier used by the envelope's attack phase. */
    static int attackRate(int attack)
    {
        return ATTACK_TABLE[attack & 0x7F];
    }

    /**
     * Convert an envelope "velocity" attenuation (0 = full, -92544 = silent) to a linear amplitude.
     * Derived from matching Gota7's SustainTable to the decibel-square table: {@code 10^(velocity/25600)}.
     */
    static double velocityToAmp(int velocity)
    {
        if (velocity <= ENV_SILENT) return 0.0;
        if (velocity >= 0) return 1.0;
        return Math.pow(10.0, velocity / 25600.0);
    }

    /** The DS "falling rate" for a decay/release register value (GotaSequenceLib {@code DecayTable}). */
    static int getFallingRate(int t)
    {
        return DECAY_TABLE[t & 0x7F];
    }

    private static final byte[] VOLUME_TABLE = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3,
        3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
        3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
        4, 4, 4, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 5,
        5, 5, 5, 5, 5, 5, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6,
        6, 6, 6, 6, 6, 6, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 8, 8, 8, 8, 8, 8, 8, 8, 8, 9, 9, 9, 9,
        9, 9, 9, 9, 9, 10, 10, 10, 10, 10, 10, 10, 10, 11, 11, 11,
        11, 11, 11, 11, 11, 12, 12, 12, 12, 12, 12, 12, 13, 13, 13, 13,
        13, 13, 13, 14, 14, 14, 14, 14, 14, 15, 15, 15, 15, 15, 16, 16,
        16, 16, 16, 16, 17, 17, 17, 17, 17, 18, 18, 18, 18, 19, 19, 19,
        19, 19, 20, 20, 20, 20, 21, 21, 21, 21, 22, 22, 22, 22, 23, 23,
        23, 23, 24, 24, 24, 25, 25, 25, 25, 26, 26, 26, 27, 27, 27, 28,
        28, 28, 29, 29, 29, 30, 30, 30, 31, 31, 31, 32, 32, 33, 33, 33,
        34, 34, 35, 35, 35, 36, 36, 37, 37, 38, 38, 38, 39, 39, 40, 40,
        41, 41, 42, 42, 43, 43, 44, 44, 45, 45, 46, 46, 47, 47, 48, 48,
        49, 50, 50, 51, 51, 52, 52, 53, 54, 54, 55, 56, 56, 57, 58, 58,
        59, 60, 60, 61, 62, 62, 63, 64, 65, 66, 66, 67, 68, 69, 70, 70,
        71, 72, 73, 74, 75, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85,
        86, 87, 88, 89, 90, 91, 92, 93, 94, 95, 96, 97, 98, 99, 101, 102,
        103, 104, 105, 106, 108, 109, 110, 111, 113, 114, 115, 117, 118, 119, 121, 122,
        124, 125, 126, 127
    };

    /**
     * NitroStudio2 / GotaSequenceLib {@code GetChannelVolume}: map summed attenuation to the
     * hardware 0..127 channel volume. The synth uses this instead of a second dB-to-linear
     * conversion, so decay/release actually reach silence instead of hanging as a quiet wash.
     */
    static int channelVolume(int vol)
    {
        int a = vol / 0x80;
        if (a < -723) a = -723;
        else if (a > 0) a = 0;
        return VOLUME_TABLE[a + 723] & 0xFF;
    }

    /** {@link #channelVolume} as a 0..1 linear amplitude (127 = full). */
    static double channelAmp(int vol)
    {
        return channelVolume(vol) / 127.0;
    }

}
