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

package io.github.turtleisaac.nds4j.framework;

import java.util.Locale;

public class StringFormatter
{
    public static String formatOutputString(int i, int cnt, String prefix, String suffix)
    {
        // Historically this padded to one digit wider than the digit count of cnt, but only
        // handled cnt < 10000 -- larger counts got no padding at all, which made unpacked
        // subfile names sort lexicographically out of order and scrambled file IDs on repack.
        // Keep the original widths (so existing unpacked projects still resolve) and simply
        // extend the same rule past 9999.
        int width = String.valueOf(Math.max(cnt, 1)).length() + 1;
        // Locale.ROOT, not the default: %d formats with the default locale's digits, so under
        // ar-EG, fa-IR or a Thai-numeral locale this produced Arabic-Indic digits instead of
        // ASCII. Those are filenames - they go on disk, get listed back, and get parsed with
        // Integer.parseInt - so the name a project was unpacked with must not depend on the
        // machine that unpacked it.
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%0" + width + "d", i));
        sb.insert(0, prefix).append(suffix);
        return sb.toString();
    }
}
