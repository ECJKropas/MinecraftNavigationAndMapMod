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

/**
 * A single map layer that can be toggled on/off in both the browser and in-game UIs.
 */
public interface MapLayer {
    /** Unique identifier, e.g. "road_network". */
    String getId();

    /** Human-readable display name, e.g. "道路路网". */
    String getDisplayName();

    /**
     * Stacking order — higher values render on top.
     *
     * <p>
     * Built-in convention:
     * <ul>
     * <li>0 — xaero_base</li>
     * <li>100 — road_network</li>
     * <li>200 — administrative</li>
     * <li>300 — poi</li>
     * </ul>
     * </p>
     */
    int getZIndex();

    /** Whether this layer is currently visible. */
    boolean isVisible();

    /** Toggle visibility. */
    void setVisible(boolean visible);
}
