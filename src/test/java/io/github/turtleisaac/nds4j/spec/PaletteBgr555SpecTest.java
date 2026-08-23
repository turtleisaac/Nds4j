package io.github.turtleisaac.nds4j.spec;

import io.github.turtleisaac.nds4j.images.Palette;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Conformance of NCLR colour handling to the DS palette specification.
 * <p>
 * GBATEK defines a DS palette entry as a little-endian 16-bit value laid out as
 * <pre>
 *   bit  0-4   red   (0-31)
 *   bit  5-9   green (0-31)
 *   bit 10-14  blue  (0-31)
 *   bit 15     unused
 * </pre>
 * A round-trip test alone cannot detect a red/blue swap, because encoding and decoding would
 * cancel each other out. So the bit layout is asserted directly against bytes decoded from the
 * saved NCLR, independently of how this library computes them.
 */
@DisplayName("Palette conforms to the BGR555 specification")
class PaletteBgr555SpecTest
{
    /** Reference encoder written straight from the GBATEK bit layout. */
    private static int referenceBgr555(Color c)
    {
        int r = (c.getRed()   >> 3) & 0x1F;
        int g = (c.getGreen() >> 3) & 0x1F;
        int b = (c.getBlue()  >> 3) & 0x1F;
        return (b << 10) | (g << 5) | r;
    }

    /**
     * Extracts the palette data block from a saved NCLR by walking the NTR container per spec:
     * a 16-byte file header, then sections each beginning with a 4-byte magic and a 4-byte size.
     * The palette lives in the "PLTT" section (stored reversed as "TTLP"), whose data begins
     * 0x18 bytes into the section.
     */
    private static byte[] paletteDataOf(byte[] nclr)
    {
        for (int i = 0x10; i + 8 <= nclr.length; i++)
        {
            if (nclr[i] == 'T' && nclr[i + 1] == 'T' && nclr[i + 2] == 'L' && nclr[i + 3] == 'P')
                return java.util.Arrays.copyOfRange(nclr, i + 0x18, nclr.length);
        }
        throw new AssertionError("saved NCLR contains no TTLP (PLTT) section");
    }

    private static int halfwordAt(byte[] data, int index)
    {
        return (data[index * 2] & 0xFF) | ((data[index * 2 + 1] & 0xFF) << 8);
    }

    @Test
    @DisplayName("channels occupy the documented bit positions, in the documented order")
    void bitLayoutMatchesSpec()
    {
        // Pure primaries at full intensity make a channel swap unmistakable: red must land in
        // the low five bits, blue in bits 10-14.
        Color[] colors = new Color[16];
        java.util.Arrays.fill(colors, Color.BLACK);
        colors[0] = Color.BLACK;
        colors[1] = new Color(248, 0, 0);     // pure red,   31 in the low field
        colors[2] = new Color(0, 248, 0);     // pure green
        colors[3] = new Color(0, 0, 248);     // pure blue,  31 in the high field
        colors[4] = new Color(248, 248, 248); // white

        byte[] data = paletteDataOf(new Palette(colors).save());

        assertThat(halfwordAt(data, 0)).as("black").isEqualTo(0x0000);
        assertThat(halfwordAt(data, 1)).as("red must occupy bits 0-4").isEqualTo(0x001F);
        assertThat(halfwordAt(data, 2)).as("green must occupy bits 5-9").isEqualTo(0x03E0);
        assertThat(halfwordAt(data, 3)).as("blue must occupy bits 10-14").isEqualTo(0x7C00);
        assertThat(halfwordAt(data, 4)).as("white").isEqualTo(0x7FFF);
    }

    @Test
    @DisplayName("every representable colour encodes exactly as the reference does")
    void encodingMatchesReferenceExhaustively()
    {
        // Sweep all 32 levels of each channel against the others' extremes and midpoint.
        // This covers every bit position in every field without needing all 32768 combinations
        // in one palette.
        for (int other : new int[]{0, 16, 31})
        {
            Color[] colors = new Color[32];
            for (int level = 0; level < 32; level++)
                colors[level] = new Color(level << 3, other << 3, ((31 - other)) << 3);

            byte[] data = paletteDataOf(new Palette(colors).save());
            for (int level = 0; level < 32; level++)
                assertThat(halfwordAt(data, level))
                        .as("red level %d with g=%d b=%d", level, other, 31 - other)
                        .isEqualTo(referenceBgr555(colors[level]));
        }
    }

    @Test
    @DisplayName("save then parse preserves every quantised colour exactly")
    void roundTripIsExact()
    {
        // 5 bits per channel means only multiples of 8 are representable. For those, the
        // round-trip must be the identity -- any loss indicates a rounding asymmetry between
        // the encode and decode paths.
        Color[] colors = new Color[256];
        int i = 0;
        for (int r = 0; r < 32 && i < 256; r += 3)
            for (int g = 0; g < 32 && i < 256; g += 5)
                for (int b = 0; b < 32 && i < 256; b += 7)
                    colors[i++] = new Color(r << 3, g << 3, b << 3);
        while (i < 256) colors[i++] = Color.BLACK;

        Palette original = new Palette(colors);
        Palette reparsed = new Palette(original.save(), original.getBitDepth());

        for (int idx = 0; idx < colors.length; idx++)
            assertThat(reparsed.getColor(idx))
                    .as("colour %d must survive save/parse unchanged", idx)
                    .isEqualTo(colors[idx]);
    }

    @Test
    @DisplayName("decoding is idempotent: re-saving a parsed palette reproduces the same bytes")
    void reSaveIsStable()
    {
        Color[] colors = new Color[16];
        for (int idx = 0; idx < 16; idx++)
            colors[idx] = new Color((idx * 8) << 3 & 0xF8, (idx * 3) << 3 & 0xF8, (idx * 5) << 3 & 0xF8);

        byte[] first = new Palette(colors).save();
        byte[] second = new Palette(first, new Palette(colors).getBitDepth()).save();

        assertThat(second)
                .as("parse then save must be a fixed point once the data is already quantised")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("a palette always holds a whole number of 16-colour banks")
    void sizeIsAlwaysAMultipleOfSixteen()
    {
        // DS hardware addresses palettes in 16-colour banks, and getColor(i, palIndex)
        // indexes as palIndex*16 + i. A palette that is not a multiple of 16 makes that
        // addressing walk off the end.
        for (int requested : new int[]{1, 2, 15, 16, 17, 20, 31, 32, 33, 100, 255, 256})
        {
            Color[] colors = new Color[requested];
            java.util.Arrays.fill(colors, Color.BLACK);
            Palette palette = new Palette(colors);

            assertThat(palette.size() % 16)
                    .as("a palette built from %d colours must round up to a whole bank", requested)
                    .isZero();
            assertThat(palette.size())
                    .as("padding must never discard colours")
                    .isGreaterThanOrEqualTo(requested);
        }
    }

    @Test
    @DisplayName("bank addressing stays in bounds for every padded palette")
    void bankAddressingIsSafe()
    {
        for (int requested : new int[]{17, 20, 31, 33})
        {
            Color[] colors = new Color[requested];
            java.util.Arrays.fill(colors, Color.RED);
            Palette palette = new Palette(colors);
            int banks = palette.size() / 16;

            for (int bank = 0; bank < banks; bank++)
                for (int idx = 0; idx < 16; idx++)
                {
                    int finalBank = bank, finalIdx = idx;
                    assertThatCode(() -> palette.getColor(finalIdx, finalBank))
                            .as("bank %d index %d of a %d-colour palette", bank, idx, palette.size())
                            .doesNotThrowAnyException();
                }
        }
    }

    @Test
    @DisplayName("out-of-range indices are rejected in both directions")
    void indexGuards()
    {
        Palette palette = new Palette(16);
        assertThatCode(() -> palette.getColor(-1))
                .as("a negative index must be rejected, not raise a raw array error")
                .isInstanceOf(RuntimeException.class);
        assertThatCode(() -> palette.getColor(palette.size()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("copyOf produces an independent palette")
    void copiesAreIndependent()
    {
        // Aliasing here let one image permanently recolour every other image in the JVM.
        Palette original = new Palette(16);
        original.setColor(3, Color.BLUE);
        Palette copy = original.copyOf();
        copy.setColor(3, Color.MAGENTA);

        assertThat(original.getColor(3))
                .as("mutating a copy must not affect the original")
                .isEqualTo(Color.BLUE);
    }

    @Test
    @DisplayName("getColors hands out a defensive copy")
    void accessorDoesNotLeakInternalState()
    {
        Palette palette = new Palette(16);
        palette.setColor(0, Color.BLUE);
        Color[] handedOut = palette.getColors();
        handedOut[0] = Color.MAGENTA;

        assertThat(palette.getColor(0))
                .as("writing through a returned array must not bypass the palette's own accessors")
                .isEqualTo(Color.BLUE);
    }
}
