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
import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.level.ChunkPos;

/**
 * Manages all MapProvider instances and routes tile requests. Uses priority-based routing: first available provider
 * wins.
 */
public class ProviderManager {

    public enum Mode {
        /** Auto-detect: prefer XaeroProvider, fallback to SelfBuiltProvider. */
        AUTO,
        /** Force XaeroProvider only (returns null tile if Xaero unavailable). */
        XAERO_ONLY,
        /** Force SelfBuiltProvider only. */
        SELF_BUILT,
    }

    private final List<MapProvider> providers = new ArrayList<>();
    private final MapProvider nullProvider = new NullProvider();
    private volatile Mode mode;

    public ProviderManager() {
        this(Mode.AUTO);
    }

    public ProviderManager(Mode mode) {
        this.mode = mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Mode getMode() {
        return mode;
    }

    private volatile String lastServingProvider = null;

    public String getActiveProviderName() {
        if (lastServingProvider != null)
            return lastServingProvider;
        for (MapProvider provider : providers) {
            if (isEnabled(provider) && provider.isAvailable())
                return provider.getName();
        }
        return "None";
    }

    public void add(MapProvider provider) {
        providers.add(provider);
    }

    public void addProvider(MapProvider provider) {
        providers.add(provider);
    }

    public void clearProviders() {
        providers.clear();
    }

    /**
     * Find the first available provider and delegate. Returns a 256x256 transparent placeholder if no provider is
     * available.
     */
    public BufferedImage getTile(int dimension, int zoom, int tileX, int tileY) {
        for (MapProvider provider : providers) {
            if (!isEnabled(provider))
                continue;
            if (provider.isAvailable()) {
                BufferedImage tile = provider.getTile(dimension, zoom, tileX, tileY);
                if (tile != null) {
                    lastServingProvider = provider.getName();
                    return tile;
                }
            }
        }
        return null;
    }

    public void invalidate(ChunkPos chunk) {
        for (MapProvider provider : providers) {
            if (!isEnabled(provider))
                continue;
            provider.invalidate(chunk);
        }
    }

    /**
     * Request async rendering of a tile from all providers. Used when a tile is not yet cached — the HTTP handler calls
     * this and returns a transparent placeholder, then the providers render the tile in the background.
     */
    public void requestTileRender(int dimension, int tileX, int tileY) {
        requestTileRender(dimension, 0, tileX, tileY);
    }

    /** Zoom-aware variant. */
    public void requestTileRender(int dimension, int zoom, int tileX, int tileY) {
        for (MapProvider provider : providers) {
            if (!isEnabled(provider))
                continue;
            if (provider.isAvailable()) {
                provider.requestTileRender(dimension, zoom, tileX, tileY);
            }
        }
    }

    /** Shutdown all providers (e.g. thread pools). */
    public void shutdown() {
        for (MapProvider provider : providers) {
            if (provider instanceof SelfBuiltProvider) {
                ((SelfBuiltProvider)provider).shutdown();
            }
        }
    }

    private boolean isEnabled(MapProvider provider) {
        return switch (mode) {
            case AUTO -> true;
            case XAERO_ONLY -> provider instanceof XaeroProvider;
            case SELF_BUILT -> provider instanceof SelfBuiltProvider;
        };
    }

    /** Fallback provider that always returns a transparent 256x256 tile. */
    private static class NullProvider implements MapProvider {
        private BufferedImage emptyTile;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public BufferedImage getTile(int dimension, int zoom, int tileX, int tileY) {
            if (emptyTile == null) {
                emptyTile = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
            }
            return emptyTile;
        }

        @Override
        public void invalidate(ChunkPos chunk) {}

        @Override
        public String getName() {
            return "Null";
        }
    }
}
