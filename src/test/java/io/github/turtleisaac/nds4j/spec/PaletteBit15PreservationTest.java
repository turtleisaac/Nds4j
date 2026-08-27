package io.github.turtleisaac.nds4j.spec;

import io.github.turtleisaac.nds4j.images.Palette;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for BGR555 bit-15 preservation on the file round-trip. NDS BGR555 only uses 15 bits,
 * but retail palettes set the unused bit 15; java.awt.Color cannot carry it, so a naive load/save clears it
 * (0xFFFF -> 0x7FFF). Palette keeps the raw source values and restores bit 15 for unedited colours. These
 * tests build a real NCLR byte stream (so the source-value path is actually exercised) and check the byte.
 */
class PaletteBit15PreservationTest
{
    // Colours in a saved NCLR begin at 0x10 (NTR header) + 0x18 (TTLP header) = 0x28, two bytes each, little-endian.
    private static final int COLOR_DATA_START = 0x28;

    /** Builds a valid NCLR whose colour 0 has bit 15 set (0xFFFF); every other colour is 0x0000. */
    private static byte[] nclrWithBit15OnColor0(int numColors)
    {
        Color[] cols = new Color[numColors];
        // (248,248,248) is already 5-bit aligned, so it packs to 0x7FFF with no quantisation loss.
        cols[0] = new Color(248, 248, 248);
        for (int i = 1; i < numColors; i++) cols[i] = Color.BLACK;
        byte[] bytes = new Palette(cols).save();
        // set the unused high bit on colour 0 -> 0xFFFF
        bytes[COLOR_DATA_START + 1] |= (byte) 0x80;
        return bytes;
    }

    private static int color(byte[] saved, int i)
    {
        return (saved[COLOR_DATA_START + 2 * i] & 0xff) | ((saved[COLOR_DATA_START + 2 * i + 1] & 0xff) << 8);
    }

    @Test
    @DisplayName("plain load/save preserves bit 15 of an unedited colour")
    void roundTripPreservesBit15()
    {
        Palette p = new Palette(nclrWithBit15OnColor0(16), 0);
        assertEquals(0xFFFF, color(p.save(), 0), "unedited colour must round-trip byte-for-byte");
        assertEquals(0x0000, color(p.save(), 1));
    }

    @Test
    @DisplayName("copyOf preserves bit 15 (DEFECT 1)")
    void copyOfPreservesBit15()
    {
        Palette copy = new Palette(nclrWithBit15OnColor0(16), 0).copyOf();
        assertEquals(0xFFFF, color(copy.save(), 0), "a copied palette must still round-trip bit 15");
    }

    @Test
    @DisplayName("editing a colour clears its bit 15, even to a visually-identical colour (DEFECT 2)")
    void editClearsBit15()
    {
        Palette p = new Palette(nclrWithBit15OnColor0(16), 0);
        p.setColor(0, new Color(248, 248, 248)); // same quantised colour, but an explicit edit
        assertEquals(0x7FFF, color(p.save(), 0), "an edited slot must pack fresh (bit 15 = 0)");
    }

    @Test
    @DisplayName("setColors drops all source values (DEFECT 3)")
    void setColorsClearsBit15()
    {
        Palette p = new Palette(nclrWithBit15OnColor0(16), 0);
        Color[] replacement = new Color[16];
        for (int i = 0; i < 16; i++) replacement[i] = new Color(248, 248, 248);
        p.setColors(replacement);
        assertEquals(0x7FFF, color(p.save(), 0), "replaced colours have no source, so no bit 15");
    }

    @Test
    @DisplayName("setNumColors keeps the surviving prefix's bit 15 and gives new slots none")
    void resizeKeepsPrefixBit15()
    {
        Palette p = new Palette(nclrWithBit15OnColor0(16), 0);
        p.setNumColors(32); // grow
        byte[] saved = p.save();
        assertEquals(0xFFFF, color(saved, 0), "surviving colour keeps its bit 15");
        assertEquals(0x0000, color(saved, 16), "newly-added slot is black with no bit 15");
    }

    @Test
    @DisplayName("a palette not read from a file never fabricates bit 15")
    void nonFilePaletteHasNoBit15()
    {
        Color[] cols = new Color[16];
        for (int i = 0; i < 16; i++) cols[i] = new Color(248, 248, 248);
        assertEquals(0x7FFF, color(new Palette(cols).save(), 0));
    }
}
