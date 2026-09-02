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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeMap;

/**
 * An object representation of an NSBTP file (a Nitro 3D <b>texture-pattern animation</b> set, magic
 * {@code BTP0}).
 * <p>
 * An NSBTP holds a {@code PAT0} block naming one or more {@link Animation}s; each animation swaps which
 * texture/palette a material samples over time (keyframes of {@code (frame → texture, palette)} per
 * material) &mdash; the flip-book effect used for blinking eyes, spinning coins and the like. Applying
 * it to a {@link Model} means, at frame <em>f</em>, pointing a material at the texture/palette of its
 * latest keyframe at or before <em>f</em>.
 * <p>
 * The container round-trips byte-for-byte (the {@code PAT0} block is preserved verbatim by
 * {@link G3dFile}); the keyframes are parsed as a read-only view over it. Layout reverse-engineered
 * from {@code nitroreader.nsbtp.*}.
 */
public class TexturePatternAnimationSet extends G3dFile
{
    private final List<Animation> animations = new ArrayList<>();

    /**
     * Generates an object representation of an NSBTP file.
     * @param data a <code>byte[]</code> representation of an NSBTP file
     */
    public TexturePatternAnimationSet(byte[] data)
    {
        super("BTP0");
        readContainer(data);
        int pat0 = indexOfBlock("PAT0");
        if (pat0 < 0)
            throw new RuntimeException("Not a valid BTP0 file: missing PAT0 block.");
        parsePat0(block(pat0));
    }

    /**
     * Assembles an NSBTP file from authored pattern animations &mdash; the writer counterpart to reading.
     * @param animations the animations, in order
     * @param version the NTR container version half-word (1 for a fresh file)
     */
    public static TexturePatternAnimationSet author(List<Animation> animations, int version)
    {
        return new TexturePatternAnimationSet(encode(animations, version));
    }

    /**
     * Re-emits this set's bytes from its parsed structure (the byte-exact writer path, verified to reproduce
     * every retail NSBTP). Distinct from the block-verbatim {@link G3dFile#save()}.
     */
    public byte[] encode()
    {
        return encode(animations, version);
    }

    private void parsePat0(byte[] d)
    {
        MemBuf buf = MemBuf.create(d);
        MemBuf.MemBufReader reader = buf.reader();
        reader.setPosition(8); // PAT0 magic + block size, then the animation dictionary
        G3dDictionary dict = new G3dDictionary(reader);
        for (int i = 0; i < dict.size(); i++)
            animations.add(new Animation(d, (int) readU32(dict.getRecord(i), 0), dict.getName(i)));
    }

    // Builds the whole NSBTP: a PAT0 block = magic + size + animation dictionary + each animation's data,
    // wrapped in the NTR container. Each animation is a 12-byte header (tag, numFrames, numTex, numPlt, and
    // the offsets to the texture/palette name tables), then a material dictionary whose 8-byte records give
    // {numKeyframes, 0, ratio = numKeyframes*4096/numFrames, offset-to-keyframes}, then the keyframe arrays
    // (frame, texIdx, pltIdx), then the 16-byte texture and palette name tables.
    private static byte[] encode(List<Animation> animations, int version)
    {
        List<String> names = new ArrayList<>();
        for (Animation a : animations) names.add(a.name);

        int dictSize = serialize(G3dDictionary.build(names, placeholders(animations.size(), 4), 4)).length;
        byte[][] blobs = new byte[animations.size()][];
        for (int i = 0; i < animations.size(); i++) blobs[i] = animations.get(i).encodeBlob();

        List<byte[]> recs = new ArrayList<>();
        int cursor = 8 + dictSize;
        for (byte[] blob : blobs) { recs.add(rec4(cursor)); cursor += blob.length; }

        ByteArrayOutputStream pat0 = new ByteArrayOutputStream();
        pat0.writeBytes("PAT0".getBytes(StandardCharsets.US_ASCII));
        pat0.writeBytes(rec4(cursor));
        pat0.writeBytes(serialize(G3dDictionary.build(names, recs, 4)));
        for (byte[] blob : blobs) pat0.writeBytes(blob);
        return G3dFile.assembleContainer("BTP0", version, pat0.toByteArray());
    }

    private static List<byte[]> placeholders(int n, int recSize)
    {
        List<byte[]> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(new byte[recSize]);
        return l;
    }
    private static byte[] serialize(G3dDictionary d) { MemBuf b = MemBuf.create(); d.write(b.writer()); return b.reader().getBuffer(); }
    private static byte[] rec4(long v) { byte[] r = new byte[4]; for (int i = 0; i < 4; i++) r[i] = (byte) (v >> (8 * i)); return r; }
    private static void u16w(byte[] d, int o, int v) { d[o] = (byte) v; d[o + 1] = (byte) (v >> 8); }

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
        return String.format("TexturePatternAnimationSet[%d animations]", animations.size());
    }

    /**
     * A single named texture-pattern animation: a frame count and a keyframe map per animated material.
     */
    public static class Animation
    {
        private final String name;
        private final int frameCount;
        private final List<MaterialPattern> materials = new ArrayList<>();
        // the texture/palette name tables in their original order — retained so re-encoding reproduces the
        // exact texIdx/pltIdx each keyframe used (the order is not always first-appearance)
        private final String[] texNames, pltNames;

        Animation(byte[] d, int animStart, String name)
        {
            this.name = name;
            // header: char[4] tag, u16 numFrames, u8 numTextures, u8 numPalettes, u16 ofsTexNames,
            //         u16 ofsPltNames; then a material dictionary (8-byte records)
            frameCount = readU16(d, animStart + 4);
            int numTex = d[animStart + 6] & 0xFF;
            int numPlt = d[animStart + 7] & 0xFF;
            int ofsTexNames = readU16(d, animStart + 8);
            int ofsPltNames = readU16(d, animStart + 10);

            String[] texNames = names(d, animStart + ofsTexNames, numTex);
            String[] pltNames = names(d, animStart + ofsPltNames, numPlt);
            this.texNames = texNames;
            this.pltNames = pltNames;

            MemBuf buf = MemBuf.create(d);
            MemBuf.MemBufReader reader = buf.reader();
            reader.setPosition(animStart + 12);
            G3dDictionary dict = new G3dDictionary(reader);
            for (int i = 0; i < dict.size(); i++)
            {
                byte[] rec = dict.getRecord(i);
                long param1 = readU32(rec, 0), param2 = readU32(rec, 4);
                int numKeyframes = (int) (param1 & 0xFFFF);
                int offset = (int) (param2 >>> 16); // relative to animStart
                TreeMap<Integer, TexturePalette> keyframes = new TreeMap<>();
                int p = animStart + offset;
                for (int k = 0; k < numKeyframes; k++)
                {
                    int frame = readU16(d, p);
                    int texIdx = d[p + 2] & 0xFF;
                    int pltIdx = d[p + 3] & 0xFF;
                    keyframes.put(frame, new TexturePalette(texNames[texIdx], pltNames[pltIdx]));
                    p += 4;
                }
                materials.add(new MaterialPattern(dict.getName(i), keyframes));
            }
        }

        /**
         * Authors a texture-pattern animation. The texture and palette name tables are derived from the
         * order the names first appear across the materials' keyframes.
         * @param name the animation's name
         * @param frameCount the animation length in frames
         * @param materials the per-material pattern tracks
         */
        public Animation(String name, int frameCount, List<MaterialPattern> materials)
        {
            this.name = name;
            this.frameCount = frameCount;
            this.materials.addAll(materials);
            LinkedHashSet<String> tex = new LinkedHashSet<>(), plt = new LinkedHashSet<>();
            for (MaterialPattern m : materials)
                for (TexturePalette tp : m.keyframes.values()) { tex.add(tp.getTexture()); plt.add(tp.getPalette()); }
            this.texNames = tex.toArray(new String[0]);
            this.pltNames = plt.toArray(new String[0]);
        }

        // rebuild this animation's data block byte-exactly (header, material dict, keyframe arrays, name tables)
        byte[] encodeBlob()
        {
            int nf = frameCount, nt = texNames.length, np = pltNames.length;
            List<String> matNames = new ArrayList<>();
            for (MaterialPattern m : materials) matNames.add(m.name);
            int dictSize = serialize(G3dDictionary.build(matNames, placeholders(materials.size(), 8), 8)).length;

            int kfStart = 12 + dictSize, cursor = kfStart;
            byte[][] kfBlobs = new byte[materials.size()][];
            int[] matOff = new int[materials.size()];
            for (int m = 0; m < materials.size(); m++)
            {
                TreeMap<Integer, TexturePalette> kf = materials.get(m).keyframes;
                byte[] kb = new byte[kf.size() * 4];
                int q = 0;
                for (java.util.Map.Entry<Integer, TexturePalette> e : kf.entrySet())
                {
                    u16w(kb, q * 4, e.getKey());
                    kb[q * 4 + 2] = (byte) indexOf(texNames, e.getValue().getTexture());
                    kb[q * 4 + 3] = (byte) indexOf(pltNames, e.getValue().getPalette());
                    q++;
                }
                matOff[m] = cursor; kfBlobs[m] = kb; cursor += kb.length;
            }
            int ofsTex = cursor, ofsPlt = ofsTex + nt * 16, animSize = ofsPlt + np * 16;

            List<byte[]> recs = new ArrayList<>();
            for (int m = 0; m < materials.size(); m++)
            {
                int numKf = materials.get(m).keyframes.size();
                int ratio = nf == 0 ? 0 : (numKf * 4096) / nf;
                byte[] rec = new byte[8];
                u16w(rec, 0, numKf); u16w(rec, 2, 0); u16w(rec, 4, ratio); u16w(rec, 6, matOff[m]);
                recs.add(rec);
            }

            byte[] anim = new byte[animSize];
            anim[0] = 0x4d; anim[1] = 0; anim[2] = 0x50; anim[3] = 0x54; // NNS pattern-anim tag ("M\0PT")
            u16w(anim, 4, nf); anim[6] = (byte) nt; anim[7] = (byte) np; u16w(anim, 8, ofsTex); u16w(anim, 10, ofsPlt);
            System.arraycopy(serialize(G3dDictionary.build(matNames, recs, 8)), 0, anim, 12, dictSize);
            for (int m = 0; m < materials.size(); m++) System.arraycopy(kfBlobs[m], 0, anim, matOff[m], kfBlobs[m].length);
            for (int t = 0; t < nt; t++) writeName(anim, ofsTex + t * 16, texNames[t]);
            for (int t = 0; t < np; t++) writeName(anim, ofsPlt + t * 16, pltNames[t]);
            return anim;
        }

        private static int indexOf(String[] arr, String s)
        {
            for (int i = 0; i < arr.length; i++) if (arr[i].equals(s)) return i;
            return 0;
        }
        private static void writeName(byte[] d, int at, String s)
        {
            byte[] b = s.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(b, 0, d, at, Math.min(16, b.length));
        }

        private static String[] names(byte[] d, int at, int count)
        {
            String[] out = new String[count];
            for (int i = 0; i < count; i++)
            {
                int start = at + i * 16, len = 0;
                while (len < 16 && d[start + len] != 0)
                    len++;
                out[i] = new String(d, start, len, java.nio.charset.StandardCharsets.US_ASCII);
            }
            return out;
        }

        /** @return this animation's name */
        public String getName() { return name; }
        /** @return the number of frames */
        public int getFrameCount() { return frameCount; }
        /** @return the per-material pattern tracks */
        public List<MaterialPattern> getMaterials() { return materials; }

        @Override
        public String toString()
        {
            return String.format("Animation[%s, %d frames, %d materials]", name, frameCount, materials.size());
        }
    }

    /** A material's texture-pattern track: keyframes of {@code frame → texture/palette}. */
    public static class MaterialPattern
    {
        private final String name;
        private final TreeMap<Integer, TexturePalette> keyframes;

        /**
         * A material's texture-pattern track.
         * @param name the material's name
         * @param keyframes {@code frame → texture/palette}, sorted by frame
         */
        public MaterialPattern(String name, TreeMap<Integer, TexturePalette> keyframes)
        {
            this.name = name;
            this.keyframes = keyframes;
        }

        /** @return the material's name */
        public String getName() { return name; }
        /** @return the keyframes, sorted by frame (each maps to the texture/palette shown from then on) */
        public TreeMap<Integer, TexturePalette> getKeyframes() { return keyframes; }

        /**
         * @param frame a frame index
         * @return the texture/palette this material shows on that frame (the latest keyframe at or before
         *         it), or null if the animation has no keyframe yet
         */
        public TexturePalette at(int frame)
        {
            java.util.Map.Entry<Integer, TexturePalette> e = keyframes.floorEntry(frame);
            return e != null ? e.getValue() : (keyframes.isEmpty() ? null : keyframes.firstEntry().getValue());
        }
    }

    /** The texture and palette names a material samples at a keyframe. */
    public static class TexturePalette
    {
        private final String texture;
        private final String palette;

        /**
         * @param texture the texture name a material samples at this keyframe
         * @param palette the palette name
         */
        public TexturePalette(String texture, String palette)
        {
            this.texture = texture;
            this.palette = palette;
        }

        /** @return the texture name */
        public String getTexture() { return texture; }
        /** @return the palette name */
        public String getPalette() { return palette; }

        @Override
        public String toString() { return texture + "/" + palette; }
    }

    private static int readU16(byte[] d, int o)
    {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    private static long readU32(byte[] d, int o)
    {
        return (d[o] & 0xFFL) | ((d[o + 1] & 0xFFL) << 8) | ((d[o + 2] & 0xFFL) << 16) | ((d[o + 3] & 0xFFL) << 24);
    }
}
