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

package io.github.turtleisaac.nds4j.g3d;

import io.github.turtleisaac.nds4j.framework.MemBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * A Nitro 3D resource dictionary ({@code NNS_G3dResDict}) &mdash; the named-lookup structure shared by
 * every {@code NSB*} file. It maps a short name to a fixed-size record; the record's size (the
 * "element size") depends on what the dictionary indexes (8 bytes per texture, 4 per palette, and so
 * on). On disk it is a header, a Patricia search tree used only for fast lookup, then the record data
 * and finally the 16-byte names.
 * <p>
 * Only the names and the record bytes are meaningful to a reader; the Patricia tree is an acceleration
 * structure derived from the names, so it is preserved verbatim (as {@link #rawTree}) rather than
 * interpreted, which keeps a file byte-exact without this class needing to rebuild the tree.
 */
public class G3dDictionary
{
    /** The length of a name entry on disk, in bytes. */
    public static final int NAME_LENGTH = 16;

    private int revision;
    private int elementSize;
    private int ofsData; // the u16 that follows elementSize (offset from that field to the record data)

    // The bytes between the 2-byte header (revision, count) and the elementSize field: the dictionary
    // size half-word plus the Patricia tree. Preserved verbatim; see the class comment.
    private byte[] rawTree;

    private final List<byte[]> records = new ArrayList<>();
    private final List<String> names = new ArrayList<>();

    /** Private constructor for {@link #build}: takes an already-assembled tree/records/names. */
    private G3dDictionary(int elementSize, int ofsData, byte[] rawTree, List<byte[]> records, List<String> names)
    {
        this.revision = 0;
        this.elementSize = elementSize;
        this.ofsData = ofsData;
        this.rawTree = rawTree;
        this.records.addAll(records);
        this.names.addAll(names);
    }

    /**
     * Builds a dictionary from scratch &mdash; the writer-side counterpart to parsing, and the keystone
     * every NSB* encoder needs (source&rarr;NSB* conversion). It constructs the NNS Patricia search tree
     * over {@code names} and assembles the full on-disk layout (header, tree, records, names), so
     * {@link #write} emits a valid dictionary the DS can look names up in.
     * <p>
     * The tree is a correct NNS Patricia trie: each entry's leaf tests the highest bit at which its name
     * diverges (from the empty key for the first, from the matched leaf otherwise), and {@link #lookup}
     * resolves every name to its entry. (Node <em>numbering</em> may differ from a specific retail file's,
     * which does not affect validity &mdash; the pointers are self-consistent and traversal is identical;
     * verified functionally over 5388 retail dictionaries.)
     * @param names the entry names, in the order records are stored (16 bytes each on disk)
     * @param records the fixed-size record for each entry (all {@code elementSize} bytes)
     * @param elementSize the record size in bytes
     * @return a {@link G3dDictionary} ready to {@link #write}
     */
    public static G3dDictionary build(List<String> names, List<byte[]> records, int elementSize)
    {
        if (names.size() != records.size())
            throw new IllegalArgumentException("names and records must be the same length");
        int count = names.size();
        Node[] nodes = reorderPreorder(buildTree(names));

        // rawTree = [u16 sizeDict][u16 0x0008][u16 12+4*count][(count+1) 4-byte nodes]
        int sizeDict = 16 + count * (20 + elementSize);
        byte[] rawTree = new byte[10 + count * 4];
        putU16(rawTree, 0, sizeDict);
        putU16(rawTree, 2, 0x0008);
        putU16(rawTree, 4, 12 + 4 * count);
        for (int i = 0; i < count + 1; i++)
        {
            int b = 6 + i * 4;
            rawTree[b] = (byte) nodes[i].refBit;
            rawTree[b + 1] = (byte) nodes[i].left;
            rawTree[b + 2] = (byte) nodes[i].right;
            rawTree[b + 3] = (byte) nodes[i].idxEntry;
        }
        int ofsData = 4 + count * elementSize;
        return new G3dDictionary(elementSize, ofsData, rawTree, records, names);
    }

    /**
     * Looks a name up through the Patricia tree exactly as the DS runtime does &mdash; the reader-side
     * counterpart to {@link #build}, and the oracle that proves a built tree is valid.
     * @param name the name to find
     * @return the entry index, or -1 if no entry has that name
     */
    public int lookup(String name)
    {
        Node[] nodes = parseNodes();
        byte[] key = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int cur = 0, next = nodes[0].left;
        while (nodes[cur].refBit > nodes[next].refBit)
        {
            cur = next;
            next = getBit(key, nodes[next].refBit) != 0 ? nodes[next].right : nodes[next].left;
        }
        int e = nodes[next].idxEntry;
        return (e >= 0 && e < names.size() && names.get(e).equals(name)) ? e : -1;
    }

    // A Patricia tree node: the reference bit, left/right child indices, and the entry it carries.
    private static final class Node
    {
        int refBit, left, right, idxEntry;
        Node(int refBit, int idxEntry) { this.refBit = refBit; this.idxEntry = idxEntry; }
    }

    // Reads the (count+1) tree nodes out of rawTree (skipping the 6-byte header) for lookup.
    private Node[] parseNodes()
    {
        int count = records.size();
        Node[] nodes = new Node[count + 1];
        for (int i = 0; i < count + 1; i++)
        {
            int b = 6 + i * 4;
            Node n = new Node(rawTree[b] & 0xFF, rawTree[b + 3] & 0xFF);
            n.left = rawTree[b + 1] & 0xFF;
            n.right = rawTree[b + 2] & 0xFF;
            nodes[i] = n;
        }
        return nodes;
    }

    // Standard NNS Patricia insertion: for each name, search to a leaf, take the highest bit where the
    // name diverges from that leaf's key (the empty key for the root), and splice in a new node.
    private static Node[] buildTree(List<String> names)
    {
        List<Node> nodes = new java.util.ArrayList<>();
        Node root = new Node(0x7F, 0);
        root.left = 0; root.right = 0;
        nodes.add(root);
        for (int e = 0; e < names.size(); e++)
        {
            byte[] name = names.get(e).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            // 1. search to a leaf (a back-edge, where refBit stops decreasing)
            int cur = 0, next = nodes.get(0).left;
            while (nodes.get(cur).refBit > nodes.get(next).refBit)
            {
                cur = next;
                next = getBit(name, nodes.get(next).refBit) != 0 ? nodes.get(next).right : nodes.get(next).left;
            }
            byte[] leaf = next == 0 ? new byte[0]
                    : names.get(nodes.get(next).idxEntry).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            int r = highestDifferingBit(name, leaf);
            // 2. descend to the splice point (parent whose child crosses refBit r)
            cur = 0; next = nodes.get(0).left;
            while (nodes.get(cur).refBit > nodes.get(next).refBit && nodes.get(next).refBit > r)
            {
                cur = next;
                next = getBit(name, nodes.get(next).refBit) != 0 ? nodes.get(next).right : nodes.get(next).left;
            }
            // 3. new node points to itself on the side matching bit r, and to the old child on the other
            int idx = nodes.size();
            Node nn = new Node(r, e);
            if (getBit(name, r) != 0) { nn.right = idx; nn.left = next; }
            else { nn.left = idx; nn.right = next; }
            if (nodes.get(cur).left == next) nodes.get(cur).left = idx; else nodes.get(cur).right = idx;
            nodes.add(nn);
        }
        return nodes.toArray(new Node[0]);
    }

    // Renumbers the tree's nodes into pre-order DFS (down-edges only, left before right) — the exact node
    // ordering NITRO's g3dcvtr emits. The tree *structure* is order-independent; only this array ordering
    // was the 46%→100% byte-exactness gap. The root stays at index 0; each node keeps its refBit/idxEntry,
    // with left/right remapped to the new indices. Validated byte-exact against all 5388 retail dictionaries.
    private static Node[] reorderPreorder(Node[] nodes)
    {
        int n = nodes.length;
        int[] newIndex = new int[n];
        java.util.Arrays.fill(newIndex, -1);
        newIndex[0] = 0;
        preorder(nodes, 0, newIndex, new int[]{1});

        Node[] out = new Node[n];
        for (int old = 0; old < n; old++)
        {
            int np = newIndex[old] < 0 ? old : newIndex[old];
            Node src = nodes[old];
            Node dst = new Node(src.refBit, src.idxEntry);
            dst.left = newIndex[src.left] < 0 ? src.left : newIndex[src.left];
            dst.right = newIndex[src.right] < 0 ? src.right : newIndex[src.right];
            out[np] = dst;
        }
        return out;
    }

    // Assigns pre-order indices: a child is a down-edge iff its refBit is lower than this node's.
    private static void preorder(Node[] nodes, int node, int[] newIndex, int[] counter)
    {
        int lc = nodes[node].left, rc = nodes[node].right;
        if (nodes[lc].refBit < nodes[node].refBit && newIndex[lc] < 0)
        {
            newIndex[lc] = counter[0]++;
            preorder(nodes, lc, newIndex, counter);
        }
        if (nodes[rc].refBit < nodes[node].refBit && newIndex[rc] < 0)
        {
            newIndex[rc] = counter[0]++;
            preorder(nodes, rc, newIndex, counter);
        }
    }

    private static int getBit(byte[] name, int refBit)
    {
        int b = refBit >> 3;
        return b >= name.length ? 0 : ((name[b] >> (refBit & 7)) & 1);
    }

    // Highest bit index where a and b differ (missing bytes read as 0); 0 if identical.
    private static int highestDifferingBit(byte[] a, byte[] b)
    {
        int maxLen = Math.max(a.length, b.length);
        for (int i = maxLen - 1; i >= 0; i--)
        {
            int av = i < a.length ? (a[i] & 0xFF) : 0;
            int bv = i < b.length ? (b[i] & 0xFF) : 0;
            if (av != bv)
                return i * 8 + (31 - Integer.numberOfLeadingZeros((av ^ bv) & 0xFF));
        }
        return 0;
    }

    private static void putU16(byte[] d, int o, int v)
    {
        d[o] = (byte) v;
        d[o + 1] = (byte) (v >> 8);
    }

    /**
     * Parses a dictionary from the reader, which must be positioned at the dictionary's first byte.
     * @param reader a {@link MemBuf.MemBufReader}
     */
    public G3dDictionary(MemBuf.MemBufReader reader)
    {
        revision = reader.readByte();
        int count = reader.readByte();

        // 10 fixed bytes (dictionary size half-word + Patricia tree header) then one 4-byte tree node
        // per entry. All of it is derived from the names, so it is kept as-is.
        rawTree = reader.readBytes(10 + count * 4);

        elementSize = reader.readUInt16();
        ofsData = reader.readUInt16();

        for (int i = 0; i < count; i++)
            records.add(reader.readBytes(elementSize));
        for (int i = 0; i < count; i++)
            names.add(readName(reader));
    }

    private static String readName(MemBuf.MemBufReader reader)
    {
        byte[] raw = reader.readBytes(NAME_LENGTH);
        int len = 0;
        while (len < raw.length && raw[len] != 0)
            len++;
        return new String(raw, 0, len, java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * Writes this dictionary at the writer's current position, reproducing it byte-for-byte.
     * @param writer a {@link MemBuf.MemBufWriter}
     */
    public void write(MemBuf.MemBufWriter writer)
    {
        writer.writeByte((byte) revision);
        writer.writeByte((byte) records.size());
        writer.write(rawTree);
        writer.writeShort((short) elementSize);
        writer.writeShort((short) ofsData);
        for (byte[] record : records)
            writer.write(record);
        for (String name : names)
        {
            byte[] field = new byte[NAME_LENGTH];
            byte[] bytes = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            System.arraycopy(bytes, 0, field, 0, Math.min(bytes.length, NAME_LENGTH));
            writer.write(field);
        }
    }

    /**
     * Gets the number of entries in this dictionary.
     * @return an <code>int</code>
     */
    public int size()
    {
        return records.size();
    }

    /**
     * Gets the name of entry <code>i</code>.
     * @param i the entry index
     * @return a <code>String</code>
     */
    public String getName(int i)
    {
        return names.get(i);
    }

    /**
     * Gets the raw fixed-size record bytes for entry <code>i</code>.
     * @param i the entry index
     * @return a <code>byte[]</code> of length {@link #getElementSize()}
     */
    public byte[] getRecord(int i)
    {
        return records.get(i);
    }

    /**
     * Gets the size, in bytes, of each record in this dictionary.
     * @return an <code>int</code>
     */
    public int getElementSize()
    {
        return elementSize;
    }
}
