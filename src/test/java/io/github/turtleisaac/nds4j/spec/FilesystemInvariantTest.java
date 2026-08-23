package io.github.turtleisaac.nds4j.spec;

import io.github.turtleisaac.nds4j.Fnt;
import io.github.turtleisaac.nds4j.Narc;
import io.github.turtleisaac.nds4j.framework.Endianness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Structural invariants of the NDS filename table (FNT) and of NARC archives.
 * <p>
 * An FNT is a tree of directories over a flat, contiguously-numbered array of files. Each
 * directory records the id of its first file and its files occupy consecutive ids from there.
 * That representation is only coherent if:
 * <ul>
 *   <li>the id ranges claimed by different directories are <strong>pairwise disjoint</strong>,</li>
 *   <li>together they <strong>partition</strong> the whole file array &mdash; every file belongs to
 *       exactly one directory, and no id is claimed twice or left unclaimed,</li>
 *   <li>resolving a path returns the bytes that were stored at that path.</li>
 * </ul>
 * These are properties of the format, not of any particular traversal order, so they hold
 * regardless of how the tree is walked. They are what an unpack/repack cycle must preserve.
 */
@DisplayName("Filename table and NARC structural invariants")
class FilesystemInvariantTest
{
    /**
     * Directory layouts whose names are deliberately adversarial for a traversal that
     * interleaves file-id allocation with subdirectory recursion: in several of them a
     * subdirectory sorts before a sibling file, so a depth-first allocation steals ids the
     * parent has already promised to its own files.
     */
    private static Map<String, String> layoutWithSubdirSortingFirst()
    {
        Map<String, String> tree = new TreeMap<>();
        tree.put("zzzz.bin", "ROOT-Z");        // sorts AFTER the "app" directory
        tree.put("app/s1.bin", "SUB-1");
        tree.put("app/s2.bin", "SUB-2");
        return tree;
    }

    private static Map<String, String> deepLayout()
    {
        Map<String, String> tree = new TreeMap<>();
        tree.put("a.bin", "A");
        tree.put("m.bin", "M");
        tree.put("z.bin", "Z");
        tree.put("bravo/b1.bin", "B1");
        tree.put("bravo/b2.bin", "B2");
        tree.put("bravo/nested/n1.bin", "N1");
        tree.put("yankee/y1.bin", "Y1");
        return tree;
    }

    private static void materialise(Path root, Map<String, String> tree) throws IOException
    {
        for (Map.Entry<String, String> entry : tree.entrySet())
        {
            Path file = root.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Collects (folderPath -> [firstId, firstId+fileCount)) for every directory in the tree. */
    private static void collectRanges(Fnt.Folder folder, String path, List<int[]> ranges, List<String> owners)
    {
        ranges.add(new int[]{folder.getFirstId(), folder.getFirstId() + folder.getFiles().size()});
        owners.add(path.isEmpty() ? "<root>" : path);
        for (Map.Entry<String, Fnt.Folder> child : folder.getFolders().entrySet())
            collectRanges(child.getValue(), path + child.getKey() + "/", ranges, owners);
    }

    private static void assertRangesPartition(Fnt.Folder root, int totalFiles)
    {
        List<int[]> ranges = new ArrayList<>();
        List<String> owners = new ArrayList<>();
        collectRanges(root, "", ranges, owners);

        Set<Integer> claimed = new HashSet<>();
        for (int i = 0; i < ranges.size(); i++)
        {
            for (int id = ranges.get(i)[0]; id < ranges.get(i)[1]; id++)
            {
                assertThat(claimed.add(id))
                        .as("file id %d is claimed by more than one directory (%s claims [%d,%d))",
                            id, owners.get(i), ranges.get(i)[0], ranges.get(i)[1])
                        .isTrue();
                assertThat(id)
                        .as("directory %s claims id %d, outside the %d-file array",
                            owners.get(i), id, totalFiles)
                        .isBetween(0, totalFiles - 1);
            }
        }

        assertThat(claimed)
                .as("every file must be owned by exactly one directory")
                .hasSize(totalFiles);
    }

    @Test
    @DisplayName("directory id ranges partition the file array, even when a subdirectory sorts first")
    void idRangesPartitionTheFileArray(@TempDir Path tmp) throws IOException
    {
        for (Map<String, String> layout : List.of(layoutWithSubdirSortingFirst(), deepLayout()))
        {
            Path root = Files.createTempDirectory(tmp, "fs");
            materialise(root, layout);

            ArrayList<byte[]> files = new ArrayList<>();
            Fnt.Folder parsed = Fnt.loadFromDisk(root.toFile(), files);

            assertThat(files).as("one entry per file on disk").hasSize(layout.size());
            assertRangesPartition(parsed, files.size());
        }
    }

    @Test
    @DisplayName("every path resolves to the bytes stored at that path")
    void pathsResolveToTheirOwnContent(@TempDir Path tmp) throws IOException
    {
        // The consequence users actually feel: if id ranges overlap, getFileByName silently
        // returns some other file's bytes.
        for (Map<String, String> layout : List.of(layoutWithSubdirSortingFirst(), deepLayout()))
        {
            Path root = Files.createTempDirectory(tmp, "fs");
            materialise(root, layout);

            ArrayList<byte[]> files = new ArrayList<>();
            Fnt.Folder parsed = Fnt.loadFromDisk(root.toFile(), files);
            Narc narc = Narc.fromContentsAndNames(files, parsed, Endianness.EndiannessType.LITTLE);

            for (Map.Entry<String, String> entry : layout.entrySet())
                assertThat(new String(narc.getFileByName(entry.getKey()), StandardCharsets.UTF_8))
                        .as("%s must resolve to its own content", entry.getKey())
                        .isEqualTo(entry.getValue());
        }
    }

    @Test
    @DisplayName("writing a tree to disk and reading it back preserves every path's content")
    void diskRoundTripPreservesPaths(@TempDir Path tmp) throws IOException
    {
        // This is the cycle a project folder goes through: a parsed tree is written out as real
        // directories, then re-read. Because the re-read assigns file ids by walking the
        // directory in name order, any disagreement between the write order and the read order
        // shows up here as a file resolving to a sibling's bytes.
        for (Map<String, String> layout : List.of(layoutWithSubdirSortingFirst(), deepLayout()))
        {
            Path source = Files.createTempDirectory(tmp, "src");
            materialise(source, layout);

            ArrayList<byte[]> files = new ArrayList<>();
            Fnt.Folder parsed = Fnt.loadFromDisk(source.toFile(), files);

            Path rewritten = Files.createTempDirectory(tmp, "out");
            Fnt.writeFolderToDisk(rewritten.toFile(), parsed, files);

            ArrayList<byte[]> reloadedFiles = new ArrayList<>();
            Fnt.Folder reloaded = Fnt.loadFromDisk(rewritten.toFile(), reloadedFiles);

            assertThat(reloadedFiles).as("file count survives the cycle").hasSize(files.size());
            assertRangesPartition(reloaded, reloadedFiles.size());

            Narc narc = Narc.fromContentsAndNames(reloadedFiles, reloaded, Endianness.EndiannessType.LITTLE);
            for (Map.Entry<String, String> entry : layout.entrySet())
                assertThat(new String(narc.getFileByName(entry.getKey()), StandardCharsets.UTF_8))
                        .as("%s must survive a write/read cycle with its own content", entry.getKey())
                        .isEqualTo(entry.getValue());
        }
    }

    @Test
    @DisplayName("a flat NARC survives unpack then repack")
    void flatNarcUnpackRepack(@TempDir Path tmp) throws IOException
    {
        // Narc.unpack only supports archives without an internal filename table, which is the
        // shape Pokemon data NARCs actually use. Named-filesystem NARCs are covered by the
        // disk round-trip above.
        ArrayList<byte[]> files = new ArrayList<>();
        for (int i = 0; i < 12; i++)
            files.add(("entry-" + i).getBytes(StandardCharsets.UTF_8));

        Narc original = Narc.fromContentsAndNames(files, new Fnt.Folder(), Endianness.EndiannessType.LITTLE);
        Path unpacked = tmp.resolve("flat");
        original.unpack(unpacked.toFile());
        Narc repacked = Narc.fromUnpacked(unpacked.toFile(), true, Endianness.EndiannessType.LITTLE);

        assertThat(repacked.getFiles()).as("file count survives").hasSize(files.size());
        for (int i = 0; i < files.size(); i++)
            assertThat(repacked.getFile(i))
                    .as("entry %d must keep its index and content across unpack/repack", i)
                    .isEqualTo(files.get(i));
    }

    @Test
    @DisplayName("the unsupported named-filesystem unpack fails loudly rather than silently")
    void namedFilesystemUnpackIsRejected(@TempDir Path tmp) throws IOException
    {
        // A limitation is only safe if it is announced. Producing a wrong archive here would be
        // far worse than refusing.
        Path source = Files.createTempDirectory(tmp, "named");
        materialise(source, deepLayout());
        ArrayList<byte[]> files = new ArrayList<>();
        Fnt.Folder parsed = Fnt.loadFromDisk(source.toFile(), files);
        Narc named = Narc.fromContentsAndNames(files, parsed, Endianness.EndiannessType.LITTLE);

        assertThatCode(() -> named.unpack(tmp.resolve("nope").toFile()))
                .as("an unsupported operation must throw, not emit a corrupt archive")
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("an empty directory is representable and does not consume a file id")
    void emptyDirectoriesAreLegal(@TempDir Path tmp) throws IOException
    {
        // Empty directories exist in real ROMs and a user can create one. Requesting a firstId
        // for a folder that owns no files must not exhaust the id pool.
        Path root = Files.createTempDirectory(tmp, "fs");
        materialise(root, Map.of("a.bin", "A"));
        Files.createDirectories(root.resolve("zdir"));   // sorts after the only file

        ArrayList<byte[]> files = new ArrayList<>();
        Fnt.Folder parsed = assertThatNoThrow(() -> Fnt.loadFromDisk(root.toFile(), files));

        assertThat(files).hasSize(1);
        Fnt.Folder empty = parsed.getSubfolder("zdir");
        assertThat(empty).as("the empty directory must survive parsing").isNotNull();
        assertThat(empty.getFiles()).isEmpty();
        assertRangesPartition(parsed, files.size());
    }

    private static <T> T assertThatNoThrow(java.util.concurrent.Callable<T> call)
    {
        try { return call.call(); }
        catch (Exception e) { throw new AssertionError("must not throw", e); }
    }

    @Test
    @DisplayName("lookups of absent or degenerate paths return the documented not-found value")
    void notFoundContract()
    {
        // getIdOf documents -1 for "not present". Throwing instead means callers cannot
        // distinguish absent from malformed.
        Fnt.Folder root = new Fnt.Folder();
        for (String path : new String[]{"", "/", "nope.bin", "no/such/path.bin"})
        {
            assertThat(root.getIdOf(path))
                    .as("getIdOf(\"%s\") on an empty tree", path)
                    .isEqualTo(-1);
            assertThatCode(() -> root.getSubfolder(path))
                    .as("getSubfolder(\"%s\") must not throw", path)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("a NARC survives save then load unchanged")
    void narcSerialisationRoundTrip()
    {
        ArrayList<byte[]> files = new ArrayList<>();
        files.add("first".getBytes(StandardCharsets.UTF_8));
        files.add(new byte[0]);                                   // zero-length entries are legal
        files.add("third-is-longer".getBytes(StandardCharsets.UTF_8));
        files.add(new byte[]{0, 1, 2, 3});                        // already 4-aligned

        Narc original = Narc.fromContentsAndNames(files, new Fnt.Folder(), Endianness.EndiannessType.LITTLE);
        Narc reloaded = new Narc(original.save());

        assertThat(reloaded.getFiles()).as("file count survives").hasSize(files.size());
        for (int i = 0; i < files.size(); i++)
            assertThat(reloaded.getFile(i)).as("file %d survives save/load", i).isEqualTo(files.get(i));
    }

    @Test
    @DisplayName("saving a NARC is a fixed point: re-saving reproduces identical bytes")
    void narcSaveIsStable()
    {
        // Alignment padding that is emitted on save but not accounted for on load makes the
        // archive grow on every cycle. A fixed point rules that out.
        ArrayList<byte[]> files = new ArrayList<>();
        files.add(new byte[]{1, 2, 3, 4});     // exactly aligned -- the case that grew
        files.add(new byte[]{5, 6, 7, 8});
        files.add(new byte[]{9});

        Narc narc = Narc.fromContentsAndNames(files, new Fnt.Folder(), Endianness.EndiannessType.LITTLE);
        byte[] once = narc.save();
        byte[] twice = new Narc(once).save();

        assertThat(twice)
                .as("save(load(save(x))) must equal save(x)")
                .isEqualTo(once);
    }

    @Test
    @DisplayName("filenames are optional, as documented")
    void filenamesAreOptional()
    {
        ArrayList<byte[]> files = new ArrayList<>();
        files.add(new byte[]{1, 2, 3, 4});

        assertThatCode(() -> Narc.fromContentsAndNames(files, null, Endianness.EndiannessType.LITTLE).save())
                .as("a null filename table is documented as legal")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an archive with no files at all is representable")
    void emptyArchive()
    {
        assertThatCode(() ->
                new Narc(Narc.fromContentsAndNames(new ArrayList<>(), new Fnt.Folder(),
                        Endianness.EndiannessType.LITTLE).save()))
                .as("the degenerate zero-file archive must round-trip")
                .doesNotThrowAnyException();
    }
}
