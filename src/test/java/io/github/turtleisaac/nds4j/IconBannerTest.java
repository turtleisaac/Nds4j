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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link IconBanner} &mdash; the DS ROM icon/title banner view. The CI-safe cases build a synthetic
 * version-1 banner and exercise the icon and title codecs and the {@code CRC16} serialisation; the retail
 * cases (skipped without a ROM: {@code -Drom.dir=<dir>}) require that every real banner re-serialises
 * byte-for-byte and that its icon and titles decode.
 */
@DisplayName("IconBanner (DS icon/title)")
public class IconBannerTest
{
    // a blank version-1 banner (icon + 6 titles), to author into
    private static IconBanner blankV1()
    {
        byte[] data = new byte[0x840];
        data[0] = 0x01; // version 0x0001
        return new IconBanner(data);
    }

    @Test
    @DisplayName("an authored icon and titles decode back to what was set, and the CRC serialises validly")
    void authorAndReadBack()
    {
        IconBanner b = blankV1();

        // a small icon: a red square on a transparent background
        BufferedImage icon = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 8; y < 24; y++) for (int x = 8; x < 24; x++) icon.setRGB(x, y, 0xFFFF0000);
        b.setIcon(icon);
        b.setTitle(IconBanner.Language.ENGLISH, "Nds4j Test\nby Turtleisaac");
        b.setTitle(IconBanner.Language.JAPANESE, "テスト");

        IconBanner reread = new IconBanner(b.toBytes());
        assertThat(reread.getVersion()).isEqualTo(1);
        assertThat(reread.getTitle(IconBanner.Language.ENGLISH)).isEqualTo("Nds4j Test\nby Turtleisaac");
        assertThat(reread.getTitle(IconBanner.Language.JAPANESE)).isEqualTo("テスト");
        assertThat(reread.getTitle()).isEqualTo("Nds4j Test\nby Turtleisaac");

        BufferedImage back = reread.getIcon();
        assertThat(back.getWidth()).isEqualTo(32);
        assertThat(back.getRGB(16, 16) | 0xFF000000).isEqualTo(0xFFFF0000);   // inside the square: red
        assertThat(back.getRGB(0, 0) >>> 24).isZero();                        // outside: transparent

        // the serialised CRC16 matches a recomputation over 0x20..0x840
        byte[] bytes = reread.toBytes();
        int stored = (bytes[2] & 0xFF) | ((bytes[3] & 0xFF) << 8);
        int calc = io.github.turtleisaac.nds4j.framework.CRC16.calculateCrc(java.util.Arrays.copyOfRange(bytes, 0x20, 0x840)) & 0xFFFF;
        assertThat(stored).isEqualTo(calc);
    }

    @Test
    @DisplayName("re-serialising an unedited banner reproduces its bytes exactly (idempotent)")
    void serialiseIsByteExactWhenUnedited()
    {
        IconBanner b = blankV1();
        b.setIcon(solidIcon());
        b.setTitle(IconBanner.Language.ENGLISH, "Title");
        byte[] once = b.toBytes();
        byte[] twice = new IconBanner(once).toBytes();
        assertThat(twice).isEqualTo(once);
    }

    @Test
    @DisplayName("more than 15 distinct opaque icon colours is rejected (index 0 is transparency)")
    void tooManyColoursRejected()
    {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < 16; i++) img.setRGB(i, 0, 0xFF000000 | (i * 0x101010 + 0x010101)); // 16 distinct colours
        try { blankV1().setIcon(img); org.junit.jupiter.api.Assertions.fail("expected rejection"); }
        catch (IllegalArgumentException expected) { assertThat(expected.getMessage()).contains("15"); }
    }

    private static BufferedImage solidIcon()
    {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) img.setRGB(x, y, 0xFF3060A0);
        return img;
    }

    // === retail (ROM-gated) ===========================================================================

    @Test
    @DisplayName("every retail ROM's banner re-serialises byte-for-byte, and its icon/title decode")
    void retailBannerRoundTrip()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        IconBanner b = rom.getBanner();
        Assumptions.assumeTrue(b != null, "ROM has no banner");

        assertThat(b.toBytes()).as("banner re-serialises byte-for-byte").isEqualTo(rom.getIconBanner());
        assertThat(b.getIcon().getWidth()).isEqualTo(32);
        assertThat(b.getIcon().getHeight()).isEqualTo(32);
        assertThat(b.getTitle()).isNotEmpty();

        // re-icon with the decoded image preserves every rendered pixel
        IconBanner b2 = rom.getBanner();
        b2.setIcon(b.getIcon());
        BufferedImage a = b.getIcon(), c = b2.getIcon();
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 32; x++)
                assertThat(c.getRGB(x, y) | 0xFF000000).isEqualTo(a.getRGB(x, y) | 0xFF000000);
    }
}
