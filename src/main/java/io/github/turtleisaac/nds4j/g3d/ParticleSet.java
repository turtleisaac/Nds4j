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

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * An object representation of an SPA file (a Nitro <b>SPL particle</b> archive) &mdash; the format Gen IV
 * uses for battle/move particle effects. Its magic is stored <em>byte-reversed</em> on disk as
 * {@code " APS"} (i.e. {@code "SPA "} little-endian), which is why a forward {@code "SPA"} scan misses it;
 * the retail ROMs pack hundreds (Platinum: narcs 460/461).
 * <p>
 * The container round-trips byte-for-byte (the raw bytes are preserved and {@link #save()} returns them),
 * so an unedited file is exact. On top of that it decodes the archive header (emitter and texture counts,
 * the texture section) and the embedded {@code " TPS"} ({@code "SPT "}) <b>particle textures</b> to
 * {@link BufferedImage}s &mdash; the alpha-mask sprites (glows, sparks, rings, streaks) the emitters draw.
 * The per-emitter behaviour parameters are preserved verbatim but not yet decoded.
 * <p>
 * Layout reverse-engineered from the retail files: header {@code " APS"}, version {@code "12_1"},
 * {@code u16 emitterCount}, {@code u16 textureCount}, then at {@code +0x14}/{@code +0x18} the texture
 * section size/offset. Each {@code " TPS"} texture: {@code u32 texParam} (format = {@code &7},
 * width = {@code 8<<((p>>4)&7)}, height = {@code 8<<((p>>8)&7)}), {@code u32 texelSize}, {@code u32}
 * palette offset, {@code u32} palette size, {@code u32} total size; texels at {@code +0x20}, palette at
 * the given offset.
 */
public class ParticleSet
{
    /** SPA magic as stored on disk (the 4CC {@code "SPA "} byte-reversed). */
    public static final String MAGIC = " APS";

    private final byte[] data;
    private final String version;
    private final int emitterCount;
    private final List<ParticleTexture> textures = new ArrayList<>();

    /**
     * Generates an object representation of an SPA file.
     * @param data a <code>byte[]</code> representation of an SPA file
     */
    public ParticleSet(byte[] data)
    {
        this.data = data.clone();
        if (data.length < 0x20 || !magicAt(0).equals(MAGIC))
            throw new RuntimeException("Not a valid SPA file (magic \" APS\" expected).");
        version = new String(data, 4, 4, StandardCharsets.US_ASCII);
        emitterCount = u16(8);
        int textureCount = u16(10);
        int texSectionOffset = (int) u32(0x18);

        int p = texSectionOffset;
        for (int i = 0; i < textureCount && p + 0x20 <= data.length; i++)
        {
            if (!magicAt(p).equals(" TPS"))
                break; // desync guard: the texture section should be a run of SPT blocks
            ParticleTexture t = new ParticleTexture(p);
            textures.add(t);
            p += t.totalSize;
        }
    }

    /** @return the archive's version tag (e.g. {@code "12_1"}) */
    public String getVersion() { return version; }
    /** @return the number of particle emitters (their parameters are preserved but not yet decoded) */
    public int getEmitterCount() { return emitterCount; }
    /** @return the decoded particle textures */
    public List<ParticleTexture> getTextures() { return textures; }

    /**
     * Returns the file's bytes, reproducing it exactly (an unedited archive round-trips byte-for-byte).
     * @return a <code>byte[]</code>
     */
    public byte[] save() { return data.clone(); }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return Arrays.equals(data, ((ParticleSet) o).data);
    }

    @Override
    public int hashCode() { return Objects.hash(Arrays.hashCode(data)); }

    @Override
    public String toString()
    {
        return String.format("ParticleSet[%s, %d emitters, %d textures]", version, emitterCount, textures.size());
    }

    /** One particle texture ({@code " TPS"}/{@code "SPT "}): an alpha-mask sprite an emitter draws. */
    public final class ParticleTexture
    {
        private final int start;
        private final int format, width, height;
        private final int texelOffset, paletteOffset;
        private final int totalSize;

        ParticleTexture(int start)
        {
            this.start = start;
            long param = u32(start + 4);
            this.format = (int) (param & 7);
            this.width = 8 << ((int) (param >> 4) & 7);
            this.height = 8 << ((int) (param >> 8) & 7);
            this.texelOffset = start + 0x20;
            this.paletteOffset = start + (int) u32(start + 12);
            this.totalSize = (int) u32(start + 20);
        }

        /** @return the texture width in texels */
        public int getWidth() { return width; }
        /** @return the texture height in texels */
        public int getHeight() { return height; }
        /** @return the NNS texture format (particle sprites are usually 6 = A5I3) */
        public int getFormat() { return format; }

        /**
         * Decodes this particle texture to an image (RGBA, with the sprite's alpha).
         * @return a {@link BufferedImage}
         */
        public BufferedImage getImage()
        {
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++)
                    img.setRGB(x, y, texel(y * width + x));
            return img;
        }

        // Decodes one texel to ARGB, covering the palette/alpha/direct formats a particle uses.
        private int texel(int p)
        {
            switch (format)
            {
                case 6: // A5I3: 3-bit index, 5-bit alpha
                {
                    int b = data[texelOffset + p] & 0xFF;
                    return (scale(b >> 3, 31) << 24) | color(b & 7);
                }
                case 1: // A3I5: 5-bit index, 3-bit alpha
                {
                    int b = data[texelOffset + p] & 0xFF;
                    return (scale(b >> 5, 7) << 24) | color(b & 0x1F);
                }
                case 2: // PLTT4 (2bpp)
                    return opaque(color(bits(p, 2)));
                case 3: // PLTT16 (4bpp)
                    return opaque(color(bits(p, 4)));
                case 4: // PLTT256 (8bpp)
                    return opaque(color(data[texelOffset + p] & 0xFF));
                case 7: // DIRECT (BGR555)
                {
                    int o = texelOffset + p * 2;
                    int v = (data[o] & 0xFF) | ((data[o + 1] & 0xFF) << 8);
                    return ((v & 0x8000) != 0 ? 0xFF000000 : 0) | bgr555(v);
                }
                default:
                    return 0;
            }
        }

        private int bits(int pixel, int bpp)
        {
            int perByte = 8 / bpp;
            int b = data[texelOffset + pixel / perByte] & 0xFF;
            return (b >> ((pixel % perByte) * bpp)) & ((1 << bpp) - 1);
        }

        private int color(int index)
        {
            int o = paletteOffset + index * 2;
            if (o + 1 >= data.length)
                return 0;
            return bgr555((data[o] & 0xFF) | ((data[o + 1] & 0xFF) << 8));
        }
    }

    private static int opaque(int rgb) { return 0xFF000000 | rgb; }
    private static int scale(int v, int max) { return v * 255 / max; }
    private static int bgr555(int v)
    {
        return (((v & 0x1F) << 3) << 16) | (((v >> 5) & 0x1F) << 3 << 8) | (((v >> 10) & 0x1F) << 3);
    }

    private String magicAt(int o)
    {
        return o + 4 <= data.length ? new String(data, o, 4, StandardCharsets.ISO_8859_1) : "";
    }

    private int u16(int o) { return (data[o] & 0xFF) | ((data[o + 1] & 0xFF) << 8); }
    private long u32(int o)
    {
        return (data[o] & 0xFFL) | ((data[o + 1] & 0xFFL) << 8) | ((data[o + 2] & 0xFFL) << 16) | ((data[o + 3] & 0xFFL) << 24);
    }
}
