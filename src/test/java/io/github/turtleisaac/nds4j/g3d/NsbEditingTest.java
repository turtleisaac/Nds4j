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
 * Tests the writer-side foundation: the <b>editing round-trip</b>. Mutating a decoded {@code NSB*} object
 * and re-serialising must produce a valid file &mdash; byte-exact when nothing was edited, and a precise,
 * minimal, reversible diff when it was. Demonstrated on {@link MaterialColorAnimationSet} (NSBMA), whose
 * constant colours and per-frame alpha are edited in place; the same {@link G3dFile} block primitive
 * underlies every format.
 */
@DisplayName("NSB* editing round-trip (writer foundation)")
public class NsbEditingTest
{
    private static String magic(byte[] d)
    {
        return d == null || d.length < 4 ? "" : new String(d, 0, 4, StandardCharsets.ISO_8859_1);
    }

    private static byte[] demoKusari()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        Narc narc;
        try { narc = new Narc(rom.getFile(139)); }
        catch (RuntimeException e) { return null; }
        return magic(narc.getFile(5)).equals("BMA0") ? narc.getFile(5) : null;
    }

    private static int diffCount(byte[] a, byte[] b)
    {
        int n = 0;
        for (int i = 0; i < a.length; i++)
            if (a[i] != b[i]) n++;
        return n;
    }

    @Test
    @DisplayName("an unedited NSBMA saves byte-for-byte")
    void uneditedIsByteExact()
    {
        byte[] original = demoKusari();
        Assumptions.assumeTrue(original != null, "need demo_kusari NSBMA");
        assertThat(new MaterialColorAnimationSet(original).save()).isEqualTo(original);
    }

    @Test
    @DisplayName("editing a constant colour and an alpha keyframe writes a valid, minimal, reversible diff")
    void editIsMinimalValidAndReversible()
    {
        byte[] original = demoKusari();
        Assumptions.assumeTrue(original != null, "need demo_kusari NSBMA");

        MaterialColorAnimationSet set = new MaterialColorAnimationSet(original);
        MaterialColorAnimationSet.MaterialColor mat = set.getAnimations().get(0).getMaterials().get(0);
        int origColour = mat.getDiffuse().rawAt(0);
        int origAlpha = mat.getAlpha().at(40);

        // edit a constant colour (2 bytes) and one animated alpha frame (1 byte)
        mat.getDiffuse().setRgb(0, 0xFF0000);      // red
        mat.getAlpha().set(40, 7);
        byte[] edited = set.save();

        assertThat(edited.length).as("same-size edit keeps the file length").isEqualTo(original.length);
        assertThat(diffCount(original, edited)).as("only the touched bytes change (2 colour + 1 alpha)").isEqualTo(3);

        // re-reading the edited bytes yields the edited values, everything else intact
        MaterialColorAnimationSet reread = new MaterialColorAnimationSet(edited);
        MaterialColorAnimationSet.MaterialColor rmat = reread.getAnimations().get(0).getMaterials().get(0);
        assertThat(rmat.getDiffuse().rgbAt(0)).as("full red survives the 15-bit round-trip").isEqualTo(0xFF0000);
        assertThat(rmat.getAlpha().at(40)).isEqualTo(7);
        assertThat(rmat.getAlpha().at(0)).isEqualTo(0); // untouched frame unchanged
        assertThat(rmat.getAmbient().rawAt(0)).isEqualTo(mat.getAmbient().rawAt(0)); // untouched channel unchanged

        // reverting the exact edits restores the original file byte-for-byte
        rmat.getDiffuse().setRaw(0, origColour);
        rmat.getAlpha().set(40, origAlpha);
        assertThat(reread.save()).as("reverting edits restores the original bytes").isEqualTo(original);
    }

    @Test
    @DisplayName("recolouring an embedded palette re-saves a valid NSBMD and changes the render")
    void paletteRecolourEditsModelSet()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        Narc narc;
        try { narc = new Narc(rom.getFile(142)); }
        catch (RuntimeException e) { Assumptions.assumeTrue(false, "narc 142 not readable"); return; }
        Assumptions.assumeTrue(magic(narc.getFile(51)).equals("BMD0"), "expected manene NSBMD at narc 142 f51");
        byte[] original = narc.getFile(51);

        ModelSet ms = new ModelSet(original);
        assertThat(ms.save()).as("unedited NSBMD is byte-exact").isEqualTo(original);

        java.awt.image.BufferedImage before = SoftwareRenderer.render(
                ms.getModels().get(0), ms.getEmbeddedTextures(), 128, 128, 205, 12);

        TextureSet tex = ms.getEmbeddedTextures();
        TextureSet.Palette pal = null;
        for (TextureSet.Palette p : tex.getPalettes())
            if (p.getName().contains("blue")) { pal = p; break; }
        Assumptions.assumeTrue(pal != null, "need a blue palette to recolour");
        for (int i = 0; i < 4; i++)
            tex.setPaletteColor(pal, i, 0x00FF00); // paint it green
        int greenQuantised = tex.getPaletteColor(pal, 0); // 0xFF -> 5-bit -> 0xF8
        byte[] edited = ms.save();

        assertThat(edited.length).isEqualTo(original.length);
        assertThat(diffCount(original, edited)).as("only palette bytes change").isGreaterThan(0).isLessThan(64);

        // reload the edited NSBMD and confirm the colour took and the render differs
        ModelSet ms2 = new ModelSet(edited);
        TextureSet tex2 = ms2.getEmbeddedTextures();
        TextureSet.Palette pal2 = null;
        for (TextureSet.Palette p : tex2.getPalettes())
            if (p.getName().contains("blue")) { pal2 = p; break; }
        assertThat(tex2.getPaletteColor(pal2, 0)).isEqualTo(greenQuantised);
        assertThat((greenQuantised >> 8) & 0xFF).as("green channel dominates after the edit").isGreaterThan(200);

        java.awt.image.BufferedImage after = SoftwareRenderer.render(
                ms2.getModels().get(0), tex2, 128, 128, 205, 12);
        int changed = 0;
        for (int y = 0; y < 128; y++)
            for (int x = 0; x < 128; x++)
                if (before.getRGB(x, y) != after.getRGB(x, y)) changed++;
        assertThat(changed).as("recolouring should visibly change the render").isGreaterThan(200);
    }
}
