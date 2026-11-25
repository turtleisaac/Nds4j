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

/**
 * OBJ Attribute 1 bits 12 and 13 mirror a non-affine object.
 */
@DisplayName("OAM horizontal and vertical flip")
public class OamFlipTest
{
    private static final int H_FLIP = 1 << 12;
    private static final int V_FLIP = 1 << 13;

    /**
     * An unflipped object keeps its left pixel on the left.
     */
    @Test
    @DisplayName("an unflipped object keeps its pixels in place")
    void unflippedKeepsTheLeftPixel()
    {
        BufferedImage image = composed(0, 0);

        assertThat(image.getRGB(0, 0))
                .isEqualTo(Color.RED.getRGB());
        assertThat(image.getRGB(7, 0))
                .isEqualTo(Color.BLUE.getRGB());
    }

    /**
     * Attribute 1 bit 12 mirrors the object horizontally.
     */
    @Test
    @DisplayName("horizontal flip moves the left pixel to the right")
    void horizontalFlipMirrorsX()
    {
        BufferedImage image = composed(0, H_FLIP);

        assertThat(image.getRGB(0, 0))
                .isEqualTo(Color.BLUE.getRGB());
        assertThat(image.getRGB(7, 0))
                .isEqualTo(Color.RED.getRGB());
    }

    /**
     * Attribute 1 bit 13 mirrors the object vertically.
     */
    @Test
    @DisplayName("vertical flip moves the top pixel to the bottom")
    void verticalFlipMirrorsY()
    {
        BufferedImage image = composed(0, V_FLIP);

        assertThat(image.getRGB(0, 0))
                .isEqualTo(Color.GREEN.getRGB());
        assertThat(image.getRGB(0, 7))
                .isEqualTo(Color.RED.getRGB());
    }

    /**
     * Both flip bits mirror both axes.
     */
    @Test
    @DisplayName("both flips move the opposite corner to the origin")
    void bothFlipsMirrorBothAxes()
    {
        BufferedImage image = composed(0, H_FLIP | V_FLIP);

        assertThat(image.getRGB(0, 0))
                .isEqualTo(Color.YELLOW.getRGB());
    }

    /**
     * An affine object uses bits 9-13 as a parameter index, not as flip bits.
     */
    @Test
    @DisplayName("an affine object does not treat the flip bits as a mirror")
    void affineIgnoresFlipBits()
    {
        BufferedImage image = composed(1, H_FLIP | V_FLIP);

        assertThat(image.getRGB(0, 0))
                .isEqualTo(Color.RED.getRGB());
    }

    /**
     * Composes the marked 8x8 object.
     * @param attr0High the high byte of Attribute 0
     * @param attr1 Attribute 1, including flip or affine-index bits
     * @return the assembled cell
     */
    private static BufferedImage composed(int attr0High, int attr1)
    {
        CellBank bank = new CellBank(ncer(attr0High, attr1));
        bank.setParentImage(markedSheet());
        return bank.getNcerImage(0);
    }

    /**
     * An 8x8 sheet with a different color in each corner.
     * @return the sheet
     */
    private static IndexedImage markedSheet()
    {
        Color[] colors = new Color[16];
        for (int i = 0; i < colors.length; i++)
            colors[i] = Color.BLACK;
        colors[1] = Color.RED;
        colors[2] = Color.BLUE;
        colors[3] = Color.GREEN;
        colors[4] = Color.YELLOW;

        IndexedImage sheet = new IndexedImage(8, 8, 4, new Palette(colors));
        sheet.setPixelValue(0, 0, 1);
        sheet.setPixelValue(7, 0, 2);
        sheet.setPixelValue(0, 7, 3);
        sheet.setPixelValue(7, 7, 4);
        return sheet;
    }

    /**
     * A one-cell bank whose only object is an 8x8 sprite.
     * @param attr0High the high byte of Attribute 0
     * @param attr1 Attribute 1
     * @return the NCER bytes
     */
    private static byte[] ncer(int attr0High, int attr1)
    {
        int sectionSize = 0x20 + 8 + 6;
        MemBuf buf = MemBuf.create();

        // RECN container, then one KBEC cell and its object.
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
                .writeByte((byte) attr0High)
                .writeShort((short) attr1)
                .writeShort((short) 0);
        return buf.reader().getBuffer();
    }
}
