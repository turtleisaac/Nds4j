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
 * Checks an OAM against its NCGR and NCLR before the tiled renderer reads them.
 */
final class CompanionBounds
{
    /** Blocks construction. */
    private CompanionBounds()
    {
    }

    /** Rejects an OAM the tiled renderer cannot draw from this NCGR and NCLR. */
    static void requireOam(IndexedImage image, int mappingType, int partitionOffset, CellBank.Cell.OAM oam)
    {
        requireShape(oam.getShape(), oam.getSize());
        if (image.getBitDepth() != 4 && image.getBitDepth() != 8)
            return;

        requireTiles(image, mappingType, partitionOffset, oam);
        if (image.getBitDepth() == 4)
            requirePalette(image, oam.getPalette());
    }

    /** Rejects shape 3 and a size outside 0..3. */
    private static void requireShape(int shape, int size)
    {
        if (shape < 0 || shape > 2 || size < 0 || size > 3)
            throw new IncompatibleCompanionException(IncompatibleCompanionException.Kind.NCER);
    }

    /** Rejects an object whose tiles fall outside the NCGR. */
    private static void requireTiles(IndexedImage image, int mappingType, int partitionOffset, CellBank.Cell.OAM oam)
    {
        if (mappingType == 4)
        {
            requireGridTiles(image, partitionOffset, oam);
            return;
        }

        requireLinearTiles(image, mappingType, partitionOffset, oam);
    }

    /** Rejects a 1D object whose consecutive tiles run past the NCGR. */
    private static void requireLinearTiles(IndexedImage image, int mappingType, int partitionOffset, CellBank.Cell.OAM oam)
    {
        int depth = image.getBitDepth();
        int bytesPerTile = depth == 8 ? 64 : 32;
        int[] box = CellBank.oamSize[oam.getShape()][oam.getSize()];
        int need = (box[0] / 8) * (box[1] / 8) * bytesPerTile;
        int startByte = CellBank.tileByteOffset(oam.getTileOffset(), mappingType, partitionOffset);
        int available = image.getNumTiles() * bytesPerTile;

        if (startByte < 0 || available < need || startByte > available - need)
            throw new IncompatibleCompanionException(IncompatibleCompanionException.Kind.NCGR);
    }

    /** Rejects a 2D object whose rectangle leaves the sheet. */
    private static void requireGridTiles(IndexedImage image, int partitionOffset, CellBank.Cell.OAM oam)
    {
        int wide = Math.max(1, image.getWidth() / 8);
        int[] box = CellBank.oamSize[oam.getShape()][oam.getSize()];
        int cols = box[0] / 8;
        int rows = box[1] / 8;
        for (int row = 0; row < rows; row++)
            requireGridRow(image, partitionOffset, oam, wide, cols, row);
    }

    /** Rejects one row of a 2D object. */
    private static void requireGridRow(IndexedImage image, int partitionOffset, CellBank.Cell.OAM oam, int wide, int cols, int row)
    {
        for (int col = 0; col < cols; col++)
            requireGridTile(image, partitionOffset, oam, wide, col, row);
    }

    /** Rejects one 8x8 of a 2D object that is not on the sheet. */
    private static void requireGridTile(IndexedImage image, int partitionOffset, CellBank.Cell.OAM oam, int wide, int col, int row)
    {
        if (gridTileFits(image, partitionOffset, oam, wide, col, row))
            return;

        throw new IncompatibleCompanionException(IncompatibleCompanionException.Kind.NCGR);
    }

    /** @return whether this 8x8 of a 2D object lands inside the sheet */
    private static boolean gridTileFits(IndexedImage image, int partitionOffset, CellBank.Cell.OAM oam, int wide, int col, int row)
    {
        int fileCol = fileColumn(image, partitionOffset, oam, wide, col);
        int fileRow = fileRow(image, partitionOffset, oam, wide, row);
        if (fileCol < 0 || fileCol >= wide)
            return false;

        int rows = image.getHeight() / 8;
        return fileRow >= 0 && fileRow < rows;
    }

    /** @return the sheet column of one 8x8 in a 2D object */
    private static int fileColumn(IndexedImage image, int partitionOffset, CellBank.Cell.OAM oam, int wide, int col)
    {
        return CellBank.gridSourceColumn(oam.getTileOffset(), image.getBitDepth(), partitionOffset, wide) + col;
    }

    /** @return the sheet row of one 8x8 in a 2D object */
    private static int fileRow(IndexedImage image, int partitionOffset, CellBank.Cell.OAM oam, int wide, int row)
    {
        return CellBank.gridSourceRow(oam.getTileOffset(), image.getBitDepth(), partitionOffset, wide) + row;
    }

    /** Rejects a 4bpp palette number outside the NCLR. */
    private static void requirePalette(IndexedImage image, int palette)
    {
        Palette colors = image.getPalette();
        int blocks = colors == null ? 0 : Math.max(1, colors.getNumColors() / 16);

        if (palette < 0 || palette >= blocks)
            throw new IncompatibleCompanionException(IncompatibleCompanionException.Kind.NCLR);
    }
}
