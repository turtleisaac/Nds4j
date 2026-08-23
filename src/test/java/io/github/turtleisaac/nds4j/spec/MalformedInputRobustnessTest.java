package io.github.turtleisaac.nds4j.spec;

import io.github.turtleisaac.nds4j.Fnt;
import io.github.turtleisaac.nds4j.Narc;
import io.github.turtleisaac.nds4j.framework.CodeCompression;
import io.github.turtleisaac.nds4j.images.IndexedImage;
import io.github.turtleisaac.nds4j.images.Palette;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Totality of the parsing entry points.
 * <p>
 * Every one of these functions is fed bytes the user did not write: a third-party ROM, a
 * hand-edited NARC, a file truncated by a failed download. Such input is <em>expected</em> to be
 * rejected &mdash; but the way it is rejected matters. A parser is well-behaved when a malformed
 * input produces a diagnosable failure that names the problem, and ill-behaved when it produces
 * a bare {@link NullPointerException} or {@link ArrayIndexOutOfBoundsException}, which tells the
 * user nothing and usually means an index was computed from unvalidated file data and then used
 * directly.
 * <p>
 * So the property asserted here is not "does not throw" &mdash; it is "throws something that
 * carries information". Raw JVM errors of the kinds listed in {@link #UNDIAGNOSABLE} indicate a
 * missing bounds check, and each one is a latent crash on a real user's corrupt file.
 */
@DisplayName("Parsers reject malformed input diagnosably rather than crashing")
class MalformedInputRobustnessTest
{
    /**
     * Exception types that indicate the parser walked off the end of something rather than
     * detecting a problem. {@code StackOverflowError} is included because unbounded recursion
     * on a malformed tree is the same class of defect.
     */
    private static final List<Class<? extends Throwable>> UNDIAGNOSABLE = List.of(
            NullPointerException.class,
            ArrayIndexOutOfBoundsException.class,
            StringIndexOutOfBoundsException.class,
            IndexOutOfBoundsException.class,
            NegativeArraySizeException.class,
            ArithmeticException.class,
            ClassCastException.class,
            StackOverflowError.class);

    private static boolean isUndiagnosable(Throwable t)
    {
        return UNDIAGNOSABLE.stream().anyMatch(type -> type.isInstance(t));
    }

    /**
     * Corpus of hostile inputs: empty, tiny, truncated, all-zero, all-ones, random, and random
     * data carrying a plausible NTR magic so the parser gets past its first check and starts
     * trusting the length fields that follow.
     */
    private static List<byte[]> corpus(String magic)
    {
        Random rng = new Random(0xBADF00D);
        List<byte[]> inputs = new ArrayList<>();

        for (int len : new int[]{0, 1, 3, 4, 7, 8, 15, 16, 17, 31, 64, 255})
        {
            inputs.add(new byte[len]);                       // all zero
            byte[] ones = new byte[len];
            java.util.Arrays.fill(ones, (byte) 0xFF);
            inputs.add(ones);                                // all ones -> huge unsigned lengths
            byte[] random = new byte[len];
            rng.nextBytes(random);
            inputs.add(random);
        }

        // Plausible-looking headers: correct magic, garbage everywhere else.
        for (int len : new int[]{16, 32, 64, 128, 512})
        {
            byte[] withMagic = new byte[len];
            rng.nextBytes(withMagic);
            for (int i = 0; i < Math.min(4, magic.length()); i++)
                withMagic[i] = (byte) magic.charAt(i);
            inputs.add(withMagic);
        }
        return inputs;
    }

    /** Runs {@code parse} over the corpus and collects any undiagnosable failures. */
    private static void assertDiagnosable(String label, String magic, Consumer<byte[]> parse)
    {
        List<String> offenders = new ArrayList<>();
        for (byte[] input : corpus(magic))
        {
            try
            {
                parse.accept(input);
            }
            catch (Throwable t)
            {
                if (isUndiagnosable(t))
                    offenders.add(String.format("  len=%-4d %s: %s",
                            input.length, t.getClass().getSimpleName(),
                            String.valueOf(t.getMessage()).replace('\n', ' ')));
            }
        }

        assertThat(offenders)
                .as("%s must reject malformed input with a diagnosable exception, but %d input(s) "
                        + "produced a raw JVM error:%n%s",
                    label, offenders.size(), String.join("\n", offenders))
                .isEmpty();
    }

    @Test
    @DisplayName("Narc rejects malformed archives diagnosably")
    void narcParsing()
    {
        assertDiagnosable("new Narc(byte[])", "NARC", Narc::new);
    }

    @Test
    @DisplayName("Palette rejects malformed NCLRs diagnosably")
    void paletteParsing()
    {
        assertDiagnosable("new Palette(byte[], 4)", "RLCN", data -> new Palette(data, 4));
        assertDiagnosable("new Palette(byte[], 8)", "RLCN", data -> new Palette(data, 8));
    }

    @Test
    @DisplayName("IndexedImage rejects malformed NCGRs diagnosably")
    void imageParsing()
    {
        assertDiagnosable("new IndexedImage(byte[], ...)", "RGCN",
                data -> new IndexedImage(data, 4, 4, 1, 1, true));
    }

    @Test
    @DisplayName("Fnt rejects malformed filename tables diagnosably")
    void fntParsing()
    {
        assertDiagnosable("Fnt.load(byte[])", "", Fnt::load);
    }

    @Test
    @DisplayName("CodeCompression rejects malformed BLZ payloads diagnosably")
    void decompressionParsing()
    {
        assertDiagnosable("CodeCompression.decompress(byte[])", "", CodeCompression::decompress);
    }

    @Test
    @DisplayName("decompression terminates on adversarial input rather than looping forever")
    void decompressionTerminates()
    {
        // A compressed-stream decoder must make progress on every iteration. Inputs crafted to
        // encode a zero-length copy are the classic way to hang one.
        Random rng = new Random(11);
        for (int trial = 0; trial < 200; trial++)
        {
            byte[] data = new byte[8 + rng.nextInt(64)];
            rng.nextBytes(data);
            // Encourage the "compressed" path: non-zero header length in the low byte of the footer.
            data[data.length - 5] = (byte) (8 + rng.nextInt(16));

            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(2),
                    () -> {
                        try { CodeCompression.decompress(data); }
                        catch (Throwable ignored) { /* rejection is fine; hanging is not */ }
                    },
                    "decompress must terminate on adversarial input");
        }
    }

    @Test
    @DisplayName("a truncated valid archive is rejected rather than silently half-parsed")
    void truncationIsDetected()
    {
        ArrayList<byte[]> files = new ArrayList<>();
        files.add(new byte[]{1, 2, 3, 4});
        files.add(new byte[]{5, 6, 7, 8});
        byte[] valid = Narc.fromContentsAndNames(files, new Fnt.Folder(),
                io.github.turtleisaac.nds4j.framework.Endianness.EndiannessType.LITTLE).save();

        // Every proper prefix of a valid archive is invalid. None may parse "successfully".
        for (int cut = 1; cut < valid.length; cut++)
        {
            byte[] truncated = java.util.Arrays.copyOf(valid, cut);
            Throwable caught = null;
            Narc parsed = null;
            try { parsed = new Narc(truncated); }
            catch (Throwable t) { caught = t; }

            if (caught != null)
            {
                assertThat(isUndiagnosable(caught))
                        .as("truncating to %d bytes produced %s: %s",
                            cut, caught.getClass().getSimpleName(), caught.getMessage())
                        .isFalse();
            }
            else
            {
                // Parsing a prefix without complaint is only acceptable if the result is not
                // presented as the original archive.
                assertThat(parsed.getFiles().size())
                        .as("a %d-byte prefix must not claim to hold all %d files", cut, files.size())
                        .isNotEqualTo(files.size());
            }
        }
    }
}
