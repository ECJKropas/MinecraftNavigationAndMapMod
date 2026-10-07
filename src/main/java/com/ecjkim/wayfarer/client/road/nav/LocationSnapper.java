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

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Node;
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
        return List.copyOf(INDEX.nearest(sourceFor(database), x, z, radius, limit));
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
