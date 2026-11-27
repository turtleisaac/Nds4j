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

import io.github.turtleisaac.nds4j.framework.MemBuf;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates a NITRO intermediate model (<b>{@code .imd}</b>, the XML the Maya/3ds&nbsp;Max NNS exporter
 * emits) into a binary <b>NSBMD</b> &mdash; the native, byte-for-byte replacement for Nintendo's
 * {@code g3dcvtr} tool. Because the {@code .imd} already carries every optimisation decision the exporter
 * made (which vertices are full {@code pos_s} vs delta {@code pos_xy}/{@code pos_xz}/{@code pos_yz}, the
 * strip/quad grouping, the node transforms and material state), translating it faithfully reproduces
 * {@code g3dcvtr}'s output <em>exactly</em> &mdash; without reimplementing the exporter's optimiser.
 * <p>
 * Verified byte-identical to g3dcvtr across all three of its output modes &mdash; {@code -emdl} ({@link #toNsbmd()}),
 * {@code -eboth} ({@link #toNsbmdWithTextures()}) and {@code -etex} ({@link #toNsbtx()}). Coverage: multi-node
 * trees with full node local transforms (translation, non-uniform scale, and rotation &mdash; pivot-compressed
 * for principal axes, full 3&times;3 otherwise), multiple materials (grouped by shared texture/palette) and
 * multiple shapes, textured or vertex-colored, billboard or not. The SBC render stream is a general node-tree
 * walk with a matrix-stack store/restore allocator and a material stack, validated byte-for-byte against every
 * retail single- and two-node model and the great majority of deeper trees (the residual are complex skeletal
 * chains and a matrix-slot-numbering edge). The geometry, resource dictionaries and container are produced by
 * the byte-exact primitives ({@link DisplayList}, {@link G3dDictionary}, {@link G3dFile}); this class adds the
 * {@code .imd} parse and the MDL0/TEX0 struct encoders (model header/box, node set with local matrices, SBC
 * render stream, material set, shape set, texture/palette).
 * <p>
 * Use the fluent, class-based API &mdash; {@link #fromXml(String)}/{@link #fromFile(File)}, {@link #named(String)},
 * the {@code getX} accessors over the parsed model, and {@link #toModelSet()}/{@link #toTextureSet()} to land
 * directly on the flagship {@link ModelSet}/{@link TextureSet} (also reachable via {@link ModelSet#fromImd} and
 * {@link TextureSet#fromImd}). The {@code static} {@code toNsbmd}/{@code toNsbmdWithTextures}/{@code toNsbtx}
 * shortcuts remain for one-liners.
 */
public final class ImdImporter
{
    private final String imd;

    private String modelName = "model";

    private ImdImporter(String imd)
    {
        this.imd = imd;
    }

    // === factories =====================================================================================

    /** Opens {@code .imd} source for conversion. Set the model name with {@link #named(String)} (default {@code "model"}). */
    public static ImdImporter fromXml(String imdXml)
    {
        return new ImdImporter(imdXml);
    }

    /** Opens an {@code .imd} file for conversion, taking the model name from the file's base name (as g3dcvtr does). */
    public static ImdImporter fromFile(File file) throws IOException
    {
        String xml = new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1);
        String base = file.getName();
        int dot = base.lastIndexOf('.');
        return new ImdImporter(xml).named(dot < 0 ? base : base.substring(0, dot));
    }

    /** Sets the model name g3dcvtr would take from the source file's base name; returns {@code this} for chaining. */
    public ImdImporter named(String modelName)
    {
        this.modelName = modelName;
        return this;
    }

    // === enriched accessors (the parsed .imd structure) ================================================

    /** The model name used when authoring the NSBMD. */
    public String getModelName()
    {
        return modelName;
    }

    /** Whether the {@code .imd} carries a texture ({@code tex_image}) — i.e. whether {@link #toNsbtx()} / embedding applies. */
    public boolean hasTextures()
    {
        return Pattern.compile("<tex_image[\\s>]").matcher(imd).find();
    }

    /** The node names, in index order (the {@code <node_array>}). */
    public List<String> getNodeNames()
    {
        List<String> names = new ArrayList<>();
        for (Node nd : parseNodes())
            names.add(nd.name);
        return names;
    }

    /** The number of nodes in the model tree. */
    public int getNodeCount()
    {
        return parseNodes().size();
    }

    /** The material names, in declaration order (the {@code <material_array>}). */
    public List<String> getMaterialNames()
    {
        List<String> names = new ArrayList<>();
        for (String el : blocks("material"))
            names.add(a(el, "name"));
        return names;
    }

    /** The number of shapes (polygons) in the model. */
    public int getShapeCount()
    {
        return blocks("polygon").size();
    }

    // === conversions (the g3dcvtr output modes) =======================================================

    /** Authors a model-only NSBMD (the {@code g3dcvtr -emdl} equivalent), byte-identical to g3dcvtr. */
    public byte[] toNsbmd()
    {
        return G3dFile.assembleContainer("BMD0", 2, buildMdl0(modelName));
    }

    /**
     * Authors an NSBMD with the texture embedded as a {@code TEX0} block (the {@code g3dcvtr -eboth} equivalent):
     * the MDL0 block is identical to {@link #toNsbmd()}, with the {@code tex_image}/{@code tex_palette} appended.
     */
    public byte[] toNsbmdWithTextures()
    {
        return G3dFile.assembleContainer("BMD0", 2, buildMdl0(modelName), buildTex0());
    }

    /** Authors a texture-only NSBTX (the {@code g3dcvtr -etex} equivalent) from the {@code .imd}'s texture/palette. */
    public byte[] toNsbtx()
    {
        return G3dFile.assembleContainer("BTX0", 1, buildTex0());
    }

    /** Authors the model and returns it as a {@link ModelSet} (textures embedded when the {@code .imd} has them). */
    public ModelSet toModelSet()
    {
        return new ModelSet(hasTextures() ? toNsbmdWithTextures() : toNsbmd());
    }

    /** Authors the texture archive and returns it as a {@link TextureSet}. */
    public TextureSet toTextureSet()
    {
        return new TextureSet(toNsbtx());
    }

    // === back-compat static shortcuts =================================================================

    /** Model-only NSBMD (the {@code g3dcvtr -emdl} equivalent). Shortcut for {@code fromXml(imdXml).named(modelName).toNsbmd()}. */
    public static byte[] toNsbmd(String imdXml, String modelName)
    {
        return fromXml(imdXml).named(modelName).toNsbmd();
    }

    /** NSBMD with embedded TEX0 (the {@code g3dcvtr -eboth} equivalent). Shortcut for {@code fromXml(imdXml).named(modelName).toNsbmdWithTextures()}. */
    public static byte[] toNsbmdWithTextures(String imdXml, String modelName)
    {
        return fromXml(imdXml).named(modelName).toNsbmdWithTextures();
    }

    /** Texture-only NSBTX (the {@code g3dcvtr -etex} equivalent). Shortcut for {@code fromXml(imdXml).toNsbtx()}. */
    public static byte[] toNsbtx(String imdXml)
    {
        return fromXml(imdXml).toNsbtx();
    }

    private byte[] buildMdl0(String modelName)
    {
        int posScale = 1 << (int) Double.parseDouble(attr("model_info", "pos_scale"));
        double[] boxXyz = doubles(attr("box_test", "xyz"));
        double[] boxWhd = doubles(attr("box_test", "whd"));
        int boxScale = 1 << (int) Double.parseDouble(attr("box_test", "pos_scale"));
        int numVtx = intAttr("output_info", "vertex_size");
        int numPoly = intAttr("output_info", "polygon_size");
        int numTri = intAttr("output_info", "triangle_size");
        int numQuad = intAttr("output_info", "quad_size");
        boolean magnified = posScale != 1;

        List<Node> nodes = parseNodes(); // the full <node_array>, in index order
        List<String> materials = blocks("material"); // each <material .../>
        List<String> polygons = blocks("polygon"); // each <polygon ...>...</polygon>

        List<byte[]> dls = new ArrayList<>();
        for (String poly : polygons)
            dls.add(buildDisplayList(poly));
        int[] firstUnused = new int[1];
        byte[] nodeSet = buildNodeSet(nodes);
        byte[] sbc = generateSbc(nodes, magnified, firstUnused);
        byte[] matSet = buildMaterialSet(materials);
        byte[] shapeSet = buildShapeSet(polygons, dls);

        int headerLen = 0x40;
        int ofsSbc = headerLen + nodeSet.length;
        int ofsMat = ofsSbc + sbc.length;
        int ofsShp = ofsMat + matSet.length;
        int modelLen = ofsShp + shapeSet.length;
        byte[] model = new byte[modelLen];
        {
            MemBuf scratch = MemBuf.create(model);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(0);
            scratch.writer().writeUInt32(modelLen);
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, model, 0, model.length);
        }
        {
            MemBuf scratch = MemBuf.create(model);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(4);
            scratch.writer().writeUInt32(ofsSbc);
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, model, 0, model.length);
        }
        {
            MemBuf scratch = MemBuf.create(model);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(8);
            scratch.writer().writeUInt32(ofsMat);
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, model, 0, model.length);
        }
        {
            MemBuf scratch = MemBuf.create(model);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(12);
            scratch.writer().writeUInt32(ofsShp);
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, model, 0, model.length);
        }
        {
            MemBuf scratch = MemBuf.create(model);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(16);
            scratchWriter.writeUInt32(modelLen); // ofsEndOrEnvelope: past the last section (no envelope)
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, model, 0, model.length);
        }

        int info = 0x14;
        model[info + 3] = (byte) nodes.size(); // numNode
        model[info + 4] = (byte) materials.size(); // matCount
        model[info + 5] = (byte) polygons.size(); // shapeCount
        model[info + 6] = (byte) firstUnused[0]; // firstUnusedMtxStackId (matrix-stack high-water)
        {
            MemBuf scratch = MemBuf.create(model);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(info + 8);
            scratchWriter.writeUInt32((long) posScale * 4096); // posScale (fx32)
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, model, 0, model.length);
        }

        {
            MemBuf scratch = MemBuf.create(model);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(info + 0xC);
            scratchWriter.writeUInt32(4096L / posScale); // inverse posScale
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, model, 0, model.length);
        }

        {
            MemBuf scratch = MemBuf.create(model);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(info + 0x10);
            scratch.writer().writeShort((short) (numVtx));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, model, 0, model.length);
        }
        {
            MemBuf scratch = MemBuf.create(model);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(info + 0x12);
            scratch.writer().writeShort((short) (numPoly));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, model, 0, model.length);
        }
        {
            MemBuf scratch = MemBuf.create(model);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(info + 0x14);
            scratch.writer().writeShort((short) (numTri));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, model, 0, model.length);
        }
        {
            MemBuf scratch = MemBuf.create(model);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(info + 0x16);
            scratch.writer().writeShort((short) (numQuad));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, model, 0, model.length);
        }
        for (int c = 0; c < 3; c++)
        {
            {
                MemBuf scratch = MemBuf.create(model);
                MemBuf.MemBufWriter scratchWriter = scratch.writer();
                int scratchEnd = scratchWriter.getPosition();
                scratchWriter.setPosition(info + 0x18 + c * 2);
                scratchWriter.writeShort((short) (fx(boxXyz[c], 12) & 0xFFFF)); // box min corner
                scratchWriter.setPosition(scratchEnd);
                System.arraycopy(scratch.reader().getBuffer(), 0, model, 0, model.length);
            }

            {
                MemBuf scratch = MemBuf.create(model);
                MemBuf.MemBufWriter scratchWriter = scratch.writer();
                int scratchEnd = scratchWriter.getPosition();
                scratchWriter.setPosition(info + 0x1E + c * 2);
                scratchWriter.writeShort((short) (fx(boxWhd[c], 12) & 0xFFFF)); // box dimensions
                scratchWriter.setPosition(scratchEnd);
                System.arraycopy(scratch.reader().getBuffer(), 0, model, 0, model.length);
            }

        }
        {
            MemBuf scratch = MemBuf.create(model);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(info + 0x24);
            scratchWriter.writeUInt32((long) boxScale * 4096); // boxPosScale (fx32)
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, model, 0, model.length);
        }

        {
            MemBuf scratch = MemBuf.create(model);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(info + 0x28);
            scratchWriter.writeUInt32(4096L / boxScale); // inverse boxPosScale
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, model, 0, model.length);
        }

        System.arraycopy(nodeSet, 0, model, headerLen, nodeSet.length);
        System.arraycopy(sbc, 0, model, ofsSbc, sbc.length);
        System.arraycopy(matSet, 0, model, ofsMat, matSet.length);
        System.arraycopy(shapeSet, 0, model, ofsShp, shapeSet.length);

        MemBuf word0Buf = MemBuf.create();
        word0Buf.writer().writeUInt32(0);
        byte[] word0 = word0Buf.reader().getBuffer();
        int modelStart = 8 + serialize(G3dDictionary.build(List.of(modelName), List.of(word0), 4)).length;
        MemBuf word1Buf = MemBuf.create();
        word1Buf.writer().writeUInt32(modelStart);
        byte[] word1 = word1Buf.reader().getBuffer();
        byte[] modelDict = serialize(G3dDictionary.build(List.of(modelName), List.of(word1), 4));
        int mdl0Len = modelStart + model.length;
        byte[] mdl0 = new byte[mdl0Len];
        System.arraycopy("MDL0".getBytes(StandardCharsets.US_ASCII), 0, mdl0, 0, 4);
        {
            MemBuf scratch = MemBuf.create(mdl0);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(4);
            scratch.writer().writeUInt32(mdl0Len);
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, mdl0, 0, mdl0.length);
        }
        System.arraycopy(modelDict, 0, mdl0, 8, modelDict.length);
        System.arraycopy(model, 0, mdl0, modelStart, model.length);
        return mdl0;
    }

    // --- TEX0 block from the .imd tex_image (palette16 bitmap) + tex_palette, in g3dcvtr's exact layout ---
    private byte[] buildTex0()
    {
        String texName = attr("tex_image", "name");
        String pltName = attr("tex_palette", "name");
        int w = intAttr("tex_image", "width"), h = intAttr("tex_image", "height");
        boolean color0Transparent = "transparency".equals(attr("tex_image", "color0_mode"));

        // texels: the bitmap is 4-hex-digit big-endian 16-bit words; store each word little-endian (the
        // low byte holds the first two 4bpp pixels, low nibble first)
        String bmpHex = tagContent(imd, "bitmap").replaceAll("[^0-9a-fA-F]", "");
        byte[] texel = new byte[w * h / 2];
        for (int i = 0; i < texel.length; i += 2)
        {
            int word = Integer.parseInt(bmpHex.substring(i * 2, i * 2 + 4), 16);
            texel[i] = (byte) word;
            texel[i + 1] = (byte) (word >> 8);
        }
        // palette: each 4-hex-digit token is a BGR555 color, stored little-endian
        String[] palTok = tagContent(imd, "tex_palette").trim().split("\\s+");
        byte[] palette = new byte[palTok.length * 2];
        for (int i = 0; i < palTok.length; i++)
        {
            int c = (int) Long.parseLong(palTok[i], 16);
            palette[i * 2] = (byte) c;
            palette[i * 2 + 1] = (byte) (c >> 8);
        }

        int widthSel = Integer.numberOfTrailingZeros(w / 8);
        int heightSel = Integer.numberOfTrailingZeros(h / 8);
        long texImageParam = ((long) 3 << 26) | ((long) widthSel << 20) | ((long) heightSel << 23)
                | (color0Transparent ? 1L << 29 : 0);
        byte[] texRec = new byte[8];
        {
            MemBuf scratch = MemBuf.create(texRec);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(0);
            scratch.writer().writeUInt32(texImageParam);
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, texRec, 0, texRec.length);
        }
        {
            MemBuf scratch = MemBuf.create(texRec);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(4);
            scratchWriter.writeUInt32(0x80008010L); // extraParam (observed; texel-key/flags)
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, texRec, 0, texRec.length);
        }

        byte[] texDict = serialize(G3dDictionary.build(List.of(texName), List.of(texRec), 8));
        MemBuf word2Buf = MemBuf.create();
        word2Buf.writer().writeUInt32(0);
        byte[] word2 = word2Buf.reader().getBuffer();
        byte[] plttDict = serialize(G3dDictionary.build(List.of(pltName), List.of(word2), 4));

        int header = 0x3c;
        int ofsDict = header;
        int ofsPltDict = ofsDict + texDict.length;
        int ofsTexData = ofsPltDict + plttDict.length;
        int ofsPltData = ofsTexData + texel.length;
        int blockSize = ofsPltData + palette.length;
        byte[] b = new byte[blockSize];
        System.arraycopy("TEX0".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 4);
        {
            MemBuf scratch = MemBuf.create(b);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(4);
            scratch.writer().writeUInt32(blockSize);
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, b, 0, b.length);
        }
        {
            MemBuf scratch = MemBuf.create(b);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(12);
            scratchWriter.writeShort((short) (texel.length >> 3)); // sizeTex (8-byte units)
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, b, 0, b.length);
        }

        {
            MemBuf scratch = MemBuf.create(b);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(14);
            scratch.writer().writeShort((short) (ofsDict));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, b, 0, b.length);
        }
        {
            MemBuf scratch = MemBuf.create(b);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(20);
            scratch.writer().writeUInt32(ofsTexData);
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, b, 0, b.length);
        }
        {
            MemBuf scratch = MemBuf.create(b);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(30);
            scratchWriter.writeShort((short) (ofsDict)); // Tex4x4Info dict (points at the tex dict; no 4x4 data)
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, b, 0, b.length);
        }

        {
            MemBuf scratch = MemBuf.create(b);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(36);
            scratchWriter.writeUInt32(ofsPltData); // ofsTex4x4Data / PlttIdx point past the texel data
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, b, 0, b.length);
        }

        {
            MemBuf scratch = MemBuf.create(b);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(40);
            scratch.writer().writeUInt32(ofsPltData);
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, b, 0, b.length);
        }
        {
            MemBuf scratch = MemBuf.create(b);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(48);
            scratchWriter.writeShort((short) (palette.length >> 3)); // sizePltt (8-byte units)
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, b, 0, b.length);
        }

        {
            MemBuf scratch = MemBuf.create(b);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(52);
            scratch.writer().writeShort((short) (ofsPltDict));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, b, 0, b.length);
        }
        {
            MemBuf scratch = MemBuf.create(b);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(56);
            scratchWriter.writeUInt32(ofsPltData); // plttDataOfs
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, b, 0, b.length);
        }

        System.arraycopy(texDict, 0, b, ofsDict, texDict.length);
        System.arraycopy(plttDict, 0, b, ofsPltDict, plttDict.length);
        System.arraycopy(texel, 0, b, ofsTexData, texel.length);
        System.arraycopy(palette, 0, b, ofsPltData, palette.length);
        return b;
    }

    // the text between an element's opening ">" and its "</tag>" (skipping the opening tag's attributes).
    // Matches the tag name at a word boundary so "tex_palette" doesn't hit "tex_palette_array".
    private static String tagContent(String s, String tag)
    {
        Matcher m = Pattern.compile("<" + tag + "[\\s>]").matcher(s);
        if (!m.find())
            return "";
        int gt = s.indexOf('>', m.start());
        int close = s.indexOf("</" + tag + ">", gt);
        return gt < 0 || close < 0 ? "" : s.substring(gt + 1, close);
    }
    private static int hexDigit(char c)
    {
        return Character.digit(c, 16);
    }
    private static int polygonMode(String m)
    {
        return "decal".equals(m) ? 1 : "toon".equals(m) ? 2 : "shadow".equals(m) ? 3 : 0;
    }
    // teximage_param wrap bits for one axis: repeat sets the repeat bit; flip sets repeat+flip; clamp = 0
    private static long wrapBits(String mode, int repeatBit, int flipBit)
    {
        if ("flip".equals(mode))
            return (1L << repeatBit) | (1L << flipBit);
        if ("repeat".equals(mode))
            return 1L << repeatBit;
        return 0;
    }

    // --- geometry: translate one polygon's primitive commands into GPU commands, pad the DL to /8 ---
    private byte[] buildDisplayList(String polygon)
    {
        List<DisplayList.Command> cmds = new ArrayList<>();
        Matcher pm = Pattern.compile("<primitive [^>]*type=\"(\\w+)\"[^>]*>(.*?)</primitive>", Pattern.DOTALL).matcher(polygon);
        pm.find();
        String type = pm.group(1);
        int prim = type.equals("triangles") ? 0 : type.equals("quads") ? 1 : type.equals("triangle_strip") ? 2 : 3;
        cmds.add(new DisplayList.Command(0x40, new int[] { prim })); // BEGIN_VTXS
        Matcher cm = Pattern.compile("<(mtx|tex|nrm|clr|pos_s|pos_xy|pos_xz|pos_yz|pos_diff)([^>]*)/>").matcher(pm.group(2));
        while (cm.find())
        {
            double[] v = doubles(firstQuoted(cm.group(2)));
            switch (cm.group(1))
            {
                case "mtx":
                    break; // single node: the current matrix is already bound (no MTX_RESTORE)
                case "tex":
                    cmds.add(cmd(0x22, (fx(v[0], 4) & 0xFFFF) | ((fx(v[1], 4) & 0xFFFF) << 16)));
                    break;
                case "nrm":
                    cmds.add(cmd(0x21, pack10(fx(v[0], 9), fx(v[1], 9), fx(v[2], 9))));
                    break;
                case "clr":
                    cmds.add(cmd(0x20, (fx(v[0], 0) & 0x1F) | ((fx(v[1], 0) & 0x1F) << 5) | ((fx(v[2], 0) & 0x1F) << 10)));
                    break;
                case "pos_s":
                    if (exact10(v[0]) && exact10(v[1]) && exact10(v[2]))
                        cmds.add(cmd(0x24, pack10(fx(v[0], 6), fx(v[1], 6), fx(v[2], 6)))); // VTX_10
                    else
                        cmds.add(cmd(0x23, (fx(v[0], 12) & 0xFFFF) | ((fx(v[1], 12) & 0xFFFF) << 16), fx(v[2], 12) & 0xFFFF)); // VTX_16
                    break;
                case "pos_xy":
                    cmds.add(cmd(0x25, (fx(v[0], 12) & 0xFFFF) | ((fx(v[1], 12) & 0xFFFF) << 16)));
                    break;
                case "pos_xz":
                    cmds.add(cmd(0x26, (fx(v[0], 12) & 0xFFFF) | ((fx(v[1], 12) & 0xFFFF) << 16)));
                    break;
                case "pos_yz":
                    cmds.add(cmd(0x27, (fx(v[0], 12) & 0xFFFF) | ((fx(v[1], 12) & 0xFFFF) << 16)));
                    break;
                case "pos_diff":
                    cmds.add(cmd(0x28, pack10(fx(v[0], 12), fx(v[1], 12), fx(v[2], 12))));
                    break;
            }
        }
        cmds.add(cmd(0x41)); // END_VTXS
        byte[] dl = DisplayList.encodeCommands(cmds);
        int pad = (8 - dl.length % 8) % 8;
        return pad == 0 ? dl : java.util.Arrays.copyOf(dl, dl.length + pad);
    }

    // --- node set: node dictionary + one identity node local matrix (flags 0xf807, rotation[0][0] = 1) ---
    private byte[] buildNodeSet(List<Node> nodes)
    {
        int n = nodes.size();
        List<String> names = new ArrayList<>();
        for (Node nd : nodes)
            names.add(nd.name);
        // each node's local matrix is encoded to a variable-length struct (identity is 4 bytes; a translated/
        // scaled/rotated node is longer). The structs sit contiguously right after the dict; each dict record
        // is the byte offset (from the node-set start) to its struct. Measure the dict, then point at the structs.
        byte[][] structs = new byte[n][];
        for (int i = 0; i < n; i++)
            structs[i] = encodeNodeStruct(nodes.get(i));
        int dictSize = serialize(G3dDictionary.build(names, placeholders(n), 4)).length;
        List<byte[]> recs = new ArrayList<>();
        int cursor = dictSize;

        for (int i = 0; i < n; i++)
        {
            MemBuf word3Buf = MemBuf.create();
            word3Buf.writer().writeUInt32(cursor);
            byte[] word3 = word3Buf.reader().getBuffer();
            recs.add(word3);
            cursor += structs[i].length;
        }
        MemBuf buf = MemBuf.create();
        MemBuf.MemBufWriter out = buf.writer();
        out.write(serialize(G3dDictionary.build(names, recs, 4)));
        for (byte[] s : structs)
            out.write(s);

        return buf.reader().getBuffer();
    }

    // --- node local matrix (NNS_G3dResNodeData), the inverse of Model.parseNodeLocals, byte-identical to
    //     g3dcvtr. Layout: flags(u16) · _00(fx16 = rotation[0][0], 1.0 when rotation omitted) · [translation
    //     3×fx32 if present] · [rotation if present] · [scale 3×fx32 + inverse 3×fx32 if present]. flags bit0/1/2
    //     omit translation/rotation/scale (set when that component is identity); bit3 selects pivot-compressed
    //     rotation. Rotation is stored transposed: Mt = (Rz·Ry·Rx)ᵀ from the Euler angles (g3dcvtr's convention).
    //     A principal-axis matrix (one row-major cell is ±1 with a zero row and column) is pivot-compressed to
    //     two values av/bv (the 2×2 minor) with the pivot cell in flags bits 4–7 and sign flags 0x100/0x200/
    //     0x400; otherwise the full 3×3 remainder is written as 8×fx16. ---
    private byte[] encodeNodeStruct(Node nd)
    {
        // Identity components are omitted; a principal-axis rotation is stored as a pivot.
        boolean omitT = every(nd.translate, 0);
        boolean omitS = every(nd.scale, 1);
        boolean omitR = every(nd.rotate, 0);
        NodeRotation rotation = nodeRotation(nd, omitR);
        int flags = nodeFlags(omitT, omitR, omitS, rotation);

        return nodeBytes(nd, flags, rotation, omitT, omitR, omitS);
    }

    /**
     * @param values three components
     * @param target the value each component must equal
     * @return whether every component equals {@code target}
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData"
     */
    private static boolean every(double[] values, double target)
    {
        return everyFrom(values, target, 0);
    }

    /**
     * @param values three components
     * @param target the value each remaining component must equal
     * @param index the component to test
     * @return whether this component and the ones after it equal {@code target}
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData"
     */
    private static boolean everyFrom(double[] values, double target, int index)
    {
        if (index >= values.length)
            return true;

        return componentMatches(values, target, index);
    }

    /**
     * @param values three components
     * @param target the value this component must equal
     * @param index the component to test
     * @return whether this component equals {@code target} and the rest do too
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData"
     */
    private static boolean componentMatches(double[] values, double target, int index)
    {
        if (values[index] != target)
            return false;

        return everyFrom(values, target, index + 1);
    }

    /**
     * @param nd the node
     * @param omit whether rotation is identity and therefore stored as none
     * @return the transposed matrix, its leading cell, and a pivot when one exists
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData"
     */
    private static NodeRotation nodeRotation(Node nd, boolean omit)
    {
        if (omit)
            return new NodeRotation(null, null, 1.0);

        double[][] mt = rotationTransposed(nd.rotate);

        return new NodeRotation(mt, findPivot(mt), mt[0][0]);
    }

    /**
     * @param omitT translation is identity
     * @param omitR rotation is identity
     * @param omitS scale is identity
     * @param rotation the stored rotation
     * @return the node flags word
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData"
     */
    private static int nodeFlags(boolean omitT, boolean omitR, boolean omitS, NodeRotation rotation)
    {
        int flags = 0xf800;
        flags = withBit(flags, omitT, 0x1);
        flags = withBit(flags, omitR, 0x2);
        flags = withBit(flags, omitS, 0x4);

        return withPivot(flags, rotation.pivot);
    }

    /**
     * @param flags the word so far
     * @param set whether {@code bit} is included
     * @param bit the flag bit
     * @return {@code flags} with {@code bit} set when requested
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData"
     */
    private static int withBit(int flags, boolean set, int bit)
    {
        if (!set)
            return flags;

        return flags | bit;
    }

    /**
     * @param flags the word so far
     * @param pivot {@code {row, col, oneNeg, cNeg, dNeg}}, or null for a full matrix
     * @return {@code flags} with the pivot selector and sign bits
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData"
     */
    private static int withPivot(int flags, int[] pivot)
    {
        if (pivot == null)
            return flags;

        int sel = pivot[0] * 3 + pivot[1];

        return flags | 0x8 | (sel << 4) | signBit(pivot[2], 0x100) | signBit(pivot[3], 0x200) | signBit(pivot[4], 0x400);
    }

    /**
     * @param sign zero when the pivot sign is positive
     * @param bit the flag bit for a negative sign
     * @return {@code bit} when {@code sign} is non-zero
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData"
     */
    private static int signBit(int sign, int bit)
    {
        if (sign == 0)
            return 0;

        return bit;
    }

    /**
     * @param nd the node
     * @param flags the node flags
     * @param rotation the stored rotation
     * @param omitT translation is omitted
     * @param omitR rotation is omitted
     * @param omitS scale is omitted
     * @return the variable-length node struct
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData"
     */
    private static byte[] nodeBytes(Node nd, int flags, NodeRotation rotation, boolean omitT, boolean omitR, boolean omitS)
    {
        MemBuf buf = MemBuf.create();
        MemBuf.MemBufWriter out = buf.writer();
        out.writeShort((short) flags);
        out.writeShort((short) (fx(rotation.m00, 12) & 0xFFFF));
        writeTranslation(out, nd, omitT);
        writeRotation(out, rotation, omitR);
        writeScale(out, nd, omitS);

        return buf.reader().getBuffer();
    }

    /**
     * @param out the node struct
     * @param nd the node
     * @param omit whether translation is omitted
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData"
     */
    private static void writeTranslation(MemBuf.MemBufWriter out, Node nd, boolean omit)
    {
        if (omit)
            return;

        writeFx32(out, nd.translate);
    }

    /**
     * @param out the node struct
     * @param rotation the stored rotation
     * @param omit whether rotation is omitted
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData"
     */
    private static void writeRotation(MemBuf.MemBufWriter out, NodeRotation rotation, boolean omit)
    {
        if (omit)
            return;

        writeRotationBody(out, rotation);
    }

    /**
     * @param out the node struct
     * @param rotation the stored rotation
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData"
     */
    private static void writeRotationBody(MemBuf.MemBufWriter out, NodeRotation rotation)
    {
        if (rotation.pivot != null)
        {
            writePivotPair(out, rotation);

            return;
        }

        writeFullRotation(out, rotation.mt);
    }

    /**
     * @param out the node struct
     * @param rotation a pivot-compressed rotation
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData"
     */
    private static void writePivotPair(MemBuf.MemBufWriter out, NodeRotation rotation)
    {
        int row = rotation.pivot[0];
        int col = rotation.pivot[1];
        int[] rows = other(row);
        int[] cols = other(col);
        out.writeShort((short) (fx(rotation.mt[rows[0]][cols[0]], 12) & 0xFFFF));
        out.writeShort((short) (fx(rotation.mt[rows[0]][cols[1]], 12) & 0xFFFF));
    }

    /**
     * @param out the node struct
     * @param mt the transposed rotation
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData"
     */
    private static void writeFullRotation(MemBuf.MemBufWriter out, double[][] mt)
    {
        int[][] order = { { 0, 1 }, { 0, 2 }, { 1, 0 }, { 1, 1 }, { 1, 2 }, { 2, 0 }, { 2, 1 }, { 2, 2 } };

        for (int[] cell : order)
            out.writeShort((short) (fx(mt[cell[0]][cell[1]], 12) & 0xFFFF));
    }

    /**
     * @param out the node struct
     * @param nd the node
     * @param omit whether scale is omitted
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData"
     */
    private static void writeScale(MemBuf.MemBufWriter out, Node nd, boolean omit)
    {
        if (omit)
            return;

        writeFx32(out, nd.scale);
        writeFx32(out, inverseScale(nd.scale));
    }

    /**
     * @param scale the three scale components
     * @return their reciprocals, which the node struct stores and the runtime ignores
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData"
     */
    private static double[] inverseScale(double[] scale)
    {
        return new double[] { 1.0 / scale[0], 1.0 / scale[1], 1.0 / scale[2] };
    }

    /**
     * @param out the node struct
     * @param values three fx32 components
     * @see "NitroSDK include/nitro/fx/fx.h"
     */
    private static void writeFx32(MemBuf.MemBufWriter out, double[] values)
    {
        for (double value : values)
            out.writeUInt32(fx(value, 12) & 0xFFFFFFFFL);
    }

    /**
     * A node's stored rotation: the transposed matrix, an optional pivot, and the leading cell.
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct.h NNSG3dResNodeData"
     */
    private static final class NodeRotation
    {
        private final double[][] mt;
        private final int[] pivot;
        private final double m00;

        private NodeRotation(double[][] mt, int[] pivot, double m00)
        {
            this.mt = mt;
            this.pivot = pivot;
            this.m00 = m00;
        }
    }

    // Mt = (Rz·Ry·Rx)ᵀ from Euler angles in degrees — the transposed local rotation g3dcvtr stores.
    private static double[][] rotationTransposed(double[] deg)
    {
        double x = Math.toRadians(deg[0]), y = Math.toRadians(deg[1]), z = Math.toRadians(deg[2]);
        double[][] rx = { { 1, 0, 0 }, { 0, Math.cos(x), -Math.sin(x) }, { 0, Math.sin(x), Math.cos(x) } };
        double[][] ry = { { Math.cos(y), 0, Math.sin(y) }, { 0, 1, 0 }, { -Math.sin(y), 0, Math.cos(y) } };
        double[][] rz = { { Math.cos(z), -Math.sin(z), 0 }, { Math.sin(z), Math.cos(z), 0 }, { 0, 0, 1 } };
        double[][] r = matMul(matMul(rz, ry), rx);
        double[][] t = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                t[i][j] = r[j][i];
        return t;
    }

    private static double[][] matMul(double[][] a, double[][] b)
    {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                for (int k = 0; k < 3; k++)
                    r[i][j] += a[i][k] * b[k][j];
        return r;
    }

    // a principal-axis matrix has a cell that is ±1 with a zero row and column; g3dcvtr picks the first such
    // cell in row-major order and pivot-compresses. Returns {row, col, oneNeg, cNeg, dNeg} or null (use full form).
    private static int[] findPivot(double[][] m)
    {
        double eps = 1e-4;
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++)
            {
                if (Math.abs(Math.abs(m[r][c]) - 1) > eps)
                    continue;
                boolean clean = true;
                for (int k = 0; k < 3; k++)
                    if ((k != c && Math.abs(m[r][k]) > eps) || (k != r && Math.abs(m[k][c]) > eps))
                    {
                        clean = false;
                        break;
                    }
                if (!clean)
                    continue;
                int[] rows = other(r), cols = other(c);
                double av = m[rows[0]][cols[0]], bv = m[rows[0]][cols[1]];
                double mc = m[rows[1]][cols[0]], md = m[rows[1]][cols[1]];
                return new int[] { r, c, m[r][c] < 0 ? 1 : 0, mc * bv < 0 ? 1 : 0, md * av < 0 ? 1 : 0 };
            }
        return null;
    }

    // the two indices other than i, in ascending order
    private static int[] other(int i)
    {
        return i == 0 ? new int[] { 1, 2 } : i == 1 ? new int[] { 0, 2 } : new int[] { 0, 1 };
    }

    // --- SBC render stream (general, multi-node): a pre-order walk of the node tree. Mirrors g3dcvtr's
    //     matrix-stack allocator (validated byte-for-byte against retail): a node whose matrix is reused by a
    //     child or by more than one of its own draws is NODEDESC-stored to the lowest free stack slot; a node
    //     whose parent's matrix isn't the current one restores it. Draws emit POSSCALE {MAT[,SHP]}* POSSCALE|end,
    //     and a material used by more than one draw is stored on first use / restored on reuse (material stack).
    //     outFirstUnused[0] receives the matrix-stack high-water mark (the model header's firstUnusedMtxStackId). ---
    private byte[] generateSbc(List<Node> nodes, boolean magnified, int[] outFirstUnused)
    {
        List<List<Integer>> children = childLists(nodes);
        int[] slotOf = new int[nodes.size()];
        java.util.Arrays.fill(slotOf, -1);
        boolean[] slotUsed = new boolean[64];
        MemBuf buf = MemBuf.create();
        int maxSlot = writeSbc(nodes, magnified, children, slotOf, slotUsed, materialUses(nodes), buf.writer());
        padSbc(buf.writer());
        outFirstUnused[0] = maxSlot + 1;

        return buf.reader().getBuffer();
    }

    /**
     * @param nodes the node tree
     * @return each node's children, in node order
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS"
     */
    private static List<List<Integer>> childLists(List<Node> nodes)
    {
        List<List<Integer>> children = new ArrayList<>();
        int index = 0;

        while (index < nodes.size())
        {
            children.add(new ArrayList<>());
            index++;
        }

        linkChildren(nodes, children);

        return children;
    }

    /**
     * @param nodes the node tree
     * @param children the lists to fill
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS"
     */
    private static void linkChildren(List<Node> nodes, List<List<Integer>> children)
    {
        for (int index = 0; index < nodes.size(); index++)
            linkOne(nodes, children, index);
    }

    /**
     * @param nodes the node tree
     * @param children the lists to fill
     * @param index the child node
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS"
     */
    private static void linkOne(List<Node> nodes, List<List<Integer>> children, int index)
    {
        int parent = nodes.get(index).parent;

        if (parent == index || parent < 0 || parent >= nodes.size())
            return;

        children.get(parent).add(index);
    }

    /**
     * @param nodes the node tree
     * @return how many draws use each material
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS"
     */
    private static Map<Integer, Integer> materialUses(List<Node> nodes)
    {
        Map<Integer, Integer> uses = new java.util.HashMap<>();

        for (Node node : nodes)
            countDraws(uses, node);

        return uses;
    }

    /**
     * @param uses the material counts
     * @param node one node
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS"
     */
    private static void countDraws(Map<Integer, Integer> uses, Node node)
    {
        for (int[] draw : node.draw)
            uses.merge(draw[0], 1, Integer::sum);
    }

    /**
     * @return the highest matrix-stack slot stored, or -1 when none was
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS"
     */
    private static int writeSbc(List<Node> nodes, boolean magnified, List<List<Integer>> children, int[] slotOf,
            boolean[] slotUsed, Map<Integer, Integer> matUse, MemBuf.MemBufWriter out)
    {
        Set<Integer> matSeen = new HashSet<>();
        int cur = -1;
        int maxSlot = -1;

        for (int n = 0; n < nodes.size(); n++)
        {
            maxSlot = emitNode(nodes, n, magnified, children, slotOf, slotUsed, matUse, matSeen, out, cur, maxSlot);
            cur = n;
        }

        out.writeByte((byte) 0x01);

        return maxSlot;
    }

    /**
     * @return the highest slot stored so far
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS"
     */
    private static int emitNode(List<Node> nodes, int n, boolean magnified, List<List<Integer>> children, int[] slotOf,
            boolean[] slotUsed, Map<Integer, Integer> matUse, Set<Integer> matSeen, MemBuf.MemBufWriter out, int cur, int maxSlot)
    {
        Node node = nodes.get(n);
        int restore = restoreSlot(node, n, cur, slotOf);
        int store = storeSlot(children.get(n), node, n, slotOf, slotUsed);
        writeDesc(out, n, node.parent, store, restore);
        writeDrawBlock(out, node, n, magnified, matUse, matSeen);
        releaseSlots(children, slotOf, slotUsed, n);

        return raiseMax(maxSlot, store);
    }

    /**
     * @return the parent's stored slot when this node must restore it, otherwise -1
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS"
     */
    private static int restoreSlot(Node node, int n, int cur, int[] slotOf)
    {
        if (node.parent == n || cur == node.parent)
            return -1;

        return slotOf[node.parent];
    }

    /**
     * @return the slot this node stores into, or -1 when its matrix is not reused
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS"
     */
    private static int storeSlot(List<Integer> kids, Node node, int n, int[] slotOf, boolean[] slotUsed)
    {
        if (kids.isEmpty() && node.draw.size() <= 1)
            return -1;

        int store = lowestFree(slotUsed);
        slotUsed[store] = true;
        slotOf[n] = store;

        return store;
    }

    /**
     * @return the greater of the previous high-water mark and {@code store}
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS"
     */
    private static int raiseMax(int maxSlot, int store)
    {
        if (store > maxSlot)
            return store;

        return maxSlot;
    }

    /**
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS"
     */
    private static void writeDesc(MemBuf.MemBufWriter out, int n, int parent, int store, int restore)
    {
        int flags = 0x06 | flagBit(store, 0x20) | flagBit(restore, 0x40);
        out.writeByte((byte) flags);
        out.writeByte((byte) n);
        out.writeByte((byte) parent);
        out.writeByte((byte) 0);
        writeOptional(out, store);
        writeOptional(out, restore);
    }

    /**
     * @param value a stack slot, or -1 when the bit is clear
     * @param bit the NODEDESC bit
     * @return {@code bit} when a slot is present
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS"
     */
    private static int flagBit(int value, int bit)
    {
        if (value < 0)
            return 0;

        return bit;
    }

    /** @param value a stack slot, or -1 when nothing is written */
    private static void writeOptional(MemBuf.MemBufWriter out, int value)
    {
        if (value < 0)
            return;

        out.writeByte((byte) value);
    }

    /**
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS"
     */
    private static void writeDrawBlock(MemBuf.MemBufWriter out, Node node, int n, boolean magnified,
            Map<Integer, Integer> matUse, Set<Integer> matSeen)
    {
        if (!node.draws)
            return;

        out.writeByte((byte) 0x02);
        out.writeByte((byte) n);
        out.writeByte((byte) node.vis);
        writeBillboard(out, node, n);
        writePosScale(out, magnified, 0x0b);
        writeDraws(out, node, matUse, matSeen);
        writePosScale(out, magnified, 0x2b);
    }

    /** @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS" */
    private static void writeBillboard(MemBuf.MemBufWriter out, Node node, int n)
    {
        if (node.bb == 1)
        {
            writePair(out, 0x07, n);

            return;
        }

        writeYBillboard(out, node, n);
    }

    /** @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS" */
    private static void writeYBillboard(MemBuf.MemBufWriter out, Node node, int n)
    {
        if (node.bb != 2)
            return;

        writePair(out, 0x08, n);
    }

    /** @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS" */
    private static void writePair(MemBuf.MemBufWriter out, int opcode, int n)
    {
        out.writeByte((byte) opcode);
        out.writeByte((byte) n);
    }

    /** @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS" */
    private static void writePosScale(MemBuf.MemBufWriter out, boolean magnified, int opcode)
    {
        if (!magnified)
            return;

        out.writeByte((byte) opcode);
    }

    /** @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS" */
    private static void writeDraws(MemBuf.MemBufWriter out, Node node, Map<Integer, Integer> matUse, Set<Integer> matSeen)
    {
        for (int[] draw : node.draw)
            writeDraw(out, draw, matUse, matSeen);
    }

    /** @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS" */
    private static void writeDraw(MemBuf.MemBufWriter out, int[] draw, Map<Integer, Integer> matUse, Set<Integer> matSeen)
    {
        out.writeByte((byte) (0x04 | materialFlag(matUse, matSeen, draw[0])));
        out.writeByte((byte) draw[0]);
        out.writeByte((byte) 0x05);
        out.writeByte((byte) draw[1]);
    }

    /**
     * @return 0x20 the first time a shared material is drawn, 0x40 after that, otherwise 0
     * @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS"
     */
    private static int materialFlag(Map<Integer, Integer> matUse, Set<Integer> matSeen, int material)
    {
        if (matUse.getOrDefault(material, 1) <= 1)
            return 0;

        return seenFlag(matSeen, material);
    }

    /** @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS" */
    private static int seenFlag(Set<Integer> matSeen, int material)
    {
        if (matSeen.add(material))
            return 0x20;

        return 0x40;
    }

    /** @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS" */
    private static void releaseSlots(List<List<Integer>> children, int[] slotOf, boolean[] slotUsed, int n)
    {
        for (int q = 0; q < children.size(); q++)
            releaseOne(children.get(q), slotOf, slotUsed, q, n);
    }

    /** @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS" */
    private static void releaseOne(List<Integer> kids, int[] slotOf, boolean[] slotUsed, int q, int n)
    {
        if (kids.isEmpty() || kids.get(kids.size() - 1) != n)
            return;

        clearSlot(slotOf, slotUsed, q);
    }

    /** @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS" */
    private static void clearSlot(int[] slotOf, boolean[] slotUsed, int q)
    {
        if (slotOf[q] < 0)
            return;

        slotUsed[slotOf[q]] = false;
    }

    /** @see "NitroSDK include/nnsys/g3d/binres/res_struct_accessor.h NNS_G3dRS" */
    private static void padSbc(MemBuf.MemBufWriter out)
    {
        while (out.getPosition() % 4 != 0)
            out.writeByte((byte) 0);
    }

    private static int lowestFree(boolean[] used)
    {
        for (int i = 0; i < used.length; i++)
            if (!used[i])
                return i;
        return 0;
    }

    // --- material set: header, material dict, tex/pltt->material dicts (grouped by resource), index lists,
    //     then the material structs. Materials sharing a texture/palette are grouped into one dict entry
    //     whose index list names the materials that use it. ---
    private byte[] buildMaterialSet(List<String> materials)
    {
        int n = materials.size();
        List<byte[]> matStructs = new ArrayList<>();
        List<String> matNames = new ArrayList<>();
        // group material indices by texture/palette name; the dict entries are ordered by name (bytewise),
        // matching g3dcvtr, and each entry lists the materials that use it (in material order)
        java.util.TreeMap<String, List<Integer>> byTex = new java.util.TreeMap<>();
        java.util.TreeMap<String, List<Integer>> byPlt = new java.util.TreeMap<>();
        for (int i = 0; i < n; i++)
        {
            String m = materials.get(i);
            matNames.add(a(m, "name"));
            int texIdx = Integer.parseInt(a(m, "tex_image_idx"));
            int pltIdx = Integer.parseInt(a(m, "tex_palette_idx"));
            String texName = a(blocks("tex_image").get(texIdx), "name");
            String pltName = a(blocks("tex_palette").get(pltIdx), "name");
            byTex.computeIfAbsent(texName, k -> new ArrayList<>()).add(i);
            byPlt.computeIfAbsent(pltName, k -> new ArrayList<>()).add(i);
            matStructs.add(buildMaterialStruct(m, texIdx));
        }

        List<byte[]> dummy = new ArrayList<>();
        for (int i = 0; i < n; i++)
        {
            MemBuf word4Buf = MemBuf.create();
            word4Buf.writer().writeUInt32(0);
            byte[] word4 = word4Buf.reader().getBuffer();
            dummy.add(word4);
        }
        int matDictLen = serialize(G3dDictionary.build(matNames, dummy, 4)).length;
        int texDictLen = serialize(G3dDictionary.build(new ArrayList<>(byTex.keySet()), placeholders(byTex.size()), 4)).length;
        int pltDictLen = serialize(G3dDictionary.build(new ArrayList<>(byPlt.keySet()), placeholders(byPlt.size()), 4)).length;
        int ofsTexDict = 4 + matDictLen;
        int ofsPltDict = ofsTexDict + texDictLen;
        int idxBase = ofsPltDict + pltDictLen;
        // index-list region: each tex group's material list, then each pltt group's, padded to /4
        MemBuf idxBuf = MemBuf.create();
        MemBuf.MemBufWriter idx = idxBuf.writer();
        List<byte[]> texRecs = new ArrayList<>();

        for (Map.Entry<String, List<Integer>> entry : byTex.entrySet())
        {
            MemBuf word5Buf = MemBuf.create();
            word5Buf.writer().writeUInt32(((idxBase + idx.getPosition()) & 0xFFFF) | ((long) entry.getValue().size() << 16));
            byte[] word5 = word5Buf.reader().getBuffer();
            texRecs.add(word5);

            for (int material : entry.getValue())
                idx.writeByte((byte) material);
        }

        List<byte[]> pltRecs = new ArrayList<>();

        for (Map.Entry<String, List<Integer>> entry : byPlt.entrySet())
        {
            MemBuf word6Buf = MemBuf.create();
            word6Buf.writer().writeUInt32(((idxBase + idx.getPosition()) & 0xFFFF) | ((long) entry.getValue().size() << 16));
            byte[] word6 = word6Buf.reader().getBuffer();
            pltRecs.add(word6);

            for (int material : entry.getValue())
                idx.writeByte((byte) material);
        }
        int idxLen = (idx.getPosition() + 3) & ~3;
        int structsBase = idxBase + idxLen;
        int setLen = structsBase + 44 * n;

        byte[] set = new byte[setLen];
        {
            MemBuf scratch = MemBuf.create(set);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(0);
            scratch.writer().writeShort((short) (ofsTexDict));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, set, 0, set.length);
        }
        {
            MemBuf scratch = MemBuf.create(set);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(2);
            scratch.writer().writeShort((short) (ofsPltDict));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, set, 0, set.length);
        }
        List<byte[]> matRecs = new ArrayList<>();
        for (int i = 0; i < n; i++)
        {
            MemBuf word7Buf = MemBuf.create();
            word7Buf.writer().writeUInt32(structsBase + i * 44);
            byte[] word7 = word7Buf.reader().getBuffer();
            matRecs.add(word7);
        }
        System.arraycopy(serialize(G3dDictionary.build(matNames, matRecs, 4)), 0, set, 4, matDictLen);
        System.arraycopy(serialize(G3dDictionary.build(new ArrayList<>(byTex.keySet()), texRecs, 4)), 0, set, ofsTexDict, texDictLen);
        System.arraycopy(serialize(G3dDictionary.build(new ArrayList<>(byPlt.keySet()), pltRecs, 4)), 0, set, ofsPltDict, pltDictLen);
        System.arraycopy(idxBuf.reader().getBuffer(), 0, set, idxBase, idx.getPosition());
        for (int i = 0; i < n; i++)
            System.arraycopy(matStructs.get(i), 0, set, structsBase + i * 44, 44);
        return set;
    }

    // one 44-byte NITRO material struct from a <material> element (texture size from tex_image index)
    private byte[] buildMaterialStruct(String m, int texIdx)
    {
        String tex = blocks("tex_image").get(texIdx);
        byte[] ms = new byte[44];
        {
            MemBuf scratch = MemBuf.create(ms);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(0);
            scratch.writer().writeShort((short) (0));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, ms, 0, ms.length);
        }
        {
            MemBuf scratch = MemBuf.create(ms);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(2);
            scratch.writer().writeShort((short) (44));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, ms, 0, ms.length);
        }
        {
            MemBuf scratch = MemBuf.create(ms);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(4);
            scratch.writer().writeUInt32(rgb15(doubles(a(m, "diffuse"))) | (1 << 15) | (rgb15(doubles(a(m, "ambient"))) << 16));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, ms, 0, ms.length);
        }
        {
            MemBuf scratch = MemBuf.create(ms);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(8);
            scratch.writer().writeUInt32(rgb15(doubles(a(m, "specular"))) | (rgb15(doubles(a(m, "emission"))) << 16));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, ms, 0, ms.length);
        }
        int alpha = Integer.parseInt(a(m, "alpha"));
        int lights = 0;
        for (int i = 0; i < 4; i++)
            if ("on".equals(a(m, "light" + i)))
                lights |= 1 << i;
        int mode = polygonMode(a(m, "polygon_mode")); // modulate=0, decal=1, toon=2, shadow=3
        String face = a(m, "face");
        int cull = "back".equals(face) ? 0x40 : "both".equals(face) ? 0xC0 : 0x80; // render front (default)
        {
            MemBuf scratch = MemBuf.create(ms);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(0x0C);
            scratchWriter.writeUInt32(((long) alpha << 16) | cull | ((long) mode << 4) | lights); // polygon_attr
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, ms, 0, ms.length);
        }

        {
            MemBuf scratch = MemBuf.create(ms);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(0x10);
            scratchWriter.writeUInt32(0x3f1ff8ffL); // polygon_attr_mask (constant)
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, ms, 0, ms.length);
        }

        String[] tiling = a(m, "tex_tiling").split("\\s+");
        {
            MemBuf scratch = MemBuf.create(ms);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(0x14);
            scratchWriter.writeUInt32(wrapBits(tiling[0], 16, 18) | wrapBits(tiling[1], 17, 19)); // teximage_param wrap/flip
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, ms, 0, ms.length);
        }

        {
            MemBuf scratch = MemBuf.create(ms);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(0x18);
            scratch.writer().writeUInt32(0xffffffffL);
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, ms, 0, ms.length);
        }
        {
            MemBuf scratch = MemBuf.create(ms);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(0x1C);
            scratch.writer().writeShort((short) (0));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, ms, 0, ms.length);
        }
        {
            MemBuf scratch = MemBuf.create(ms);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(0x1E);
            scratch.writer().writeShort((short) (0x1fce));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, ms, 0, ms.length);
        }
        {
            MemBuf scratch = MemBuf.create(ms);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(0x20);
            scratch.writer().writeShort((short) (Integer.parseInt(a(tex, "width"))));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, ms, 0, ms.length);
        }
        {
            MemBuf scratch = MemBuf.create(ms);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(0x22);
            scratch.writer().writeShort((short) (Integer.parseInt(a(tex, "height"))));
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, ms, 0, ms.length);
        }
        {
            MemBuf scratch = MemBuf.create(ms);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(0x24);
            scratch.writer().writeUInt32(0x1000);
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, ms, 0, ms.length);
        }
        {
            MemBuf scratch = MemBuf.create(ms);
            MemBuf.MemBufWriter scratchWriter = scratch.writer();
            int scratchEnd = scratchWriter.getPosition();
            scratchWriter.setPosition(0x28);
            scratch.writer().writeUInt32(0x1000);
            scratchWriter.setPosition(scratchEnd);
            System.arraycopy(scratch.reader().getBuffer(), 0, ms, 0, ms.length);
        }
        return ms;
    }

    // --- shape set: shape dictionary + N 16-byte shape structs + N display lists ---
    private byte[] buildShapeSet(List<String> polygons, List<byte[]> dls)
    {
        int n = polygons.size();
        List<String> names = new ArrayList<>();
        for (String p : polygons)
            names.add(a(p, "name"));
        int dictLen = serialize(G3dDictionary.build(names, placeholders(n), 4)).length;
        int dlBase = dictLen + n * 16;
        List<byte[]> recs = new ArrayList<>();
        byte[][] structs = new byte[n][16];
        int dlCursor = dlBase;
        for (int i = 0; i < n; i++)
        {
            int structOfs = dictLen + i * 16;
            MemBuf word8Buf = MemBuf.create();
            word8Buf.writer().writeUInt32(structOfs);
            byte[] word8 = word8Buf.reader().getBuffer();
            recs.add(word8);
            String prim = polygons.get(i).replaceAll("(?s).*<primitive[^>]*>(.*?)</primitive>.*", "$1");
            int vtxFlags = (prim.contains("<nrm") ? 1 : 0) | (prim.contains("<clr") ? 2 : 0) | (prim.contains("<tex") ? 4 : 0);
            {
                MemBuf scratch = MemBuf.create(structs[i]);
                MemBuf.MemBufWriter scratchWriter = scratch.writer();
                int scratchEnd = scratchWriter.getPosition();
                scratchWriter.setPosition(2);
                scratch.writer().writeShort((short) (0x10));
                scratchWriter.setPosition(scratchEnd);
                System.arraycopy(scratch.reader().getBuffer(), 0, structs[i], 0, structs[i].length);
            }
            {
                MemBuf scratch = MemBuf.create(structs[i]);
                MemBuf.MemBufWriter scratchWriter = scratch.writer();
                int scratchEnd = scratchWriter.getPosition();
                scratchWriter.setPosition(4);
                scratch.writer().writeUInt32(vtxFlags);
                scratchWriter.setPosition(scratchEnd);
                System.arraycopy(scratch.reader().getBuffer(), 0, structs[i], 0, structs[i].length);
            }
            {
                MemBuf scratch = MemBuf.create(structs[i]);
                MemBuf.MemBufWriter scratchWriter = scratch.writer();
                int scratchEnd = scratchWriter.getPosition();
                scratchWriter.setPosition(8);
                scratchWriter.writeUInt32(dlCursor - structOfs); // dlOffset relative to this struct
                scratchWriter.setPosition(scratchEnd);
                System.arraycopy(scratch.reader().getBuffer(), 0, structs[i], 0, structs[i].length);
            }

            {
                MemBuf scratch = MemBuf.create(structs[i]);
                MemBuf.MemBufWriter scratchWriter = scratch.writer();
                int scratchEnd = scratchWriter.getPosition();
                scratchWriter.setPosition(12);
                scratch.writer().writeUInt32(dls.get(i).length);
                scratchWriter.setPosition(scratchEnd);
                System.arraycopy(scratch.reader().getBuffer(), 0, structs[i], 0, structs[i].length);
            }
            dlCursor += dls.get(i).length;
        }
        byte[] set = new byte[dlCursor];
        System.arraycopy(serialize(G3dDictionary.build(names, recs, 4)), 0, set, 0, dictLen);
        for (int i = 0; i < n; i++)
            System.arraycopy(structs[i], 0, set, dictLen + i * 16, 16);
        int p = dlBase;
        for (byte[] d : dls)
        {
            System.arraycopy(d, 0, set, p, d.length);
            p += d.length;
        }
        return set;
    }

    // --- .imd parse helpers (the intermediate is simple XML) ---
    private String attr(String tag, String name)
    {
        Matcher m = Pattern.compile("<" + tag + "\\b[^>]*\\b" + name + "=\"([^\"]*)\"").matcher(imd);
        return m.find() ? m.group(1) : null;
    }
    private int intAttr(String tag, String name)
    {
        return Integer.parseInt(attr(tag, name));
    }

    // every <tag ...>...</tag> or <tag .../> element (matched at a word boundary), in document order
    private List<String> blocks(String tag)
    {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<" + tag + "\\b(?:[^>]*?/>|[^>]*?>.*?</" + tag + ">)", Pattern.DOTALL).matcher(imd);
        while (m.find())
            out.add(m.group());
        return out;
    }
    // an attribute from a single element string
    private static String a(String element, String name)
    {
        Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(element);
        return m.find() ? m.group(1) : null;
    }

    // the node's <display material="m" polygon="p"/> entries as {materialIdx, polygonIdx} pairs
    // one node of the <node_array>: its parent (root maps to its own index), whether it draws, its visibility
    // and billboard state, and its {materialIdx, polygonIdx} displays in order.
    private static final class Node
    {
        String name;
        int parent, vis = 1, bb = 0;
        boolean draws;
        final List<int[]> draw = new ArrayList<>();
        double[] scale = { 1, 1, 1 };
        double[] rotate = { 0, 0, 0 }; // Euler angles in degrees (x, y, z)
        double[] translate = { 0, 0, 0 };
    }

    // parse the whole <node_array>, in index order (g3dcvtr numbers/walks nodes by index)
    private List<Node> parseNodes()
    {
        List<Node> nodes = new ArrayList<>();
        for (String el : blocks("node"))
        {
            Node nd = new Node();
            nd.name = a(el, "name");
            int index = Integer.parseInt(a(el, "index"));
            int par = Integer.parseInt(a(el, "parent"));
            nd.parent = par < 0 ? index : par; // the root's NODEDESC parent is its own index
            nd.vis = "off".equals(a(el, "visibility")) ? 0 : 1;
            nd.bb = "on".equals(a(el, "billboard")) ? 1 : "y_on".equals(a(el, "billboard")) ? 2 : 0;
            if (a(el, "scale") != null)
                nd.scale = doubles(a(el, "scale"));
            if (a(el, "rotate") != null)
                nd.rotate = doubles(a(el, "rotate"));
            if (a(el, "translate") != null)
                nd.translate = doubles(a(el, "translate"));
            Matcher dm = Pattern.compile("<display\\b[^>]*/>").matcher(el);
            while (dm.find())
                nd.draw.add(new int[] { Integer.parseInt(a(dm.group(), "material")), Integer.parseInt(a(dm.group(), "polygon")) });
            nd.draws = !nd.draw.isEmpty();
            while (nodes.size() <= index)
                nodes.add(null);
            nodes.set(index, nd);
        }
        nodes.removeIf(java.util.Objects::isNull);
        return nodes;
    }
    private static List<byte[]> placeholders(int n)
    {
        List<byte[]> records = new ArrayList<>();
        int left = n;

        while (left > 0)
        {
            MemBuf word9Buf = MemBuf.create();
            word9Buf.writer().writeUInt32(0);
            byte[] word9 = word9Buf.reader().getBuffer();
            records.add(0, word9);
            left--;
        }

        return records;
    }
    private static String firstQuoted(String s)
    {
        Matcher m = Pattern.compile("\"([^\"]*)\"").matcher(s);
        m.find();
        return m.group(1);
    }
    private static double[] doubles(String s)
    {
        String[] p = s.trim().split("\\s+");
        double[] v = new double[p.length];
        for (int i = 0; i < p.length; i++)
            v[i] = Double.parseDouble(p[i]);
        return v;
    }

    // --- encode helpers ---
    private static DisplayList.Command cmd(int op, int... operands)
    {
        return new DisplayList.Command(op, operands);
    }
    private static int pack10(int a, int b, int c)
    {
        return (a & 0x3FF) | ((b & 0x3FF) << 10) | ((c & 0x3FF) << 20);
    }
    private static int fx(double v, int frac)
    {
        return (int) Math.round(v * (1 << frac));
    }
    private static boolean exact10(double v)
    {
        double s = v * 64;
        return Math.abs(s - Math.round(s)) < 1e-6 && Math.round(s) >= -512 && Math.round(s) < 512;
    }
    private static int rgb15(double[] c)
    {
        return (int) (Math.round(c[0]) & 0x1F) | ((int) (Math.round(c[1]) & 0x1F) << 5) | ((int) (Math.round(c[2]) & 0x1F) << 10);
    }
    private static byte[] serialize(G3dDictionary d)
    {
        MemBuf b = MemBuf.create();
        d.write(b.writer());
        return b.reader().getBuffer();
    }
    private static byte[] concat(byte[]... arr)
    {
        int n = 0;
        for (byte[] a : arr)
            n += a.length;
        byte[] r = new byte[n];
        int p = 0;
        for (byte[] a : arr)
        {
            System.arraycopy(a, 0, r, p, a.length);
            p += a.length;
        }
        return r;
    }
}
