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
package com.cjkim.mcnav.client.road;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import com.cjkim.mcnav.client.road.model.RoadIntersection;
import com.cjkim.mcnav.client.road.model.RoadPath;
import com.cjkim.mcnav.client.road.model.RoadPoint;

public class RoadRecordingManager {
    private static final double SAMPLE_DISTANCE_SQUARED = 0.5D * 0.5D;

    private final RoadDataStore roadDataStore;

    private boolean recording;
    private long sessionStartedAt;
    private final List<RoadPoint> sessionPoints = new ArrayList<>();

    public RoadRecordingManager(RoadDataStore roadDataStore) {
        this.roadDataStore = roadDataStore;
    }

    public boolean isRecording() {
        return recording;
    }

    public int getRecordedPointCount() {
        return sessionPoints.size();
    }

    public void startRecording() {
        recording = true;
        sessionStartedAt = System.currentTimeMillis();
        sessionPoints.clear();
    }

    public void stopRecording() {
        recording = false;
    }

    public void discardRecording() {
        sessionPoints.clear();
    }

    public void tick(Minecraft client) {
        if (!recording) {
            return;
        }

        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }

        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();

        if (!sessionPoints.isEmpty()) {
            RoadPoint lastPoint = sessionPoints.get(sessionPoints.size() - 1);
            double deltaX = x - lastPoint.x;
            double deltaY = y - lastPoint.y;
            double deltaZ = z - lastPoint.z;
            double distanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
            if (distanceSquared < SAMPLE_DISTANCE_SQUARED) {
                return;
            }
        }

        sessionPoints.add(new RoadPoint(x, y, z, System.currentTimeMillis() - sessionStartedAt));
    }

    public void saveRecording(String roadName, double width) {
        if (sessionPoints.size() < 2) {
            return;
        }

        roadDataStore.syncToCurrentContext();

        RoadPath road = new RoadPath();
        road.id = UUID.randomUUID().toString();
        road.name = roadName;
        road.width = width;
        road.points = new ArrayList<>(sessionPoints);
        road.intersections = detectIntersections(road);

        roadDataStore.addRoad(road);
        sessionPoints.clear();
    }

    private List<RoadIntersection> detectIntersections(RoadPath newRoad) {
        List<RoadIntersection> intersections = new ArrayList<>();

        for (RoadPath existingRoad : roadDataStore.getRoads()) {
            double threshold = Math.max(1.0D, (newRoad.width + existingRoad.width) / 2.0D);
            for (RoadPoint point : newRoad.points) {
                RoadPoint nearestPoint = null;
                double nearestDistanceSquared = Double.MAX_VALUE;

                for (int i = 0; i < existingRoad.points.size() - 1; i++) {
                    RoadPoint a = existingRoad.points.get(i);
                    RoadPoint b = existingRoad.points.get(i + 1);
                    RoadPoint projected = Geometry.closestPointOnSegment(point, a, b);
                    double dx = point.x - projected.x;
                    double dz = point.z - projected.z;
                    double distanceSquared = dx * dx + dz * dz;
                    if (distanceSquared < nearestDistanceSquared) {
                        nearestDistanceSquared = distanceSquared;
                        nearestPoint = projected;
                    }
                }

                if (nearestPoint != null && nearestDistanceSquared <= threshold * threshold) {
                    RoadIntersection intersection = new RoadIntersection();
                    intersection.roadId = existingRoad.id;
                    intersection.roadName = existingRoad.name;
                    intersection.x = nearestPoint.x;
                    intersection.y = nearestPoint.y;
                    intersection.z = nearestPoint.z;
                    intersections.add(intersection);
                    break;
                }
            }
        }

        return intersections;
    }
}
