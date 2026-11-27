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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An object representation of an NSBCA file (a Nitro 3D <b>skeletal (joint) animation</b> set, magic
 * {@code BCA0}).
 * <p>
 * An NSBCA holds one {@code JNT0} block naming one or more {@link Animation}s. Each animation is a set
 * of per-{@link NodeAnim node} scale/rotation/translation (SRT) tracks that pose a {@link Model}'s
 * bind-pose skeleton over time: for a given frame each node yields an SRT that replaces (or, for
 * "base" tracks, keeps) its bind-pose local transform, and the model's node hierarchy is composed and
 * applied exactly as at bind pose &mdash; see {@link Model#pose(Animation, int)}.
 * <p>
 * The container round-trips byte-for-byte (the {@code JNT0} block is preserved verbatim by
 * {@link G3dFile}); the tracks are parsed as a read-only view over it. Tracks are stored compactly
 * (constant tracks inline; variable tracks as keyframe arrays sampled every 1/2/4 frames with linear
 * interpolation, rotations as pivot- or 5-value-compressed 3&times;3 matrices in shared pools). Layout
 * reverse-engineered from the reference {@code nitroreader.nsbca.*} decoder.
 * <p>
 * <b>Writer:</b> {@link #encode()} re-emits the file byte-for-byte from the parsed structure (verified over
 * every retail NSBCA), the fifth and last of the animation writers. It disassembles each animation into its
 * per-node blocks, the two shared rotation-matrix pools (pivot 6-byte and 5-value 10-byte entries, indexed by
 * {@code u16}, kept verbatim since they are index- not offset-referenced) and the keyframe arrays, then relays
 * them out: node blocks (offset fields repointed) &middot; rot3 pool &middot; rot5 pool &middot; keyframe arrays
 * grouped by section (rotation, translation, scale), each group 4-byte-aligned and its members element-aligned.
 */
public class SkeletalAnimationSet extends G3dFile
{
    private final List<Animation> animations = new ArrayList<>();

    /**
     * Generates an object representation of an NSBCA file.
     * @param data a <code>byte[]</code> representation of an NSBCA file
     */
    public SkeletalAnimationSet(byte[] data)
    {
        super("BCA0");
        readContainer(data);
        int jnt0 = indexOfBlock("JNT0");
        if (jnt0 < 0)
            throw new RuntimeException("Not a valid BCA0 file: missing JNT0 block.");
        parseJnt0(block(jnt0));
    }

    /**
     * Reads the JNT0 animation dictionary.
     * @param d the JNT0 block
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private void parseJnt0(byte[] d)
    {
        // Magic and block size, then the named animation records.
        MemBuf.MemBufReader reader = MemBuf.create(d).reader();
        reader.setPosition(8);
        G3dDictionary dict = new G3dDictionary(reader);
        addJntAnims(d, dict, jntStarts(dict));
    }

    /**
     * @param dict the JNT0 animation dictionary
     * @return each animation's offset from the start of the block
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private static int[] jntStarts(G3dDictionary dict)
    {
        int[] starts = new int[dict.size()];

        for (int i = 0; i < dict.size(); i++)
        {
            starts[i] = (int) Fx.uint32(dict.getRecord(i), 0);
        }

        return starts;
    }

    /**
     * @param d the JNT0 block
     * @param dict the animation dictionary
     * @param starts each animation's block offset
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private void addJntAnims(byte[] d, G3dDictionary dict, int[] starts)
    {
        for (int i = 0; i < starts.length; i++)
        {
            animations.add(jntAnim(d, dict, starts, i));
        }
    }

    /**
     * @param d the JNT0 block
     * @param dict the animation dictionary
     * @param starts each animation's block offset
     * @param i animation index
     * @return the animation, with its raw payload retained for re-encode
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private Animation jntAnim(byte[] d, G3dDictionary dict, int[] starts, int i)
    {
        Animation anim = new Animation(d, starts[i], dict.getName(i));
        anim.payload = Arrays.copyOfRange(d, starts[i], jntEnd(d, starts, i));

        return anim;
    }

    /**
     * @param d the JNT0 block
     * @param starts each animation's block offset
     * @param i animation index
     * @return the exclusive end of that animation's bytes
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private static int jntEnd(byte[] d, int[] starts, int i)
    {
        if (i + 1 < starts.length)
            return starts[i + 1];

        return d.length;
    }

    /**
     * Re-emits this set's bytes from its parsed structure (the byte-exact writer path, verified to reproduce
     * every retail NSBCA). Distinct from the block-verbatim {@link G3dFile#save()}: it disassembles each
     * animation into its node blocks, the two shared rotation pools and the keyframe arrays, then relays them
     * out with recomputed offsets — the node blocks (offset fields repointed), then the rot3/rot5 pools
     * (index-referenced, so verbatim), then the keyframe arrays grouped by section (rotation, translation,
     * scale) with each group 4-byte-aligned and its members element-aligned within.
     * @return the NSBCA file bytes
     */
    public byte[] encode()
    {
        // Each animation is relayed, then packed behind a JNT0 dictionary.
        List<String> names = animationNames();
        byte[][] blobs = reassembledBlobs();

        return bcaFile(names, blobs);
    }

    /**
     * @return the animation names in file order
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private List<String> animationNames()
    {
        List<String> names = new ArrayList<>();

        for (Animation anim : animations)
        {
            names.add(anim.name);
        }

        return names;
    }

    /**
     * @return each animation's reassembled payload
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private byte[][] reassembledBlobs()
    {
        byte[][] blobs = new byte[animations.size()][];

        for (int i = 0; i < animations.size(); i++)
        {
            blobs[i] = reassemble(animations.get(i).payload);
        }

        return blobs;
    }

    /**
     * @param names animation names
     * @param blobs reassembled payloads
     * @return the BCA0 container
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private byte[] bcaFile(List<String> names, byte[][] blobs)
    {
        int origin = 8 + serialize(G3dDictionary.build(names, placeholders(animations.size()), 4)).length;
        byte[] dictionary = serialize(G3dDictionary.build(names, NsbBlock.offsets(blobs, origin), 4));
        byte[] jnt0 = NsbBlock.block("JNT0", NsbBlock.end(blobs, origin), dictionary, blobs);

        return G3dFile.assembleContainer("BCA0", version, jnt0);
    }

    // --- the byte-exact NSBCA payload writer (relays a decoded animation, offsets recomputed) ---

    private static final int I_ID = 0x1, I_TID = 0x2, I_TB = 0x4, I_TXC = 0x8, I_TYC = 0x10, I_TZC = 0x20,
            I_RID = 0x40, I_RB = 0x80, I_RC = 0x100, I_SID = 0x200, I_SB = 0x400, I_SXC = 0x800, I_SYC = 0x1000, I_SZC = 0x2000;

    /**
     * Records each variable track's offset field and the array it points at.
     * An array record is {@code {length, kind, elementSize}} with kind 0 rotation, 1 translation, 2 scale.
     * @param p0 the animation bytes
     * @param p start of the node block
     * @param nf frame count
     * @param fields offset-field positions, block-relative, and the original array offset
     * @param arrays original array offset to its length, kind and element size
     * @param start start of this node block
     * @return the position after the node block's header fields
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnmSRTInfo"
     */
    private static int parseNodeRaw(byte[] p0, int p, int nf, List<int[]> fields, Map<Integer, int[]> arrays, int start)
    {
        // An identity node stores only the info word.
        int info = (int) Fx.uint32(p0, p);

        if ((info & I_ID) != 0)
            return p + 4;

        int at = parseRawAxes(p0, p + 4, nf, fields, arrays, start, info, I_TID, I_TB, I_TXC, I_TYC, I_TZC, 4, 1);
        at = parseRawRotate(p0, at, nf, fields, arrays, start, info);

        return parseRawAxes(p0, at, nf, fields, arrays, start, info, I_SID, I_SB, I_SXC, I_SYC, I_SZC, 8, 2);
    }

    /**
     * @param info the node info word
     * @param identity the section-identity bit
     * @param base the section-base bit
     * @return whether that section stores no tracks
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnmSRTInfo"
     */
    private static boolean rawSkipped(int info, int identity, int base)
    {
        if ((info & identity) != 0)
            return true;

        return (info & base) != 0;
    }

    /**
     * @param p0 the animation bytes
     * @param p position of the section
     * @param nf frame count
     * @param fields offset fields collected so far
     * @param arrays arrays collected so far
     * @param start start of this node block
     * @param info the node info word
     * @param identity the section-identity bit
     * @param base the section-base bit
     * @param b0 constant bit for the first axis
     * @param b1 constant bit for the second axis
     * @param b2 constant bit for the third axis
     * @param constStep bytes a constant axis occupies
     * @param kind 1 for translation, 2 for scale
     * @return the position after the section
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnmSRTInfo"
     */
    private static int parseRawAxes(byte[] p0, int p, int nf, List<int[]> fields, Map<Integer, int[]> arrays, int start, int info, int identity, int base, int b0, int b1, int b2, int constStep, int kind)
    {
        if (rawSkipped(info, identity, base))
            return p;

        return walkRawAxes(p0, p, nf, fields, arrays, start, info, new int[] { b0, b1, b2 }, constStep, kind);
    }

    /**
     * @param p0 the animation bytes
     * @param p position of the first axis
     * @param nf frame count
     * @param fields offset fields collected so far
     * @param arrays arrays collected so far
     * @param start start of this node block
     * @param info the node info word
     * @param bits constant bit per axis
     * @param constStep bytes a constant axis occupies
     * @param kind 1 for translation, 2 for scale
     * @return the position after the three axes
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnmSRTInfo"
     */
    private static int walkRawAxes(byte[] p0, int p, int nf, List<int[]> fields, Map<Integer, int[]> arrays, int start, int info, int[] bits, int constStep, int kind)
    {
        int cursor = p;

        for (int k = 0; k < 3; k++)
        {
            cursor = oneRawAxis(p0, cursor, nf, fields, arrays, start, info, bits[k], constStep, kind);
        }

        return cursor;
    }

    /**
     * @param p0 the animation bytes
     * @param p position of this axis
     * @param nf frame count
     * @param fields offset fields collected so far
     * @param arrays arrays collected so far
     * @param start start of this node block
     * @param info the node info word
     * @param bit this axis's constant bit
     * @param constStep bytes a constant axis occupies
     * @param kind 1 for translation, 2 for scale
     * @return the position after this axis
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnmSRTInfo"
     */
    private static int oneRawAxis(byte[] p0, int p, int nf, List<int[]> fields, Map<Integer, int[]> arrays, int start, int info, int bit, int constStep, int kind)
    {
        if ((info & bit) != 0)
            return p + constStep;

        return variableRaw(p0, p, nf, fields, arrays, start, kind);
    }

    /**
     * @param p0 the animation bytes
     * @param p position of the info word and offset
     * @param nf frame count
     * @param fields offset fields collected so far
     * @param arrays arrays collected so far
     * @param start start of this node block
     * @param kind 0 rotation, 1 translation, 2 scale
     * @return the position after the eight-byte variable header
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnmSRTInfo"
     */
    private static int variableRaw(byte[] p0, int p, int nf, List<int[]> fields, Map<Integer, int[]> arrays, int start, int kind)
    {
        int ti = (int) Fx.uint32(p0, p);
        int off = (int) Fx.uint32(p0, p + 4);
        fields.add(new int[] { p + 4 - start, off });
        arrays.put(off, new int[] { rawBytes(nf, ti, kind), kind, rawElement(ti, kind) });

        return p + 8;
    }

    /**
     * @param nf frame count
     * @param ti the track info word
     * @param kind 0 rotation, 1 translation, 2 scale
     * @return the byte length of the referenced array
     * @see "NitroSystem g3d/src/anm/nsbca.c"
     */
    private static int rawBytes(int nf, int ti, int kind)
    {
        int count = valueCount(nf, frameStep(ti)) * rawElement(ti, kind);

        if (kind == 2)
            return count * 2;

        return count;
    }

    /**
     * @param ti the track info word
     * @param kind 0 rotation, 1 translation, 2 scale
     * @return the element width in bytes
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnmTInfo"
     */
    private static int rawElement(int ti, int kind)
    {
        if (kind == 0)
            return 2;

        return elementWidth(ti);
    }

    /**
     * @param ti the track info word
     * @return 2 when the track is fx16, otherwise 4
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnmTInfo"
     */
    private static int elementWidth(int ti)
    {
        // fx16 tracks pack one value in two bytes. fx32 uses four.
        int width = 4;

        if ((ti & TINFO_FX16) != 0)
            width = 2;

        return width;
    }

    /**
     * @param p0 the animation bytes
     * @param p position of the rotation section
     * @param nf frame count
     * @param fields offset fields collected so far
     * @param arrays arrays collected so far
     * @param start start of this node block
     * @param info the node info word
     * @return the position after the rotation section
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnmSRTInfo"
     */
    private static int parseRawRotate(byte[] p0, int p, int nf, List<int[]> fields, Map<Integer, int[]> arrays, int start, int info)
    {
        if (rawSkipped(info, I_RID, I_RB))
            return p;

        return rotatePresent(p0, p, nf, fields, arrays, start, info);
    }

    /**
     * @param p0 the animation bytes
     * @param p position of a present rotation
     * @param nf frame count
     * @param fields offset fields collected so far
     * @param arrays arrays collected so far
     * @param start start of this node block
     * @param info the node info word
     * @return the position after the rotation
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnmSRTInfo"
     */
    private static int rotatePresent(byte[] p0, int p, int nf, List<int[]> fields, Map<Integer, int[]> arrays, int start, int info)
    {
        if ((info & I_RC) != 0)
            return p + 4;

        return variableRaw(p0, p, nf, fields, arrays, start, 0);
    }

    /**
     * Relays one animation with its node, pool and array offsets recomputed.
     * @param a the original animation bytes
     * @return the animation bytes
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private static byte[] reassemble(byte[] a)
    {
        // Counts, pool offsets and the per-node table.
        AnmHead head = readAnmHead(a);
        AnmParts parts = sliceAnm(a, head);

        return writeAnm(a, head, parts);
    }

    /**
     * @param a the animation bytes
     * @return the header counts and node-offset table
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private static AnmHead readAnmHead(byte[] a)
    {
        int nf = Fx.uint16(a, 4);
        int nn = Fx.uint16(a, 6);
        int r3 = (int) Fx.uint32(a, 12);
        int r5 = (int) Fx.uint32(a, 16);

        return new AnmHead(nf, nn, r3, r5, nodeTable(a, nn));
    }

    /**
     * @param a the animation bytes
     * @param nn node count
     * @return each node's offset from the animation start
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private static int[] nodeTable(byte[] a, int nn)
    {
        int[] nodeOfs = new int[nn];

        for (int n = 0; n < nn; n++)
        {
            nodeOfs[n] = Fx.uint16(a, 20 + n * 2);
        }

        return nodeOfs;
    }

    /**
     * @param a the animation bytes
     * @param head the parsed header
     * @return node blocks, rotation pools and keyframe arrays
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private static AnmParts sliceAnm(byte[] a, AnmHead head)
    {
        byte[][] nodeRaw = new byte[head.nn][];
        List<List<int[]>> nodeFields = new ArrayList<>();
        Map<Integer, int[]> arrays = new TreeMap<>();
        fillNodes(a, head, nodeRaw, nodeFields, arrays);
        byte[] rot3 = Arrays.copyOfRange(a, head.r3, head.r5);
        byte[] rot5 = Arrays.copyOfRange(a, head.r5, rot5End(a, arrays));

        return new AnmParts(nodeRaw, nodeFields, arrays, rot3, rot5);
    }

    /**
     * @param a the animation bytes
     * @param head the parsed header
     * @param nodeRaw node blocks, filled here
     * @param nodeFields per-node offset fields, filled here
     * @param arrays keyframe arrays, filled here
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private static void fillNodes(byte[] a, AnmHead head, byte[][] nodeRaw, List<List<int[]>> nodeFields, Map<Integer, int[]> arrays)
    {
        for (int n = 0; n < head.nn; n++)
        {
            fillOneNode(a, head, n, nodeRaw, nodeFields, arrays);
        }
    }

    /**
     * @param a the animation bytes
     * @param head the parsed header
     * @param n node index
     * @param nodeRaw node blocks
     * @param nodeFields per-node offset fields
     * @param arrays keyframe arrays
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private static void fillOneNode(byte[] a, AnmHead head, int n, byte[][] nodeRaw, List<List<int[]>> nodeFields, Map<Integer, int[]> arrays)
    {
        List<int[]> fields = new ArrayList<>();
        parseNodeRaw(a, head.nodeOfs[n], head.nf, fields, arrays, head.nodeOfs[n]);
        nodeRaw[n] = Arrays.copyOfRange(a, head.nodeOfs[n], nodeBlockEnd(head, n));
        nodeFields.add(fields);
    }

    /**
     * @param head the parsed header
     * @param n node index
     * @return the exclusive end of that node block
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private static int nodeBlockEnd(AnmHead head, int n)
    {
        if (n + 1 < head.nn)
            return head.nodeOfs[n + 1];

        return head.r3;
    }

    /**
     * @param a the animation bytes
     * @param arrays keyframe arrays keyed by their original offset
     * @return where the five-value rotation pool ends
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private static int rot5End(byte[] a, Map<Integer, int[]> arrays)
    {
        if (arrays.isEmpty())
            return a.length;

        return firstKey(arrays, a.length);
    }

    /**
     * @param arrays keyframe arrays keyed by their original offset
     * @param fallback used when the map is empty
     * @return the smallest original array offset
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private static int firstKey(Map<Integer, int[]> arrays, int fallback)
    {
        int first = fallback;

        for (int off : arrays.keySet())
        {
            first = Math.min(first, off);
        }

        return first;
    }

    /**
     * @param a the original animation bytes
     * @param head the parsed header
     * @param parts the sliced blocks
     * @return the animation with recomputed offsets
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private static byte[] writeAnm(byte[] a, AnmHead head, AnmParts parts)
    {
        // Node blocks, then the two rotation pools, then the keyframe arrays.
        int cur = (20 + head.nn * 2 + 3) & ~3;
        MemBuf bodyBuf = MemBuf.create();
        MemBuf.MemBufWriter body = bodyBuf.writer();
        int[] newNodeOfs = stampNodes(body, parts.nodeRaw, cur);
        int newR3 = cur + body.getPosition();
        body.write(parts.rot3);
        int newR5 = cur + body.getPosition();
        body.write(parts.rot5);
        Map<Integer, Integer> newArrOff = placeArrays(a, parts.arrays, body, cur);
        padAlign(body, cur, 4);

        // Copy the header, then the repointed pieces into the gaps the sizes describe.
        byte[] out = new byte[cur + body.getPosition()];
        System.arraycopy(a, 0, out, 0, 20);
        {
            MemBuf scratch = MemBuf.create(out);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(12);
            scratch.writer().writeUInt32(newR3);
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, out, 0, out.length);
        }
        {
            MemBuf scratch = MemBuf.create(out);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(16);
            scratch.writer().writeUInt32(newR5);
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, out, 0, out.length);
        }
        patchNodes(out, parts, newNodeOfs, newArrOff);
        System.arraycopy(parts.rot3, 0, out, newR3, parts.rot3.length);
        System.arraycopy(parts.rot5, 0, out, newR5, parts.rot5.length);
        copyArrays(a, out, parts.arrays, newArrOff);

        return out;
    }

    /**
     * @param body the size accumulator
     * @param nodeRaw the node blocks
     * @param cur where the node blocks begin in the output
     * @return each node's new offset
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private static int[] stampNodes(MemBuf.MemBufWriter body, byte[][] nodeRaw, int cur)
    {
        int[] newNodeOfs = new int[nodeRaw.length];

        for (int n = 0; n < nodeRaw.length; n++)
        {
            newNodeOfs[n] = cur + body.getPosition();
            body.write(nodeRaw[n]);
        }

        return newNodeOfs;
    }

    /**
     * @param a the original animation bytes
     * @param arrays keyframe arrays
     * @param body the size accumulator
     * @param cur where the node blocks begin in the output
     * @return original array offset to its new offset
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private static Map<Integer, Integer> placeArrays(byte[] a, Map<Integer, int[]> arrays, MemBuf.MemBufWriter body, int cur)
    {
        Map<Integer, Integer> placed = new HashMap<>();
        int prevKind = -1;

        for (Map.Entry<Integer, int[]> entry : arrays.entrySet())
        {
            prevKind = placeOneArray(a, body, cur, placed, prevKind, entry);
        }

        return placed;
    }

    /**
     * @param a the original animation bytes
     * @param body the size accumulator
     * @param cur where the node blocks begin in the output
     * @param placed original offset to new offset
     * @param prevKind kind of the array written just before this one
     * @param entry this array's original offset and {@code {length, kind, elementSize}}
     * @return this array's kind
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private static int placeOneArray(byte[] a, MemBuf.MemBufWriter body, int cur, Map<Integer, Integer> placed, int prevKind, Map.Entry<Integer, int[]> entry)
    {
        int kind = entry.getValue()[1];
        int el = entry.getValue()[2];
        padAlign(body, cur, arrayAlign(kind, prevKind, el));
        placed.put(entry.getKey(), cur + body.getPosition());
        body.write(Arrays.copyOfRange(a, entry.getKey(), entry.getKey() + entry.getValue()[0]));

        return kind;
    }

    /**
     * @param kind this array's kind
     * @param prevKind the previous array's kind
     * @param el element size
     * @return the alignment for this array
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private static int arrayAlign(int kind, int prevKind, int el)
    {
        if (kind != prevKind)
            return 4;

        return el;
    }

    /**
     * @param body the size accumulator
     * @param cur where the node blocks begin in the output
     * @param align the modulus to reach
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private static void padAlign(MemBuf.MemBufWriter body, int cur, int align)
    {
        while ((cur + body.getPosition()) % align != 0)
        {
            body.writeByte((byte) 0);
        }
    }

    /**
     * @param out the animation being written
     * @param parts the sliced blocks
     * @param newNodeOfs each node's new offset
     * @param newArrOff original array offset to its new offset
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private static void patchNodes(byte[] out, AnmParts parts, int[] newNodeOfs, Map<Integer, Integer> newArrOff)
    {
        for (int n = 0; n < newNodeOfs.length; n++)
        {
            patchNode(out, parts.nodeRaw[n], parts.nodeFields.get(n), newNodeOfs[n], newArrOff);
            {
                MemBuf scratch = MemBuf.create(out);
                MemBuf.MemBufWriter scratchWriter = scratch.writer();
                int scratchEnd = scratchWriter.getPosition();
                scratchWriter.setPosition(20 + n * 2);
                scratch.writer().writeShort((short) (newNodeOfs[n]));
                scratchWriter.setPosition(scratchEnd);
                System.arraycopy(scratch.reader().getBuffer(), 0, out, 0, out.length);
            }
        }
    }

    /**
     * @param out the animation being written
     * @param raw the original node block
     * @param fields its offset fields
     * @param at the node's new offset
     * @param newArrOff original array offset to its new offset
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private static void patchNode(byte[] out, byte[] raw, List<int[]> fields, int at, Map<Integer, Integer> newArrOff)
    {
        byte[] block = raw.clone();
        retarget(block, fields, newArrOff);
        System.arraycopy(block, 0, out, at, block.length);
    }

    /**
     * @param block a node block being repointed
     * @param fields its offset fields
     * @param newArrOff original array offset to its new offset
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private static void retarget(byte[] block, List<int[]> fields, Map<Integer, Integer> newArrOff)
    {
        for (int[] field : fields)
        {
            {
                MemBuf scratch = MemBuf.create(block);
                MemBuf.MemBufWriter scratchWriter = scratch.writer();
                int scratchEnd = scratchWriter.getPosition();
                scratchWriter.setPosition(field[0]);
                scratch.writer().writeUInt32(newArrOff.get(field[1]));
                scratchWriter.setPosition(scratchEnd);
                System.arraycopy(scratch.reader().getBuffer(), 0, block, 0, block.length);
            }
        }
    }

    /**
     * @param a the original animation bytes
     * @param out the animation being written
     * @param arrays keyframe arrays
     * @param newArrOff original array offset to its new offset
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResJntAnm"
     */
    private static void copyArrays(byte[] a, byte[] out, Map<Integer, int[]> arrays, Map<Integer, Integer> newArrOff)
    {
        for (Map.Entry<Integer, int[]> entry : arrays.entrySet())
        {
            System.arraycopy(a, entry.getKey(), out, newArrOff.get(entry.getKey()), entry.getValue()[0]);
        }
    }

    /** Header counts and the node-offset table of one {@code NNSG3dResJntAnm}. */
    private static final class AnmHead
    {
        private final int nf;
        private final int nn;
        private final int r3;
        private final int r5;
        private final int[] nodeOfs;

        /**
         * @param nf frame count
         * @param nn node count
         * @param r3 pivot-rotation pool offset
         * @param r5 five-value rotation pool offset
         * @param nodeOfs each node's offset
         */
        private AnmHead(int nf, int nn, int r3, int r5, int[] nodeOfs)
        {
            this.nf = nf;
            this.nn = nn;
            this.r3 = r3;
            this.r5 = r5;
            this.nodeOfs = nodeOfs;
        }
    }

    /** The pieces {@link #reassemble(byte[])} relays into a new animation. */
    private static final class AnmParts
    {
        private final byte[][] nodeRaw;
        private final List<List<int[]>> nodeFields;
        private final Map<Integer, int[]> arrays;
        private final byte[] rot3;
        private final byte[] rot5;

        /**
         * @param nodeRaw node blocks
         * @param nodeFields per-node offset fields
         * @param arrays keyframe arrays
         * @param rot3 the pivot rotation pool
         * @param rot5 the five-value rotation pool
         */
        private AnmParts(byte[][] nodeRaw, List<List<int[]>> nodeFields, Map<Integer, int[]> arrays, byte[] rot3, byte[] rot5)
        {
            this.nodeRaw = nodeRaw;
            this.nodeFields = nodeFields;
            this.arrays = arrays;
            this.rot3 = rot3;
            this.rot5 = rot5;
        }
    }

    private static List<byte[]> placeholders(int n)
    {
        List<byte[]> l = new ArrayList<>();
        for (int i = 0; i < n; i++)
        {
            MemBuf word0Buf = MemBuf.create();
            word0Buf.writer().writeUInt32(0);
            byte[] word0 = word0Buf.reader().getBuffer();
            l.add(word0);
        }
        return l;
    }
    private static byte[] serialize(G3dDictionary d)
    {
        MemBuf b = MemBuf.create();
        d.write(b.writer());
        return b.reader().getBuffer();
    }

    /**
     * Gets the animations in this file.
     * @return a <code>List</code> of {@link Animation}
     */
    public List<Animation> getAnimations()
    {
        return animations;
    }

    // save(), equals(), hashCode() are inherited from G3dFile (block-level, byte-exact).

    @Override
    public String toString()
    {
        return String.format("SkeletalAnimationSet[%d animations]", animations.size());
    }

    /**
     * A single named skeletal animation ({@code NNS_G3dResJntAnm}): a frame count and one
     * {@link NodeAnim} per skeleton node, in node order.
     */
    public static class Animation
    {
        private final String name;
        private final int frameCount;
        private final List<NodeAnim> nodes = new ArrayList<>();
        byte[] payload; // the raw animation block bytes, retained for the byte-exact writer

        Animation(byte[] d, int animStart, String name)
        {
            this.name = name;
            // header: char[4] tag, u16 numFrames, u16 numNodes, u32 flag, u32 ofsRot3Pool, u32 ofsRot5Pool
            frameCount = Fx.uint16(d, animStart + 4);
            int numNodes = Fx.uint16(d, animStart + 6);
            int rot3Pool = (int) Fx.uint32(d, animStart + 12); // pivot (6-byte) rotation pool, relative to animStart
            int rot5Pool = (int) Fx.uint32(d, animStart + 16); // 5-value (10-byte) rotation pool, relative to animStart
            for (int n = 0; n < numNodes; n++)
            {
                int nodeOfs = Fx.uint16(d, animStart + 20 + n * 2); // relative to animStart
                nodes.add(new NodeAnim(d, animStart, animStart + nodeOfs, rot3Pool, rot5Pool, frameCount));
            }
        }

        /** @return this animation's name */
        public String getName()
        {
            return name;
        }
        /** @return the number of frames this animation runs */
        public int getFrameCount()
        {
            return frameCount;
        }
        /** @return the per-node tracks, one per skeleton node in node order */
        public List<NodeAnim> getNodes()
        {
            return nodes;
        }

        @Override
        public String toString()
        {
            return String.format("Animation[%s, %d frames, %d nodes]", name, frameCount, nodes.size());
        }
    }

    /**
     * The animation tracks for one skeleton node: independent scale, rotation and translation, each of
     * which is <em>identity</em> (forced to the neutral value), <em>base</em> (keep the model's
     * bind-pose value), <em>constant</em>, or <em>variable</em> (keyframed). Sampling a track at a frame
     * yields the value to place on that node before composing the skeleton.
     */
    public static class NodeAnim
    {
        // NNS_G3D_JNTANM_SRTINFO flag bits (whole-node and per-section modes, per-axis "constant" bits).
        private static final int INFO_IDENTITY = 0x1;
        private static final int INFO_T_IDENTITY = 0x2, INFO_T_BASE = 0x4;
        private static final int INFO_TX_CONST = 0x8, INFO_TY_CONST = 0x10, INFO_TZ_CONST = 0x20;
        private static final int INFO_R_IDENTITY = 0x40, INFO_R_BASE = 0x80, INFO_R_CONST = 0x100;
        private static final int INFO_S_IDENTITY = 0x200, INFO_S_BASE = 0x400;
        private static final int INFO_SX_CONST = 0x800, INFO_SY_CONST = 0x1000, INFO_SZ_CONST = 0x2000;

        // Per-section mode: forced identity, keep bind pose, or "present" (constant/variable per track).
        private enum Mode
        {
            IDENTITY, BASE, PRESENT
        }

        private final int frameCount;
        private Mode transMode = Mode.BASE, rotMode = Mode.BASE, scaleMode = Mode.BASE;
        private Track tx, ty, tz, sx, sy, sz; // when transMode/scaleMode == PRESENT
        private RotTrack rot; // when rotMode == PRESENT

        NodeAnim(byte[] d, int animStart, int p, int rot3Pool, int rot5Pool, int frameCount)
        {
            this.frameCount = frameCount;
            int info = (int) Fx.uint32(d, p);
            p += 4;
            if ((info & INFO_IDENTITY) != 0)
            {
                transMode = rotMode = scaleMode = Mode.IDENTITY;
                return;
            }

            // Translation: identity / base / (tx,ty,tz each constant or variable, read inline in order).
            if ((info & INFO_T_IDENTITY) != 0)
                transMode = Mode.IDENTITY;
            else if ((info & INFO_T_BASE) != 0)
                transMode = Mode.BASE;
            else
            {
                transMode = Mode.PRESENT;
                Track[] r = new Track[3];
                int[] constBits = { INFO_TX_CONST, INFO_TY_CONST, INFO_TZ_CONST };
                for (int k = 0; k < 3; k++)
                {
                    if ((info & constBits[k]) != 0)
                    {
                        r[k] = Track.constant(readFx32(d, p));
                        p += 4;
                    }
                    else
                    {
                        r[k] = Track.variable((int) Fx.uint32(d, p), animStart + (int) Fx.uint32(d, p + 4));
                        p += 8;
                    }
                }
                tx = r[0];
                ty = r[1];
                tz = r[2];
            }

            // Rotation: identity / base / constant / variable.
            if ((info & INFO_R_IDENTITY) != 0)
                rotMode = Mode.IDENTITY;
            else if ((info & INFO_R_BASE) != 0)
                rotMode = Mode.BASE;
            else
            {
                rotMode = Mode.PRESENT;
                if ((info & INFO_R_CONST) != 0)
                {
                    rot = RotTrack.constant((int) Fx.uint32(d, p));
                    p += 4;
                }
                else
                {
                    rot = RotTrack.variable((int) Fx.uint32(d, p), animStart + (int) Fx.uint32(d, p + 4));
                    p += 8;
                }
                rot.bind(d, animStart, rot3Pool, rot5Pool, frameCount);
            }

            // Scale: identity / base / (sx,sy,sz each constant or variable; each stores value+inverse).
            if ((info & INFO_S_IDENTITY) != 0)
                scaleMode = Mode.IDENTITY;
            else if ((info & INFO_S_BASE) != 0)
                scaleMode = Mode.BASE;
            else
            {
                scaleMode = Mode.PRESENT;
                Track[] r = new Track[3];
                int[] constBits = { INFO_SX_CONST, INFO_SY_CONST, INFO_SZ_CONST };
                for (int k = 0; k < 3; k++)
                {
                    if ((info & constBits[k]) != 0)
                    {
                        r[k] = Track.constant(readFx32(d, p));
                        p += 8;
                    } // value + unused inverse
                    else
                    {
                        r[k] = Track.scaleVariable((int) Fx.uint32(d, p), animStart + (int) Fx.uint32(d, p + 4));
                        p += 8;
                    }
                }
                sx = r[0];
                sy = r[1];
                sz = r[2];
                // variable scale tracks need the block to read their keyframes
                for (Track t : r)
                    t.bind(d, frameCount);
            }
            for (Track t : new Track[] { tx, ty, tz })
                if (t != null)
                    t.bind(d, frameCount);
        }

        /**
         * Samples this node's translation at {@code frame}.
         * @param frame the frame index
         * @return {@code {x,y,z}}, {@code {0,0,0}} if the track is identity, or {@code null} if it is a
         *         base track (the caller should keep the bind-pose translation)
         */
        public Vec3 translationAt(int frame)
        {
            if (transMode == Mode.BASE)
                return null;

            if (transMode == Mode.IDENTITY)
                return Vec3.zero();

            return Vec3.of(tx.sample(frame), ty.sample(frame), tz.sample(frame));
        }

        /**
         * Samples this node's scale at {@code frame}.
         * @param frame the frame index
         * @return {@code {x,y,z}}, {@code {1,1,1}} if identity, or {@code null} for a base track
         */
        public Vec3 scaleAt(int frame)
        {
            if (scaleMode == Mode.BASE)
                return null;

            if (scaleMode == Mode.IDENTITY)
                return Vec3.one();

            return Vec3.of(sx.sample(frame), sy.sample(frame), sz.sample(frame));
        }

        /**
         * Samples this node's rotation at {@code frame}.
         * @param frame the frame index
         * @return a row-major 3&times;3 matrix (9 doubles), the identity if the track is identity, or
         *         {@code null} for a base track
         */
        public Mat3 rotationAt(int frame)
        {
            if (rotMode == Mode.BASE)
                return null;

            if (rotMode == Mode.IDENTITY)
                return Mat3.identity();

            return rot.sample(frame);
        }
    }

    // A scalar (translation/scale) track: constant, or a keyframe array sampled every `step` frames.
    private static final class Track
    {
        private final float constValue;
        private final boolean isConst;
        private final int info; // variable-track info word (step + fx16 flag)
        private final int dataOffset; // variable-track keyframe array offset (relative to block)
        private final boolean scalePairs; // scale keyframes store value+inverse; read the value, skip the inverse
        private float[] keys;
        private int step;

        private Track(float c, boolean isConst, int info, int dataOffset, boolean scalePairs)
        {
            this.constValue = c;
            this.isConst = isConst;
            this.info = info;
            this.dataOffset = dataOffset;
            this.scalePairs = scalePairs;
        }

        static Track constant(float c)
        {
            return new Track(c, true, 0, 0, false);
        }
        static Track variable(int info, int offset)
        {
            return new Track(0, false, info, offset, false);
        }
        static Track scaleVariable(int info, int offset)
        {
            return new Track(0, false, info, offset, true);
        }

        void bind(byte[] d, int frameCount)
        {
            if (isConst)
                return;
            step = frameStep(info);
            boolean fx16 = (info & TINFO_FX16) != 0;
            int count = valueCount(frameCount, step);
            keys = new float[count];
            int stride = (fx16 ? 2 : 4) * (scalePairs ? 2 : 1);
            for (int i = 0; i < count; i++)
                keys[i] = fx16 ? readFx16(d, dataOffset + i * stride) : readFx32(d, dataOffset + i * stride);
        }

        float sample(int frame)
        {
            if (isConst)
                return constValue;
            int seg = frame / step;
            if (seg >= keys.length - 1)
                return keys[keys.length - 1];
            double frac = (frame - seg * step) / (double) step;
            return (float) (keys[seg] + (keys[seg + 1] - keys[seg]) * frac);
        }
    }

    // A rotation track: constant matrix, or keyframe matrices sampled every `step` frames. Each stored
    // value is a u16 index into one of two shared pools (pivot 6-byte, or 5-value 10-byte).
    private static final class RotTrack
    {
        private final boolean isConst;
        private final int constIndex; // constant-track pool index (-1 => identity)
        private final int info;
        private final int dataOffset;
        private int rot3Pool, rot5Pool, animStart;
        private Mat3[] keys;
        private int step;
        private int frameCount;

        private RotTrack(boolean isConst, int constIndex, int info, int dataOffset)
        {
            this.isConst = isConst;
            this.constIndex = constIndex;
            this.info = info;
            this.dataOffset = dataOffset;
        }

        static RotTrack constant(int index)
        {
            return new RotTrack(true, index, 0, 0);
        }
        static RotTrack variable(int info, int offset)
        {
            return new RotTrack(false, -1, info, offset);
        }

        void bind(byte[] d, int animStart, int rot3Pool, int rot5Pool, int frameCount)
        {
            this.animStart = animStart;
            this.rot3Pool = rot3Pool;
            this.rot5Pool = rot5Pool;
            this.frameCount = frameCount;
            if (isConst)
                bindConstant(d);
            else
                bindKeys(d);
        }

        /**
         * @param d the JNT0 block
         * @see "NitroSystem g3d/src/anm/nsbca.c getRotData_"
         */
        private void bindConstant(byte[] d)
        {
            keys = new Mat3[] { constantMatrix(d) };
            step = 1;
        }

        /**
         * @param d the JNT0 block
         * @return the constant rotation
         * @see "NitroSystem g3d/src/anm/nsbca.c getRotData_"
         */
        private Mat3 constantMatrix(byte[] d)
        {
            if (constIndex == -1)
                return Mat3.identity();

            return readMatrix(d, animStart, rot3Pool, rot5Pool, constIndex);
        }

        /**
         * @param d the JNT0 block
         * @see "NitroSystem g3d/src/anm/nsbca.c getRotData_"
         */
        private void bindKeys(byte[] d)
        {
            step = frameStep(info);
            int count = valueCount(frameCount, step);
            keys = new Mat3[count];
            for (int i = 0; i < count; i++)
                keys[i] = readMatrix(d, animStart, rot3Pool, rot5Pool, Fx.uint16(d, dataOffset + i * 2));
        }

        /**
         * @param frame the frame index
         * @return the rotation at that frame
         * @see "NitroSystem g3d/src/anm/nsbca.c getRotData_"
         */
        Mat3 sample(int frame)
        {
            if (isConst)
                return keys[0];

            return sampleRun(frame);
        }

        /**
         * @param frame the frame index
         * @return the rotation of a variable track
         * @see "NitroSystem g3d/src/anm/nsbca.c getRotData_"
         */
        private Mat3 sampleRun(int frame)
        {
            if (keys.length == 1)
                return keys[0];

            return blended(frame);
        }

        /**
         * @param frame the frame index
         * @return the interpolated rotation
         * @see "NitroSystem g3d/src/anm/nsbca.c getRotData_"
         */
        private Mat3 blended(int frame)
        {
            int seg = frame / step;
            if (seg >= keys.length - 1)
                return keys[keys.length - 1];

            return keys[seg].lerp(keys[seg + 1], (frame - seg * step) / (double) step);
        }
    }

    // --- rotation matrix pools ---

    // Reads a 3x3 rotation from the shared pools. Bit 15 of raw selects the pivot pool (6-byte entries);
    // otherwise the 5-value pool (10-byte entries). The low 15 bits index the entry.
    private static Mat3 readMatrix(byte[] d, int animStart, int rot3Pool, int rot5Pool, int raw)
    {
        // Bit 15 selects the pivot pool. The low 15 bits are the entry index.
        int idx = RotPool.index(raw);
        if (RotPool.of(raw) == RotPool.PIVOT)
            return readRot3(d, animStart + rot3Pool + 6 * idx);

        return readRot5(d, animStart + rot5Pool + 10 * idx);
    }

    // Pivot-compressed rotation (same construction as a model node's pivot matrix): flags select which
    // cell is +/-1 and the sign of the two stored values fill a 2x2 minor.
    private static Mat3 readRot3(byte[] d, int p)
    {
        // The pivot byte and two fx16 values reconstruct the minor.
        MemBuf.MemBufReader reader = MemBuf.create(d).reader();
        reader.setPosition(p);
        int flags = reader.readUInt16();
        double a = Fx.fx16(reader.readUInt16());
        double b = Fx.fx16(reader.readUInt16());
        return pivotMatrix(flags, a, b);
    }

    /**
     * @param flags the pivot byte
     * @param a the first stored value
     * @param b the second stored value
     * @return the reconstructed rotation
     * @see "NitroSystem g3d/src/sbc.c getPivotMatrix"
     */
    private static Mat3 pivotMatrix(int flags, double a, double b)
    {
        double[] cells = new double[9];
        int sel = flags & PivotByte.CELL;
        fillPivot(cells, flags, a, b, sel / 3, sel % 3);
        cells[(sel / 3) * 3 + (sel % 3)] = pivotOne(flags);

        return Mat3.of(cells[0], cells[1], cells[2], cells[3], cells[4], cells[5], cells[6], cells[7], cells[8]);
    }

    /**
     * @param flags the pivot byte
     * @return 1 or -1 for the pivot cell
     * @see "NitroSystem g3d/src/sbc.c getPivotMatrix"
     */
    private static double pivotOne(int flags)
    {
        if (PivotByte.MINUS.set(flags))
            return -1;

        return 1;
    }

    /**
     * @param cells nine cells being filled
     * @param flags the pivot byte
     * @param a the first stored value
     * @param b the second stored value
     * @param row pivot row
     * @param col pivot column
     * @see "NitroSystem g3d/src/sbc.c getPivotMatrix"
     */
    private static void fillPivot(double[] cells, int flags, double a, double b, int row, int col)
    {
        double[] minor = { a, b, signedPivot(flags, PivotByte.SIGN_C, b), signedPivot(flags, PivotByte.SIGN_D, a) };
        int k = 0;
        for (int i = 0; i < 9; i++)
            k = placePivotCell(cells, minor, row, col, i, k);
    }

    /**
     * @param flags the pivot byte
     * @param flag the sign bit
     * @param value the stored value
     * @return the value, negated when the flag is set
     * @see "NitroSystem g3d/src/sbc.c getPivotMatrix"
     */
    private static double signedPivot(int flags, PivotByte flag, double value)
    {
        if (flag.set(flags))
            return -value;

        return value;
    }

    /**
     * @param cells nine cells being filled
     * @param minor the four off-pivot values
     * @param row pivot row
     * @param col pivot column
     * @param i cell index
     * @param k next minor index
     * @return the minor index after this cell
     * @see "NitroSystem g3d/src/sbc.c getPivotMatrix"
     */
    private static int placePivotCell(double[] cells, double[] minor, int row, int col, int i, int k)
    {
        if (i / 3 == row)
            return clearCell(cells, i, k);

        return columnCell(cells, minor, col, i, k);
    }

    /**
     * @param cells nine cells being filled
     * @param i cell index
     * @param k next minor index
     * @return {@code k}, leaving the cell zero
     * @see "NitroSystem g3d/src/sbc.c getPivotMatrix"
     */
    private static int clearCell(double[] cells, int i, int k)
    {
        cells[i] = 0;

        return k;
    }

    /**
     * @param cells nine cells being filled
     * @param minor the four off-pivot values
     * @param col pivot column
     * @param i cell index
     * @param k next minor index
     * @return the minor index after this cell
     * @see "NitroSystem g3d/src/sbc.c getPivotMatrix"
     */
    private static int columnCell(double[] cells, double[] minor, int col, int i, int k)
    {
        if (i % 3 == col)
            return clearCell(cells, i, k);

        cells[i] = minor[k];

        return k + 1;
    }

    // 5-value-compressed rotation: two rows are stored as 5 fx16 (each row value in the high 13 bits, its
    // low 3 bits packing a sixth value); the third row is their cross product, completing the basis.
    private static Mat3 readRot5(byte[] d, int p)
    {
        // Five stored fx16 values. Their low bits pack the sixth.
        MemBuf.MemBufReader reader = MemBuf.create(d).reader();
        reader.setPosition(p);
        int v0 = (short) reader.readUInt16();
        int v1 = (short) reader.readUInt16();
        int v2 = (short) reader.readUInt16();
        int v3 = (short) reader.readUInt16();
        int v4 = (short) reader.readUInt16();
        int packed = (v3 & 7) | ((v2 & 7) << 3) | ((v1 & 7) << 6) | ((v0 & 7) << 9);

        if ((v4 & 1) != 0)
            packed |= 0xF000;

        // The stored row values sit in the high 13 bits.
        double m00 = ((short) (v0 & 0xFFF8) >> 3) / 4096.0;
        double m01 = ((short) (v1 & 0xFFF8) >> 3) / 4096.0;
        double m02 = ((short) (v2 & 0xFFF8) >> 3) / 4096.0;
        double m10 = ((short) (v3 & 0xFFF8) >> 3) / 4096.0;
        double m11 = ((short) (v4 & 0xFFF8) >> 3) / 4096.0;
        double m12 = (short) packed / 4096.0;

        // The third row is the cross product of the first two.
        double m20 = m01 * m12 - m02 * m11;
        double m21 = m02 * m10 - m00 * m12;
        double m22 = m00 * m11 - m01 * m10;

        return Mat3.of(m00, m01, m02, m10, m11, m12, m20, m21, m22);
    }

    // --- keyframe / step helpers (NNS_G3D_JNTANM_?INFO) ---

    private static final int TINFO_STEP_2 = 0x40000000, TINFO_STEP_4 = 0x80000000, TINFO_FX16 = 0x20000000;

    private static int frameStep(int info)
    {
        if ((info & TINFO_STEP_4) == TINFO_STEP_4)
            return 4;
        if ((info & TINFO_STEP_2) == TINFO_STEP_2)
            return 2;
        return 1;
    }

    // The number of stored keyframes for a track: ceil(frames/step) + (frames-1) % step (NNS getValueCount).
    private static int valueCount(int frames, int step)
    {
        return (int) Math.ceil(frames / (double) step) + (frames - 1) % step;
    }



    /**
     * @param d the block bytes
     * @param o index of an fx16
     * @return the value in model units
     * @see "NitroSDK include/nitro/fx/fx.h fx16"
     */
    private static float readFx16(byte[] d, int o)
    {
        // Signed fx16, not the unsigned halfword MemBuf returns.
        return (short) Fx.uint16(d, o) / 4096.0f;
    }

    /**
     * @param d the block bytes
     * @param o index of an fx32
     * @return the value in model units
     * @see "NitroSDK include/nitro/fx/fx.h fx32"
     */
    private static float readFx32(byte[] d, int o)
    {
        return (int) Fx.uint32(d, o) / 4096.0f;
    }

}
