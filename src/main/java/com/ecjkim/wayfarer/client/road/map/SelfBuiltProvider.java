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
import java.lang.ref.SoftReference;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;

/**
 * Self-built tile provider (Scheme B). Listens to chunk load events and renders tiles using vanilla MapColor. Does not
 * depend on any external mod.
 *
 * <p>
 * Coordinate convention: tileX = floor(worldX / 256), pixelX = worldX % 256. Chunk (chunkX, chunkZ) belongs to tile
 * (floor(chunkX * 16 / 256), floor(chunkZ * 16 / 256)).
 */
public class SelfBuiltProvider implements MapProvider {

    private static final int TILE_SIZE = 256;
    private static final int DEFAULT_GRAY = 0xFFC0C0C0;

    private final Map<Long, SoftReference<int[]>> tileCache = new ConcurrentHashMap<>();
    private final Set<Long> dirtyTileSet = ConcurrentHashMap.newKeySet();
    private final ExecutorService workerPool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Wayfarer-TileWorker");
        t.setDaemon(true);
        return t;
    });

    private boolean chunkListenerRegistered;

    public SelfBuiltProvider() {}

    // --- MapProvider ---

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public BufferedImage getTile(int dimension, int zoom, int tileX, int tileY) {
        long key = tileKey(tileX, tileY);
        SoftReference<int[]> ref = tileCache.get(key);
        int[] pixels = ref != null ? ref.get() : null;
        if (pixels == null) {
            pixels = renderTile(dimension, tileX, tileY);
            if (pixels != null) {
                tileCache.put(key, new SoftReference<>(pixels));
            }
        }
        if (pixels == null)
            return null;
        BufferedImage img = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, TILE_SIZE, TILE_SIZE, pixels, 0, TILE_SIZE);
        return img;
    }

    @Override
    public void invalidate(ChunkPos chunk) {
        int tileX = Math.floorDiv(chunk.getMinBlockX(), TILE_SIZE);
        int tileZ = Math.floorDiv(chunk.getMinBlockZ(), TILE_SIZE);
        long key = tileKey(tileX, tileZ);
        dirtyTileSet.add(key);
    }

    @Override
    public String getName() {
        return "SelfBuilt";
    }

    // --- lifecycle ---

    public void registerChunkListener() {
        if (chunkListenerRegistered)
            return;
        chunkListenerRegistered = true;
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> onChunkLoad(chunk));
    }

    private void onChunkLoad(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        invalidate(pos);
        scheduleDirtyProcessing();
    }

    // --- rendering ---

    /**
     * Render a tile synchronously from the current client world. Returns null if the world is not available or
     * dimension mismatch.
     */
    private int[] renderTile(int dimension, int tileX, int tileY) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null)
            return null;

        // dimension check: overlay worlds freely since we serve what we have
        int[] pixels = new int[TILE_SIZE * TILE_SIZE];
        int originX = tileX * TILE_SIZE;
        int originZ = tileY * TILE_SIZE;

        for (int dz = 0; dz < TILE_SIZE; dz++) {
            int worldZ = originZ + dz;
            int rowBase = dz * TILE_SIZE;
            for (int dx = 0; dx < TILE_SIZE; dx++) {
                int worldX = originX + dx;
                int chunkX = worldX >> 4;
                int chunkZ = worldZ >> 4;
                int color = DEFAULT_GRAY;

                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk != null) {
                    int inChunkX = worldX & 15;
                    int inChunkZ = worldZ & 15;
                    int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, inChunkX, inChunkZ);
                    BlockPos pos = new BlockPos(worldX, surfaceY, worldZ);
                    MapColor mapColor = level.getBlockState(pos).getMapColor(level, pos);
                    color = 0xFF000000 | mapColor.col;
                }
                pixels[rowBase + dx] = color;
            }
        }
        return pixels;
    }

    // --- background worker ---

    private volatile boolean processingScheduled;

    private void scheduleDirtyProcessing() {
        if (processingScheduled)
            return;
        processingScheduled = true;
        workerPool.submit(this::processDirtyTiles);
    }

    private void processDirtyTiles() {
        try {
            // short delay to batch up rapid chunk loads
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
        }

        Set<Long> batch = new HashSet<>();
        dirtyTileSet.removeIf(key -> {
            if (batch.size() >= 8)
                return false;
            batch.add(key);
            return true;
        });

        for (long key : batch) {
            int tileX = (int)(key >> 32);
            int tileY = (int)key;
            int[] pixels = renderTile(0, tileX, tileY);
            if (pixels != null) {
                tileCache.put(key, new SoftReference<>(pixels));
            }
        }

        processingScheduled = false;

        // if more dirty tiles remain, schedule another round
        if (!dirtyTileSet.isEmpty()) {
            scheduleDirtyProcessing();
        }
    }

    public void shutdown() {
        workerPool.shutdown();
        try {
            workerPool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    // --- helpers ---

    private static long tileKey(int tileX, int tileY) {
        return ((long)tileX << 32) | (tileY & 0xFFFFFFFFL);
    }
}
