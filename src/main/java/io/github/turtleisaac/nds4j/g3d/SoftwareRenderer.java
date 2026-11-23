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

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny, dependency-free software rasterizer for previewing a decoded {@link Model} &mdash; a
 * headless, OS-agnostic way to render a model to a {@link BufferedImage} with its textures applied
 * (see {@code TECH_DEBT.md} &sect;3: no LWJGL/JOGL, pure JVM). It is a preview, not a game renderer:
 * orthographic 3/4 view, per-face flat shading, a z-buffer and nearest-neighbour texture sampling with
 * a DS-style alpha test. A material whose polygon alpha is 0 is not drawn ({@link PolygonDraw}).
 * For a rich, viewer-grade export use {@link GltfExporter}.
 * <p>
 * Render a model at its bind pose, pass the per-mesh positions from {@link Model#pose} to render a
 * skeletal frame, or pass a {@link NitroAnimation.Frame} to apply <em>all four</em> animation tracks at
 * once (skeleton pose, texture-SRT UV scroll, texture-pattern swap and node visibility). The
 * {@link #renderFrames} helper rasterises a whole animation for an animated GIF (see {@link AnimatedGif}).
 */
public final class SoftwareRenderer
{
    private SoftwareRenderer()
    {
    }

    /**
     * Renders a model at its bind pose.
     * @param model the model
     * @param textures the textures its materials reference (e.g. {@link ModelSet#getEmbeddedTextures()}),
     *                 or null to render untextured
     * @param width the image width in pixels
     * @param height the image height in pixels
     * @param yawDegrees rotation about the vertical axis, in degrees
     * @param pitchDegrees rotation about the horizontal axis, in degrees
     * @return a rendered {@link BufferedImage}
     */
    public static BufferedImage render(Model model, TextureSet textures, int width, int height,
            double yawDegrees, double pitchDegrees)
    {
        List<float[]> positions = new ArrayList<>();
        for (Model.Mesh mesh : model.getMeshes())
            positions.add(mesh.getPositions());
        return render(model, positions, textures, width, height, yawDegrees, pitchDegrees);
    }

    /**
     * Renders a model with an explicit set of per-mesh vertex positions &mdash; typically an animation
     * frame from {@link Model#pose(SkeletalAnimationSet.Animation, int)}. The positions list is parallel
     * to {@link Model#getMeshes()}.
     * @param model the model (for its meshes' texcoords, materials and triangles)
     * @param positions per-mesh vertex positions (x,y,z triples), parallel to {@code model.getMeshes()}
     * @param textures the textures its materials reference, or null
     * @param width the image width in pixels
     * @param height the image height in pixels
     * @param yawDegrees rotation about the vertical axis, in degrees
     * @param pitchDegrees rotation about the horizontal axis, in degrees
     * @return a rendered {@link BufferedImage}
     */
    public static BufferedImage render(Model model, List<float[]> positions, TextureSet textures,
            int width, int height, double yawDegrees, double pitchDegrees)
    {
        return render(model, positions, textures, null, width, height, yawDegrees, pitchDegrees);
    }

    /**
     * Renders a fully-animated frame: the skeleton pose, texture-SRT UVs, texture-pattern swap and node
     * visibility from a {@link NitroAnimation.Frame} are all applied.
     * @param model the model
     * @param frame the sampled animation frame (see {@link NitroAnimation#sample})
     * @param textures the textures its materials reference, or null
     * @param width the image width in pixels
     * @param height the image height in pixels
     * @param yawDegrees rotation about the vertical axis, in degrees
     * @param pitchDegrees rotation about the horizontal axis, in degrees
     * @return a rendered {@link BufferedImage}
     */
    public static BufferedImage render(Model model, NitroAnimation.Frame frame, TextureSet textures,
            int width, int height, double yawDegrees, double pitchDegrees)
    {
        return render(model, frame.getPositions(), textures, frame, width, height, yawDegrees, pitchDegrees);
    }

    /**
     * Rasterises every frame of an animation, framed on the animation's overall extent so the model does
     * not jitter as parts move (each frame shares one camera). Feed the result to {@link AnimatedGif}.
     * @param model the model
     * @param animation the animation to play
     * @param textures the textures its materials reference, or null
     * @param width the image width in pixels
     * @param height the image height in pixels
     * @param yawDegrees rotation about the vertical axis, in degrees
     * @param pitchDegrees rotation about the horizontal axis, in degrees
     * @return one {@link BufferedImage} per frame
     */
    public static List<BufferedImage> renderFrames(Model model, NitroAnimation animation, TextureSet textures,
            int width, int height, double yawDegrees, double pitchDegrees)
    {
        int frames = animation.getFrameCount();
        List<NitroAnimation.Frame> sampled = new ArrayList<>(frames);
        for (int f = 0; f < frames; f++)
            sampled.add(animation.sample(model, f));

        // A single camera for the whole clip: fit to the union of every frame's bounding box.
        float[] min = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY };
        float[] max = { Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
        for (NitroAnimation.Frame fr : sampled)
            accumulateBounds(fr.getPositions(), min, max);
        Camera cam = frameCamera(min, max, width, height);

        List<BufferedImage> out = new ArrayList<>(frames);
        for (NitroAnimation.Frame fr : sampled)
            out.add(rasterizeFrame(model, fr.getPositions(), textures, fr, width, height, yawDegrees, pitchDegrees, cam));
        return out;
    }

    private static BufferedImage render(Model model, List<float[]> positions, TextureSet textures,
            NitroAnimation.Frame frame, int width, int height,
            double yawDegrees, double pitchDegrees)
    {
        float[] min = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY };
        float[] max = { Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
        accumulateBounds(positions, min, max);
        Camera cam = frameCamera(min, max, width, height);
        return rasterizeFrame(model, positions, textures, frame, width, height, yawDegrees, pitchDegrees, cam);
    }

    /**
     * Paints one frame. A null camera means the model had no extent, so only the gradient is returned.
     * @param model the model
     * @param positions per-mesh positions
     * @param textures textures, or null
     * @param frame the animation frame, or null
     * @param width image width
     * @param height image height
     * @param yawDegrees orbit yaw
     * @param pitchDegrees orbit pitch
     * @param cam the fitted camera, or null
     * @return the rendered image
     */
    private static BufferedImage rasterizeFrame(Model model, List<float[]> positions, TextureSet textures,
            NitroAnimation.Frame frame, int width, int height,
            double yawDegrees, double pitchDegrees, Camera cam)
    {
        BufferedImage img = background(width, height);
        if (cam == null)
            return img;
        new FramePainter(model, positions, textures, frame, img, cam, yawDegrees, pitchDegrees).paint();
        return img;
    }

    /**
     * @param width image width
     * @param height image height
     * @return the grey gradient the preview draws behind the model
     */
    private static BufferedImage background(int width, int height)
    {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
            fillRow(img, y, height);
        return img;
    }

    /**
     * @param img the image
     * @param y the row
     * @param height the image height
     */
    private static void fillRow(BufferedImage img, int y, int height)
    {
        int g = 248 - (int) (26.0 * y / height);
        int rgb = 0xFF000000 | (g << 16) | (g << 8) | Math.min(255, g + 6);
        for (int x = 0; x < img.getWidth(); x++)
            img.setRGB(x, y, rgb);
    }

    /**
     * Draws one frame. A mesh is skipped when its node is hidden or its polygon alpha (the value NNS
     * checks after the material blend; this preview has no NSBMA bound, so that is the material's own
     * alpha) is 0. A wireframe material with a non-zero alpha is drawn as edges.
     */
    private static final class FramePainter
    {
        private final Model model;
        private final List<float[]> positions;
        private final TextureSet textures;
        private final NitroAnimation.Frame frame;
        private final BufferedImage img;
        private final Camera cam;
        private final int width;
        private final int height;
        private final double cy, sy, cp, sp;
        private final double[] light = normalise(0.35, 0.5, 0.78);
        private final double[] zbuf;
        private final Map<String, BufferedImage> texCache = new HashMap<>();
        private final double[][] c = new double[3][3];
        private final double[][] uvw = new double[3][2];

        /**
         * @param model the model
         * @param positions per-mesh positions
         * @param textures textures, or null
         * @param frame the animation frame, or null
         * @param img the target image
         * @param cam the fitted camera
         * @param yawDegrees orbit yaw
         * @param pitchDegrees orbit pitch
         */
        FramePainter(Model model, List<float[]> positions, TextureSet textures, NitroAnimation.Frame frame,
                BufferedImage img, Camera cam, double yawDegrees, double pitchDegrees)
        {
            this.model = model;
            this.positions = positions;
            this.textures = textures;
            this.frame = frame;
            this.img = img;
            this.cam = cam;
            this.width = img.getWidth();
            this.height = img.getHeight();
            double yaw = Math.toRadians(yawDegrees), pitch = Math.toRadians(pitchDegrees);
            cy = Math.cos(yaw);
            sy = Math.sin(yaw);
            cp = Math.cos(pitch);
            sp = Math.sin(pitch);
            zbuf = new double[width * height];
            java.util.Arrays.fill(zbuf, Double.POSITIVE_INFINITY);
        }

        /** Draws every mesh the material and visibility state still submit. */
        void paint()
        {
            List<Model.Mesh> meshes = model.getMeshes();
            for (int m = 0; m < meshes.size(); m++)
                paintMesh(meshes.get(m), m);
        }

        /**
         * @param mesh the mesh
         * @param index its index in the model
         */
        private void paintMesh(Model.Mesh mesh, int index)
        {
            if (skipMesh(mesh, index))
                return;
            drawMesh(mesh, index);
        }

        /**
         * @param mesh the mesh
         * @param index its index
         * @return true when NNS would not send this shape
         */
        private boolean skipMesh(Model.Mesh mesh, int index)
        {
            if (nodeHidden(index))
                return true;
            return materialHidden(mesh);
        }

        /**
         * @param index the mesh index
         * @return true when the visibility track hides this mesh
         */
        private boolean nodeHidden(int index)
        {
            if (frame == null)
                return false;
            return !frame.isVisible(index);
        }

        /**
         * @param mesh the mesh
         * @return true when polygon alpha is 0
         */
        private boolean materialHidden(Model.Mesh mesh)
        {
            Model.Material mat = mesh.getMaterial();
            if (mat == null)
                return false;
            return !PolygonDraw.submitsShape(mat.getPolygonAlpha());
        }

        /**
         * @param mesh the mesh
         * @param index its index
         */
        private void drawMesh(Model.Mesh mesh, int index)
        {
            int[] idx = mesh.getTriangleIndices();
            Prepared prep = prepare(mesh, index);
            for (int t = 0; t + 2 < idx.length; t += 3)
                drawTriangle(prep, idx, t);
        }

        /**
         * @param mesh the mesh
         * @param index its index
         * @return the per-mesh values the triangle loop needs
         */
        private Prepared prepare(Model.Mesh mesh, int index)
        {
            Prepared prep = new Prepared();
            prep.pos = positions.get(index);
            prep.uv = mesh.getTexcoords();
            prep.tex = textureFor(mesh, textures, frame, index, texCache);
            prep.tw = prep.tex != null ? prep.tex.getWidth() : 0;
            prep.th = prep.tex != null ? prep.tex.getHeight() : 0;
            prep.uvMat = uvMatrix(index);
            prep.wire = wireframe(mesh);
            prep.billboard = model.isBillboardNode(mesh.getNodeIndex());
            if (prep.billboard)
                fillBillboard(prep, mesh, index);
            return prep;
        }

        /**
         * @param index the mesh index
         * @return the texture matrix, or null
         */
        private float[] uvMatrix(int index)
        {
            if (frame == null)
                return null;
            return frame.uvMatrixFor(index);
        }

        /**
         * @param mesh the mesh
         * @return true when a non-zero alpha is sent as wireframe edges
         */
        private boolean wireframe(Model.Mesh mesh)
        {
            Model.Material mat = mesh.getMaterial();
            if (mat == null)
                return false;
            return PolygonDraw.wireframeEdges(mat.getPolygonAlpha(), mat.isWireframe());
        }

        /**
         * Billboard (BB/BBY): place the shape's local geometry on the screen at its node's pivot.
         * @param prep the mesh values to fill
         * @param mesh the mesh
         * @param index its index
         */
        private void fillBillboard(Prepared prep, Model.Mesh mesh, int index)
        {
            double[] pivotWorld = billboardPivot(index);
            if (pivotWorld == null)
                pivotWorld = model.getNodeWorldTranslation(mesh.getNodeIndex());
            prep.bill = projectPivot(pivotWorld, cam, cy, sy, cp, sp, width, height);
            prep.rawLocal = mesh.getRawPositions();
            prep.wscale = model.getNodeWorldScale(mesh.getNodeIndex());
            prep.posScale = model.getPositionScale();
        }

        /**
         * @param index the mesh index
         * @return the posed billboard pivot, or null
         */
        private double[] billboardPivot(int index)
        {
            if (frame == null)
                return null;
            return frame.billboardPivotFor(index);
        }

        /**
         * @param prep the prepared mesh
         * @param idx triangle indices
         * @param t the first index of this triangle
         */
        private void drawTriangle(Prepared prep, int[] idx, int t)
        {
            projectCorner(prep, idx[t], 0);
            projectCorner(prep, idx[t + 1], 1);
            projectCorner(prep, idx[t + 2], 2);
            if (prep.wire)
                drawWire(prep);
            else
                rasterize(img, zbuf, width, height, c, uvw, prep.tex, prep.tw, prep.th, shade(prep));
        }

        /**
         * @param prep the prepared mesh
         * @return the flat shade for this triangle
         */
        private double shade(Prepared prep)
        {
            if (prep.billboard)
                return 1.0;
            return shadeFor(c, light);
        }

        /**
         * @param prep the prepared mesh
         * @param v the vertex index
         * @param k the corner, 0..2
         */
        private void projectCorner(Prepared prep, int v, int k)
        {
            if (prep.billboard)
                placeBillboard(prep, v, k);
            else
                placeWorld(prep, v, k);
            placeUv(prep, v, k);
        }

        /**
         * @param prep the prepared mesh
         * @param v the vertex index
         * @param k the corner
         */
        private void placeBillboard(Prepared prep, int v, int k)
        {
            double lx = prep.rawLocal[v * 3] * prep.wscale[0] * prep.posScale;
            double ly = prep.rawLocal[v * 3 + 1] * prep.wscale[1] * prep.posScale;
            double lz = prep.rawLocal[v * 3 + 2] * prep.wscale[2] * prep.posScale;
            c[k][0] = prep.bill[0] + lx * cam.scale;
            c[k][1] = prep.bill[1] - ly * cam.scale;
            c[k][2] = prep.bill[2] - lz * cam.scale;
        }

        /**
         * @param prep the prepared mesh
         * @param v the vertex index
         * @param k the corner
         */
        private void placeWorld(Prepared prep, int v, int k)
        {
            double x = prep.pos[v * 3] - cam.centre[0];
            double y = prep.pos[v * 3 + 1] - cam.centre[1];
            double z = prep.pos[v * 3 + 2] - cam.centre[2];
            double x1 = cy * x + sy * z, z1 = -sy * x + cy * z, y1 = y;
            double y2 = cp * y1 - sp * z1, z2 = sp * y1 + cp * z1;
            c[k][0] = width / 2.0 + x1 * cam.scale;
            c[k][1] = height / 2.0 - y2 * cam.scale;
            c[k][2] = z2;
        }

        /**
         * @param prep the prepared mesh
         * @param v the vertex index
         * @param k the corner
         */
        private void placeUv(Prepared prep, int v, int k)
        {
            double s = normCoord(prep.uv[v * 2], prep.tw);
            double tt = normCoord(prep.uv[v * 2 + 1], prep.th);
            if (prep.uvMat != null)
                applyUvMat(prep, k, s, tt);
            else
                storeUv(k, s, tt);
        }

        /**
         * @param value a texel coordinate
         * @param size the texture size along that axis, or 0
         * @return the coordinate normalised when a texture size is known
         */
        private double normCoord(float value, int size)
        {
            if (size > 0)
                return value / size;
            return value;
        }

        /**
         * @param prep the prepared mesh
         * @param k the corner
         * @param s the normalised s
         * @param tt the normalised t
         */
        private void applyUvMat(Prepared prep, int k, double s, double tt)
        {
            float[] m = prep.uvMat;
            storeUv(k, m[0] * s + m[2] * tt + m[4], m[1] * s + m[3] * tt + m[5]);
        }

        /**
         * @param k the corner
         * @param s normalised s
         * @param tt normalised t
         */
        private void storeUv(int k, double s, double tt)
        {
            uvw[k][0] = s;
            uvw[k][1] = tt;
        }

        /**
         * @param prep the prepared mesh
         */
        private void drawWire(Prepared prep)
        {
            int rgb = edgeRgb(prep);
            drawEdge(0, 1, rgb);
            drawEdge(1, 2, rgb);
            drawEdge(2, 0, rgb);
        }

        /**
         * @param prep the prepared mesh
         * @return an opaque edge colour
         */
        private int edgeRgb(Prepared prep)
        {
            if (prep.tex == null)
                return shadedRgb(200, 205, 215, shade(prep));
            return texturedEdge(prep);
        }

        /**
         * @param prep the prepared mesh
         * @return the first corner's texel, shaded
         */
        private int texturedEdge(Prepared prep)
        {
            int argb = prep.tex.getRGB(floorMod((int) Math.floor(uvw[0][0] * prep.tw), prep.tw),
                    floorMod((int) Math.floor(uvw[0][1] * prep.th), prep.th));
            int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
            return shadedRgb(r, g, b, shade(prep));
        }

        /**
         * @param r red
         * @param g green
         * @param b blue
         * @param shade the shade factor
         * @return an opaque shaded pixel
         */
        private int shadedRgb(int r, int g, int b, double shade)
        {
            int rr = (int) Math.min(255, r * shade);
            int gg = (int) Math.min(255, g * shade);
            int bb = (int) Math.min(255, b * shade);
            return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
        }

        /**
         * @param a the first corner
         * @param b the second corner
         * @param rgb the edge colour
         */
        private void drawEdge(int a, int b, int rgb)
        {
            double dx = c[b][0] - c[a][0], dy = c[b][1] - c[a][1];
            int steps = edgeSteps(dx, dy);
            for (int i = 0; i <= steps; i++)
                plotStep(a, b, dx, dy, steps, i, rgb);
        }

        /**
         * @param dx the edge's x span
         * @param dy the edge's y span
         * @return how many pixels to walk, at least 1
         */
        private int edgeSteps(double dx, double dy)
        {
            int steps = (int) Math.max(Math.abs(dx), Math.abs(dy));
            if (steps < 1)
                return 1;
            return steps;
        }

        /**
         * @param a the first corner
         * @param b the second corner
         * @param dx the x span
         * @param dy the y span
         * @param steps the walk length
         * @param i the step
         * @param rgb the edge colour
         */
        private void plotStep(int a, int b, double dx, double dy, int steps, int i, int rgb)
        {
            double t = i / (double) steps;
            int x = (int) Math.round(c[a][0] + dx * t);
            int y = (int) Math.round(c[a][1] + dy * t);
            double zz = c[a][2] + (c[b][2] - c[a][2]) * t;
            plot(x, y, zz, rgb);
        }

        /**
         * @param x the pixel column
         * @param y the pixel row
         * @param zz the depth
         * @param rgb the colour
         */
        private void plot(int x, int y, double zz, int rgb)
        {
            if (!onScreen(x, y))
                return;
            writePixel(x, y, zz, rgb);
        }

        /**
         * @param x the pixel column
         * @param y the pixel row
         * @return true when the pixel is inside the image
         */
        private boolean onScreen(int x, int y)
        {
            if (!axisOn(x, width))
                return false;
            return axisOn(y, height);
        }

        /**
         * @param v the coordinate
         * @param limit the image size on that axis
         * @return true when {@code v} is inside {@code [0, limit)}
         */
        private boolean axisOn(int v, int limit)
        {
            if (v < 0)
                return false;
            return v < limit;
        }

        /**
         * @param x the pixel column
         * @param y the pixel row
         * @param zz the depth
         * @param rgb the colour
         */
        private void writePixel(int x, int y, double zz, int rgb)
        {
            int i = y * width + x;
            if (zz >= zbuf[i])
                return;
            zbuf[i] = zz;
            img.setRGB(x, y, rgb);
        }
    }

    /** Values one mesh needs while its triangles are drawn. */
    private static final class Prepared
    {
        private float[] pos;
        private float[] uv;
        private BufferedImage tex;
        private int tw;
        private int th;
        private float[] uvMat;
        private boolean wire;
        private boolean billboard;
        private double[] bill;
        private float[] rawLocal;
        private double[] wscale;
        private double posScale;
    }

    // A fitted orthographic camera: the model centre and a scale that fills ~80% of the image.
    private static final class Camera
    {
        final float[] centre;
        final double scale;

        Camera(float[] centre, double scale)
        {
            this.centre = centre;
            this.scale = scale;
        }
    }

    private static void accumulateBounds(List<float[]> positions, float[] min, float[] max)
    {
        for (float[] p : positions)
            for (int i = 0; i < p.length; i += 3)
                for (int c = 0; c < 3; c++)
                {
                    min[c] = Math.min(min[c], p[i + c]);
                    max[c] = Math.max(max[c], p[i + c]);
                }
    }

    private static Camera frameCamera(float[] min, float[] max, int width, int height)
    {
        if (min[0] > max[0])
            return null;
        float[] centre = { (min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2 };
        float extent = Math.max(max[0] - min[0], Math.max(max[1] - min[1], max[2] - min[2]));
        if (extent <= 0)
            extent = 1;
        return new Camera(centre, Math.min(width, height) * 0.8 / extent);
    }

    // Projects a billboard node's bind-pose world pivot to screen space {screenX, screenY, depth}; the
    // sprite's local geometry is then laid out around this point without the camera's orientation.
    private static double[] projectPivot(double[] p, Camera cam,
            double cy, double sy, double cp, double sp, int width, int height)
    {
        double x = p[0] - cam.centre[0], y = p[1] - cam.centre[1], z = p[2] - cam.centre[2];
        double x1 = cy * x + sy * z, z1 = -sy * x + cy * z, y1 = y;
        double y2 = cp * y1 - sp * z1, z2 = sp * y1 + cp * z1;
        return new double[] { width / 2.0 + x1 * cam.scale, height / 2.0 - y2 * cam.scale, z2 };
    }

    private static BufferedImage textureFor(Model.Mesh mesh, TextureSet textures, NitroAnimation.Frame frame,
            int meshIndex, Map<String, BufferedImage> cache)
    {
        if (textures == null)
            return null;
        // A texture-pattern (NSBTP) frame overrides which texture/palette this mesh samples.
        String[] override = frame != null ? frame.textureOverrideFor(meshIndex) : null;
        String texName = override != null ? override[0] : null;
        String pltName = override != null ? override[1] : null;
        if (texName == null)
        {
            Model.Material mat = mesh.getMaterial();
            if (mat == null || mat.getTextureName() == null)
                return null;
            texName = mat.getTextureName();
            pltName = mat.getPaletteName();
        }
        String key = texName + " " + pltName;
        final String tName = texName, pName = pltName;
        return cache.computeIfAbsent(key, k -> {
            TextureSet.Texture t = textures.getTexture(tName);
            if (t == null)
                return null;
            try
            {
                TextureSet.Palette p = pName != null ? textures.getPalette(pName) : null;
                return p != null ? textures.getImage(t, p) : textures.getImage(t);
            }
            catch (RuntimeException e)
            {
                return null;
            }
        });
    }

    private static double shadeFor(double[][] c, double[] light)
    {
        // screen-space face normal, two-sided; ambient + lambert
        double ax = c[1][0] - c[0][0], ay = c[1][1] - c[0][1], az = c[1][2] - c[0][2];
        double bx = c[2][0] - c[0][0], by = c[2][1] - c[0][1], bz = c[2][2] - c[0][2];
        double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl == 0)
            return 1;
        double diff = Math.abs((nx * light[0] + ny * light[1] + nz * light[2]) / nl);
        return 0.45 + 0.55 * diff;
    }

    private static void rasterize(BufferedImage img, double[] z, int w, int h, double[][] c, double[][] uv,
            BufferedImage tex, int tw, int th, double shade)
    {
        int minx = Math.max(0, (int) Math.floor(Math.min(c[0][0], Math.min(c[1][0], c[2][0]))));
        int maxx = Math.min(w - 1, (int) Math.ceil(Math.max(c[0][0], Math.max(c[1][0], c[2][0]))));
        int miny = Math.max(0, (int) Math.floor(Math.min(c[0][1], Math.min(c[1][1], c[2][1]))));
        int maxy = Math.min(h - 1, (int) Math.ceil(Math.max(c[0][1], Math.max(c[1][1], c[2][1]))));
        double d = (c[1][1] - c[2][1]) * (c[0][0] - c[2][0]) + (c[2][0] - c[1][0]) * (c[0][1] - c[2][1]);
        if (Math.abs(d) < 1e-9)
            return;
        for (int y = miny; y <= maxy; y++)
            for (int x = minx; x <= maxx; x++)
            {
                double px = x + 0.5, py = y + 0.5;
                double w0 = ((c[1][1] - c[2][1]) * (px - c[2][0]) + (c[2][0] - c[1][0]) * (py - c[2][1])) / d;
                double w1 = ((c[2][1] - c[0][1]) * (px - c[2][0]) + (c[0][0] - c[2][0]) * (py - c[2][1])) / d;
                double w2 = 1 - w0 - w1;
                if (w0 < -1e-6 || w1 < -1e-6 || w2 < -1e-6)
                    continue;
                double zz = w0 * c[0][2] + w1 * c[1][2] + w2 * c[2][2];
                int i = y * w + x;
                if (zz >= z[i])
                    continue;
                int r, gg, b;
                if (tex != null)
                {
                    // interpolate normalised UV, then scale to texel space for nearest-neighbour lookup
                    double s = (w0 * uv[0][0] + w1 * uv[1][0] + w2 * uv[2][0]) * tw;
                    double tt = (w0 * uv[0][1] + w1 * uv[1][1] + w2 * uv[2][1]) * th;
                    int argb = tex.getRGB(floorMod((int) Math.floor(s), tw), floorMod((int) Math.floor(tt), th));
                    if ((argb >>> 24) < 128)
                        continue; // alpha test
                    r = (argb >> 16) & 0xFF;
                    gg = (argb >> 8) & 0xFF;
                    b = argb & 0xFF;
                }
                else
                {
                    r = 200;
                    gg = 205;
                    b = 215;
                }
                r = (int) Math.min(255, r * shade);
                gg = (int) Math.min(255, gg * shade);
                b = (int) Math.min(255, b * shade);
                z[i] = zz;
                img.setRGB(x, y, 0xFF000000 | (r << 16) | (gg << 8) | b);
            }
    }

    private static int floorMod(int a, int m)
    {
        if (m <= 0)
            return 0;
        int r = a % m;
        return r < 0 ? r + m : r;
    }

    private static double[] normalise(double x, double y, double z)
    {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[] { x / l, y / l, z / l };
    }
}
