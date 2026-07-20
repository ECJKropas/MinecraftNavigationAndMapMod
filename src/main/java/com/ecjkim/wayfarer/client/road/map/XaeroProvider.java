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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Xaero World Map provider.
 *
 * <p>
 * Xaero stores map data in its own region files and exposes the loaded data as MapTile/MapBlock objects. This class
 * reads those objects through reflection so Xaero remains an optional dependency; it never rebuilds the map from
 * Minecraft chunks.
 */
public class XaeroProvider implements MapProvider {

    private static final Logger LOGGER = Logger.getLogger("Wayfarer|XaeroProvider");
    private static final int TILE_SIZE = 256;

    private boolean reflectionProbed;
    private boolean xaeroAvailable;
    private Class<?> mapProcessorClass;
    private Method mapProcessorGetMapTileMethod;
    private Method mapTileIsLoadedMethod;
    private Method mapTileGetBlockMethod;
    private Method mapBlockGetStateMethod;
    private Method mapBlockGetHeightMethod;
    private Method mapProcessorGetLeafMapRegionMethod;
    private Method mapProcessorGetMapSaveLoadMethod;
    private Method mapSaveLoadRequestLoadMethod;
    private Object mapProcessor;
    private final Set<Long> pendingRegionLoads = ConcurrentHashMap.newKeySet();

    @Override
    public boolean isAvailable() {
        probeReflection();
        return xaeroAvailable && findMapProcessor() != null;
    }

    @Override
    public BufferedImage getTile(int dimension, int zoom, int tileX, int tileY) {
        probeReflection();
        Object processor = findMapProcessor();
        Minecraft mc = Minecraft.getInstance();
        if (processor == null || mc.level == null || zoom < -14 || zoom > 18)
            return null;

        try {
            BufferedImage image = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
            boolean hasData = false;
            Map<Long, Object> tileCache = new HashMap<>();
            int scale = zoom >= 0 ? 1 << zoom : 1;
            int step = zoom < 0 ? 1 << -zoom : 1;

            synchronized (processor) {
                for (int pixelZ = 0; pixelZ < TILE_SIZE; pixelZ++) {
                    for (int pixelX = 0; pixelX < TILE_SIZE; pixelX++) {
                        int mapX = tileX * TILE_SIZE + pixelX;
                        int mapZ = tileY * TILE_SIZE + pixelZ;
                        int worldX = zoom >= 0 ? Math.floorDiv(mapX, scale) : mapX * step;
                        int worldZ = zoom >= 0 ? Math.floorDiv(mapZ, scale) : mapZ * step;
                        int chunkX = Math.floorDiv(worldX, 16);
                        int chunkZ = Math.floorDiv(worldZ, 16);
                        long tileKey = ((long)chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
                        Object xaeroTile = tileCache.get(tileKey);
                        if (xaeroTile == null && !tileCache.containsKey(tileKey)) {
                            xaeroTile = mapProcessorGetMapTileMethod.invoke(processor, chunkX, chunkZ, 0);
                            tileCache.put(tileKey, xaeroTile);
                        }
                        if (xaeroTile == null || !(Boolean)mapTileIsLoadedMethod.invoke(xaeroTile))
                            continue;

                        Object xaeroBlock = mapTileGetBlockMethod.invoke(xaeroTile, worldX & 15, worldZ & 15);
                        if (xaeroBlock == null)
                            continue;
                        BlockState state = (BlockState)mapBlockGetStateMethod.invoke(xaeroBlock);
                        if (state == null)
                            continue;
                        int height = (Integer)mapBlockGetHeightMethod.invoke(xaeroBlock);
                        int color = state.getMapColor(mc.level, new BlockPos(worldX, height, worldZ)).col;
                        image.setRGB(pixelX, pixelZ, 0xFF000000 | color);
                        hasData = true;
                    }
                }
            }
            return hasData ? image : null;
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Xaero tile read failed", e);
            return null;
        }
    }

    @Override
    public void invalidate(ChunkPos chunk) {}

    @Override
    public void requestTileRender(int dimension, int zoom, int tileX, int tileY) {
        Object processor = findMapProcessor();
        if (processor == null)
            return;

        long requestKey = (((long)zoom & 0xFFL) << 56) ^ (((long)tileX) << 28) ^ (tileY & 0x0FFFFFFFL);
        if (!pendingRegionLoads.add(requestKey))
            return;

        Minecraft.getInstance().execute(() -> {
            try {
                int scale = zoom >= 0 ? 1 << zoom : 1;
                int step = zoom < 0 ? 1 << -zoom : 1;
                int minBlockX = zoom >= 0 ? Math.floorDiv(tileX * TILE_SIZE, scale) : tileX * TILE_SIZE * step;
                int minBlockZ = zoom >= 0 ? Math.floorDiv(tileY * TILE_SIZE, scale) : tileY * TILE_SIZE * step;
                int maxBlockX = zoom >= 0 ? Math.floorDiv(tileX * TILE_SIZE + TILE_SIZE - 1, scale)
                    : minBlockX + TILE_SIZE * step - 1;
                int maxBlockZ = zoom >= 0 ? Math.floorDiv(tileY * TILE_SIZE + TILE_SIZE - 1, scale)
                    : minBlockZ + TILE_SIZE * step - 1;
                int minRegionX = Math.floorDiv(Math.floorDiv(minBlockX, 16), 8);
                int minRegionZ = Math.floorDiv(Math.floorDiv(minBlockZ, 16), 8);
                int maxRegionX = Math.floorDiv(Math.floorDiv(maxBlockX, 16), 8);
                int maxRegionZ = Math.floorDiv(Math.floorDiv(maxBlockZ, 16), 8);

                long regionCount = (long)(maxRegionX - minRegionX + 1) * (maxRegionZ - minRegionZ + 1);
                if (regionCount > 256)
                    return;

                for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                    for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                        Object region = mapProcessorGetLeafMapRegionMethod.invoke(processor, 0, regionX, regionZ, true);
                        if (region != null) {
                            Object saveLoad = mapProcessorGetMapSaveLoadMethod.invoke(processor);
                            mapSaveLoadRequestLoadMethod.invoke(saveLoad, region, "Wayfarer");
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Xaero region load request failed", e);
            } finally {
                pendingRegionLoads.remove(requestKey);
            }
        });
    }

    @Override
    public String getName() {
        return "Xaero";
    }

    private void probeReflection() {
        if (reflectionProbed)
            return;
        reflectionProbed = true;

        try {
            Class<?> mapTileClass = Class.forName("xaero.map.region.MapTile");
            Class<?> mapBlockClass = Class.forName("xaero.map.region.MapBlock");
            mapProcessorClass = Class.forName("xaero.map.MapProcessor");
            mapProcessorGetMapTileMethod = mapProcessorClass.getMethod("getMapTile", int.class, int.class, int.class);
            mapTileIsLoadedMethod = mapTileClass.getMethod("isLoaded");
            mapTileGetBlockMethod = mapTileClass.getMethod("getBlock", int.class, int.class);
            mapBlockGetStateMethod = mapBlockClass.getMethod("getState");
            mapBlockGetHeightMethod = mapBlockClass.getMethod("getHeight");
            mapProcessorGetLeafMapRegionMethod =
                mapProcessorClass.getMethod("getLeafMapRegion", int.class, int.class, int.class, boolean.class);
            mapProcessorGetMapSaveLoadMethod = mapProcessorClass.getMethod("getMapSaveLoad");
            Class<?> mapSaveLoadClass = Class.forName("xaero.map.file.MapSaveLoad");
            mapSaveLoadRequestLoadMethod =
                mapSaveLoadClass.getMethod("requestLoad", Class.forName("xaero.map.region.MapRegion"), String.class);
            xaeroAvailable = true;
            LOGGER.info("XaeroProvider: MapProcessor.getMapTile probe succeeded");
        } catch (ReflectiveOperationException e) {
            xaeroAvailable = false;
        }
    }

    private Object findMapProcessor() {
        if (!xaeroAvailable)
            return null;
        if (mapProcessor != null && mapProcessorClass.isInstance(mapProcessor))
            return mapProcessor;

        try {
            Object screen = currentScreen();
            if (screen != null && screen.getClass().getName().equals("xaero.map.gui.GuiMap")) {
                Method getter = screen.getClass().getMethod("getMapProcessor");
                mapProcessor = getter.invoke(screen);
                if (mapProcessor != null)
                    return mapProcessor;
            }

            Class<?> worldMapClass = Class.forName("xaero.map.WorldMap");
            Object instance = worldMapClass.getField("INSTANCE").get(null);
            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            mapProcessor = findObject(instance, 5, visited);
            if (mapProcessor == null) {
                for (Field field : worldMapClass.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive())
                        continue;
                    field.setAccessible(true);
                    mapProcessor = findObject(field.get(null), 5, visited);
                    if (mapProcessor != null)
                        break;
                }
            }
        } catch (ReflectiveOperationException e) {
            LOGGER.log(Level.FINE, "Xaero MapProcessor lookup failed", e);
        }
        return mapProcessor;
    }

    private Object currentScreen() throws ReflectiveOperationException {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            Field field = Minecraft.class.getDeclaredField("screen");
            field.setAccessible(true);
            return field.get(minecraft);
        } catch (NoSuchFieldException ignored) {
            Field guiField = Minecraft.class.getDeclaredField("gui");
            guiField.setAccessible(true);
            Object gui = guiField.get(minecraft);
            return gui.getClass().getMethod("screen").invoke(gui);
        }
    }

    private Object findObject(Object value, int depth, Set<Object> visited) throws IllegalAccessException {
        if (value == null || depth < 0 || !visited.add(value))
            return null;
        if (mapProcessorClass.isInstance(value))
            return value;
        if (!value.getClass().getName().startsWith("xaero.map"))
            return null;

        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive())
                    continue;
                try {
                    field.setAccessible(true);
                    Object child = findObject(field.get(value), depth - 1, visited);
                    if (child != null)
                        return child;
                } catch (RuntimeException ignored) {
                }
            }
        }
        return null;
    }
}
