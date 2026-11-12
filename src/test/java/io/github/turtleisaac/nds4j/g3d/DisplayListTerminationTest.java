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
 * Authored display lists must follow the GXFIFO packing rule that the <em>top-most packed command must have
 * parameters</em> (GBATEK): since {@code END_VTXS} has none, the packed word that ends a list must be followed
 * by one all-zero word, exactly as {@code g3dcvtr} does (see {@link DisplayList#terminate}).
 * <p>
 * The check used here is a strict third-party reader: the {@code LibNDSFormats} walker used by DSPRE's model
 * renderer and its relatives, which re-tests its bounds check <em>before each of the four opcodes in a word</em>:
 * <pre>{@code for (i = 0; i < 4 && commandptr < commandlimit; i++) { switch (command[i]) { ... } }}</pre>
 * Without the trailing word that reader skips the final {@code END_VTXS}, so its {@code glEnd()} never runs
 * and the primitive's faces are never collected. These tests pin the behaviour for both authoring front
 * doors and confirm the real {@code g3dcvtr} fixtures already satisfy it.
 */
@DisplayName("Display-list termination (GXFIFO packing rule)")
public class DisplayListTerminationTest
{
    /**
     * The LibNDSFormats/DSPRE command walker, ported faithfully (including its mid-word bounds check).
     * @return the number of {@code END_VTXS} opcodes actually reached and executed
     */
    static int strictReaderEndVtxsCount(byte[] polydata)
    {
        int commandptr = 0, limit = polydata.length;
        int[] command = new int[4];
        int ends = 0;
        while (commandptr < limit)
        {
            for (int i = 0; i < 4; ++i)
                command[i] = commandptr >= limit ? 0xFF : polydata[commandptr++] & 0xFF;
            for (int i = 0; i < 4 && commandptr < limit; i++)
            {
                if (command[i] == 0x41)
                    ends++;
                if (command[i] != 0xFF)
                    commandptr += 4 * DisplayList.operandWords(command[i]);
            }
        }
        return ends;
    }

    private static boolean strictReaderReachesEndVtxs(byte[] polydata)
    {
        return strictReaderEndVtxsCount(polydata) > 0;
    }

    /**
     * Walks the packed opcode words of a display list and checks GBATEK's rule that the top-most packed
     * command must have parameters, in {@code g3dcvtr}'s form: a word whose top-most non-NOP command takes no
     * parameters must be followed by an all-zero word.
     * @return {violations, words that needed the zero word and had it mid-list, ... at the end of the list}
     */
    static int[] topMostRule(byte[] dl)
    {
        int violations = 0, midList = 0, atEnd = 0, pos = 0;
        while (pos + 4 <= dl.length)
        {
            int top = -1;
            int[] ops = {dl[pos] & 0xFF, dl[pos + 1] & 0xFF, dl[pos + 2] & 0xFF, dl[pos + 3] & 0xFF};
            pos += 4;
            for (int i = 0; i < 4; i++)
            {
                pos += 4 * Math.max(0, DisplayList.operandWords(ops[i]));
                if (ops[i] != 0)
                    top = ops[i];
            }
            if (top < 0 || DisplayList.operandWords(top) > 0)
                continue;
            boolean zeroFollows = pos + 4 <= dl.length
                    && dl[pos] == 0 && dl[pos + 1] == 0 && dl[pos + 2] == 0 && dl[pos + 3] == 0;
            if (!zeroFollows)
                violations++;
            else if (pos + 4 >= dl.length)
                atEnd++;
            else
                midList++;
        }
        return new int[]{violations, midList, atEnd};
    }

    /** the list length with trailing NOP commands stripped, i.e. what an encoder had before terminating */
    static int unterminatedLength(byte[] dl)
    {
        java.util.List<DisplayList.Command> cmds = DisplayList.decodeCommands(dl);
        int n = cmds.size();
        while (n > 0 && cmds.get(n - 1).opcode == 0) // NOP
            n--;
        return DisplayList.encodeCommands(cmds.subList(0, n)).length;
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
    @DisplayName("DisplayList.encode output ends with exactly one NOP word and is readable by a strict reader")
    void encodeIsTerminated(int triangles)
    {
        byte[] dl = DisplayList.encode(triangleFan(triangles), null, sequentialIndices(triangles));

        assertThat(dl.length - unterminatedLength(dl))
                .as("%d-triangle display list carries exactly one NOP word of padding, no alignment", triangles)
                .isEqualTo(4);
        assertThat(new byte[]{dl[dl.length - 4], dl[dl.length - 3], dl[dl.length - 2], dl[dl.length - 1]})
                .as("%d-triangle display list ends with an all-NOP command word", triangles)
                .containsOnly((byte) 0);
        assertThat(strictReaderReachesEndVtxs(dl))
                .as("a strict LibNDSFormats-style reader still executes END_VTXS for %d triangle(s)", triangles)
                .isTrue();
        assertThat(topMostRule(dl)[0])
                .as("%d-triangle display list follows the top-most-command packing rule", triangles)
                .isZero();
        // termination must not disturb the geometry the encoder promises
        assertThat(DisplayList.decode(dl).triangles.length)
                .as("termination padding does not change decoded geometry")
                .isEqualTo(triangles * 3);
    }

    @Test
    @DisplayName("the checks reject a list that breaks the rule, at the end or mid-list")
    void checksCatchViolations()
    {
        DisplayList.Command begin = new DisplayList.Command(0x40, new int[]{0});
        DisplayList.Command vtx = new DisplayList.Command(0x25, new int[]{0});
        DisplayList.Command end = new DisplayList.Command(0x41, new int[0]);

        // BEGIN, VTX_XY x2, END in one word: END is top-most and nothing follows it
        byte[] unterminated = DisplayList.encodeCommands(java.util.List.of(begin, vtx, vtx, end));
        assertThat(topMostRule(unterminated)[0]).as("unterminated list breaks the rule").isEqualTo(1);
        assertThat(strictReaderReachesEndVtxs(unterminated)).as("strict reader skips its END_VTXS").isFalse();
        assertThat(topMostRule(DisplayList.terminate(unterminated))).containsExactly(0, 0, 1);

        // two primitives back to back: the first word's top-most END is followed directly by the next opcode word
        java.util.List<DisplayList.Command> two = java.util.List.of(begin, vtx, vtx, end, begin, vtx, vtx, end);
        assertThat(topMostRule(DisplayList.terminate(DisplayList.encodeCommands(two)))[0])
                .as("a mid-list END_VTXS without a following zero word breaks the rule")
                .isEqualTo(1);
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
                .as("the unterminated stream is 24 bytes (already 8-aligned); exactly one NOP word still follows")
                .isEqualTo(28);
        assertThat(strictReaderReachesEndVtxs(dl))
                .as("a strict reader executes END_VTXS for an already-aligned .imd display list")
                .isTrue();
        assertThat(topMostRule(dl)[0])
                .as("an already-aligned .imd display list follows the top-most-command packing rule")
                .isZero();
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
            {
                assertThat(strictReaderReachesEndVtxs(mesh.getRawDisplayList()))
                        .as("g3dcvtr's %s/%s display list executes END_VTXS", model.getName(), mesh.getName())
                        .isTrue();
                assertThat(topMostRule(mesh.getRawDisplayList())[0])
                        .as("g3dcvtr's %s/%s display list follows the top-most-command packing rule",
                                model.getName(), mesh.getName())
                        .isZero();
            }
    }
}
