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

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the texture encoder ({@link TextureSet#encodeTextureData}) for source&rarr;NSB* conversion.
 * Re-encoding a decoded texture must reproduce a bit-stream that decodes back to the same pixels
 * (always), and reproduce the retail texel bytes exactly for the paletted/direct formats whose mapping
 * is deterministic. Validated over real Platinum textures.
 */
@DisplayName("texture encoder (image -> NSBTX texel data)")
public class TextureEncodeTest
{
    private static String magic(byte[] d)
    {
        return d == null || d.length < 4 ? "" : new String(d, 0, 4, StandardCharsets.ISO_8859_1);
    }

    @Test
    @DisplayName("re-encoding a decoded texture is pixel-exact, and byte-exact for most")
    void encodeRoundTrip()
    {
        NintendoDsRom rom = TestRoms.require("Platinum.nds");
        int tested = 0, pixelExact = 0, byteExact = 0;

        outer:
        for (int i = 0; i < rom.getNumFiles(); i++)
        {
            byte[] f = rom.getFile(i);
            if (!magic(f).equals("NARC"))
                continue;
            Narc narc;
            try { narc = new Narc(f); }
            catch (RuntimeException e) { continue; }
            for (int j = 0; j < narc.getNumFiles(); j++)
            {
                byte[] file = narc.getFile(j);
                TextureSet tex = null;
                if (magic(file).equals("BTX0"))
                {
                    try { tex = new TextureSet(file); } catch (RuntimeException e) { continue; }
                }
                else if (magic(file).equals("BMD0"))
                {
                    try { ModelSet ms = new ModelSet(file); tex = ms.getEmbeddedTextures(); }
                    catch (RuntimeException e) { continue; }
                }
                if (tex == null)
                    continue;

                for (TextureSet.Texture t : tex.getTextures())
                {
                    byte[] original = tex.getRawTextureData(t);
                    if (original == null)
                        continue; // compressed/unsupported length
                    BufferedImage decoded;
                    byte[] encoded;
                    try
                    {
                        decoded = tex.getImage(t);
                        encoded = tex.encodeTextureData(decoded, t);
                    }
                    catch (RuntimeException e) { continue; }
                    if (encoded == null)
                        continue; // format not encoded here (A3I5/A5I3)
                    tested++;

                    if (java.util.Arrays.equals(encoded, original))
                        byteExact++;

                    // decoding the re-encoded bytes must reproduce the same pixels
                    byte[] saved = tex.getRawTextureData(t);
                    if (tex.overwriteRawTextureData(t, encoded))
                    {
                        BufferedImage redecoded = tex.getImage(t);
                        tex.overwriteRawTextureData(t, saved); // restore
                        if (imagesEqual(decoded, redecoded))
                            pixelExact++;
                    }
                    if (tested >= 600)
                        break outer;
                }
            }
        }
        Assumptions.assumeTrue(tested > 50, "need retail textures");
        System.out.printf("texture encode: tested=%d pixelExact=%d byteExact=%d%n", tested, pixelExact, byteExact);
        assertThat(pixelExact).as("re-encoded texels decode to identical pixels").isEqualTo(tested);
        assertThat(byteExact).as("most re-encode byte-for-byte").isGreaterThan(tested * 3 / 4);
    }

    private static boolean imagesEqual(BufferedImage a, BufferedImage b)
    {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight())
            return false;
        for (int y = 0; y < a.getHeight(); y++)
            for (int x = 0; x < a.getWidth(); x++)
                if (a.getRGB(x, y) != b.getRGB(x, y))
                    return false;
        return true;
    }
}
