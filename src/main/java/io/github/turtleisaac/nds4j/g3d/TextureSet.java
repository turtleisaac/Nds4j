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
 * {@link Palette}s (both indexed by {@link G3dDictionary}), plus the raw texel and color data they
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
    private MemBuf.MemBufReader pixels;

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
            long texImageParam = Fx.uint32(rec, 0);
            long extraParam = Fx.uint32(rec, 4);
            textures.add(new Texture(texDict.getName(i), texImageParam, extraParam));
        }

        // palette dictionary (4-byte records: offset, flag)
        r.setPosition(plttOfsDict);
        G3dDictionary plttDict = new G3dDictionary(r);
        for (int i = 0; i < plttDict.size(); i++)
        {
            byte[] rec = plttDict.getRecord(i);
            int offsetUnits = Fx.uint16(rec, 0);
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
     * the palette at the same index, else the first). Direct-color textures ignore the palette.
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
     * @param palette a {@link Palette} (may be null for direct-color textures)
     * @return a <code>BufferedImage</code>
     */
    public BufferedImage getImage(Texture texture, Palette palette)
    {
        int w = texture.getWidth();
        int h = texture.getHeight();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        switch (texture.getFormat())
        {
            case FORMAT_PLTT4:
                decodePalette(img, texture, palette, 2);
                break;
            case FORMAT_PLTT16:
                decodePalette(img, texture, palette, 4);
                break;
            case FORMAT_PLTT256:
                decodePalette(img, texture, palette, 8);
                break;
            case FORMAT_A3I5:
                decodeAlpha(img, texture, palette, 5, 3);
                break;
            case FORMAT_A5I3:
                decodeAlpha(img, texture, palette, 3, 5);
                break;
            case FORMAT_DIRECT:
                decodeDirect(img, texture);
                break;
            case FORMAT_COMP4x4:
                decodeComp4x4(img, texture, palette);
                break;
            default:
                throw new UnsupportedOperationException("Unsupported texture format " + texture.getFormat());
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
            case FORMAT_PLTT4:
                bytes = w * h / 4;
                break;
            case FORMAT_PLTT16:
                bytes = w * h / 2;
                break;
            case FORMAT_PLTT256:
                bytes = w * h;
                break;
            case FORMAT_A3I5:
            case FORMAT_A5I3:
                bytes = w * h;
                break;
            case FORMAT_DIRECT:
                bytes = w * h * 2;
                break;
            default:
                return null;
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

    /**
     * Encodes an image back into a texture's on-disk texel bytes &mdash; the writer-side counterpart to
     * {@link #getImage(Texture, Palette)}, for source&rarr;NSB* conversion. Paletted formats map each
     * pixel to its palette index (color 0 handles transparency); direct color packs {@code BGR555}.
     * Re-encoding a decoded texture reproduces its bytes exactly when the palette colors are distinct.
     * @param img the image (its size must match the texture)
     * @param texture the target texture (for format/size)
     * @param palette the palette to index against (ignored for direct color)
     * @return the encoded texel bytes, or null for an unsupported format (A3I5/A5I3/compressed 4x4)
     */
    public byte[] encodeTextureData(BufferedImage img, Texture texture, Palette palette)
    {
        switch (texture.getFormat())
        {
            case FORMAT_PLTT4:
                return encodePaletted(img, texture, palette, 2);
            case FORMAT_PLTT16:
                return encodePaletted(img, texture, palette, 4);
            case FORMAT_PLTT256:
                return encodePaletted(img, texture, palette, 8);
            case FORMAT_DIRECT:
                return encodeDirect(img);
            default:
                return null;
        }
    }

    private byte[] encodePaletted(BufferedImage img, Texture texture, Palette palette, int bpp)
    {
        int w = img.getWidth(), h = img.getHeight();
        boolean color0Transparent = texture.isColor0Transparent();
        int colors = 1 << bpp;
        // reverse map color -> lowest index (skip index 0 when it means "transparent")
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
        // One reader for every texel and palette fetch in this image.
        pixels = MemBuf.create(tex0).reader();
        decodePaletteRows(img, texture, palette, bitsPerPixel);
    }

    /**
     * @param img the image
     * @param texture the texture
     * @param palette the palette
     * @param bitsPerPixel 2, 4 or 8
     * @see "GBATEK DS 3D Video, palette textures"
     */
    private void decodePaletteRows(BufferedImage img, Texture texture, Palette palette, int bitsPerPixel)
    {
        int h = img.getHeight();
        for (int y = 0; y < h; y++)
            decodePaletteRow(img, texture, palette, bitsPerPixel, y);
    }

    /**
     * @param img the image
     * @param texture the texture
     * @param palette the palette
     * @param bitsPerPixel 2, 4 or 8
     * @param y row
     * @see "GBATEK DS 3D Video, palette textures"
     */
    private void decodePaletteRow(BufferedImage img, Texture texture, Palette palette, int bitsPerPixel, int y)
    {
        int w = img.getWidth();
        int base = texDataOfs + texture.getDataOffset();
        int perByte = 8 / bitsPerPixel;
        int mask = (1 << bitsPerPixel) - 1;
        for (int x = 0; x < w; x++)
            paintPalette(img, texture, palette, y, x, base, perByte, mask);
    }

    /**
     * @param img the image
     * @param texture the texture
     * @param palette the palette
     * @param y row
     * @param x column
     * @param base first texel byte
     * @param perByte texels packed in one byte
     * @param mask index mask
     * @see "GBATEK DS 3D Video, palette textures"
     */
    private void paintPalette(BufferedImage img, Texture texture, Palette palette, int y, int x, int base, int perByte,
            int mask)
    {
        int pixelIndex = y * img.getWidth() + x;
        int b = texByte(base + pixelIndex / perByte);
        int index = (b >> ((pixelIndex % perByte) * (8 / perByte))) & mask;
        img.setRGB(x, y, paletteColor(palette, index, transparentIndex(texture, index)));
    }

    /**
     * @param texture the texture
     * @param index palette index
     * @return true when color 0 is transparent
     * @see "GBATEK DS 3D Video, TEXIMAGE_PARAM"
     */
    private static boolean transparentIndex(Texture texture, int index)
    {
        if (index != 0)
            return false;

        return texture.isColor0Transparent();
    }

    private void decodeAlpha(BufferedImage img, Texture texture, Palette palette, int indexBits, int alphaBits)
    {
        pixels = MemBuf.create(tex0).reader();
        for (int y = 0; y < img.getHeight(); y++)
            decodeAlphaRow(img, texture, palette, indexBits, alphaBits, y);
    }

    /**
     * @param img the image
     * @param texture the texture
     * @param palette the palette
     * @param indexBits bits of the palette index
     * @param alphaBits bits of the alpha
     * @param y row
     * @see "GBATEK DS 3D Video, A3I5 and A5I3"
     */
    private void decodeAlphaRow(BufferedImage img, Texture texture, Palette palette, int indexBits, int alphaBits, int y)
    {
        int base = texDataOfs + texture.getDataOffset();
        int indexMask = (1 << indexBits) - 1;
        int alphaMax = (1 << alphaBits) - 1;
        for (int x = 0; x < img.getWidth(); x++)
            paintAlpha(img, palette, y, x, texByte(base + y * img.getWidth() + x), indexMask, indexBits, alphaMax);
    }

    /**
     * @param img the image
     * @param palette the palette
     * @param y row
     * @param x column
     * @param b the texel byte
     * @param indexMask palette-index mask
     * @param indexBits bits of the palette index
     * @param alphaMax maximum alpha field
     * @see "GBATEK DS 3D Video, A3I5 and A5I3"
     */
    private void paintAlpha(BufferedImage img, Palette palette, int y, int x, int b, int indexMask, int indexBits,
            int alphaMax)
    {
        int rgb = paletteColor(palette, b & indexMask, false) & 0xFFFFFF;
        int a = (((b >> indexBits) & alphaMax) * 255) / alphaMax;
        img.setRGB(x, y, (a << 24) | rgb);
    }

    private void decodeDirect(BufferedImage img, Texture texture)
    {
        pixels = MemBuf.create(tex0).reader();
        int w = img.getWidth(), h = img.getHeight();
        int base = texDataOfs + texture.getDataOffset();
        for (int y = 0; y < h; y++)
        {
            for (int x = 0; x < w; x++)
            {
                int off = base + (y * w + x) * 2;
                int value = texShort(off);
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
        pixels = MemBuf.create(tex0).reader();
        int texelBase = tex4x4DataOfs + texture.getDataOffset();
        int idxBase = tex4x4PlttIdxOfs + texture.getDataOffset() / 2;
        int blocksWide = w / 4;

        for (int by = 0; by < h / 4; by++)
            decodeCompRow(img, palette, by, blocksWide, texelBase, idxBase);
    }

    /**
     * @param img the image
     * @param palette the palette
     * @param by block row
     * @param blocksWide blocks across
     * @param texelBase first texel byte
     * @param idxBase first palette-index byte
     * @see "GBATEK DS 3D Video, 4x4 compressed textures"
     */
    private void decodeCompRow(BufferedImage img, Palette palette, int by, int blocksWide, int texelBase, int idxBase)
    {
        for (int bx = 0; bx < blocksWide; bx++)
            decodeCompBlock(img, palette, by, bx, blocksWide, texelBase, idxBase);
    }

    /**
     * @param img the image
     * @param palette the palette
     * @param by block row
     * @param bx block column
     * @param blocksWide blocks across
     * @param texelBase first texel byte
     * @param idxBase first palette-index byte
     * @see "GBATEK DS 3D Video, 4x4 compressed textures"
     */
    private void decodeCompBlock(BufferedImage img, Palette palette, int by, int bx, int blocksWide, int texelBase,
            int idxBase)
    {
        int blockNum = by * blocksWide + bx;
        int control = texShort(idxBase + blockNum * 2);
        int paletteBase = (control & 0x3FFF) << 1;
        int mode = (control >> 14) & 3;
        for (int ty = 0; ty < 4; ty++)
            decodeCompTexels(img, palette, bx, by, ty, texByte(texelBase + blockNum * 4 + ty), paletteBase, mode);
    }

    /**
     * @param img the image
     * @param palette the palette
     * @param bx block column
     * @param by block row
     * @param ty row inside the block
     * @param row the four 2-bit indices
     * @param paletteBase palette index of color 0
     * @param mode interpolation mode
     * @see "GBATEK DS 3D Video, 4x4 compressed textures"
     */
    private void decodeCompTexels(BufferedImage img, Palette palette, int bx, int by, int ty, int row, int paletteBase,
            int mode)
    {
        for (int tx = 0; tx < 4; tx++)
            img.setRGB(bx * 4 + tx, by * 4 + ty, comp4x4Color(palette, paletteBase, (row >> (tx * 2)) & 3, mode));
    }

    private int comp4x4Color(Palette palette, int paletteBase, int index, int mode)
    {
        // Colors are read relative to the palette's own base; the block adds its paletteBase on top.
        int c0 = colorValue(palette, paletteBase + 0);
        int c1 = colorValue(palette, paletteBase + 1);
        switch (mode)
        {
            case 0:
                return index == 3 ? 0 : opaque(colorValue(palette, paletteBase + index));
            case 1:
                if (index == 0 || index == 1)
                    return opaque(colorValue(palette, paletteBase + index));
                if (index == 2)
                    return opaque(blend(c0, c1, 1, 1, 2));
                return 0;
            case 2:
                return opaque(colorValue(palette, paletteBase + index));
            default: // mode 3
                if (index == 0 || index == 1)
                    return opaque(colorValue(palette, paletteBase + index));
                if (index == 2)
                    return opaque(blend(c0, c1, 5, 3, 8));
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

        return storedColor(palette, index);
    }

    /**
     * @param palette a palette
     * @param index color index
     * @return the color, or zero when the entry is outside the block
     * @see "GBATEK DS 3D Video, BGR555"
     */
    private int storedColor(Palette palette, int index)
    {
        int abs = plttDataOfs + palette.dataOffset + index * 2;
        if (paletteOutOfRange(abs))
            return 0;

        return bgr555(paletteWord(abs));
    }

    /**
     * @param abs byte index of a palette color
     * @return the little-endian halfword
     * @see "GBATEK DS 3D Video, BGR555"
     */
    private int paletteWord(int abs)
    {
        if (pixels != null)
            return texShort(abs);

        return Fx.uint16(tex0, abs);
    }

    /**
     * @param offset index in the TEX0 block
     * @return that byte
     * @see "GBATEK DS 3D Video, texture formats"
     */
    private int texByte(int offset)
    {
        // The image reader was positioned by the decode that owns this fetch.
        if (offset < 0)
            return 0;

        pixels.setPosition(offset);

        return pixels.readByte();
    }

    /**
     * @param offset index in the TEX0 block
     * @return the little-endian halfword there
     * @see "GBATEK DS 3D Video, texture formats"
     */
    private int texShort(int offset)
    {
        // Palette entries and direct-color texels are 16 bits.
        int at = offset;
        pixels.setPosition(at);
        int value = pixels.readUInt16();

        return value;
    }

    /**
     * Reads a palette entry as {@code 0xRRGGBB}. @param palette a palette from this set @param index the
     * color index @return the color as 24-bit RGB
     */
    public int getPaletteColor(Palette palette, int index)
    {
        return colorValue(palette, index) & 0xFFFFFF;
    }

    /**
     * Recolors a palette entry in place &mdash; the writer-side edit that "repaints" a texture. The
     * {@code 0xRRGGBB} color is quantised to the DS's 15-bit {@code BGR555} and written straight into the
     * live {@code TEX0} block, so it shows immediately in a re-decoded {@link #getImage} and is emitted by
     * the owning container's {@code save()} (this {@link TextureSet} for a standalone NSBTX, or the
     * {@link ModelSet} whose embedded {@code TEX0} this views). The edit is same-size, so an unedited file
     * still round-trips byte-for-byte.
     * @param palette a palette from this set
     * @param index the color index to change
     * @param rgb the new color as {@code 0xRRGGBB}
     */
    public void setPaletteColor(Palette palette, int index, int rgb)
    {
        int abs = plttDataOfs + palette.dataOffset + index * 2;
        if (paletteOutOfRange(abs))
            return;
        int r = ((rgb >> 16) & 0xFF) >> 3;
        int g = ((rgb >> 8) & 0xFF) >> 3;
        int b = (rgb & 0xFF) >> 3;
        int v = r | (g << 5) | (b << 10);

        // Two bytes through MemBuf, then back into the live block.
        MemBuf buf = MemBuf.create();
        buf.writer().writeShort((short) v);
        System.arraycopy(buf.reader().getBuffer(), 0, tex0, abs, 2);
    }

    /**
     * @param abs byte index of a palette color
     * @return true when that color is outside the TEX0 block
     * @see "GBATEK DS 3D Video, BGR555"
     */
    private boolean paletteOutOfRange(int abs)
    {
        if (abs < 0)
            return true;

        return abs + 1 >= tex0.length;
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
        public String getName()
        {
            return name;
        }
        /** @return this texture's width in texels */
        public int getWidth()
        {
            return 8 << ((int) (texImageParam >> 20) & 7);
        }
        /** @return this texture's height in texels */
        public int getHeight()
        {
            return 8 << ((int) (texImageParam >> 23) & 7);
        }
        /** @return this texture's NNS format id (1-7) */
        public int getFormat()
        {
            return (int) (texImageParam >> 26) & 7;
        }
        /** @return whether palette color 0 is treated as transparent */
        public boolean isColor0Transparent()
        {
            return ((texImageParam >> 29) & 1) != 0;
        }
        /** @return the byte offset of this texture's data within its texel region */
        public int getDataOffset()
        {
            return (int) (texImageParam & 0xFFFF) << 3;
        }

        @Override
        public String toString()
        {
            return String.format("%s (%dx%d fmt%d)", name, getWidth(), getHeight(), getFormat());
        }
    }

    /** A named palette: a start offset into the archive's shared color data. */
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
        public String getName()
        {
            return name;
        }

        @Override
        public String toString()
        {
            return name;
        }
    }
}
