/*
 * Copyright (c) 2023 Turtleisaac.
 *
 * This file is part of Nds4j.
 *
 * Nds4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Nds4j is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Nds4j. If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.turtleisaac.nds4j;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests DSi-enhanced (TWL) ROM support in {@link NintendoDsRom}: the extended header is parsed, the TWL data
 * region (ARM9i/ARM7i binaries and digest hash tables) is exposed and preserved on rebuild, and a clean DSi
 * ROM re-serialises byte-for-byte. The retail cases need {@code -Drom.dir=<dir>} (skipped otherwise) and use a
 * full (untrimmed) copy of Pokémon White 2 ({@code White2.nds}); the synthetic ones build their own TWL region.
 */
@DisplayName("DSi-enhanced ROM support")
public class DsiRomTest
{
    private static byte[] readRom(String name)
    {
        Path p = TestRoms.romPath(name);
        Assumptions.assumeTrue(Files.exists(p), () -> "Skipping: " + p.toAbsolutePath() + " not found");
        try
        {
            return Files.readAllBytes(p);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("a DSi-enhanced ROM parses its TWL section and re-serialises byte-for-byte")
    void whiteTwoDsiRoundTrip()
    {
        // loading a 294 MB ROM and rebuilding it needs a good chunk of heap
        Assumptions.assumeTrue(Runtime.getRuntime().maxMemory() > 1_500_000_000L,
                "needs >1.5 GB heap; run with -Xmx2g");
        byte[] original = readRom("White2.nds");

        NintendoDsRom rom = new NintendoDsRom(original);
        assertThat(rom.isDsiEnhanced()).as("White2 is DSi-enhanced").isTrue();
        assertThat(rom.getUnitCode()).isEqualTo(2); // NDS+DSi hybrid
        assertThat(rom.getArm9i().getCode()).as("ARM9i present").isNotNull();
        assertThat(rom.getArm7i().getCode()).as("ARM7i present").isNotNull();
        assertThat(rom.getArm9i().getCode().length).isEqualTo(0x21C38);
        assertThat(rom.getArm7i().getCode().length).isEqualTo(0x470F8);
        assertThat(rom.getTwlTitleId()).isEqualTo(0x000300004952444FL);
        assertThat(rom.getBanner().getVersion()).isEqualTo(0x0103); // DSi animated icon banner

        // the whole ROM — NTR content, extended header and the TWL data region — rebuilds byte-for-byte
        assertThat(rom.save(false)).as("DSi ROM re-serialises byte-for-byte").isEqualTo(original);

        // and the ARM9i/ARM7i survive a save + reload unchanged
        NintendoDsRom reloaded = new NintendoDsRom(rom.save(false));
        assertThat(reloaded.isDsiEnhanced()).isTrue();
        assertThat(reloaded.getArm9i().getCode()).isEqualTo(rom.getArm9i().getCode());
        assertThat(reloaded.getArm7i().getCode()).isEqualTo(rom.getArm7i().getCode());
    }

    @Test
    @DisplayName("a plain NDS ROM re-serialises byte-for-byte (no DSi section)")
    void plainNdsRoundTrip()
    {
        byte[] original = readRom("Platinum.nds");
        NintendoDsRom rom = new NintendoDsRom(original);
        assertThat(rom.isDsiEnhanced()).isFalse();
        assertThat(rom.getUnitCode()).isEqualTo(0);
        assertThat(rom.getArm9i().getCode()).isNull();
        assertThat(rom.save(false)).as("NDS ROM re-serialises byte-for-byte").isEqualTo(original);
    }

    @Test
    @DisplayName("a synthetic TWL region exposes its ARM9i/ARM7i and re-serialises byte-for-byte")
    void syntheticTwlRegionRoundTrip()
    {
        byte[] original = twlRom().build();

        NintendoDsRom rom = new NintendoDsRom(original);
        NintendoDsRom.TwlBinary arm9i = rom.getArm9i();
        assertThat(rom.isDsiEnhanced())
                .isTrue();
        assertThat(arm9i.getCode())
                .isEqualTo(SyntheticRom.arm9i());
        assertThat(rom.getArm7i().getCode())
                .isEqualTo(SyntheticRom.arm7i());
        assertThat(arm9i.getRomOffset() % 0x400)
                .isZero();
        assertThat(rom.getArm7i().getRomOffset())
                .isEqualTo(arm9i.getRomOffset() + SyntheticRom.ARM9I_SIZE);
        assertThat(arm9i.getLoadAddress())
                .isEqualTo(SyntheticRom.ARM9I_LOAD)
                .isEqualTo(rom.getArm9iLoadAddress());
        assertThat(arm9i.getEntryAddress())
                .isEqualTo(SyntheticRom.ARM9I_LOAD + 0x800);
        assertThat(rom.getArm7i().getSize())
                .isEqualTo(SyntheticRom.ARM7I_SIZE);
        assertThat(rom.save(true))
                .isEqualTo(original);
    }

    @Test
    @DisplayName("the TWL code getter hands out a copy")
    void twlCodeIsACopy()
    {
        NintendoDsRom rom = new NintendoDsRom(twlRom().build());
        rom.getArm9i().getCode()[0]++;

        assertThat(rom.getArm9i().getCode())
                .isEqualTo(SyntheticRom.arm9i());
    }

    @Test
    @DisplayName("a DSi ROM cut off inside its TWL region has no ARM9i/ARM7i code")
    void truncatedTwlRegionHasNoCode()
    {
        byte[] original = twlRom().build();
        int arm9iOffset = new NintendoDsRom(original).getArm9i().getRomOffset();

        NintendoDsRom trimmed = new NintendoDsRom(Arrays.copyOf(original, arm9iOffset));
        NintendoDsRom cut = new NintendoDsRom(Arrays.copyOf(original, arm9iOffset + 0x80));
        assertThat(trimmed.isDsiEnhanced())
                .isTrue();
        assertThat(trimmed.getArm9i().getCode())
                .isNull();
        assertThat(cut.getArm9i().getCode())
                .isNull();
        assertThat(cut.getArm7i().getCode())
                .isNull();
    }

    @Test
    @DisplayName("growing the NTR content past the TWL region is refused")
    void ntrGrowthIntoTheTwlRegionIsRejected()
    {
        NintendoDsRom rom = new NintendoDsRom(twlRom().build());
        rom.setFile(3, new byte[0x2000]);

        assertThatThrownBy(() -> rom.save(true))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("overruns the DSi region start");
    }

    private static SyntheticRom twlRom()
    {
        SyntheticRom rom = SyntheticRom.plain();
        rom.dsi = true;
        rom.multiboot = false;
        rom.twlRegion = true;

        return rom;
    }
}
