/*
 * Copyright (c) 2026 Turtleisaac.
 *
 * This file is part of Nds4j.
 *
 * Nds4j is free software: you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version. See <https://www.gnu.org/licenses/>.
 */

package io.github.turtleisaac.nds4j.images;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Headless coverage for {@link IndexedImage#applyImageMatched} / {@link IndexedImage#applyImageQuantized}
 * — the clean PNG-import path that replaced the deprecated 80x80 / 16-colour / JPanel constructor. No
 * ROM or display required.
 */
@DisplayName("IndexedImage headless PNG quantizers")
class QuantizeTest
{
    private static Palette pal16(Color... first)
    {
        Color[] c = new Color[16];
        for (int i = 0; i < 16; i++) c[i] = i < first.length ? first[i] : Color.BLACK;
        return new Palette(c);
    }

    @Test
    @DisplayName("applyImageMatched maps exact palette colours to their indices with zero mismatches")
    void matchedExact()
    {
        // index 0 = transparent slot colour, 1 = red, 2 = green
        Palette pal = pal16(Color.MAGENTA, Color.RED, Color.GREEN);
        IndexedImage img = new IndexedImage(8, 8, 4, pal);

        BufferedImage src = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++)
                src.setRGB(x, y, (x < 4 ? Color.RED : Color.GREEN).getRGB());

        int unmatched = img.applyImageMatched(src);
        assertThat(unmatched).isEqualTo(0);
        assertThat(img.getPixelValue(0, 0)).isEqualTo(1); // red
        assertThat(img.getPixelValue(7, 0)).isEqualTo(2); // green
        // survives re-encode (bit depth / geometry preserved from the constructed image)
        assertThat(img.save()).isNotEmpty();
    }

    @Test
    @DisplayName("applyImageMatched sends transparent pixels to index 0 and counts near-misses")
    void matchedTransparencyAndMiss()
    {
        Palette pal = pal16(Color.MAGENTA, Color.RED);
        IndexedImage img = new IndexedImage(8, 8, 4, pal);
        BufferedImage src = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        src.setRGB(0, 0, 0x00000000);            // fully transparent -> index 0
        src.setRGB(1, 0, new Color(250, 5, 5).getRGB()); // near-red, not exact -> matches red, counts as miss
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++)
                if (!((x == 0 || x == 1) && y == 0)) src.setRGB(x, y, Color.RED.getRGB());

        int unmatched = img.applyImageMatched(src);
        assertThat(img.getPixelValue(0, 0)).isEqualTo(0); // transparent
        assertThat(img.getPixelValue(1, 0)).isEqualTo(1); // nearest = red
        assertThat(unmatched).isEqualTo(1);               // only the near-red pixel missed
    }

    @Test
    @DisplayName("applyImageMatched rejects a size mismatch instead of corrupting pixels")
    void matchedSizeGuard()
    {
        IndexedImage img = new IndexedImage(8, 8, 4, pal16(Color.RED));
        BufferedImage wrong = new BufferedImage(16, 8, BufferedImage.TYPE_INT_ARGB);
        try { img.applyImageMatched(wrong); assertThat(false).isTrue(); }
        catch (RuntimeException e) { assertThat(e.getMessage()).contains("must match"); }
    }

    @Test
    @DisplayName("applyImageQuantized builds a fresh palette (index 0 transparent) and re-encodes")
    void quantizeBuildsPalette()
    {
        IndexedImage img = new IndexedImage(8, 8, 4, pal16()); // all-black start
        BufferedImage src = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        // a handful of distinct opaque colours + a transparent corner
        Color[] hues = { Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW };
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++)
                src.setRGB(x, y, hues[(x / 2) % hues.length].getRGB());
        src.setRGB(0, 0, 0x00000000); // transparent

        Palette built = img.applyImageQuantized(src, 16);
        assertThat(built.getColors()).hasSize(16);
        assertThat(img.getPixelValue(0, 0)).isEqualTo(0);            // transparent -> 0
        assertThat(img.getPixelValue(2, 0)).isGreaterThan(0);        // an opaque hue -> non-zero slot
        // distinct hues should not all collapse to one index
        assertThat(img.getPixelValue(0, 2)).isNotEqualTo(img.getPixelValue(6, 2));
        assertThat(img.save()).isNotEmpty();
    }
}
