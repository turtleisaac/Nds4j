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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.turtleisaac.nds4j.Fnt;

import java.util.ArrayList;
import java.util.HashMap;

public class FolderTest
{
    private final Fnt.Folder test = new Fnt.Folder(
            null,
            new ArrayList<String>() {
                {
                    add("leg");
                }
            },
            3
    );



    private final Fnt.Folder folder = new Fnt.Folder(
            new HashMap<String, Fnt.Folder>() {
                {
                    put("a", new Fnt.Folder(
                            new HashMap<String, Fnt.Folder>() {
                                {
                                    put("test", test);
                                }
                            },
                            new ArrayList<String>() {
                                {
                                    add("a");
                                    add("b");
                                    add("c");
                                }
                            },
                            4));
                    put("sub", new Fnt.Folder(null,
                            new ArrayList<String>() {
                                {
                                    add("a");
                                    add("b");
                                    add("c");
                                }
                            },
                            7));
                    put("sub2", new Fnt.Folder(null,
                            new ArrayList<String>() {
                                {
                                    add("a");
                                    add("b");
                                    add("c");
                                }
                            },
                            10));
                }
            },
            new ArrayList<String>() {
                {
                    add("Alpha");
                    add("Beta");
                    add("Delta");
                }
            },
            0
    );

    @Test
    void folderNotNull() {
        assertThat(folder)
                .isNotNull();
    }

    @Test
    void getIdOf() {
        assertThat(folder.getIdOf("Alpha"))
                .isEqualTo(0);
        assertThat(folder.getIdOf("Delta"))
                .isEqualTo(2);
        assertThat(folder.getIdOf("a/test/leg"))
                .isEqualTo(3);
        assertThat(folder.getIdOf("a/a"))
                .isNotEqualTo(folder.getIdOf("sub/a"));
        assertThat(folder.getIdOf("moo"))
                .isEqualTo(-1);
    }

    @Test
    void getFilenameOf() {
        assertThat(folder.getFilenameOf(0))
                .isEqualTo("Alpha");
        assertThat(folder.getFilenameOf(32))
                .isNull();
    }

    @Test
    void getSubfolder() {
        assertThat(folder.getSubfolder("a/test"))
                .isEqualTo(test);
        assertThat(folder.getSubfolder("moo/test"))
                .isNull();
    }

    @Test
    void equality() {
        assertThat(folder.getSubfolder("sub1"))
                .isNotEqualTo(folder.getSubfolder("sub2"));
    }

}
