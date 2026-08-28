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
 * Render a model at its bind pose, or pass the per-mesh positions from {@link Model#pose} to render an
 * animation frame.
 */
public final class SoftwareRenderer
{
    private SoftwareRenderer() {}

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
        List<float[]> positions = new java.util.ArrayList<>();
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
        List<Model.Mesh> meshes = model.getMeshes();
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
        {
            int g = 248 - (int) (26.0 * y / height);
            int rgb = 0xFF000000 | (g << 16) | (g << 8) | Math.min(255, g + 6);
            for (int x = 0; x < width; x++)
                img.setRGB(x, y, rgb);
        }

        // frame the model: centre on its bounding box, scale to fill ~80% of the image
        float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (float[] p : positions)
            for (int i = 0; i < p.length; i += 3)
                for (int c = 0; c < 3; c++)
                {
                    min[c] = Math.min(min[c], p[i + c]);
                    max[c] = Math.max(max[c], p[i + c]);
                }
        if (min[0] > max[0])
            return img; // nothing to draw
        float[] centre = {(min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2};
        float extent = Math.max(max[0] - min[0], Math.max(max[1] - min[1], max[2] - min[2]));
        if (extent <= 0)
            extent = 1;

        double yaw = Math.toRadians(yawDegrees), pitch = Math.toRadians(pitchDegrees);
        double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        double scale = Math.min(width, height) * 0.8 / extent;
        double[] light = normalise(0.35, 0.5, 0.78);

        double[] zbuf = new double[width * height];
        java.util.Arrays.fill(zbuf, Double.POSITIVE_INFINITY);
        Map<String, BufferedImage> texCache = new HashMap<>();

        double[][] c = new double[3][3];
        double[][] uvw = new double[3][2];
        for (int m = 0; m < meshes.size(); m++)
        {
            Model.Mesh mesh = meshes.get(m);
            float[] pos = positions.get(m);
            float[] uv = mesh.getTexcoords();
            int[] idx = mesh.getTriangleIndices();
            BufferedImage tex = textureFor(mesh, textures, texCache);
            int tw = tex != null ? tex.getWidth() : 0, th = tex != null ? tex.getHeight() : 0;

            for (int t = 0; t + 2 < idx.length; t += 3)
            {
                for (int k = 0; k < 3; k++)
                {
                    int v = idx[t + k];
                    double x = pos[v * 3] - centre[0], y = pos[v * 3 + 1] - centre[1], z = pos[v * 3 + 2] - centre[2];
                    double x1 = cy * x + sy * z, z1 = -sy * x + cy * z, y1 = y;
                    double y2 = cp * y1 - sp * z1, z2 = sp * y1 + cp * z1;
                    c[k][0] = width / 2.0 + x1 * scale;
                    c[k][1] = height / 2.0 - y2 * scale;
                    c[k][2] = z2;
                    uvw[k][0] = uv[v * 2];
                    uvw[k][1] = uv[v * 2 + 1];
                }
                double shade = shadeFor(c, light);
                rasterize(img, zbuf, width, height, c, uvw, tex, tw, th, shade);
            }
        }
        return img;
    }

    private static BufferedImage textureFor(Model.Mesh mesh, TextureSet textures, Map<String, BufferedImage> cache)
    {
        Model.Material mat = mesh.getMaterial();
        if (textures == null || mat == null || mat.getTextureName() == null)
            return null;
        return cache.computeIfAbsent(mat.getTextureName(), name -> {
            TextureSet.Texture t = textures.getTexture(name);
            if (t == null)
                return null;
            try { return textures.getImage(t); }
            catch (RuntimeException e) { return null; }
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
                    double s = w0 * uv[0][0] + w1 * uv[1][0] + w2 * uv[2][0];
                    double tt = w0 * uv[0][1] + w1 * uv[1][1] + w2 * uv[2][1];
                    int argb = tex.getRGB(floorMod((int) Math.floor(s), tw), floorMod((int) Math.floor(tt), th));
                    if ((argb >>> 24) < 128)
                        continue; // alpha test
                    r = (argb >> 16) & 0xFF; gg = (argb >> 8) & 0xFF; b = argb & 0xFF;
                }
                else { r = 200; gg = 205; b = 215; }
                r = (int) Math.min(255, r * shade); gg = (int) Math.min(255, gg * shade); b = (int) Math.min(255, b * shade);
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
        return new double[]{x / l, y / l, z / l};
    }
}
