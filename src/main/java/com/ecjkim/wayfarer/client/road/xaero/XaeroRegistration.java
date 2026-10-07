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
package com.ecjkim.wayfarer.client.road.xaero;

import xaero.map.WorldMap;
import xaero.map.element.MapElementRenderHandler;

/**
 * The only class in the mod that names Xaero's types.
 *
 * <p>
 * Kept apart from {@link XaeroLayer} so that the mod loads and runs with Xaero absent: this class is not resolved until
 * {@link #attempt()} is called, and that is only done once the mod has been found to be installed.
 */
final class XaeroRegistration {

    private XaeroRegistration() {}

    /**
     * Hands the road layer to Xaero.
     *
     * @return false while Xaero has not finished initialising, in which case the caller should try again later
     */
    static boolean attempt() {
        MapElementRenderHandler handler = WorldMap.mapElementRenderHandler;
        if (handler == null) {
            return false;
        }
        handler.add(new XaeroRoadRenderer(new XaeroRoadContext(), new XaeroRoadProvider(), new XaeroRoadReader()));
        handler.add(new XaeroRouteRenderer(new XaeroRoadContext(), new XaeroRouteProvider(), new XaeroRouteReader()));
        return true;
    }
}
