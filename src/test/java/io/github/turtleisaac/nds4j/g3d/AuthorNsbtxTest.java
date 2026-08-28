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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that the encoder pieces compose into <b>source&rarr;NSB* conversion</b>: this authors a
 * complete, valid NSBTX from an image entirely from scratch &mdash; assembling the {@code TEX0} block
 * (header + texture data), building the resource dictionaries with {@link G3dDictionary#build}, and
 * wrapping it in a container with {@link G3dFile#assembleContainer} &mdash; then reads it back with the
 * production {@link TextureSet} and confirms the decoded image is pixel-identical. Nothing here parses a
 * pre-existing file; the bytes are built.
 */
@DisplayName("author an NSBTX from scratch (end-to-end conversion)")
public class AuthorNsbtxTest
{
    @Test
    @DisplayName("a from-scratch NSBTX reads back pixel-exact")
    void authorAndReadBack()
    {
        int w = 8, h = 8;
        // an image whose colours are already 5-bit-aligned per channel, so BGR555 is lossless
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
            {
                int r = (x * 4) & 0xF8, g = (y * 4) & 0xF8, b = ((x + y) * 4) & 0xF8;
                img.setRGB(x, y, 0xFF000000 | (r << 16) | (g << 8) | b);
            }

        byte[] tex0 = buildDirectTex0("authored", img);
        byte[] file = G3dFile.assembleContainer("BTX0", 1, tex0);

        // read it back with the production decoder
        TextureSet set = new TextureSet(file);
        List<TextureSet.Texture> textures = set.getTextures();
        assertThat(textures).hasSize(1);
        TextureSet.Texture t = textures.get(0);
        assertThat(t.getName()).isEqualTo("authored");
        assertThat(t.getWidth()).isEqualTo(w);
        assertThat(t.getHeight()).isEqualTo(h);

        BufferedImage decoded = set.getImage(t);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                assertThat(decoded.getRGB(x, y) & 0xFFFFFF)
                        .as("pixel (%d,%d) survives author->read", x, y)
                        .isEqualTo(img.getRGB(x, y) & 0xFFFFFF);

        // and the authored file round-trips its own bytes
        assertThat(set.save()).isEqualTo(file);
    }

    // Assembles a TEX0 block holding one direct-colour texture: header, the tex/pltt dictionaries built
    // by G3dDictionary.build, and the encoded texel data.
    private static byte[] buildDirectTex0(String name, BufferedImage img)
    {
        int w = img.getWidth(), h = img.getHeight();
        byte[] data = new byte[w * h * 2]; // BGR555 per pixel
        for (int p = 0; p < w * h; p++)
        {
            int argb = img.getRGB(p % w, p / w);
            int r = ((argb >> 16) & 0xFF) >> 3, g = ((argb >> 8) & 0xFF) >> 3, b = (argb & 0xFF) >> 3;
            int v = r | (g << 5) | (b << 10) | 0x8000;
            data[p * 2] = (byte) v;
            data[p * 2 + 1] = (byte) (v >> 8);
        }

        // texImageParam: format 7 (direct) at bits 26-28, width/height selectors (8 -> 0), dataOffset 0
        int widthSel = Integer.numberOfTrailingZeros(w / 8);
        int heightSel = Integer.numberOfTrailingZeros(h / 8);
        long texImageParam = ((long) 7 << 26) | ((long) widthSel << 20) | ((long) heightSel << 23);
        byte[] texRecord = new byte[8];
        putU32(texRecord, 0, texImageParam);
        G3dDictionary texDict = G3dDictionary.build(List.of(name), List.of(texRecord), 8);
        G3dDictionary plttDict = G3dDictionary.build(List.of(), List.of(), 4);

        byte[] texDictBytes = serialize(texDict);
        byte[] plttDictBytes = serialize(plttDict);

        int headerSize = 0x3C; // 60-byte TEX0 header (TexInfo + Tex4x4Info + PlttInfo)
        int texOfsDict = headerSize;
        int plttOfsDict = texOfsDict + texDictBytes.length;
        int texDataOfs = plttOfsDict + plttDictBytes.length;
        int blockSize = texDataOfs + data.length;
        blockSize = (blockSize + 3) & ~3;

        byte[] block = new byte[blockSize];
        System.arraycopy("TEX0".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, block, 0, 4);
        putU32(block, 4, blockSize);
        // TexInfo
        putU16(block, 12, (data.length >> 3));   // sizeTex (in 8-byte units)
        putU16(block, 14, texOfsDict);           // ofsDict
        putU32(block, 20, texDataOfs);           // ofsData
        // Tex4x4Info (24..43): point its dict at the tex dict, no 4x4 data
        putU16(block, 30, texOfsDict);
        // PlttInfo (44..59): plttOfsDict at +52
        putU16(block, 52, plttOfsDict);
        putU32(block, 56, texDataOfs);           // plttDataOfs (unused by a direct texture)

        System.arraycopy(texDictBytes, 0, block, texOfsDict, texDictBytes.length);
        System.arraycopy(plttDictBytes, 0, block, plttOfsDict, plttDictBytes.length);
        System.arraycopy(data, 0, block, texDataOfs, data.length);
        return block;
    }

    private static byte[] serialize(G3dDictionary d)
    {
        io.github.turtleisaac.nds4j.framework.MemBuf buf = io.github.turtleisaac.nds4j.framework.MemBuf.create();
        d.write(buf.writer());
        return buf.reader().getBuffer();
    }

    private static void putU16(byte[] d, int o, int v) { d[o] = (byte) v; d[o + 1] = (byte) (v >> 8); }
    private static void putU32(byte[] d, int o, long v)
    {
        d[o] = (byte) v; d[o + 1] = (byte) (v >> 8); d[o + 2] = (byte) (v >> 16); d[o + 3] = (byte) (v >> 24);
    }
}
