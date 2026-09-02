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

package io.github.turtleisaac.nds4j.images;

import io.github.turtleisaac.nds4j.framework.GenericNtrFile;
import io.github.turtleisaac.nds4j.framework.MemBuf;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Objects;

/**
 * An object representation of an NANR file.
 * <p>
 * An NANR ("RNAN") describes cell animations: a set of {@link Animation} sequences, each of which
 * plays a list of {@link Frame}s for a given number of display frames. Every frame points at an
 * entry in a shared pool of result data (the cell index and, for some animation types, a transform)
 * that lives inside the file's animation bank (<code>KNBA</code>) block. An NANR is meaningless on
 * its own; it animates the cells of a companion {@link CellBank} (NCER).
 * <p>
 * The three animation element types (index, index + SRT, index + translation) store transforms of
 * different sizes and frames within a sequence may share a single pooled result entry, so the pool
 * is preserved verbatim as a {@code byte[]} rather than being decomposed per frame. This keeps a
 * loaded NANR byte-for-byte identical when written back out. The cell index of a frame — the field
 * common to every element type — is exposed through {@link Animation.Frame} for convenience.
 */
public class CellAnimation extends GenericNtrFile
{
    /** Animation element type: each frame's result is a single cell index (2 bytes, padded to 4). */
    public static final int ELEMENT_INDEX = 0;
    /** Animation element type: cell index plus a scale/rotate/translate transform. */
    public static final int ELEMENT_SRT = 1;
    /** Animation element type: cell index plus a translation. */
    public static final int ELEMENT_TRANSLATION = 2;

    private Animation[] animations;

    // The shared frame-result pool exactly as it appears in the file. Frames reference entries in it
    // by byte offset (Frame.resultOffset). Because entries vary in size by element type and are
    // frequently shared between frames of the same sequence, the pool is kept whole so an unedited
    // NANR round-trips byte-for-byte; individual cell indices are still editable through Frame.
    private byte[] resultPool;

    // Whether the file carries the trailing LBAL (per-animation names) and UEXT sections. Retail NANRs
    // always do (numBlocks == 3); a bare NANR (numBlocks == 1) omits them, mirroring CellBank/NCER.
    private boolean labelEnabled;

    // The UEXT section's single content word (always a 12-byte section: magic + size + one u32). Its
    // meaning isn't decoded (retail files carry only 0 or 1), so it's captured and re-emitted verbatim
    // rather than assumed to always be 0.
    private long uextValue;

    // The companion cell bank (NCER) whose cells this file animates. Not part of the NANR itself; set
    // by the consumer with setCellBank so a frame can be rendered. Never serialised.
    private CellBank cellBank;

    // The two 32-bit words that follow the three section offsets in the KNBA header (file offsets
    // 0x28-0x2F). The first is consistently zero; the second is a secondary pointer into the result
    // pool used by the transform element types. They are neither zero-padding nor recomputable from
    // the counts, so the raw 8 bytes are preserved to keep the file byte-for-byte identical.
    private byte[] bankHeaderExtra;

    /**
     * Generates an object representation of an NANR file
     * @param data a <code>byte[]</code> representation of an NANR file
     */
    public CellAnimation(byte[] data)
    {
        super("RNAN");
        MemBuf dataBuf = MemBuf.create(data);
        MemBuf.MemBufReader reader = dataBuf.reader();

        readGenericNtrHeader(reader);

        // reader position is now 0x10

        labelEnabled = numBlocks != 1;

        String bankMagic = reader.readString(4); // 0x10
        if (!bankMagic.equals("KNBA"))
            throw new RuntimeException("Not a valid RNAN file.");

        long bankSectionSize = reader.readUInt32(); // 0x14
        int animationCount = reader.readUInt16(); // 0x18
        int frameCount = reader.readUInt16(); // 0x1A

        // These three offsets are all relative to 0x18 (the start of the offset table). Their values
        // are fully determined by the counts above, but they are read and validated rather than
        // assumed so a malformed file is rejected instead of silently misparsed.
        long animationArrayOffset = reader.readUInt32(); // 0x1C
        long frameArrayOffset = reader.readUInt32(); // 0x20
        long resultDataOffset = reader.readUInt32(); // 0x24
        bankHeaderExtra = reader.readBytes(8); // 0x28: two more header words, preserved verbatim

        int animationArrayBase = NTR_HEADER_SIZE + 8 + (int) animationArrayOffset;
        int frameArrayBase = NTR_HEADER_SIZE + 8 + (int) frameArrayOffset;
        int resultDataBase = NTR_HEADER_SIZE + 8 + (int) resultDataOffset;
        int bankSectionEnd = NTR_HEADER_SIZE + (int) bankSectionSize;

        // --- animation (sequence) descriptors ---
        animations = new Animation[animationCount];
        int[] animationFrameCounts = new int[animationCount];
        for (int i = 0; i < animationCount; i++)
        {
            reader.setPosition(animationArrayBase + i * ANIMATION_SIZE);
            int animationFrameCount = reader.readUInt16();
            int loopStartFrame = reader.readUInt16();
            long type = reader.readUInt32();
            long mode = reader.readUInt32();
            long thisFrameArrayOffset = reader.readUInt32(); // relative to the frame array base

            Animation animation = new Animation();
            animation.loopStartFrame = loopStartFrame;
            animation.type = type;
            animation.mode = mode;
            animation.frames = new Animation.Frame[animationFrameCount];
            animations[i] = animation;
            animationFrameCounts[i] = animationFrameCount;

            // The frames of every sequence are laid out contiguously in file order; this offset is the
            // running total of all preceding sequences' frames. Read it here so it can hold for the
            // few files that might not follow the convention, but writing recomputes it.
            int firstFrameIndex = (int) (thisFrameArrayOffset / FRAME_SIZE);

            for (int f = 0; f < animationFrameCount; f++)
            {
                reader.setPosition(frameArrayBase + (firstFrameIndex + f) * FRAME_SIZE);
                Animation.Frame frame = animation.new Frame();
                frame.resultOffset = reader.readUInt32();
                frame.duration = reader.readUInt16();
                frame.sentinel = reader.readUInt16();
                animation.frames[f] = frame;
            }
        }

        // --- shared result pool ---
        // Everything from the result data to the end of the KNBA block is the pool. Frame result
        // offsets index into this blob, which is preserved verbatim.
        reader.setPosition(resultDataBase);
        resultPool = reader.readBytes(bankSectionEnd - resultDataBase);

        if (!labelEnabled)
            return;

        // --- LBAL (per-animation names) ---
        reader.setPosition(bankSectionEnd);
        String labelMagic = reader.readString(4);
        if (!labelMagic.equals("LBAL"))
            throw new RuntimeException("Not a valid RNAN file.");

        long[] stringOffsets = new long[animationCount + 1];
        int labelSectionSize = reader.readInt();
        stringOffsets[stringOffsets.length - 1] = labelSectionSize - 8 - (4L * animationCount);
        for (int i = 0; i < animationCount; i++)
        {
            long offset = reader.readUInt32();
            if (offset >= labelSectionSize - 8)
            {
                reader.setPosition(reader.getPosition() - 4);
                offset = -1;
            }
            stringOffsets[i] = offset;
        }

        for (int i = 0; i < stringOffsets.length - 1; i++)
        {
            if (stringOffsets[i] != -1)
            {
                animations[i].name = reader.readString((int) (stringOffsets[i + 1] - stringOffsets[i])).trim();
            }
        }

        // --- UEXT ---
        String uextMagic = reader.readString(4);
        if (!uextMagic.equals("TXEU"))
            throw new RuntimeException("Not a valid RNAN file.");
        reader.skip(4); // section size, always 12 (fixed one-word body); recomputed on save
        uextValue = reader.readUInt32();
    }

    /**
     * Generate a <code>byte[]</code> representation of this <code>CellAnimation</code> as an NANR
     * @return a <code>byte[]</code>
     */
    public byte[] save()
    {
        MemBuf dataBuf = MemBuf.create();
        MemBuf.MemBufWriter writer = dataBuf.writer();

        int frameCount = 0;
        for (Animation animation : animations)
            frameCount += animation.frames.length;

        writer.skip(NTR_HEADER_SIZE);

        // KNBA header
        writer.writeString("KNBA");
        int bankSizePos = writer.getPosition();
        writer.skip(4); // bank section size, filled in at the end
        writer.writeShort((short) animations.length);
        writer.writeShort((short) frameCount);

        // All three offsets are relative to 0x18 (NTR_HEADER_SIZE + 8). The animation array follows the
        // 0x20-byte KNBA header, i.e. at absolute 0x30, which is 0x18 past that base.
        long animationArrayOffset = 0x18;
        long frameArrayOffset = animationArrayOffset + (long) animations.length * ANIMATION_SIZE;
        long resultDataOffset = frameArrayOffset + (long) frameCount * FRAME_SIZE;
        writer.writeUInt32(animationArrayOffset);
        writer.writeUInt32(frameArrayOffset);
        writer.writeUInt32(resultDataOffset);
        writer.write(bankHeaderExtra); // the two preserved header words at 0x28

        // animation (sequence) descriptors
        int cumulativeFrames = 0;
        for (Animation animation : animations)
        {
            writer.writeShort((short) animation.frames.length);
            writer.writeShort((short) animation.loopStartFrame);
            writer.writeUInt32(animation.type);
            writer.writeUInt32(animation.mode);
            writer.writeUInt32((long) cumulativeFrames * FRAME_SIZE);
            cumulativeFrames += animation.frames.length;
        }

        // frame descriptors, in animation order (matching how they were read)
        for (Animation animation : animations)
        {
            for (Animation.Frame frame : animation.frames)
            {
                writer.writeUInt32(frame.resultOffset);
                writer.writeShort((short) frame.duration);
                writer.writeShort((short) frame.sentinel);
            }
        }

        // shared result pool
        writer.write(resultPool);

        int bankSectionEnd = writer.getPosition();
        writer.setPosition(bankSizePos);
        writer.writeInt(bankSectionEnd - NTR_HEADER_SIZE);
        writer.setPosition(bankSectionEnd);

        if (labelEnabled)
        {
            writer.writeString("LBAL");
            writeLabelSection(writer, animations);

            writer.writeString("TXEU");
            writer.writeInt(12); // section size (magic + size + one word of contents)
            writer.writeUInt32(uextValue);
        }

        int fileSize = writer.getPosition();
        writer.setPosition(0);
        writeGenericNtrHeader(writer, fileSize, numBlocks);
        writer.setPosition(fileSize);

        return dataBuf.reader().getBuffer();
    }

    // Mirrors CellBank's NCER label writer: a table of per-entry string offsets followed by the
    // NUL-terminated names, with the section size patched in once the strings have been laid out.
    private static void writeLabelSection(MemBuf.MemBufWriter writer, Animation[] animations)
    {
        int sectionStart = writer.getPosition() - 4; // back up to the "LBAL" magic
        int stringStartOffset = 8 + (4 * animations.length);
        writer.setPosition(sectionStart + stringStartOffset);

        long[] offsets = new long[animations.length];
        for (int i = 0; i < animations.length; i++)
        {
            offsets[i] = writer.getPosition() - (sectionStart + stringStartOffset);
            writer.writeString(animations[i].name + "\0");
        }

        int labelEnd = writer.getPosition();

        writer.setPosition(sectionStart + 8); // start of the offset table
        for (long offset : offsets)
            writer.writeUInt32(offset);

        writer.setPosition(sectionStart + 4); // section-size field
        writer.writeInt(labelEnd - sectionStart);
        writer.setPosition(labelEnd);
    }

    /**
     * Gets the <code>Animation</code> sequences contained in this file.
     * @return an <code>Animation[]</code>
     */
    public Animation[] getAnimations()
    {
        return animations;
    }

    /**
     * Sets the <code>Animation</code> sequences contained in this file.
     * @param animations an <code>Animation[]</code>
     */
    public void setAnimations(Animation[] animations)
    {
        this.animations = animations;
    }

    /**
     * Gets the number of <code>Animation</code> sequences contained in this file.
     * @return an <code>int</code>
     */
    public int getNumAnimations()
    {
        return animations.length;
    }

    /* BEGIN SECTION: rendering and write-back across the NCER/NCGR layers */

    /**
     * Associates the companion cell bank (NCER) whose cells this animation plays. The bank must have
     * its own parent NCGR set (via {@link CellBank#setParentImage}) before any frame is rendered, since
     * that is where the pixels come from. Not stored in the file.
     * @param cellBank a {@link CellBank}
     */
    public void setCellBank(CellBank cellBank)
    {
        this.cellBank = cellBank;
    }

    /**
     * Gets the companion cell bank set with {@link #setCellBank}, or null if none has been set.
     * @return a {@link CellBank}
     */
    public CellBank getCellBank()
    {
        return cellBank;
    }

    /**
     * Renders a single animation frame: the cell it names, assembled from the companion NCER/NCGR and
     * transformed by the frame's scale, rotation and translation. The transform is applied about the
     * cell's origin (the point the DS rotates and scales it about); the returned image is sized so the
     * transformed cell fits without clipping, with that origin at its centre.
     *
     * @param frame the frame to render
     * @return a transparent <code>BufferedImage</code>
     * @throws IllegalStateException if no cell bank has been associated with {@link #setCellBank}
     */
    public BufferedImage getFrameImage(Animation.Frame frame)
    {
        requireCellBank();

        int cellIndex = frame.getCellIndex();
        BufferedImage cell = cellBank.getTransparentNcerImage(cellIndex);
        java.awt.Rectangle bounds = cellBank.getCellBounds(cellIndex);

        double scaleX = frame.getScaleX();
        double scaleY = frame.getScaleY();

        // The cell's origin is at (0,0) in cell space; its furthest reach from the origin in each axis
        // sets how big a half-canvas is needed. Use the diagonal (times the scale) so a rotated, scaled
        // cell still fits, and keep the origin at the canvas centre so the transform pivots correctly.
        int reachX = Math.max(bounds.x + bounds.width, -bounds.x);
        int reachY = Math.max(bounds.y + bounds.height, -bounds.y);
        double scale = Math.max(1.0, Math.max(Math.abs(scaleX), Math.abs(scaleY)));
        int half = Math.max(1, (int) Math.ceil(Math.hypot(reachX, reachY) * scale));
        int size = half * 2;

        BufferedImage output = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);

        // Compose, right to left: place the cell so its origin lands on the canvas centre, then scale
        // and rotate (a full turn is 0x10000; negated so a positive angle turns the way the DS does),
        // then translate. drawImage applies the transform to the cell image's top-left corner, so the
        // final step shifts the cell's top-left (cell-space bounds.x, bounds.y) to the centre + offset.
        AffineTransform transform = new AffineTransform();
        transform.translate(frame.getTranslateX(), frame.getTranslateY());
        transform.translate(half, half);
        transform.rotate(-frame.getRotation() / 65536.0 * 2 * Math.PI);
        transform.scale(scaleX, scaleY);
        transform.translate(bounds.x, bounds.y);

        Graphics2D g = output.createGraphics();
        g.drawImage(cell, transform, null);
        g.dispose();
        return output;
    }

    /**
     * Returns the editable assembled image of the cell a frame names, as a
     * {@link CellBank.Cell.CellImage}. Editing its pixels and calling its {@code save()} writes the
     * change down into the source NCGR &mdash; this is how a pixel edit on an animation frame flows
     * back through the NCER to the graphics. The frame's transform is a display-time effect and is not
     * applied here (it does not belong in the stored pixels).
     *
     * @param frame the frame whose cell to edit
     * @return a {@link CellBank.Cell.CellImage} backing that frame's cell
     * @throws IllegalStateException if no cell bank has been associated with {@link #setCellBank}
     */
    public CellBank.Cell.CellImage getFrameCellImage(Animation.Frame frame)
    {
        requireCellBank();
        return cellBank.getCellImage(frame.getCellIndex());
    }

    private void requireCellBank()
    {
        if (cellBank == null)
            throw new IllegalStateException("No cell bank set; call setCellBank(CellBank) before rendering frames.");
    }

    /* END SECTION: rendering and write-back */

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        CellAnimation that = (CellAnimation) o;
        return labelEnabled == that.labelEnabled
                && uextValue == that.uextValue
                && Arrays.equals(animations, that.animations)
                && Arrays.equals(resultPool, that.resultPool)
                && Arrays.equals(bankHeaderExtra, that.bankHeaderExtra);
    }

    @Override
    public int hashCode()
    {
        int result = Objects.hash(labelEnabled, uextValue);
        result = 31 * result + Arrays.hashCode(animations);
        result = 31 * result + Arrays.hashCode(resultPool);
        result = 31 * result + Arrays.hashCode(bankHeaderExtra);
        return result;
    }

    @Override
    public String toString()
    {
        return String.format("CellAnimation[%d animations]", animations.length);
    }

    private static final int ANIMATION_SIZE = 16;
    private static final int FRAME_SIZE = 8;

    /**
     * A single animation sequence within an NANR. It plays its {@link Frame}s in order, looping from
     * {@link #getLoopStartFrame()} according to its playback {@link #getMode() mode}.
     */
    public class Animation
    {
        private String name = "";
        private int loopStartFrame;

        // type: low 16 bits are the element kind (see ELEMENT_* constants); the high bits carry flags.
        // mode: playback mode (e.g. 1 = play once, 2 = loop). Both are kept as the raw stored words so
        // that whatever combination a file uses is reproduced exactly; getElement() decodes the part
        // this class needs in order to interpret result sizes.
        private long type;
        private long mode;

        private Frame[] frames;

        /**
         * Gets this animation's name (from the file's label section), or an empty string if it has none.
         * @return a <code>String</code>
         */
        public String getName()
        {
            return name;
        }

        /**
         * Sets this animation's name (stored in the file's label section).
         * @param name a <code>String</code>
         */
        public void setName(String name)
        {
            this.name = name;
        }

        /**
         * Gets the frame index the animation loops back to once it reaches the end.
         * @return an <code>int</code>
         */
        public int getLoopStartFrame()
        {
            return loopStartFrame;
        }

        /**
         * Sets the frame index the animation loops back to once it reaches the end.
         * @param loopStartFrame an <code>int</code>
         */
        public void setLoopStartFrame(int loopStartFrame)
        {
            this.loopStartFrame = loopStartFrame;
        }

        /**
         * Gets the animation element type, one of {@link CellAnimation#ELEMENT_INDEX},
         * {@link CellAnimation#ELEMENT_SRT}, or {@link CellAnimation#ELEMENT_TRANSLATION}.
         * @return an <code>int</code>
         */
        public int getElement()
        {
            return (int) (type & 0xFFFF);
        }

        /**
         * Gets the raw 32-bit type word (element kind in its low bits, flags in its high bits).
         * @return a <code>long</code>
         */
        public long getType()
        {
            return type;
        }

        /**
         * Sets the raw 32-bit type word.
         * @param type a <code>long</code>
         */
        public void setType(long type)
        {
            this.type = type;
        }

        /**
         * Gets the raw playback mode word.
         * @return a <code>long</code>
         */
        public long getMode()
        {
            return mode;
        }

        /**
         * Sets the raw playback mode word.
         * @param mode a <code>long</code>
         */
        public void setMode(long mode)
        {
            this.mode = mode;
        }

        /**
         * Gets this animation's frames.
         * @return a <code>Frame[]</code>
         */
        public Frame[] getFrames()
        {
            return frames;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            Animation animation = (Animation) o;
            return loopStartFrame == animation.loopStartFrame
                    && type == animation.type
                    && mode == animation.mode
                    && Objects.equals(name, animation.name)
                    && Arrays.equals(frames, animation.frames);
        }

        @Override
        public int hashCode()
        {
            int result = Objects.hash(name, loopStartFrame, type, mode);
            result = 31 * result + Arrays.hashCode(frames);
            return result;
        }

        @Override
        public String toString()
        {
            return name.isEmpty() ? String.format("Animation[%d frames]", frames.length) : name;
        }

        /**
         * A single frame of an {@link Animation}. It displays a pooled result (a cell index and, for
         * non-index element types, a transform) for {@link #getDuration() duration} display frames.
         */
        public class Frame
        {
            private long resultOffset;
            private int duration;
            private int sentinel; // the 0xBEEF marker that follows the duration; preserved verbatim

            /**
             * Gets how long this frame is shown, in display frames (the DS renders at 60 per second).
             * @return an <code>int</code>
             */
            public int getDuration()
            {
                return duration;
            }

            /**
             * Sets how long this frame is shown, in display frames.
             * @param duration an <code>int</code>
             */
            public void setDuration(int duration)
            {
                this.duration = duration;
            }

            /**
             * Gets the index of the {@link CellBank} cell this frame displays.
             * @return an <code>int</code>
             */
            public int getCellIndex()
            {
                int off = (int) resultOffset;
                return (resultPool[off] & 0xFF) | ((resultPool[off + 1] & 0xFF) << 8);
            }

            /**
             * Sets the index of the {@link CellBank} cell this frame displays. Because pooled results
             * can be shared between frames, changing one frame's cell index changes it for every frame
             * that references the same pooled result.
             * @param cellIndex an <code>int</code>
             */
            public void setCellIndex(int cellIndex)
            {
                writeS16(0, cellIndex);
            }

            // The frame's pooled result stores the cell index first, then a transform whose size and
            // shape depend on the parent animation's element type:
            //   index (0):       s16 index, u16 pad                                                (4 bytes)
            //   translation (2): s16 index, u16 pad, s16 px, s16 py                                (8 bytes)
            //   SRT (1):         s16 index, s16 rotation, s32 scaleX, s32 scaleY, s16 px, s16 py  (16 bytes)
            // Scales are 20.12 fixed point (0x1000 = 1.0); rotation is a signed 16-bit angle where a
            // full turn is 0x10000. The accessors below read and write these fields in place, so they
            // do not affect the byte-exact round-trip unless a value is actually changed.

            /**
             * Gets this frame's translation along x, in pixels. Zero for the index element type, which
             * carries no transform.
             * @return an <code>int</code>
             */
            public int getTranslateX()
            {
                if (getElement() == ELEMENT_SRT) return readS16(12);
                if (getElement() == ELEMENT_TRANSLATION) return readS16(4);
                return 0;
            }

            /**
             * Gets this frame's translation along y, in pixels. Zero for the index element type.
             * @return an <code>int</code>
             */
            public int getTranslateY()
            {
                if (getElement() == ELEMENT_SRT) return readS16(14);
                if (getElement() == ELEMENT_TRANSLATION) return readS16(6);
                return 0;
            }

            /**
             * Sets this frame's translation, in pixels.
             * @param x an <code>int</code>
             * @param y an <code>int</code>
             * @throws IllegalStateException if the parent animation is the index element type, which has no transform
             */
            public void setTranslate(int x, int y)
            {
                if (getElement() == ELEMENT_SRT) { writeS16(12, x); writeS16(14, y); }
                else if (getElement() == ELEMENT_TRANSLATION) { writeS16(4, x); writeS16(6, y); }
                else throw new IllegalStateException("The index element type carries no translation.");
            }

            /**
             * Gets this frame's rotation as a signed 16-bit angle (a full turn is 0x10000). Zero for
             * element types that carry no rotation.
             * @return an <code>int</code>
             */
            public int getRotation()
            {
                return getElement() == ELEMENT_SRT ? readS16(2) : 0;
            }

            /**
             * Sets this frame's rotation as a signed 16-bit angle (a full turn is 0x10000).
             * @param rotation an <code>int</code>
             * @throws IllegalStateException if the parent animation is not the SRT element type
             */
            public void setRotation(int rotation)
            {
                requireSrt();
                writeS16(2, rotation);
            }

            /**
             * Gets this frame's scale along x. 1.0 for element types that carry no scale.
             * @return a <code>double</code>
             */
            public double getScaleX()
            {
                return getElement() == ELEMENT_SRT ? readS32(4) / 4096.0 : 1.0;
            }

            /**
             * Gets this frame's scale along y. 1.0 for element types that carry no scale.
             * @return a <code>double</code>
             */
            public double getScaleY()
            {
                return getElement() == ELEMENT_SRT ? readS32(8) / 4096.0 : 1.0;
            }

            /**
             * Sets this frame's scale.
             * @param scaleX a <code>double</code>
             * @param scaleY a <code>double</code>
             * @throws IllegalStateException if the parent animation is not the SRT element type
             */
            public void setScale(double scaleX, double scaleY)
            {
                requireSrt();
                writeS32(4, (int) Math.round(scaleX * 4096.0));
                writeS32(8, (int) Math.round(scaleY * 4096.0));
            }

            private void requireSrt()
            {
                if (getElement() != ELEMENT_SRT)
                    throw new IllegalStateException("Only the SRT element type carries scale and rotation.");
            }

            private int readS16(int rel)
            {
                int o = (int) resultOffset + rel;
                return (short) ((resultPool[o] & 0xFF) | ((resultPool[o + 1] & 0xFF) << 8));
            }

            private void writeS16(int rel, int value)
            {
                int o = (int) resultOffset + rel;
                resultPool[o] = (byte) (value & 0xFF);
                resultPool[o + 1] = (byte) ((value >> 8) & 0xFF);
            }

            private int readS32(int rel)
            {
                int o = (int) resultOffset + rel;
                return (resultPool[o] & 0xFF) | ((resultPool[o + 1] & 0xFF) << 8)
                        | ((resultPool[o + 2] & 0xFF) << 16) | ((resultPool[o + 3] & 0xFF) << 24);
            }

            private void writeS32(int rel, int value)
            {
                int o = (int) resultOffset + rel;
                resultPool[o] = (byte) (value & 0xFF);
                resultPool[o + 1] = (byte) ((value >> 8) & 0xFF);
                resultPool[o + 2] = (byte) ((value >> 16) & 0xFF);
                resultPool[o + 3] = (byte) ((value >> 24) & 0xFF);
            }

            @Override
            public boolean equals(Object o)
            {
                if (this == o)
                    return true;
                if (o == null || getClass() != o.getClass())
                    return false;
                Frame frame = (Frame) o;
                return resultOffset == frame.resultOffset && duration == frame.duration && sentinel == frame.sentinel;
            }

            @Override
            public int hashCode()
            {
                return Objects.hash(resultOffset, duration, sentinel);
            }

            @Override
            public String toString()
            {
                return String.format("Frame[cell=%d, duration=%d]", getCellIndex(), duration);
            }
        }
    }
}
