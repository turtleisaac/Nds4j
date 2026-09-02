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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * A pure-JVM, headless-capable interactive viewer for a decoded {@link Model} &mdash; Swing/Java2D over
 * {@link SoftwareRenderer}, no LWJGL/JOGL (see {@code TECH_DEBT.md} &sect;3). It draws the model in an
 * orbitable 3/4 view with a heads-up display: model/mesh/node counts, the playing animation and frame,
 * a per-material list (material &rarr; texture), a per-node list (parent, billboard flag) and a texture
 * browser strip.
 * <p>
 * The visual content is produced by {@link #renderView} &mdash; one Java2D function that composites the
 * 3D viewport and the HUD onto a single {@link BufferedImage}. The interactive Swing shell
 * ({@link ModelViewerFrame}, launched by {@link #main}) blits that image and adds mouse-orbit, a frame
 * scrubber and play/pause; because the same function renders headlessly, the viewer can also snapshot
 * itself with no display attached (used by tests and for documentation).
 */
public final class ModelViewer
{
    private ModelViewer() {}

    private static final Color BG = new Color(24, 26, 32);
    private static final Color PANEL = new Color(33, 36, 44);
    private static final Color INK = new Color(228, 231, 238);
    private static final Color DIM = new Color(150, 156, 168);
    private static final Color ACCENT = new Color(120, 178, 255);

    /**
     * Composites the 3D viewport and the inspection HUD onto one image &mdash; the viewer's entire visual
     * output, renderable with or without a display.
     * @param model the model to show
     * @param textures its textures, or null
     * @param animation the animation to play, or null for the bind pose
     * @param animLabel a label for the animation (shown in the HUD), or null
     * @param frame the frame to show (ignored if {@code animation} is null)
     * @param yawDegrees orbit yaw
     * @param pitchDegrees orbit pitch
     * @param width total image width
     * @param height total image height
     * @return the composited {@link BufferedImage}
     */
    public static BufferedImage renderView(Model model, TextureSet textures, NitroAnimation animation,
                                           String animLabel, int frame, double yawDegrees, double pitchDegrees,
                                           int width, int height)
    {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, width, height);

        int sidebarW = Math.max(280, width * 36 / 100);
        int viewportW = width - sidebarW;
        int stripH = 74;
        int viewportH = height - stripH;

        // 3D viewport
        BufferedImage view;
        if (animation != null)
            view = SoftwareRenderer.render(model, animation.sample(model, frame), textures, viewportW, viewportH, yawDegrees, pitchDegrees);
        else
            view = SoftwareRenderer.render(model, textures, viewportW, viewportH, yawDegrees, pitchDegrees);
        g.drawImage(view, 0, 0, null);

        drawSidebar(g, model, textures, animation, animLabel, frame, viewportW, 0, sidebarW, height);
        drawTextureStrip(g, textures, 0, viewportH, viewportW, stripH);
        // viewport border / overlay title
        g.setColor(new Color(0, 0, 0, 90));
        g.fillRect(0, 0, viewportW, 26);
        g.setColor(INK);
        g.setFont(uiFont(Font.BOLD, 13));
        g.drawString(model.getName(), 10, 18);
        g.setColor(DIM);
        g.setFont(uiFont(Font.PLAIN, 11));
        String orbit = String.format("yaw %.0f°  pitch %.0f°  — drag to orbit", yawDegrees, pitchDegrees);
        g.drawString(orbit, viewportW - g.getFontMetrics().stringWidth(orbit) - 10, 18);

        g.dispose();
        return img;
    }

    private static void drawSidebar(Graphics2D g, Model model, TextureSet textures, NitroAnimation anim,
                                    String animLabel, int frame, int x, int y, int w, int h)
    {
        g.setColor(PANEL);
        g.fillRect(x, y, w, h);
        int pad = 14, cx = x + pad;
        int cy = y + 26;

        g.setColor(ACCENT);
        g.setFont(uiFont(Font.BOLD, 12));
        g.drawString("MODEL", cx, cy); cy += 18;
        g.setColor(INK);
        g.setFont(uiFont(Font.PLAIN, 12));
        int verts = 0;
        for (Model.Mesh m : model.getMeshes()) verts += m.getVertexCount();
        cy = line(g, cx, cy, model.getMeshes().size() + " meshes, " + verts + " verts, " + model.getNodeCount() + " nodes");
        cy = line(g, cx, cy, "posScale " + String.format("%.4g", model.getPositionScale())
                + (model.hasDynamicPose() ? "  • dynamic" : ""));
        cy += 8;

        // animation
        g.setColor(ACCENT); g.setFont(uiFont(Font.BOLD, 12));
        g.drawString("ANIMATION", cx, cy); cy += 18;
        g.setColor(INK); g.setFont(uiFont(Font.PLAIN, 12));
        if (anim != null)
        {
            cy = line(g, cx, cy, (animLabel != null ? animLabel : "(anim)"));
            g.setColor(DIM);
            cy = line(g, cx, cy, "frame " + (frame + 1) + " / " + anim.getFrameCount()
                    + "   tracks: " + tracks(anim));
            // scrub bar
            int barY = cy + 2, barW = w - 2 * pad;
            g.setColor(new Color(60, 64, 74));
            g.fillRoundRect(cx, barY, barW, 5, 5, 5);
            g.setColor(ACCENT);
            int fill = anim.getFrameCount() <= 1 ? barW : (int) (barW * (frame / (double) (anim.getFrameCount() - 1)));
            g.fillRoundRect(cx, barY, Math.max(4, fill), 5, 5, 5);
            cy = barY + 18;
        }
        else
        {
            g.setColor(DIM);
            cy = line(g, cx, cy, "(bind pose)");
        }
        cy += 8;

        // materials
        g.setColor(ACCENT); g.setFont(uiFont(Font.BOLD, 12));
        g.drawString("MATERIALS", cx, cy); cy += 18;
        g.setFont(uiFont(Font.PLAIN, 11));
        List<Model.Material> mats = model.getMaterials();
        for (int i = 0; i < mats.size() && cy < y + h * 55 / 100; i++)
        {
            Model.Material mm = mats.get(i);
            g.setColor(INK);
            g.drawString(clip(mm.getName(), 16), cx, cy);
            g.setColor(DIM);
            String tex = mm.getTextureName() != null ? mm.getTextureName() : "—";
            g.drawString(clip(tex, 18), cx + 128, cy);
            cy += 15;
        }
        cy += 8;

        // nodes
        g.setColor(ACCENT); g.setFont(uiFont(Font.BOLD, 12));
        g.drawString("NODES", cx, cy); cy += 18;
        g.setFont(uiFont(Font.PLAIN, 11));
        for (int n = 0; n < model.getNodeCount() && cy < y + h - 12; n++)
        {
            g.setColor(INK);
            String pstr = model.getNodeParent(n) >= 0 ? ("←" + model.getNodeParent(n)) : "root";
            String bb = model.isBillboardNode(n) ? "  billboard" : "";
            g.drawString("node " + n + "   " + pstr + bb, cx, cy);
            cy += 15;
        }
    }

    private static void drawTextureStrip(Graphics2D g, TextureSet textures, int x, int y, int w, int h)
    {
        g.setColor(new Color(18, 19, 24));
        g.fillRect(x, y, w, h);
        g.setColor(DIM);
        g.setFont(uiFont(Font.BOLD, 10));
        g.drawString("TEXTURES", x + 10, y + 14);
        if (textures == null)
            return;
        int tx = x + 10, ty = y + 20, sz = h - 30;
        List<TextureSet.Texture> list = textures.getTextures();
        for (TextureSet.Texture t : list)
        {
            if (tx + sz > x + w - 6)
                break;
            try
            {
                BufferedImage ti = textures.getImage(t);
                g.drawImage(ti, tx, ty, sz, sz, null);
                g.setColor(new Color(70, 74, 84));
                g.drawRect(tx, ty, sz, sz);
            }
            catch (RuntimeException ignore) { }
            tx += sz + 6;
        }
    }

    private static String tracks(NitroAnimation a)
    {
        StringBuilder sb = new StringBuilder();
        if (a.hasSkeletal()) sb.append("CA ");
        if (a.hasTextureSrt()) sb.append("TA ");
        if (a.hasPattern()) sb.append("TP ");
        if (a.hasVisibility()) sb.append("VA ");
        return sb.length() == 0 ? "—" : sb.toString().trim();
    }

    private static int line(Graphics2D g, int x, int y, String s) { g.drawString(s, x, y); return y + 16; }

    private static String clip(String s, int n) { return s == null ? "" : (s.length() <= n ? s : s.substring(0, n - 1) + "…"); }

    private static String uiFontName;
    private static Font uiFont(int style, int size)
    {
        if (uiFontName == null)
        {
            // prefer a clean sans; fall back to the logical SansSerif
            uiFontName = Font.SANS_SERIF;
            for (String cand : new String[]{"Helvetica Neue", "Helvetica", "Arial", "DejaVu Sans"})
            {
                Font f = new Font(cand, Font.PLAIN, 12);
                if (f.getFamily().equalsIgnoreCase(cand)) { uiFontName = cand; break; }
            }
        }
        return new Font(uiFontName, style, size);
    }

    // --- interactive shell (only used with a display) ---

    /**
     * Launches the interactive Swing viewer for a model. Requires a display; for headless use call
     * {@link #renderView}. Mouse-drag orbits, the space bar and the on-screen button toggle playback, and
     * the slider scrubs frames.
     * @param model the model
     * @param textures its textures, or null
     * @param animation the animation to play, or null
     * @param animLabel a label for the animation
     */
    public static void launch(Model model, TextureSet textures, NitroAnimation animation, String animLabel)
    {
        javax.swing.SwingUtilities.invokeLater(() ->
                new ModelViewerFrame(model, textures, animation, animLabel).setVisible(true));
    }

    /**
     * Command-line entry point: {@code ModelViewer <rom> <narcIndex> <modelIndex> [ca=I] [ta=I] [tp=I] [va=I]}.
     * With a display it opens the interactive viewer; headless, it writes a snapshot PNG next to the ROM.
     * @param args see above
     * @throws Exception if the ROM/NARC cannot be read
     */
    public static void main(String[] args) throws Exception
    {
        if (args.length < 3)
        {
            System.err.println("usage: ModelViewer <rom> <narcIndex> <modelIndex> [ca=I] [ta=I] [tp=I] [va=I]");
            return;
        }
        io.github.turtleisaac.nds4j.NintendoDsRom rom = io.github.turtleisaac.nds4j.NintendoDsRom.fromFile(args[0]);
        io.github.turtleisaac.nds4j.Narc narc = new io.github.turtleisaac.nds4j.Narc(rom.getFile(Integer.parseInt(args[1])));
        ModelSet ms = new ModelSet(narc.getFile(Integer.parseInt(args[2])));
        Model model = ms.getModels().get(0);
        SkeletalAnimationSet.Animation ca = null;
        TextureSrtAnimationSet.Animation ta = null;
        TexturePatternAnimationSet.Animation tp = null;
        VisibilityAnimationSet.Animation va = null;
        String label = null;
        for (String a : args)
        {
            if (a.startsWith("ca=")) { ca = new SkeletalAnimationSet(narc.getFile(idx(a))).getAnimations().get(0); label = ca.getName(); }
            else if (a.startsWith("ta=")) ta = new TextureSrtAnimationSet(narc.getFile(idx(a))).getAnimations().get(0);
            else if (a.startsWith("tp=")) tp = new TexturePatternAnimationSet(narc.getFile(idx(a))).getAnimations().get(0);
            else if (a.startsWith("va=")) va = new VisibilityAnimationSet(narc.getFile(idx(a))).getAnimations().get(0);
        }
        NitroAnimation anim = (ca != null || ta != null || tp != null || va != null)
                ? new NitroAnimation(ca, ta, tp, va) : null;

        if (java.awt.GraphicsEnvironment.isHeadless())
        {
            BufferedImage shot = renderView(model, ms.getEmbeddedTextures(), anim, label,
                    anim != null ? anim.getFrameCount() / 3 : 0, 205, 14, 960, 600);
            java.io.File out = new java.io.File(args[0] + ".viewer.png");
            javax.imageio.ImageIO.write(shot, "png", out);
            System.out.println("headless: wrote " + out);
        }
        else
        {
            launch(model, ms.getEmbeddedTextures(), anim, label);
        }
    }

    private static int idx(String a) { return Integer.parseInt(a.substring(3)); }
}
