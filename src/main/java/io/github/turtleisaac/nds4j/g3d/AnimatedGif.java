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

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Writes a list of frames as an animated GIF using only {@code javax.imageio} &mdash; no native
 * dependency (see {@code TECH_DEBT.md} &sect;3). This is the shareable output of an animated
 * {@link SoftwareRenderer} preview: a single looping file that shows a model's walk cycle, water scroll,
 * blinking pattern or visibility toggle actually playing.
 * <p>
 * GIF is a 256-colour format, so the JDK's GIF writer quantises each frame; that is acceptable for a
 * preview (the lossless, viewer-grade path is {@link GltfExporter}). The per-frame delay and an infinite
 * loop are written into the standard GIF control/application extension blocks.
 */
public final class AnimatedGif
{
    private AnimatedGif() {}

    /**
     * Writes {@code frames} as an infinitely-looping animated GIF.
     * @param frames the frames, in order (all should share one size)
     * @param delayMs the delay between frames in milliseconds
     * @param out the {@code .gif} file to write
     * @throws IOException if no GIF writer is available or the file cannot be written
     */
    public static void write(List<BufferedImage> frames, int delayMs, File out) throws IOException
    {
        if (frames.isEmpty())
            throw new IOException("No frames to write");
        ImageWriter writer = ImageIO.getImageWritersBySuffix("gif").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out))
        {
            writer.setOutput(ios);
            writer.prepareWriteSequence(null);

            ImageWriteParam param = writer.getDefaultWriteParam();
            ImageTypeSpecifier type = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB);
            boolean first = true;
            for (BufferedImage frame : frames)
            {
                IIOMetadata meta = writer.getDefaultImageMetadata(type, param);
                configureFrame(meta, delayMs, first);
                writer.writeToSequence(new IIOImage(frame, null, meta), param);
                first = false;
            }
            writer.endWriteSequence();
        }
        finally
        {
            writer.dispose();
        }
    }

    // Sets the frame delay (GraphicControlExtension, in hundredths of a second) and, on the first frame,
    // the Netscape application extension that requests an infinite loop.
    private static void configureFrame(IIOMetadata meta, int delayMs, boolean first) throws IOException
    {
        String format = meta.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);

        IIOMetadataNode gce = child(root, "GraphicControlExtension");
        gce.setAttribute("disposalMethod", "none");
        gce.setAttribute("userInputFlag", "FALSE");
        gce.setAttribute("transparentColorFlag", "FALSE");
        gce.setAttribute("delayTime", Integer.toString(Math.max(1, delayMs / 10)));
        gce.setAttribute("transparentColorIndex", "0");

        if (first)
        {
            IIOMetadataNode appExts = child(root, "ApplicationExtensions");
            IIOMetadataNode appNode = new IIOMetadataNode("ApplicationExtension");
            appNode.setAttribute("applicationID", "NETSCAPE");
            appNode.setAttribute("authenticationCode", "2.0");
            appNode.setUserObject(new byte[]{0x1, 0x0, 0x0}); // sub-block: loop forever
            appExts.appendChild(appNode);
        }
        meta.setFromTree(format, root);
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name)
    {
        for (int i = 0; i < root.getLength(); i++)
            if (root.item(i).getNodeName().equalsIgnoreCase(name))
                return (IIOMetadataNode) root.item(i);
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }
}
