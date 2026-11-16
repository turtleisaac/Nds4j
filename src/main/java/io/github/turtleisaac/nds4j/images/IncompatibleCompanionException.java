/*
 * Copyright (c) 2026 Turtleisaac.
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

package io.github.turtleisaac.nds4j.images;

/**
 * The NCGR, NCLR, or NCER paired with a cell does not cover what that cell's OAMs address.
 */
public class IncompatibleCompanionException extends RuntimeException
{
    /**
     * Which paired file is too small or out of range for the cell.
     */
    public enum Kind
    {
        /** The tileset does not contain the tiles an OAM addresses. */
        NCGR,
        /** The palette does not contain the OAM's 16-color block. */
        NCLR,
        /** The cell bank has no cell at the requested index, or an OAM shape is prohibited. */
        NCER
    }

    private final Kind kind;

    /**
     * @param kind which companion failed
     */
    public IncompatibleCompanionException(Kind kind)
    {
        super(kind.name());
        this.kind = kind;
    }

    /**
     * @return which companion failed
     */
    public Kind getKind()
    {
        return kind;
    }
}
