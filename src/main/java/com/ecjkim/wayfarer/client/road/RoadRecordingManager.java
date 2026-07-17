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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import com.ecjkim.wayfarer.client.road.model.RoadIntersection;
import com.ecjkim.wayfarer.client.road.model.RoadPath;
import com.ecjkim.wayfarer.client.road.model.RoadPoint;

public class RoadRecordingManager {
    private static final Logger LOGGER = Logger.getLogger("Wayfarer|RoadRecording");
    private static final double SAMPLE_DISTANCE_SQUARED = 0.5D * 0.5D;
    private static final double MIN_ANGLE_DEGREES = 60.0;

    private final RoadDataStore roadDataStore;

    private boolean recording;
    private long sessionStartedAt;
    private final List<RoadPoint> sessionPoints = new ArrayList<>();

    private boolean appendMode;
    private RoadPath appendRoad;
    private int appendEndpoint = -1; // 0=start, 1=end, -1=none
    private boolean appendWaitingForAngle;

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
        if (appendMode) {
            appendMode = false;
            appendRoad = null;
            appendEndpoint = -1;
            appendWaitingForAngle = false;
        }
    }

    public boolean isAppending() {
        return appendMode;
    }

    public String getAppendRoadName() {
        return appendRoad != null && appendRoad.name != null ? appendRoad.name : "";
    }

    public double getAppendRoadWidth() {
        return appendRoad != null ? appendRoad.width : 7.0;
    }

    public String getAppendRoadClassification() {
        return appendRoad != null ? appendRoad.classification : "";
    }

    public String getAppendRoadNumber() {
        return appendRoad != null ? appendRoad.number : "";
    }

    public void startAppend(RoadPath road, double playerX, double playerY, double playerZ) {
        this.appendMode = true;
        this.recording = true;
        this.appendRoad = road;
        long now = System.currentTimeMillis();
        this.sessionStartedAt = now;
        this.sessionPoints.clear();

        for (RoadPoint p : road.points) {
            this.sessionPoints.add(new RoadPoint(p.x, p.y, p.z, p.tick));
        }

        if (road.points.isEmpty()) {
            this.appendEndpoint = -1;
            this.appendWaitingForAngle = false;
            this.sessionPoints.add(new RoadPoint(playerX, playerY, playerZ, 0L));
            return;
        }

        RoadPoint first = road.points.get(0);
        RoadPoint last = road.points.get(road.points.size() - 1);
        double distToFirst = dist3D(playerX, playerY, playerZ, first.x, first.y, first.z);
        double distToLast = dist3D(playerX, playerY, playerZ, last.x, last.y, last.z);
        double width = road.width;

        if (distToFirst <= width && (road.points.size() < 2 || distToFirst <= distToLast)) {
            this.appendEndpoint = 0;
            this.appendWaitingForAngle = true;
        } else if (distToLast <= width) {
            this.appendEndpoint = 1;
            this.appendWaitingForAngle = true;
        } else if (isWithinRoadPath(playerX, playerY, playerZ, road)) {
            RoadPoint closest = findClosestPointOnPath(playerX, playerY, playerZ, road);
            closest.tick = 0L;
            this.sessionPoints.add(closest);
            this.sessionPoints.add(new RoadPoint(playerX, playerY, playerZ, now - sessionStartedAt));
            this.appendEndpoint = -1;
            this.appendWaitingForAngle = false;
        } else {
            this.sessionPoints.add(new RoadPoint(playerX, playerY, playerZ, 0L));
            this.appendEndpoint = -1;
            this.appendWaitingForAngle = false;
        }
    }

    public void finishAppend(String name, double width, String classification, String number) {
        if (!appendMode || appendRoad == null)
            return;

        roadDataStore.deleteRoad(appendRoad.id);

        RoadPath updated = new RoadPath();
        updated.id = appendRoad.id;
        updated.name = name;
        updated.width = width;
        updated.classification = classification;
        updated.number = number;
        updated.points = new ArrayList<>(sessionPoints);
        updated.intersections = detectIntersections(updated);

        roadDataStore.addRoad(updated);

        sessionPoints.clear();
        appendMode = false;
        appendRoad = null;
        appendEndpoint = -1;
        appendWaitingForAngle = false;
    }

    public void tick(Minecraft client) {
        if (!recording)
            return;

        LocalPlayer player = client.player;
        if (player == null)
            return;

        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();

        if (appendWaitingForAngle) {
            checkAngleAndMaybeStart(x, y, z);
            return;
        }

        if (!sessionPoints.isEmpty()) {
            RoadPoint anchor;
            if (appendMode && appendEndpoint == 0) {
                anchor = sessionPoints.get(0);
            } else {
                anchor = sessionPoints.get(sessionPoints.size() - 1);
            }
            double dx = x - anchor.x;
            double dy = y - anchor.y;
            double dz = z - anchor.z;
            if (dx * dx + dy * dy + dz * dz < SAMPLE_DISTANCE_SQUARED)
                return;
        }

        RoadPoint newPoint = new RoadPoint(x, y, z, System.currentTimeMillis() - sessionStartedAt);
        if (appendMode && appendEndpoint == 0) {
            sessionPoints.add(0, newPoint);
        } else {
            sessionPoints.add(newPoint);
        }
    }

    public void saveRecording(String roadName, double width, String classification, String number) {
        if (sessionPoints.size() < 2) {
            LOGGER.log(Level.WARNING,
                "saveRecording called with only {0} point(s), need at least 2 — save aborted",
                sessionPoints.size());
            return;
        }

        LOGGER.log(Level.INFO, "Saving road \"{0}\" with {1} points (width={2}, class={3}, number={4})",
            new Object[] {roadName, sessionPoints.size(), width, classification, number});
        roadDataStore.syncToCurrentContext();

        RoadPath road = new RoadPath();
        road.id = UUID.randomUUID().toString();
        road.name = roadName;
        road.width = width;
        road.classification = classification;
        road.number = number;
        road.points = new ArrayList<>(sessionPoints);
        road.intersections = detectIntersections(road);

        roadDataStore.addRoad(road);
        sessionPoints.clear();
    }

    // --- append helpers ---

    private void checkAngleAndMaybeStart(double px, double py, double pz) {
        if (appendEndpoint == 0 && sessionPoints.size() < 2)
            return;
        if (appendEndpoint == 1 && sessionPoints.size() < 2)
            return;

        RoadPoint endpoint;
        RoadPoint secondToLast;
        if (appendEndpoint == 0) {
            endpoint = sessionPoints.get(0);
            secondToLast = sessionPoints.get(1);
        } else {
            int last = sessionPoints.size() - 1;
            endpoint = sessionPoints.get(last);
            secondToLast = sessionPoints.get(last - 1);
        }

        double vx = px - endpoint.x;
        double vy = py - endpoint.y;
        double vz = pz - endpoint.z;
        double vLen = Math.sqrt(vx * vx + vy * vy + vz * vz);

        double ix = endpoint.x - secondToLast.x;
        double iy = endpoint.y - secondToLast.y;
        double iz = endpoint.z - secondToLast.z;
        double iLen = Math.sqrt(ix * ix + iy * iy + iz * iz);

        if (vLen < 0.001 || iLen < 0.001)
            return;

        double dot = vx * ix + vy * iy + vz * iz;
        double cosAngle = dot / (vLen * iLen);
        double angleDeg = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cosAngle))));

        if (angleDeg < MIN_ANGLE_DEGREES)
            return;

        appendWaitingForAngle = false;
        RoadPoint newPoint = new RoadPoint(px, py, pz, System.currentTimeMillis() - sessionStartedAt);
        if (appendEndpoint == 0) {
            sessionPoints.add(0, newPoint);
        } else {
            sessionPoints.add(newPoint);
        }
    }

    private boolean isWithinRoadPath(double px, double py, double pz, RoadPath road) {
        double halfWidth = road.width / 2.0;
        for (int i = 0; i < road.points.size() - 1; i++) {
            RoadPoint a = road.points.get(i);
            RoadPoint b = road.points.get(i + 1);
            RoadPoint closest = Geometry.closestPointOnSegment(new RoadPoint(px, py, pz, 0L), a, b);
            double dx = px - closest.x;
            double dy = py - closest.y;
            double dz = pz - closest.z;
            if (dx * dx + dy * dy + dz * dz <= halfWidth * halfWidth) {
                return true;
            }
        }
        return false;
    }

    private RoadPoint findClosestPointOnPath(double px, double py, double pz, RoadPath road) {
        RoadPoint best = null;
        double bestDist = Double.MAX_VALUE;
        RoadPoint playerPoint = new RoadPoint(px, py, pz, 0L);
        for (int i = 0; i < road.points.size() - 1; i++) {
            RoadPoint a = road.points.get(i);
            RoadPoint b = road.points.get(i + 1);
            RoadPoint closest = Geometry.closestPointOnSegment(playerPoint, a, b);
            double dx = px - closest.x;
            double dy = py - closest.y;
            double dz = pz - closest.z;
            double dist = dx * dx + dy * dy + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                best = closest;
            }
        }
        return best != null ? best : road.points.get(0);
    }

    private static double dist3D(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
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
