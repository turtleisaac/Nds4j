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
 * DS sound-driver synthesis primitives shared by {@link SequencePlayer}: the LFO sine table, the
 * GotaSequenceLib pitch-timer table, and the PSG duty-square and LFSR-noise generators. These match
 * NitroStudio2 / GotaSequenceLib. The driver interrupt runs at {@link #FRAME_RATE 192&nbsp;Hz};
 * sequence-tick LFO and non-auto sweep live in {@link SequencePlayer}.
 */
final class DsSynth
{
    private DsSynth() {}

    /** DS sound-driver interrupt rate; envelope and auto-sweep tick at this rate. */
    static final int FRAME_RATE = 192;
    /** GotaSequenceLib mixer sample rate. */
    static final int MIX_RATE = 65456;
    /** Mixer samples produced per 192 Hz driver frame (Gota {@code Mixer._samplesPerBuffer}). */
    static final int MIX_SAMPLES_PER_FRAME = 341;

    // quarter-wave sine, 0..127; full cycle is a 7-bit phase (0..0x7F)
    private static final byte[] SIN_TABLE = {
        0, 6, 12, 19, 25, 31, 37, 43, 49, 54, 60, 65, 71, 76, 81, 85,
        90, 94, 98, 102, 106, 109, 112, 115, 117, 120, 122, 123, 125, 126, 126, 127,
        127 };

    /** @return the DS LFO sine for a 7-bit phase index (0..0x7F), ranged -127..127. */
    static int sin(int index)
    {
        index &= 0x7F;
        if (index < 0x20) return SIN_TABLE[index];
        if (index < 0x40) return SIN_TABLE[0x40 - index];
        if (index < 0x60) return -SIN_TABLE[index - 0x40];
        return -SIN_TABLE[0x80 - index];
    }

    /** @return frequency multiplier for a pitch offset in 1/64-semitone units (768 = one octave). */
    static double pitchMultiplier(double units)
    {
        return Math.pow(2.0, units / 768.0);
    }

    /** MIDI note frequency (A4 = note 69 = 440 Hz), used as the PSG/noise reference pitch. */
    static double noteFrequency(double note)
    {
        return 440.0 * Math.pow(2.0, (note - 69.0) / 12.0);
    }

    /**
     * Advance an LFO phase by one 192-Hz frame. Phase is packed as a {@code u16}: the high byte is the
     * 7-bit {@link #sin} index, the low byte is the fraction.
     * @return the new phase
     */
    static int advanceLfoPhase(int phase, int speed)
    {
        int step = speed << 6;
        int counter = (phase + step) >> 8;
        while (counter >= 0x80) counter -= 0x80;
        int newPhase = (phase + step) & 0xFF;
        newPhase |= (counter << 8);
        return newPhase & 0xFFFF;
    }

    /** PSG square sample for an 8-step counter and a duty cycle 0..7. */
    static short psgSquare(int counter, int duty)
    {
        return (short) ((counter & 7) <= duty ? -0x8000 : 0x7FFF);
    }

    /** One step of the DS 16-bit noise LFSR (polynomial 0x6000). Returns the new state in {@code out[0]}. */
    /**
     * GotaSequenceLib {@code _pitchTable}: fractional timer scale for 0..767 pitch units
     * (one octave). {@link #channelTimer} uses this instead of {@code 2^(-pitch/768)}.
     */
    private static final int[] PITCH_TABLE = {
            0,    59,   118,   178,   237,   296,   356,   415,
          475,   535,   594,   654,   714,   773,   833,   893,
          953,  1013,  1073,  1134,  1194,  1254,  1314,  1375,
         1435,  1496,  1556,  1617,  1677,  1738,  1799,  1859,
         1920,  1981,  2042,  2103,  2164,  2225,  2287,  2348,
         2409,  2471,  2532,  2593,  2655,  2716,  2778,  2840,
         2902,  2963,  3025,  3087,  3149,  3211,  3273,  3335,
         3397,  3460,  3522,  3584,  3647,  3709,  3772,  3834,
         3897,  3960,  4022,  4085,  4148,  4211,  4274,  4337,
         4400,  4463,  4526,  4590,  4653,  4716,  4780,  4843,
         4907,  4971,  5034,  5098,  5162,  5226,  5289,  5353,
         5417,  5481,  5546,  5610,  5674,  5738,  5803,  5867,
         5932,  5996,  6061,  6125,  6190,  6255,  6320,  6384,
         6449,  6514,  6579,  6645,  6710,  6775,  6840,  6906,
         6971,  7037,  7102,  7168,  7233,  7299,  7365,  7431,
         7496,  7562,  7628,  7694,  7761,  7827,  7893,  7959,
         8026,  8092,  8159,  8225,  8292,  8358,  8425,  8492,
         8559,  8626,  8693,  8760,  8827,  8894,  8961,  9028,
         9096,  9163,  9230,  9298,  9366,  9433,  9501,  9569,
         9636,  9704,  9772,  9840,  9908,  9976, 10045, 10113,
        10181, 10250, 10318, 10386, 10455, 10524, 10592, 10661,
        10730, 10799, 10868, 10937, 11006, 11075, 11144, 11213,
        11283, 11352, 11421, 11491, 11560, 11630, 11700, 11769,
        11839, 11909, 11979, 12049, 12119, 12189, 12259, 12330,
        12400, 12470, 12541, 12611, 12682, 12752, 12823, 12894,
        12965, 13036, 13106, 13177, 13249, 13320, 13391, 13462,
        13533, 13605, 13676, 13748, 13819, 13891, 13963, 14035,
        14106, 14178, 14250, 14322, 14394, 14467, 14539, 14611,
        14684, 14756, 14829, 14901, 14974, 15046, 15119, 15192,
        15265, 15338, 15411, 15484, 15557, 15630, 15704, 15777,
        15850, 15924, 15997, 16071, 16145, 16218, 16292, 16366,
        16440, 16514, 16588, 16662, 16737, 16811, 16885, 16960,
        17034, 17109, 17183, 17258, 17333, 17408, 17483, 17557,
        17633, 17708, 17783, 17858, 17933, 18009, 18084, 18160,
        18235, 18311, 18387, 18462, 18538, 18614, 18690, 18766,
        18842, 18918, 18995, 19071, 19147, 19224, 19300, 19377,
        19454, 19530, 19607, 19684, 19761, 19838, 19915, 19992,
        20070, 20147, 20224, 20302, 20379, 20457, 20534, 20612,
        20690, 20768, 20846, 20924, 21002, 21080, 21158, 21236,
        21315, 21393, 21472, 21550, 21629, 21708, 21786, 21865,
        21944, 22023, 22102, 22181, 22260, 22340, 22419, 22498,
        22578, 22658, 22737, 22817, 22897, 22977, 23056, 23136,
        23216, 23297, 23377, 23457, 23537, 23618, 23698, 23779,
        23860, 23940, 24021, 24102, 24183, 24264, 24345, 24426,
        24507, 24589, 24670, 24752, 24833, 24915, 24996, 25078,
        25160, 25242, 25324, 25406, 25488, 25570, 25652, 25735,
        25817, 25900, 25982, 26065, 26148, 26230, 26313, 26396,
        26479, 26562, 26645, 26729, 26812, 26895, 26979, 27062,
        27146, 27230, 27313, 27397, 27481, 27565, 27649, 27733,
        27818, 27902, 27986, 28071, 28155, 28240, 28324, 28409,
        28494, 28579, 28664, 28749, 28834, 28919, 29005, 29090,
        29175, 29261, 29346, 29432, 29518, 29604, 29690, 29776,
        29862, 29948, 30034, 30120, 30207, 30293, 30380, 30466,
        30553, 30640, 30727, 30814, 30900, 30988, 31075, 31162,
        31249, 31337, 31424, 31512, 31599, 31687, 31775, 31863,
        31951, 32039, 32127, 32215, 32303, 32392, 32480, 32568,
        32657, 32746, 32834, 32923, 33012, 33101, 33190, 33279,
        33369, 33458, 33547, 33637, 33726, 33816, 33906, 33995,
        34085, 34175, 34265, 34355, 34446, 34536, 34626, 34717,
        34807, 34898, 34988, 35079, 35170, 35261, 35352, 35443,
        35534, 35626, 35717, 35808, 35900, 35991, 36083, 36175,
        36267, 36359, 36451, 36543, 36635, 36727, 36820, 36912,
        37004, 37097, 37190, 37282, 37375, 37468, 37561, 37654,
        37747, 37841, 37934, 38028, 38121, 38215, 38308, 38402,
        38496, 38590, 38684, 38778, 38872, 38966, 39061, 39155,
        39250, 39344, 39439, 39534, 39629, 39724, 39819, 39914,
        40009, 40104, 40200, 40295, 40391, 40486, 40582, 40678,
        40774, 40870, 40966, 41062, 41158, 41255, 41351, 41448,
        41544, 41641, 41738, 41835, 41932, 42029, 42126, 42223,
        42320, 42418, 42515, 42613, 42710, 42808, 42906, 43004,
        43102, 43200, 43298, 43396, 43495, 43593, 43692, 43790,
        43889, 43988, 44087, 44186, 44285, 44384, 44483, 44583,
        44682, 44781, 44881, 44981, 45081, 45180, 45280, 45381,
        45481, 45581, 45681, 45782, 45882, 45983, 46083, 46184,
        46285, 46386, 46487, 46588, 46690, 46791, 46892, 46994,
        47095, 47197, 47299, 47401, 47503, 47605, 47707, 47809,
        47912, 48014, 48117, 48219, 48322, 48425, 48528, 48631,
        48734, 48837, 48940, 49044, 49147, 49251, 49354, 49458,
        49562, 49666, 49770, 49874, 49978, 50082, 50187, 50291,
        50396, 50500, 50605, 50710, 50815, 50920, 51025, 51131,
        51236, 51341, 51447, 51552, 51658, 51764, 51870, 51976,
        52082, 52188, 52295, 52401, 52507, 52614, 52721, 52827,
        52934, 53041, 53148, 53256, 53363, 53470, 53578, 53685,
        53793, 53901, 54008, 54116, 54224, 54333, 54441, 54549,
        54658, 54766, 54875, 54983, 55092, 55201, 55310, 55419,
        55529, 55638, 55747, 55857, 55966, 56076, 56186, 56296,
        56406, 56516, 56626, 56736, 56847, 56957, 57068, 57179,
        57289, 57400, 57511, 57622, 57734, 57845, 57956, 58068,
        58179, 58291, 58403, 58515, 58627, 58739, 58851, 58964,
        59076, 59189, 59301, 59414, 59527, 59640, 59753, 59866,
        59979, 60092, 60206, 60319, 60433, 60547, 60661, 60774,
        60889, 61003, 61117, 61231, 61346, 61460, 61575, 61690,
        61805, 61920, 62035, 62150, 62265, 62381, 62496, 62612,
        62727, 62843, 62959, 63075, 63191, 63308, 63424, 63540,
        63657, 63774, 63890, 64007, 64124, 64241, 64358, 64476,
        64593, 64711, 64828, 64946, 65064, 65182, 65300, 65418
    };

    /**
     * DS channel TIMER from a base timer and pitch in 1/64-semitone units. Matches
     * GotaSequenceLib {@code GetChannelTimer}: higher pitch → smaller timer → faster playback.
     */
    static int channelTimer(int baseTimer, int pitchUnits)
    {
        int shift = 0;
        int pitch = -pitchUnits;
        while (pitch < 0)
        {
            shift--;
            pitch += 0x300;
        }
        while (pitch >= 0x300)
        {
            shift++;
            pitch -= 0x300;
        }
        long timer = (PITCH_TABLE[pitch] + 0x10000L) * (baseTimer & 0xFFFF);
        shift -= 16;
        if (shift <= 0)
            timer >>>= -shift;
        else if (shift < 32)
        {
            if ((timer & (-1L << (32 - shift))) != 0) return 0xFFFF;
            timer <<= shift;
        }
        else return 0xFFFF;
        if (timer < 0x10) return 0x10;
        if (timer > 0xFFFF) return 0xFFFF;
        return (int) timer;
    }

    static short noiseStep(int[] lfsr)
    {
        int c = lfsr[0] & 0xFFFF;
        short samp;
        if ((c & 1) != 0) { c = (c >> 1) ^ 0x6000; samp = -0x7FFF; }
        else { c = c >> 1; samp = 0x7FFF; }
        lfsr[0] = c & 0xFFFF;
        return samp;
    }
}
