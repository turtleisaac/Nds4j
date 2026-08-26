package io.github.turtleisaac.nds4j.spec;

import io.github.turtleisaac.nds4j.NintendoDsRom;
import io.github.turtleisaac.nds4j.TestRoms;
import io.github.turtleisaac.nds4j.framework.CodeCompression;
import io.github.turtleisaac.nds4j.framework.StringFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Properties that three separate audit fixes each violated, none of which the existing suite
 * could see.
 * <p>
 * They are grouped because they share a cause rather than a subject: each was a change that
 * was correct about the thing it set out to fix and wrong about something the fix touched on
 * its way past. That is the failure mode a suite has to be built against once the obvious
 * defects are gone.
 */
class RegressionGuardTest
{
    @Nested
    @DisplayName("unpacked filenames")
    class Filenames
    {
        private final Locale original = Locale.getDefault();

        @AfterEach
        void restoreLocale()
        {
            Locale.setDefault(original);
        }

        /**
         * Locales whose default numbering system is not Latin digits. Under these, {@code %d}
         * formats with Arabic-Indic or Thai numerals.
         */
        private final String[] nonLatinDigitLocales =
                {"ar-EG", "ar-SA", "fa-IR", "bn-IN", "ne-NP", "my-MM", "th-TH-u-nu-thai"};

        @Test
        @DisplayName("a filename is the same on every machine, whatever its locale")
        void filenamesDoNotDependOnTheDefaultLocale()
        {
            // These strings become paths on disk: they are written by one machine, listed back
            // by another, and parsed with Integer.parseInt to recover the file ID. A name that
            // depends on the unpacking machine's locale means a project unpacked on one system
            // cannot be repacked on another - and the digits are not even representable in some
            // filesystem encodings, so the unpack itself fails.
            String expected = StringFormatter.formatOutputString(7, 200, "overlay_", ".bin");
            assertThat(expected).as("the reference name, under the test JVM's own locale")
                    .isEqualTo("overlay_0007.bin");

            for (String tag : nonLatinDigitLocales)
            {
                Locale.setDefault(Locale.forLanguageTag(tag));
                assertThat(StringFormatter.formatOutputString(7, 200, "overlay_", ".bin"))
                        .as("under locale %s", tag)
                        .isEqualTo(expected);
            }
        }

        @Test
        @DisplayName("every digit produced is an ASCII digit")
        void digitsAreAlwaysAscii()
        {
            for (String tag : nonLatinDigitLocales)
            {
                Locale.setDefault(Locale.forLanguageTag(tag));
                String name = StringFormatter.formatOutputString(0, 1000, "", "");
                assertThat(name).as("under locale %s", tag).matches("[0-9]+");
            }
        }

        /**
         * The widths an already-unpacked project on disk was named with. Derived from the rule
         * the original implementation followed, not from running the current one: this is the
         * compatibility fact the whole change turned on, and nothing pinned it.
         */
        @Test
        @DisplayName("the widths existing unpacked projects were named with are unchanged")
        void historicalWidthsArePreserved()
        {
            // one digit wider than the digit count of the total
            assertThat(StringFormatter.formatOutputString(7, 9, "", "")).isEqualTo("07");
            assertThat(StringFormatter.formatOutputString(7, 10, "", "")).isEqualTo("007");
            assertThat(StringFormatter.formatOutputString(7, 99, "", "")).isEqualTo("007");
            assertThat(StringFormatter.formatOutputString(7, 100, "", "")).isEqualTo("0007");
            assertThat(StringFormatter.formatOutputString(7, 999, "", "")).isEqualTo("0007");
            assertThat(StringFormatter.formatOutputString(7, 1000, "", "")).isEqualTo("00007");
            assertThat(StringFormatter.formatOutputString(7, 9999, "", "")).isEqualTo("00007");

            // and the boundary either side of every decade, since that is where a width rule
            // goes wrong if it goes wrong at all. From 10 upwards: a count of 0 and a count of 1
            // both describe a single-entry archive, so they share a width and there is no step
            // between them.
            for (int decade = 10; decade <= 10000; decade *= 10)
            {
                int lower = StringFormatter.formatOutputString(0, decade - 1, "", "").length();
                int upper = StringFormatter.formatOutputString(0, decade, "", "").length();
                assertThat(upper).as("width at %d must be one more than at %d", decade, decade - 1)
                        .isEqualTo(lower + 1);
            }
        }

        @Test
        @DisplayName("names sort lexicographically in numeric order")
        void namesSortInNumericOrder()
        {
            // the reason the padding exists at all: an unpacked project is listed back off the
            // filesystem, and if "10" sorts before "9" the file IDs are scrambled on repack
            int count = 250;
            String previous = null;
            for (int i = 0; i < count; i++)
            {
                String name = StringFormatter.formatOutputString(i, count, "f", ".bin");
                if (previous != null)
                    assertThat(name).as("entry %d must sort after entry %d", i, i - 1)
                            .isGreaterThan(previous);
                previous = name;
            }
        }
    }

    @Nested
    @DisplayName("code binary decompression")
    class Decompression
    {
        /**
         * Builds the shape {@code decompress} has to cope with: BLZ-compressed data followed by
         * an appended tail, which is what an arm9 binary with a footer looks like. Only the
         * header arithmetic matters here - the test asserts that the appended-data path is
         * entered and survives, not that any particular bytes come back out.
         */
        /**
         * Builds the exact shape {@code decompress} has to cope with: a BLZ footer sitting four
         * bytes before the end, i.e. an arm9 binary with an appended tail.
         * <p>
         * The layout is dictated by {@code detectAppendedData}, which walks backwards in
         * four-byte steps looking for a footer whose top byte is a header length of at least 8
         * and whose low 24 bits are a compressed length that fits. Placing a valid footer at
         * {@code length - 12} and leaving nothing valid at {@code length - 8} is what makes it
         * report four appended bytes rather than zero - and only a non-zero amount reaches the
         * truncation this test exists for.
         */
        private byte[] withAppendedTail()
        {
            int length = 32;
            byte[] data = new byte[length];

            // footer for an appended amount of 4: composite at length-12, extraSize at length-8
            int headerLength = 8;
            int compressedLength = 16;
            int composite = (headerLength << 24) | compressedLength;
            writeIntLe(data, length - 12, composite);

            // extraSize 0 says "not actually compressed", so decompress returns straight after
            // the truncation. That is deliberate: this test is about reaching the truncation at
            // all, not about the decoder, and a real BLZ stream would assert two things at once.
            writeIntLe(data, length - 8, 0);

            return data;
        }

        private void writeIntLe(byte[] data, int offset, int value)
        {
            data[offset] = (byte) value;
            data[offset + 1] = (byte) (value >>> 8);
            data[offset + 2] = (byte) (value >>> 16);
            data[offset + 3] = (byte) (value >>> 24);
        }

        @Test
        @DisplayName("the fixture really does reach the appended-data branch")
        void theFixtureIsNotVacuous()
        {
            // Without this the test below proves nothing: if the fixture were rejected as "not
            // compressed", decompress would return at its first line and the truncation would
            // never run, yet the assertion would still pass. That is how the fuzz suite in this
            // package ended up exercising nothing at all.
            //
            // The branch is selected by detectAppendedData, which is private, so it is called
            // directly rather than inferred. Asserting on decompress's return value cannot
            // distinguish the two paths here: with extraSize 0 it hands back the original array
            // either way, and the only outward difference between the fixed and broken versions
            // is whether the truncation throws.
            int amount;
            try {
                java.lang.reflect.Method detect = CodeCompression.class
                        .getDeclaredMethod("detectAppendedData", byte[].class);
                detect.setAccessible(true);
                amount = (int) detect.invoke(null, (Object) withAppendedTail());
            }
            catch (ReflectiveOperationException e) {
                throw new AssertionError("detectAppendedData could not be called", e);
            }

            assertThat(amount)
                    .as("the fixture must report appended data, and a non-zero amount of it - "
                            + "zero takes the other branch and skips the truncation entirely")
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("data carrying an appended tail decompresses instead of throwing")
        void appendedDataDoesNotAbortDecompression()
        {
            // The tail is excluded by moving the buffer's write cursor back, because that cursor
            // doubles as the reader's bound. Routing that through skip() with a negative count
            // made every arm9 with a footer throw before any decoding began - and the guard that
            // caused it was added two commits after the fix it broke, so the suite never saw the
            // two together.
            assertThatCode(() -> CodeCompression.decompress(withAppendedTail()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("data with no appended tail is returned unchanged")
        void uncompressedDataIsReturnedUnchanged()
        {
            // the early-out: anything that does not look compressed comes back as-is, by identity
            byte[] plain = "this is not compressed at all, not even slightly".getBytes();
            assertThat(CodeCompression.decompress(plain)).isSameAs(plain);
        }
    }

    /**
     * These need a retail ROM and skip without one, like the rest of the ROM-dependent suite.
     * <p>
     * That is a real gap, not a preference: the header is the highest-stakes surface in the
     * library and CI cannot see it. Building a synthetic ROM was the alternative, and it was
     * rejected because a fixture assembled from the same understanding as the code under test
     * asserts that the two agree, which is the one thing already known. Run with -Drom.dir.
     */
    @Nested
    @DisplayName("code binary compression flag")
    class CompressionFlag
    {
        /**
         * Asks the binary directly. This used to read the private field by reflection, because
         * nothing in the library read it at all - which is how it carried an inverted value
         * undetected. It is public API now, so the test exercises what a caller would.
         */
        private boolean compressedFlagOf(byte[] data)
        {
            return new io.github.turtleisaac.nds4j.binaries.CodeBinary(data, 0, 0) {}.isCompressed();
        }

        @Test
        @DisplayName("is false for a binary that was never compressed")
        void uncompressedBinaryIsNotFlagged()
        {
            // The property, stated plainly: if decompressing changed nothing, the input was not
            // compressed. The original expressed exactly this test and then assigned its
            // negation, so the flag was true for every uncompressed binary and false for every
            // compressed one.
            byte[] plain = "an arm9 binary with nothing compressed about it".getBytes();
            assertThat(compressedFlagOf(plain))
                    .as("decompression changed nothing, so this binary was not compressed")
                    .isFalse();
        }

        @Test
        @DisplayName("does not depend on decompress returning the same array")
        void flagIsAboutContentNotIdentity()
        {
            // A copy of the same bytes must give the same answer as the bytes themselves. This
            // is what reference comparison cannot promise: it happens to work only because
            // decompress returns its argument unchanged on the early-out path, which is an
            // implementation detail of another class and not part of any contract.
            byte[] plain = "an arm9 binary with nothing compressed about it".getBytes();
            assertThat(compressedFlagOf(plain.clone()))
                    .as("the answer must come from the contents, not from which object holds them")
                    .isEqualTo(compressedFlagOf(plain));
        }
    }

    @Nested
    @DisplayName("ROM header (needs a retail ROM)")
    class Header
    {
        /**
         * An independently written CRC-16/MODBUS, so the assertion below does not read the
         * answer out of the class it is checking. Poly 0xA001 reflected, init 0xFFFF - the
         * algorithm GBATEK specifies for the DS header.
         */
        private int modbus(byte[] data, int from, int to)
        {
            int crc = 0xFFFF;
            for (int i = from; i < to; i++)
            {
                crc ^= (data[i] & 0xFF);
                for (int bit = 0; bit < 8; bit++)
                    crc = ((crc & 1) != 0) ? (crc >>> 1) ^ 0xA001 : (crc >>> 1);
            }
            return crc & 0xFFFF;
        }

        private int u16At(byte[] data, int offset)
        {
            return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
        }

        @Test
        @DisplayName("the header CRC covers bytes 0 to 0x15E and is written little-endian at 0x15E")
        void headerCrcIsCorrectAndInTheRightPlace()
        {
            // GBATEK fixes both the range and the location. Checking the value against a
            // reference written here, rather than against the library's own CRC16, is what makes
            // this a test of the header rather than a test that the class agrees with itself.
            byte[] saved = TestRoms.require("HeartGold.nds").save(false);

            assertThat(saved.length).as("a header is 0x200 bytes at minimum").isGreaterThanOrEqualTo(0x200);
            assertThat(u16At(saved, 0x15E))
                    .as("the header CRC at 0x15E")
                    .isEqualTo(modbus(saved, 0, 0x15E));
        }

        @Test
        @DisplayName("the Nintendo logo CRC at 0x15C is the constant GBATEK documents")
        void logoCrcIsTheDocumentedConstant()
        {
            // 0xCF56 is a fixed value: the logo bytes are the same in every retail cartridge, so
            // a ROM whose logo CRC is anything else has a corrupted logo block
            byte[] saved = TestRoms.require("HeartGold.nds").save(false);
            assertThat(u16At(saved, 0x15C)).as("the logo CRC at 0x15C").isEqualTo(0xCF56);
        }

        @Test
        @DisplayName("saving, loading and saving again produces identical bytes")
        void saveIsAFixedPoint()
        {
            // The property that matters for a ROM editor: opening a file and closing it without
            // editing must not change it. A CRC recomputed over the wrong range, a field written
            // at a different width, or a section whose length is derived from the wrong place all
            // show up here and nowhere else - and this surface had no test at all.
            byte[] first = TestRoms.require("HeartGold.nds").save(false);

            java.nio.file.Path tmp;
            try {
                tmp = java.nio.file.Files.createTempFile("nds4j-fixedpoint", ".nds");
                java.nio.file.Files.write(tmp, first);
            }
            catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            byte[] second = NintendoDsRom.fromFile(tmp.toString()).save(false);

            assertThat(second).as("save(load(save(x))) must equal save(x)").isEqualTo(first);
        }
    }
}
