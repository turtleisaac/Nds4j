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
}
