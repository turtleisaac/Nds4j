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
    private final SkeletalAnimationSet.Animation skeletal; // NSBCA, or null
    private final TextureSrtAnimationSet.Animation textureSrt; // NSBTA, or null
    private final TexturePatternAnimationSet.Animation pattern; // NSBTP, or null
    private final VisibilityAnimationSet.Animation visibility; // NSBVA, or null

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
    public static NitroAnimation ofSkeletal(SkeletalAnimationSet.Animation a)
    {
        return new NitroAnimation(a, null, null, null);
    }
    /** @return an NSBTA-only animation */
    public static NitroAnimation ofTextureSrt(TextureSrtAnimationSet.Animation a)
    {
        return new NitroAnimation(null, a, null, null);
    }
    /** @return an NSBTP-only animation */
    public static NitroAnimation ofPattern(TexturePatternAnimationSet.Animation a)
    {
        return new NitroAnimation(null, null, a, null);
    }
    /** @return an NSBVA-only animation */
    public static NitroAnimation ofVisibility(VisibilityAnimationSet.Animation a)
    {
        return new NitroAnimation(null, null, null, a);
    }

    /**
     * @return the animation's length in frames &mdash; the longest of the present tracks (at least 1).
     *         Tracks shorter than this hold their last value (the samplers clamp).
     */
    public int getFrameCount()
    {
        int n = 1;
        if (skeletal != null)
            n = Math.max(n, skeletal.getFrameCount());
        if (textureSrt != null)
            n = Math.max(n, textureSrt.getFrameCount());
        if (pattern != null)
            n = Math.max(n, pattern.getFrameCount());
        if (visibility != null)
            n = Math.max(n, visibility.getFrameCount());
        return n;
    }

    /** @return true if a skeletal (NSBCA) track is present, i.e. vertex positions change per frame */
    public boolean hasSkeletal()
    {
        return skeletal != null;
    }
    /** @return true if a texture-SRT (NSBTA) track is present */
    public boolean hasTextureSrt()
    {
        return textureSrt != null;
    }
    /** @return true if a pattern (NSBTP) track is present */
    public boolean hasPattern()
    {
        return pattern != null;
    }
    /** @return true if a visibility (NSBVA) track is present */
    public boolean hasVisibility()
    {
        return visibility != null;
    }

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
        TexAffine[] uvMatrix = new TexAffine[n];
        String[][] texOverride = new String[n][];
        boolean[] visible = new boolean[n];
        Vec3[] billboardPivot = new Vec3[n];
        Vec3[] posedNodeT = posedTranslations(model, frame);
        for (int i = 0; i < n; i++)
            fillMesh(meshes.get(i), i, frame, uvMatrix, texOverride, visible, billboardPivot, posedNodeT);

        return new Frame(posedPositions(model, meshes, frame), uvMatrix, texOverride, visible, billboardPivot);
    }

    /**
     * @param model the model
     * @param meshes its meshes
     * @param frame the frame index
     * @return posed or bind positions
     * @see "NitroSystem g3d/src/sbc.c NNS_G3dDraw"
     */
    private List<float[]> posedPositions(Model model, List<Model.Mesh> meshes, int frame)
    {
        if (skeletal == null)
            return bindPositions(meshes);

        return model.pose(skeletal, frame);
    }

    /**
     * @param frame the frame index
     * @return posed node translations, or null when there is no skeletal track
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_BB"
     */
    private Vec3[] posedTranslations(Model model, int frame)
    {
        if (skeletal == null)
            return null;

        return model.poseNodeWorldTranslations(skeletal, frame);
    }

    /**
     * @param mesh one mesh
     * @param i its index
     * @param frame the frame index
     * @param uvMatrix texture matrices being filled
     * @param texOverride pattern overrides being filled
     * @param visible visibility flags being filled
     * @param billboardPivot pivots being filled
     * @param posedNodeT posed node translations, or null
     * @see "NitroSystem g3d/src/sbc.c NNS_G3dDraw"
     */
    private void fillMesh(Model.Mesh mesh, int i, int frame, TexAffine[] uvMatrix, String[][] texOverride,
            boolean[] visible, Vec3[] billboardPivot, Vec3[] posedNodeT)
    {
        String matName = materialName(mesh.getMaterial());
        uvMatrix[i] = uvFor(matName, frame);
        texOverride[i] = patternFor(matName, frame);
        visible[i] = shown(mesh.getNodeIndex(), frame);
        billboardPivot[i] = pivotAt(posedNodeT, mesh.getNodeIndex());
    }

    /**
     * @param mat a material, or null
     * @return its name, or null
     * @see "NitroSystem g3d/src/anm/nsbta.c"
     */
    private static String materialName(Model.Material mat)
    {
        if (mat == null)
            return null;

        return mat.getName();
    }

    /**
     * @param matName material name, or null
     * @param frame the frame index
     * @return the texture matrix, or null
     * @see "NitroSystem g3d/src/anm/nsbta.c NNS_G3dAnmObjCalcTexSRT"
     */
    private TexAffine uvFor(String matName, int frame)
    {
        if (textureSrt == null)
            return null;

        return uvNamed(matName, frame);
    }

    /**
     * @param matName material name, or null
     * @param frame the frame index
     * @return the texture matrix, or null
     * @see "NitroSystem g3d/src/anm/nsbta.c NNS_G3dAnmObjCalcTexSRT"
     */
    private TexAffine uvNamed(String matName, int frame)
    {
        if (matName == null)
            return null;

        return srtMatrix(matName, frame);
    }

    /**
     * @param matName material name, or null
     * @param frame the frame index
     * @return the texture and palette override, or null
     * @see "NitroSystem g3d/src/anm/nsbtp.c"
     */
    private String[] patternFor(String matName, int frame)
    {
        if (pattern == null)
            return null;

        return patternNamed(matName, frame);
    }

    /**
     * @param matName material name, or null
     * @param frame the frame index
     * @return the texture and palette override, or null
     * @see "NitroSystem g3d/src/anm/nsbtp.c"
     */
    private String[] patternNamed(String matName, int frame)
    {
        if (matName == null)
            return null;

        return patternOverride(matName, frame);
    }

    /**
     * @param node node index
     * @param frame the frame index
     * @return whether the node is drawn
     * @see "NitroSystem g3d/src/anm/nsbva.c"
     */
    private boolean shown(int node, int frame)
    {
        if (visibility == null)
            return true;

        return nodeVisible(node, frame);
    }

    /**
     * @param posed posed translations, or null
     * @param node node index
     * @return that node's translation, or null
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_BB"
     */
    private static Vec3 pivotAt(Vec3[] posed, int node)
    {
        if (posed == null)
            return null;

        return pivotIndex(posed, node);
    }

    /**
     * @param posed posed translations
     * @param node node index
     * @return that node's translation, or null
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_BB"
     */
    private static Vec3 pivotIndex(Vec3[] posed, int node)
    {
        if (node < 0)
            return null;

        return pivotInRange(posed, node);
    }

    /**
     * @param posed posed translations
     * @param node node index
     * @return that node's translation, or null when the index is past the array
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_BB"
     */
    private static Vec3 pivotInRange(Vec3[] posed, int node)
    {
        if (node >= posed.length)
            return null;

        return posed[node];
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
    private TexAffine srtMatrix(String matName, int frame)
    {
        // Walk the material tracks until the name matches.
        return scanSrt(textureSrt.getMaterials().iterator(), matName, frame);
    }

    /**
     * @param materials remaining material tracks
     * @param matName material name
     * @param frame the frame index
     * @return the matching matrix, or null
     * @see "NitroSystem g3d/src/anm/nsbta.c NNS_G3dAnmObjCalcTexSRT"
     */
    private TexAffine scanSrt(java.util.Iterator<TextureSrtAnimationSet.MaterialSrt> materials, String matName, int frame)
    {
        if (!materials.hasNext())
            return null;

        return nextSrt(materials, matName, frame);
    }

    /**
     * @param materials remaining material tracks, with at least one left
     * @param matName material name
     * @param frame the frame index
     * @return the matching matrix, or null
     * @see "NitroSystem g3d/src/anm/nsbta.c NNS_G3dAnmObjCalcTexSRT"
     */
    private TexAffine nextSrt(java.util.Iterator<TextureSrtAnimationSet.MaterialSrt> materials, String matName, int frame)
    {
        TextureSrtAnimationSet.MaterialSrt material = materials.next();
        if (material.getName().equals(matName))
            return TexAffine.of(material.scaleSAt(frame), material.scaleTAt(frame),
                    Math.toRadians(material.rotationAt(frame)), material.transSAt(frame), material.transTAt(frame));

        return scanSrt(materials, matName, frame);
    }

    private String[] patternOverride(String matName, int frame)
    {
        for (TexturePatternAnimationSet.MaterialPattern m : pattern.getMaterials())
            if (m.getName().equals(matName))
            {
                TexturePatternAnimationSet.TexturePalette tp = m.at(frame);
                return tp != null ? new String[] { tp.getTexture(), tp.getPalette() } : null;
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
        private final TexAffine[] uvMatrix;
        private final String[][] textureOverride;
        private final boolean[] visible;
        private final Vec3[] billboardPivot;

        Frame(List<float[]> positions, TexAffine[] uvMatrix, String[][] textureOverride, boolean[] visible,
                Vec3[] billboardPivot)
        {
            this.positions = positions;
            this.uvMatrix = uvMatrix;
            this.textureOverride = textureOverride;
            this.visible = visible;
            this.billboardPivot = billboardPivot;
        }

        /** @return per-mesh vertex positions (x,y,z triples), parallel to {@code model.getMeshes()} */
        public List<float[]> getPositions()
        {
            return positions;
        }
        /** @param i a mesh index @return the mesh's 2x3 normalised-UV texture matrix {a,b,c,d,e,f}, or null for identity */
        public TexAffine uvMatrixFor(int i)
        {
            TexAffine row = uvMatrix[i];

            return row;
        }
        /** @param i a mesh index @return {textureName, paletteName} overriding the mesh's material, or null */
        public String[] textureOverrideFor(int i)
        {
            return textureOverride[i];
        }
        /** @param i a mesh index @return whether the mesh is drawn this frame */
        public boolean isVisible(int i)
        {
            return visible[i];
        }
        /**
         * @param i a mesh index
         * @return the posed world translation of mesh {@code i}'s node &mdash; the pivot a billboard node
         *         should face-track around this frame &mdash; or {@code null} if this frame has no skeletal
         *         pose (use the model's bind-pose pivot then).
         */
        public Vec3 billboardPivotFor(int i)
        {
            return pivotAt(billboardPivot, i);
        }

        /**
         * @param pivots per-mesh pivots
         * @param i mesh index
         * @return that mesh's pivot
         * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_BB"
         */
        private static Vec3 pivotAt(Vec3[] pivots, int i)
        {
            return java.util.Arrays.asList(pivots).get(i);
        }
    }
}
