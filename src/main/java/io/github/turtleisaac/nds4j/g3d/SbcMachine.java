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

/**
 * Software stand-in for the 32-slot position matrix stack the SBC player keeps.
 * Slot 31 is unused; stored indices are masked to five bits.
 */
final class SbcMachine
{
    /** One NODEMIX term list, or a single joint when {@code weights} is null. */
    static final class Bind
    {
        final int[] joints;
        final double[] weights;

        private Bind(int[] joints, double[] weights)
        {
            this.joints = joints;
            this.weights = weights;
        }

        /**
         * @param joint node index
         * @return a weight-1 binding
         * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_NODEDESC"
         */
        static Bind joint(int joint)
        {
            return new Bind(new int[] { joint }, null);
        }

        /**
         * @param joints joint indices, parallel to {@code weights}
         * @param weights blend weights, summing to about 1
         * @return a NODEMIX binding
         * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_NODEMIX"
         */
        static Bind mix(int[] joints, double[] weights)
        {
            return new Bind(joints, weights);
        }
    }

    private final double[][] stack = new double[32][];
    private final Bind[] stackBind = new Bind[32];
    private final boolean[] scaleOne;
    private final double[][] scaleInv;
    private final double[][] jointWorld;
    private double[] current = NitroMtx.identity();
    private Bind bind = Bind.joint(0);

    /**
     * @param nodes number of skeleton nodes. Bit 0 of the scale cache starts set.
     * @see "NitroSystem g3d/src/sbc.c G3dDrawInternal_"
     */
    SbcMachine(int nodes)
    {
        double[] id = NitroMtx.identity();
        for (int i = 0; i < stack.length; i++)
            stack[i] = id;

        jointWorld = new double[nodes][];
        for (int i = 0; i < nodes; i++)
            jointWorld[i] = id;

        scaleOne = new boolean[Math.max(nodes, 1)];
        scaleInv = new double[scaleOne.length][];
        if (scaleOne.length > 0)
            scaleOne[0] = true;

        for (int i = 0; i < scaleInv.length; i++)
            scaleInv[i] = new double[] { 0, 0, 0 };
    }

    /**
     * @param node node whose SRT is multiplied in
     * @param parent parent id used for the Maya scale cache
     * @param opt NODEDESC flag byte
     * @param srcSlot stack slot to restore first, or -1
     * @param dstSlot stack slot to store into, or -1
     * @param t translation
     * @param r rotation
     * @param s scale
     * @param maya true when the model scaling rule is Maya
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_NODEDESC"
     */
    void nodeDesc(int node, int parent, int opt, int srcSlot, int dstSlot,
            double[] t, double[] r, double[] s, boolean maya)
    {
        if (srcSlot >= 0)
            restore(srcSlot);

        double[] inv = parentInverse(parent, opt, maya);
        current = NitroMtx.mul(NitroMtx.mayaJoint(t, r, s, inv), current);
        bind = Bind.joint(node);

        if (node >= 0 && node < jointWorld.length)
            jointWorld[node] = current;

        if (dstSlot >= 0)
            store(dstSlot);

        rememberScale(node, opt, s, maya);
    }

    /**
     * @param parent parent node id
     * @param opt NODEDESC flag byte
     * @param maya Maya scaling rule
     * @return inverse parent scale, or {@code (1,1,1)}
     * @see "NitroSystem g3d/src/cgtool/maya.c NNSi_G3dGetJointScaleMaya"
     */
    private double[] parentInverse(int parent, int opt, boolean maya)
    {
        if (!maya || (opt & NodeDescFlag.NNS_G3D_SBC_NODEDESC_FLAG_MAYASSC_APPLY.mask) == 0)
            return new double[] { 1, 1, 1 };

        if (parent < 0 || parent >= scaleOne.length || scaleOne[parent])
            return new double[] { 1, 1, 1 };

        return scaleInv[parent];
    }

    /**
     * @param node node that may be an SSC parent
     * @param opt NODEDESC flag byte
     * @param s the node's scale
     * @param maya Maya scaling rule
     * @see "NitroSystem g3d/src/cgtool/maya.c NNSi_G3dGetJointScaleMaya"
     */
    private void rememberScale(int node, int opt, double[] s, boolean maya)
    {
        if (!maya || (opt & NodeDescFlag.NNS_G3D_SBC_NODEDESC_FLAG_MAYASSC_PARENT.mask) == 0
                || node < 0 || node >= scaleOne.length)
            return;

        boolean one = Math.abs(s[0] - 1) < 1e-8 && Math.abs(s[1] - 1) < 1e-8 && Math.abs(s[2] - 1) < 1e-8;
        scaleOne[node] = one;

        if (!one)
            scaleInv[node] = NitroMtx.safeInv(s);
    }

    /**
     * @param dest stack slot that receives the blend
     * @param slots stack slot of each term
     * @param joints envelope joint of each term
     * @param weights term weights
     * @param invBind per-node inverse bind, Nitro 4×3
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_NODEMIX"
     */
    void nodeMix(int dest, int[] slots, int[] joints, double[] weights, double[][] invBind)
    {
        double[] sum = new double[12];
        for (int i = 0; i < slots.length; i++)
        {
            double[] joint = stack[slots[i] & 31];
            double[] inv = invBind[joints[i]];
            NitroMtx.mad(sum, NitroMtx.mul(inv, joint), weights[i]);
        }

        stack[dest & 31] = sum;
        stackBind[dest & 31] = Bind.mix(joints.clone(), weights.clone());
        current = sum;
        bind = stackBind[dest & 31];
    }

    /**
     * @param factor {@code posScale} or its inverse
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_POSSCALE"
     */
    void posScale(double factor)
    {
        current = NitroMtx.mul(NitroMtx.scale(factor, factor, factor), current);
    }

    /**
     * @param slot stack index
     * @see "NitroSDK gx g3.h G3OP_MTX_RESTORE"
     */
    void restore(int slot)
    {
        current = stack[slot & 31];
        Bind stored = stackBind[slot & 31];
        if (stored != null)
            bind = stored;
    }

    /**
     * @param slot stack index
     * @see "NitroSDK gx g3.h G3OP_MTX_STORE"
     */
    void store(int slot)
    {
        stack[slot & 31] = current;
        stackBind[slot & 31] = bind;
    }

    /**
     * @return the current position matrix
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_SHP"
     */
    double[] current()
    {
        return current;
    }

    /**
     * @param slot stack index
     * @return that slot's matrix
     * @see "NitroSDK gx g3.h G3OP_MTX_RESTORE"
     */
    double[] slot(int slot)
    {
        return stack[slot & 31];
    }

    /**
     * @param slot stack index
     * @return the binding stored with that slot, or null
     * @see "NitroSDK gx g3.h G3OP_MTX_RESTORE"
     */
    Bind slotBind(int slot)
    {
        int index = slot & 31;
        Bind stored = stackBind[index];
        return stored;
    }

    /**
     * @return which joints the current matrix stands for
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_NODEMIX"
     */
    Bind binding()
    {
        return bind;
    }

    /**
     * @param node node index
     * @return the matrix last stored for that node by NODEDESC
     * @see "NitroSystem g3d/src/sbc.c NNSi_G3dFuncSbc_NODEDESC"
     */
    double[] jointWorld(int node)
    {
        return jointWorld[node];
    }

    /**
     * @param slot stack index
     * @param matrix matrix to install, for tests
     * @see "NitroSDK gx g3.h G3OP_MTX_STORE"
     */
    void putSlot(int slot, double[] matrix, Bind binding)
    {
        stack[slot & 31] = matrix;
        stackBind[slot & 31] = binding;
    }
}
