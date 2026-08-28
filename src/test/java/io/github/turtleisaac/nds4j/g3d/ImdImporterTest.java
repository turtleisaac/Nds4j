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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link ImdImporter} &mdash; the native {@code .imd} &rarr; NSBMD translator, the byte-for-byte
 * replacement for Nintendo's {@code g3dcvtr}. The fixtures are real {@code .imd} models and the exact
 * NSBMD bytes {@code g3dcvtr -emdl} produced from them (checked in so the test needs neither the tool nor
 * wine): the translator must reproduce those bytes <b>byte-identically</b>, and the result must decode.
 */
@DisplayName("ImdImporter (.imd -> NSBMD, byte-identical to g3dcvtr)")
public class ImdImporterTest
{
    private static byte[] resource(String name)
    {
        try (InputStream in = ImdImporterTest.class.getResourceAsStream("/imd/" + name))
        {
            if (in == null) throw new IllegalStateException("missing test resource /imd/" + name);
            return in.readAllBytes();
        }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private void assertByteExact(String base)
    {
        String imd = new String(resource(base + ".imd"), StandardCharsets.ISO_8859_1);
        byte[] expected = resource(base + ".nsbmd");
        byte[] actual = ImdImporter.toNsbmd(imd, base);
        assertThat(actual)
                .as("%s.imd translates byte-for-byte to g3dcvtr's NSBMD", base)
                .isEqualTo(expected);

        // and the output is a real model the production decoder reads (vertex-count oracle)
        ModelSet ms = new ModelSet(actual);
        assertThat(ms.getModels()).hasSize(1);
        Model m = ms.getModels().get(0);
        assertThat(m.getName()).isEqualTo(base);
        assertThat(m.getMeshes()).isNotEmpty();
        assertThat(m.getVertexCount()).isEqualTo(m.getExpectedVertexCount());
    }

    // -eboth: the model with its texture embedded as a TEX0 block, vs g3dcvtr -eboth.
    private void assertByteExactWithTextures(String base)
    {
        String imd = new String(resource(base + ".imd"), StandardCharsets.ISO_8859_1);
        byte[] expected = resource(base + "_both.nsbmd");
        byte[] actual = ImdImporter.toNsbmdWithTextures(imd, base);
        assertThat(actual)
                .as("%s.imd translates (with textures) byte-for-byte to g3dcvtr's NSBMD", base)
                .isEqualTo(expected);

        // the embedded texture decodes to the authored palette16 image
        ModelSet ms = new ModelSet(actual);
        assertThat(ms.hasEmbeddedTextures()).isTrue();
        TextureSet tex = ms.getEmbeddedTextures();
        assertThat(tex.getTextures()).hasSize(1);
        TextureSet.Texture t = tex.getTextures().get(0);
        assertThat(t.getWidth()).isEqualTo(16);
        assertThat(t.getHeight()).isEqualTo(16);
        assertThat(ms.getModels().get(0).getMeshes().get(0).getMaterial().getTextureName()).isEqualTo(t.getName());
    }

    @Test
    @DisplayName("a billboard, hardware-lit textured model matches g3dcvtr byte-for-byte")
    void rockMatchesG3dcvtr() { assertByteExact("rock"); }

    @Test
    @DisplayName("a non-billboard, vertex-coloured textured model matches g3dcvtr byte-for-byte")
    void bookMatchesG3dcvtr() { assertByteExact("book"); }

    @Test
    @DisplayName("with the texture embedded (-eboth), the whole MDL0+TEX0 matches g3dcvtr byte-for-byte")
    void embeddedTexturesMatchG3dcvtr() { assertByteExactWithTextures("rock"); assertByteExactWithTextures("book"); }

    @Test
    @DisplayName("a two-material, two-shape model (shared texture) matches g3dcvtr byte-for-byte")
    void multiShapeSharedTexture()
    {
        assertByteExact("two");
        Model m = new ModelSet(ImdImporter.toNsbmd(new String(resource("two.imd"), StandardCharsets.ISO_8859_1), "two")).getModels().get(0);
        assertThat(m.getMeshes()).hasSize(2);
        assertThat(m.getMaterials()).hasSize(2);
    }

    @Test
    @DisplayName("a two-material, two-shape model with two distinct textures matches g3dcvtr byte-for-byte")
    void multiShapeDistinctTextures() { assertByteExact("twotex"); }

    @Test
    @DisplayName("material state (flip tiling, decal mode) is derived correctly — byte-for-byte vs g3dcvtr")
    void materialStateDerivation() { assertByteExact("v_flip"); assertByteExact("v_decal"); }

    // little-endian u32
    private static long u32(byte[] d, int o)
    {
        return (d[o] & 0xFFL) | ((d[o + 1] & 0xFFL) << 8) | ((d[o + 2] & 0xFFL) << 16) | ((d[o + 3] & 0xFFL) << 24);
    }

    @Test
    @DisplayName("a three-node tree (null root + two mesh children) generates g3dcvtr's matrix-stack SBC")
    void multiNodeStarTree()
    {
        // The multi-node SBC generator (matrix-stack store/restore, per-node POSSCALE) validated byte-for-byte
        // against every retail single- and two-node model. This exercises the ported path end-to-end: a null
        // root with two mesh children is the canonical "star" g3dcvtr emits — NODEDESC+store on the root, then
        // each child NODEDESC (the second restoring the root matrix), NODE, POSSCALE, MAT/SHP, POSSCALE|end.
        String imd = new String(resource("star.imd"), StandardCharsets.ISO_8859_1);
        byte[] nsbmd = ImdImporter.toNsbmd(imd, "star");

        // locate the MDL0 model header and read the node counts and SBC bytes back out
        int mdl0 = (int) u32(nsbmd, 16);
        int modelStart = mdl0 + 8 + 8 + (0x10);         // BMD0(16)+MDL0(8)+model dict(header 8 + 1 rec of 0x10)
        // the model dict's single record points at the model; read it rather than assume the layout
        int modelOfs = (int) u32(nsbmd, mdl0 + 8 + 8 + 0xC);
        int ms = mdl0 + modelOfs;
        int info = ms + 0x14;
        assertThat(nsbmd[info + 3] & 0xFF).as("numNode").isEqualTo(3);
        assertThat(nsbmd[info + 6] & 0xFF).as("firstUnusedMtxStackId").isEqualTo(1);

        int ofsSbc = (int) u32(nsbmd, ms + 4), ofsMat = (int) u32(nsbmd, ms + 8);
        byte[] sbc = java.util.Arrays.copyOfRange(nsbmd, ms + ofsSbc, ms + ofsMat);
        // 26 00 00 00 00 | 06 01 00 00 | 02 01 01 | 0b 04 00 05 00 2b | 46 02 00 00 00 | 02 02 01 | 0b 04 01 05 01 2b | 01  (pad /4)
        byte[] expectedSbc = {
                0x26, 0, 0, 0, 0,          // NODEDESC(root) + store slot 0
                0x06, 1, 0, 0,             // NODEDESC(mesh0, parent root)
                0x02, 1, 1,                // NODE(mesh0, visible)
                0x0b, 0x04, 0, 0x05, 0, 0x2b,   // POSSCALE MAT0 SHP0 POSSCALE|end
                0x46, 2, 0, 0, 0,          // NODEDESC(mesh1, parent root) + restore slot 0
                0x02, 2, 1,                // NODE(mesh1, visible)
                0x0b, 0x04, 1, 0x05, 1, 0x2b,   // POSSCALE MAT1 SHP1 POSSCALE|end
                0x01, 0, 0, 0              // RET + pad to /4
        };
        assertThat(sbc).as("star-tree SBC matches g3dcvtr's matrix-stack pattern").isEqualTo(expectedSbc);

        // and it decodes to a real two-mesh model with all vertices intact
        ModelSet set = new ModelSet(nsbmd);
        Model m = set.getModels().get(0);
        assertThat(m.getMeshes()).hasSize(2);
        assertThat(m.getVertexCount()).isEqualTo(m.getExpectedVertexCount());
    }

    @Test
    @DisplayName("a node with translation, non-uniform scale and rotation encodes byte-for-byte vs g3dcvtr")
    void nodeTransformsMatchG3dcvtr()
    {
        // xform.imd gives the mesh node translate=(1.5,-2,3), scale=(2,0.5,1) and a 30° Y rotation. The node
        // local matrix is the inverse of Model.parseNodeLocals: translation (fx32), scale + inverse (fx32),
        // and the Y-rotation pivot-compressed exactly as g3dcvtr emits it.
        assertByteExact("xform");
    }

    @Test
    @DisplayName("a texture-only NSBTX (-etex) matches g3dcvtr byte-for-byte and decodes")
    void standaloneNsbtxMatchesG3dcvtr()
    {
        String imd = new String(resource("rock.imd"), StandardCharsets.ISO_8859_1);
        byte[] nsbtx = ImdImporter.toNsbtx(imd);
        assertThat(nsbtx).as("rock.imd -etex matches g3dcvtr's NSBTX").isEqualTo(resource("rock.nsbtx"));

        TextureSet tex = new TextureSet(nsbtx);
        assertThat(tex.getTextures()).hasSize(1);
        assertThat(tex.getTextures().get(0).getWidth()).isEqualTo(16);
        assertThat(tex.getTextures().get(0).getHeight()).isEqualTo(16);
        // the flagship factory produces the same archive
        assertThat(TextureSet.fromImd(imd).getTextures().get(0).getName()).isEqualTo(tex.getTextures().get(0).getName());
    }

    @Test
    @DisplayName("the enriched class-based API exposes parse + every g3dcvtr output mode via the flagship classes")
    void enrichedClassBasedApi()
    {
        String imd = new String(resource("two.imd"), StandardCharsets.ISO_8859_1);
        ImdImporter imp = ImdImporter.fromXml(imd).named("two");

        // enriched accessors over the parsed .imd
        assertThat(imp.getModelName()).isEqualTo("two");
        assertThat(imp.hasTextures()).isTrue();
        assertThat(imp.getNodeCount()).isEqualTo(1);
        assertThat(imp.getMaterialNames()).containsExactly("obj", "obj2");
        assertThat(imp.getShapeCount()).isEqualTo(2);

        // instance conversions equal the static shortcuts (same bytes)
        assertThat(imp.toNsbmd()).isEqualTo(ImdImporter.toNsbmd(imd, "two"));
        assertThat(imp.toNsbmdWithTextures()).isEqualTo(ImdImporter.toNsbmdWithTextures(imd, "two"));

        // and produce usable flagship objects directly
        ModelSet ms = imp.toModelSet();               // textures embedded (the .imd has them)
        assertThat(ms.getModels().get(0).getName()).isEqualTo("two");
        assertThat(ms.getModels().get(0).getMeshes()).hasSize(2);
        assertThat(ms.hasEmbeddedTextures()).isTrue();

        // the ModelSet.fromImd factory is equivalent
        assertThat(ModelSet.fromImd(imd, "two").getModels().get(0).getMeshes()).hasSize(2);
    }
}
