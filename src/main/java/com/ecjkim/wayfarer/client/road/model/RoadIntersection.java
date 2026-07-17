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
package com.ecjkim.wayfarer.client.road.model;

import java.util.List;

/**
 * Upgraded intersection model that keeps legacy flat fields
 * ({@code roadId}, {@code roadName}, {@code x}, {@code y}, {@code z})
 * for backward-compatibility while adding the structured
 * {@code id} / {@code position} / {@code type} / {@code connectedSegments}
 * / {@code name} fields used by the new GeoJSON + Leaflet frontend.
 */
public class RoadIntersection {
    // ---- legacy fields (kept for deserialization of old roads.json) ----
    public String roadId;
    public String roadName;
    public double x;
    public double y;
    public double z;

    // ---- new structured fields (optional, may be null in legacy data) ----
    /** Unique intersection id, e.g. "int-uuid-1". */
    public String id;
    /** Structured 3-D position. */
    public IntersectionPosition position;
    /** Intersection type: cross, t-junction, roundabout, etc. */
    public String type;
    /** IDs of connected RoadSegments. */
    public List<String> connectedSegments;
    /** Human-readable intersection name, e.g. "城南立交". */
    public String name;
}
