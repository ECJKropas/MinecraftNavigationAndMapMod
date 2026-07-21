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
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private Method mapProcessorGetLeveledRegionMethod;
    private Method leveledRegionGetTextureMethod;
    private Method mapProcessorGetMapSaveLoadMethod;
    private Method mapSaveLoadRequestLoadMethod;
    private Method mapSaveLoadRequestBranchCacheMethod;
    private Method regionTextureGetDirectColorBufferMethod;
    private Method mapSaveLoadLoadRegionMethod;
    private Method mapProcessorGetWorldBlockLookupMethod;
    private Method mapProcessorGetWorldBlockRegistryMethod;
    private Method worldMapSessionGetCurrentSessionMethod;
    private Method worldMapSessionGetMapProcessorMethod;
    private static Class<?> worldMapSessionClass;
    private Field mapProcessorWorldFluidRegistryField;
    private Field mapProcessorBiomeGetterField;
    private Object mapProcessor;
    private final Set<Long> pendingRegionLoads = ConcurrentHashMap.newKeySet();
    private volatile Object scannedProcessor;
    private volatile boolean fullScanStarted;
    private static final Pattern REGION_FILE = Pattern.compile("(-?\\d+)_(-?\\d+)(?:\\.zip|\\.xaero)?");

    @Override
    public boolean isAvailable() {
        probeReflection();
        boolean available = xaeroAvailable && findMapProcessor() != null;
        return available;
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
            if (hasData)
                return image;
            return getCachedTextureTile(processor, zoom, tileX, tileY);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Xaero tile read failed", e);
            return null;
        }
    }

    private BufferedImage getCachedTextureTile(Object processor, int zoom, int tileX, int tileY) {
        int blockSpan = zoom < 0 ? TILE_SIZE << Math.min(-zoom, 20) : TILE_SIZE;
        int level = 1;
        int regionSpan = 1024;
        while (regionSpan < blockSpan && level < 8) {
            regionSpan <<= 1;
            level++;
        }
        int textureScale = 1 << (level - 1);
        int blockPerTexturePixel = textureScale;
        BufferedImage image = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        boolean hasData = false;

        try {
            synchronized (processor) {
                for (int pixelZ = 0; pixelZ < TILE_SIZE; pixelZ++) {
                    for (int pixelX = 0; pixelX < TILE_SIZE; pixelX++) {
                        int mapX = tileX * TILE_SIZE + pixelX;
                        int mapZ = tileY * TILE_SIZE + pixelZ;
                        int worldX = zoom >= 0 ? Math.floorDiv(mapX, 1 << zoom) : mapX << -zoom;
                        int worldZ = zoom >= 0 ? Math.floorDiv(mapZ, 1 << zoom) : mapZ << -zoom;
                        int regionX = Math.floorDiv(worldX, regionSpan);
                        int regionZ = Math.floorDiv(worldZ, regionSpan);
                        int localX = Math.floorMod(worldX, regionSpan) / blockPerTexturePixel;
                        int localZ = Math.floorMod(worldZ, regionSpan) / blockPerTexturePixel;
                        int textureX = localX / 128;
                        int textureZ = localZ / 128;
                        Object region =
                            mapProcessorGetLeveledRegionMethod.invoke(processor, regionX, regionZ, level, 0);
                        if (region == null)
                            continue;
                        Object texture = leveledRegionGetTextureMethod.invoke(region, textureX, textureZ);
                        if (texture == null)
                            continue;
                        ByteBuffer buffer = (ByteBuffer)regionTextureGetDirectColorBufferMethod.invoke(texture);
                        if (buffer == null)
                            continue;
                        int bufferX = localX & 127;
                        int bufferZ = localZ & 127;
                        int offset = (bufferZ * 128 + bufferX) * 4;
                        if (offset + 3 >= buffer.limit())
                            continue;
                        int red = buffer.get(offset) & 0xFF;
                        int green = buffer.get(offset + 1) & 0xFF;
                        int blue = buffer.get(offset + 2) & 0xFF;
                        int alpha = buffer.get(offset + 3) & 0xFF;
                        if (alpha == 0)
                            continue;
                        image.setRGB(pixelX, pixelZ, (alpha << 24) | (red << 16) | (green << 8) | blue);
                        hasData = true;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Xaero cached texture read failed", e);
            return null;
        }
        return hasData ? image : null;
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
                requestCachedTextureRegions(processor, zoom, tileX, tileY);
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
                        loadRegion(processor, regionX, regionZ);
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Xaero region load request failed", e);
            } finally {
                pendingRegionLoads.remove(requestKey);
            }
        });
    }

    private void requestCachedTextureRegions(Object processor, int zoom, int tileX, int tileY) {
        int blockSpan = zoom < 0 ? TILE_SIZE << Math.min(-zoom, 20) : TILE_SIZE;
        int level = 1;
        int regionSpan = 1024;
        while (regionSpan < blockSpan && level < 8) {
            regionSpan <<= 1;
            level++;
        }
        int minBlockX = zoom >= 0 ? Math.floorDiv(tileX * TILE_SIZE, 1 << zoom) : tileX * blockSpan;
        int minBlockZ = zoom >= 0 ? Math.floorDiv(tileY * TILE_SIZE, 1 << zoom) : tileY * blockSpan;
        int maxBlockX =
            zoom >= 0 ? Math.floorDiv(tileX * TILE_SIZE + TILE_SIZE - 1, 1 << zoom) : minBlockX + blockSpan - 1;
        int maxBlockZ =
            zoom >= 0 ? Math.floorDiv(tileY * TILE_SIZE + TILE_SIZE - 1, 1 << zoom) : minBlockZ + blockSpan - 1;
        int minRegionX = Math.floorDiv(minBlockX, regionSpan);
        int minRegionZ = Math.floorDiv(minBlockZ, regionSpan);
        int maxRegionX = Math.floorDiv(maxBlockX, regionSpan);
        int maxRegionZ = Math.floorDiv(maxBlockZ, regionSpan);
        int requested = 0;
        try {
            Object saveLoad = processorMapSaveLoad(processor);
            for (int regionZ = minRegionZ; regionZ <= maxRegionZ && requested < 16; regionZ++) {
                for (int regionX = minRegionX; regionX <= maxRegionX && requested < 16; regionX++) {
                    Object region = mapProcessorGetLeveledRegionMethod.invoke(processor, regionX, regionZ, level, 0);
                    if (region != null
                        && mapSaveLoadRequestBranchCacheMethod.getParameterTypes()[0].isInstance(region)) {
                        mapSaveLoadRequestBranchCacheMethod.invoke(saveLoad, region, "Wayfarer");
                        requested++;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Xaero visible cache request failed", e);
        }
    }

    private void startFullRegionScan(Object processor) {
        if (fullScanStarted && scannedProcessor == processor)
            return;
        synchronized (this) {
            if (fullScanStarted && scannedProcessor == processor)
                return;
            fullScanStarted = true;
            scannedProcessor = processor;
        }

        try {
            Method getMapWorld = mapProcessorClass.getMethod("getMapWorld");
            Object mapWorld = getMapWorld.invoke(processor);
            Object dimension = mapWorld.getClass().getMethod("getCurrentDimension").invoke(mapWorld);
            Path dimensionPath = (Path)dimension.getClass().getMethod("getMainFolderPath").invoke(dimension);
            String multiworld = (String)dimension.getClass().getMethod("getCurrentMultiworld").invoke(dimension);
            Path mapPath = multiworld == null ? dimensionPath : dimensionPath.resolve(multiworld);

            CompletableFuture.runAsync(() -> {
                List<Long> regions = new ArrayList<>();
                List<long[]> caches = new ArrayList<>();
                try (var files = Files.list(mapPath)) {
                    files.filter(Files::isRegularFile).forEach(path -> {
                        Matcher matcher = REGION_FILE.matcher(path.getFileName().toString());
                        if (matcher.matches()) {
                            int regionX = Integer.parseInt(matcher.group(1));
                            int regionZ = Integer.parseInt(matcher.group(2));
                            regions.add(((long)regionX << 32) ^ (regionZ & 0xFFFFFFFFL));
                        }
                    });
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "Xaero region scan failed: " + mapPath, e);
                    return;
                }
                try (var files = Files.walk(mapPath)) {
                    files.filter(Files::isRegularFile).forEach(path -> {
                        String fileName = path.getFileName().toString();
                        if (!fileName.endsWith(".xwmc"))
                            return;
                        Path levelPath = path.getParent();
                        if (levelPath == null)
                            return;
                        String levelDirName = levelPath.getFileName().toString();
                        int level = -1;
                        if (levelDirName.startsWith("cache")) {
                            // cache_N format: "cache_1" → level 1, "cache_3" → level 3
                            String suffix = levelDirName.substring(5); // "_1" or "3" or ""
                            try {
                                level = suffix.isEmpty() ? 1 : Integer.parseInt(suffix.replace("_", ""));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        // Walk one level deeper: cache/{level}/{x}_{y}.xwmc
                        if (level <= 0 && levelPath.getParent() != null) {
                            String grandDirName = levelPath.getParent().getFileName().toString();
                            if (grandDirName.equals("cache") || grandDirName.startsWith("cache_")) {
                                try {
                                    level = Integer.parseInt(levelDirName);
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                        if (level <= 0)
                            return;
                        String regionName = fileName.substring(0, fileName.length() - 5);
                        Matcher matcher = REGION_FILE.matcher(regionName);
                        if (matcher.matches()) {
                            caches.add(new long[] {Integer.parseInt(matcher.group(1)),
                                Integer.parseInt(matcher.group(2)), level});
                        }
                    });
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "Xaero region scan failed: " + mapPath, e);
                    return;
                }
                requestBranchCaches(processor, caches, 0);
                loadRegionBatch(processor, regions, 0);
            });
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Xaero map path lookup failed", e);
        }
    }

    private void requestBranchCaches(Object processor, List<long[]> caches, int offset) {
        Minecraft.getInstance().execute(() -> {
            int end = Math.min(offset + 16, caches.size());
            for (int i = offset; i < end; i++) {
                long[] cache = caches.get(i);
                try {
                    Object region = mapProcessorGetLeveledRegionMethod.invoke(processor, (int)cache[0], (int)cache[1],
                        (int)cache[2], 0);
                    if (region != null && mapSaveLoadRequestBranchCacheMethod.getParameterTypes()[0].isInstance(region))
                        mapSaveLoadRequestBranchCacheMethod.invoke(processorMapSaveLoad(processor), region, "Wayfarer");
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "Xaero cache load request failed", e);
                }
            }
            if (end < caches.size())
                requestBranchCaches(processor, caches, end);
        });
    }

    private Object processorMapSaveLoad(Object processor) throws ReflectiveOperationException {
        return mapProcessorGetMapSaveLoadMethod.invoke(processor);
    }

    private void loadRegionBatch(Object processor, List<Long> regions, int offset) {
        Minecraft.getInstance().execute(() -> {
            int end = Math.min(offset + 16, regions.size());
            for (int i = offset; i < end; i++) {
                long key = regions.get(i);
                loadRegion(processor, (int)(key >> 32), (int)key);
            }
            if (end < regions.size())
                loadRegionBatch(processor, regions, end);
        });
    }

    private void loadRegion(Object processor, int regionX, int regionZ) {
        try {
            Object region = mapProcessorGetLeafMapRegionMethod.invoke(processor, 0, regionX, regionZ, true);
            if (region == null)
                return;
            Object saveLoad = mapProcessorGetMapSaveLoadMethod.invoke(processor);
            Object blockLookup = mapProcessorGetWorldBlockLookupMethod.invoke(processor);
            Object blockRegistry = mapProcessorGetWorldBlockRegistryMethod.invoke(processor);
            Object fluidRegistry = mapProcessorWorldFluidRegistryField.get(processor);
            Object biomeGetter = mapProcessorBiomeGetterField.get(processor);
            Object loaded = mapSaveLoadLoadRegionMethod.invoke(saveLoad, region, blockLookup, blockRegistry,
                fluidRegistry, biomeGetter, false, 0);
            if (Boolean.FALSE.equals(loaded)) {
                LOGGER.warning("Xaero region file was not loaded: " + regionX + "," + regionZ);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Xaero region load failed: " + regionX + "," + regionZ, e);
        }
    }

    @Override
    public String getName() {
        return "Xaero";
    }

    private void probeReflection() {
        if (reflectionProbed)
            return;
        reflectionProbed = true;
        LOGGER.info("XaeroProvider: starting reflection probe...");

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
            mapProcessorGetLeveledRegionMethod =
                mapProcessorClass.getMethod("getLeveledRegion", int.class, int.class, int.class, int.class);
            Class<?> leveledRegionClass = Class.forName("xaero.map.region.LeveledRegion");
            leveledRegionGetTextureMethod = leveledRegionClass.getMethod("getTexture", int.class, int.class);
            mapProcessorGetMapSaveLoadMethod = mapProcessorClass.getMethod("getMapSaveLoad");
            Class<?> mapSaveLoadClass = Class.forName("xaero.map.file.MapSaveLoad");
            mapSaveLoadRequestLoadMethod =
                mapSaveLoadClass.getMethod("requestLoad", Class.forName("xaero.map.region.MapRegion"), String.class);
            mapSaveLoadLoadRegionMethod = mapSaveLoadClass.getMethod("loadRegion",
                Class.forName("xaero.map.region.MapRegion"), Class.forName("net.minecraft.core.HolderLookup"),
                Class.forName("net.minecraft.core.Registry"), Class.forName("net.minecraft.core.Registry"),
                Class.forName("xaero.map.biome.BiomeGetter"), boolean.class, int.class);
            mapSaveLoadRequestBranchCacheMethod = mapSaveLoadClass.getMethod("requestBranchCache",
                Class.forName("xaero.map.region.BranchLeveledRegion"), String.class);
            regionTextureGetDirectColorBufferMethod =
                Class.forName("xaero.map.region.texture.RegionTexture").getMethod("getDirectColorBuffer");
            mapProcessorGetWorldBlockLookupMethod = mapProcessorClass.getMethod("getWorldBlockLookup");
            mapProcessorGetWorldBlockRegistryMethod = mapProcessorClass.getMethod("getWorldBlockRegistry");
            Class<?> worldMapSessionClassLocal = Class.forName("xaero.map.WorldMapSession");
            worldMapSessionClass = worldMapSessionClassLocal;
            worldMapSessionGetCurrentSessionMethod = worldMapSessionClassLocal.getMethod("getCurrentSession");
            worldMapSessionGetMapProcessorMethod = worldMapSessionClassLocal.getMethod("getMapProcessor");
            mapProcessorWorldFluidRegistryField = mapProcessorClass.getDeclaredField("worldFluidRegistry");
            mapProcessorWorldFluidRegistryField.setAccessible(true);
            mapProcessorBiomeGetterField = mapProcessorClass.getDeclaredField("biomeGetter");
            mapProcessorBiomeGetterField.setAccessible(true);
            xaeroAvailable = true;
            LOGGER.info("XaeroProvider: MapProcessor.getMapTile probe succeeded");
        } catch (ReflectiveOperationException e) {
            xaeroAvailable = false;
            LOGGER.log(Level.WARNING, "XaeroProvider reflection probe failed", e);
        }
    }

    private Object findMapProcessor() {
        if (!xaeroAvailable)
            return null;
        if (mapProcessor != null && mapProcessorClass.isInstance(mapProcessor))
            return mapProcessor;

        try {
            Object session = worldMapSessionGetCurrentSessionMethod.invoke(null);
            if (session != null) {
                mapProcessor = worldMapSessionGetMapProcessorMethod.invoke(session);
                if (mapProcessor != null) {
                    startFullRegionScan(mapProcessor);
                    return mapProcessor;
                }
                // 26.x: session exists but MapProcessor not yet created (lazy init);
                // try to access the private field directly as fallback
                try {
                    Field mpField = worldMapSessionClass.getDeclaredField("mapProcessor");
                    mpField.setAccessible(true);
                    mapProcessor = mpField.get(session);
                    if (mapProcessor != null && mapProcessorClass.isInstance(mapProcessor)) {
                        startFullRegionScan(mapProcessor);
                        return mapProcessor;
                    }
                } catch (NoSuchFieldException ignored) {
                }
            }

            Object screen = currentScreen();
            if (screen != null && screen.getClass().getName().equals("xaero.map.gui.GuiMap")) {
                Method getter = screen.getClass().getMethod("getMapProcessor");
                mapProcessor = getter.invoke(screen);
                if (mapProcessor != null) {
                    startFullRegionScan(mapProcessor);
                    return mapProcessor;
                }
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
            if (mapProcessor != null) {
                startFullRegionScan(mapProcessor);
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
            if (gui == null)
                return null;
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
