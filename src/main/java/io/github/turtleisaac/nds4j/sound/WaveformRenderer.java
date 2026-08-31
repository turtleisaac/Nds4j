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

package io.github.turtleisaac.nds4j.sound;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Headless (pure Java2D) renderer that draws a decoded PCM waveform as a min/max envelope image — the
 * visual proxy for "the audio decoded correctly". Also lays out a labelled sheet of several waves, so a
 * whole {@link WaveArchive} or an SDAT selection can be checkpointed in one PNG. No display needed;
 * paints straight to a {@link BufferedImage}.
 */
public final class WaveformRenderer
{
    private WaveformRenderer() {}

    private static final Color BG = new Color(0x14, 0x16, 0x1c);
    private static final Color GRID = new Color(0x2a, 0x2e, 0x38);
    private static final Color WAVE = new Color(0x4f, 0xd1, 0xc5);
    private static final Color WAVE_DIM = new Color(0x2c, 0x74, 0x70);
    private static final Color TEXT = new Color(0xd8, 0xdc, 0xe4);

    /** Render a single waveform (signed 16-bit PCM) as a min/max envelope. */
    public static BufferedImage render(short[] samples, int width, int height)
    {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(BG);
        g.fillRect(0, 0, width, height);
        drawWave(g, samples, 0, 0, width, height);
        g.dispose();
        return img;
    }

    private static void drawWave(Graphics2D g, short[] samples, int x0, int y0, int w, int h)
    {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int mid = y0 + h / 2;
        // zero line
        g.setColor(GRID);
        g.drawLine(x0, mid, x0 + w, mid);
        if (samples == null || samples.length == 0) return;

        double perCol = (double) samples.length / w;
        g.setStroke(new BasicStroke(1f));
        for (int col = 0; col < w; col++)
        {
            int start = (int) (col * perCol);
            int end = (int) ((col + 1) * perCol);
            if (end <= start) end = start + 1;
            if (end > samples.length) end = samples.length;
            int min = 0x7FFF, max = -0x8000;
            for (int i = start; i < end; i++)
            {
                int s = samples[i];
                if (s < min) min = s;
                if (s > max) max = s;
            }
            int yMax = mid - (int) (max / 32768.0 * (h / 2 - 1));
            int yMin = mid - (int) (min / 32768.0 * (h / 2 - 1));
            g.setColor(WAVE);
            g.drawLine(x0 + col, yMax, x0 + col, yMin);
        }
    }

    /**
     * Lay out a labelled sheet of waveforms — one row each — for checkpointing a whole archive/selection.
     * @param waves decoded PCM arrays
     * @param labels a label per wave (may be null)
     */
    public static BufferedImage sheet(List<short[]> waves, List<String> labels, int width, int rowHeight)
    {
        int rows = waves.size();
        int header = 22;
        int totalRow = rowHeight + header;
        int height = Math.max(totalRow, rows * totalRow);
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(BG);
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (int r = 0; r < rows; r++)
        {
            int y = r * totalRow;
            g.setColor(new Color(0x1c, 0x1f, 0x28));
            g.fillRect(6, y + 4, width - 12, totalRow - 8);
            g.setColor(TEXT);
            String label = (labels != null && r < labels.size() && labels.get(r) != null) ? labels.get(r) : ("wave " + r);
            g.drawString(label, 14, y + 17);
            drawWave(g, waves.get(r), 10, y + header, width - 20, rowHeight - 4);
        }
        g.dispose();
        return img;
    }
}
