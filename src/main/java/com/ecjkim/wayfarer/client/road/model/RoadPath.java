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

import java.util.ArrayList;
import java.util.List;

/**
 * A recorded road path with optional classification metadata.
 *
 * <p>
 * All new fields ({@code segments}, {@code style}) are {@code null} by default so that existing {@code roads.json}
 * files deserialize without error (backward-compatible).
 * </p>
 */
public class RoadPath {
    public String id;
    public String name;
    public double width;
    /** G=国道, S=省道, X=县道, Y=乡道, C=村道. Defaults to C when missing. */
    public String classification;
    /** Road number, e.g. "318". */
    public String number;
    public List<RoadPoint> points = new ArrayList<>();
    /** Upgraded intersection model — may be null in legacy data. */
    public List<RoadIntersection> intersections = new ArrayList<>();
    /** Road sub-sections bounded by intersections — optional. */
    public List<RoadSegment> segments;
    /** Per-road visual style override — optional. */
    public RoadStyle style;
}
