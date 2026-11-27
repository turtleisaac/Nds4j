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
 * a DS-style alpha test. For a rich, viewer-grade export use {@link GltfExporter}.
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

    private static BufferedImage rasterizeFrame(Model model, List<float[]> positions, TextureSet textures,
            NitroAnimation.Frame frame, int width, int height,
            double yawDegrees, double pitchDegrees, Camera cam)
    {
        BufferedImage img = backdrop(width, height);
        if (cam == null)
            return img;

        paintMeshes(model, positions, textures, frame, width, height, yawDegrees, pitchDegrees, cam, img);

        return img;
    }

    /**
     * @param width image width
     * @param height image height
     * @return the gradient backdrop
     * @see "NitroSystem g3d"
     */
    private static BufferedImage backdrop(int width, int height)
    {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
            fillRow(img, y, width, height);

        return img;
    }

    /**
     * @param img the image
     * @param y row
     * @param width image width
     * @param height image height
     * @see "NitroSystem g3d"
     */
    private static void fillRow(BufferedImage img, int y, int width, int height)
    {
        int g = 248 - (int) (26.0 * y / height);
        int rgb = 0xFF000000 | (g << 16) | (g << 8) | Math.min(255, g + 6);
        for (int x = 0; x < width; x++)
            img.setRGB(x, y, rgb);
    }

    /**
     * @param model the model
     * @param positions posed positions
     * @param textures textures, or null
     * @param frame animation frame, or null
     * @param width image width
     * @param height image height
     * @param yawDegrees camera yaw
     * @param pitchDegrees camera pitch
     * @param cam fitted camera
     * @param img destination
     * @see "NitroSystem g3d/src/sbc.c NNS_G3dDraw"
     */
    private static void paintMeshes(Model model, List<float[]> positions, TextureSet textures, NitroAnimation.Frame frame,
            int width, int height, double yawDegrees, double pitchDegrees, Camera cam, BufferedImage img)
    {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double[] zbuf = new double[width * height];
        java.util.Arrays.fill(zbuf, Double.POSITIVE_INFINITY);
        Map<String, BufferedImage> texCache = new HashMap<>();
        for (int m = 0; m < model.getMeshes().size(); m++)
            paintMesh(model, positions, textures, frame, width, height, cam, img, zbuf, texCache,
                    Math.cos(yaw), Math.sin(yaw), Math.cos(pitch), Math.sin(pitch), normalise(0.35, 0.5, 0.78), m);
    }

    /**
     * @param model the model
     * @param positions posed positions
     * @param textures textures, or null
     * @param frame animation frame, or null
     * @param width image width
     * @param height image height
     * @param cam fitted camera
     * @param img destination
     * @param zbuf depth buffer
     * @param texCache decoded textures
     * @param cy cosine of yaw
     * @param sy sine of yaw
     * @param cp cosine of pitch
     * @param sp sine of pitch
     * @param light light direction
     * @param m mesh index
     * @see "NitroSystem g3d/src/sbc.c NNS_G3dDraw"
     */
    private static void paintMesh(Model model, List<float[]> positions, TextureSet textures, NitroAnimation.Frame frame,
            int width, int height, Camera cam, BufferedImage img, double[] zbuf, Map<String, BufferedImage> texCache,
            double cy, double sy, double cp, double sp, double[] light, int m)
    {
        if (hidden(frame, m))
            return;

        Model.Mesh mesh = model.getMeshes().get(m);
        BufferedImage tex = textureFor(mesh, textures, frame, m, texCache);
        int tw = widthOf(tex);
        int th = heightOf(tex);
        boolean billboard = model.isBillboardNode(mesh.getNodeIndex());
        double[] bill = billboardPoint(model, frame, mesh, m, cam, cy, sy, cp, sp, width, height, billboard);
        float[] rawLocal = rawOf(mesh, billboard);
        Vec3 wscale = scaleOf(model, mesh, billboard);
        double posScale = scaleFactor(model, billboard);
        TexAffine uvMat = uvOf(frame, m);
        paintTris(mesh.getTriangleIndices(), positions.get(m), mesh.getTexcoords(), billboard, rawLocal, wscale, posScale,
                bill, cam, cy, sy, cp, sp, width, height, tw, th, uvMat, img, zbuf, tex, light);
    }

    /**
     * @param idx triangle indices
     * @param pos posed positions
     * @param uv mesh texcoords
     * @param billboard true for a camera-facing node
     * @param rawLocal raw positions, or null
     * @param wscale node scale, or null
     * @param posScale model position scale
     * @param bill projected pivot, or null
     * @param cam fitted camera
     * @param cy cosine of yaw
     * @param sy sine of yaw
     * @param cp cosine of pitch
     * @param sp sine of pitch
     * @param width image width
     * @param height image height
     * @param tw texture width
     * @param th texture height
     * @param uvMat texture matrix, or null
     * @param img destination
     * @param zbuf depth buffer
     * @param tex texture, or null
     * @param light light direction
     * @see "NitroSystem g3d/src/sbc.c NNS_G3dDraw"
     */
    private static void paintTris(int[] idx, float[] pos, float[] uv, boolean billboard, float[] rawLocal, Vec3 wscale,
            double posScale, double[] bill, Camera cam, double cy, double sy, double cp, double sp, int width,
            int height, int tw, int th, TexAffine uvMat, BufferedImage img, double[] zbuf, BufferedImage tex,
            double[] light)
    {
        double[][] c = new double[3][3];
        double[][] uvw = new double[3][2];
        for (int t = 0; t + 2 < idx.length; t += 3)
            paintTri(c, uvw, idx, t, pos, uv, billboard, rawLocal, wscale, posScale, bill, cam, cy, sy, cp, sp, width,
                    height, tw, th, uvMat, img, zbuf, tex, light);
    }

    /**
     * @param frame animation frame, or null
     * @param m mesh index
     * @return true when the frame hides the mesh
     * @see "NitroSystem g3d/src/anm/nsbva.c"
     */
    private static boolean hidden(NitroAnimation.Frame frame, int m)
    {
        if (frame == null)
            return false;

        return !frame.isVisible(m);
    }

    /**
     * @param tex a texture, or null
     * @return its width, or zero
     * @see "GBATEK DS 3D Video, TEXIMAGE_PARAM"
     */
    private static int widthOf(BufferedImage tex)
    {
        if (tex == null)
            return 0;

        return tex.getWidth();
    }

    /**
     * @param tex a texture, or null
     * @return its height, or zero
     * @see "GBATEK DS 3D Video, TEXIMAGE_PARAM"
     */
    private static int heightOf(BufferedImage tex)
    {
        if (tex == null)
            return 0;

        return tex.getHeight();
    }

    /**
     * @param mesh a mesh
     * @param billboard true for a camera-facing node
     * @return the raw positions, or null
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_BB"
     */
    private static float[] rawOf(Model.Mesh mesh, boolean billboard)
    {
        if (!billboard)
            return null;

        return mesh.getRawPositions();
    }

    /**
     * @param model the model
     * @param mesh a mesh
     * @param billboard true for a camera-facing node
     * @return the node scale, or null
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_BB"
     */
    private static Vec3 scaleOf(Model model, Model.Mesh mesh, boolean billboard)
    {
        if (!billboard)
            return null;

        return model.getNodeWorldScale(mesh.getNodeIndex());
    }

    /**
     * @param model the model
     * @param billboard true for a camera-facing node
     * @return the position scale, or one
     * @see "NitroSystem include/nnsys/g3d/binres/res_struct.h NNSG3dResMdlInfo"
     */
    private static double scaleFactor(Model model, boolean billboard)
    {
        if (!billboard)
            return 1;

        return model.getPositionScale();
    }

    /**
     * @param frame animation frame, or null
     * @param m mesh index
     * @return the texture matrix, or null
     * @see "NitroSystem g3d/src/anm/nsbta.c NNS_G3dAnmObjCalcTexSRT"
     */
    private static TexAffine uvOf(NitroAnimation.Frame frame, int m)
    {
        if (frame == null)
            return null;

        return frame.uvMatrixFor(m);
    }

    /**
     * @param model the model
     * @param frame animation frame, or null
     * @param mesh a mesh
     * @param cam fitted camera
     * @param cy cosine of yaw
     * @param sy sine of yaw
     * @param cp cosine of pitch
     * @param sp sine of pitch
     * @param width image width
     * @param height image height
     * @param billboard true for a camera-facing node
     * @return the projected pivot, or null
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_BB"
     */
    private static double[] billboardPoint(Model model, NitroAnimation.Frame frame, Model.Mesh mesh, int m, Camera cam,
            double cy, double sy, double cp, double sp, int width, int height, boolean billboard)
    {
        if (!billboard)
            return null;

        return projectPivot(pivotOf(model, frame, mesh, m), cam, cy, sy, cp, sp, width, height);
    }

    /**
     * @param c three screen corners
     * @param uvw three texture coordinates
     * @param idx triangle indices
     * @param t index of the triangle's first index
     * @param pos posed positions
     * @param uv mesh texcoords
     * @param billboard true for a camera-facing node
     * @param rawLocal raw positions, or null
     * @param wscale node scale, or null
     * @param posScale model position scale
     * @param bill projected pivot, or null
     * @param cam fitted camera
     * @param cy cosine of yaw
     * @param sy sine of yaw
     * @param cp cosine of pitch
     * @param sp sine of pitch
     * @param width image width
     * @param height image height
     * @param tw texture width
     * @param th texture height
     * @param uvMat texture matrix, or null
     * @param img destination
     * @param zbuf depth buffer
     * @param tex texture, or null
     * @param light light direction
     * @see "NitroSystem g3d/src/sbc.c NNS_G3dDraw"
     */
    private static void paintTri(double[][] c, double[][] uvw, int[] idx, int t, float[] pos, float[] uv,
            boolean billboard, float[] rawLocal, Vec3 wscale, double posScale, double[] bill, Camera cam,
            double cy, double sy, double cp, double sp, int width, int height, int tw, int th, TexAffine uvMat,
            BufferedImage img, double[] zbuf, BufferedImage tex, double[] light)
    {
        for (int k = 0; k < 3; k++)
            placeCorner(c, uvw, k, idx[t + k], pos, uv, billboard, rawLocal, wscale, posScale, bill, cam, cy, sy, cp, sp,
                    width, height, tw, th, uvMat);

        rasterize(img, zbuf, width, height, c, uvw, tex, tw, th, shade(billboard, c, light));
    }

    /**
     * @param billboard true for a camera-facing node
     * @param c three screen corners
     * @param light light direction
     * @return the triangle shade
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_BB"
     */
    private static double shade(boolean billboard, double[][] c, double[] light)
    {
        if (billboard)
            return 1;

        return shadeFor(c, light);
    }

    /**
     * Places one corner of a triangle in screen space and its texture coordinate.
     * @param c screen corners
     * @param uvw texture coordinates
     * @param k corner index
     * @param v vertex index
     * @param pos posed positions
     * @param uv mesh texcoords
     * @param billboard true for a camera-facing node
     * @param rawLocal raw positions, or null
     * @param wscale node scale, or null
     * @param posScale model position scale
     * @param bill projected pivot, or null
     * @param cam fitted camera
     * @param cy cosine of yaw
     * @param sy sine of yaw
     * @param cp cosine of pitch
     * @param sp sine of pitch
     * @param width image width
     * @param height image height
     * @param tw texture width
     * @param th texture height
     * @param uvMat texture matrix, or null
     * @see "NitroSystem g3d/src/sbc.c NNS_G3dDraw"
     */
    private static void placeCorner(double[][] c, double[][] uvw, int k, int v, float[] pos, float[] uv,
            boolean billboard, float[] rawLocal, Vec3 wscale, double posScale, double[] bill, Camera cam,
            double cy, double sy, double cp, double sp, int width, int height, int tw, int th, TexAffine uvMat)
    {
        if (billboard)
            billboardCorner(c, k, v, rawLocal, wscale, posScale, bill, cam);
        else
            cameraCorner(c, k, v, pos, cam, cy, sy, cp, sp, width, height);

        double[] st = texCorner(uv, v, tw, th, uvMat);
        uvw[k][0] = st[0];
        uvw[k][1] = st[1];
    }

    /**
     * @param c screen corners
     * @param k corner index
     * @param v vertex index
     * @param rawLocal raw positions
     * @param wscale node scale
     * @param posScale model position scale
     * @param bill projected pivot
     * @param cam fitted camera
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_BB"
     */
    private static void billboardCorner(double[][] c, int k, int v, float[] rawLocal, Vec3 wscale, double posScale,
            double[] bill, Camera cam)
    {
        double lx = rawLocal[v * 3] * wscale.x * posScale;
        double ly = rawLocal[v * 3 + 1] * wscale.y * posScale;
        double lz = rawLocal[v * 3 + 2] * wscale.z * posScale;
        c[k][0] = bill[0] + lx * cam.scale;
        c[k][1] = bill[1] - ly * cam.scale;
        c[k][2] = bill[2] - lz * cam.scale;
    }

    /**
     * @param c screen corners
     * @param k corner index
     * @param v vertex index
     * @param pos posed positions
     * @param cam fitted camera
     * @param cy cosine of yaw
     * @param sy sine of yaw
     * @param cp cosine of pitch
     * @param sp sine of pitch
     * @param width image width
     * @param height image height
     * @see "NitroSystem g3d/src/sbc.c NNS_G3dDraw"
     */
    private static void cameraCorner(double[][] c, int k, int v, float[] pos, Camera cam, double cy, double sy,
            double cp, double sp, int width, int height)
    {
        // Offset from the camera centre.
        double x = pos[v * 3] - cam.centre[0];
        double y = pos[v * 3 + 1] - cam.centre[1];
        double z = pos[v * 3 + 2] - cam.centre[2];

        // Yaw, then pitch.
        double x1 = cy * x + sy * z;
        double z1 = -sy * x + cy * z;
        double y2 = cp * y - sp * z1;
        double z2 = sp * y + cp * z1;

        // Screen position and depth.
        c[k][0] = width / 2.0 + x1 * cam.scale;
        c[k][1] = height / 2.0 - y2 * cam.scale;
        c[k][2] = z2;
    }

    /**
     * @param uv mesh texcoords
     * @param v vertex index
     * @param tw texture width
     * @param th texture height
     * @param uvMat texture matrix, or null
     * @return normalised S and T
     * @see "NitroSystem g3d/src/anm/nsbta.c NNS_G3dAnmObjCalcTexSRT"
     */
    private static double[] texCorner(float[] uv, int v, int tw, int th, TexAffine uvMat)
    {
        double s = normalised(uv[v * 2], tw);
        double t = normalised(uv[v * 2 + 1], th);
        if (uvMat == null)
            return new double[] { s, t };

        return new double[] { uvMat.a * s + uvMat.c * t + uvMat.e, uvMat.b * s + uvMat.d * t + uvMat.f };
    }

    /**
     * @param texel a texel coordinate
     * @param size texture size on that axis
     * @return the coordinate in 0..1 when the texture has a size
     * @see "GBATEK DS 3D Video, TEXCOORD"
     */
    private static double normalised(float texel, int size)
    {
        if (size <= 0)
            return texel;

        return texel / size;
    }

    /**
     * @param model the model
     * @param frame animation frame, or null
     * @param mesh a mesh
     * @param m mesh index
     * @return the billboard pivot
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_BB"
     */
    private static Vec3 pivotOf(Model model, NitroAnimation.Frame frame, Model.Mesh mesh, int m)
    {
        Vec3 posed = posedPivot(frame, m);
        if (posed != null)
            return posed;

        return model.getNodeWorldTranslation(mesh.getNodeIndex());
    }

    /**
     * @param frame animation frame, or null
     * @param m mesh index
     * @return the posed pivot, or null
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_BB"
     */
    private static Vec3 posedPivot(NitroAnimation.Frame frame, int m)
    {
        if (frame == null)
            return null;

        return frame.billboardPivotFor(m);
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
    private static double[] projectPivot(Vec3 p, Camera cam,
            double cy, double sy, double cp, double sp, int width, int height)
    {
        double x = p.x - cam.centre[0], y = p.y - cam.centre[1], z = p.z - cam.centre[2];
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
