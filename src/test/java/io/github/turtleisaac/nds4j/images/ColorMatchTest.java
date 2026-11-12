/*
 * Copyright (c) 2026 Turtleisaac.
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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ColorMatch nearest-color lookup")
public class ColorMatchTest
{
    private static final Color GREY = new Color(80, 80, 80);
    private static final Color[] DUPES = { Color.BLACK, Color.RED, GREY, Color.GREEN, GREY, Color.BLUE };

    @Test
    @DisplayName("distanceSq sums the squared channel differences")
    void distance()
    {
        assertThat(ColorMatch.distanceSq(new Color(10, 20, 30), 0xFF0D1A28))
                .isEqualTo(9 + 36 + 100);
        assertThat(ColorMatch.distanceSq(GREY, GREY.getRGB()))
                .isEqualTo(0);
    }

    @Test
    @DisplayName("a tie goes to the first index when the preferred index is not one of the nearest")
    void tieWithoutPreference()
    {
        assertThat(ColorMatch.nearestIndex(DUPES, 0, DUPES.length, GREY.getRGB(), 1))
                .isEqualTo(2);
        assertThat(ColorMatch.nearestIndex(DUPES, 0, DUPES.length, GREY.getRGB(), -1))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a tie keeps the preferred index, earlier or later")
    void tieKeepsPreferred()
    {
        assertThat(ColorMatch.nearestIndex(DUPES, 0, DUPES.length, GREY.getRGB(), 4))
                .isEqualTo(4);
        assertThat(ColorMatch.nearestIndex(DUPES, 0, DUPES.length, GREY.getRGB(), 2))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a strictly nearer color beats the preferred index")
    void nearerBeatsPreferred()
    {
        assertThat(ColorMatch.nearestIndex(DUPES, 0, DUPES.length, new Color(250, 5, 5).getRGB(), 4))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("base and count select a sub-palette and the result is relative to base")
    void subPalette()
    {
        assertThat(ColorMatch.nearestIndex(DUPES, 3, 3, GREY.getRGB(), 0))
                .isEqualTo(1);
        assertThat(ColorMatch.nearestIndex(DUPES, 3, 2, Color.BLUE.getRGB(), 0))
                .as("blue sits past count, so grey is the nearest left")
                .isEqualTo(1);
    }
}
