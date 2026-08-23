package io.github.turtleisaac.nds4j;

import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Locates the retail ROM that some tests need. Retail ROMs are copyrighted and are deliberately not
 * committed to this repository, so tests that require one are <em>skipped</em> rather than failed
 * when it is absent.
 * <p>
 * Point the suite at a ROM with {@code -Drom.dir=/path/to/roms}, and optionally override the file
 * name with {@code -Drom.name=MyRom.nds}.
 */
public final class TestRoms
{
    private TestRoms() {}

    public static Path romPath(String defaultName)
    {
        return Paths.get(System.getProperty("rom.dir", "."),
                System.getProperty("rom.name", defaultName));
    }

    /**
     * @param defaultName the file name to look for when {@code -Drom.name} is not set
     * @return the loaded rom
     */
    public static NintendoDsRom require(String defaultName)
    {
        Path path = romPath(defaultName);
        Assumptions.assumeTrue(Files.exists(path),
                () -> "Skipping: test ROM not found at " + path.toAbsolutePath()
                        + " -- set -Drom.dir=<dir> (and optionally -Drom.name=<file>) to run this suite.");
        return NintendoDsRom.fromFile(path.toString());
    }
}
