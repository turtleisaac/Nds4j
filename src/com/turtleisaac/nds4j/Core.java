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

package com.turtleisaac.nds4j;

public class Core
{
    /**
     * Individual values can be accessed in a structured manner using <code>Core.getSpecificVersionNumber(VersionData vData)</code>
     */
    public static final int[] VERSION = {1, 0, 0};

    public enum VersionData
    {
        MAJOR(0),
        MINOR(1),
        PATCH(2);

        private final int accessID;

        VersionData(int accessID)
        {
            this.accessID = accessID;
        }
    }

    /**
     * Returns a formatted string containing the version number of this library
     * @return a formatted string
     */
    public static String getVersionNumber()
    {
        return String.format("%d.%d.%d", VERSION[VersionData.MAJOR.accessID], VERSION[VersionData.MINOR.accessID], VERSION[VersionData.PATCH.accessID]);
    }

    /**
     * Returns an int representing a specific part of the library's version number
     * @param vData can be <code>MAJOR</code>, <code>MINOR</code>, or <code>PATCH</code>
     * @return an int
     */
    private static int getSpecificVersionNumber(VersionData vData)
    {
        return VERSION[vData.accessID];
    }
}
