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
import net.minecraft.core.BlockPos;

import com.ecjkim.wayfarer.client.WayfarerConfig;
import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Direction;
import com.ecjkim.wayfarer.client.road.model.Node;
import com.ecjkim.wayfarer.client.road.model.Segment;
import com.ecjkim.wayfarer.client.road.model.Source;

public class RoadRecordingManager {
    private static final Logger LOGGER = Logger.getLogger("Wayfarer|RoadRecording");
    private static final double SAMPLE_DISTANCE_SQUARED = 0.5D * 0.5D;
    /** Threshold (blocks) for backtracking removal: 3x sample distance. */
    private static final double BACKTRACK_THRESHOLD = 1.5;

    private boolean recording;
    private long sessionStartedAt;
    private final List<double[]> sessionPoints = new ArrayList<>();

    public RoadRecordingManager() {}

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
        if (!recording)
            return;

        LocalPlayer player = client.player;
        if (player == null)
            return;

        // Use blockPosition() to get exact block coordinates (avoids Y offset issues)
        BlockPos blockPos = player.blockPosition();
        double x = blockPos.getX();
        double y = blockPos.getY();
        double z = blockPos.getZ();

        if (WayfarerConfig.getInstance().isAutoIntegral()) {
            x = Math.round(x);
            y = Math.round(y);
            z = Math.round(z);
        }

        if (!sessionPoints.isEmpty()) {
            double[] anchor = sessionPoints.get(sessionPoints.size() - 1);
            double dx = x - anchor[0];
            double dy = y - anchor[1];
            double dz = z - anchor[2];
            if (dx * dx + dy * dy + dz * dz < SAMPLE_DISTANCE_SQUARED)
                return;
        }

        sessionPoints.add(new double[] {x, y, z});
    }

    public Segment saveRecording() {
        if (sessionPoints.size() < 2) {
            LOGGER.log(Level.WARNING, "saveRecording called with only {0} point(s), need at least 2 — save aborted",
                sessionPoints.size());
            return null;
        }

        LOGGER.log(Level.INFO, "Saving recording with {0} points", sessionPoints.size());

        // 0. Copy
        List<double[]> copy = new ArrayList<>(sessionPoints);
        WayfarerConfig config = WayfarerConfig.getInstance();
        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        double epsilon = config.getRdpEpsilon();

        // 1. Snap endpoints (before simplification)
        UUID snappedStartId = null;
        UUID snappedEndId = null;
        if (config.isAutoSnapEndpoints()) {
            snappedStartId = snapPoint(copy.get(0), epsilon, db);
            if (snappedStartId != null) {
                Node sn = db.getNode(snappedStartId);
                copy.set(0, new double[] {sn.getX(), sn.getY(), sn.getZ()});
                LOGGER.log(Level.INFO, "Snapped start to node {0}", snappedStartId);
            }
            int last = copy.size() - 1;
            snappedEndId = snapPoint(copy.get(last), epsilon, db);
            if (snappedEndId != null) {
                Node en = db.getNode(snappedEndId);
                copy.set(last, new double[] {en.getX(), en.getY(), en.getZ()});
                LOGGER.log(Level.INFO, "Snapped end to node {0}", snappedEndId);
            }
        }

        // 2. Simplify
        List<double[]> simplified = RoadSimplifier.simplify(copy, BACKTRACK_THRESHOLD, epsilon);
        LOGGER.log(Level.INFO, "Simplified {0} → {1} points (epsilon={2})",
            new Object[] {sessionPoints.size(), simplified.size(), epsilon});

        // 3. Convert to Nodes — reuse snapped nodes for first/last position
        long now = System.currentTimeMillis();
        List<UUID> nodeIds = new ArrayList<>();
        for (int i = 0; i < simplified.size(); i++) {
            if (i == 0 && snappedStartId != null) {
                nodeIds.add(snappedStartId);
            } else if (i == simplified.size() - 1 && snappedEndId != null) {
                nodeIds.add(snappedEndId);
            } else {
                double[] pt = simplified.get(i);
                Node node = new Node(UUID.randomUUID(), pt[0], pt[1], pt[2], Source.USER, 1, now);
                db.addNode(node);
                nodeIds.add(node.getId());
            }
        }

        // 4. Create Segment
        Segment segment = new Segment(UUID.randomUUID(), nodeIds, null, Source.USER, Direction.BIDIRECTIONAL, 1);
        db.addSegment(segment);

        sessionPoints.clear();
        return segment;
    }

    /**
     * Snap a point to the road network.
     *
     * <p>
     * Level 1: find an existing Node within {@code epsilon} (XZ plane). Level 2: find a Segment whose edge has a
     * perpendicular foot within {@code epsilon} and t ∈ [0,1], then insert a new Node at the foot and split the edge.
     * Returns the snapped Node UUID, or {@code null} if no snap target found (caller creates a new Node).
     * </p>
     */
    private static UUID snapPoint(double[] pt, double epsilon, RoadNetworkDatabase db) {
        double x = pt[0];
        double y = pt[1];
        double z = pt[2];

        // Level 1: closest existing node
        Node closestNode = null;
        double closestDist = epsilon;
        for (Node node : db.getAllNodes()) {
            double dx = x - node.getX();
            double dz = z - node.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < closestDist) {
                closestDist = d;
                closestNode = node;
            }
        }
        if (closestNode != null) {
            return closestNode.getId();
        }

        // Level 2: closest segment edge with foot on edge
        double bestDist = epsilon;
        Segment bestSeg = null;
        int bestInsertAfter = -1;
        double bestFootX = 0;
        double bestFootY = 0;
        double bestFootZ = 0;

        for (Segment seg : db.getAllSegments()) {
            List<Node> segNodes = db.getNodesForSegment(seg.getId());
            for (int i = 0; i < segNodes.size() - 1; i++) {
                Node a = segNodes.get(i);
                Node b = segNodes.get(i + 1);
                double dx2 = b.getX() - a.getX();
                double dz2 = b.getZ() - a.getZ();
                double lenSq = dx2 * dx2 + dz2 * dz2;
                if (lenSq == 0)
                    continue;
                double t = ((x - a.getX()) * dx2 + (z - a.getZ()) * dz2) / lenSq;
                if (t < 0 || t > 1)
                    continue;
                double projX = a.getX() + t * dx2;
                double projZ = a.getZ() + t * dz2;
                double pdx = x - projX;
                double pdz = z - projZ;
                double d = Math.sqrt(pdx * pdx + pdz * pdz);
                if (d < bestDist) {
                    bestDist = d;
                    bestSeg = seg;
                    bestInsertAfter = i;
                    bestFootX = projX;
                    bestFootY = a.getY() + t * (b.getY() - a.getY());
                    bestFootZ = projZ;
                }
            }
        }

        if (bestSeg != null) {
            long now = System.currentTimeMillis();
            Node newNode = new Node(UUID.randomUUID(), bestFootX, bestFootY, bestFootZ, Source.USER, 1, now);
            db.addNode(newNode);
            List<UUID> newIds = new ArrayList<>(bestSeg.getNodeIds());
            newIds.add(bestInsertAfter + 1, newNode.getId());
            bestSeg.setNodeIds(newIds);
            bestSeg.setVersion(bestSeg.getVersion() + 1);
            bestSeg.setModifiedAt(now);
            return newNode.getId();
        }

        return null;
    }
}
