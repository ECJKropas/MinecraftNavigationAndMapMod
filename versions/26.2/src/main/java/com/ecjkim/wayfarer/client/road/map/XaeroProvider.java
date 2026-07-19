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

import net.minecraft.world.level.ChunkPos;

/**
 * 26.x override: screen field is private; use getScreen().
 */
public class XaeroProvider implements MapProvider {

    private static final Logger LOGGER = Logger.getLogger("Wayfarer|XaeroProvider");
    private static final int TILE_SIZE = 256;

    private boolean reflectionProbed;
    private boolean xaeroAvailable;
    private Field guiMapProcessorField;
    private Method mapProcessorGetWorldMethod;
    private Method mapWorldGetTileMethod;

    public XaeroProvider() {}

    @Override
    public boolean isAvailable() {
        probeReflection();
        return xaeroAvailable;
    }

    @Override
    public BufferedImage getTile(int dimension, int zoom, int tileX, int tileY) {
        if (!isAvailable())
            return null;
        probeReflection();
        if (guiMapProcessorField == null)
            return null;

        try {
            // reflect read GuiMap.mapProcessor -> MapProcessor -> MapWorld -> tile cache
            // GuiMap is runtime-accessible via Xaero's own instance; we probe the
            // tile cache path directly. If GuiMap is not open, the reflection chain
            // simply returns null (no crash).
            return null; // TODO: wire to actual GuiMap instance when available
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Xaero tile reflection failed", e);
            return null;
        }
    }

    @Override
    public void invalidate(ChunkPos chunk) {}

    @Override
    public String getName() {
        return "Xaero";
    }

    private void probeReflection() {
        if (reflectionProbed)
            return;
        reflectionProbed = true;

        try {
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

            Class<?> mapProcessorClass = Class.forName("xaero.map.MapProcessor");
            try {
                mapProcessorGetWorldMethod = mapProcessorClass.getMethod("getMapWorld");
            } catch (NoSuchMethodException e) {
                Field worldField = mapProcessorClass.getDeclaredField("mapWorld");
                worldField.setAccessible(true);
                mapProcessorGetWorldMethod = null;
            }

            Class<?> mapWorldClass = Class.forName("xaero.map.world.MapWorld");
            try {
                mapWorldGetTileMethod = mapWorldClass.getMethod("getTile", int.class, int.class);
            } catch (NoSuchMethodException e) {
                mapWorldGetTileMethod = null;
            }

            LOGGER.info("XaeroProvider: reflection probes succeeded");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "XaeroProvider: reflection probing failed", e);
            xaeroAvailable = false;
        }
    }
}
