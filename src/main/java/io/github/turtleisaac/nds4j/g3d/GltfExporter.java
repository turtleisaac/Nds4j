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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports a decoded {@link Model} to a self-contained <b>glTF 2.0</b> document: geometry, per-shape UVs
 * and materials, and the referenced {@code TEX0} textures as embedded PNGs, all inlined as
 * {@code data:} URIs so a single {@code .gltf} file opens in any glTF viewer with nothing beside it.
 * <p>
 * This is the "viewable-as-intended" export the OBJ path (see {@link Model#toObj()}) can't be:
 * {@code MDL0} materials are wired to their {@code TEX0} textures, the display-list texture coordinates
 * are normalised against each texture's true size, and the DS wrap/flip and alpha-test behaviour map to
 * glTF sampler wrap modes and {@code MASK} alpha. It is pure Java with no native dependency (see
 * {@code TECH_DEBT.md} &sect;3): geometry is written to a base64 buffer and textures are PNG-encoded via
 * {@link ImageIO}.
 * <p>
 * Positions are in model space (already node-placed); a model whose parts need an animation to pose are
 * exported in their bind pose (animation is layered separately).
 */
public final class GltfExporter
{
    private GltfExporter() {}

    /**
     * Writes {@code model} as a self-contained glTF 2.0 file. Textures the model's materials reference
     * are decoded from {@code textures} (typically {@link ModelSet#getEmbeddedTextures()}); pass
     * {@code null} to export geometry with plain materials only.
     * @param model the model to export
     * @param textures the texture archive to resolve material textures against, or null
     * @param out the {@code .gltf} file to write
     * @throws IOException if the file cannot be written
     */
    public static void write(Model model, TextureSet textures, File out) throws IOException
    {
        Files.write(out.toPath(), toGltf(model, textures).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Builds the glTF 2.0 JSON document for {@code model} as a string (buffers and textures embedded as
     * {@code data:} URIs).
     * @param model the model to export
     * @param textures the texture archive to resolve material textures against, or null
     * @return the glTF document as a {@code String}
     */
    public static String toGltf(Model model, TextureSet textures)
    {
        Build b = new Build(textures);
        for (Model.Mesh mesh : model.getMeshes())
            b.addMesh(mesh);
        return b.finish(model.getName());
    }

    /** DS animations are authored for 30 fps playback. */
    private static final double FRAME_RATE = 30.0;

    /**
     * Writes {@code model} as an <b>animated</b>, self-contained glTF 2.0 file: the model's skeleton is
     * emitted as a glTF node tree and each NSBCA animation becomes a glTF animation whose channels drive
     * the nodes' translation/rotation/scale &mdash; so the model actually <em>plays</em> (e.g. a walk
     * cycle) in any glTF viewer. An optional NSBTA supplies the initial texture-coordinate transform
     * ({@code KHR_texture_transform}). Textures resolve exactly as the static export.
     * @param model the model to export
     * @param textures the texture archive to resolve material textures against, or null
     * @param animations the NSBCA animations to embed (each becomes a glTF animation), may be empty
     * @param textureSrt an NSBTA whose first frame seeds {@code KHR_texture_transform}, or null
     * @param out the {@code .gltf} file to write
     * @throws IOException if the file cannot be written
     */
    public static void write(Model model, TextureSet textures, List<SkeletalAnimationSet.Animation> animations,
                             TextureSrtAnimationSet.Animation textureSrt, File out) throws IOException
    {
        Files.write(out.toPath(), toGltf(model, textures, animations, textureSrt).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Builds an animated glTF 2.0 document (skeleton node tree + NSBCA animation channels). See
     * {@link #write(Model, TextureSet, List, TextureSrtAnimationSet.Animation, File)}.
     * @param model the model to export
     * @param textures the texture archive, or null
     * @param animations the NSBCA animations to embed, may be null/empty
     * @param textureSrt an NSBTA whose first frame seeds {@code KHR_texture_transform}, or null
     * @return the glTF document as a {@code String}
     */
    public static String toGltf(Model model, TextureSet textures, List<SkeletalAnimationSet.Animation> animations,
                                TextureSrtAnimationSet.Animation textureSrt)
    {
        return new SkinnedBuild(model, textures, textureSrt).finish(
                animations != null ? animations : java.util.Collections.emptyList());
    }

    // Accumulates the binary buffer and the JSON arrays as meshes are added, then serialises everything.
    private static final class Build
    {
        private final TextureSet textures;
        private final ByteArrayOutputStream bin = new ByteArrayOutputStream();
        private final List<String> bufferViews = new ArrayList<>();
        private final List<String> accessors = new ArrayList<>();
        private final List<String> primitives = new ArrayList<>();
        private final List<String> materials = new ArrayList<>();
        private final List<String> images = new ArrayList<>();
        private final List<String> glTextures = new ArrayList<>();
        private final List<String> samplers = new ArrayList<>();
        private final Map<String, Integer> imageByTexture = new LinkedHashMap<>();
        private final Map<String, Integer> samplerByKey = new LinkedHashMap<>();
        private int defaultMaterial = -1;

        Build(TextureSet textures) { this.textures = textures; }

        void addMesh(Model.Mesh mesh)
        {
            int nVerts = mesh.getVertexCount();
            int[] idx = mesh.getTriangleIndices();
            if (nVerts == 0 || idx.length == 0)
                return;

            float[] pos = mesh.getPositions();
            int material = materialFor(mesh);
            BufferedImage tex = textureFor(mesh);
            int texW = tex != null ? tex.getWidth() : 1;
            int texH = tex != null ? tex.getHeight() : 1;

            // POSITION (with the min/max the spec requires) and TEXCOORD_0, normalised to [0,1].
            float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
            float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
            int posAccessor = beginAccessor("VEC3", 5126, nVerts);
            for (int i = 0; i < nVerts; i++)
                for (int c = 0; c < 3; c++)
                {
                    float v = pos[i * 3 + c];
                    putFloat(v);
                    min[c] = Math.min(min[c], v);
                    max[c] = Math.max(max[c], v);
                }
            endAccessor(34962, "\"min\":[" + f(min[0]) + "," + f(min[1]) + "," + f(min[2]) + "],\"max\":["
                    + f(max[0]) + "," + f(max[1]) + "," + f(max[2]) + "]");

            float[] uv = mesh.getTexcoords();
            int uvAccessor = beginAccessor("VEC2", 5126, nVerts);
            for (int i = 0; i < nVerts; i++)
            {
                putFloat(uv[i * 2] / texW);
                putFloat(uv[i * 2 + 1] / texH);
            }
            endAccessor(34962, null);

            int idxAccessor = beginAccessor("SCALAR", 5125, idx.length);
            for (int v : idx)
                putInt(v);
            endAccessor(34963, null);

            primitives.add("{\"attributes\":{\"POSITION\":" + posAccessor + ",\"TEXCOORD_0\":" + uvAccessor
                    + "},\"indices\":" + idxAccessor + ",\"material\":" + material + "}");
        }

        // Emits a glTF material for this mesh's material, embedding its texture if one resolves. Materials
        // are not deduped (there are only a handful per model); textures and samplers are.
        private int materialFor(Model.Mesh mesh)
        {
            BufferedImage tex = textureFor(mesh);
            Model.Material mat = mesh.getMaterial();
            if (tex == null || mat == null)
                return defaultMaterial();

            int image = imageByTexture.computeIfAbsent(mat.getTextureName(), n -> {
                images.add("{\"uri\":\"data:image/png;base64," + pngDataUri(tex) + "\"}");
                int sampler = samplerFor(mat);
                glTextures.add("{\"source\":" + (images.size() - 1) + ",\"sampler\":" + sampler + "}");
                return glTextures.size() - 1;
            });

            materials.add("{\"name\":" + jsonString(mat.getName())
                    + ",\"pbrMetallicRoughness\":{\"baseColorTexture\":{\"index\":" + image
                    + "},\"metallicFactor\":0,\"roughnessFactor\":1},\"alphaMode\":\"MASK\",\"alphaCutoff\":0.5"
                    + ",\"doubleSided\":true}");
            return materials.size() - 1;
        }

        private int samplerFor(Model.Material mat)
        {
            int wrapS = mat.isFlipS() ? 33648 : mat.isRepeatS() ? 10497 : 33071; // MIRRORED / REPEAT / CLAMP
            int wrapT = mat.isFlipT() ? 33648 : mat.isRepeatT() ? 10497 : 33071;
            String key = wrapS + ":" + wrapT;
            return samplerByKey.computeIfAbsent(key, k -> {
                samplers.add("{\"wrapS\":" + wrapS + ",\"wrapT\":" + wrapT + "}");
                return samplers.size() - 1;
            });
        }

        private int defaultMaterial()
        {
            if (defaultMaterial < 0)
            {
                materials.add("{\"name\":\"default\",\"pbrMetallicRoughness\":{\"baseColorFactor\":[0.8,0.8,0.8,1],"
                        + "\"metallicFactor\":0,\"roughnessFactor\":1},\"doubleSided\":true}");
                defaultMaterial = materials.size() - 1;
            }
            return defaultMaterial;
        }

        private BufferedImage textureFor(Model.Mesh mesh)
        {
            Model.Material mat = mesh.getMaterial();
            if (textures == null || mat == null || mat.getTextureName() == null)
                return null;
            TextureSet.Texture t = textures.getTexture(mat.getTextureName());
            if (t == null)
                return null;
            try { return textures.getImage(t); }
            catch (RuntimeException e) { return null; }
        }

        // --- binary buffer / accessor plumbing ---

        private int beginAccessor(String type, int componentType, int count)
        {
            pad4();
            accessors.add("__PENDING__" + type + "|" + componentType + "|" + count + "|" + bin.size());
            return accessors.size() - 1;
        }

        private void endAccessor(int target, String extra)
        {
            int i = accessors.size() - 1;
            String[] parts = accessors.get(i).substring("__PENDING__".length()).split("\\|");
            String type = parts[0];
            int componentType = Integer.parseInt(parts[1]);
            int count = Integer.parseInt(parts[2]);
            int start = Integer.parseInt(parts[3]);
            int length = bin.size() - start;
            int view = bufferViews.size();
            bufferViews.add("{\"buffer\":0,\"byteOffset\":" + start + ",\"byteLength\":" + length
                    + ",\"target\":" + target + "}");
            accessors.set(i, "{\"bufferView\":" + view + ",\"componentType\":" + componentType
                    + ",\"count\":" + count + ",\"type\":\"" + type + "\""
                    + (extra != null ? "," + extra : "") + "}");
        }

        private void pad4()
        {
            while ((bin.size() & 3) != 0)
                bin.write(0);
        }

        private void putFloat(float v)
        {
            int bits = Float.floatToIntBits(v);
            bin.write(bits); bin.write(bits >> 8); bin.write(bits >> 16); bin.write(bits >> 24);
        }

        private void putInt(int v)
        {
            bin.write(v); bin.write(v >> 8); bin.write(v >> 16); bin.write(v >> 24);
        }

        String finish(String name)
        {
            byte[] buffer = bin.toByteArray();
            List<String> nodes = new ArrayList<>();
            List<String> sceneNodes = new ArrayList<>();
            // One mesh with all primitives, under one node.
            nodes.add("{\"mesh\":0,\"name\":" + jsonString(name) + "}");
            sceneNodes.add("0");

            StringBuilder sb = new StringBuilder();
            sb.append("{\"asset\":{\"version\":\"2.0\",\"generator\":\"Nds4j\"},");
            sb.append("\"scene\":0,\"scenes\":[{\"nodes\":[0]}],");
            sb.append("\"nodes\":[").append(String.join(",", nodes)).append("],");
            sb.append("\"meshes\":[{\"name\":").append(jsonString(name))
                    .append(",\"primitives\":[").append(String.join(",", primitives)).append("]}],");
            sb.append("\"accessors\":[").append(String.join(",", accessors)).append("],");
            sb.append("\"bufferViews\":[").append(String.join(",", bufferViews)).append("],");
            if (!materials.isEmpty())
                sb.append("\"materials\":[").append(String.join(",", materials)).append("],");
            if (!glTextures.isEmpty())
            {
                sb.append("\"textures\":[").append(String.join(",", glTextures)).append("],");
                sb.append("\"images\":[").append(String.join(",", images)).append("],");
                sb.append("\"samplers\":[").append(String.join(",", samplers)).append("],");
            }
            sb.append("\"buffers\":[{\"byteLength\":").append(buffer.length)
                    .append(",\"uri\":\"data:application/octet-stream;base64,")
                    .append(Base64.getEncoder().encodeToString(buffer)).append("\"}]}");
            return sb.toString();
        }

        private static String pngDataUri(BufferedImage img)
        {
            try
            {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(img, "png", out);
                return Base64.getEncoder().encodeToString(out.toByteArray());
            }
            catch (IOException e)
            {
                throw new RuntimeException("Failed to PNG-encode a texture for glTF export", e);
            }
        }

        // glTF numbers must not be NaN/Infinity and prefer a compact form.
        private static String f(float v)
        {
            if (Float.isNaN(v) || Float.isInfinite(v))
                v = 0;
            if (v == Math.rint(v) && Math.abs(v) < 1e7)
                return Integer.toString((int) v);
            return Float.toString(v);
        }

        private static String jsonString(String s)
        {
            if (s == null)
                return "\"\"";
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++)
            {
                char c = s.charAt(i);
                if (c == '"' || c == '\\') sb.append('\\').append(c);
                else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                else sb.append(c);
            }
            return sb.append('"').toString();
        }
    }

    // kept to make the little-endian intent explicit if a GLB path is added later
    static ByteBuffer le(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }

    // Shared JSON/number helpers, used by the animated builder below.
    private static String f(float v)
    {
        if (Float.isNaN(v) || Float.isInfinite(v)) v = 0;
        if (v == Math.rint(v) && Math.abs(v) < 1e7) return Integer.toString((int) v);
        return Float.toString(v);
    }

    private static String jsonString(String s)
    {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    private static String pngDataUri(BufferedImage img)
    {
        try
        {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        }
        catch (IOException e) { throw new RuntimeException("Failed to PNG-encode a texture for glTF export", e); }
    }

    // Converts a rotation held in NNS row-vector convention (v' = v * r, r row-major) to the unit
    // quaternion glTF wants for a column-vector node rotation (v' = R_gltf * v), i.e. the transpose.
    static float[] matrixToQuat(double[] r)
    {
        // M = r^T (column-vector rotation): M[i][j] = r[j*3+i]
        double m00 = r[0], m01 = r[3], m02 = r[6];
        double m10 = r[1], m11 = r[4], m12 = r[7];
        double m20 = r[2], m21 = r[5], m22 = r[8];
        double tr = m00 + m11 + m22;
        double x, y, z, w;
        if (tr > 0)
        {
            double s = Math.sqrt(tr + 1.0) * 2;
            w = 0.25 * s; x = (m21 - m12) / s; y = (m02 - m20) / s; z = (m10 - m01) / s;
        }
        else if (m00 > m11 && m00 > m22)
        {
            double s = Math.sqrt(1.0 + m00 - m11 - m22) * 2;
            w = (m21 - m12) / s; x = 0.25 * s; y = (m01 + m10) / s; z = (m02 + m20) / s;
        }
        else if (m11 > m22)
        {
            double s = Math.sqrt(1.0 + m11 - m00 - m22) * 2;
            w = (m02 - m20) / s; x = (m01 + m10) / s; y = 0.25 * s; z = (m12 + m21) / s;
        }
        else
        {
            double s = Math.sqrt(1.0 + m22 - m00 - m11) * 2;
            w = (m10 - m01) / s; x = (m02 + m20) / s; y = (m12 + m21) / s; z = 0.25 * s;
        }
        double n = Math.sqrt(x * x + y * y + z * z + w * w);
        if (n == 0) return new float[]{0, 0, 0, 1};
        return new float[]{(float) (x / n), (float) (y / n), (float) (z / n), (float) (w / n)};
    }

    // Builds a hierarchical, animatable glTF: one node per skeleton node (bind-pose TRS), geometry
    // placed under its node in local space (raw vertices * posScale), and one glTF animation per NSBCA.
    private static final class SkinnedBuild
    {
        private final Model model;
        private final TextureSet textures;
        private final TextureSrtAnimationSet.Animation textureSrt;
        private final ByteArrayOutputStream bin = new ByteArrayOutputStream();
        private final List<String> bufferViews = new ArrayList<>();
        private final List<String> accessors = new ArrayList<>();
        private final List<String> meshes = new ArrayList<>();
        private final List<String> materials = new ArrayList<>();
        private final List<String> images = new ArrayList<>();
        private final List<String> glTextures = new ArrayList<>();
        private final List<String> samplers = new ArrayList<>();
        private final Map<String, Integer> imageByTexture = new LinkedHashMap<>();
        private final Map<String, Integer> samplerByKey = new LinkedHashMap<>();
        private boolean usesTextureTransform = false;
        private final double posScale;

        SkinnedBuild(Model model, TextureSet textures, TextureSrtAnimationSet.Animation textureSrt)
        {
            this.model = model;
            this.textures = textures;
            this.textureSrt = textureSrt;
            this.posScale = model.getPositionScale();
        }

        String finish(List<SkeletalAnimationSet.Animation> animations)
        {
            int nodeCount = model.getNodeCount();

            // Group each shape's primitive JSON under its skeleton node.
            Map<Integer, List<String>> primsByNode = new LinkedHashMap<>();
            for (Model.Mesh mesh : model.getMeshes())
            {
                String prim = buildPrimitive(mesh);
                if (prim != null)
                    primsByNode.computeIfAbsent(mesh.getNodeIndex(), k -> new ArrayList<>()).add(prim);
            }
            // A glTF mesh per node that has geometry.
            Map<Integer, Integer> meshOfNode = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<String>> e : primsByNode.entrySet())
            {
                meshes.add("{\"primitives\":[" + String.join(",", e.getValue()) + "]}");
                meshOfNode.put(e.getKey(), meshes.size() - 1);
            }

            // Nodes: bind-pose TRS, children by parent link, mesh if any.
            Model.NodeTransform[] bind = model.localTransforms(null, 0);
            List<List<Integer>> children = new ArrayList<>();
            for (int i = 0; i < nodeCount; i++) children.add(new ArrayList<>());
            List<Integer> roots = new ArrayList<>();
            for (int n = 0; n < nodeCount; n++)
            {
                int p = model.getNodeParent(n);
                if (p >= 0 && p < nodeCount && p != n) children.get(p).add(n);
                else roots.add(n);
            }
            List<String> nodes = new ArrayList<>();
            for (int n = 0; n < nodeCount; n++)
            {
                StringBuilder nb = new StringBuilder("{");
                nb.append(trsJson(bind[n]));
                if (meshOfNode.containsKey(n)) nb.append(",\"mesh\":").append(meshOfNode.get(n));
                if (!children.get(n).isEmpty()) nb.append(",\"children\":[").append(join(children.get(n))).append("]");
                nb.append("}");
                nodes.add(nb.toString());
            }

            List<String> animJson = new ArrayList<>();
            for (SkeletalAnimationSet.Animation a : animations)
                animJson.add(buildAnimation(a, nodeCount, bind));

            return assemble(nodes, roots, animJson);
        }

        private String trsJson(Model.NodeTransform t)
        {
            double[] tr = t.getTranslation(), sc = t.getScale();
            float[] q = matrixToQuat(t.getRotation());
            // node translation includes the model position scale (geometry is raw*posScale in local space)
            return "\"translation\":[" + f((float) (tr[0])) + "," + f((float) (tr[1])) + "," + f((float) (tr[2])) + "]"
                    + ",\"rotation\":[" + f(q[0]) + "," + f(q[1]) + "," + f(q[2]) + "," + f(q[3]) + "]"
                    + ",\"scale\":[" + f((float) sc[0]) + "," + f((float) sc[1]) + "," + f((float) sc[2]) + "]";
        }

        // One glTF animation: for each node, a T/R/S sampler+channel, emitted only when that channel
        // actually moves (varies across frames, or its frame-0 value differs from the bind pose).
        private String buildAnimation(SkeletalAnimationSet.Animation a, int nodeCount, Model.NodeTransform[] bind)
        {
            int frames = Math.max(1, a.getFrameCount());
            // shared time input
            int timeAcc = beginAccessor("SCALAR", 5126, frames);
            float tmin = 0, tmax = (float) ((frames - 1) / FRAME_RATE);
            for (int fI = 0; fI < frames; fI++) putFloat((float) (fI / FRAME_RATE));
            endAccessor(0, "\"min\":[" + f(tmin) + "],\"max\":[" + f(tmax) + "]");

            // pre-sample every node's local transform per frame
            Model.NodeTransform[][] pose = new Model.NodeTransform[frames][];
            for (int fI = 0; fI < frames; fI++) pose[fI] = model.localTransforms(a, fI);

            List<String> samplersJson = new ArrayList<>();
            List<String> channels = new ArrayList<>();
            for (int n = 0; n < nodeCount; n++)
            {
                emitChannel(n, frames, pose, bind[n], timeAcc, 0, samplersJson, channels); // translation
                emitChannel(n, frames, pose, bind[n], timeAcc, 1, samplersJson, channels); // rotation
                emitChannel(n, frames, pose, bind[n], timeAcc, 2, samplersJson, channels); // scale
            }
            return "{\"name\":" + jsonString(a.getName()) + ",\"samplers\":[" + String.join(",", samplersJson)
                    + "],\"channels\":[" + String.join(",", channels) + "]}";
        }

        // kind: 0=translation (VEC3), 1=rotation (VEC4 quat, sign-continuous), 2=scale (VEC3)
        private void emitChannel(int node, int frames, Model.NodeTransform[][] pose, Model.NodeTransform bind,
                                 int timeAcc, int kind, List<String> samplersJson, List<String> channels)
        {
            int comps = kind == 1 ? 4 : 3;
            float[][] vals = new float[frames][];
            for (int fI = 0; fI < frames; fI++)
                vals[fI] = sampleComponent(pose[fI][node], kind);
            // sign-continuity for quaternions (avoid nlerp taking the long way)
            if (kind == 1)
                for (int fI = 1; fI < frames; fI++)
                {
                    double dot = 0;
                    for (int c = 0; c < 4; c++) dot += vals[fI][c] * vals[fI - 1][c];
                    if (dot < 0) for (int c = 0; c < 4; c++) vals[fI][c] = -vals[fI][c];
                }
            float[] bindVal = sampleComponent(bind, kind);
            if (!moves(vals, bindVal))
                return;

            int outAcc = beginAccessor(comps == 4 ? "VEC4" : "VEC3", 5126, frames);
            for (float[] v : vals) for (float c : v) putFloat(c);
            endAccessor(0, null);

            int samplerIdx = samplersJson.size();
            samplersJson.add("{\"input\":" + timeAcc + ",\"output\":" + outAcc + ",\"interpolation\":\"LINEAR\"}");
            String path = kind == 0 ? "translation" : kind == 1 ? "rotation" : "scale";
            channels.add("{\"sampler\":" + samplerIdx + ",\"target\":{\"node\":" + node + ",\"path\":\"" + path + "\"}}");
        }

        private float[] sampleComponent(Model.NodeTransform t, int kind)
        {
            if (kind == 0) { double[] v = t.getTranslation(); return new float[]{(float) v[0], (float) v[1], (float) v[2]}; }
            if (kind == 2) { double[] v = t.getScale(); return new float[]{(float) v[0], (float) v[1], (float) v[2]}; }
            return matrixToQuat(t.getRotation());
        }

        private static boolean moves(float[][] vals, float[] bind)
        {
            for (float[] v : vals)
                for (int c = 0; c < v.length; c++)
                    if (Math.abs(v[c] - bind[c]) > 1e-6f)
                        return true;
            return false;
        }

        // ---- geometry / material plumbing (parallel to Build, but grouped by node) ----

        private String buildPrimitive(Model.Mesh mesh)
        {
            int nVerts = mesh.getVertexCount();
            int[] idx = mesh.getTriangleIndices();
            if (nVerts == 0 || idx.length == 0)
                return null;

            float[] raw = mesh.getRawPositions();
            BufferedImage tex = textureFor(mesh);
            int texW = tex != null ? tex.getWidth() : 1;
            int texH = tex != null ? tex.getHeight() : 1;

            float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
            float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
            int posAccessor = beginAccessor("VEC3", 5126, nVerts);
            for (int i = 0; i < nVerts; i++)
                for (int c = 0; c < 3; c++)
                {
                    float v = (float) (raw[i * 3 + c] * posScale);
                    putFloat(v);
                    min[c] = Math.min(min[c], v);
                    max[c] = Math.max(max[c], v);
                }
            endAccessor(34962, "\"min\":[" + f(min[0]) + "," + f(min[1]) + "," + f(min[2]) + "],\"max\":["
                    + f(max[0]) + "," + f(max[1]) + "," + f(max[2]) + "]");

            float[] uv = mesh.getTexcoords();
            int uvAccessor = beginAccessor("VEC2", 5126, nVerts);
            for (int i = 0; i < nVerts; i++)
            {
                putFloat(uv[i * 2] / texW);
                putFloat(uv[i * 2 + 1] / texH);
            }
            endAccessor(34962, null);

            int idxAccessor = beginAccessor("SCALAR", 5125, idx.length);
            for (int v : idx) putInt(v);
            endAccessor(34963, null);

            int material = materialFor(mesh);
            return "{\"attributes\":{\"POSITION\":" + posAccessor + ",\"TEXCOORD_0\":" + uvAccessor
                    + "},\"indices\":" + idxAccessor + ",\"material\":" + material + "}";
        }

        private int materialFor(Model.Mesh mesh)
        {
            BufferedImage tex = textureFor(mesh);
            Model.Material mat = mesh.getMaterial();
            if (tex == null || mat == null)
            {
                materials.add("{\"name\":\"default\",\"pbrMetallicRoughness\":{\"baseColorFactor\":[0.8,0.8,0.8,1],"
                        + "\"metallicFactor\":0,\"roughnessFactor\":1},\"doubleSided\":true}");
                return materials.size() - 1;
            }
            int image = imageByTexture.computeIfAbsent(mat.getTextureName(), n -> {
                images.add("{\"uri\":\"data:image/png;base64," + pngDataUri(tex) + "\"}");
                glTextures.add("{\"source\":" + (images.size() - 1) + ",\"sampler\":" + samplerFor(mat) + "}");
                return glTextures.size() - 1;
            });
            String texTransform = textureTransform(mat.getName());
            materials.add("{\"name\":" + jsonString(mat.getName())
                    + ",\"pbrMetallicRoughness\":{\"baseColorTexture\":{\"index\":" + image + texTransform
                    + "},\"metallicFactor\":0,\"roughnessFactor\":1},\"alphaMode\":\"MASK\",\"alphaCutoff\":0.5"
                    + ",\"doubleSided\":true}");
            return materials.size() - 1;
        }

        // The initial (frame-0) NSBTA texture-SRT as a KHR_texture_transform, if this material is animated.
        private String textureTransform(String matName)
        {
            if (textureSrt == null) return "";
            for (TextureSrtAnimationSet.MaterialSrt m : textureSrt.getMaterials())
                if (m.getName().equals(matName))
                {
                    usesTextureTransform = true;
                    double rot = Math.toRadians(m.rotationAt(0));
                    return ",\"extensions\":{\"KHR_texture_transform\":{\"offset\":[" + f(m.transSAt(0)) + "," + f(m.transTAt(0))
                            + "],\"scale\":[" + f(m.scaleSAt(0)) + "," + f(m.scaleTAt(0)) + "],\"rotation\":" + f((float) rot) + "}}";
                }
            return "";
        }

        private int samplerFor(Model.Material mat)
        {
            int wrapS = mat.isFlipS() ? 33648 : mat.isRepeatS() ? 10497 : 33071;
            int wrapT = mat.isFlipT() ? 33648 : mat.isRepeatT() ? 10497 : 33071;
            String key = wrapS + ":" + wrapT;
            return samplerByKey.computeIfAbsent(key, k -> {
                samplers.add("{\"wrapS\":" + wrapS + ",\"wrapT\":" + wrapT + "}");
                return samplers.size() - 1;
            });
        }

        private BufferedImage textureFor(Model.Mesh mesh)
        {
            Model.Material mat = mesh.getMaterial();
            if (textures == null || mat == null || mat.getTextureName() == null)
                return null;
            TextureSet.Texture t = textures.getTexture(mat.getTextureName());
            if (t == null) return null;
            try { return textures.getImage(t); }
            catch (RuntimeException e) { return null; }
        }

        private String assemble(List<String> nodes, List<Integer> roots, List<String> animJson)
        {
            byte[] buffer = bin.toByteArray();
            StringBuilder sb = new StringBuilder();
            sb.append("{\"asset\":{\"version\":\"2.0\",\"generator\":\"Nds4j\"},");
            if (usesTextureTransform)
                sb.append("\"extensionsUsed\":[\"KHR_texture_transform\"],");
            sb.append("\"scene\":0,\"scenes\":[{\"nodes\":[").append(join(roots)).append("]}],");
            sb.append("\"nodes\":[").append(String.join(",", nodes)).append("],");
            if (!meshes.isEmpty())
                sb.append("\"meshes\":[").append(String.join(",", meshes)).append("],");
            sb.append("\"accessors\":[").append(String.join(",", accessors)).append("],");
            sb.append("\"bufferViews\":[").append(String.join(",", bufferViews)).append("],");
            if (!materials.isEmpty())
                sb.append("\"materials\":[").append(String.join(",", materials)).append("],");
            if (!glTextures.isEmpty())
            {
                sb.append("\"textures\":[").append(String.join(",", glTextures)).append("],");
                sb.append("\"images\":[").append(String.join(",", images)).append("],");
                sb.append("\"samplers\":[").append(String.join(",", samplers)).append("],");
            }
            if (!animJson.isEmpty())
                sb.append("\"animations\":[").append(String.join(",", animJson)).append("],");
            sb.append("\"buffers\":[{\"byteLength\":").append(buffer.length)
                    .append(",\"uri\":\"data:application/octet-stream;base64,")
                    .append(Base64.getEncoder().encodeToString(buffer)).append("\"}]}");
            return sb.toString();
        }

        private static String join(List<Integer> xs)
        {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < xs.size(); i++) { if (i > 0) sb.append(','); sb.append(xs.get(i)); }
            return sb.toString();
        }

        // --- binary buffer / accessor plumbing (identical policy to Build) ---
        private int beginAccessor(String type, int componentType, int count)
        {
            pad4();
            accessors.add("__PENDING__" + type + "|" + componentType + "|" + count + "|" + bin.size());
            return accessors.size() - 1;
        }

        private void endAccessor(int target, String extra)
        {
            int i = accessors.size() - 1;
            String[] parts = accessors.get(i).substring("__PENDING__".length()).split("\\|");
            String type = parts[0];
            int componentType = Integer.parseInt(parts[1]);
            int count = Integer.parseInt(parts[2]);
            int start = Integer.parseInt(parts[3]);
            int length = bin.size() - start;
            int view = bufferViews.size();
            String bv = "{\"buffer\":0,\"byteOffset\":" + start + ",\"byteLength\":" + length;
            if (target != 0) bv += ",\"target\":" + target;
            bufferViews.add(bv + "}");
            accessors.set(i, "{\"bufferView\":" + view + ",\"componentType\":" + componentType
                    + ",\"count\":" + count + ",\"type\":\"" + type + "\""
                    + (extra != null ? "," + extra : "") + "}");
        }

        private void pad4() { while ((bin.size() & 3) != 0) bin.write(0); }

        private void putFloat(float v)
        {
            int bits = Float.floatToIntBits(v);
            bin.write(bits); bin.write(bits >> 8); bin.write(bits >> 16); bin.write(bits >> 24);
        }

        private void putInt(int v) { bin.write(v); bin.write(v >> 8); bin.write(v >> 16); bin.write(v >> 24); }
    }
}
