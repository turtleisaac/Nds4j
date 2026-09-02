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

import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * An object representation of an <b>NTFT</b> file &mdash; a raw, headerless 8bpp indexed bitmap: nothing
 * but one palette-index byte per pixel, in plain row-major (linear, <em>not</em> tiled) order, no magic,
 * no dimension fields at all. Every retail NTFT is square, so its side length is simply the square root
 * of the file size (128&times;128, 64&times;64, or 32&times;32 &mdash; the three sizes found in the one
 * confirmed retail source). Pairs with a {@link RawPalette} (NTFP) to resolve to real colors; palette
 * index 0 renders transparent, matching the DS sprite convention used throughout {@code images.*}.
 * <p>
 * Confirmed against <i>Learn with Pok&eacute;mon: Typing Adventure</i> (JP: <i>Battle &amp; Get! Pok&eacute;mon
 * Typing DS</i>), which carries one NTFT/NTFP pair per Pok&eacute;mon "note" icon (7979 pairs). Decoding one as
 * a linear 128&times;128 bitmap reproduces the icon's artwork exactly (a tiled interpretation, the other
 * natural guess, produces scrambled diagonal noise instead &mdash; ruling it out).
 */
public class RawTexture
{
    private int[][] pixels; // palette indices, [row][col]
    private int width;
    private int height;

    /**
     * Parses an NTFT file.
     * @param data a <code>byte[]</code> representation of an NTFT file; its length must be a perfect square
     */
    public RawTexture(byte[] data)
    {
        int side = (int) Math.round(Math.sqrt(data.length));
        if (side * side != data.length)
            throw new RuntimeException("NTFT data length (" + data.length + ") is not a perfect square.");

        width = side;
        height = side;
        pixels = new int[height][width];
        int idx = 0;
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                pixels[y][x] = data[idx++] & 0xFF;
    }

    /**
     * Creates a square texture from raw palette-index pixels.
     * @param pixels a square <code>int[][]</code> of palette indices, <code>pixels[row][col]</code>, each 0-255
     */
    public RawTexture(int[][] pixels)
    {
        this.height = pixels.length;
        this.width = height == 0 ? 0 : pixels[0].length;
        if (width != height)
            throw new RuntimeException("RawTexture must be square, was " + width + "x" + height + ".");
        this.pixels = new int[height][];
        for (int y = 0; y < height; y++)
            this.pixels[y] = Arrays.copyOf(pixels[y], width);
    }

    /**
     * Generate a <code>byte[]</code> representation of this <code>RawTexture</code> as an NTFT.
     * @return a <code>byte[]</code>
     */
    public byte[] save()
    {
        byte[] out = new byte[width * height];
        int idx = 0;
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                out[idx++] = (byte) pixels[y][x];
        return out;
    }

    /** @return the side length in pixels (NTFT is always square) */
    public int getWidth() { return width; }
    /** @return the side length in pixels (NTFT is always square) */
    public int getHeight() { return height; }

    /**
     * @param x the column
     * @param y the row
     * @return the palette index (0-255) at that pixel
     */
    public int getPixelValue(int x, int y)
    {
        return pixels[y][x];
    }

    /**
     * @param x the column
     * @param y the row
     * @param colorIdx the new palette index (0-255) for that pixel
     */
    public void setPixelValue(int x, int y, int colorIdx)
    {
        pixels[y][x] = colorIdx;
    }

    /**
     * @return the palette indices, <code>[row][col]</code>
     */
    public int[][] getPixels()
    {
        int[][] copy = new int[height][];
        for (int y = 0; y < height; y++)
            copy[y] = Arrays.copyOf(pixels[y], width);
        return copy;
    }

    /**
     * Decodes this texture through a palette to an opaque image.
     * @param palette a {@link RawPalette} with at least as many colors as this texture's highest index
     * @return a <code>BufferedImage</code>
     */
    public BufferedImage getImage(RawPalette palette)
    {
        return render(palette, false);
    }

    /**
     * Same as {@link #getImage(RawPalette)}, but with palette index 0 rendered fully transparent, matching
     * the DS sprite convention used throughout {@code images.*}.
     * @param palette a {@link RawPalette}
     * @return a <code>BufferedImage</code> with an alpha channel
     */
    public BufferedImage getTransparentImage(RawPalette palette)
    {
        return render(palette, true);
    }

    private BufferedImage render(RawPalette palette, boolean transparent)
    {
        BufferedImage img = new BufferedImage(width, height,
                transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
            {
                int idx = pixels[y][x];
                if (transparent && idx == 0)
                {
                    img.setRGB(x, y, 0);
                    continue;
                }
                int rgb = idx < palette.getNumColors() ? palette.getColor(idx).getRGB() : 0xFFFF00FF;
                img.setRGB(x, y, 0xFF000000 | rgb);
            }
        return img;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        RawTexture that = (RawTexture) o;
        return width == that.width && height == that.height && Arrays.deepEquals(pixels, that.pixels);
    }

    @Override
    public int hashCode()
    {
        return 31 * (31 * width + height) + Arrays.deepHashCode(pixels);
    }

    @Override
    public String toString()
    {
        return String.format("RawTexture[%dx%d]", width, height);
    }
}
