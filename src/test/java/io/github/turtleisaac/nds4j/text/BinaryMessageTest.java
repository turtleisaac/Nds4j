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

package io.github.turtleisaac.nds4j.text;

import io.github.turtleisaac.nds4j.NintendoDsRom;
import io.github.turtleisaac.nds4j.TestRoms;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests {@link BinaryMessage} (BMG). The whole-ROM round-trip tests are the real correctness bar (byte-
 * exact save() over every real file found), same as the rest of this project's format classes; the
 * from-scratch authoring test additionally confirms the decode isn't just accidentally byte-matching --
 * see also the ad hoc semantic check (real, readable Phantom Hourglass tutorial dialogue with correctly
 * parsed escape sequences) that motivated shipping this class in the first place.
 */
@DisplayName("BMG (BinaryMessage)")
class BinaryMessageTest
{
    @Test
    @DisplayName("save() reproduces every BMG in Phantom Hourglass byte-for-byte (incl. FLW1/FLI1)")
    void writtenBmgRoundTripsByteExactAcrossPhantomHourglass()
    {
        // Found two real bugs this way: an offset-computation error (this class's DAT1 buffer excludes
        // the 8-byte section header unlike the reference algorithm's, so porting its "len(DAT1) - 8"
        // literally zeroed out every first message's offset, colliding with the "no text" sentinel), and
        // the outer header's declared total-file-size field being decorative for any file carrying
        // FLW1/FLI1 -- it only ever covers header+INF1+DAT1 in real files, apparently a back-compat
        // leftover from before those sections existed. See BinaryMessage.declaredTotalLength's doc.
        assertRoundTripsAcrossRom("Legend of Zelda, The - Phantom Hourglass.nds");
    }

    @Test
    @DisplayName("save() reproduces every BMG in New Super Mario Bros / Mario Kart DS / Animal Crossing byte-for-byte")
    void writtenBmgRoundTripsByteExactAcrossOtherRoms()
    {
        for (String romName : new String[] {"New Super Mario Bros.nds", "Mario Kart DS.nds",
                "Animal Crossing - Wild World.nds"})
            assertRoundTripsAcrossRom(romName);
    }

    private void assertRoundTripsAcrossRom(String romName)
    {
        NintendoDsRom rom = TestRoms.require(romName);
        List<byte[]> files = BmgFixtures.collect(rom);
        Assumptions.assumeFalse(files.isEmpty(), "no BMG files found in " + romName);
        for (int i = 0; i < files.size(); i++)
        {
            byte[] original = files.get(i);
            byte[] written = new BinaryMessage(original).save();
            assertThat(written).as("%s BMG file #%d must round-trip byte-for-byte", romName, i).isEqualTo(original);
        }
    }

    @Test
    @DisplayName("a real message's text decodes as escape sequences interleaved with readable strings")
    void decodesRealMessageContent()
    {
        NintendoDsRom rom = TestRoms.require("Legend of Zelda, The - Phantom Hourglass.nds");
        List<byte[]> files = BmgFixtures.collect(rom);
        Assumptions.assumeFalse(files.isEmpty(), "no BMG files found");

        boolean sawText = false, sawEscape = false;
        for (byte[] file : files)
        {
            BinaryMessage bmg = new BinaryMessage(file);
            for (BinaryMessage.Message m : bmg.getMessages())
            {
                if (m.isNull()) continue;
                for (Object part : m.getParts())
                {
                    if (part instanceof String && !((String) part).isEmpty()) sawText = true;
                    if (part instanceof BinaryMessage.Message.Escape) sawEscape = true;
                }
            }
        }
        assertThat(sawText).as("at least one message has real text").isTrue();
        assertThat(sawEscape).as("at least one message has an escape sequence").isTrue();
    }

    @Test
    @DisplayName("a from-scratch BMG (text, an escape sequence, and a null message) round-trips through save/parse")
    void authorFromScratch()
    {
        BinaryMessage bmg = new BinaryMessage();
        BinaryMessage.Message greeting = new BinaryMessage.Message(new byte[]{1, 2},
                Arrays.asList("Hello, ", new BinaryMessage.Message.Escape(3, new byte[]{0x12, 0x34}), "!"), false);
        // A single space, not an empty string: an empty-string part carries zero encoded bytes, so it
        // can't be told apart from "zero parts" once re-parsed (both round-trip to the same file bytes,
        // but the in-memory parts list shape isn't preserved for that specific degenerate case).
        BinaryMessage.Message minimal = new BinaryMessage.Message(new byte[]{0, 0}, Arrays.asList(" "), false);
        BinaryMessage.Message nothing = new BinaryMessage.Message(new byte[]{9, 9}, Arrays.asList(), true);
        bmg.getMessages().add(greeting);
        bmg.getMessages().add(minimal);
        bmg.getMessages().add(nothing);

        byte[] saved = bmg.save();
        BinaryMessage reread = new BinaryMessage(saved);

        assertThat(reread.getMessages()).hasSize(3);
        assertThat(reread.getMessages().get(0)).isEqualTo(greeting);
        assertThat(reread.getMessages().get(0).toString()).isEqualTo("Hello, [3:1234]!");
        assertThat(reread.getMessages().get(1)).isEqualTo(minimal);
        assertThat(reread.getMessages().get(1).isNull()).isFalse();
        assertThat(reread.getMessages().get(2).isNull()).isTrue();

        // and it round-trips a second time (save() of the re-parsed object matches too)
        assertThat(reread.save()).isEqualTo(saved);
    }

    @Test
    @DisplayName("rejects non-BMG input")
    void rejectsWrongMagic()
    {
        assertThatThrownBy(() -> new BinaryMessage(new byte[32]))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("an empty from-scratch BMG still saves and re-parses cleanly")
    void emptyBmgRoundTrips()
    {
        BinaryMessage bmg = new BinaryMessage();
        assertThatCode(() -> new BinaryMessage(bmg.save())).doesNotThrowAnyException();
        assertThat(new BinaryMessage(bmg.save()).getMessages()).isEmpty();
    }
}
