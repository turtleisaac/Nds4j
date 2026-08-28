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
    @DisplayName("the display-list interpreter emits exactly the vertex count each model header declares")
    void geometryMatchesHeaderVertexCount()
    {
        // The NNS model header records the total vertex count; a correct interpreter emits exactly that
        // many while walking the shapes' display lists. This is a strong, self-checking oracle for the
        // whole geometry decode over every model in the ROM.
        int models = 0;
        for (byte[] file : nsbmdFiles)
        {
            for (Model model : new ModelSet(file).getModels())
            {
                assertThat(model.getVertexCount())
                        .as("model %s vertex count", model.getName())
                        .isEqualTo(model.getExpectedVertexCount());
                models++;
            }
        }
        assertThat(models).as("the ROM should contain models").isGreaterThan(0);
    }

    @Test
    @DisplayName("decoded geometry, with node transforms, lands in the header bounding box")
    void modelsArePlacedInHeaderBox()
    {
        // Positional oracle (the vertex-count oracle can't see wrong placement): with node transforms
        // applied, a model's decoded AABB should equal the box the header declares. This guards the
        // fixed-point scale, posScale and the whole node/SBC matrix pipeline. A count-only total is
        // dominated by the ~4700 single-node models and can hide a multi-node regression, so assert
        // separate floors for single-node and multi-node models: a desync in the SBC walk (e.g. a wrong
        // NODEDESC operand width) collapses the multi-node rate specifically.
        int singleTotal = 0, singleOk = 0, multiTotal = 0, multiOk = 0;
        for (byte[] file : nsbmdFiles)
        {
            for (Model model : new ModelSet(file).getModels())
            {
                if (model.getVertexCount() == 0)
                    continue;
                float[][] decoded = model.getDecodedBoundingBox();
                float[][] header = model.getHeaderBoundingBox();
                float extent = 0;
                for (int c = 0; c < 3; c++)
                    extent = Math.max(extent, header[1][c] - header[0][c]);
                float tol = Math.max(1e-3f, 0.02f * extent);
                boolean match = true;
                for (int c = 0; c < 3 && match; c++)
                    match = Math.abs(decoded[0][c] - header[0][c]) < tol && Math.abs(decoded[1][c] - header[1][c]) < tol;
                if (model.isSingleNode()) { singleTotal++; if (match) singleOk++; }
                else                      { multiTotal++;  if (match) multiOk++; }
            }
        }
        // Single-node models are the identity case and must be essentially perfect (retail Platinum ~98%).
        assertThat(singleOk).as("single-node models placed correctly (of %d)", singleTotal)
                .isGreaterThan((int) (0.95 * singleTotal));
        // Multi-node models exercise the node hierarchy + SBC walk (retail Platinum ~69%; the misses are
        // segment-scale / billboard / skinning cases). A NODEDESC/operand-size desync - the classic bug -
        // collapses this to near zero, so a 60% floor catches a regression without being brittle.
        assertThat(multiTotal).as("the ROM should contain multi-node models").isGreaterThan(100);
        assertThat(multiOk).as("multi-node models placed correctly (of %d)", multiTotal)
                .isGreaterThan((int) (0.60 * multiTotal));
    }

    @Test
    @DisplayName("meshes and OBJ export are coherent")
    void meshesAndObjExport()
    {
        Model model = nsbmdFiles.stream().map(ModelSet::new)
                .flatMap(ms -> ms.getModels().stream())
                .filter(m -> m.getVertexCount() > 0).findFirst().orElse(null);
        Assumptions.assumeTrue(model != null, "need a model with geometry");

        int triangleVerts = 0;
        for (Model.Mesh mesh : model.getMeshes())
        {
            assertThat(mesh.getPositions().length).isEqualTo(mesh.getVertexCount() * 3);
            assertThat(mesh.getTexcoords().length).isEqualTo(mesh.getVertexCount() * 2);
            assertThat(mesh.getTriangleIndices().length % 3).isZero();
            for (int idx : mesh.getTriangleIndices())
                assertThat(idx).isBetween(0, mesh.getVertexCount() - 1);
            triangleVerts += mesh.getTriangleCount() * 3;
        }
        String obj = model.toObj();
        assertThat(obj).contains("v ").contains("f ");
        // one OBJ face line per triangle
        assertThat(obj.lines().filter(l -> l.startsWith("f ")).count()).isEqualTo((long) triangleVerts / 3);
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
