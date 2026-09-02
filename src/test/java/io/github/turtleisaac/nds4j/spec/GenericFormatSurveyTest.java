package io.github.turtleisaac.nds4j.spec;

import io.github.turtleisaac.nds4j.Narc;
import io.github.turtleisaac.nds4j.NintendoDsRom;
import io.github.turtleisaac.nds4j.TestRoms;
import io.github.turtleisaac.nds4j.framework.NitroLz;
import io.github.turtleisaac.nds4j.g3d.MaterialColorAnimationSet;
import io.github.turtleisaac.nds4j.g3d.ModelSet;
import io.github.turtleisaac.nds4j.g3d.ParticleSet;
import io.github.turtleisaac.nds4j.g3d.SkeletalAnimationSet;
import io.github.turtleisaac.nds4j.g3d.TexturePatternAnimationSet;
import io.github.turtleisaac.nds4j.g3d.TextureSet;
import io.github.turtleisaac.nds4j.g3d.TextureSrtAnimationSet;
import io.github.turtleisaac.nds4j.g3d.VisibilityAnimationSet;
import io.github.turtleisaac.nds4j.images.CellAnimation;
import io.github.turtleisaac.nds4j.images.CellBank;
import io.github.turtleisaac.nds4j.images.IndexedImage;
import io.github.turtleisaac.nds4j.images.MultiCellAnimation;
import io.github.turtleisaac.nds4j.images.MultiCellBank;
import io.github.turtleisaac.nds4j.images.NitroFont;
import io.github.turtleisaac.nds4j.images.Palette;
import io.github.turtleisaac.nds4j.images.Screen;
import io.github.turtleisaac.nds4j.sound.InstrumentBank;
import io.github.turtleisaac.nds4j.sound.Sequence;
import io.github.turtleisaac.nds4j.sound.SequenceArchive;
import io.github.turtleisaac.nds4j.sound.SoundArchive;
import io.github.turtleisaac.nds4j.sound.Stream;
import io.github.turtleisaac.nds4j.sound.WaveArchive;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Not a regression test: a one-shot exploratory survey. Walks every embedded file of a ROM
 * (recursing into NARCs, decompressing LZ-compressed files) and, for anything whose four-byte
 * magic matches a generic Nitro/G3D/SDAT format Nds4j knows how to parse, round-trips it
 * (parse then {@code save()}) and reports whether the output is byte-identical to the input.
 * <p>
 * Purely informational -- it never fails the build. Read the console/report output (or the
 * surefire .txt report) for the actual findings; individual real bugs found this way get their
 * own permanent regression test elsewhere (see e.g. {@code NarcTest}, {@code ImageTest}) once
 * fixed.
 */
class GenericFormatSurveyTest
{
    private static final List<String> SURVEY_ROMS = Arrays.asList(
            "Animal Crossing - Wild World.nds",
            "Contact.nds",
            "Legend of Zelda, The - Phantom Hourglass.nds",
            "Freshly Picked - Tingle's Rosy Rupeeland.nds",
            "LEGO Star Wars - The Complete Saga.nds",
            "Mario & Luigi - Bowser's Inside Story.nds",
            "New Super Mario Bros.nds",
            "Super Mario 64 DS.nds",
            "Solatorobo - Red the Hunter.nds",
            "Mario Kart DS.nds",
            "Age of Empires - Mythologies.nds",
            "Age of Empires - The Age of Kings.nds"
    );

    private static final Map<String, Function<byte[], byte[]>> HANDLERS = new LinkedHashMap<>();
    static
    {
        HANDLERS.put("NARC", d -> new Narc(d).save());
        HANDLERS.put("RGCN", d -> new IndexedImage(d, 0, 0, 1, 1, true).save());
        HANDLERS.put("RLCN", d -> new Palette(d, 0).save());
        HANDLERS.put("RPCN", d -> new Palette(d, 0).save());
        HANDLERS.put("RECN", d -> new CellBank(d).save());
        HANDLERS.put("RNAN", d -> new CellAnimation(d).save());
        HANDLERS.put("RCMN", d -> new MultiCellBank(d).save());
        HANDLERS.put("RAMN", d -> new MultiCellAnimation(d).save());
        HANDLERS.put("RTFN", d -> new NitroFont(d).save());
        HANDLERS.put("NFTR", d -> new NitroFont(d).save());
        HANDLERS.put("RCSN", d -> new Screen(d).save());
        HANDLERS.put("BMD0", d -> new ModelSet(d).save());
        HANDLERS.put("BTX0", d -> new TextureSet(d).save());
        HANDLERS.put("BCA0", d -> new SkeletalAnimationSet(d).save());
        HANDLERS.put("BTP0", d -> new TexturePatternAnimationSet(d).save());
        HANDLERS.put("BTA0", d -> new TextureSrtAnimationSet(d).save());
        HANDLERS.put("BVA0", d -> new VisibilityAnimationSet(d).save());
        HANDLERS.put("BMA0", d -> new MaterialColorAnimationSet(d).save());
        HANDLERS.put(" APS", d -> new ParticleSet(d).save());
        HANDLERS.put("SDAT", d -> SoundArchive.fromBytes(d).save());
        HANDLERS.put("SWAR", d -> WaveArchive.fromBytes(d).save());
        HANDLERS.put("SBNK", d -> InstrumentBank.fromBytes(d).save());
        HANDLERS.put("SSEQ", d -> Sequence.fromBytes(d).save());
        HANDLERS.put("SSAR", d -> SequenceArchive.fromBytes(d).save());
        HANDLERS.put("STRM", d -> Stream.fromBytes(d).save());
    }

    private static final int MAX_NARC_RECURSION_DEPTH = 6;

    private static class FormatStats
    {
        int found;
        int roundTripOk;
        int roundTripMismatch;
        int parseFailed;
        final List<String> mismatchSamples = new ArrayList<>();
        final List<String> failureSamples = new ArrayList<>();
    }

    @Test
    void surveyGenericFormatsAcrossNewRoms()
    {
        StringBuilder report = new StringBuilder();
        boolean anyRomFound = false;

        for (String romName : SURVEY_ROMS)
        {
            Path path = TestRoms.romPath(romName);
            report.append("\n==== ").append(romName).append(" ====\n");
            if (!Files.exists(path))
            {
                report.append("  (ROM not found at ").append(path.toAbsolutePath()).append(", skipped)\n");
                continue;
            }
            anyRomFound = true;

            NintendoDsRom rom;
            try
            {
                rom = NintendoDsRom.fromFile(path.toString());
            }
            catch (RuntimeException e)
            {
                report.append("  FAILED TO LOAD ROM: ").append(e).append('\n');
                continue;
            }

            Map<String, FormatStats> stats = new TreeMap<>();
            for (int i = 0; i < rom.getNumFiles(); i++)
            {
                scan(rom.getFile(i), 0, stats);
            }

            if (stats.isEmpty())
            {
                report.append("  no generic-format files detected\n");
                continue;
            }

            for (Map.Entry<String, FormatStats> e : stats.entrySet())
            {
                FormatStats s = e.getValue();
                report.append(String.format("  %-6s found=%-6d roundTripOk=%-6d mismatch=%-6d parseFailed=%-6d%n",
                        e.getKey(), s.found, s.roundTripOk, s.roundTripMismatch, s.parseFailed));
                for (String sample : s.mismatchSamples)
                    report.append("      mismatch: ").append(sample).append('\n');
                for (String sample : s.failureSamples)
                    report.append("      failure:  ").append(sample).append('\n');
            }
        }

        System.out.println(report);
        try
        {
            Path out = Path.of(System.getProperty("rom.dir", "."), "nds4j_generic_format_survey.txt");
            Files.writeString(out, report.toString());
            System.out.println("Full report also written to " + out.toAbsolutePath());
        }
        catch (Exception ignored) { }

        Assumptions.assumeTrue(anyRomFound,
                "none of the survey ROMs were found -- set -Drom.dir=<dir containing them>");
    }

    private static void scan(byte[] raw, int depth, Map<String, FormatStats> stats)
    {
        if (raw == null || raw.length < 4)
            return;

        byte[] data = decompressIfNeeded(raw);
        if (data.length < 4)
            return;

        String m = magic(data);

        if (m.equals("NARC"))
        {
            recordAndRoundTrip(stats, "NARC", data);
            if (depth < MAX_NARC_RECURSION_DEPTH)
            {
                try
                {
                    Narc narc = new Narc(data);
                    for (int i = 0; i < narc.getNumFiles(); i++)
                        scan(narc.getFile(i), depth + 1, stats);
                }
                catch (RuntimeException ignored) { /* already counted as a NARC parse failure above */ }
            }
            return;
        }

        if (HANDLERS.containsKey(m))
        {
            recordAndRoundTrip(stats, m, data);
        }
    }

    private static void recordAndRoundTrip(Map<String, FormatStats> stats, String magic, byte[] data)
    {
        FormatStats s = stats.computeIfAbsent(magic, k -> new FormatStats());
        s.found++;
        try
        {
            byte[] written = HANDLERS.get(magic).apply(data);
            if (Arrays.equals(written, data))
            {
                s.roundTripOk++;
            }
            else
            {
                s.roundTripMismatch++;
                if (s.mismatchSamples.size() < 3)
                    s.mismatchSamples.add(diffSummary(data, written));
            }
        }
        catch (Exception e)
        {
            s.parseFailed++;
            if (s.failureSamples.size() < 3)
                s.failureSamples.add(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String diffSummary(byte[] original, byte[] written)
    {
        int firstDiff = -1;
        int minLen = Math.min(original.length, written.length);
        for (int i = 0; i < minLen; i++)
        {
            if (original[i] != written[i])
            {
                firstDiff = i;
                break;
            }
        }
        if (firstDiff == -1 && original.length != written.length)
            firstDiff = minLen;
        return String.format("len %d -> %d, first diff at 0x%X", original.length, written.length, firstDiff);
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
