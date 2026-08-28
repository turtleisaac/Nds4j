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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * An object representation of an NSBTX file (a Nitro 3D texture archive, magic {@code BTX0}).
 * <p>
 * An NSBTX holds a single {@code TEX0} block: a set of named {@link Texture}s and named
 * {@link Palette}s (both indexed by {@link G3dDictionary}), plus the raw texel and colour data they
 * point into. The same {@code TEX0} block also appears embedded inside an NSBMD model; this class
 * reads the standalone form. Textures name their pixel format, size and data location through a
 * 32-bit {@code texImageParam}; palettes are shared, so a texture is decoded against a chosen palette.
 * <p>
 * The file round-trips byte-for-byte: the {@code TEX0} block (and any others) is preserved verbatim
 * and the dictionaries/data are parsed as a read-only view over it for decoding and export.
 */
public class TextureSet extends G3dFile
{
    // Texture formats (NNS_G3D_TEX_FORMAT), indexed by the texImageParam format field.
    private static final int FORMAT_A3I5 = 1;
    private static final int FORMAT_PLTT4 = 2;
    private static final int FORMAT_PLTT16 = 3;
    private static final int FORMAT_PLTT256 = 4;
    private static final int FORMAT_COMP4x4 = 5;
    private static final int FORMAT_A5I3 = 6;
    private static final int FORMAT_DIRECT = 7;

    private final List<Texture> textures = new ArrayList<>();
    private final List<Palette> palettes = new ArrayList<>();

    // Views into the TEX0 block. Offsets are relative to the block's first byte.
    private byte[] tex0;
    private int texDataOfs, tex4x4DataOfs, tex4x4PlttIdxOfs, plttDataOfs;

    /**
     * Generates an object representation of an NSBTX file.
     * @param data a <code>byte[]</code> representation of an NSBTX file
     */
    public TextureSet(byte[] data)
    {
        super("BTX0");
        readContainer(data);
        int tex0Index = indexOfBlock("TEX0");
        if (tex0Index < 0)
            throw new RuntimeException("Not a valid BTX0 file: missing TEX0 block.");
        parseTex0(block(tex0Index));
    }

    private TextureSet(byte[] tex0Block, boolean fromRawTex0)
    {
        super("BTX0");
        parseTex0(tex0Block);
    }

    /**
     * Authors a texture-only {@code TextureSet} (NSBTX) from NITRO intermediate ({@code .imd}) source &mdash;
     * the native, byte-for-byte replacement for Nintendo's {@code g3dcvtr -etex}.
     * @param imdXml the full contents of an {@code .imd} file (must carry a {@code tex_image})
     * @see ImdImporter
     */
    public static TextureSet fromImd(String imdXml)
    {
        return ImdImporter.fromXml(imdXml).toTextureSet();
    }

    /** Authors a texture-only {@code TextureSet} (NSBTX) from an {@code .imd} file. */
    public static TextureSet fromImd(java.io.File imdFile) throws java.io.IOException
    {
        return ImdImporter.fromFile(imdFile).toTextureSet();
    }

    /**
     * Builds a read-only texture decoder over a bare {@code TEX0} block &mdash; the form embedded inside
     * an NSBMD model (see {@link ModelSet#getEmbeddedTextures()}). This view decodes and exports
     * textures like a standalone NSBTX; it is not a container, so {@link #save()} is not meaningful on it
     * (the owning {@link ModelSet} round-trips the bytes).
     * @param tex0Block the raw {@code TEX0} block
     * @return a {@link TextureSet}
     */
    static TextureSet fromTex0Block(byte[] tex0Block)
    {
        return new TextureSet(tex0Block, true);
    }

    private void parseTex0(byte[] tex0Block)
    {
        tex0 = tex0Block;
        MemBuf buf = MemBuf.create(tex0);
        MemBuf.MemBufReader r = buf.reader();

        String magic = r.readString(4);
        if (!magic.equals("TEX0"))
            throw new RuntimeException("Not a valid BTX0 file: missing TEX0 block.");
        r.skip(4); // block size

        // TexInfo
        r.skip(4); // vramKey
        r.skip(2); // sizeTex (<<3) -- recoverable from the data, not needed here
        int texOfsDict = r.readUInt16();
        r.skip(2); // flag
        r.skip(2); // padding
        texDataOfs = (int) r.readUInt32();

        // Tex4x4Info
        r.skip(4); // vramKey
        r.skip(2); // sizeTex
        int tex4x4OfsDict = r.readUInt16();
        r.skip(2); // flag
        r.skip(2); // padding
        tex4x4DataOfs = (int) r.readUInt32();
        tex4x4PlttIdxOfs = (int) r.readUInt32();

        // PlttInfo
        r.skip(4); // vramKey
        r.skip(2); // sizePltt
        r.skip(2); // flag
        int plttOfsDict = r.readUInt16();
        r.skip(2); // padding
        plttDataOfs = (int) r.readUInt32();

        // texture dictionary (8-byte records: texImageParam, extraParam)
        r.setPosition(texOfsDict);
        G3dDictionary texDict = new G3dDictionary(r);
        for (int i = 0; i < texDict.size(); i++)
        {
            byte[] rec = texDict.getRecord(i);
            long texImageParam = readU32(rec, 0);
            long extraParam = readU32(rec, 4);
            textures.add(new Texture(texDict.getName(i), texImageParam, extraParam));
        }

        // palette dictionary (4-byte records: offset, flag)
        r.setPosition(plttOfsDict);
        G3dDictionary plttDict = new G3dDictionary(r);
        for (int i = 0; i < plttDict.size(); i++)
        {
            byte[] rec = plttDict.getRecord(i);
            int offsetUnits = (rec[0] & 0xFF) | ((rec[1] & 0xFF) << 8);
            palettes.add(new Palette(plttDict.getName(i), offsetUnits << 3));
        }
    }

    /**
     * Gets the textures in this archive.
     * @return a <code>List</code> of {@link Texture}
     */
    public List<Texture> getTextures()
    {
        return textures;
    }

    /**
     * Gets the palettes in this archive.
     * @return a <code>List</code> of {@link Palette}
     */
    public List<Palette> getPalettes()
    {
        return palettes;
    }

    // save(), equals(), and hashCode() are inherited from G3dFile (block-level, byte-exact).

    @Override
    public String toString()
    {
        return String.format("TextureSet[%d textures, %d palettes]", textures.size(), palettes.size());
    }

    /**
     * Gets the texture with the given name, or null.
     * @param name a texture name
     * @return a {@link Texture} or null
     */
    public Texture getTexture(String name)
    {
        for (Texture t : textures)
            if (t.name.equals(name))
                return t;
        return null;
    }

    /**
     * Gets the palette with the given name, or null.
     * @param name a palette name
     * @return a {@link Palette} or null
     */
    public Palette getPalette(String name)
    {
        for (Palette p : palettes)
            if (p.name.equals(name))
                return p;
        return null;
    }

    /**
     * Decodes the named texture to an image (choosing a palette automatically).
     * @param name a texture name
     * @return a <code>BufferedImage</code>
     */
    public BufferedImage getImage(String name)
    {
        Texture t = getTexture(name);
        if (t == null)
            throw new IllegalArgumentException("No texture named " + name);
        return getImage(t);
    }

    /**
     * Exports every texture in this archive to a PNG in the given directory, named
     * <code>&lt;textureName&gt;.png</code>. This is the user-friendly export path &mdash; standard PNGs
     * carry the decoded RGBA, including per-texel alpha.
     * @param directory the target directory (created if absent)
     * @throws IOException if a file cannot be written
     */
    public void exportTexturesToDirectory(File directory) throws IOException
    {
        if (!directory.exists() && !directory.mkdirs())
            throw new IOException("Could not create directory " + directory);
        for (Texture t : textures)
            ImageIO.write(getImage(t), "png", new File(directory, t.getName() + ".png"));
    }

    /**
     * Decodes a texture to an image, choosing the palette whose name best matches (the same name, else
     * the palette at the same index, else the first). Direct-colour textures ignore the palette.
     * @param texture a {@link Texture} from this archive
     * @return a <code>BufferedImage</code>
     */
    public BufferedImage getImage(Texture texture)
    {
        return getImage(texture, choosePalette(texture));
    }

    private Palette choosePalette(Texture texture)
    {
        for (Palette p : palettes)
            if (p.name.equals(texture.name))
                return p;
        // many archives suffix the palette name with "_pl"/"_pltt"; fall back to positional pairing
        int idx = textures.indexOf(texture);
        if (idx >= 0 && idx < palettes.size())
            return palettes.get(idx);
        return palettes.isEmpty() ? null : palettes.get(0);
    }

    /**
     * Decodes a texture to an image against a specific palette.
     * @param texture a {@link Texture}
     * @param palette a {@link Palette} (may be null for direct-colour textures)
     * @return a <code>BufferedImage</code>
     */
    public BufferedImage getImage(Texture texture, Palette palette)
    {
        int w = texture.getWidth();
        int h = texture.getHeight();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        switch (texture.getFormat())
        {
            case FORMAT_PLTT4:   decodePalette(img, texture, palette, 2); break;
            case FORMAT_PLTT16:  decodePalette(img, texture, palette, 4); break;
            case FORMAT_PLTT256: decodePalette(img, texture, palette, 8); break;
            case FORMAT_A3I5:    decodeAlpha(img, texture, palette, 5, 3); break;
            case FORMAT_A5I3:    decodeAlpha(img, texture, palette, 3, 5); break;
            case FORMAT_DIRECT:  decodeDirect(img, texture); break;
            case FORMAT_COMP4x4: decodeComp4x4(img, texture, palette); break;
            default: throw new UnsupportedOperationException("Unsupported texture format " + texture.getFormat());
        }
        return img;
    }

    /**
     * Gets the raw on-disk texel bytes of a texture &mdash; the data an encoder must reproduce.
     * @param texture a texture from this set
     * @return the texel bytes, or null for a format whose length is not fixed here (compressed 4x4)
     */
    public byte[] getRawTextureData(Texture texture)
    {
        int w = texture.getWidth(), h = texture.getHeight();
        int bytes;
        switch (texture.getFormat())
        {
            case FORMAT_PLTT4:   bytes = w * h / 4; break;
            case FORMAT_PLTT16:  bytes = w * h / 2; break;
            case FORMAT_PLTT256: bytes = w * h; break;
            case FORMAT_A3I5: case FORMAT_A5I3: bytes = w * h; break;
            case FORMAT_DIRECT:  bytes = w * h * 2; break;
            default: return null;
        }
        int base = texDataOfs + texture.getDataOffset();
        if (base < 0 || base + bytes > tex0.length)
            return null;
        return java.util.Arrays.copyOfRange(tex0, base, base + bytes);
    }

    /**
     * Overwrites a texture's raw texel bytes in the live {@code TEX0} block &mdash; the writer path for
     * replacing a texture's pixels (e.g. with {@link #encodeTextureData} output). Same-size, in place, so
     * an unedited file still round-trips byte-for-byte and the owning container's {@code save()} emits the
     * edited texture.
     * @param texture the texture to overwrite
     * @param data the new texel bytes (must match {@link #getRawTextureData}'s length)
     * @return true if written, false if the length is wrong or the format is unsupported
     */
    public boolean overwriteRawTextureData(Texture texture, byte[] data)
    {
        byte[] cur = getRawTextureData(texture);
        if (cur == null || cur.length != data.length)
            return false;
        int base = texDataOfs + texture.getDataOffset();
        System.arraycopy(data, 0, tex0, base, data.length);
        return true;
    }

    /**
     * Encodes an image back into a texture's on-disk texel bytes &mdash; the writer-side counterpart to
     * {@link #getImage(Texture, Palette)}, for source&rarr;NSB* conversion. Paletted formats map each
     * pixel to its palette index (colour 0 handles transparency); direct colour packs {@code BGR555}.
     * Re-encoding a decoded texture reproduces its bytes exactly when the palette colours are distinct.
     * @param img the image (its size must match the texture)
     * @param texture the target texture (for format/size)
     * @param palette the palette to index against (ignored for direct colour)
     * @return the encoded texel bytes, or null for an unsupported format (A3I5/A5I3/compressed 4x4)
     */
    /**
     * Encodes an image into texel bytes, choosing the same palette {@link #getImage(Texture)} decodes
     * against.
     * @param img the image
     * @param texture the target texture
     * @return the encoded texel bytes, or null for an unsupported format
     */
    public byte[] encodeTextureData(BufferedImage img, Texture texture)
    {
        return encodeTextureData(img, texture, choosePalette(texture));
    }

    public byte[] encodeTextureData(BufferedImage img, Texture texture, Palette palette)
    {
        switch (texture.getFormat())
        {
            case FORMAT_PLTT4:   return encodePaletted(img, texture, palette, 2);
            case FORMAT_PLTT16:  return encodePaletted(img, texture, palette, 4);
            case FORMAT_PLTT256: return encodePaletted(img, texture, palette, 8);
            case FORMAT_DIRECT:  return encodeDirect(img);
            default: return null;
        }
    }

    private byte[] encodePaletted(BufferedImage img, Texture texture, Palette palette, int bpp)
    {
        int w = img.getWidth(), h = img.getHeight();
        boolean color0Transparent = texture.isColor0Transparent();
        int colors = 1 << bpp;
        // reverse map colour -> lowest index (skip index 0 when it means "transparent")
        java.util.Map<Integer, Integer> byColor = new java.util.HashMap<>();
        for (int i = colors - 1; i >= (color0Transparent ? 1 : 0); i--)
            byColor.put(colorValue(palette, i) & 0xFFFFFF, i);

        int perByte = 8 / bpp;
        byte[] out = new byte[w * h / perByte];
        for (int p = 0; p < w * h; p++)
        {
            int argb = img.getRGB(p % w, p / w);
            int index;
            if (color0Transparent && (argb >>> 24) < 128)
                index = 0;
            else
                index = byColor.getOrDefault(argb & 0xFFFFFF, 0);
            out[p / perByte] |= (index & ((1 << bpp) - 1)) << ((p % perByte) * bpp);
        }
        return out;
    }

    private byte[] encodeDirect(BufferedImage img)
    {
        int w = img.getWidth(), h = img.getHeight();
        byte[] out = new byte[w * h * 2];
        for (int p = 0; p < w * h; p++)
        {
            int argb = img.getRGB(p % w, p / w);
            // >>3 is the exact inverse of the decoder's (bits<<3), so a decoded image re-encodes losslessly
            int r = ((argb >> 16) & 0xFF) >> 3;
            int g = ((argb >> 8) & 0xFF) >> 3;
            int b = (argb & 0xFF) >> 3;
            int v = r | (g << 5) | (b << 10);
            if ((argb >>> 24) >= 128)
                v |= 0x8000; // alpha/opaque bit
            out[p * 2] = (byte) v;
            out[p * 2 + 1] = (byte) (v >> 8);
        }
        return out;
    }

    private void decodePalette(BufferedImage img, Texture texture, Palette palette, int bitsPerPixel)
    {
        int w = img.getWidth(), h = img.getHeight();
        int base = texDataOfs + texture.getDataOffset();
        int perByte = 8 / bitsPerPixel;
        int mask = (1 << bitsPerPixel) - 1;
        for (int y = 0; y < h; y++)
        {
            for (int x = 0; x < w; x++)
            {
                int pixelIndex = y * w + x;
                int b = tex0[base + pixelIndex / perByte] & 0xFF;
                int shift = (pixelIndex % perByte) * bitsPerPixel;
                int index = (b >> shift) & mask;
                img.setRGB(x, y, paletteColor(palette, index, texture.isColor0Transparent() && index == 0));
            }
        }
    }

    private void decodeAlpha(BufferedImage img, Texture texture, Palette palette, int indexBits, int alphaBits)
    {
        int w = img.getWidth(), h = img.getHeight();
        int base = texDataOfs + texture.getDataOffset();
        int indexMask = (1 << indexBits) - 1;
        int alphaMax = (1 << alphaBits) - 1;
        for (int y = 0; y < h; y++)
        {
            for (int x = 0; x < w; x++)
            {
                int b = tex0[base + y * w + x] & 0xFF;
                int index = b & indexMask;
                int alpha = (b >> indexBits) & alphaMax;
                int rgb = paletteColor(palette, index, false) & 0xFFFFFF;
                int a = (alpha * 255) / alphaMax;
                img.setRGB(x, y, (a << 24) | rgb);
            }
        }
    }

    private void decodeDirect(BufferedImage img, Texture texture)
    {
        int w = img.getWidth(), h = img.getHeight();
        int base = texDataOfs + texture.getDataOffset();
        for (int y = 0; y < h; y++)
        {
            for (int x = 0; x < w; x++)
            {
                int off = base + (y * w + x) * 2;
                int value = (tex0[off] & 0xFF) | ((tex0[off + 1] & 0xFF) << 8);
                int a = (value & 0x8000) != 0 ? 255 : 0;
                img.setRGB(x, y, (a << 24) | (bgr555(value) & 0xFFFFFF));
            }
        }
    }

    // 4x4-block texel compression (NNS format 5). Each 4x4 block of texels is 4 bytes of 2-bit indices
    // in the texel data, paired with a 16-bit control value in the palette-index data that gives the
    // block's palette base (in 8-byte units, low 14 bits) and its interpolation mode (top 2 bits).
    private void decodeComp4x4(BufferedImage img, Texture texture, Palette palette)
    {
        int w = img.getWidth(), h = img.getHeight();
        int texelBase = tex4x4DataOfs + texture.getDataOffset();
        int idxBase = tex4x4PlttIdxOfs + texture.getDataOffset() / 2;
        int blocksWide = w / 4;

        for (int by = 0; by < h / 4; by++)
        {
            for (int bx = 0; bx < blocksWide; bx++)
            {
                int blockNum = by * blocksWide + bx;
                int texelOff = texelBase + blockNum * 4;
                int control = (tex0[idxBase + blockNum * 2] & 0xFF) | ((tex0[idxBase + blockNum * 2 + 1] & 0xFF) << 8);
                int paletteBase = (control & 0x3FFF) << 1; // in colours, within the palette region
                int mode = (control >> 14) & 3;

                for (int ty = 0; ty < 4; ty++)
                {
                    int row = tex0[texelOff + ty] & 0xFF;
                    for (int tx = 0; tx < 4; tx++)
                    {
                        int index = (row >> (tx * 2)) & 3;
                        int argb = comp4x4Color(palette, paletteBase, index, mode);
                        img.setRGB(bx * 4 + tx, by * 4 + ty, argb);
                    }
                }
            }
        }
    }

    private int comp4x4Color(Palette palette, int paletteBase, int index, int mode)
    {
        // Colours are read relative to the palette's own base; the block adds its paletteBase on top.
        int c0 = colorValue(palette, paletteBase + 0);
        int c1 = colorValue(palette, paletteBase + 1);
        switch (mode)
        {
            case 0:
                return index == 3 ? 0 : opaque(colorValue(palette, paletteBase + index));
            case 1:
                if (index == 0 || index == 1) return opaque(colorValue(palette, paletteBase + index));
                if (index == 2) return opaque(blend(c0, c1, 1, 1, 2));
                return 0;
            case 2:
                return opaque(colorValue(palette, paletteBase + index));
            default: // mode 3
                if (index == 0 || index == 1) return opaque(colorValue(palette, paletteBase + index));
                if (index == 2) return opaque(blend(c0, c1, 5, 3, 8));
                return opaque(blend(c0, c1, 3, 5, 8));
        }
    }

    private static int blend(int a, int b, int wa, int wb, int total)
    {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (ar * wa + br * wb) / total;
        int g = (ag * wa + bg * wb) / total;
        int bl = (ab * wa + bb * wb) / total;
        return (r << 16) | (g << 8) | bl;
    }

    private static int opaque(int rgb)
    {
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }

    private int colorValue(Palette palette, int index)
    {
        if (palette == null)
            return 0;
        int abs = plttDataOfs + palette.dataOffset + index * 2;
        if (abs < 0 || abs + 1 >= tex0.length)
            return 0;
        int value = (tex0[abs] & 0xFF) | ((tex0[abs + 1] & 0xFF) << 8);
        return bgr555(value);
    }

    /**
     * Reads a palette entry as {@code 0xRRGGBB}. @param palette a palette from this set @param index the
     * colour index @return the colour as 24-bit RGB
     */
    public int getPaletteColor(Palette palette, int index)
    {
        return colorValue(palette, index) & 0xFFFFFF;
    }

    /**
     * Recolours a palette entry in place &mdash; the writer-side edit that "repaints" a texture. The
     * {@code 0xRRGGBB} colour is quantised to the DS's 15-bit {@code BGR555} and written straight into the
     * live {@code TEX0} block, so it shows immediately in a re-decoded {@link #getImage} and is emitted by
     * the owning container's {@code save()} (this {@link TextureSet} for a standalone NSBTX, or the
     * {@link ModelSet} whose embedded {@code TEX0} this views). The edit is same-size, so an unedited file
     * still round-trips byte-for-byte.
     * @param palette a palette from this set
     * @param index the colour index to change
     * @param rgb the new colour as {@code 0xRRGGBB}
     */
    public void setPaletteColor(Palette palette, int index, int rgb)
    {
        int abs = plttDataOfs + palette.dataOffset + index * 2;
        if (abs < 0 || abs + 1 >= tex0.length)
            return;
        int r = ((rgb >> 16) & 0xFF) * 31 / 255;
        int g = ((rgb >> 8) & 0xFF) * 31 / 255;
        int b = (rgb & 0xFF) * 31 / 255;
        int v = r | (g << 5) | (b << 10);
        tex0[abs] = (byte) v;
        tex0[abs + 1] = (byte) (v >> 8);
    }

    private int paletteColor(Palette palette, int index, boolean transparent)
    {
        if (transparent || palette == null)
            return 0;
        return opaque(colorValue(palette, index));
    }

    private static int bgr555(int value)
    {
        int r = (value & 0x1F) << 3;
        int g = ((value >> 5) & 0x1F) << 3;
        int b = ((value >> 10) & 0x1F) << 3;
        return (r << 16) | (g << 8) | b;
    }

    private static long readU32(byte[] b, int o)
    {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    /** A named texture: its pixel format, dimensions and data location come from its texImageParam. */
    public class Texture
    {
        private final String name;
        private final long texImageParam;
        private final long extraParam;

        private Texture(String name, long texImageParam, long extraParam)
        {
            this.name = name;
            this.texImageParam = texImageParam;
            this.extraParam = extraParam;
        }

        /** @return this texture's name */
        public String getName() { return name; }
        /** @return this texture's width in texels */
        public int getWidth() { return 8 << ((int) (texImageParam >> 20) & 7); }
        /** @return this texture's height in texels */
        public int getHeight() { return 8 << ((int) (texImageParam >> 23) & 7); }
        /** @return this texture's NNS format id (1-7) */
        public int getFormat() { return (int) (texImageParam >> 26) & 7; }
        /** @return whether palette colour 0 is treated as transparent */
        public boolean isColor0Transparent() { return ((texImageParam >> 29) & 1) != 0; }
        /** @return the byte offset of this texture's data within its texel region */
        public int getDataOffset() { return (int) (texImageParam & 0xFFFF) << 3; }

        @Override
        public String toString()
        {
            return String.format("%s (%dx%d fmt%d)", name, getWidth(), getHeight(), getFormat());
        }
    }

    /** A named palette: a start offset into the archive's shared colour data. */
    public class Palette
    {
        private final String name;
        private final int dataOffset;

        private Palette(String name, int dataOffset)
        {
            this.name = name;
            this.dataOffset = dataOffset;
        }

        /** @return this palette's name */
        public String getName() { return name; }

        @Override
        public String toString()
        {
            return name;
        }
    }
}
