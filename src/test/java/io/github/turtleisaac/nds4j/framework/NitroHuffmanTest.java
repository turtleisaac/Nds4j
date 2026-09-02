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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests {@link NitroHuffman} (the Nitro Huffman 4-bit/8-bit codec). Unlike {@link NitroLzTest}, there is
 * no "decodes real retail files" test here: a scan of every ROM in this project's test fixtures found
 * only false-positive header matches (the same class of coincidental byte pattern {@code isCompressed()}
 * is already known to occasionally match, per {@link NitroLz}) -- none of Diamond, Platinum, HeartGold,
 * SoulSilver, White2, or the newer non-Pokemon titles appear to actually use Huffman compression anywhere
 * this suite can find it. The correctness bar here is therefore the round-trip
 * ({@code decompress(compress(x)) == x}), same as {@link NitroLz}, cross-checked against the DSDecmp
 * reference implementation's exact tree-addressing scheme (see {@link NitroHuffman}'s class doc).
 */
@DisplayName("NitroHuffman (Nitro Huffman 4-bit/8-bit codec)")
public class NitroHuffmanTest
{
    @Test
    @DisplayName("compress -> decompress is the identity for 4-bit and 8-bit block sizes")
    void roundTripBothBlockSizes()
    {
        byte[] repetitive = new byte[4000];
        for (int i = 0; i < repetitive.length; i++) repetitive[i] = (byte) ("NITRO".charAt(i % 5));
        // a large, low-cardinality alphabet: realistic for Huffman's actual Nitro use case (small
        // tables/indices), and narrow enough to stay within the format's 6-bit tree-offset field even
        // for 8-bit block size (unlike near-uniform high-entropy data -- see the dedicated test below,
        // which is why plain random/full-range-cycling data isn't a case here).
        byte[] skewed = new byte[50000];
        Random skewRandom = new Random(7);
        for (int i = 0; i < skewed.length; i++) skewed[i] = (byte) skewRandom.nextInt(20);

        byte[][] cases = {new byte[0], new byte[]{7}, new byte[]{5, 5, 5, 5, 5, 5, 5, 5},
                "AAAAAAAAAAAAAAAABBBBBBBBBBBBCCCCCCCCCCCDDDDDDDDDD".getBytes(StandardCharsets.US_ASCII),
                repetitive, skewed};
        for (byte[] c : cases)
        {
            byte[] h4 = NitroHuffman.compress4Bit(c);
            byte[] h8 = NitroHuffman.compress8Bit(c);
            assertThat(h4[0] & 0xFF).isEqualTo(0x24);
            assertThat(h8[0] & 0xFF).isEqualTo(0x28);
            assertThat(NitroHuffman.decompress(h4)).as("4-bit round-trip, len %d", c.length).isEqualTo(c);
            assertThat(NitroHuffman.decompress(h8)).as("8-bit round-trip, len %d", c.length).isEqualTo(c);
        }

        // full-alphabet-range data is still fine for 4-bit block size (only 16 symbols either way);
        // 8-bit block size over data this high-entropy is covered by refusesToEncodeAnUnrepresentableTree.
        byte[] random = new byte[5000];
        new Random(42).nextBytes(random);
        byte[] structured = new byte[8192];
        for (int i = 0; i < structured.length; i++) structured[i] = (byte) ((i / 16) & 0xFF);
        for (byte[] c : new byte[][]{random, structured})
            assertThat(NitroHuffman.decompress(NitroHuffman.compress4Bit(c)))
                    .as("4-bit round-trip, len %d", c.length).isEqualTo(c);
    }

    @Test
    @DisplayName("compressible data actually shrinks")
    void compresses()
    {
        byte[] runs = new byte[10000]; // a single repeated byte: the most compressible input possible
        byte[] h4 = NitroHuffman.compress4Bit(runs);
        byte[] h8 = NitroHuffman.compress8Bit(runs);
        // 4-bit block size can't beat ~1 bit/nibble here (only 2 tree leaves: the real symbol plus a
        // padding leaf the bitstream never selects -- see compress()'s doc), so length/4 is close to
        // the achievable floor, not a loose bound; 8-bit block size does much better (~1 bit/byte).
        assertThat(h4.length).as("4-bit compresses a run of zeros").isLessThan(runs.length / 3);
        assertThat(h8.length).as("8-bit compresses a run of zeros").isLessThan(runs.length / 4);
        assertThat(NitroHuffman.decompress(h4)).isEqualTo(runs);
        assertThat(NitroHuffman.decompress(h8)).isEqualTo(runs);
    }

    @Test
    @DisplayName("isCompressed() requires a fitting tree table, not just a plausible header byte")
    void isCompressedSanityChecksTheTree()
    {
        byte[] valid = NitroHuffman.compress8Bit("hello nitro huffman".getBytes(StandardCharsets.US_ASCII));
        assertThat(NitroHuffman.isCompressed(valid)).isTrue();

        // same header, but the declared tree table extends past the end of the buffer
        byte[] truncated = new byte[]{0x28, 10, 0, 0, (byte) 0xFF};
        assertThat(NitroHuffman.isCompressed(truncated)).isFalse();

        assertThat(NitroHuffman.isCompressed(new byte[]{0x10, 1, 0, 0})).as("an LZ header, not Huffman").isFalse();
        assertThat(NitroHuffman.isCompressed(null)).isFalse();
        assertThat(NitroHuffman.isCompressed(new byte[]{0x24, 0, 0})).as("too short to even have a size").isFalse();
    }

    @Test
    @DisplayName("a tree too wide for the format's 6-bit offset field fails loudly, not silently")
    void refusesToEncodeAnUnrepresentableTree()
    {
        // near-uniform over the full 8-bit alphabet: a balanced 256-leaf tree's widest BFS frontier
        // exceeds what a 6-bit offset can address (see NitroHuffman.compress's doc).
        byte[] wide = new byte[20000];
        new Random(1).nextBytes(wide);
        assertThatThrownBy(() -> NitroHuffman.compress8Bit(wide))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too wide");
        // the same bytes as a 4-bit alphabet (16 symbols) stay well within the limit
        assertThat(NitroHuffman.decompress(NitroHuffman.compress4Bit(wide))).isEqualTo(wide);
    }

    @Test
    @DisplayName("rejects a non-Huffman header")
    void rejectsWrongType()
    {
        assertThatThrownBy(() -> NitroHuffman.decompress(new byte[]{0x10, 0, 0, 0}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
