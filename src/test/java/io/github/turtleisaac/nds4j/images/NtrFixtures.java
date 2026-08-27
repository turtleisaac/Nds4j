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

import io.github.turtleisaac.nds4j.Narc;
import io.github.turtleisaac.nds4j.NintendoDsRom;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Harvests every embedded file of a given NTR magic (e.g. {@code "RNAN"}, {@code "RCSN"}) from a
 * retail ROM, so the image-format tests can exercise their reader/writer against thousands of real
 * files rather than a single hand-picked sample. Files are located by walking every NARC in the ROM
 * and matching the four-byte magic, which keeps the tests independent of any particular game's
 * directory layout.
 */
final class NtrFixtures
{
    private NtrFixtures() {}

    static String magic(byte[] data)
    {
        if (data == null || data.length < 4)
            return "";
        return new String(data, 0, 4, StandardCharsets.ISO_8859_1);
    }

    /**
     * @param rom the ROM to scan
     * @param wantedMagic the four-byte NTR magic to collect (as stored, e.g. {@code "RNAN"})
     * @return every embedded file with that magic, in scan order
     */
    static List<byte[]> collect(NintendoDsRom rom, String wantedMagic)
    {
        List<byte[]> found = new ArrayList<>();
        for (int i = 0; i < rom.getNumFiles(); i++)
        {
            byte[] file = rom.getFile(i);
            if (!magic(file).equals("NARC"))
                continue;
            Narc narc;
            try
            {
                narc = new Narc(file);
            }
            catch (RuntimeException e)
            {
                continue; // not every "NARC"-tagged file parses; skip rather than fail the scan
            }
            for (int j = 0; j < narc.getNumFiles(); j++)
            {
                byte[] sub = narc.getFile(j);
                if (magic(sub).equals(wantedMagic))
                    found.add(sub);
            }
        }
        return found;
    }
}
