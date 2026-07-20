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
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

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
    private static final long DEBOUNCE_MS = 100;

    private final Map<Long, SoftReference<int[]>> tileCache = new ConcurrentHashMap<>();
    private final Set<Long> dirtyTileSet = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> lastInvalidateTime = new ConcurrentHashMap<>();

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
        if (pixels == null)
            return null;
        BufferedImage img = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, TILE_SIZE, TILE_SIZE, pixels, 0, TILE_SIZE);
        return img;
    }

    @Override
    public void requestTileRender(int dimension, int tileX, int tileY) {
        long key = tileKey(tileX, tileY);
        if (tileCache.containsKey(key))
            return;
        dirtyTileSet.add(key);
        scheduleDirtyProcessing();
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

    public void registerListeners() {
        if (chunkListenerRegistered)
            return;
        chunkListenerRegistered = true;
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> onChunkLoad(chunk));

        // Block break → invalidate tile (reflection for 1.20.1 compat)
        try {
            Class<?> breakEventsClass = Class.forName("net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents");
            Object afterEvent = breakEventsClass.getField("AFTER").get(null);
            Class<?> afterBreakIf =
                Class.forName("net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents$AfterBlockBreak");
            Object listener = java.lang.reflect.Proxy.newProxyInstance(afterBreakIf.getClassLoader(),
                new Class<?>[] {afterBreakIf}, (proxy, method, args) -> {
                    if (method.getName().equals("afterBlockBreak"))
                        invalidateBlockPos((BlockPos)args[2]);
                    return null;
                });
            afterEvent.getClass().getMethod("register", Object.class).invoke(afterEvent, listener);
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Block place → invalidate tile (reflection for 1.20.1 compat)
        try {
            Class<?> useBlockClass = Class.forName("net.fabricmc.fabric.api.event.player.UseBlockCallback");
            Object eventField = useBlockClass.getField("EVENT").get(null);
            Object listener = java.lang.reflect.Proxy.newProxyInstance(useBlockClass.getClassLoader(),
                new Class<?>[] {useBlockClass}, (proxy, method, args) -> {
                    if (method.getName().equals("interact")) {
                        if (!((Level)args[1]).isClientSide())
                            return InteractionResult.PASS;
                        BlockHitResult hitResult = (BlockHitResult)args[3];
                        BlockPos placePos = hitResult.getBlockPos().relative(hitResult.getDirection());
                        Minecraft.getInstance().execute(() -> invalidateBlockPos(placePos));
                        return InteractionResult.PASS;
                    }
                    return null;
                });
            eventField.getClass().getMethod("register", Object.class).invoke(eventField, listener);
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void invalidateBlockPos(BlockPos pos) {
        int tileX = Math.floorDiv(pos.getX(), TILE_SIZE);
        int tileY = Math.floorDiv(pos.getZ(), TILE_SIZE);
        long key = tileKey(tileX, tileY);
        long now = System.currentTimeMillis();
        Long last = lastInvalidateTime.get(key);
        if (last != null && now - last < DEBOUNCE_MS)
            return;
        lastInvalidateTime.put(key, now);
        dirtyTileSet.add(key);
        scheduleDirtyProcessing();
    }

    private void onChunkLoad(LevelChunk chunk) {
        // Skip empty chunks (no terrain data): ChunkLoadEvent fires for the entire
        // square render distance, but actual terrain only exists in the inscribed circle.
        if (chunk.isEmpty())
            return;
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

    // --- main-thread batch render ---

    private volatile boolean processingScheduled;

    private void scheduleDirtyProcessing() {
        if (processingScheduled)
            return;
        processingScheduled = true;
        Minecraft.getInstance().execute(this::processDirtyTiles);
    }

    private void processDirtyTiles() {
        Set<Long> batch = new HashSet<>();
        dirtyTileSet.removeIf(key -> {
            if (batch.size() >= 4)
                return false;
            batch.add(key);
            return true;
        });

        if (batch.isEmpty()) {
            processingScheduled = false;
            return;
        }

        for (long key : batch) {
            int tileX = (int)(key >> 32);
            int tileY = (int)key;
            int[] pixels = renderTile(0, tileX, tileY);
            if (pixels != null && hasTerrainData(pixels)) {
                tileCache.put(key, new SoftReference<>(pixels));
                writeTileToDisk(0, tileX, tileY, pixels);
            }
        }

        processingScheduled = false;

        if (!dirtyTileSet.isEmpty()) {
            scheduleDirtyProcessing();
        }
    }

    public void shutdown() {
        // no background threads to shut down
    }

    // --- helpers ---

    private static long tileKey(int tileX, int tileY) {
        return ((long)tileX << 32) | (tileY & 0xFFFFFFFFL);
    }

    /** Returns true if at least one pixel has real MapColor (not DEFAULT_GRAY). */
    private static boolean hasTerrainData(int[] pixels) {
        for (int p : pixels) {
            if (p != DEFAULT_GRAY)
                return true;
        }
        return false;
    }

    // --- disk cache ---

    private Path cachePath(int dimension, int tileX, int tileY) {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("wayfarer/tilecache")
            .resolve(String.valueOf(dimension)).resolve(tileX + "_" + tileY + ".png");
    }

    private void writeTileToDisk(int dimension, int tileX, int tileY, int[] pixels) {
        try {
            Path p = cachePath(dimension, tileX, tileY);
            Files.createDirectories(p.getParent());
            BufferedImage img = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, TILE_SIZE, TILE_SIZE, pixels, 0, TILE_SIZE);
            ImageIO.write(img, "PNG", p.toFile());
        } catch (IOException ignored) {
        }
    }
}
