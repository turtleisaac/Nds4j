package io.github.turtleisaac.nds4j.spec;

import io.github.turtleisaac.nds4j.Narc;
import io.github.turtleisaac.nds4j.NintendoDsRom;
import io.github.turtleisaac.nds4j.TestRoms;
import io.github.turtleisaac.nds4j.framework.NitroLz;
import io.github.turtleisaac.nds4j.g3d.MaterialColorAnimationSet;
import io.github.turtleisaac.nds4j.g3d.TextureSet;
import io.github.turtleisaac.nds4j.images.CellAnimation;
import io.github.turtleisaac.nds4j.images.CellBank;
import io.github.turtleisaac.nds4j.images.IndexedImage;
import io.github.turtleisaac.nds4j.images.MultiCellAnimation;
import io.github.turtleisaac.nds4j.images.MultiCellBank;
import io.github.turtleisaac.nds4j.images.NitroFont;
import io.github.turtleisaac.nds4j.images.Palette;
import io.github.turtleisaac.nds4j.images.Screen;
import io.github.turtleisaac.nds4j.text.BinaryMessage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Edit-path (get/set) round-trip survey across the retail ROM set.
 * <p>
 * The whole-file byte round-trip tests (parse then {@code save()}) never call a single setter — an
 * unedited file exercises only the read+write-back path, so an asymmetric or lossy get/set pair on a
 * <em>field</em> stays invisible to them. (Both of the BGR555 quantizer bugs fixed on this branch —
 * {@code TextureSet.setPaletteColor} and {@code MaterialColorAnimationSet.setRgb} — were exactly that:
 * {@code set(get())} silently drifted the value, yet every file still round-tripped byte-for-byte.)
 * <p>
 * This test walks every embedded file of each ROM (recursing NARCs, decompressing LZ), and for each
 * format with editable fields asserts two properties:
 * <ul>
 *   <li><b>Isolated</b> — for each field, {@code set(get())} then re-reading returns the same value
 *       (and, where the field is a distinct writable slot, a deliberately-perturbed value survives a
 *       re-read). This is the direct get/set idempotence check.</li>
 *   <li><b>Combined</b> — setting <em>every</em> field of an instance back to its own value must leave
 *       the serialized output byte-identical to the pre-edit serialization (a no-op edit is a no-op on
 *       disk); and for the formats where it's range-safe, a batch of perturbed values survives a full
 *       {@code save()} &rarr; reload &rarr; get.</li>
 * </ul>
 * The combined byte-identity check compares against the instance's <em>own</em> pre-edit {@code save()}
 * (not the source file), so it is independent of whether a given format is byte-exact against the ROM —
 * it isolates "did my get/set edits change anything they shouldn't." Note the scope: because it compares
 * a save against a later save of the same object, it catches <em>get/set asymmetry</em> (a setter lossier
 * than its getter, e.g. the fixed BGR555 and alpha-clamp bugs), setter side-effects, and getter/setter
 * addressing mismatches — but NOT the correctness of the encoder itself (a systematic packing bug corrupts
 * both saves equally). Encoder/decoder correctness is the job of the separate whole-file byte round-trip
 * survey; this test is the complementary edit-path half. Its teeth are therefore strongest on the
 * value-decoding setters (Palette/TextureSet color, MatColorAnim raw/alpha) and weaker on plain-field
 * setters (NCGR pixels, NMCR/anim scalar fields), where it verifies addressing and absence of side-effects.
 * <p>
 * Failures are accumulated with samples and asserted once at the end, so a run reports the full picture
 * rather than aborting at the first mismatch. ROMs that aren't present are skipped; the test is skipped
 * entirely if none are found (point it at them with {@code -Drom.dir=<dir>}).
 */
@DisplayName("get/set edit-path round-trips across the ROM set")
class EditRoundTripSurveyTest
{
    /** ROMs to survey. Gen-IV titles carry the 3D (BMA0/BTX0) formats; all carry the 2D formats. */
    private static final List<String> ROMS = Arrays.asList(
            "Diamond.nds", "Pearl.nds", "Platinum.nds", "HeartGold.nds", "SoulSilver.nds", "White2.nds",
            "Mario Kart DS.nds", "New Super Mario Bros.nds", "Super Mario 64 DS.nds",
            "Legend of Zelda, The - Phantom Hourglass.nds", "Pokemon Ranger - Shadows of Almia.nds"
    );

    private static final int MAX_NARC_RECURSION_DEPTH = 6;

    /** Per-format accumulator: how many instances/fields were exercised and every mismatch found. */
    private static final class Stat
    {
        int instances;
        long fields;
        final List<String> failures = new ArrayList<>();

        void fail(String msg)
        {
            if (failures.size() < 8) failures.add(msg);
            else if (failures.size() == 8) failures.add("... (further failures suppressed)");
        }
    }

    @Test
    @DisplayName("every get/set field pair is idempotent and survives serialization")
    void editRoundTripsAcrossRoms()
    {
        Map<String, Stat> stats = new TreeMap<>();
        StringBuilder report = new StringBuilder();
        boolean anyRomFound = false;

        for (String romName : ROMS)
        {
            Path path = TestRoms.romPath(romName);
            report.append("\n==== ").append(romName).append(" ====\n");
            if (!Files.exists(path))
            {
                report.append("  (not found, skipped)\n");
                continue;
            }
            anyRomFound = true;

            NintendoDsRom rom;
            try { rom = NintendoDsRom.fromFile(path.toString()); }
            catch (RuntimeException e) { report.append("  FAILED TO LOAD: ").append(e).append('\n'); continue; }

            for (int i = 0; i < rom.getNumFiles(); i++)
                walk(rom.getFile(i), 0, stats);
        }

        long totalFailures = 0, totalInstances = 0, totalFields = 0;
        List<String> starvedFormats = new ArrayList<>();
        for (Map.Entry<String, Stat> e : stats.entrySet())
        {
            Stat s = e.getValue();
            totalFailures += s.failures.size();
            totalInstances += s.instances;
            totalFields += s.fields;
            report.append(String.format("%-28s instances=%-6d fields=%-9d failures=%d%n",
                    e.getKey(), s.instances, s.fields, s.failures.size()));
            for (String f : s.failures)
                report.append("      ! ").append(f).append('\n');
            // A format that parsed instances but exercised almost no fields is silently starved -- e.g.
            // every instance aborting mid-check. Flag it (an average of <1 field/instance is impossible
            // for any format here if its checker ran to completion).
            if (s.instances > 0 && s.fields < s.instances)
                starvedFormats.add(String.format("%s (%d instances, only %d fields)", e.getKey(), s.instances, s.fields));
        }
        System.out.println(report);

        Assumptions.assumeTrue(anyRomFound,
                "none of the survey ROMs were found -- set -Drom.dir=<dir containing them>");

        // Guard against silent degradation: if a parsing regression made every instance throw, the
        // failure count would still be zero and this test would pass green while testing nothing.
        assertThat(totalInstances)
                .as("survey exercised no editable-format instances -- parsing may have regressed:\n%s", report)
                .isPositive();
        assertThat(totalFields)
                .as("survey exercised no get/set field pairs:\n%s", report)
                .isPositive();
        // Per-format guard: catches a checker that aborts on most files (fewer fields than instances),
        // which the global totals would otherwise hide behind a high-volume format like NCGR.
        assertThat(starvedFormats)
                .as("formats exercised far fewer fields than instances (checker likely aborting):\n%s", report)
                .isEmpty();

        assertThat(totalFailures)
                .as("get/set edit-path round-trip mismatches:\n%s", report)
                .isZero();
    }

    // ------------------------------------------------------------------ dispatch

    private static void walk(byte[] raw, int depth, Map<String, Stat> stats)
    {
        if (raw == null || raw.length < 4) return;
        byte[] data = decompressIfNeeded(raw);
        if (data.length < 4) return;

        String m = magic(data);
        if (m.equals("NARC"))
        {
            if (depth < MAX_NARC_RECURSION_DEPTH)
            {
                try
                {
                    Narc narc = new Narc(data);
                    for (int i = 0; i < narc.getNumFiles(); i++)
                        walk(narc.getFile(i), depth + 1, stats);
                }
                catch (RuntimeException ignored) { /* parse failures are the byte-survey's concern, not this test's */ }
            }
            return;
        }

        try
        {
            switch (m)
            {
                case "RCSN": checkScreen(data, stats); break;
                case "RECN": checkCellBank(data, stats); break;
                case "RLCN":
                case "RPCN": checkPalette(data, stats); break;
                case "RGCN": checkIndexedImage(data, stats); break;
                case "RCMN": checkMultiCellBank(data, stats); break;
                case "RNAN": checkCellAnimation(data, stats); break;
                case "RAMN": checkMultiCellAnimation(data, stats); break;
                case "RTFN":
                case "NFTR": checkNitroFont(data, stats); break;
                case "MESG": checkBinaryMessage(data, stats); break;
                case "BTX0": checkTextureSet(data, stats); break;
                case "BMA0": checkMaterialColorAnim(data, stats); break;
                default: break;
            }
        }
        catch (RuntimeException ignored)
        {
            // A format that fails to parse is covered by the parse/byte survey; this test only judges
            // the get/set behaviour of instances that parse cleanly.
        }
    }

    // ------------------------------------------------------------------ Screen (NSCR)

    private static void checkScreen(byte[] data, Map<String, Stat> stats)
    {
        Screen s = new Screen(data);
        Stat st = stats.computeIfAbsent("Screen (NSCR)", k -> new Stat());
        st.instances++;
        int n = s.getNumEntries();

        // (combined) no-op edit: set every sub-field of every entry back to itself -> save() unchanged.
        byte[] base = s.save();
        for (int i = 0; i < n; i++)
        {
            s.setTileIndex(i, s.getTileIndex(i));
            s.setPaletteIndex(i, s.getPaletteIndex(i));
            s.setHorizontalFlip(i, s.isHorizontalFlip(i));
            s.setVerticalFlip(i, s.isVerticalFlip(i));
        }
        st.fields += n * 4L;
        if (!Arrays.equals(base, s.save()))
            st.fail("NSCR: no-op set(get()) over " + n + " entries changed save()");

        // (combined) perturbation: distinct in-range values per entry survive save -> reload -> get.
        // Entry layout is fully partitioned (tile 10b | Hflip | Vflip | palette 4b), so these ranges
        // are always valid and mutually independent.
        int[] tile = new int[n], pal = new int[n];
        boolean[] hf = new boolean[n], vf = new boolean[n];
        for (int i = 0; i < n; i++)
        {
            tile[i] = (i * 7) & 0x3FF;
            pal[i] = (i * 3) & 0xF;
            hf[i] = (i & 1) != 0;
            vf[i] = (i & 2) != 0;
            s.setTileIndex(i, tile[i]);
            s.setPaletteIndex(i, pal[i]);
            s.setHorizontalFlip(i, hf[i]);
            s.setVerticalFlip(i, vf[i]);
        }
        Screen re = new Screen(s.save());
        for (int i = 0; i < n; i++)
        {
            if (re.getTileIndex(i) != tile[i] || re.getPaletteIndex(i) != pal[i]
                    || re.isHorizontalFlip(i) != hf[i] || re.isVerticalFlip(i) != vf[i])
            {
                st.fail(String.format("NSCR: entry %d perturbation lost across save/reload "
                                + "(tile %d->%d pal %d->%d h %b->%b v %b->%b)",
                        i, tile[i], re.getTileIndex(i), pal[i], re.getPaletteIndex(i),
                        hf[i], re.isHorizontalFlip(i), vf[i], re.isVerticalFlip(i)));
                break;
            }
        }
    }

    // ------------------------------------------------------------------ CellBank OAM (NCER)

    private static void checkCellBank(byte[] data, Map<String, Stat> stats)
    {
        CellBank bank = new CellBank(data);
        Stat st = stats.computeIfAbsent("CellBank OAM (NCER)", k -> new Stat());
        st.instances++;

        byte[] base = bank.save();
        long fields = 0;
        for (int c = 0; c < bank.getNumCells(); c++)
        {
            CellBank.Cell cell = bank.getCell(c);
            CellBank.Cell.OAM[] oams = cell.getOams();
            if (oams == null) continue;
            for (CellBank.Cell.OAM o : oams)
            {
                // (isolated) each field: set(get()) leaves the getter returning the same value.
                idempotentInt(st, "NCER OAM.yCoord", o.getYCoord(), o::setYCoord, o::getYCoord);
                idempotentInt(st, "NCER OAM.xCoord", o.getXCoord(), o::setXCoord, o::getXCoord);
                idempotentInt(st, "NCER OAM.shape", o.getShape(), o::setShape, o::getShape);
                idempotentInt(st, "NCER OAM.size", o.getSize(), o::setSize, o::getSize);
                idempotentInt(st, "NCER OAM.tileOffset", o.getTileOffset(), o::setTileOffset, o::getTileOffset);
                idempotentInt(st, "NCER OAM.palette", o.getPalette(), o::setPalette, o::getPalette);
                idempotentInt(st, "NCER OAM.priority", o.getPriority(), o::setPriority, o::getPriority);
                idempotentInt(st, "NCER OAM.mode", o.getMode(), o::setMode, o::getMode);
                idempotentInt(st, "NCER OAM.colors", o.getColors(), o::setColors, o::getColors);
                idempotentInt(st, "NCER OAM.rotationScaling", o.getRotationScaling(), o::setRotationScaling, o::getRotationScaling);
                idempotentBool(st, "NCER OAM.mosaic", o.isMosaic(), o::setMosaic, o::isMosaic);
                idempotentBool(st, "NCER OAM.rotation", o.isRotation(), o::setRotation, o::isRotation);
                idempotentBool(st, "NCER OAM.sizeDisable", o.isSizeDisable(), o::setSizeDisable, o::isSizeDisable);
                fields += 13;
            }
        }
        st.fields += fields;

        // (combined) after setting every OAM field back to itself, the re-serialized bank is unchanged.
        if (!Arrays.equals(base, bank.save()))
            st.fail("NCER: no-op set(get()) over all OAM fields changed save()");
    }

    // ------------------------------------------------------------------ Palette (NCLR)

    private static void checkPalette(byte[] data, Map<String, Stat> stats)
    {
        Palette pal = new Palette(data, 0);
        Stat st = stats.computeIfAbsent("Palette (NCLR)", k -> new Stat());
        st.instances++;
        int n = pal.getNumColors();

        // Capture the decoded colors, then write each one straight back with setColor. Because the value
        // written is exactly what was decoded, a correct BGR555 encode/decode pair must reproduce it
        // through save() + reload. (An asymmetric quantizer would drift the channel here.)
        Color[] before = new Color[n];
        for (int i = 0; i < n; i++)
        {
            before[i] = pal.getColor(i);
            pal.setColor(i, before[i]);
        }
        st.fields += n;

        Palette re = new Palette(pal.save(), 0);
        for (int i = 0; i < n; i++)
        {
            Color a = before[i], b = re.getColor(i);
            if (a.getRed() != b.getRed() || a.getGreen() != b.getGreen() || a.getBlue() != b.getBlue())
            {
                st.fail(String.format("NCLR: color %d drifted across set/save/reload rgb(%d,%d,%d)->(%d,%d,%d)",
                        i, a.getRed(), a.getGreen(), a.getBlue(), b.getRed(), b.getGreen(), b.getBlue()));
                break;
            }
        }
    }

    // ------------------------------------------------------------------ TextureSet palettes (NSBTX/BTX0)

    private static void checkTextureSet(byte[] data, Map<String, Stat> stats)
    {
        TextureSet ts = new TextureSet(data);
        Stat st = stats.computeIfAbsent("TextureSet palette (BTX0)", k -> new Stat());
        st.instances++;

        byte[] base = ts.save();
        long fields = 0;
        for (TextureSet.Palette p : ts.getPalettes())
        {
            // No public per-palette color count; getPaletteColor/setPaletteColor are both bounds-guarded
            // (out-of-range indices read 0 / write nothing), so probing a fixed span is safe and any
            // in-range index exercises the real encode/decode.
            for (int i = 0; i < 256; i++)
            {
                int rgb = ts.getPaletteColor(p, i);
                ts.setPaletteColor(p, i, rgb);        // set(get()) must be idempotent...
                int rgb2 = ts.getPaletteColor(p, i);
                fields++;
                if (rgb != rgb2)
                {
                    st.fail(String.format("BTX0: palette '%s' index %d drifted on set(get()) 0x%06X->0x%06X",
                            p.getName(), i, rgb, rgb2));
                    break;
                }
            }
        }
        st.fields += fields;

        // (combined) writing every color back to itself must not change the serialized TEX0.
        if (!Arrays.equals(base, ts.save()))
            st.fail("BTX0: no-op setPaletteColor(get()) over all palettes changed save()");
    }

    // ------------------------------------------------------------------ MaterialColorAnimationSet (NSBMA/BMA0)

    private static void checkMaterialColorAnim(byte[] data, Map<String, Stat> stats)
    {
        MaterialColorAnimationSet mca = new MaterialColorAnimationSet(data);
        Stat st = stats.computeIfAbsent("MatColorAnim (BMA0)", k -> new Stat());
        st.instances++;

        byte[] base = mca.save();
        long fields = 0;

        // (combined) no-op edit using only the lossless accessors: write every color word and every
        // alpha value straight back with setRaw/set. A correct implementation leaves save() unchanged.
        // (setRgb is deliberately excluded here -- it authors a canonical 15-bit color from RGB888 and
        // legitimately clears the color word's unused bit 15, so it belongs in the rgb idempotence check
        // below, not in a byte-preserving no-op.) Colors and alpha are exercised on separate fresh
        // instances so a failure pinpoints which accessor is not byte-preserving.
        MaterialColorAnimationSet colorOnly = new MaterialColorAnimationSet(data);
        for (MaterialColorAnimationSet.Animation anim : colorOnly.getAnimations())
        {
            int frames = Math.max(1, anim.getFrameCount());
            for (MaterialColorAnimationSet.MaterialColor mat : anim.getMaterials())
                for (MaterialColorAnimationSet.ColorChannel ch : colorChannels(mat))
                {
                    if (ch == null) continue;
                    int fc = ch.isConstant() ? 1 : frames;
                    for (int f = 0; f < fc; f++) { ch.setRaw(f, ch.rawAt(f)); fields++; }
                }
        }
        byte[] colorSave = colorOnly.save();
        if (!Arrays.equals(base, colorSave))
            st.fail("BMA0: color setRaw(rawAt()) no-op changed save() at 0x" + Integer.toHexString(firstDiff(base, colorSave)));

        MaterialColorAnimationSet alphaOnly = new MaterialColorAnimationSet(data);
        for (MaterialColorAnimationSet.Animation anim : alphaOnly.getAnimations())
        {
            int frames = Math.max(1, anim.getFrameCount());
            for (MaterialColorAnimationSet.MaterialColor mat : anim.getMaterials())
            {
                MaterialColorAnimationSet.ScalarChannel alpha = mat.getAlpha();
                if (alpha == null) continue;
                int fc = alpha.isConstant() ? 1 : frames;
                for (int f = 0; f < fc; f++)
                {
                    int v = alpha.at(f);
                    alpha.set(f, v);
                    if (alpha.at(f) != v)
                        st.fail(String.format("BMA0: alpha not idempotent (constant=%b frame %d) at()=%d -> set -> at()=%d",
                                alpha.isConstant(), f, v, alpha.at(f)));
                    fields++;
                }
            }
        }
        byte[] alphaSave = alphaOnly.save();
        if (!Arrays.equals(base, alphaSave))
            st.fail("BMA0: alpha set(at()) no-op changed save() at 0x" + Integer.toHexString(firstDiff(base, alphaSave)));

        // (isolated) rgb get/set idempotence -- the exact property that setRgb's old `* 31 / 255`
        // quantizer violated. Done after the combined check because setRgb mutates the color word.
        for (MaterialColorAnimationSet.Animation anim : mca.getAnimations())
        {
            int frames = Math.max(1, anim.getFrameCount());
            for (MaterialColorAnimationSet.MaterialColor mat : anim.getMaterials())
            {
                for (MaterialColorAnimationSet.ColorChannel ch : colorChannels(mat))
                {
                    if (ch == null) continue;
                    int fc = ch.isConstant() ? 1 : frames;
                    for (int f = 0; f < fc; f++)
                    {
                        int rgb = ch.rgbAt(f);
                        ch.setRgb(f, rgb);
                        if (ch.rgbAt(f) != rgb)
                            st.fail(String.format("BMA0: rgb drifted on set(get()) frame %d 0x%06X->0x%06X",
                                    f, rgb, ch.rgbAt(f)));
                        fields++;
                    }
                }
            }
        }
        st.fields += fields;
    }

    // ------------------------------------------------------------------ helpers

    // ------------------------------------------------------------------ IndexedImage (NCGR)

    private static void checkIndexedImage(byte[] data, Map<String, Stat> stats)
    {
        IndexedImage img = new IndexedImage(data, 0, 0, 1, 1, true);
        Stat st = stats.computeIfAbsent("IndexedImage (NCGR)", k -> new Stat());
        st.instances++;
        // Scanned NCGRs store pixels in a scrambled order; getPixelValue/setPixelValue are not a clean
        // (x,y) pair there, so leave them to the byte round-trip rather than risk a false failure.
        if (img.isScanned()) return;

        int w = img.getWidth(), h = img.getHeight();
        byte[] base = img.save();
        long fields = 0;
        boolean drift = false;
        for (int y = 0; y < h && !drift; y++)
            for (int x = 0; x < w; x++)
            {
                int v = img.getPixelValue(x, y);
                img.setPixelValue(x, y, v);
                if (img.getPixelValue(x, y) != v)
                {
                    st.fail(String.format("NCGR: pixel (%d,%d) set(get()) drifted %d->%d", x, y, v, img.getPixelValue(x, y)));
                    drift = true;
                    break;
                }
                fields++;
            }
        st.fields += fields;

        // (combined) writing every pixel back to its own value must not change the encoded character data.
        if (!Arrays.equals(base, img.save()))
            st.fail("NCGR: no-op setPixelValue(get()) over " + w + "x" + h + " changed save()");
    }

    // ------------------------------------------------------------------ MultiCellBank (NMCR)

    private static void checkMultiCellBank(byte[] data, Map<String, Stat> stats)
    {
        MultiCellBank mcb = new MultiCellBank(data);
        Stat st = stats.computeIfAbsent("MultiCellBank (NMCR)", k -> new Stat());
        st.instances++;

        byte[] base = mcb.save();
        long fields = 0;
        for (MultiCellBank.MultiCell mc : mcb.getMultiCells())
        {
            idempotentInt(st, "NMCR MultiCell.attribute", mc.getAttribute(), mc::setAttribute, mc::getAttribute);
            fields++;
            MultiCellBank.CellInfo[] infos = mc.getCellInfos();
            if (infos == null) continue;
            for (MultiCellBank.CellInfo ci : infos)
            {
                idempotentInt(st, "NMCR CellInfo.cellIndex", ci.getCellIndex(), ci::setCellIndex, ci::getCellIndex);
                idempotentInt(st, "NMCR CellInfo.x", ci.getX(), ci::setX, ci::getX);
                idempotentInt(st, "NMCR CellInfo.y", ci.getY(), ci::setY, ci::getY);
                idempotentInt(st, "NMCR CellInfo.attr", ci.getAttr(), ci::setAttr, ci::getAttr);
                fields += 4;
            }
        }
        st.fields += fields;
        if (!Arrays.equals(base, mcb.save()))
            st.fail("NMCR: no-op set(get()) over all multi-cell fields changed save()");
    }

    // ------------------------------------------------------------------ CellAnimation (NANR)

    private static void checkCellAnimation(byte[] data, Map<String, Stat> stats)
    {
        CellAnimation ca = new CellAnimation(data);
        Stat st = stats.computeIfAbsent("CellAnimation (NANR)", k -> new Stat());
        st.instances++;
        long fields = 0;
        // Field-phase exceptions are recorded as failures (not left to walk()'s parse-skip catch), so a
        // setter that unexpectedly throws surfaces as a red failure instead of silently dropping the file.
        try
        {
            byte[] base = ca.save();
            for (CellAnimation.Animation an : ca.getAnimations())
            {
                idempotentObj(st, "NANR Animation.name", an.getName(), an::setName, an::getName);
                idempotentInt(st, "NANR Animation.loopStartFrame", an.getLoopStartFrame(), an::setLoopStartFrame, an::getLoopStartFrame);
                idempotentLong(st, "NANR Animation.type", an.getType(), an::setType, an::getType);
                idempotentLong(st, "NANR Animation.mode", an.getMode(), an::setMode, an::getMode);
                fields += 4;
                int el = an.getElement();   // element type is per-animation; all its frames share it
                for (CellAnimation.Animation.Frame fr : an.getFrames())
                {
                    idempotentInt(st, "NANR Frame.duration", fr.getDuration(), fr::setDuration, fr::getDuration);
                    idempotentInt(st, "NANR Frame.cellIndex", fr.getCellIndex(), fr::setCellIndex, fr::getCellIndex);
                    fields += 2;
                    // translate exists only for SRT/TRANSLATION frames; rotation only for SRT (its setter
                    // throws otherwise). Gate on element type so the check exercises real frames instead
                    // of aborting the whole file on a require-SRT guard.
                    if (el == CellAnimation.ELEMENT_SRT || el == CellAnimation.ELEMENT_TRANSLATION)
                    {
                        int tx = fr.getTranslateX(), ty = fr.getTranslateY();
                        fr.setTranslate(tx, ty);
                        if (fr.getTranslateX() != tx || fr.getTranslateY() != ty)
                            st.fail(String.format("NANR: Frame.translate set(get()) drifted (%d,%d)->(%d,%d)",
                                    tx, ty, fr.getTranslateX(), fr.getTranslateY()));
                        fields++;
                    }
                    if (el == CellAnimation.ELEMENT_SRT)
                    {
                        idempotentInt(st, "NANR Frame.rotation", fr.getRotation(), fr::setRotation, fr::getRotation);
                        fields++;
                    }
                }
            }
            // Scale (double, fixed-point-backed) is deliberately not touched: it is a lossy conversion by
            // design, so a no-op edit of it is not required to be byte-preserving.
            if (!Arrays.equals(base, ca.save()))
                st.fail("NANR: no-op set(get()) over frame/animation fields changed save()");
        }
        catch (RuntimeException e)
        {
            st.fail("NANR: unexpected exception during get/set checks: " + e);
        }
        finally { st.fields += fields; }
    }

    // ------------------------------------------------------------------ MultiCellAnimation (NMAR)

    private static void checkMultiCellAnimation(byte[] data, Map<String, Stat> stats)
    {
        MultiCellAnimation ma = new MultiCellAnimation(data);
        Stat st = stats.computeIfAbsent("MultiCellAnim (NMAR)", k -> new Stat());
        st.instances++;
        long fields = 0;
        try
        {
            byte[] base = ma.save();
            for (MultiCellAnimation.Animation an : ma.getAnimations())
            {
                idempotentObj(st, "NMAR Animation.name", an.getName(), an::setName, an::getName);
                idempotentInt(st, "NMAR Animation.loopStartFrame", an.getLoopStartFrame(), an::setLoopStartFrame, an::getLoopStartFrame);
                idempotentLong(st, "NMAR Animation.type", an.getType(), an::setType, an::getType);
                idempotentLong(st, "NMAR Animation.mode", an.getMode(), an::setMode, an::getMode);
                fields += 4;
                int el = an.getElement();   // element type is per-animation; all its frames share it
                for (MultiCellAnimation.Animation.Frame fr : an.getFrames())
                {
                    idempotentInt(st, "NMAR Frame.duration", fr.getDuration(), fr::setDuration, fr::getDuration);
                    idempotentInt(st, "NMAR Frame.multiCellIndex", fr.getMultiCellIndex(), fr::setMultiCellIndex, fr::getMultiCellIndex);
                    fields += 2;
                    if (el == MultiCellAnimation.ELEMENT_SRT || el == MultiCellAnimation.ELEMENT_TRANSLATION)
                    {
                        int tx = fr.getTranslateX(), ty = fr.getTranslateY();
                        fr.setTranslate(tx, ty);
                        if (fr.getTranslateX() != tx || fr.getTranslateY() != ty)
                            st.fail(String.format("NMAR: Frame.translate set(get()) drifted (%d,%d)->(%d,%d)",
                                    tx, ty, fr.getTranslateX(), fr.getTranslateY()));
                        fields++;
                    }
                    if (el == MultiCellAnimation.ELEMENT_SRT)
                    {
                        idempotentInt(st, "NMAR Frame.rotation", fr.getRotation(), fr::setRotation, fr::getRotation);
                        fields++;
                    }
                }
            }
            if (!Arrays.equals(base, ma.save()))
                st.fail("NMAR: no-op set(get()) over frame/animation fields changed save()");
        }
        catch (RuntimeException e)
        {
            st.fail("NMAR: unexpected exception during get/set checks: " + e);
        }
        finally { st.fields += fields; }
    }

    // ------------------------------------------------------------------ NitroFont (NFTR)

    private static void checkNitroFont(byte[] data, Map<String, Stat> stats)
    {
        NitroFont font = new NitroFont(data);
        Stat st = stats.computeIfAbsent("NitroFont (NFTR)", k -> new Stat());
        st.instances++;
        long fields = 0;
        try
        {
            byte[] base = font.save();

            NitroFont.FontInfo fi = font.getFontInfo();
            idempotentInt(st, "NFTR FINF.lineFeed", fi.getLineFeed(), fi::setLineFeed, fi::getLineFeed);
            idempotentInt(st, "NFTR FINF.defaultLeft", fi.getDefaultLeft(), fi::setDefaultLeft, fi::getDefaultLeft);
            idempotentInt(st, "NFTR FINF.defaultGlyphWidth", fi.getDefaultGlyphWidth(), fi::setDefaultGlyphWidth, fi::getDefaultGlyphWidth);
            idempotentInt(st, "NFTR FINF.defaultCharWidth", fi.getDefaultCharWidth(), fi::setDefaultCharWidth, fi::getDefaultCharWidth);
            fields += 4;
            if (fi.getHeight() >= 0)   // extended (0x0102) header only
            {
                idempotentInt(st, "NFTR FINF.height", fi.getHeight(), fi::setHeight, fi::getHeight);
                idempotentInt(st, "NFTR FINF.width", fi.getWidth(), fi::setWidth, fi::getWidth);
                idempotentInt(st, "NFTR FINF.ascent", fi.getAscent(), fi::setAscent, fi::getAscent);
                fields += 3;
            }

            // Glyph bitmaps: setGlyphPixels(getGlyphPixels(g)) must reproduce the exact pixels -- real
            // encode/decode teeth (the bpp re-quantisation must invert the decode).
            NitroFont.GlyphData gd = font.getGlyphData();
            if (gd != null)
                for (int g = 0; g < gd.getNumGlyphs(); g++)
                {
                    int[] px = gd.getGlyphPixels(g);
                    gd.setGlyphPixels(g, px);
                    if (!Arrays.equals(px, gd.getGlyphPixels(g)))
                        st.fail("NFTR: glyph " + g + " pixels drifted on set(get())");
                    fields++;
                }

            // Per-glyph widths.
            for (NitroFont.WidthGroup wg : font.getWidthGroups())
                for (int i = 0; i < wg.getNumEntries(); i++)
                {
                    int g = wg.getIndexBegin() + i;
                    int[] w = wg.widthsFor(g);
                    if (w == null) continue;
                    wg.setWidths(g, w[0], w[1], w[2]);
                    int[] w2 = wg.widthsFor(g);
                    if (w2 == null || w2[0] != w[0] || w2[1] != w[1] || w2[2] != w[2])
                        st.fail("NFTR: width group glyph " + g + " drifted on set(get())");
                    fields++;
                }

            if (!Arrays.equals(base, font.save()))
                st.fail("NFTR: no-op set(get()) over glyphs/widths/metrics changed save()");
        }
        catch (RuntimeException e)
        {
            st.fail("NFTR: unexpected exception during get/set checks: " + e);
        }
        finally { st.fields += fields; }
    }

    // ------------------------------------------------------------------ BinaryMessage (BMG)

    private static void checkBinaryMessage(byte[] data, Map<String, Stat> stats)
    {
        BinaryMessage bmg = new BinaryMessage(data);
        Stat st = stats.computeIfAbsent("BinaryMessage (BMG)", k -> new Stat());
        st.instances++;
        long fields = 0;
        try
        {
            byte[] base = bmg.save();
            for (BinaryMessage.Message m : bmg.getMessages())
            {
                m.setInfo(m.getInfo());
                fields++;
                if (!m.isNull())
                {
                    m.setParts(m.getParts());   // re-encodes the same text runs / escapes
                    fields++;
                }
                for (Object part : m.getParts())
                {
                    if (!(part instanceof BinaryMessage.Message.Escape)) continue;
                    BinaryMessage.Message.Escape esc = (BinaryMessage.Message.Escape) part;
                    idempotentInt(st, "BMG Escape.type", esc.getType(), esc::setType, esc::getType);
                    byte[] d0 = esc.getData();
                    esc.setData(d0);
                    if (!Arrays.equals(d0, esc.getData()))
                        st.fail("BMG: escape data drifted on set(get())");
                    fields += 2;
                }
            }
            idempotentInt(st, "BMG.encoding", bmg.getEncoding(), bmg::setEncoding, bmg::getEncoding);
            fields++;

            if (!Arrays.equals(base, bmg.save()))
                st.fail("BMG: no-op set(get()) over messages/escapes changed save()");
        }
        catch (RuntimeException e)
        {
            st.fail("BMG: unexpected exception during get/set checks: " + e);
        }
        finally { st.fields += fields; }
    }

    private static MaterialColorAnimationSet.ColorChannel[] colorChannels(MaterialColorAnimationSet.MaterialColor mat)
    {
        return new MaterialColorAnimationSet.ColorChannel[]{
                mat.getDiffuse(), mat.getAmbient(), mat.getSpecular(), mat.getEmission()
        };
    }

    private interface IntSetter { void set(int v); }
    private interface IntGetter { int get(); }
    private interface LongSetter { void set(long v); }
    private interface LongGetter { long get(); }
    private interface BoolSetter { void set(boolean v); }
    private interface BoolGetter { boolean get(); }

    private static void idempotentInt(Stat st, String field, int original, IntSetter set, IntGetter get)
    {
        set.set(original);
        int now = get.get();
        if (now != original)
            st.fail(field + ": set(get())=" + original + " re-read as " + now);
    }

    private static void idempotentLong(Stat st, String field, long original, LongSetter set, LongGetter get)
    {
        set.set(original);
        long now = get.get();
        if (now != original)
            st.fail(field + ": set(get())=" + original + " re-read as " + now);
    }

    private interface ObjSetter<T> { void set(T v); }
    private interface ObjGetter<T> { T get(); }

    private static <T> void idempotentObj(Stat st, String field, T original, ObjSetter<T> set, ObjGetter<T> get)
    {
        set.set(original);
        T now = get.get();
        if (!java.util.Objects.equals(now, original))
            st.fail(field + ": set(get())=" + original + " re-read as " + now);
    }

    private static void idempotentBool(Stat st, String field, boolean original, BoolSetter set, BoolGetter get)
    {
        set.set(original);
        boolean now = get.get();
        if (now != original)
            st.fail(field + ": set(get())=" + original + " re-read as " + now);
    }

    private static int firstDiff(byte[] a, byte[] b)
    {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) if (a[i] != b[i]) return i;
        return n;
    }

    private static String magic(byte[] data)
    {
        return new String(data, 0, 4, StandardCharsets.ISO_8859_1);
    }

    private static byte[] decompressIfNeeded(byte[] data)
    {
        try
        {
            if (NitroLz.isCompressed(data))
                return NitroLz.decompress(data);
        }
        catch (RuntimeException ignored) { }
        return data;
    }
}
