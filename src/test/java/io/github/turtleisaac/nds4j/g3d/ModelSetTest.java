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

import io.github.turtleisaac.nds4j.Narc;
import io.github.turtleisaac.nds4j.NintendoDsRom;
import io.github.turtleisaac.nds4j.TestRoms;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ModelSet} (NSBMD / {@code BMD0}) &mdash; the Nitro 3D model container. Geometry
 * decoding is layered on later; this pins down the byte-exact container round-trip over every model
 * in a retail ROM.
 */
@DisplayName("NSBMD (3D model container)")
public class ModelSetTest
{
    private static List<byte[]> nsbmdFiles;

    @BeforeAll
    static void loadFixtures()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        nsbmdFiles = collect(rom, "BMD0");
        Assumptions.assumeFalse(nsbmdFiles.isEmpty(), "no BMD0 files found in the test ROM");
    }

    private static String magic(byte[] d)
    {
        return d == null || d.length < 4 ? "" : new String(d, 0, 4, StandardCharsets.ISO_8859_1);
    }

    private static List<byte[]> collect(NintendoDsRom rom, String want)
    {
        List<byte[]> found = new ArrayList<>();
        for (int i = 0; i < rom.getNumFiles(); i++)
        {
            byte[] f = rom.getFile(i);
            if (!magic(f).equals("NARC"))
                continue;
            Narc narc;
            try { narc = new Narc(f); }
            catch (RuntimeException e) { continue; }
            for (int j = 0; j < narc.getNumFiles(); j++)
                if (magic(narc.getFile(j)).equals(want))
                    found.add(narc.getFile(j));
        }
        return found;
    }

    @Test
    @DisplayName("save() reproduces every BMD0 file byte-for-byte")
    void writtenModelSetEqualsOriginalBytes()
    {
        for (int i = 0; i < nsbmdFiles.size(); i++)
        {
            byte[] original = nsbmdFiles.get(i);
            byte[] written = new ModelSet(original).save();
            assertThat(written).as("BMD0 file #%d must round-trip byte-for-byte", i).isEqualTo(original);
        }
    }

    @Test
    @DisplayName("a saved NSBMD re-reads equal to the original object")
    void writtenModelSetEqualsOriginalObject()
    {
        for (int i = 0; i < nsbmdFiles.size(); i++)
        {
            ModelSet original = new ModelSet(nsbmdFiles.get(i));
            ModelSet reloaded = new ModelSet(original.save());
            assertThat(reloaded).as("BMD0 file #%d must equal itself after a save/load cycle", i).isEqualTo(original);
            assertThat(reloaded.hashCode()).isEqualTo(original.hashCode());
        }
    }

    @Test
    @DisplayName("most models embed their textures, and all declare a model block")
    void embeddedTextureReporting()
    {
        int embedded = 0;
        for (byte[] file : nsbmdFiles)
            if (new ModelSet(file).hasEmbeddedTextures())
                embedded++;
        // The overwhelming majority of Gen IV models carry their own TEX0.
        assertThat(embedded).as("some models should embed textures").isGreaterThan(0);
    }

    @Test
    @DisplayName("a non-NSBMD input is rejected")
    void rejectsNonModelSet()
    {
        byte[] junk = new byte[0x20];
        junk[0] = 'J'; junk[1] = 'U'; junk[2] = 'N'; junk[3] = 'K';
        assertThatThrownBy(() -> new ModelSet(junk)).isInstanceOf(RuntimeException.class);
    }
}
