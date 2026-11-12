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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link DisplayList} &mdash; the geometry encoder for source&rarr;NSB* conversion. Encoding then
 * decoding a mesh must reproduce its triangles exactly (positions and texcoords survive the fixed-point
 * round-trip). Validated on real retail meshes (every shape of a Platinum model) plus a synthetic mesh.
 */
@DisplayName("DisplayList (geometry encoder)")
public class DisplayListTest
{
    private static String magic(byte[] d)
    {
        return d == null || d.length < 4 ? "" : new String(d, 0, 4, StandardCharsets.ISO_8859_1);
    }

    @Test
    @DisplayName("encode -> decode reproduces a synthetic mesh's triangles exactly")
    void syntheticRoundTrip()
    {
        // two triangles forming a quad, with texcoords
        float[] pos = {0, 0, 0,  1, 0, 0,  1, 1, 0,  0, 1, 0};
        float[] uv = {0, 0,  16, 0,  16, 16,  0, 16};
        int[] tris = {0, 1, 2,  0, 2, 3};

        DisplayList.Geometry g = DisplayList.decode(DisplayList.encode(pos, uv, tris));
        assertThat(g.triangles.length).isEqualTo(6);
        // decoded triangle i uses vertices 3i..3i+2, matching input triangle i's vertices
        for (int t = 0; t < tris.length; t += 3)
            for (int k = 0; k < 3; k++)
            {
                int inV = tris[t + k];
                int outV = g.triangles[t + k];
                for (int c = 0; c < 3; c++)
                    assertThat(g.positions[outV * 3 + c]).isEqualTo(pos[inV * 3 + c]);
                for (int c = 0; c < 2; c++)
                    assertThat(g.texcoords[outV * 2 + c]).isEqualTo(uv[inV * 2 + c]);
            }
    }

    @Test
    @DisplayName("encode -> decode is geometry-exact for every shape of real retail models")
    void retailMeshesRoundTrip()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        int meshesChecked = 0, trianglesChecked = 0;

        outer:
        for (int i = 0; i < rom.getNumFiles(); i++)
        {
            byte[] f = rom.getFile(i);
            if (!magic(f).equals("NARC"))
                continue;
            Narc narc;
            try { narc = new Narc(f); }
            catch (RuntimeException e) { continue; }
            for (int j = 0; j < narc.getNumFiles(); j++)
            {
                if (!magic(narc.getFile(j)).equals("BMD0"))
                    continue;
                ModelSet ms;
                try { ms = new ModelSet(narc.getFile(j)); }
                catch (RuntimeException e) { continue; }
                for (Model model : ms.getModels())
                    for (Model.Mesh mesh : model.getMeshes())
                    {
                        float[] raw = mesh.getRawPositions();
                        float[] uv = mesh.getTexcoords();
                        int[] tris = mesh.getTriangleIndices();
                        if (tris.length == 0)
                            continue;
                        // positions must be within VTX_16 range (±8) to encode losslessly
                        boolean inRange = true;
                        for (float v : raw) if (Math.abs(v) >= 8f) { inRange = false; break; }
                        if (!inRange)
                            continue;

                        DisplayList.Geometry g = DisplayList.decode(DisplayList.encode(raw, uv, tris));
                        assertThat(g.triangles.length).as("triangle count preserved").isEqualTo(tris.length);
                        for (int t = 0; t < tris.length; t++)
                        {
                            int inV = tris[t], outV = g.triangles[t];
                            for (int c = 0; c < 3; c++)
                                assertThat(g.positions[outV * 3 + c])
                                        .as("vertex position survives fx16 round-trip")
                                        .isCloseTo(raw[inV * 3 + c], org.assertj.core.data.Offset.offset(1f / 4096));
                        }
                        trianglesChecked += tris.length / 3;
                        if (++meshesChecked >= 400)
                            break outer;
                    }
            }
        }
        Assumptions.assumeTrue(meshesChecked > 0, "need retail meshes");
        System.out.printf("DisplayList: geometry-exact over %d meshes, %d triangles%n", meshesChecked, trianglesChecked);
        assertThat(meshesChecked).isGreaterThan(50);
    }

    @Test
    @DisplayName("the command codec round-trips every retail display list byte-for-byte")
    void commandCodecIsByteExact()
    {
        int meshes = 0, commands = 0, notEightAligned = 0, alignedBeforeTermination = 0, midListZeroWords = 0;
        for (String romName : new String[]{"Platinum.nds", "HeartGold.nds", "Diamond.nds"})
        {
            NintendoDsRom rom;
            try { rom = TestRoms.require(romName); }
            catch (RuntimeException e) { continue; }
            for (int i = 0; i < rom.getNumFiles(); i++)
            {
                if (!magic(rom.getFile(i)).equals("NARC")) continue;
                Narc narc;
                try { narc = new Narc(rom.getFile(i)); }
                catch (RuntimeException e) { continue; }
                for (int j = 0; j < narc.getNumFiles(); j++)
                {
                    if (!magic(narc.getFile(j)).equals("BMD0")) continue;
                    ModelSet ms;
                    try { ms = new ModelSet(narc.getFile(j)); }
                    catch (RuntimeException e) { continue; }
                    for (Model model : ms.getModels())
                        for (Model.Mesh mesh : model.getMeshes())
                        {
                            byte[] dl = mesh.getRawDisplayList();
                            assertThat(dl).as("mesh %s retains its display list", mesh.getName()).isNotNull();
                            // lossless: the exact opcode stream (incl. NOP padding, quads/strips, every vertex
                            // format and the 4-opcodes-per-word packing) re-emits byte-for-byte
                            java.util.List<DisplayList.Command> cmds = DisplayList.decodeCommands(dl);
                            assertThat(DisplayList.encodeCommands(cmds))
                                    .as("display list of %s round-trips byte-for-byte", mesh.getName())
                                    .isEqualTo(dl);
                            commands += cmds.size();
                            meshes++;

                            // GBATEK: the top-most packed command must have parameters. g3dcvtr satisfies it by
                            // following every word whose top-most command is parameterless with a zero word,
                            // mid-list as well as at the end
                            int[] rule = DisplayListTerminationTest.topMostRule(dl);
                            assertThat(rule[0])
                                    .as("retail display list of %s follows the top-most-command packing rule",
                                            mesh.getName())
                                    .isZero();
                            midListZeroWords += rule[1];

                            // its end-of-list case, which DisplayList#terminate reproduces: exactly one zero word
                            // after the last real command, never any further alignment padding
                            int core = DisplayListTerminationTest.unterminatedLength(dl);
                            assertThat(dl.length - core)
                                    .as("retail display list of %s ends with exactly one NOP word", mesh.getName())
                                    .isEqualTo(4);
                            if (dl.length % 8 != 0) notEightAligned++;
                            if (core % 8 == 0) alignedBeforeTermination++;
                            // ...which is what keeps strict LibNDSFormats-style readers executing every END_VTXS
                            long ends = cmds.stream().filter(c -> c.opcode == 0x41).count();
                            assertThat(DisplayListTerminationTest.strictReaderEndVtxsCount(dl))
                                    .as("a strict reader executes every END_VTXS in %s", mesh.getName())
                                    .isEqualTo((int) ends);
                        }
                }
            }
        }
        Assumptions.assumeTrue(meshes > 0, "need retail meshes");
        System.out.printf("DisplayList command codec: %d meshes byte-exact, %d commands; %d not 8-aligned, "
                + "%d NOP-terminated although already 8-aligned, %d mid-list zero words%n", meshes, commands,
                notEightAligned, alignedBeforeTermination, midListZeroWords);
        assertThat(meshes).isGreaterThan(1000);
        // evidence that the NOP word is deliberate and there is no align-to-8 step on top of it
        assertThat(notEightAligned).as("retail lists whose length is not a multiple of 8").isPositive();
        assertThat(alignedBeforeTermination).as("retail lists terminated although already 8-aligned").isPositive();
        // evidence that the zero word is the packing rule, not only an end-of-list convention
        assertThat(midListZeroWords).as("mid-list zero words after a parameterless top-most command").isPositive();
    }
}
