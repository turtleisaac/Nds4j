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

import io.github.turtleisaac.nds4j.framework.Endianness;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class NarcTest
{
    private final byte[] b1 = {0, 0, 0, 0};
    private final byte[] b2 = {1, 1, 1, 1};
    private final byte[] b3 = {0, 0, 0, 0};
    private final ArrayList<byte[]> testList = new ArrayList<>(Arrays.stream(new byte[][]{b1, b2, b3}).collect(Collectors.toList()));
    private final Narc narc = Narc.fromContentsAndNames(testList, new Fnt.Folder(), Endianness.BIG);

    @Test
    void narcNotNull() {
        assertThat(narc)
                .isNotNull();
    }

    @Test
    void fromContentsAndNamesProducesSame() {
        assertThat(narc)
                .isEqualTo(Narc.fromContentsAndNames(testList, new Fnt.Folder(), Endianness.BIG));
    }

    @Test
    void fromContentsAndNamesProducesDifferent() {
        assertThat(narc)
                .isNotEqualTo(Narc.fromContentsAndNames(new ArrayList<>(), new Fnt.Folder(), Endianness.BIG));
    }

    @Test
    void fileContentsModificationChangesEquality() {
        Narc narc2 = Narc.fromContentsAndNames(testList, new Fnt.Folder(), Endianness.BIG);
        narc.files.set(0, new byte[] {0});
        assertThat(narc)
                .isNotEqualTo(narc2);
    }

    @Test
    void saveDoesNotAffectEquality() {
        Narc narc2 = Narc.fromContentsAndNames(testList, new Fnt.Folder(), Endianness.BIG);
        narc.save();
        assertThat(narc)
                .isEqualTo(narc2);
    }

    @Test
    void differentNarcsDoNotSaveIdentically() {
        Narc narc2 = Narc.fromContentsAndNames(testList, new Fnt.Folder(), Endianness.BIG);
        narc2.files.remove(0);
        assertThat(narc.save())
                .isNotEqualTo(narc2.save());
    }
}
