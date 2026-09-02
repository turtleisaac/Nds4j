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

import java.awt.Color;
import java.util.Arrays;
import java.util.Objects;

/**
 * An object representation of an <b>NTFP</b> file &mdash; a raw, headerless palette: nothing but a flat
 * array of 16-bit BGR555 colors, no magic, no length field, no section structure at all. Unlike NCLR
 * ({@link Palette}), which wraps the same BGR555 data in a full {@code RLCN}/{@code TTLP} container,
 * NTFP is just the color bytes themselves &mdash; its length in bytes divided by two <em>is</em> the
 * color count. Retail files are tightly packed to however many colors are actually used (not padded to
 * 16 or 256), so sizes vary continuously.
 * <p>
 * Confirmed against <i>Learn with Pok&eacute;mon: Typing Adventure</i> (JP: <i>Battle &amp; Get! Pok&eacute;mon
 * Typing DS</i>), which pairs one NTFP with one {@link RawTexture} (NTFT) per Pok&eacute;mon "note" icon.
 * NTFT/NTFP are part of the generic Nitro toolkit (not specific to that title) but had no confirmed
 * retail example anywhere until this one.
 */
public class RawPalette
{
    private Color[] colors;

    /**
     * Parses an NTFP file.
     * @param data a <code>byte[]</code> representation of an NTFP file (must have even length)
     */
    public RawPalette(byte[] data)
    {
        if (data.length % 2 != 0)
            throw new RuntimeException("NTFP data length (" + data.length + ") must be a multiple of 2.");

        colors = new Color[data.length / 2];
        for (int i = 0; i < colors.length; i++)
        {
            int lo = data[i * 2] & 0xFF, hi = data[i * 2 + 1] & 0xFF;
            int bgr = (hi << 8) | lo;
            int r = expand5(bgr & 0x1F);
            int g = expand5((bgr >> 5) & 0x1F);
            int b = expand5((bgr >> 10) & 0x1F);
            colors[i] = new Color(r, g, b);
        }
    }

    // 5-bit BGR555 <-> 8-bit RGB via bit replication ((c<<3)|(c>>2)), the exact DS hardware expansion --
    // unlike a plain "<<3" shift, this reaches the full 0-255 range (31 -> 255, not 248), so a color that
    // came from (or is quantized to) a 5-bit channel round-trips exactly.
    private static int expand5(int c5) { return (c5 << 3) | (c5 >> 2); }

    /**
     * Creates a palette from a given <code>Color[]</code>. Unlike {@link Palette}, there's no 16/256-color
     * padding or multiple-of-16 requirement &mdash; any length is valid.
     * @param colors a <code>Color[]</code>
     */
    public RawPalette(Color[] colors)
    {
        this.colors = colors.clone();
    }

    /**
     * Generate a <code>byte[]</code> representation of this <code>RawPalette</code> as an NTFP.
     * @return a <code>byte[]</code>
     */
    public byte[] save()
    {
        byte[] out = new byte[colors.length * 2];
        for (int i = 0; i < colors.length; i++)
        {
            Color c = colors[i];
            int bgr = (c.getRed() / 8) | ((c.getGreen() / 8) << 5) | ((c.getBlue() / 8) << 10);
            out[i * 2] = (byte) (bgr & 0xFF);
            out[i * 2 + 1] = (byte) ((bgr >> 8) & 0xFF);
        }
        return out;
    }

    /**
     * @return the number of colors in this palette
     */
    public int getNumColors()
    {
        return colors.length;
    }

    /**
     * @param index the color index
     * @return the <code>Color</code> at that index
     */
    public Color getColor(int index)
    {
        return colors[index];
    }

    /**
     * @param index the color index
     * @param color the new <code>Color</code> for that index
     */
    public void setColor(int index, Color color)
    {
        colors[index] = color;
    }

    /**
     * @return every color in this palette, in order
     */
    public Color[] getColors()
    {
        return colors.clone();
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        RawPalette that = (RawPalette) o;
        return Arrays.equals(colors, that.colors);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash((Object[]) colors);
    }

    @Override
    public String toString()
    {
        return String.format("RawPalette[%d colors]", colors.length);
    }
}
