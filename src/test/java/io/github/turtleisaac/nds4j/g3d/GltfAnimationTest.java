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

import io.github.turtleisaac.nds4j.Narc;
import io.github.turtleisaac.nds4j.NintendoDsRom;
import io.github.turtleisaac.nds4j.TestRoms;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the animated glTF export ({@link GltfExporter#toGltf(Model, TextureSet, List, TextureSrtAnimationSet.Animation)}).
 * Correctness is checked <em>geometrically</em>: a glTF viewer composes each node's world matrix down
 * the tree as {@code T * R * S} (column vectors, rotation from the emitted quaternion) and applies it to
 * the node-local geometry. This test reproduces exactly that composition from the model's own node
 * transforms and asserts it reconstructs (a) the model's decoded bind pose and (b) the skeletal
 * {@link Model#pose} of a mid-animation frame. If the node tree, the row-vector&rarr;column-vector
 * quaternion conversion, or the geometry placement were wrong, the reconstruction would diverge.
 */
@DisplayName("animated glTF export (skeleton node tree + NSBCA channels)")
public class GltfAnimationTest
{
    private static String magic(byte[] d)
    {
        return d == null || d.length < 4 ? "" : new String(d, 0, 4, StandardCharsets.ISO_8859_1);
    }

    private Model manene, maneneModel;
    private SkeletalAnimationSet.Animation maneneAnim;

    private boolean loadManene()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        Narc narc;
        try { narc = new Narc(rom.getFile(142)); }
        catch (RuntimeException e) { return false; }
        if (!magic(narc.getFile(51)).equals("BMD0") || !magic(narc.getFile(53)).equals("BCA0"))
            return false;
        maneneModel = new ModelSet(narc.getFile(51)).getModels().get(0);
        maneneAnim = new SkeletalAnimationSet(narc.getFile(53)).getAnimations().get(0);
        return true;
    }

    @Test
    @DisplayName("emits a valid glTF with a node tree and animation channels")
    void emitsAnimatedGltf()
    {
        Assumptions.assumeTrue(loadManene(), "need manene fixture");
        String gltf = GltfExporter.toGltf(maneneModel, null, Collections.singletonList(maneneAnim), null);
        assertThat(gltf).startsWith("{\"asset\"").endsWith("}");
        assertThat(gltf).contains("\"version\":\"2.0\"").contains("\"animations\"")
                .contains("\"path\":\"rotation\"").contains("\"translation\"").contains("\"scale\"");
    }

    @Test
    @DisplayName("glTF node composition reconstructs the model's bind pose")
    void reconstructsBindPose()
    {
        Assumptions.assumeTrue(loadManene(), "need manene fixture");
        assertPoseMatches(null);
    }

    @Test
    @DisplayName("glTF node composition reconstructs a posed animation frame")
    void reconstructsPosedFrame()
    {
        Assumptions.assumeTrue(loadManene(), "need manene fixture");
        assertPoseMatches(maneneAnim.getFrameCount() / 2);
    }

    // Compose glTF world matrices exactly as a viewer would and compare to the model's own placement.
    private void assertPoseMatches(Integer frame)
    {
        Model model = maneneModel;
        int nodeCount = model.getNodeCount();
        Model.NodeTransform[] local = model.localTransforms(frame == null ? null : maneneAnim, frame == null ? 0 : frame);
        double posScale = model.getPositionScale();

        // world[n] = world[parent] * (T * R * S), rotation from the emitted quaternion
        double[][] world = new double[nodeCount][];
        for (int n = 0; n < nodeCount; n++)
            world[n] = composeWorld(n, model, local);

        List<float[]> expected = frame == null
                ? null
                : model.pose(maneneAnim, frame);

        double maxErr = 0;
        List<Model.Mesh> meshes = model.getMeshes();
        for (int mi = 0; mi < meshes.size(); mi++)
        {
            Model.Mesh mesh = meshes.get(mi);
            float[] raw = mesh.getRawPositions();
            float[] ref = expected == null ? mesh.getPositions() : expected.get(mi);
            double[] m = world[mesh.getNodeIndex()];
            for (int i = 0; i < raw.length; i += 3)
            {
                double[] p = {raw[i] * posScale, raw[i + 1] * posScale, raw[i + 2] * posScale};
                double x = m[0] * p[0] + m[1] * p[1] + m[2] * p[2] + m[3];
                double y = m[4] * p[0] + m[5] * p[1] + m[6] * p[2] + m[7];
                double z = m[8] * p[0] + m[9] * p[1] + m[10] * p[2] + m[11];
                maxErr = Math.max(maxErr, Math.abs(x - ref[i]));
                maxErr = Math.max(maxErr, Math.abs(y - ref[i + 1]));
                maxErr = Math.max(maxErr, Math.abs(z - ref[i + 2]));
            }
        }
        assertThat(maxErr).as("glTF node composition should reproduce the model placement").isLessThan(1e-3);
    }

    // 3x4 world matrix (row-major, column-vector) for node n, composed down the tree.
    private double[] composeWorld(int n, Model model, Model.NodeTransform[] local)
    {
        double[] l = trsMatrix(local[n]);
        int parent = model.getNodeParent(n);
        if (parent < 0 || parent == n)
            return l;
        return mul(composeWorld(parent, model, local), l);
    }

    private double[] trsMatrix(Model.NodeTransform t)
    {
        float[] q = GltfExporter.matrixToQuat(t.getRotation());
        double[] r = quatToMatrix(q);
        double[] s = t.getScale();
        double[] tr = t.getTranslation();
        // M = T * R * S  (column vector), 3x4 row-major
        return new double[]{
                r[0] * s[0], r[1] * s[1], r[2] * s[2], tr[0],
                r[3] * s[0], r[4] * s[1], r[5] * s[2], tr[1],
                r[6] * s[0], r[7] * s[1], r[8] * s[2], tr[2]};
    }

    private static double[] quatToMatrix(float[] q)
    {
        double x = q[0], y = q[1], z = q[2], w = q[3];
        return new double[]{
                1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
                2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
                2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)};
    }

    // 3x4 * 3x4 (each an affine with implicit [0 0 0 1] row)
    private static double[] mul(double[] a, double[] b)
    {
        double[] o = new double[12];
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 4; col++)
            {
                double s = 0;
                for (int k = 0; k < 3; k++)
                    s += a[row * 4 + k] * b[k * 4 + col];
                if (col == 3)
                    s += a[row * 4 + 3];
                o[row * 4 + col] = s;
            }
        return o;
    }
}
