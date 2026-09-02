package io.github.turtleisaac.nds4j.spec;

import io.github.turtleisaac.nds4j.Narc;
import io.github.turtleisaac.nds4j.NintendoDsRom;
import io.github.turtleisaac.nds4j.TestRoms;
import io.github.turtleisaac.nds4j.framework.NitroLz;
import io.github.turtleisaac.nds4j.images.NitroFont;
import io.github.turtleisaac.nds4j.text.BinaryMessage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the newly-exposed BMG and NFTR setters are <em>functional</em> — a real edit takes effect and
 * survives {@code save()} &rarr; reload. This is the complement to {@link EditRoundTripSurveyTest}, whose
 * no-op checks prove fidelity but would also pass a setter that did nothing; here we change a value and
 * assert the change actually persisted (and that neighbouring data did not).
 */
@DisplayName("BMG/NFTR setters produce real, persistent edits")
class FunctionalSetterTest
{
    private static final List<String> ROMS = Arrays.asList(
            "White2.nds", "HeartGold.nds", "SoulSilver.nds", "Platinum.nds", "Diamond.nds", "Pearl.nds",
            "Legend of Zelda, The - Phantom Hourglass.nds", "Mario Kart DS.nds", "New Super Mario Bros.nds");

    @Test
    @DisplayName("BinaryMessage.setText edits a message and it survives save/reload")
    void bmgTextEditPersists()
    {
        byte[] bmgBytes = findFirst("MESG");
        Assumptions.assumeTrue(bmgBytes != null, "no BMG file found in the available ROM set");

        BinaryMessage bmg = new BinaryMessage(bmgBytes);
        // find two distinct non-null messages: one to edit, one to prove isolation
        int editIdx = -1, keepIdx = -1;
        for (int i = 0; i < bmg.getMessages().size(); i++)
        {
            if (bmg.getMessages().get(i).isNull()) continue;
            if (editIdx < 0) editIdx = i;
            else { keepIdx = i; break; }
        }
        Assumptions.assumeTrue(editIdx >= 0, "no editable (non-null) message in the chosen BMG");

        String newText = "Nds4jEDIT";   // ASCII: encodable in every BMG charset
        String keptText = keepIdx >= 0 ? bmg.getMessages().get(keepIdx).toString() : null;
        bmg.getMessages().get(editIdx).setText(newText);

        BinaryMessage reloaded = new BinaryMessage(bmg.save());
        assertThat(reloaded.getMessages()).hasSameSizeAs(bmg.getMessages());
        assertThat(reloaded.getMessages().get(editIdx).toString())
                .as("edited message text persisted through save/reload").isEqualTo(newText);
        if (keepIdx >= 0)
            assertThat(reloaded.getMessages().get(keepIdx).toString())
                    .as("a different message was left untouched").isEqualTo(keptText);
    }

    @Test
    @DisplayName("NitroFont glyph and width edits survive save/reload; neighbours untouched")
    void nftrGlyphAndWidthEditPersist()
    {
        byte[] fontBytes = findFirstFont();
        Assumptions.assumeTrue(fontBytes != null, "no decodable NFTR font found in the available ROM set");

        NitroFont font = new NitroFont(fontBytes);
        NitroFont.GlyphData gd = font.getGlyphData();
        Assumptions.assumeTrue(gd != null && gd.getNumGlyphs() >= 2, "font has too few glyphs to test");

        int g = 1;
        int n = gd.getCellWidth() * gd.getCellHeight();
        int[] neighbor0 = gd.getGlyphPixels(0);        // must stay unchanged
        // A distinctive pattern at full and zero intensity (both exactly representable at any bpp).
        int[] pattern = new int[n];
        for (int i = 0; i < n; i++) pattern[i] = (i % 2 == 0) ? 255 : 0;
        gd.setGlyphPixels(g, pattern);

        // Also edit a width entry, if this glyph has one.
        NitroFont.WidthGroup wg = null;
        int[] origW = null;
        for (NitroFont.WidthGroup group : font.getWidthGroups())
        {
            int[] w = group.widthsFor(g);
            if (w != null) { wg = group; origW = w; break; }
        }
        if (wg != null)
            wg.setWidths(g, origW[0], (origW[1] + 1) & 0xFF, origW[2]);

        NitroFont reloaded = new NitroFont(font.save());
        NitroFont.GlyphData rgd = reloaded.getGlyphData();
        assertThat(rgd.getGlyphPixels(g))
                .as("edited glyph bitmap persisted through save/reload").isEqualTo(pattern);
        assertThat(rgd.getGlyphPixels(0))
                .as("a neighbouring glyph was left untouched").isEqualTo(neighbor0);
        if (wg != null)
        {
            int[] rw = null;
            for (NitroFont.WidthGroup group : reloaded.getWidthGroups())
            {
                int[] w = group.widthsFor(g);
                if (w != null) { rw = w; break; }
            }
            assertThat(rw).as("width entry present after reload").isNotNull();
            assertThat(rw[1]).as("edited glyph width persisted").isEqualTo((origW[1] + 1) & 0xFF);
        }
    }

    // --- helpers: locate the first file of a given magic across the ROM set -------------------------

    private static byte[] findFirst(String magic)
    {
        for (String romName : ROMS)
        {
            Path path = TestRoms.romPath(romName);
            if (!Files.exists(path)) continue;
            NintendoDsRom rom;
            try { rom = NintendoDsRom.fromFile(path.toString()); } catch (RuntimeException e) { continue; }
            for (int i = 0; i < rom.getNumFiles(); i++)
            {
                byte[] found = search(rom.getFile(i), 0, magic, null);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static byte[] findFirstFont()
    {
        for (String magic : new String[]{"RTFN", "NFTR"})
        {
            for (String romName : ROMS)
            {
                Path path = TestRoms.romPath(romName);
                if (!Files.exists(path)) continue;
                NintendoDsRom rom;
                try { rom = NintendoDsRom.fromFile(path.toString()); } catch (RuntimeException e) { continue; }
                for (int i = 0; i < rom.getNumFiles(); i++)
                {
                    byte[] found = search(rom.getFile(i), 0, magic, FunctionalSetterTest::decodesAsFont);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    private static boolean decodesAsFont(byte[] d)
    {
        try { return new NitroFont(d).getGlyphData() != null && new NitroFont(d).getGlyphData().getNumGlyphs() >= 2; }
        catch (RuntimeException e) { return false; }
    }

    private interface Accept { boolean ok(byte[] d); }

    private static byte[] search(byte[] raw, int depth, String magic, Accept accept)
    {
        if (raw == null || raw.length < 4) return null;
        byte[] data = decompress(raw);
        if (data.length < 4) return null;
        String m = new String(data, 0, 4, StandardCharsets.ISO_8859_1);
        if (m.equals(magic) && (accept == null || accept.ok(data))) return data;
        if (m.equals("NARC") && depth < 6)
        {
            try
            {
                Narc narc = new Narc(data);
                for (int i = 0; i < narc.getNumFiles(); i++)
                {
                    byte[] found = search(narc.getFile(i), depth + 1, magic, accept);
                    if (found != null) return found;
                }
            }
            catch (RuntimeException ignored) { }
        }
        return null;
    }

    private static byte[] decompress(byte[] data)
    {
        try { if (NitroLz.isCompressed(data)) return NitroLz.decompress(data); }
        catch (RuntimeException ignored) { }
        return data;
    }
}
