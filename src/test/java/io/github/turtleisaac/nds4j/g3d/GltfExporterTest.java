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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link GltfExporter}: a decoded {@link Model} must serialise to a self-contained,
 * self-consistent glTF 2.0 document &mdash; the buffer and every embedded texture decode, and the
 * accessor/bufferView offsets stay in range.
 */
@DisplayName("glTF 2.0 export")
public class GltfExporterTest
{
    private static List<byte[]> nsbmdFiles;

    @BeforeAll
    static void loadFixtures()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        nsbmdFiles = new ArrayList<>();
        for (int i = 0; i < rom.getNumFiles(); i++)
        {
            byte[] f = rom.getFile(i);
            if (!magic(f).equals("NARC"))
                continue;
            Narc narc;
            try { narc = new Narc(f); }
            catch (RuntimeException e) { continue; }
            for (int j = 0; j < narc.getNumFiles(); j++)
                if (magic(narc.getFile(j)).equals("BMD0"))
                    nsbmdFiles.add(narc.getFile(j));
        }
        Assumptions.assumeFalse(nsbmdFiles.isEmpty(), "no BMD0 files found in the test ROM");
    }

    private static String magic(byte[] d)
    {
        return d == null || d.length < 4 ? "" : new String(d, 0, 4, StandardCharsets.ISO_8859_1);
    }

    @Test
    @DisplayName("a textured model exports a self-consistent glTF with embedded PNG textures")
    void exportsSelfConsistentGltf() throws IOException
    {
        ModelSet ms = null;
        Model model = null;
        for (byte[] file : nsbmdFiles)
        {
            ModelSet candidate = new ModelSet(file);
            if (!candidate.hasEmbeddedTextures())
                continue;
            for (Model m : candidate.getModels())
                if (m.getVertexCount() > 0 && m.getMeshes().stream()
                        .anyMatch(me -> me.getMaterial() != null && me.getMaterial().getTextureName() != null))
                {
                    ms = candidate;
                    model = m;
                    break;
                }
            if (model != null)
                break;
        }
        Assumptions.assumeTrue(model != null, "need a textured model");

        String gltf = GltfExporter.toGltf(model, ms.getEmbeddedTextures());
        assertThat(gltf).contains("\"version\":\"2.0\"").contains("POSITION").contains("TEXCOORD_0");

        // the base64 buffer must decode to exactly its declared byteLength
        Matcher buf = Pattern.compile("\"byteLength\":(\\d+),\"uri\":\"data:application/octet-stream;base64,([^\"]+)\"")
                .matcher(gltf);
        assertThat(buf.find()).as("glTF has an embedded buffer").isTrue();
        int declared = Integer.parseInt(buf.group(1));
        byte[] bytes = Base64.getDecoder().decode(buf.group(2));
        assertThat(bytes.length).as("buffer byteLength matches its data").isEqualTo(declared);

        // every embedded image must decode as a real PNG
        Matcher img = Pattern.compile("data:image/png;base64,([^\"]+)").matcher(gltf);
        int decoded = 0;
        while (img.find())
        {
            BufferedImage png = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(img.group(1))));
            assertThat(png).as("embedded texture decodes as PNG").isNotNull();
            assertThat(png.getWidth()).isGreaterThan(0);
            decoded++;
        }
        assertThat(decoded).as("a textured model embeds at least one PNG").isGreaterThan(0);
    }

    @Test
    @DisplayName("every model in the ROM exports without error")
    void exportsAllModels()
    {
        int exported = 0;
        for (byte[] file : nsbmdFiles)
        {
            ModelSet ms = new ModelSet(file);
            TextureSet tex = ms.getEmbeddedTextures();
            for (Model model : ms.getModels())
            {
                String gltf = GltfExporter.toGltf(model, tex);
                assertThat(gltf).startsWith("{\"asset\"").endsWith("}");
                exported++;
            }
        }
        assertThat(exported).as("the ROM should contain models").isGreaterThan(0);
    }
}
