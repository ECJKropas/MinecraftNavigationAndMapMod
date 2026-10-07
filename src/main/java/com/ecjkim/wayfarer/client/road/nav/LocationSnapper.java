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
package com.ecjkim.wayfarer.client.road.nav;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Node;
import com.ecjkim.wayfarer.client.road.model.Segment;
import com.ecjkim.wayfarer.client.road.spatial.NodeSpatialIndex;

/**
 * Snaps an arbitrary world position onto the closest node of the road network.
 *
 * <p>
 * Lookups go through a shared {@link NodeSpatialIndex} instead of a full scan, so their cost no longer scales with the
 * size of the network. The public API is unchanged. The index is rebuilt lazily whenever the database reports a
 * different node count or a new mutation stamp, which means edits coming from the web editor or from disk reloads are
 * picked up without any explicit invalidation call.
 * </p>
 */
public final class LocationSnapper {
    private static final NodeSpatialIndex INDEX = new NodeSpatialIndex();

    private static RoadNetworkDatabase indexedDatabase;
    private static NodeSpatialIndex.NodeSource source;

    private LocationSnapper() {}

    /**
     * Returns the network node closest to {@code (x, z)} within {@code radius} blocks, if any.
     */
    public static Optional<Node> nearestNode(RoadNetworkDatabase database, double x, double z, double radius) {
        return INDEX.nearest(sourceFor(database), x, z, radius);
    }

    /**
     * Returns up to {@code limit} network nodes within {@code radius} blocks, ordered by distance and then by id, so a
     * caller that cannot tell which of several nearby nodes is meant can offer all of them as candidates.
     *
     * <p>
     * The old single-node API is untouched: {@link #nearestNode(RoadNetworkDatabase, double, double, double)} is
     * exactly this query with {@code limit == 1}, including the tie rule.
     * </p>
     */
    public static List<Node> nearestNodes(RoadNetworkDatabase database, double x, double z, double radius, int limit) {
        List<Node> nodeHits = List.copyOf(INDEX.nearest(sourceFor(database), x, z, radius, limit));
        if (!nodeHits.isEmpty()) {
            return nodeHits;
        }
        // No node within the snap radius. The click may still land on a visible road line whose nodes are spaced far
        // apart, so project onto the nearest segment and snap to its endpoints instead of failing outright. This keeps
        // "tap anywhere on the road" working even for sparse networks.
        SegmentEndpoints endpoints = nearestSegmentEndpoints(database, x, z, radius);
        if (endpoints != null) {
            List<Node> fallback = new ArrayList<>(2);
            if (endpoints.a() != null) fallback.add(endpoints.a());
            if (endpoints.b() != null) fallback.add(endpoints.b());
            fallback.sort(Comparator.comparing(n -> n.getId() == null ? "" : n.getId().toString()));
            return fallback;
        }
        return List.of();
    }

    /**
     * Finds the road segment whose line passes closest to {@code (x, z)}, provided that distance is at most
     * {@code radius} blocks. Returns its two endpoint nodes, or {@code null} when nothing is that close.
     */
    private static SegmentEndpoints nearestSegmentEndpoints(RoadNetworkDatabase database, double x, double z,
        double radius) {
        double bestDistance = radius;
        SegmentEndpoints best = null;
        for (Segment segment : database.getAllSegments()) {
            List<Node> nodes = database.getNodesForSegment(segment.getId());
            if (nodes.size() < 2) {
                continue;
            }
            Node prev = nodes.get(0);
            for (int i = 1; i < nodes.size(); i++) {
                Node curr = nodes.get(i);
                double d = distanceToSegment(x, z, prev, curr);
                if (d <= bestDistance) {
                    bestDistance = d;
                    best = new SegmentEndpoints(prev, curr);
                }
                prev = curr;
            }
        }
        return best;
    }

    private static double distanceToSegment(double px, double pz, Node a, Node b) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double len2 = dx * dx + dz * dz;
        if (len2 < 1e-9) {
            return Math.hypot(px - a.getX(), pz - a.getZ());
        }
        double t = ((px - a.getX()) * dx + (pz - a.getZ()) * dz) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        double cx = a.getX() + t * dx;
        double cz = a.getZ() + t * dz;
        return Math.hypot(px - cx, pz - cz);
    }

    private record SegmentEndpoints(Node a, Node b) {
    }

    private static synchronized NodeSpatialIndex.NodeSource sourceFor(RoadNetworkDatabase database) {
        if (source == null || indexedDatabase != database) {
            indexedDatabase = database;
            source = new DatabaseNodeSource(database);
            INDEX.invalidate();
        }
        return source;
    }

    /**
     * Adapts the road network database to the index contract: the node count and the mutation stamp are the cache keys.
     */
    private static final class DatabaseNodeSource implements NodeSpatialIndex.NodeSource {
        private final RoadNetworkDatabase database;

        private DatabaseNodeSource(RoadNetworkDatabase database) {
            this.database = database;
        }

        @Override
        public Collection<Node> nodes() {
            return database.getAllNodes();
        }

        @Override
        public int nodeCount() {
            return database.getNodeCount();
        }

        @Override
        public long revision() {
            return database.getMutationStamp();
        }
    }
}
