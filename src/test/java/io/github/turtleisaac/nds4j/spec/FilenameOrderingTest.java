package io.github.turtleisaac.nds4j.spec;

import io.github.turtleisaac.nds4j.framework.StringFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The property that gives {@link StringFormatter} its reason to exist.
 * <p>
 * Unpacked NARC subfiles are named by index, and the repack path
 * ({@code Fnt.loadFolderFromDisk}) re-assigns file IDs by walking the directory in
 * <em>lexicographic</em> order. The archive therefore round-trips correctly if and only if
 * lexicographic ordering of the generated names agrees with numeric ordering of the indices
 * they encode.
 * <p>
 * That is the invariant asserted here. It is a statement about the function's purpose, so it
 * holds no matter which padding widths are chosen &mdash; and it is violated by any scheme that
 * stops padding above some threshold, which is what let a 10,000-entry archive scramble its
 * file IDs on repack.
 */
@DisplayName("Generated subfile names sort lexicographically in numeric order")
class FilenameOrderingTest
{
    private static List<String> namesFor(int count, String prefix, String suffix)
    {
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            names.add(StringFormatter.formatOutputString(i, count, prefix, suffix));
        return names;
    }

    @Test
    @DisplayName("lexicographic order equals numeric order at every archive size")
    void lexicographicOrderMatchesNumericOrder()
    {
        // Sizes chosen to straddle every decimal-digit boundary, because that is where a
        // width-selection scheme can change its mind.
        int[] counts = {1, 2, 9, 10, 11, 99, 100, 101, 999, 1000, 1001,
                        9999, 10000, 10001, 65535, 65536};

        for (int count : counts)
        {
            List<String> generated = namesFor(count, "", "");
            List<String> sorted = new ArrayList<>(generated);
            sorted.sort(Comparator.naturalOrder());

            assertThat(sorted)
                    .as("with %d entries, sorting the names by string order must reproduce index order", count)
                    .containsExactlyElementsOf(generated);
        }
    }

    @Test
    @DisplayName("all names for a given archive share one width, so ordering cannot depend on length")
    void uniformWidth()
    {
        for (int count : new int[]{1, 10, 100, 1000, 10000, 65536})
        {
            List<String> generated = namesFor(count, "", "");
            assertThat(generated.stream().map(String::length).distinct())
                    .as("every name in a %d-entry archive must be the same length", count)
                    .hasSize(1);
        }
    }

    @Test
    @DisplayName("names are unique, so no two subfiles can collide on disk")
    void namesAreUnique()
    {
        for (int count : new int[]{10, 1000, 10000})
        {
            assertThat(namesFor(count, "", "")).doesNotHaveDuplicates();
        }
    }

    @Test
    @DisplayName("the encoded index survives a parse back")
    void namesDecodeToTheirIndex()
    {
        for (int count : new int[]{5, 100, 10000})
        {
            List<String> generated = namesFor(count, "", "");
            for (int i = 0; i < count; i++)
                assertThat(Integer.parseInt(generated.get(i)))
                        .as("name %s encodes index %d", generated.get(i), i)
                        .isEqualTo(i);
        }
    }

    @Test
    @DisplayName("prefix and suffix are applied around the padded index, not inside it")
    void affixesWrapTheNumber()
    {
        String name = StringFormatter.formatOutputString(7, 200, "overlay_", ".bin");
        assertThat(name).startsWith("overlay_").endsWith(".bin");
        String middle = name.substring("overlay_".length(), name.length() - ".bin".length());
        assertThat(Integer.parseInt(middle)).isEqualTo(7);
        // Affixes are constant, so ordering is still decided by the padded number.
        assertThat(namesFor(200, "overlay_", ".bin"))
                .isSortedAccordingTo(Comparator.naturalOrder());
    }
}
