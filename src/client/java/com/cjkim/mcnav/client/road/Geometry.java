package com.cjkim.mcnav.client.road;

import com.cjkim.mcnav.client.road.model.RoadPoint;

public final class Geometry {
    private Geometry() {
    }

    public static RoadPoint closestPointOnSegment(RoadPoint point, RoadPoint start, RoadPoint end) {
        double segmentX = end.x - start.x;
        double segmentY = end.y - start.y;
        double segmentZ = end.z - start.z;
        double lengthSquared = segmentX * segmentX + segmentY * segmentY + segmentZ * segmentZ;
        if (lengthSquared == 0.0D) {
            return start;
        }

        double projection = ((point.x - start.x) * segmentX + (point.y - start.y) * segmentY + (point.z - start.z) * segmentZ) / lengthSquared;
        double clamped = Math.max(0.0D, Math.min(1.0D, projection));
        return new RoadPoint(
                start.x + clamped * segmentX,
                start.y + clamped * segmentY,
                start.z + clamped * segmentZ,
                point.tick
        );
    }
}
