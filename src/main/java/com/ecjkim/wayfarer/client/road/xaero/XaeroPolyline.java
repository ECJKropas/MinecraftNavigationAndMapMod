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

/**
 * The flat, immutable polyline shape that {@link XaeroRoadStroke} projects and paints.
 *
 * <p>
 * Both {@link XaeroRoadElement} (the cached road network) and {@link XaeroRouteElement} (the live navigation route)
 * carry the same geometry accessors, so the projection lives in exactly one place: this interface. Drawing a new kind
 * of line on the map is therefore a matter of implementing this and pointing a renderer's {@link XaeroRoadStroke} at it
 * -- no duplication of the world-to-screen maths.
 */
public interface XaeroPolyline {

    int vertexCount();

    double x(int index);

    double z(int index);

    int color();

    float widthBlocks();

    double anchorX();

    double anchorZ();
}
