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

package io.github.turtleisaac.nds4j.framework;

import java.io.ByteArrayOutputStream;

/**
 * The general-purpose <b>Nitro LZ77</b> codec (compression types {@code 0x10} = LZ10 and {@code 0x11} =
 * LZ11), the sliding-window compression the DS SDK ({@code NNS_UNCOMP} / the {@code LZ*.h} routines) uses
 * for NARC file members &mdash; graphics, maps, sometimes packed archives. This is distinct from
 * {@link CodeCompression}, which is the ARM9's <em>backward</em> BLZ variant; here the standard forward
 * header is {@code [type][u24 decompressedSize]} followed by flag-driven literal/back-reference blocks.
 * <p>
 * Some titles (first seen in Animal Crossing: Wild World's loose top-level {@code .nsbtx} files) wrap that
 * same header/stream in a 4-byte ASCII {@code "LZ77"} tag rather than emitting it bare at offset 0 &mdash;
 * i.e. {@code "LZ77"} followed by the ordinary {@code [type][u24 size]...} stream, unrelated to the
 * standalone {@code CompressLZ77.exe}-style tooling convention it's named after. {@link #isCompressed} and
 * {@link #decompress} detect and transparently skip this tag; {@link #compressLz77Tagged} /
 * {@link #compressLz11Lz77Tagged} re-emit it for a caller that needs to preserve the wrapper on write-back.
 * <p>
 * {@link #decompress} reads both types (tagged or not); {@link #compress}/{@link #compressLz11} produce
 * byte-valid streams that decode back identically ({@code decompress(compress(x)).equals(x)}). Nintendo's
 * exact match heuristics are not reproduced, so a re-compressed file is <em>valid</em>, not byte-identical
 * to the original &mdash; the correctness bar is the round-trip, matching the rest of the library's
 * editing model.
 */
public final class NitroLz
{
    private NitroLz() {}

    private static final int MIN_MATCH = 3;
    private static final int WINDOW = 0x1000;   // 4 KiB back-reference window (12-bit displacement)
    private static final byte[] LZ77_TAG = {'L', 'Z', '7', '7'};

    private static boolean hasLz77Tag(byte[] data)
    {
        if (data.length < LZ77_TAG.length) return false;
        for (int i = 0; i < LZ77_TAG.length; i++)
            if (data[i] != LZ77_TAG[i]) return false;
        return true;
    }

    private static boolean isNitroLzHeader(byte[] data, int off)
    {
        if (data.length < off + 4) return false;
        int type = data[off] & 0xFF;
        if (type != 0x10 && type != 0x11) return false;
        long size = (data[off + 1] & 0xFF) | ((data[off + 2] & 0xFF) << 8) | ((data[off + 3] & 0xFF) << 16);
        return size > 0;
    }

    /**
     * @return true if {@code data} carries a Nitro LZ10/LZ11 header (optionally behind a {@code "LZ77"}
     * tag) with a plausible decompressed size.
     */
    public static boolean isCompressed(byte[] data)
    {
        if (data == null || data.length < 4) return false;
        if (hasLz77Tag(data)) return isNitroLzHeader(data, LZ77_TAG.length);
        return isNitroLzHeader(data, 0);
    }

    /**
     * Decompresses a Nitro LZ10 or LZ11 stream, optionally behind a {@code "LZ77"} tag (see the class doc).
     * @param data the compressed bytes: {@code [type][u24 size]...}, or {@code "LZ77"[type][u24 size]...}
     * @return the decompressed bytes
     * @throws IllegalArgumentException if the header is not a supported LZ type
     */
    public static byte[] decompress(byte[] data)
    {
        return decompressAt(data, hasLz77Tag(data) ? LZ77_TAG.length : 0);
    }

    private static byte[] decompressAt(byte[] data, int headerOffset)
    {
        int type = data[headerOffset] & 0xFF;
        if (type != 0x10 && type != 0x11)
            throw new IllegalArgumentException(String.format("not a Nitro LZ stream (type 0x%02X)", type));
        int size = (data[headerOffset + 1] & 0xFF) | ((data[headerOffset + 2] & 0xFF) << 8)
                | ((data[headerOffset + 3] & 0xFF) << 16);
        byte[] out = new byte[size];
        int op = 0, ip = headerOffset + 4;
        while (op < size)
        {
            int flags = data[ip++] & 0xFF;
            for (int bit = 0; bit < 8 && op < size; bit++)
            {
                if ((flags & (0x80 >> bit)) == 0)
                {
                    out[op++] = data[ip++]; // literal
                    continue;
                }
                int len, disp;
                int b0 = data[ip++] & 0xFF;
                if (type == 0x10)
                {
                    int b1 = data[ip++] & 0xFF;
                    len = (b0 >> 4) + MIN_MATCH;
                    disp = (((b0 & 0xF) << 8) | b1) + 1;
                }
                else // LZ11: variable-length token by the top nibble
                {
                    int indicator = b0 >> 4;
                    if (indicator == 0)
                    {
                        int b1 = data[ip++] & 0xFF, b2 = data[ip++] & 0xFF;
                        len = (((b0 & 0xF) << 4) | (b1 >> 4)) + 0x11;
                        disp = (((b1 & 0xF) << 8) | b2) + 1;
                    }
                    else if (indicator == 1)
                    {
                        int b1 = data[ip++] & 0xFF, b2 = data[ip++] & 0xFF, b3 = data[ip++] & 0xFF;
                        len = (((b0 & 0xF) << 12) | (b1 << 4) | (b2 >> 4)) + 0x111;
                        disp = (((b2 & 0xF) << 8) | b3) + 1;
                    }
                    else
                    {
                        int b1 = data[ip++] & 0xFF;
                        len = indicator + 1;
                        disp = (((b0 & 0xF) << 8) | b1) + 1;
                    }
                }
                for (int k = 0; k < len && op < size; k++, op++)
                    out[op] = out[op - disp];
            }
        }
        return out;
    }

    /** Compresses with LZ10 (type {@code 0x10}). @param data the raw bytes @return the compressed stream. */
    public static byte[] compress(byte[] data) { return encode(data, false); }

    /** Compresses with LZ11 (type {@code 0x11}). @param data the raw bytes @return the compressed stream. */
    public static byte[] compressLz11(byte[] data) { return encode(data, true); }

    /**
     * Compresses with LZ10, wrapped in the {@code "LZ77"} tag (see the class doc) &mdash; for writing back
     * over a file whose original bytes carried that tag, so the wrapper is preserved rather than dropped.
     * @param data the raw bytes @return the tagged, compressed stream.
     */
    public static byte[] compressLz77Tagged(byte[] data) { return tagged(encode(data, false)); }

    /** {@code "LZ77"}-tagged counterpart of {@link #compressLz11}. @param data the raw bytes @return the tagged, compressed stream. */
    public static byte[] compressLz11Lz77Tagged(byte[] data) { return tagged(encode(data, true)); }

    private static byte[] tagged(byte[] body)
    {
        byte[] out = new byte[LZ77_TAG.length + body.length];
        System.arraycopy(LZ77_TAG, 0, out, 0, LZ77_TAG.length);
        System.arraycopy(body, 0, out, LZ77_TAG.length, body.length);
        return out;
    }

    // Greedy longest-match encoder for both types. Produces a valid stream (round-trips through decompress);
    // it does not reproduce Nintendo's exact match choices, so it is not byte-identical to a retail file.
    private static byte[] encode(byte[] data, boolean lz11)
    {
        int maxLen = lz11 ? 0x10110 : 0x12;   // LZ11 tokens reach 0x10110; LZ10 caps at 18
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(lz11 ? 0x11 : 0x10);
        out.write(data.length & 0xFF);
        out.write((data.length >> 8) & 0xFF);
        out.write((data.length >> 16) & 0xFF);

        int pos = 0;
        while (pos < data.length)
        {
            int flagIndex = out.size();
            out.write(0); // placeholder flag byte
            int flags = 0;
            for (int bit = 0; bit < 8 && pos < data.length; bit++)
            {
                int[] match = longestMatch(data, pos, maxLen);
                int len = match[0], disp = match[1];
                if (len >= MIN_MATCH)
                {
                    flags |= 0x80 >> bit;
                    writeToken(out, len, disp, lz11);
                    pos += len;
                }
                else
                {
                    out.write(data[pos++]);
                }
            }
            byte[] buf = out.toByteArray();
            buf[flagIndex] = (byte) flags;
            out.reset();
            out.write(buf, 0, buf.length);
        }
        return out.toByteArray();
    }

    private static void writeToken(ByteArrayOutputStream out, int len, int disp, boolean lz11)
    {
        int d = disp - 1;
        if (!lz11)
        {
            out.write(((len - MIN_MATCH) << 4) | (d >> 8));
            out.write(d & 0xFF);
            return;
        }
        if (len <= 0x10)
        {
            out.write(((len - 1) << 4) | (d >> 8));
            out.write(d & 0xFF);
        }
        else if (len <= 0x110)
        {
            int l = len - 0x11;
            out.write(l >> 4);
            out.write(((l & 0xF) << 4) | (d >> 8));
            out.write(d & 0xFF);
        }
        else
        {
            int l = len - 0x111;
            out.write(0x10 | (l >> 12));
            out.write((l >> 4) & 0xFF);
            out.write(((l & 0xF) << 4) | (d >> 8));
            out.write(d & 0xFF);
        }
    }

    // Finds the longest back-reference for the data at pos within the 4 KiB window (returns {len, disp}).
    private static int[] longestMatch(byte[] data, int pos, int maxLen)
    {
        int bestLen = 0, bestDisp = 0;
        int start = Math.max(0, pos - WINDOW);
        int limit = Math.min(maxLen, data.length - pos);
        for (int cand = pos - 1; cand >= start; cand--)
        {
            int len = 0;
            while (len < limit && data[cand + len] == data[pos + len]) len++;
            if (len > bestLen)
            {
                bestLen = len;
                bestDisp = pos - cand;
                if (len >= limit) break;
            }
        }
        return new int[]{bestLen, bestDisp};
    }
}
