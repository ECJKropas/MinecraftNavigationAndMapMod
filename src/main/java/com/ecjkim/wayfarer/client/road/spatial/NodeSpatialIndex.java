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
package com.ecjkim.wayfarer.client.road.spatial;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

import com.ecjkim.wayfarer.client.road.model.Node;

/**
 * Uniform-grid spatial index over the nodes of the road network.
 *
 * <p>
 * It replaces the full node scan that {@code LocationSnapper} used to perform: a query only visits the grid cells
 * overlapping the search radius, expanding ring by ring and stopping as soon as the remaining rings cannot hold a
 * closer node. Cost per query therefore stops scaling with the size of the network.
 * </p>
 *
 * <p>
 * The index is a pure cache and never owns the data. Callers hand in a {@link NodeSource}; the index rebuilds itself
 * lazily when either {@link NodeSource#revision()} or {@link NodeSource#nodeCount()} reports a change. A mutation that
 * keeps the node count stable (moving a node in place) <b>must</b> bump the revision, a mutation that adds or removes a
 * node is detected by the count alone.
 * </p>
 *
 * <p>
 * Every public method is {@code synchronized} so a single instance can be shared by the client thread and the HTTP
 * server thread.
 * </p>
 *
 * <p>
 * Lock order: a rebuild reads the {@link NodeSource} while the index lock is held, so the index lock must stay the
 * innermost one. Never call {@link #nearest(NodeSource, double, double, double)} while holding a monitor that the
 * source itself takes (for instance {@code RoadNetworkDatabase}'s), or the order inverts and two threads can deadlock.
 * </p>
 */
public final class NodeSpatialIndex {
    /**
     * Supplies the data set to index. Implementations must always expose every live node.
     */
    public interface NodeSource {
        /**
         * Returns all nodes. Only called when the index actually rebuilds, so a copying implementation is fine.
         */
        Collection<Node> nodes();

        /**
         * Returns the current number of nodes. Must be cheap (constant time).
         */
        int nodeCount();

        /**
         * Returns a monotonic counter that changes whenever an already indexed node is modified in place.
         */
        long revision();
    }

    /**
     * Default cell edge length in blocks. Road nodes sit a few blocks apart, so this keeps the buckets small without
     * wasting cells on sparse networks.
     */
    public static final double DEFAULT_CELL_SIZE = 32.0;

    /**
     * Upper bound for the ring count, so an absurd radius can never turn a query into an unbounded loop.
     */
    private static final int MAX_RING = 1_000_000;

    private final double cellSize;
    private final Map<Long, List<Node>> cells = new HashMap<>();

    private long builtRevision = Long.MIN_VALUE;
    private int builtCount = -1;
    private int indexedNodes;
    private int minCellX;
    private int maxCellX;
    private int minCellZ;
    private int maxCellZ;

    public NodeSpatialIndex() {
        this(DEFAULT_CELL_SIZE);
    }

    public NodeSpatialIndex(double cellSize) {
        if (!(cellSize > 0.0)) {
            throw new IllegalArgumentException("cellSize must be positive: " + cellSize);
        }
        this.cellSize = cellSize;
    }

    public double getCellSize() {
        return cellSize;
    }

    /**
     * Drops the cached grid. The next query rebuilds it. Call this when the underlying database instance changes.
     */
    public synchronized void invalidate() {
        builtCount = -1;
        builtRevision = Long.MIN_VALUE;
    }

    /**
     * Returns how many nodes the current grid holds.
     */
    public synchronized int indexedNodeCount() {
        return indexedNodes;
    }

    /**
     * Returns how many grid cells are occupied.
     */
    public synchronized int occupiedCellCount() {
        return cells.size();
    }

    /**
     * Returns the node closest to {@code (x, z)} within {@code radius} blocks, or an empty optional when there is none.
     *
     * <p>
     * Exactly equidistant nodes resolve to the node whose id sorts first, so the answer never depends on iteration
     * order or on insertion order.
     * </p>
     */
    public synchronized Optional<Node> nearest(NodeSource source, double x, double z, double radius) {
        List<Node> hits = nearest(source, x, z, radius, 1);
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }

    /**
     * Returns up to {@code limit} nodes within {@code radius} blocks, ordered by distance and then by id.
     *
     * <p>
     * The ring expansion and the distance pruning are shared with the single-node query: the loop stops early once the
     * closest possible point of the next ring is already farther than the {@code limit}-th hit, and exactly equidistant
     * nodes still resolve to the smaller id. Passing {@code limit <= 0} yields an empty list; passing
     * {@code limit == 1} is exactly {@link #nearest(NodeSource, double, double, double)}.
     * </p>
     */
    public synchronized List<Node> nearest(NodeSource source, double x, double z, double radius, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        rebuildIfStale(source);
        if (cells.isEmpty()) {
            return List.of();
        }

        int centerCellX = cellIndex(x);
        int centerCellZ = cellIndex(z);
        double radiusSquared = radius * radius;
        double bestSquared = radiusSquared;
        TreeSet<Hit> hits = new TreeSet<>(HIT_ORDER);

        int ringLimit = ringLimit(radius, centerCellX, centerCellZ);
        for (int ring = 0; ring <= ringLimit; ring++) {
            // The closest point of a ring is at least (ring - 1) cells away from the query position. Once the set is
            // full and that distance is already greater than the worst kept hit, no outer ring can improve on it. The
            // comparison is strict on purpose: a ring whose cells could sit at exactly the worst kept distance may
            // still hold an equidistant node with a smaller id, which the tie rule below prefers.
            if (hits.size() >= limit && ring > 0 && squared((ring - 1) * cellSize) > bestSquared) {
                break;
            }

            int fromX = Math.max(centerCellX - ring, minCellX);
            int toX = Math.min(centerCellX + ring, maxCellX);
            int fromZ = Math.max(centerCellZ - ring, minCellZ);
            int toZ = Math.min(centerCellZ + ring, maxCellZ);
            if (fromX > toX || fromZ > toZ) {
                continue;
            }

            for (int cellX = fromX; cellX <= toX; cellX++) {
                for (int cellZ = fromZ; cellZ <= toZ; cellZ++) {
                    if (ring > 0 && ringDistance(cellX, cellZ, centerCellX, centerCellZ) != ring) {
                        // Inner cells were already visited by an earlier, smaller ring.
                        continue;
                    }
                    List<Node> bucket = cells.get(cellKey(cellX, cellZ));
                    if (bucket == null) {
                        continue;
                    }
                    for (Node node : bucket) {
                        double distanceSquared = squared(node.getX() - x) + squared(node.getZ() - z);
                        if (distanceSquared > bestSquared) {
                            continue;
                        }
                        Hit hit = new Hit(node, distanceSquared);
                        if (hits.size() < limit || HIT_ORDER.compare(hit, hits.last()) < 0) {
                            hits.add(hit);
                            if (hits.size() > limit) {
                                hits.pollLast();
                            }
                            bestSquared = hits.size() >= limit ? hits.last().distanceSquared() : radiusSquared;
                        }
                    }
                }
            }
        }

        List<Node> result = new ArrayList<>(hits.size());
        for (Hit hit : hits) {
            result.add(hit.node());
        }
        return result;
    }

    private void rebuildIfStale(NodeSource source) {
        long revision = source.revision();
        int count = source.nodeCount();
        if (count == builtCount && revision == builtRevision) {
            return;
        }

        cells.clear();
        indexedNodes = 0;
        minCellX = Integer.MAX_VALUE;
        minCellZ = Integer.MAX_VALUE;
        maxCellX = Integer.MIN_VALUE;
        maxCellZ = Integer.MIN_VALUE;

        for (Node node : source.nodes()) {
            if (node == null) {
                continue;
            }
            int cellX = cellIndex(node.getX());
            int cellZ = cellIndex(node.getZ());
            cells.computeIfAbsent(cellKey(cellX, cellZ), key -> new ArrayList<>(4)).add(node);
            indexedNodes++;
            minCellX = Math.min(minCellX, cellX);
            maxCellX = Math.max(maxCellX, cellX);
            minCellZ = Math.min(minCellZ, cellZ);
            maxCellZ = Math.max(maxCellZ, cellZ);
        }

        if (indexedNodes == 0) {
            minCellX = 0;
            maxCellX = 0;
            minCellZ = 0;
            maxCellZ = 0;
        }

        builtCount = count;
        builtRevision = revision;
    }

    private int ringLimit(double radius, int centerCellX, int centerCellZ) {
        double radiusCells = Math.ceil(Math.abs(radius) / cellSize);
        int byRadius = radiusCells >= MAX_RING ? MAX_RING : (int)radiusCells;
        int byBounds = 1 + Math.max(Math.max(Math.abs(centerCellX - minCellX), Math.abs(maxCellX - centerCellX)),
            Math.max(Math.abs(centerCellZ - minCellZ), Math.abs(maxCellZ - centerCellZ)));
        return Math.min(byRadius, byBounds);
    }

    private static int ringDistance(int cellX, int cellZ, int centerCellX, int centerCellZ) {
        return Math.max(Math.abs(cellX - centerCellX), Math.abs(cellZ - centerCellZ));
    }

    /**
     * Candidate hit ordered by distance first and by id second, so equidistant hits always resolve to the smaller id.
     */
    private static final Comparator<Hit> HIT_ORDER =
        Comparator.comparingDouble(Hit::distanceSquared).thenComparing(hit -> idOf(hit.node()));

    private record Hit(Node node, double distanceSquared) {
    }

    private static String idOf(Node node) {
        UUID id = node.getId();
        return id == null ? "" : id.toString();
    }

    private int cellIndex(double value) {
        return (int)Math.floor(value / cellSize);
    }

    private static long cellKey(int cellX, int cellZ) {
        return ((long)cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }

    private static double squared(double value) {
        return value * value;
    }
}
