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

package io.github.turtleisaac.nds4j.text;

import io.github.turtleisaac.nds4j.Narc;
import io.github.turtleisaac.nds4j.NintendoDsRom;
import io.github.turtleisaac.nds4j.framework.NitroLz;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Harvests every embedded BMG ({@code "MESGbmg1"}) file from a retail ROM, walking both loose top-level
 * ROM files and every NARC (recursively, since a BMG can sit inside a NARC-in-NARC). Mirrors {@code
 * io.github.turtleisaac.nds4j.images.NtrFixtures}, but BMG's 8-byte magic isn't a 4-byte reversed Nitro
 * magic, so it can't reuse that collector directly.
 */
final class BmgFixtures
{
    private BmgFixtures() {}

    private static final byte[] BMG_MAGIC = "MESGbmg1".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_DEPTH = 4;

    private static String magic4(byte[] d)
    {
        return d == null || d.length < 4 ? "" : new String(d, 0, 4, StandardCharsets.ISO_8859_1);
    }

    private static boolean isBmg(byte[] d)
    {
        if (d == null || d.length < BMG_MAGIC.length) return false;
        for (int i = 0; i < BMG_MAGIC.length; i++)
            if (d[i] != BMG_MAGIC[i]) return false;
        return true;
    }

    private static byte[] decompressIfNeeded(byte[] data)
    {
        try
        {
            if (NitroLz.isCompressed(data))
                return NitroLz.decompress(data);
        }
        catch (RuntimeException ignored) { }
        return data;
    }

    static List<byte[]> collect(NintendoDsRom rom)
    {
        List<byte[]> found = new ArrayList<>();
        for (int i = 0; i < rom.getNumFiles(); i++)
            scan(rom.getFile(i), 0, found);
        return found;
    }

    private static void scan(byte[] raw, int depth, List<byte[]> found)
    {
        if (raw == null || raw.length < 8) return;
        byte[] data = decompressIfNeeded(raw);
        if (isBmg(data))
        {
            found.add(data);
            return;
        }
        if (!magic4(data).equals("NARC") || depth >= MAX_DEPTH) return;
        Narc narc;
        try { narc = new Narc(data); }
        catch (RuntimeException e) { return; }
        for (int j = 0; j < narc.getNumFiles(); j++)
            scan(narc.getFile(j), depth + 1, found);
    }
}
