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

package io.github.turtleisaac.nds4j;

import io.github.turtleisaac.nds4j.framework.CRC16;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Nintendo DS ROM <b>icon/title banner</b> &mdash; the 32&times;32 icon and the per-language game title
 * shown on the DS home menu (the {@code iconTitle} region an NDS header points at via offset {@code 0x68}).
 * <p>
 * This wraps the raw banner bytes with a friendly view: the icon decodes to a {@link BufferedImage} (a
 * 16-color, 4bpp, 4&times;4-tiled bitmap over a {@code BGR555} palette, color&nbsp;0 rendered transparent),
 * and each title is UTF-16LE text of up to three {@code '\n'}-separated lines. The banner comes in versions
 * that add languages: {@code 0x0001} carries Japanese, English, French, German, Italian and Spanish;
 * {@code 0x0002} adds Chinese; {@code 0x0003} adds Korean; {@code 0x0103} additionally holds a DSi animated
 * icon (preserved verbatim, not decoded here).
 * <p>
 * The original bytes are retained, so an unedited banner re-serialises byte-for-byte; {@link #setIcon} and
 * {@link #setTitle} patch only what they change, and {@link #toBytes} recomputes the version's {@code CRC16}
 * checksum(s). Reserved/padding regions are preserved.
 */
public class IconBanner
{
    /** The languages a banner can carry, in their stored order. */
    public enum Language
    {
        JAPANESE, ENGLISH, FRENCH, GERMAN, ITALIAN, SPANISH, CHINESE, KOREAN
    }

    private static final int ICON_BITMAP_OFFSET = 0x20;   // 512 bytes: 32x32, 4bpp, 4x4 tiles of 8x8
    private static final int ICON_PALETTE_OFFSET = 0x220; // 32 bytes: 16 colors, BGR555
    private static final int TITLES_OFFSET = 0x240;       // 0x100 bytes per title, one per language
    private static final int TITLE_LENGTH = 0x100;        // 128 UTF-16 code units

    private final byte[] data;
    private final int version;

    /**
     * Wraps an existing banner. The array is copied, so the source is not modified by edits here.
     * @param bannerData the raw banner bytes (as stored in the ROM)
     */
    public IconBanner(byte[] bannerData)
    {
        this.data = bannerData.clone();
        this.version = u16(0);
    }

    /** @return the banner version half-word ({@code 0x0001}/{@code 0x0002}/{@code 0x0003}/{@code 0x0103}). */
    public int getVersion() { return version; }

    /** @return how many language titles this banner carries (6, 7 or 8, by version). */
    public int getLanguageCount() { return version >= 0x0003 ? 8 : version >= 0x0002 ? 7 : 6; }

    // === icon ==========================================================================================

    /**
     * Decodes the 32&times;32 icon to an ARGB image; palette index 0 is rendered fully transparent (as the
     * DS menu draws it).
     * @return a 32&times;32 {@link BufferedImage}
     */
    public BufferedImage getIcon()
    {
        int[] palette = readPalette();
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 32; x++)
            {
                int index = pixelIndex(x, y);
                img.setRGB(x, y, index == 0 ? 0 : (0xFF000000 | palette[index]));
            }
        return img;
    }

    /**
     * Replaces the icon from a 32&times;32 image. Palette index&nbsp;0 is reserved for transparency (as the DS
     * menu always renders it), so fully-transparent pixels map to index&nbsp;0 and the distinct opaque colors
     * fill entries 1&hellip;15.
     * @param image a 32&times;32 image using at most 15 distinct opaque colors (plus transparency)
     * @throws IllegalArgumentException if the image is not 32&times;32 or uses more than 15 opaque colors
     */
    public void setIcon(BufferedImage image)
    {
        if (image.getWidth() != 32 || image.getHeight() != 32)
            throw new IllegalArgumentException("icon must be 32x32, was " + image.getWidth() + "x" + image.getHeight());

        Map<Integer, Integer> colorToIndex = new LinkedHashMap<>();  // opaque RGB -> palette index (1..15)
        int[][] indices = new int[32][32];
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 32; x++)
            {
                int argb = image.getRGB(x, y);
                if ((argb >>> 24) < 0x80) { indices[y][x] = 0; continue; } // transparent -> index 0
                int rgb = argb & 0xFFFFFF;
                Integer idx = colorToIndex.get(rgb);
                if (idx == null)
                {
                    idx = colorToIndex.size() + 1;
                    if (idx > 15)
                        throw new IllegalArgumentException("icon uses more than 15 distinct opaque colors; index 0 is reserved for transparency");
                    colorToIndex.put(rgb, idx);
                }
                indices[y][x] = idx;
            }

        int[] palette = new int[16];   // index 0 (transparent) left black; unused entries stay zero
        for (Map.Entry<Integer, Integer> e : colorToIndex.entrySet()) palette[e.getValue()] = e.getKey();
        writePalette(palette);
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 32; x++)
                setPixelIndex(x, y, indices[y][x]);
    }

    // === titles ========================================================================================

    /**
     * @param language a language the banner carries (see {@link #getLanguageCount()})
     * @return that title as text, its up to three lines separated by {@code '\n'}
     */
    public String getTitle(Language language)
    {
        int i = language.ordinal();
        if (i >= getLanguageCount())
            throw new IllegalArgumentException("banner version " + Integer.toHexString(version) + " has no " + language + " title");
        String s = new String(data, TITLES_OFFSET + i * TITLE_LENGTH, TITLE_LENGTH, StandardCharsets.UTF_16LE);
        int nul = s.indexOf('\0');
        return nul >= 0 ? s.substring(0, nul) : s;
    }

    /** @return the English title (a convenient default). */
    public String getTitle() { return getTitle(Language.ENGLISH); }

    /**
     * Sets a language's title (up to three {@code '\n'}-separated lines). Encoded UTF-16LE and null-padded.
     * @param language the language to set
     * @param title the new title
     * @throws IllegalArgumentException if the language is absent in this version, or the text exceeds 127 units
     */
    public void setTitle(Language language, String title)
    {
        int i = language.ordinal();
        if (i >= getLanguageCount())
            throw new IllegalArgumentException("banner version " + Integer.toHexString(version) + " has no " + language + " title");
        byte[] enc = title.getBytes(StandardCharsets.UTF_16LE);
        if (enc.length > TITLE_LENGTH - 2)
            throw new IllegalArgumentException("title too long: " + (enc.length / 2) + " code units, max 127");
        int base = TITLES_OFFSET + i * TITLE_LENGTH;
        Arrays.fill(data, base, base + TITLE_LENGTH, (byte) 0);
        System.arraycopy(enc, 0, data, base, enc.length);
    }

    // === serialise =====================================================================================

    /**
     * Serialises the banner, recomputing the version's {@code CRC16} checksum(s) over the (possibly edited)
     * data. An unedited banner reproduces its original bytes exactly.
     * @return the banner bytes
     */
    public byte[] toBytes()
    {
        byte[] out = data.clone();
        putU16(out, 0x02, crc16(out, 0x20, 0x840));                 // v1: icon + 6 titles
        if (version >= 0x0002) putU16(out, 0x04, crc16(out, 0x20, 0x940));  // + Chinese
        if (version >= 0x0003) putU16(out, 0x06, crc16(out, 0x20, 0xA40));  // + Korean
        if (version == 0x0103) putU16(out, 0x08, crc16(out, 0x1240, 0x23C0)); // DSi animated icon
        return out;
    }

    @Override
    public String toString()
    {
        return String.format("IconBanner[version=%04x, \"%s\"]", version, getTitle().replace("\n", " "));
    }

    // === internals =====================================================================================

    private int pixelIndex(int x, int y)
    {
        int within = tileByteIndex(x, y);
        int b = data[ICON_BITMAP_OFFSET + within / 2] & 0xFF;
        return (within & 1) == 0 ? (b & 0xF) : (b >> 4);
    }

    private void setPixelIndex(int x, int y, int index)
    {
        int within = tileByteIndex(x, y);
        int p = ICON_BITMAP_OFFSET + within / 2;
        int b = data[p] & 0xFF;
        data[p] = (byte) ((within & 1) == 0 ? (b & 0xF0) | (index & 0xF) : (b & 0x0F) | (index << 4));
    }

    // the pixel's index within the 4bpp bitmap, honouring the 4x4 grid of 8x8 tiles
    private static int tileByteIndex(int x, int y)
    {
        int tile = (y / 8) * 4 + (x / 8);
        return tile * 64 + (y % 8) * 8 + (x % 8);
    }

    private int[] readPalette()
    {
        int[] pal = new int[16];
        for (int i = 0; i < 16; i++) pal[i] = bgr555ToRgb(u16(ICON_PALETTE_OFFSET + i * 2));
        return pal;
    }

    private void writePalette(int[] rgb)
    {
        for (int i = 0; i < 16; i++) putU16(data, ICON_PALETTE_OFFSET + i * 2, rgbToBgr555(rgb[i]));
    }

    // 5-bit BGR555 <-> 8-bit RGB using bit replication ((c<<3)|(c>>2)), so a decode/encode round-trip is
    // exact: an unedited color re-encodes to its original 5-bit value.
    private static int bgr555ToRgb(int v)
    {
        int r = expand5(v & 0x1F), g = expand5((v >> 5) & 0x1F), b = expand5((v >> 10) & 0x1F);
        return (r << 16) | (g << 8) | b;
    }

    private static int rgbToBgr555(int rgb)
    {
        int r = ((rgb >> 16) & 0xFF) >> 3, g = ((rgb >> 8) & 0xFF) >> 3, b = (rgb & 0xFF) >> 3;
        return r | (g << 5) | (b << 10);
    }

    private static int expand5(int c5) { return (c5 << 3) | (c5 >> 2); }

    private static int crc16(byte[] d, int from, int to)
    {
        return CRC16.calculateCrc(Arrays.copyOfRange(d, from, to)) & 0xFFFF;
    }

    private int u16(int o) { return (data[o] & 0xFF) | ((data[o + 1] & 0xFF) << 8); }
    private static void putU16(byte[] d, int o, int v) { d[o] = (byte) v; d[o + 1] = (byte) (v >> 8); }
}
