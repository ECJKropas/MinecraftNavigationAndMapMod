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
package com.ecjkim.wayfarer.client.road;

import com.ecjkim.wayfarer.client.road.model.RoadPoint;

public final class Geometry {
    private Geometry() {}

    public static RoadPoint closestPointOnSegment(RoadPoint point, RoadPoint start, RoadPoint end) {
        double segmentX = end.x - start.x;
        double segmentY = end.y - start.y;
        double segmentZ = end.z - start.z;
        double lengthSquared = segmentX * segmentX + segmentY * segmentY + segmentZ * segmentZ;
        if (lengthSquared == 0.0D) {
            return start;
        }

        double projection =
            ((point.x - start.x) * segmentX + (point.y - start.y) * segmentY + (point.z - start.z) * segmentZ)
                / lengthSquared;
        double clamped = Math.max(0.0D, Math.min(1.0D, projection));
        return new RoadPoint(start.x + clamped * segmentX, start.y + clamped * segmentY, start.z + clamped * segmentZ,
            point.tick);
    }
}
