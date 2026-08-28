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

import java.util.List;

/**
 * A playable bundle of the Nitro-3D animation tracks that apply to one {@link Model}, and the
 * per-frame sampler that ties them together. A model can be animated by up to four independent files at
 * once, each optional:
 * <ul>
 *   <li><b>NSBCA</b> ({@link SkeletalAnimationSet.Animation}) &mdash; re-poses the skeleton (node SRT).</li>
 *   <li><b>NSBTA</b> ({@link TextureSrtAnimationSet.Animation}) &mdash; scrolls/scales/rotates a
 *       material's texture coordinates.</li>
 *   <li><b>NSBTP</b> ({@link TexturePatternAnimationSet.Animation}) &mdash; flip-books which
 *       texture/palette a material samples.</li>
 *   <li><b>NSBVA</b> ({@link VisibilityAnimationSet.Animation}) &mdash; toggles nodes on/off.</li>
 * </ul>
 * The decoders already parse and sample each track; this class is the small piece that composes them
 * into a single {@link Frame} the {@link SoftwareRenderer} (and, later, the interactive viewer and the
 * glTF exporter) can consume. Pass any subset &mdash; a lone NSBCA walk cycle, a lone NSBTA water
 * scroll, or all four together.
 * <p>
 * A {@link Frame} is expressed <em>parallel to</em> {@link Model#getMeshes()}: posed vertex positions,
 * a normalised-UV texture matrix, an optional texture/palette override, and a visibility flag, one
 * entry per mesh. That is exactly what a renderer walks, so applying an animation adds no per-frame
 * bookkeeping to the draw loop.
 */
public final class NitroAnimation
{
    private final SkeletalAnimationSet.Animation skeletal;      // NSBCA, or null
    private final TextureSrtAnimationSet.Animation textureSrt;  // NSBTA, or null
    private final TexturePatternAnimationSet.Animation pattern; // NSBTP, or null
    private final VisibilityAnimationSet.Animation visibility;  // NSBVA, or null

    /**
     * Bundles up to four animation tracks. Any argument may be null; passing all-null yields a
     * single-frame animation that reproduces the model's bind pose.
     * @param skeletal an NSBCA animation, or null
     * @param textureSrt an NSBTA animation, or null
     * @param pattern an NSBTP animation, or null
     * @param visibility an NSBVA animation, or null
     */
    public NitroAnimation(SkeletalAnimationSet.Animation skeletal,
                          TextureSrtAnimationSet.Animation textureSrt,
                          TexturePatternAnimationSet.Animation pattern,
                          VisibilityAnimationSet.Animation visibility)
    {
        this.skeletal = skeletal;
        this.textureSrt = textureSrt;
        this.pattern = pattern;
        this.visibility = visibility;
    }

    /** @return an NSBCA-only animation */
    public static NitroAnimation ofSkeletal(SkeletalAnimationSet.Animation a) { return new NitroAnimation(a, null, null, null); }
    /** @return an NSBTA-only animation */
    public static NitroAnimation ofTextureSrt(TextureSrtAnimationSet.Animation a) { return new NitroAnimation(null, a, null, null); }
    /** @return an NSBTP-only animation */
    public static NitroAnimation ofPattern(TexturePatternAnimationSet.Animation a) { return new NitroAnimation(null, null, a, null); }
    /** @return an NSBVA-only animation */
    public static NitroAnimation ofVisibility(VisibilityAnimationSet.Animation a) { return new NitroAnimation(null, null, null, a); }

    /**
     * @return the animation's length in frames &mdash; the longest of the present tracks (at least 1).
     *         Tracks shorter than this hold their last value (the samplers clamp).
     */
    public int getFrameCount()
    {
        int n = 1;
        if (skeletal != null) n = Math.max(n, skeletal.getFrameCount());
        if (textureSrt != null) n = Math.max(n, textureSrt.getFrameCount());
        if (pattern != null) n = Math.max(n, pattern.getFrameCount());
        if (visibility != null) n = Math.max(n, visibility.getFrameCount());
        return n;
    }

    /** @return true if a skeletal (NSBCA) track is present, i.e. vertex positions change per frame */
    public boolean hasSkeletal() { return skeletal != null; }
    /** @return true if a texture-SRT (NSBTA) track is present */
    public boolean hasTextureSrt() { return textureSrt != null; }
    /** @return true if a pattern (NSBTP) track is present */
    public boolean hasPattern() { return pattern != null; }
    /** @return true if a visibility (NSBVA) track is present */
    public boolean hasVisibility() { return visibility != null; }

    /**
     * Samples every present track at {@code frame} and returns the per-mesh render data. Frames outside a
     * track's range clamp to its endpoints (via the underlying samplers), so the length mismatch between
     * tracks is handled gracefully.
     * @param model the model these tracks animate
     * @param frame the frame index to sample
     * @return a {@link Frame} parallel to {@code model.getMeshes()}
     */
    public Frame sample(Model model, int frame)
    {
        List<Model.Mesh> meshes = model.getMeshes();
        int n = meshes.size();

        List<float[]> positions = (skeletal != null)
                ? model.pose(skeletal, frame)
                : bindPositions(meshes);

        float[][] uvMatrix = new float[n][];
        String[][] texOverride = new String[n][];
        boolean[] visible = new boolean[n];

        for (int i = 0; i < n; i++)
        {
            Model.Mesh mesh = meshes.get(i);
            Model.Material mat = mesh.getMaterial();
            String matName = mat != null ? mat.getName() : null;

            uvMatrix[i] = (textureSrt != null && matName != null) ? srtMatrix(matName, frame) : null;
            texOverride[i] = (pattern != null && matName != null) ? patternOverride(matName, frame) : null;
            visible[i] = (visibility != null) ? nodeVisible(mesh.getNodeIndex(), frame) : true;
        }
        return new Frame(positions, uvMatrix, texOverride, visible);
    }

    private static List<float[]> bindPositions(List<Model.Mesh> meshes)
    {
        List<float[]> out = new java.util.ArrayList<>(meshes.size());
        for (Model.Mesh m : meshes)
            out.add(m.getPositions());
        return out;
    }

    // Builds a 2x3 affine over normalised UVs (u' = a*u + c*v + e, v' = b*u + d*v + f) from the NSBTA
    // scale/rotation/translation channels for one material at a frame. Standard SRT: rotate+scale, then
    // translate. Rotation is rare in practice (scrolling water is translation-only); the dominant case is
    // therefore an identity linear part plus a per-frame translation, which this reproduces exactly.
    private float[] srtMatrix(String matName, int frame)
    {
        for (TextureSrtAnimationSet.MaterialSrt m : textureSrt.getMaterials())
            if (m.getName().equals(matName))
            {
                double rad = Math.toRadians(m.rotationAt(frame));
                double cos = Math.cos(rad), sin = Math.sin(rad);
                float ss = m.scaleSAt(frame), st = m.scaleTAt(frame);
                float ts = m.transSAt(frame), tt = m.transTAt(frame);
                return new float[]{
                        (float) (ss * cos), (float) (-st * sin),   // column for u (a, b)
                        (float) (ss * sin), (float) (st * cos),    // column for v (c, d)
                        ts, tt};                                    // translation (e, f)
            }
        return null;
    }

    private String[] patternOverride(String matName, int frame)
    {
        for (TexturePatternAnimationSet.MaterialPattern m : pattern.getMaterials())
            if (m.getName().equals(matName))
            {
                TexturePatternAnimationSet.TexturePalette tp = m.at(frame);
                return tp != null ? new String[]{tp.getTexture(), tp.getPalette()} : null;
            }
        return null;
    }

    private boolean nodeVisible(int node, int frame)
    {
        if (node < 0 || node >= visibility.getNodeCount())
            return true; // this node isn't controlled by the visibility track
        int f = Math.min(frame, visibility.getFrameCount() - 1);
        return visibility.isVisible(node, f);
    }

    /**
     * One frame of animation, expressed parallel to {@link Model#getMeshes()}. A renderer walks its
     * model's meshes and, for mesh <em>i</em>, draws {@link #positions}{@code [i]} with the mesh's own
     * texcoords transformed by {@link #uvMatrix}{@code [i]}, sampling {@link #textureOverride}{@code [i]}
     * instead of the material's default texture when present, and skips the mesh entirely when
     * {@link #visible}{@code [i]} is false.
     */
    public static final class Frame
    {
        private final List<float[]> positions;
        private final float[][] uvMatrix;
        private final String[][] textureOverride;
        private final boolean[] visible;

        Frame(List<float[]> positions, float[][] uvMatrix, String[][] textureOverride, boolean[] visible)
        {
            this.positions = positions;
            this.uvMatrix = uvMatrix;
            this.textureOverride = textureOverride;
            this.visible = visible;
        }

        /** @return per-mesh vertex positions (x,y,z triples), parallel to {@code model.getMeshes()} */
        public List<float[]> getPositions() { return positions; }
        /** @param i a mesh index @return the mesh's 2x3 normalised-UV texture matrix {a,b,c,d,e,f}, or null for identity */
        public float[] uvMatrixFor(int i) { return uvMatrix[i]; }
        /** @param i a mesh index @return {textureName, paletteName} overriding the mesh's material, or null */
        public String[] textureOverrideFor(int i) { return textureOverride[i]; }
        /** @param i a mesh index @return whether the mesh is drawn this frame */
        public boolean isVisible(int i) { return visible[i]; }
    }
}
