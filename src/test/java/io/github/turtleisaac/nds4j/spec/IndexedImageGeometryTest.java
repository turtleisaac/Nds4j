package io.github.turtleisaac.nds4j.spec;

import io.github.turtleisaac.nds4j.images.IndexedImage;
import io.github.turtleisaac.nds4j.images.Palette;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Geometry and storage laws for {@link IndexedImage}.
 * <p>
 * DS character data stores pixels in 8x8 tiles rather than in scanline order, so the mapping
 * from a pixel coordinate to a storage offset is a non-trivial permutation. The properties
 * asserted here are the ones that make such a mapping correct, independently of the formula
 * used to compute it:
 * <ul>
 *   <li><strong>Injectivity</strong> &mdash; writing one pixel must not disturb any other. A
 *       swapped x/y, a wrong row stride, or an off-by-one in the tile index all break this.</li>
 *   <li><strong>Totality</strong> &mdash; every coordinate in the declared bounds is addressable.</li>
 *   <li><strong>Round-trip</strong> &mdash; serialising and re-parsing preserves every pixel.</li>
 *   <li><strong>Domain</strong> &mdash; a bit depth of n admits exactly the indices 0..2^n-1.</li>
 * </ul>
 */
@DisplayName("IndexedImage pixel storage is a faithful bijection")
class IndexedImageGeometryTest
{
    private static Palette greyPalette(int numColors)
    {
        Color[] colors = new Color[numColors];
        for (int i = 0; i < numColors; i++)
        {
            int level = (i * 8) & 0xF8;
            colors[i] = new Color(level, level, level);
        }
        return new Palette(colors);
    }

    /** NOTE: the constructor takes height BEFORE width. */
    private static IndexedImage blank(int height, int width, int bitDepth)
    {
        return new IndexedImage(height, width, bitDepth, greyPalette(1 << bitDepth));
    }

    @Test
    @DisplayName("reported dimensions match the ones supplied, in the documented order")
    void dimensionsAreNotTransposed()
    {
        // A non-square image is the only way to catch a height/width swap, and the constructor's
        // parameter order (height, width) is an easy one to get backwards.
        IndexedImage image = blank(16, 32, 4);
        assertThat(image.getHeight()).as("height").isEqualTo(16);
        assertThat(image.getWidth()).as("width").isEqualTo(32);
    }

    @Test
    @DisplayName("writing one pixel leaves every other pixel untouched")
    void pixelWritesAreIndependent()
    {
        // The core injectivity property. If the coordinate-to-offset mapping collides, or
        // reads and writes disagree about the layout, some other pixel changes too.
        for (int[] dims : new int[][]{{8, 8}, {16, 16}, {16, 32}, {32, 16}})
        {
            int height = dims[0], width = dims[1];
            for (int targetY = 0; targetY < height; targetY++)
            {
                for (int targetX = 0; targetX < width; targetX++)
                {
                    IndexedImage image = blank(height, width, 4);
                    image.setPixelValue(targetX, targetY, 0xF);

                    for (int y = 0; y < height; y++)
                        for (int x = 0; x < width; x++)
                        {
                            int expected = (x == targetX && y == targetY) ? 0xF : 0;
                            assertThat(image.getPixelValue(x, y))
                                    .as("%dx%d image: writing (%d,%d) changed (%d,%d)",
                                        width, height, targetX, targetY, x, y)
                                    .isEqualTo(expected);
                        }
                }
            }
        }
    }

    @Test
    @DisplayName("every coordinate in bounds is addressable")
    void allCoordinatesAreAddressable()
    {
        IndexedImage image = blank(24, 40, 4);
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++)
            {
                int fx = x, fy = y;
                assertThatCode(() -> image.setPixelValue(fx, fy, 1))
                        .as("(%d,%d) must be writable in a %dx%d image",
                            x, y, image.getWidth(), image.getHeight())
                        .doesNotThrowAnyException();
            }
    }

    @Test
    @DisplayName("a 4bpp image stores every index in 0..15 exactly")
    void fourBppDomain()
    {
        IndexedImage image = blank(8, 8, 4);
        for (int value = 0; value <= 0xF; value++)
        {
            image.setPixelValue(3, 5, value);
            assertThat(image.getPixelValue(3, 5))
                    .as("4bpp must round-trip palette index %d", value)
                    .isEqualTo(value);
        }
    }

    @Test
    @DisplayName("an 8bpp image stores every index in 0..255 exactly, including above 127")
    void eightBppDomainIncludesHighIndices()
    {
        // Indices above 127 are where a byte-typed pixel sign-extends into a negative value
        // and then indexes the palette out of bounds.
        IndexedImage image = blank(8, 8, 8);
        for (int value = 0; value <= 0xFF; value++)
        {
            image.setPixelValue(2, 6, value);
            assertThat(image.getPixelValue(2, 6))
                    .as("8bpp must round-trip palette index %d without sign extension", value)
                    .isEqualTo(value)
                    .isNotNegative();
        }
    }

    @Test
    @DisplayName("a full pattern survives set-then-get across the whole canvas")
    void wholeCanvasRoundTrip()
    {
        int height = 16, width = 24;
        IndexedImage image = blank(height, width, 4);

        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                image.setPixelValue(x, y, (x * 7 + y * 3) & 0xF);

        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                assertThat(image.getPixelValue(x, y))
                        .as("pixel (%d,%d)", x, y)
                        .isEqualTo((x * 7 + y * 3) & 0xF);
    }

    @Test
    @DisplayName("getPixels hands out a defensive copy")
    void accessorDoesNotLeakInternalState()
    {
        IndexedImage image = blank(8, 8, 4);
        image.setPixelValue(0, 0, 1);
        int[][] pixels = image.getPixels();
        pixels[0][0] = 0xF;

        assertThat(image.getPixelValue(0, 0))
                .as("writing through a returned array must not bypass the image's own accessors")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("images with a non-multiple-of-8 dimension are rejected, not silently mangled")
    void tileAlignmentIsEnforced()
    {
        // Character data is tiled 8x8; a dimension that is not a multiple of 8 has no valid
        // representation, so it must fail loudly at construction rather than truncate later.
        for (int[] bad : new int[][]{{7, 8}, {8, 7}, {9, 16}, {65, 65}})
        {
            assertThatCode(() -> blank(bad[0], bad[1], 4))
                    .as("a %dx%d image is not representable", bad[1], bad[0])
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    @DisplayName("chunk parameters documented as optional are actually accepted")
    void optionalChunkParametersAreAccepted()
    {
        // The byte[] constructor's javadoc states that 0 means "no chunking". A value the
        // documentation invites must not divide by zero.
        byte[] ncgr = blank(8, 8, 4).save();
        assertThatCode(() -> new IndexedImage(ncgr, 0, 0, 0, 0, true))
                .as("colsPerChunk/rowsPerChunk of 0 are documented as legal")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a saved image parses back with the same dimensions and pixels")
    void serialisationRoundTrip()
    {
        int height = 16, width = 16;
        IndexedImage original = blank(height, width, 4);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                original.setPixelValue(x, y, (x ^ y) & 0xF);

        IndexedImage reparsed = new IndexedImage(original.save(), width / 8, 4, 1, 1, true);

        assertThat(reparsed.getWidth()).as("width survives").isEqualTo(width);
        assertThat(reparsed.getHeight()).as("height survives").isEqualTo(height);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                assertThat(reparsed.getPixelValue(x, y))
                        .as("pixel (%d,%d) survives save/parse", x, y)
                        .isEqualTo((x ^ y) & 0xF);
    }

    @Test
    @DisplayName("the declared file size in a saved NCGR matches the bytes actually produced")
    void declaredFileSizeMatchesReality()
    {
        // The NTR container header at offset 0x08 states the total file length. Tools that walk
        // sections by these fields (Tinke, nitrogfx, the DS's own NNS loader) rely on it, even
        // though this library's own parser ignores it -- which is how a wrong value hides.
        byte[] ncgr = blank(16, 16, 4).save();
        long declared = (ncgr[0x08] & 0xFFL)
                | ((ncgr[0x09] & 0xFFL) << 8)
                | ((ncgr[0x0A] & 0xFFL) << 16)
                | ((ncgr[0x0B] & 0xFFL) << 24);

        assertThat(declared)
                .as("NTR header file size at 0x08 must equal the real length")
                .isEqualTo(ncgr.length);
    }

    @Test
    @DisplayName("the CHAR section declares its own size")
    void charSectionSizeIsWritten()
    {
        // Section size lives at +0x04 of the section header. Leaving it zero makes the file
        // unwalkable by any conformant reader.
        byte[] ncgr = blank(16, 16, 4).save();
        int sectionSize = (ncgr[0x14] & 0xFF)
                | ((ncgr[0x15] & 0xFF) << 8)
                | ((ncgr[0x16] & 0xFF) << 16)
                | ((ncgr[0x17] & 0xFF) << 24);

        assertThat(sectionSize)
                .as("RAHC/CHAR section size at 0x14 must be populated")
                .isPositive()
                .isLessThanOrEqualTo(ncgr.length);
    }
}
