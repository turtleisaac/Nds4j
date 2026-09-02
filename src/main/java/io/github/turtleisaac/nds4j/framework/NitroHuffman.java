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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * The Nitro <b>Huffman</b> codec (compression types {@code 0x24} = 4-bit and {@code 0x28} = 8-bit
 * symbols), the sibling of {@link NitroLz} in the Nitro SDK's compression family (LZ10/LZ11, Huffman,
 * RLE all share the same {@code [type][u24 decompressedSize]} header convention). Layout (matching the
 * well-known GBATEK description, cross-checked against the DSDecmp reference decoder/encoder every
 * DS-era tool derives from):
 * <pre>
 * u8  type            0x24 (4-bit symbols) or 0x28 (8-bit symbols)
 * u24 decompressedSize (little-endian; if 0, an extra u32 follows with the real size)
 * u8  treeSizeByte     real tree-table byte count = (treeSizeByte + 1) * 2, minus one (see below)
 * ...tree table...     one byte per node, breadth-first from the root; a non-data node's byte is
 *                       {@code offset(6 bits) | node1IsData(bit 6) | node0IsData(bit 7)}, where
 *                       {@code child0Addr = (thisNodeAddr &amp; ~1) + offset*2 + 2} (child1 immediately
 *                       follows); a data node's byte is the symbol itself
 * ...bitstream...      32-bit little-endian words, MSB (bit 31) read first; 0 = take child0, 1 = child1
 * </pre>
 * The declared tree size looks like it should leave the table even-aligned, but a full binary
 * tree with {@code L} leaves always serialises to exactly {@code 2L-1} (odd) bytes; the bitstream
 * actually starts immediately after that last real tree byte, one earlier than the naive
 * {@code (treeSizeByte+1)*2} byte count would suggest &mdash; i.e. the tree-table end used for both the
 * "is this node address still inside the tree" bound <em>and</em> the bitstream start is
 * {@code headerEnd + (treeSizeByte+1)*2}, where {@code headerEnd} is the position of the treeSizeByte
 * itself (not one past it). Verified against this project's round-trip harness, not just derived from
 * the spec text.
 * <p>
 * {@link #decompress} reads both symbol sizes; {@link #compress4Bit}/{@link #compress8Bit} produce
 * byte-valid streams that decode back identically, using an ordinary (non-canonical) Huffman tree, so a
 * re-compressed file is not byte-identical to a retail one &mdash; same correctness bar as
 * {@link NitroLz}.
 */
public final class NitroHuffman
{
    private NitroHuffman() {}

    /**
     * @return true if {@code data} carries a Nitro Huffman header (type {@code 0x24} or {@code 0x28})
     * with a plausible decompressed size and a declared tree table that actually fits in the buffer.
     * Like {@link NitroLz#isCompressed}, this is a heuristic (there's no distinguishing ASCII magic to
     * check against) -- a false positive still needs a caller to catch the {@link IllegalArgumentException}
     * {@link #decompress} throws when it walks off the end of a bogus "tree".
     */
    public static boolean isCompressed(byte[] data)
    {
        if (data == null || data.length < 5) return false;
        int type = data[0] & 0xFF;
        if (type != 0x24 && type != 0x28) return false;
        long size = u24(data, 1);
        int headerEnd = 4;
        if (size == 0)
        {
            if (data.length < 9) return false;
            size = u32(data, 4);
            headerEnd = 8;
        }
        if (size <= 0 || data.length <= headerEnd) return false;
        int treeSizeByte = data[headerEnd] & 0xFF;
        long treeEnd = headerEnd + (treeSizeByte + 1) * 2L;
        return treeEnd <= data.length;
    }

    /**
     * Decompresses a Nitro Huffman (4-bit or 8-bit) stream.
     * @param data the compressed bytes (starting with the {@code [type][u24 size]} header)
     * @return the decompressed bytes
     * @throws IllegalArgumentException if the header is not a supported Huffman type, or the stream is truncated/corrupt
     */
    public static byte[] decompress(byte[] data)
    {
        int type = data[0] & 0xFF;
        int blockBits;
        if (type == 0x24) blockBits = 4;
        else if (type == 0x28) blockBits = 8;
        else throw new IllegalArgumentException(String.format("not a Nitro Huffman stream (type 0x%02X)", type));

        long size = u24(data, 1);
        int headerEnd = 4; // position of the treeSizeByte
        if (size == 0)
        {
            size = u32(data, 4);
            headerEnd = 8;
        }

        int treeSizeByte = data[headerEnd] & 0xFF;
        int treeTableStart = headerEnd + 1;      // the root node's own address
        int treeEnd = headerEnd + (treeSizeByte + 1) * 2; // exclusive bound; also where the bitstream begins

        byte[] out = new byte[(int) size];
        int op = 0, ip = treeEnd;
        int word = 0, bitsLeft = 0;
        int cachedNibble = -1;

        while (op < size)
        {
            int nodeAddr = treeTableStart;
            boolean isData = false;
            while (!isData)
            {
                if (bitsLeft == 0)
                {
                    if (ip + 4 > data.length)
                        throw new IllegalArgumentException("truncated Nitro Huffman bitstream");
                    word = (data[ip] & 0xFF) | ((data[ip + 1] & 0xFF) << 8)
                            | ((data[ip + 2] & 0xFF) << 16) | ((data[ip + 3] & 0xFF) << 24);
                    ip += 4;
                    bitsLeft = 32;
                }
                bitsLeft--;
                boolean bitIsOne = ((word >>> bitsLeft) & 1) != 0;

                if (nodeAddr < 0 || nodeAddr >= treeEnd || nodeAddr >= data.length)
                    throw new IllegalArgumentException("Nitro Huffman tree reference out of bounds");
                int raw = data[nodeAddr] & 0xFF;
                int offset = raw & 0x3F;
                boolean zeroIsData = (raw & 0x80) != 0;
                boolean oneIsData = (raw & 0x40) != 0;
                int zeroAddr = (nodeAddr & ~1) + offset * 2 + 2;

                if (bitIsOne) { nodeAddr = zeroAddr + 1; isData = oneIsData; }
                else { nodeAddr = zeroAddr; isData = zeroIsData; }
            }
            if (nodeAddr < 0 || nodeAddr >= data.length)
                throw new IllegalArgumentException("Nitro Huffman tree reference out of bounds");
            int symbol = data[nodeAddr] & 0xFF;

            if (blockBits == 8)
            {
                out[op++] = (byte) symbol;
            }
            else if (cachedNibble < 0)
            {
                cachedNibble = symbol & 0xF;
            }
            else
            {
                out[op++] = (byte) ((cachedNibble << 4) | (symbol & 0xF));
                cachedNibble = -1;
            }
        }
        return out;
    }

    /** Compresses with 4-bit Huffman (type {@code 0x24}). @param data the raw bytes @return the compressed stream. */
    public static byte[] compress4Bit(byte[] data) { return compress(data, 4); }

    /** Compresses with 8-bit Huffman (type {@code 0x28}). @param data the raw bytes @return the compressed stream. */
    public static byte[] compress8Bit(byte[] data) { return compress(data, 8); }

    private static byte[] compress(byte[] data, int blockBits)
    {
        if (data.length > 0xFFFFFF)
            throw new IllegalArgumentException("input too large for a 24-bit Nitro Huffman size field");

        int alphabet = 1 << blockBits; // 16 or 256
        long[] freq = new long[alphabet];
        if (blockBits == 8)
        {
            for (byte b : data) freq[b & 0xFF]++;
        }
        else
        {
            for (byte b : data) { freq[(b >> 4) & 0xF]++; freq[b & 0xF]++; }
        }

        Node[] leafBySymbol = new Node[alphabet];
        List<Node> leaves = new ArrayList<>();
        for (int i = 0; i < alphabet; i++)
        {
            if (freq[i] == 0) continue;
            Node leaf = Node.leaf(i, freq[i]);
            leafBySymbol[i] = leaf;
            leaves.add(leaf);
        }
        // A valid tree's root is always a non-data (branch) node, which needs >= 2 leaves; pad
        // degenerate inputs (empty data, or data using only one distinct symbol/nibble) with
        // zero-frequency filler leaves that the bitstream never actually selects.
        for (int i = 0; i < alphabet && leaves.size() < 2; i++)
        {
            if (freq[i] != 0) continue;
            Node leaf = Node.leaf(i, 0);
            leafBySymbol[i] = leaf;
            leaves.add(leaf);
        }

        PriorityQueue<Node> queue = new PriorityQueue<>(Comparator.comparingLong(n -> n.freq));
        queue.addAll(leaves);
        int nodeCount = leaves.size();
        while (queue.size() > 1)
        {
            Node a = queue.poll(), b = queue.poll();
            Node parent = Node.branch(a, b);
            a.parent = parent;
            b.parent = parent;
            queue.add(parent);
            nodeCount++;
        }
        Node root = queue.poll();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((blockBits == 4) ? 0x24 : 0x28);
        out.write(data.length & 0xFF);
        out.write((data.length >> 8) & 0xFF);
        out.write((data.length >> 16) & 0xFF);
        // The plain 3-byte size field can't represent 0 (that's the escape meaning "read a real size
        // from the next u32" -- see isCompressed/decompress), so a genuinely empty input must use it.
        if (data.length == 0)
        {
            out.write(0); out.write(0); out.write(0); out.write(0);
        }

        // Breadth-first tree serialisation: mirrors the read-side addressing scheme exactly, so a
        // node's children land where decompress()'s offset arithmetic expects them. The offset field
        // is 6 bits (matching the retail format), so this encoder can't serialise a tree whose widest
        // BFS frontier needs more than 126 nodes' lookahead -- true of any real Nitro Huffman asset
        // (small tables/palettes/glyph indices in practice), but not of e.g. large uniformly-random
        // 8-bit-alphabet input; fail loudly rather than silently truncate the offset into corruption.
        out.write((nodeCount - 1) / 2);
        LinkedList<Node> bfs = new LinkedList<>();
        bfs.add(root);
        while (!bfs.isEmpty())
        {
            Node n = bfs.removeFirst();
            if (n.isData)
            {
                out.write(n.data);
            }
            else
            {
                int off = bfs.size() / 2;
                if (off > 0x3F)
                    throw new IllegalArgumentException(
                            "Huffman tree too wide to encode (its BFS frontier exceeds the format's 6-bit offset field)");
                int b = off;
                if (n.child0.isData) b |= 0x80;
                if (n.child1.isData) b |= 0x40;
                out.write(b);
                bfs.add(n.child0);
                bfs.add(n.child1);
            }
        }

        // Bitstream: each symbol's root-to-leaf path (child0 = 0, child1 = 1), packed MSB-first into
        // 32-bit little-endian words.
        int word = 0, bitsFilled = 0;
        for (byte b0 : data)
        {
            int nSymbols = (blockBits == 8) ? 1 : 2;
            for (int k = 0; k < nSymbols; k++)
            {
                int sym = (blockBits == 8) ? (b0 & 0xFF) : (k == 0 ? (b0 >> 4) & 0xF : b0 & 0xF);
                boolean[] path = pathFromRoot(leafBySymbol[sym]);
                for (boolean bit : path)
                {
                    if (bitsFilled == 32)
                    {
                        writeWordLe(out, word);
                        word = 0;
                        bitsFilled = 0;
                    }
                    bitsFilled++;
                    if (bit) word |= 1 << (32 - bitsFilled);
                }
            }
        }
        if (bitsFilled > 0)
            writeWordLe(out, word);

        return out.toByteArray();
    }

    private static void writeWordLe(ByteArrayOutputStream out, int word)
    {
        out.write(word & 0xFF);
        out.write((word >>> 8) & 0xFF);
        out.write((word >>> 16) & 0xFF);
        out.write((word >>> 24) & 0xFF);
    }

    // The bit path from the root to this leaf (path[0] = the choice made at the root), found by
    // walking up via parent pointers and reversing.
    private static boolean[] pathFromRoot(Node leaf)
    {
        int depth = 0;
        for (Node n = leaf; n.parent != null; n = n.parent) depth++;
        boolean[] path = new boolean[depth];
        Node n = leaf;
        for (int d = depth - 1; d >= 0; d--)
        {
            path[d] = n.parent.child1 == n;
            n = n.parent;
        }
        return path;
    }

    private static final class Node
    {
        boolean isData;
        int data;
        long freq;
        Node child0, child1, parent;

        static Node leaf(int data, long freq)
        {
            Node n = new Node();
            n.isData = true;
            n.data = data;
            n.freq = freq;
            return n;
        }

        static Node branch(Node child0, Node child1)
        {
            Node n = new Node();
            n.isData = false;
            n.child0 = child0;
            n.child1 = child1;
            n.freq = child0.freq + child1.freq;
            return n;
        }
    }

    private static long u24(byte[] d, int off)
    {
        return (d[off] & 0xFFL) | ((d[off + 1] & 0xFFL) << 8) | ((d[off + 2] & 0xFFL) << 16);
    }

    private static long u32(byte[] d, int off)
    {
        return (d[off] & 0xFFL) | ((d[off + 1] & 0xFFL) << 8) | ((d[off + 2] & 0xFFL) << 16) | ((d[off + 3] & 0xFFL) << 24);
    }
}
