/*
 * Copyright (C) 2025  MinecraftNavigationAndMapMod contributors
 * https://github.com/ECJKropas/MinecraftNavigationAndMapMod

 * MinecraftNavigationAndMapMod is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.

 * MinecraftNavigationAndMapMod is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with MinecraftNavigationAndMapMod.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.ecjkim.wayfarer.client.road.map;

import java.awt.image.BufferedImage;

import net.minecraft.world.level.ChunkPos;

/**
 * Abstraction over tile sources for the web map preview.
 * <p>
 * Coordinate convention: tileX = floor(worldX / 256), tileY = floor(worldZ / 256), pixelX = worldX % 256, pixelY =
 * worldZ % 256. Each tile covers 256x256 blocks and is returned as a 256x256 ARGB image.
 */
public interface MapProvider {

    /** Whether this provider can currently serve tiles. */
    boolean isAvailable();

    /**
     * Get the tile image for the given coordinates, or null if not available.
     *
     * @param dimension 0 = overworld, -1 = nether, 1 = end
     * @param zoom zoom level (currently always 0 = 1px/block)
     * @param tileX tile column in tile coordinates
     * @param tileY tile row in tile coordinates
     */
    BufferedImage getTile(int dimension, int zoom, int tileX, int tileY);

    /** Mark the tile(s) covering this chunk as dirty for re-render. */
    void invalidate(ChunkPos chunk);

    /** Human-readable provider name for logging / settings display. */
    String getName();
}
