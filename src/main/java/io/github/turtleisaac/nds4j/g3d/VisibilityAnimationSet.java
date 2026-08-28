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
 * An object representation of an NSBVA file (a Nitro 3D <b>visibility animation</b> set, magic
 * {@code BVA0}).
 * <p>
 * An NSBVA holds a {@code VIS0} block naming one or more {@link Animation}s; each animation turns
 * individual model nodes on and off per frame (a packed one-bit-per-(frame,node) stream). Applying it
 * to a {@link Model} means skipping the shapes under a node whose bit is clear on that frame.
 * <p>
 * The container round-trips byte-for-byte (the {@code VIS0} block is preserved verbatim by
 * {@link G3dFile}); the bit stream is parsed as a read-only view over it. Layout reverse-engineered
 * from {@code nitroreader.nsbva.*}.
 */
public class VisibilityAnimationSet extends G3dFile
{
    private final List<Animation> animations = new ArrayList<>();

    /**
     * Generates an object representation of an NSBVA file.
     * @param data a <code>byte[]</code> representation of an NSBVA file
     */
    public VisibilityAnimationSet(byte[] data)
    {
        super("BVA0");
        readContainer(data);
        int vis0 = indexOfBlock("VIS0");
        if (vis0 < 0)
            throw new RuntimeException("Not a valid BVA0 file: missing VIS0 block.");
        parseVis0(block(vis0));
    }

    private void parseVis0(byte[] d)
    {
        MemBuf buf = MemBuf.create(d);
        MemBuf.MemBufReader reader = buf.reader();
        reader.setPosition(8); // VIS0 magic + block size, then the animation dictionary
        G3dDictionary dict = new G3dDictionary(reader);
        for (int i = 0; i < dict.size(); i++)
            animations.add(new Animation(d, (int) readU32(dict.getRecord(i), 0), dict.getName(i)));
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
        return String.format("VisibilityAnimationSet[%d animations]", animations.size());
    }

    /**
     * A single named visibility animation: a per-node on/off flag for every frame.
     */
    public static class Animation
    {
        private final String name;
        private final int frameCount;
        private final boolean[][] visible; // [node][frame]

        Animation(byte[] d, int animStart, String name)
        {
            this.name = name;
            // header: char[4] tag, u16 numFrames, u16 numNodes, u16 (unused), 2 pad; then the bit stream
            frameCount = readU16(d, animStart + 4);
            int numNodes = readU16(d, animStart + 6);
            visible = new boolean[numNodes][frameCount];
            int p = animStart + 12;
            long word = readU32(d, p);
            p += 4;
            int bit = 0;
            // one bit per (frame, node), frame-major, refilling a 32-bit word as it drains
            for (int f = 0; f < frameCount; f++)
                for (int n = 0; n < numNodes; n++)
                {
                    visible[n][f] = (word & 1) != 0;
                    word >>= 1;
                    if (++bit % 32 == 0)
                    {
                        word = readU32(d, p);
                        p += 4;
                    }
                }
        }

        /** @return this animation's name */
        public String getName() { return name; }
        /** @return the number of frames */
        public int getFrameCount() { return frameCount; }
        /** @return the number of nodes this animation controls */
        public int getNodeCount() { return visible.length; }

        /**
         * @param node a node index
         * @param frame a frame index
         * @return whether that node is visible on that frame
         */
        public boolean isVisible(int node, int frame)
        {
            return visible[node][frame];
        }

        @Override
        public String toString()
        {
            return String.format("Animation[%s, %d frames, %d nodes]", name, frameCount, visible.length);
        }
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
