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

    // GBATEK OBJ Attribute 0: shape 3 is prohibited, and size is 0..3.
    private static void requireShape(int shape, int size)
    {
        if (shape < 0 || shape > 2 || size < 0 || size > 3)
            throw new IncompatibleCompanionException(IncompatibleCompanionException.Kind.NCER);
    }

    // 4bpp tiles are 32 bytes and 8bpp tiles are 64 (GBATEK LCD VRAM Character Data).
    private static void requireTiles(IndexedImage image, int mappingType, int partitionOffset, CellBank.Cell.OAM oam)
    {
        int depth = image.getBitDepth();
        int bytesPerTile = depth == 8 ? 64 : 32;
        int[] box = CellBank.oamSize[oam.getShape()][oam.getSize()];
        int need = (box[0] / 8) * (box[1] / 8) * bytesPerTile;
        int startByte = tileStart(oam.getTileOffset(), mappingType, depth, partitionOffset);
        int available = image.getNumTiles() * bytesPerTile;

        if (startByte < 0 || available < need || startByte > available - need)
            throw new IncompatibleCompanionException(IncompatibleCompanionException.Kind.NCGR);
    }

    // Same start the renderer uses: (tileOffset << mappingType) * (bitDepth * 8) + partition.
    private static int tileStart(int tileOffset, int mappingType, int depth, int partitionOffset)
    {
        return (tileOffset << (byte) mappingType) * (depth * 8) + partitionOffset;
    }

    // GBATEK OBJ Attribute 2 bits 12-15 select a 16-color block. 8bpp ignores them.
    private static void requirePalette(IndexedImage image, int palette)
    {
        Palette colors = image.getPalette();
        int blocks = colors == null ? 0 : Math.max(1, colors.getNumColors() / 16);

        if (palette < 0 || palette >= blocks)
            throw new IncompatibleCompanionException(IncompatibleCompanionException.Kind.NCLR);
    }
}
