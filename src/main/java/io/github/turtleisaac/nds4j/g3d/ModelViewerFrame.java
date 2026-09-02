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

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

/**
 * The interactive Swing window around {@link ModelViewer#renderView}: mouse-orbit, a frame scrubber and
 * a play/pause button over the composited viewport+HUD. Pure Swing/Java2D, so it satisfies the
 * OS-agnostic constraint ({@code TECH_DEBT.md} &sect;3). Only constructed when a display is present
 * (see {@link ModelViewer#launch}); the headless path renders straight to an image.
 */
class ModelViewerFrame extends JFrame
{
    private final Model model;
    private final TextureSet textures;
    private final NitroAnimation animation;
    private final String animLabel;

    private double yaw = 205, pitch = 14;
    private int frame = 0;
    private int lastX, lastY;

    private final ViewCanvas canvas = new ViewCanvas();
    private final JSlider scrubber;
    private final Timer timer;

    ModelViewerFrame(Model model, TextureSet textures, NitroAnimation animation, String animLabel)
    {
        super("Nds4j — " + model.getName());
        this.model = model;
        this.textures = textures;
        this.animation = animation;
        this.animLabel = animLabel;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        canvas.setPreferredSize(new Dimension(960, 600));
        add(canvas, BorderLayout.CENTER);

        int frames = animation != null ? animation.getFrameCount() : 1;
        scrubber = new JSlider(0, Math.max(0, frames - 1), 0);
        scrubber.setEnabled(animation != null && frames > 1);
        scrubber.addChangeListener(e -> { frame = scrubber.getValue(); canvas.repaint(); });

        JButton play = new JButton("▶ Play");
        timer = new Timer(1000 / 30, e -> {
            if (animation != null)
            {
                frame = (frame + 1) % animation.getFrameCount();
                scrubber.setValue(frame);
            }
        });
        play.addActionListener(e -> {
            if (timer.isRunning()) { timer.stop(); play.setText("▶ Play"); }
            else { timer.start(); play.setText("❚❚ Pause"); }
        });
        play.setEnabled(animation != null && frames > 1);

        JPanel controls = new JPanel(new BorderLayout(8, 0));
        controls.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        controls.add(play, BorderLayout.WEST);
        controls.add(scrubber, BorderLayout.CENTER);
        add(controls, BorderLayout.SOUTH);

        MouseAdapter orbit = new MouseAdapter()
        {
            @Override public void mousePressed(MouseEvent e) { lastX = e.getX(); lastY = e.getY(); }
            @Override public void mouseDragged(MouseEvent e)
            {
                yaw += (e.getX() - lastX) * 0.6;
                pitch = Math.max(-89, Math.min(89, pitch + (e.getY() - lastY) * 0.6));
                lastX = e.getX(); lastY = e.getY();
                canvas.repaint();
            }
        };
        canvas.addMouseListener(orbit);
        canvas.addMouseMotionListener(orbit);
        pack();
        setLocationRelativeTo(null);
    }

    private class ViewCanvas extends JComponent
    {
        @Override protected void paintComponent(Graphics g)
        {
            BufferedImage view = ModelViewer.renderView(model, textures, animation, animLabel, frame,
                    yaw, pitch, Math.max(320, getWidth()), Math.max(240, getHeight()));
            g.drawImage(view, 0, 0, null);
        }
    }
}
