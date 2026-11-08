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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every authored display list must survive a <b>strict</b> third-party reader &mdash; the
 * {@code LibNDSFormats} walker used by DSPRE's model renderer and its relatives, which re-tests its bounds
 * check <em>before each of the four opcodes in a command word</em>:
 * <pre>{@code for (i = 0; i < 4 && commandptr < commandlimit; i++) { switch (command[i]) { ... } }}</pre>
 * A list whose final operand ends exactly at the buffer limit therefore loses the remaining opcodes of its
 * last word. When the lost opcode is {@code END_VTXS} the reader's {@code glEnd()} never runs, the primitive
 * is left open and the mesh mis-renders &mdash; the failure mode that makes an otherwise valid NSBMD
 * "not importable" in those tools.
 * <p>
 * {@link DisplayList#terminate} prevents it by appending an all-{@code NOP} word. These tests pin that
 * behaviour for both authoring front doors, and confirm the real {@code g3dcvtr} fixtures already satisfy it.
 */
@DisplayName("Display-list termination (strict third-party readers)")
public class DisplayListTerminationTest
{
    /**
     * The LibNDSFormats/DSPRE command walker, ported faithfully (including its mid-word bounds check).
     * @return true if an {@code END_VTXS} opcode was actually reached and executed
     */
    private static boolean strictReaderReachesEndVtxs(byte[] polydata)
    {
        int commandptr = 0, limit = polydata.length;
        int[] command = new int[4];
        boolean sawEnd = false;
        while (commandptr < limit)
        {
            for (int i = 0; i < 4; ++i)
                command[i] = commandptr >= limit ? 0xFF : polydata[commandptr++] & 0xFF;
            for (int i = 0; i < 4 && commandptr < limit; i++)
            {
                if (command[i] == 0x41)
                    sawEnd = true;
                if (command[i] != 0xFF)
                    commandptr += 4 * DisplayList.operandWords(command[i]);
            }
        }
        return sawEnd;
    }

    private static byte[] resource(String name)
    {
        try (InputStream in = DisplayListTerminationTest.class.getResourceAsStream("/imd/" + name))
        {
            return in.readAllBytes();
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private static float[] triangleFan(int triangles)
    {
        float[] pos = new float[triangles * 9];
        for (int i = 0; i < pos.length; i++)
            pos[i] = (i % 7) * 0.1f;
        return pos;
    }

    private static int[] sequentialIndices(int triangles)
    {
        int[] tris = new int[triangles * 3];
        for (int i = 0; i < tris.length; i++)
            tris[i] = i;
        return tris;
    }

    @ParameterizedTest(name = "{0} triangle(s)")
    @ValueSource(ints = {1, 2, 3, 4, 5, 8, 17})
    @DisplayName("DisplayList.encode output is NOP-terminated, 8-aligned, and readable by a strict reader")
    void encodeIsTerminated(int triangles)
    {
        byte[] dl = DisplayList.encode(triangleFan(triangles), null, sequentialIndices(triangles));

        assertThat(dl.length % 8)
                .as("%d-triangle display list is 8-byte aligned", triangles)
                .isZero();
        assertThat(new byte[]{dl[dl.length - 4], dl[dl.length - 3], dl[dl.length - 2], dl[dl.length - 1]})
                .as("%d-triangle display list ends with an all-NOP command word", triangles)
                .containsOnly((byte) 0);
        assertThat(strictReaderReachesEndVtxs(dl))
                .as("a strict LibNDSFormats-style reader still executes END_VTXS for %d triangle(s)", triangles)
                .isTrue();
        // termination must not disturb the geometry the encoder promises
        assertThat(DisplayList.decode(dl).triangles.length)
                .as("termination padding does not change decoded geometry")
                .isEqualTo(triangles * 3);
    }

    @Test
    @DisplayName("an already-8-aligned ImdImporter list is still NOP-terminated (the old pad-only gap)")
    void imdImporterTerminatesAlreadyAlignedList()
    {
        // Three pos_xy vertices: 2 opcode words + 4 operand words = 24 bytes, already a multiple of 8, so
        // the previous align-only padding appended nothing and the trailing END_VTXS was droppable.
        String imd = new String(resource("book.imd"), StandardCharsets.ISO_8859_1)
                .replaceAll("(?s)<primitive index=\"0\".*?</primitive>",
                        "<primitive index=\"0\" type=\"triangles\" vertex_size=\"3\">"
                                + "<mtx idx=\"0\"/>"
                                + "<pos_xy xy=\"-4.000000 7.000000\"/>"
                                + "<pos_xy xy=\"4.000000 7.000000\"/>"
                                + "<pos_xy xy=\"0.000000 -5.000000\"/>"
                                + "</primitive>")
                .replace("nrm_flag=\"off\" clr_flag=\"on\" tex_flag=\"on\"",
                        "nrm_flag=\"off\" clr_flag=\"off\" tex_flag=\"off\"");

        ModelSet set = new ModelSet(ImdImporter.toNsbmd(imd, "book"));
        byte[] dl = set.getModels().get(0).getMeshes().get(0).getRawDisplayList();

        assertThat(dl.length)
                .as("the unterminated stream is 24 bytes (already 8-aligned), so a NOP word must still follow")
                .isGreaterThan(24);
        assertThat(strictReaderReachesEndVtxs(dl))
                .as("a strict reader executes END_VTXS for an already-aligned .imd display list")
                .isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"book.nsbmd", "rock.nsbmd", "two.nsbmd", "twotex.nsbmd", "v_decal.nsbmd",
            "v_flip.nsbmd", "xform.nsbmd", "book_both.nsbmd", "rock_both.nsbmd"})
    @DisplayName("g3dcvtr's own output already satisfies the strict reader (the behaviour being matched)")
    void g3dcvtrReferenceIsTerminated(String fixture)
    {
        ModelSet set = new ModelSet(resource(fixture));
        for (Model model : set.getModels())
            for (Model.Mesh mesh : model.getMeshes())
                assertThat(strictReaderReachesEndVtxs(mesh.getRawDisplayList()))
                        .as("g3dcvtr's %s/%s display list executes END_VTXS", model.getName(), mesh.getName())
                        .isTrue();
    }
}
