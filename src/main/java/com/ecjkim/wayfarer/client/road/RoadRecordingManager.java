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

import com.ecjkim.wayfarer.client.WayfarerConfig;
import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.CornerType;
import com.ecjkim.wayfarer.client.road.model.Node;
import com.ecjkim.wayfarer.client.road.model.Segment;
import com.ecjkim.wayfarer.client.road.model.Source;
import com.ecjkim.wayfarer.client.road.model.Status;

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

        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();

        if (WayfarerConfig.getInstance().autoIntegral) {
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

        // 1. Simplify
        List<double[]> copy = new ArrayList<>(sessionPoints);
        WayfarerConfig config = WayfarerConfig.getInstance();
        double epsilon = config.rdpEpsilon;
        List<double[]> simplified = RoadSimplifier.simplify(copy, BACKTRACK_THRESHOLD, epsilon);
        LOGGER.log(Level.INFO, "Simplified {0} → {1} points (epsilon={2})",
            new Object[] {sessionPoints.size(), simplified.size(), epsilon});

        // 2. Convert to Nodes
        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        long now = System.currentTimeMillis();
        List<UUID> nodeIds = new ArrayList<>();
        for (double[] pt : simplified) {
            Node node = new Node(UUID.randomUUID(), pt[0], pt[1], pt[2], CornerType.AUTO, Source.USER, 1, now);
            db.addNode(node);
            nodeIds.add(node.getId());
        }

        // 3. Create Segment
        Segment segment = new Segment(UUID.randomUUID(), nodeIds, null, Source.USER, Status.CONFIRMED, 1);
        db.addSegment(segment);

        sessionPoints.clear();
        return segment;
    }
}
