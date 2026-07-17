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
package com.ecjkim.wayfarer.client.road.layer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central registry for all map layers.
 *
 * <p>Four built-in layers are registered eagerly:
 * {@code xaero_base (0), road_network (100), administrative (200), poi (300)}.</p>
 */
public class LayerManager {
    private final Map<String, MapLayer> layers = new LinkedHashMap<>();

    public LayerManager() {
        registerBuiltin(new BuiltinLayer("xaero_base", "Xaero 世界地图", 0, true));
        registerBuiltin(new BuiltinLayer("road_network", "道路路网", 100, true));
        registerBuiltin(new BuiltinLayer("administrative", "行政区域", 200, true));
        registerBuiltin(new BuiltinLayer("poi", "兴趣点", 300, true));
    }

    /**
     * Register an external layer. Overwrites any existing layer with the same id.
     */
    public synchronized void register(MapLayer layer) {
        layers.put(layer.getId(), layer);
    }

    /**
     * Remove a layer by id.
     */
    public synchronized void unregister(String layerId) {
        // Never allow removal of the four built-in layers
        if ("xaero_base".equals(layerId) || "road_network".equals(layerId)
            || "administrative".equals(layerId) || "poi".equals(layerId)) {
            return;
        }
        layers.remove(layerId);
    }

    /**
     * Returns all layers sorted by z-index ascending.
     */
    public synchronized List<MapLayer> getAllLayers() {
        List<MapLayer> list = new ArrayList<>(layers.values());
        list.sort(Comparator.comparingInt(MapLayer::getZIndex));
        return Collections.unmodifiableList(list);
    }

    /**
     * Look up a specific layer by id, or {@code null}.
     */
    public synchronized MapLayer getLayer(String layerId) {
        return layers.get(layerId);
    }

    /**
     * Toggle visibility of a layer by id.
     */
    public synchronized void setVisible(String layerId, boolean visible) {
        MapLayer layer = layers.get(layerId);
        if (layer != null) {
            layer.setVisible(visible);
        }
    }

    public synchronized boolean isVisible(String layerId) {
        MapLayer layer = layers.get(layerId);
        return layer != null && layer.isVisible();
    }

    private void registerBuiltin(BuiltinLayer layer) {
        layers.put(layer.getId(), layer);
    }

    // ------------------------------------------------------------------
    // Built-in layer implementation
    // ------------------------------------------------------------------

    private static final class BuiltinLayer implements MapLayer {
        private final String id;
        private final String displayName;
        private final int zIndex;
        private boolean visible;

        BuiltinLayer(String id, String displayName, int zIndex, boolean visible) {
            this.id = id;
            this.displayName = displayName;
            this.zIndex = zIndex;
            this.visible = visible;
        }

        @Override
        public String getId() { return id; }

        @Override
        public String getDisplayName() { return displayName; }

        @Override
        public int getZIndex() { return zIndex; }

        @Override
        public boolean isVisible() { return visible; }

        @Override
        public void setVisible(boolean visible) { this.visible = visible; }
    }
}
