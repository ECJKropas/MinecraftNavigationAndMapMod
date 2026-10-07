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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Directed weighted graph search with multiple candidate sources and multiple candidate targets, deliberately decoupled
 * from Minecraft and from the road network model: callers hand in plain ids, edges and coordinates.
 *
 * <p>
 * The search is an A* over a directed graph whose edge cost is {@code length / speed}. The heuristic for a node is the
 * straight-line distance to the closest candidate target divided by the fastest speed anywhere in the graph
 * ({@link Graph#maxSpeed()}). Because every edge satisfies {@code cost >= euclidean / maxSpeed}, the heuristic never
 * overestimates the remaining cost, so it is admissible; and because the per-edge bound holds on both sides of every
 * arc it is also consistent, which lets the search stop the moment a target is popped.
 * </p>
 *
 * <p>
 * The result is deterministic: adjacency lists are re-ordered by target id, candidate sets are de-duplicated and
 * ordered by id, and the open set breaks ties by id. Two runs over the same graph therefore return the exact same path
 * regardless of map insertion order or iteration order, and equidistant alternatives always resolve to the smaller id.
 * </p>
 *
 * <p>
 * All methods are static and stateless, so the class is thread-safe by construction.
 * </p>
 */
public final class GraphSearch {
    private GraphSearch() {}

    /** One directed edge of the graph. {@code length} is the geometric length, {@code cost} the traversal cost. */
    public record Edge(UUID to, double length, double cost) {
    }

    /** A resolved route: node ids from the chosen source to the chosen target, plus per-edge lengths and total cost. */
    public record Path(List<UUID> nodes, List<Double> edgeLengths, double totalCost) {
    }

    /**
     * Immutable, deterministic view of a directed weighted graph.
     *
     * <p>
     * The constructor copies the adjacency lists, sorts each of them by target id (then by cost, then by length) and
     * copies the coordinates. Node membership is defined by the adjacency map, matching the previous router behaviour
     * where a node that is not part of any non-degenerate edge simply is not part of the graph.
     * </p>
     */
    public static final class Graph {
        private static final Comparator<Edge> EDGE_ORDER = Comparator.comparing((Edge edge) -> idOf(edge.to()))
            .thenComparingDouble(Edge::cost).thenComparingDouble(Edge::length);

        private final Map<UUID, List<Edge>> adjacency;
        private final Map<UUID, double[]> positions;
        private final double maxSpeed;

        public Graph(Map<UUID, List<Edge>> adjacency, Map<UUID, double[]> positions, double maxSpeed) {
            Map<UUID, List<Edge>> adjacencyCopy = new HashMap<>();
            for (Map.Entry<UUID, List<Edge>> entry : adjacency.entrySet()) {
                List<Edge> edges = new ArrayList<>(entry.getValue());
                edges.removeIf(edge -> edge == null || edge.to() == null);
                edges.sort(EDGE_ORDER);
                adjacencyCopy.put(entry.getKey(), Collections.unmodifiableList(edges));
            }
            this.adjacency = Collections.unmodifiableMap(adjacencyCopy);

            Map<UUID, double[]> positionCopy = new HashMap<>();
            for (Map.Entry<UUID, double[]> entry : positions.entrySet()) {
                double[] value = entry.getValue();
                if (value == null || value.length < 2) {
                    continue;
                }
                positionCopy.put(entry.getKey(), new double[] {value[0], value[1]});
            }
            this.positions = Collections.unmodifiableMap(positionCopy);
            this.maxSpeed = maxSpeed > 0 ? maxSpeed : 0D;
        }

        /** Returns whether {@code id} takes part in the graph. */
        public boolean contains(UUID id) {
            return id != null && adjacency.containsKey(id);
        }

        /** Returns the outgoing edges of {@code id}, already in deterministic order. */
        public List<Edge> edges(UUID id) {
            return adjacency.getOrDefault(id, List.of());
        }

        /** Returns the {@code {x, z}} position of {@code id}, or {@code null} when it is not part of the graph. */
        public double[] position(UUID id) {
            return positions.get(id);
        }

        /** Returns the upper bound on edge speed used by the heuristic. */
        public double maxSpeed() {
            return maxSpeed;
        }

        /** Returns how many nodes take part in the graph. */
        public int nodeCount() {
            return adjacency.size();
        }
    }

    /**
     * Returns the minimum-cost path from any of {@code sources} to any of {@code targets}, or {@code null} when no
     * candidate target can be reached from any candidate source.
     */
    public static Path search(Graph graph, Collection<UUID> sources, Collection<UUID> targets) {
        if (graph == null || sources == null || targets == null) {
            return null;
        }
        List<UUID> sourceIds = normalize(sources, graph);
        Set<UUID> targetIds = new HashSet<>(normalize(targets, graph));
        if (sourceIds.isEmpty() || targetIds.isEmpty()) {
            return null;
        }

        Map<UUID, Double> best = new HashMap<>();
        Map<UUID, UUID> previous = new HashMap<>();
        Map<UUID, Edge> previousEdge = new HashMap<>();
        PriorityQueue<Entry> open = new PriorityQueue<>(ENTRY_ORDER);

        for (UUID id : sourceIds) {
            best.put(id, 0D);
            open.add(new Entry(id, 0D, heuristic(graph, id, targetIds)));
        }

        while (!open.isEmpty()) {
            Entry state = open.poll();
            Double known = best.get(state.id());
            if (known == null || state.cost() > known) {
                continue;
            }
            if (targetIds.contains(state.id())) {
                return reconstruct(state.id(), best, previous, previousEdge);
            }
            for (Edge edge : graph.edges(state.id())) {
                double next = state.cost() + edge.cost();
                Double current = best.get(edge.to());
                if (current == null || next < current) {
                    best.put(edge.to(), next);
                    previous.put(edge.to(), state.id());
                    previousEdge.put(edge.to(), edge);
                    open.add(new Entry(edge.to(), next, heuristic(graph, edge.to(), targetIds)));
                }
            }
        }
        return null;
    }

    private static Path reconstruct(UUID goal, Map<UUID, Double> best, Map<UUID, UUID> previous,
        Map<UUID, Edge> previousEdge) {
        LinkedList<UUID> nodes = new LinkedList<>();
        LinkedList<Double> lengths = new LinkedList<>();
        UUID cursor = goal;
        nodes.addFirst(cursor);
        while (previous.containsKey(cursor)) {
            Edge edge = previousEdge.get(cursor);
            if (edge == null) {
                return null;
            }
            lengths.addFirst(edge.length());
            cursor = previous.get(cursor);
            nodes.addFirst(cursor);
        }
        double totalCost = best.getOrDefault(goal, 0D);
        return new Path(List.copyOf(nodes), List.copyOf(lengths), totalCost);
    }

    /**
     * Admissible and consistent heuristic: straight-line distance to the closest candidate target divided by the
     * fastest speed in the graph.
     */
    private static double heuristic(Graph graph, UUID id, Set<UUID> targets) {
        if (graph.maxSpeed() <= 0) {
            return 0D;
        }
        double[] from = graph.position(id);
        if (from == null) {
            return 0D;
        }
        double best = Double.POSITIVE_INFINITY;
        for (UUID target : targets) {
            double[] to = graph.position(target);
            if (to == null) {
                continue;
            }
            best = Math.min(best, Math.hypot(from[0] - to[0], from[1] - to[1]));
        }
        if (!Double.isFinite(best)) {
            return 0D;
        }
        return best / graph.maxSpeed();
    }

    /** De-duplicates the candidates and keeps only nodes that take part in the graph, ordered by id. */
    private static List<UUID> normalize(Collection<UUID> candidates, Graph graph) {
        Set<UUID> unique = new TreeSet<>(Comparator.comparing(GraphSearch::idOf));
        for (UUID id : candidates) {
            if (id != null && graph.contains(id)) {
                unique.add(id);
            }
        }
        return new ArrayList<>(unique);
    }

    private static String idOf(UUID id) {
        return id == null ? "" : id.toString();
    }

    /** Orders the open set by {@code f = g + h} and breaks ties by id, which keeps the exploration deterministic. */
    private static final Comparator<Entry> ENTRY_ORDER = Comparator
        .comparingDouble((Entry entry) -> entry.cost() + entry.estimate()).thenComparing(entry -> idOf(entry.id()));

    private record Entry(UUID id, double cost, double estimate) {
    }
}
