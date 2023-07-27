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
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;

public class ImageTest
{
    // rom
    private static final NintendoDsRom rom = NintendoDsRom.fromFile("HeartGold.nds");

    // this contains the party icons in HGSS
    private static final Narc a020 = new Narc(rom.getFileByName("a/0/2/0"));

    // this contains the battle sprites in HGSS
    private static final Narc a004 = new Narc(rom.getFileByName("a/0/0/4"));

    // infernape party sprite in HGSS
    private static final IndexedImage tiled = IndexedImage.fromNcgr(a020.files.get(399), 4, 0, 1, 1, true);

    // bulbasaur battle sprite in HGSS
    private static final IndexedImage scanned = IndexedImage.fromNcgr(a004.files.get(6), 0, 0, 1, 1, true);
    private static final IndexedImage scanned2 = IndexedImage.fromNcgr(a004.files.get(6), 0, 0, 1, 1, false);

    @Test
    void bitDepth()
    {
        assertThat(tiled.getBitDepth())
                .isEqualTo(4);
        assertThat(scanned.getBitDepth())
                .isEqualTo(4);
    }

    @Test
    void scanModeNone()
    {
        assertThat(tiled.getScanMode())
                .isEqualTo(IndexedImage.NcgrUtils.ScanMode.NOT_SCANNED);
    }

    @Test
    void scanModeFrontToBack()
    {
        assertThat(scanned.getScanMode())
                .isEqualTo(IndexedImage.NcgrUtils.ScanMode.FRONT_TO_BACK);
    }

    @Test
    void scanModeBackToFront()
    {
        assertThat(scanned2.getScanMode())
                .isEqualTo(IndexedImage.NcgrUtils.ScanMode.BACK_TO_FRONT);
    }

    @Test
    void height()
    {
        assertThat(tiled.getHeight())
                .isEqualTo(64);
    }

    @Test
    void width()
    {
        assertThat(tiled.getWidth())
                .isEqualTo(32);
    }

    @Test
    void sopc()
    {
        assertThat(tiled.hasSopc())
                .isEqualTo(false);
    }

    @Test
    void vram()
    {
        assertThat(tiled.isVram())
                .isEqualTo(false);
    }

    @Test
    void mappingType()
    {
        assertThat(tiled.getMappingType())
                .isEqualTo(0);
    }

    @Test
    void numTiles()
    {
        assertThat(tiled.getNumTiles())
                .isEqualTo(32);
    }

    @Test
    void pixelsHeight()
    {
        assertThat(tiled.getPixels().length)
                .isEqualTo(tiled.getHeight());
    }

    @Test
    void pixelsWidth()
    {
        assertThat(tiled.getPixels()[0].length)
                .isEqualTo(tiled.getWidth());
    }

    @Test
    void scannedEncryptionKeySet()
    {
        assertThat(scanned.getEncryptionKey())
                .isNotEqualTo(-1);
    }

    @Test
    void writtenTiledNcgrEqualsOriginal()
    {
        IndexedImage written = IndexedImage.fromNcgr(tiled.saveAsNcgr(), 4, 0, 1, 1, true);
        assertThat(tiled)
                .isEqualTo(written);
    }

//    @Test
//    void writtenTiledNcgrVisuallyMatchesOriginal() throws InterruptedException
//    {
//        Palette palette = Palette.fromNclr(a020.files.get(0), tiled.getBitDepth());
//        tiled.setPalette(palette);
//
//        BufferedImage originalImage = new BufferedImage(tiled.getWidth() * 4, tiled.getHeight() * 4, BufferedImage.TYPE_INT_RGB);
//        Graphics2D graphics2D = originalImage.createGraphics();
//        graphics2D.drawImage(tiled.getImage(), 0, 0, tiled.getWidth() * 4, tiled.getHeight() * 4, null);
//        graphics2D.dispose();
//
//        IndexedImage written = IndexedImage.fromNcgr(tiled.saveAsNcgr(), 4, 0, 1, 1, true);
//        palette = Palette.fromNclr(a020.files.get(0), written.getBitDepth());
//        written.setPalette(palette);
//
//        BufferedImage writtenImage = new BufferedImage(written.getWidth() * 4, written.getHeight() * 4, BufferedImage.TYPE_INT_RGB);
//        graphics2D = writtenImage.createGraphics();
//        graphics2D.drawImage(written.getImage(), 0, 0, written.getWidth() * 4, written.getHeight() * 4, null);
//        graphics2D.dispose();
//
//        JFrame frame = new JFrame("Are these images the same?");
//        frame.setSize(originalImage.getWidth() + writtenImage.getWidth() + 50, originalImage.getHeight() + writtenImage.getHeight());
//
//        JLabel label1 = new JLabel();
//        label1.setIcon(new ImageIcon(originalImage));
//
//        JLabel label2 = new JLabel();
//        label2.setIcon(new ImageIcon(writtenImage));
//
//        JButton yesButton = new JButton("Yes");
//        JButton noButton = new JButton("No");
//
//        final JButton[] source = new JButton[1];
//
//        ActionListener actionListener = new ActionListener()
//        {
//            @Override
//            public void actionPerformed(ActionEvent e)
//            {
//                source[0] = (JButton) e.getSource();
//                frame.setVisible(false);
//            }
//        };
//
//        yesButton.addActionListener(actionListener);
//        noButton.addActionListener(actionListener);
//
//        frame.getContentPane().add(label1, BorderLayout.WEST);
//        frame.getContentPane().add(label2, BorderLayout.EAST);
//        frame.getContentPane().add(yesButton, BorderLayout.NORTH);
//        frame.getContentPane().add(noButton, BorderLayout.SOUTH);
//
//        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
//        frame.setLocationRelativeTo(null);
//        frame.pack();
//        frame.setVisible(true);
//        frame.setSize(frame.getWidth() + 50, frame.getHeight() + 50);
//
//        while (frame.isVisible())
//        {
//            Thread.sleep(1000);
//        }
//
//        assertThat(source[0])
//                .isEqualTo(yesButton);
//    }

    @Test
    void writtenScannedNcgrEqualsOriginal()
    {
        IndexedImage written = IndexedImage.fromNcgr(scanned.saveAsNcgr(), 0, 0, 1, 1, true);
        assertThat(scanned)
                .isEqualTo(written);
    }

//    @Test
//    void writtenScannedNcgrVisuallyMatchesOriginal() throws InterruptedException
//    {
//        Palette palette = Palette.fromNclr(a004.files.get(10), scanned.getBitDepth());
//        scanned.setPalette(palette);
//
//        BufferedImage originalImage = new BufferedImage(scanned.getWidth() * 4, scanned.getHeight() * 4, BufferedImage.TYPE_INT_RGB);
//        Graphics2D graphics2D = originalImage.createGraphics();
//        graphics2D.drawImage(scanned.getImage(), 0, 0, scanned.getWidth() * 4, scanned.getHeight() * 4, null);
//        graphics2D.dispose();
//
//        IndexedImage written = IndexedImage.fromNcgr(scanned.saveAsNcgr(), 0, 0, 1, 1, true);
//        palette = Palette.fromNclr(a004.files.get(10), written.getBitDepth());
//        written.setPalette(palette);
//
//        BufferedImage writtenImage = new BufferedImage(written.getWidth() * 4, written.getHeight() * 4, BufferedImage.TYPE_INT_RGB);
//        graphics2D = writtenImage.createGraphics();
//        graphics2D.drawImage(written.getImage(), 0, 0, written.getWidth() * 4, written.getHeight() * 4, null);
//        graphics2D.dispose();
//
//        JFrame frame = new JFrame("Are these images the same?");
//        frame.setSize(originalImage.getWidth() + writtenImage.getWidth() + 50, originalImage.getHeight() + writtenImage.getHeight());
//
//        JLabel label1 = new JLabel();
//        label1.setIcon(new ImageIcon(originalImage));
//
//        JLabel label2 = new JLabel();
//        label2.setIcon(new ImageIcon(writtenImage));
//
//        JButton yesButton = new JButton("Yes");
//        JButton noButton = new JButton("No");
//
//        final JButton[] source = new JButton[1];
//
//        ActionListener actionListener = new ActionListener()
//        {
//            @Override
//            public void actionPerformed(ActionEvent e)
//            {
//                source[0] = (JButton) e.getSource();
//                frame.setVisible(false);
//            }
//        };
//
//        yesButton.addActionListener(actionListener);
//        noButton.addActionListener(actionListener);
//
//        frame.getContentPane().add(label1, BorderLayout.WEST);
//        frame.getContentPane().add(label2, BorderLayout.EAST);
//        frame.getContentPane().add(yesButton, BorderLayout.NORTH);
//        frame.getContentPane().add(noButton, BorderLayout.SOUTH);
//
//        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
//        frame.setLocationRelativeTo(null);
//        frame.pack();
//        frame.setVisible(true);
//        frame.setSize(frame.getWidth() + 50, frame.getHeight() + 50);
//
//        while (frame.isVisible())
//        {
//            Thread.sleep(1000);
//        }
//
//        assertThat(source[0])
//                .isEqualTo(yesButton);
//    }

    @Test
    void writtenScannedEncryptionKeyMatchesOriginal()
    {
        IndexedImage written = IndexedImage.fromNcgr(scanned.saveAsNcgr(), 0, 0, 1, 1, true);

        assertThat(scanned.getEncryptionKey())
                .isEqualTo(written.getEncryptionKey());
    }
}
