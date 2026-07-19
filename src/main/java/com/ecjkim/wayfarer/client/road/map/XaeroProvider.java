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
import java.util.logging.Level;
import java.util.logging.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;

/**
 * Xaero World Map provider (Scheme A). Reads tile pixel data from Xaero's in-memory tile cache via reflection. Does not
 * parse any disk files; falls back gracefully if reflection fails.
 */
public class XaeroProvider implements MapProvider {

    private static final Logger LOGGER = Logger.getLogger("Wayfarer|XaeroProvider");
    private static final int TILE_SIZE = 256;

    // Cached reflection handles — populated once on first use
    private boolean reflectionProbed;
    private boolean xaeroAvailable;
    private Field guiMapProcessorField;
    private Method mapProcessorGetWorldMethod;
    private Method mapWorldGetTileMethod;

    private String savePath; // for logging only

    public XaeroProvider() {}

    // --- MapProvider ---

    @Override
    public boolean isAvailable() {
        probeReflection();
        if (!xaeroAvailable)
            return false;
        // GuiMap must be the current screen for live tile access
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null)
            return false;
        return mc.screen.getClass().getName().equals("xaero.map.gui.GuiMap");
    }

    @Override
    public BufferedImage getTile(int dimension, int zoom, int tileX, int tileY) {
        if (!isAvailable())
            return null;
        probeReflection();
        if (guiMapProcessorField == null)
            return null;

        try {
            Minecraft mc = Minecraft.getInstance();
            Object guiMap = mc.screen;

            // GuiMap.mapProcessor -> MapProcessor
            Object mapProcessor = guiMapProcessorField.get(guiMap);
            if (mapProcessor == null)
                return null;

            // MapProcessor.getMapWorld() -> MapWorld
            Object mapWorld =
                mapProcessorGetWorldMethod != null ? mapProcessorGetWorldMethod.invoke(mapProcessor) : null;
            if (mapWorld == null)
                return null;

            // MapWorld.getTile() or read tile cache field -> int[]
            int[] pixels = null;
            if (mapWorldGetTileMethod != null) {
                pixels = (int[])mapWorldGetTileMethod.invoke(mapWorld, tileX, tileY);
            }
            if (pixels == null)
                return null;

            BufferedImage img = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, TILE_SIZE, TILE_SIZE, pixels, 0, TILE_SIZE);
            return img;
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Xaero tile reflection failed", e);
            return null;
        }
    }

    @Override
    public void invalidate(ChunkPos chunk) {
        // Xaero maintains its own tile cache; nothing to invalidate here.
    }

    @Override
    public String getName() {
        return "Xaero";
    }

    // --- reflection probing ---

    private void probeReflection() {
        if (reflectionProbed)
            return;
        reflectionProbed = true;

        try {
            // verify Xaero is on classpath
            Class.forName("xaero.map.gui.GuiMap");
            xaeroAvailable = true;
        } catch (ClassNotFoundException e) {
            xaeroAvailable = false;
            return;
        }

        try {
            Class<?> guiMapClass = Class.forName("xaero.map.gui.GuiMap");
            guiMapProcessorField = guiMapClass.getDeclaredField("mapProcessor");
            guiMapProcessorField.setAccessible(true);

            // probe MapProcessor for getMapWorld or mapWorld field
            Class<?> mapProcessorClass = Class.forName("xaero.map.MapProcessor");
            try {
                mapProcessorGetWorldMethod = mapProcessorClass.getMethod("getMapWorld");
            } catch (NoSuchMethodException e) {
                // try field "mapWorld" instead
                Field worldField = mapProcessorClass.getDeclaredField("mapWorld");
                worldField.setAccessible(true);
                mapProcessorGetWorldMethod = null;
            }

            // probe MapWorld for tile cache
            Class<?> mapWorldClass = Class.forName("xaero.map.world.MapWorld");
            try {
                mapWorldGetTileMethod = mapWorldClass.getMethod("getTile", int.class, int.class);
            } catch (NoSuchMethodException e) {
                mapWorldGetTileMethod = null;
            }

            // get save path for debugging
            try {
                Field saveField = mapProcessorClass.getDeclaredField("currentSavePath");
                saveField.setAccessible(true);
                // savePath is read lazily
            } catch (NoSuchFieldException ignored) {
            }

            LOGGER.info("XaeroProvider: reflection probes succeeded");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "XaeroProvider: reflection probing failed", e);
            xaeroAvailable = false;
        }
    }
}
