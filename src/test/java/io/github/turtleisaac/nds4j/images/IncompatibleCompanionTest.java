/*
 * Copyright (c) 2026 Turtleisaac.
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

import io.github.turtleisaac.nds4j.framework.MemBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("A cell whose OAMs fall outside the paired tileset")
public class IncompatibleCompanionTest
{
    @Test
    @DisplayName("an OAM tile past a one-tile NCGR is an incompatible tileset, for the cell and the NANR")
    void tilePastTileset()
    {
        IndexedImage ncgr = oneTile();
        CellBank bank = new CellBank(ncer(4, 0));
        bank.setParentImage(ncgr);
        CellAnimation animation = new CellAnimation(nanr());
        animation.setCellBank(bank);

        assertThatThrownBy(() -> bank.getTransparentNcerImage(0))
                .isInstanceOf(IncompatibleCompanionException.class)
                .extracting(ex -> ((IncompatibleCompanionException) ex).getKind())
                .isEqualTo(IncompatibleCompanionException.Kind.NCGR);
        assertThatThrownBy(() -> animation.getFrameImage(animation.getAnimations()[0].getFrames()[0]))
                .isInstanceOf(IncompatibleCompanionException.class)
                .extracting(ex -> ((IncompatibleCompanionException) ex).getKind())
                .isEqualTo(IncompatibleCompanionException.Kind.NCGR);
    }

    @Test
    @DisplayName("tile 0 of that same NCGR still draws")
    void tileInsideTileset()
    {
        IndexedImage ncgr = oneTile();
        CellBank bank = new CellBank(ncer(0, 0));
        bank.setParentImage(ncgr);

        BufferedImage image = bank.getTransparentNcerImage(0);

        assertThat(image.getWidth())
                .isEqualTo(8);
        assertThat(image.getHeight())
                .isEqualTo(8);
    }

    @Test
    @DisplayName("a cell index the NCER does not have is an incompatible cell bank")
    void cellPastBank()
    {
        IndexedImage ncgr = oneTile();
        CellBank bank = new CellBank(ncer(0, 0));
        bank.setParentImage(ncgr);

        assertThatThrownBy(() -> bank.getTransparentNcerImage(1))
                .isInstanceOf(IncompatibleCompanionException.class)
                .extracting(ex -> ((IncompatibleCompanionException) ex).getKind())
                .isEqualTo(IncompatibleCompanionException.Kind.NCER);
    }

    @Test
    @DisplayName("a prohibited OBJ shape is an incompatible cell bank")
    void prohibitedShape()
    {
        IndexedImage ncgr = oneTile();
        CellBank bank = new CellBank(shapedNcer());
        bank.setParentImage(ncgr);

        assertThatThrownBy(() -> bank.getNcerImage(0))
                .isInstanceOf(IncompatibleCompanionException.class)
                .extracting(ex -> ((IncompatibleCompanionException) ex).getKind())
                .isEqualTo(IncompatibleCompanionException.Kind.NCER);
    }

    @Test
    @DisplayName("a 4bpp palette number past the NCLR is an incompatible palette")
    void palettePastNclr()
    {
        IndexedImage ncgr = oneTile();
        CellBank bank = new CellBank(ncer(0, 2));
        bank.setParentImage(ncgr);

        assertThatThrownBy(() -> bank.getNcerImage(0))
                .isInstanceOf(IncompatibleCompanionException.class)
                .extracting(ex -> ((IncompatibleCompanionException) ex).getKind())
                .isEqualTo(IncompatibleCompanionException.Kind.NCLR);
    }

    /** One 8x8 4bpp tile and a 16-color palette. */
    private static IndexedImage oneTile()
    {
        Color[] colors = new Color[16];
        for (int i = 0; i < colors.length; i++)
            colors[i] = Color.BLACK;

        colors[1] = Color.RED;
        IndexedImage ncgr = new IndexedImage(8, 8, 4, new Palette(colors));
        ncgr.setPixelValue(1, 1, 1);
        return ncgr;
    }

    /** One cell whose only OAM has shape 3, which GBATEK OBJ Attribute 0 prohibits. */
    private static byte[] shapedNcer()
    {
        byte[] data = ncer(0, 0);
        data[0x10 + 0x20 + 8 + 1] = (byte) 0xC0;
        return data;
    }

    /** One cell, one square 8x8 OAM. GBATEK OBJ Attribute 2: tile number in bits 0-9, palette in bits 12-15. */
    private static byte[] ncer(int tile, int palette)
    {
        int sectionSize = 0x20 + 8 + 6;
        MemBuf buf = MemBuf.create();
        buf.writer()
                .writeString("RECN")
                .writeShort((short) 0xFEFF)
                .writeShort((short) 0x0100)
                .writeInt(0x10 + sectionSize)
                .writeShort((short) 0x10)
                .writeShort((short) 1);
        buf.writer()
                .writeString("KBEC")
                .writeInt(sectionSize)
                .writeShort((short) 1)
                .writeShort((short) 0)
                .writeInt(0x18)
                .writeInt(0)
                .writeInt(0)
                .writeInt(0)
                .writeInt(0);
        buf.writer()
                .writeShort((short) 1)
                .writeShort((short) 0)
                .writeInt(0);
        buf.writer()
                .writeByte((byte) 0)
                .writeByte((byte) 0)
                .writeShort((short) 0)
                .writeShort((short) (tile | (palette << 12)));
        return buf.reader().getBuffer();
    }

    /** One index-element animation whose only frame shows cell 0. numBlocks is 1, so there is no label section. */
    private static byte[] nanr()
    {
        int sectionSize = 0x3C;
        MemBuf buf = MemBuf.create();
        buf.writer()
                .writeString("RNAN")
                .writeShort((short) 0xFEFF)
                .writeShort((short) 0x0100)
                .writeInt(0x10 + sectionSize)
                .writeShort((short) 0x10)
                .writeShort((short) 1);
        buf.writer()
                .writeString("KNBA")
                .writeInt(sectionSize)
                .writeShort((short) 1)
                .writeShort((short) 1)
                .writeInt(0x18)
                .writeInt(0x28)
                .writeInt(0x30)
                .writeInt(0)
                .writeInt(0);
        buf.writer()
                .writeShort((short) 1)
                .writeShort((short) 0)
                .writeInt(0)
                .writeInt(1)
                .writeInt(0);
        buf.writer()
                .writeInt(0)
                .writeShort((short) 4)
                .writeShort((short) 0xBEEF);
        buf.writer()
                .writeShort((short) 0)
                .writeShort((short) 0);
        return buf.reader().getBuffer();
    }
}
