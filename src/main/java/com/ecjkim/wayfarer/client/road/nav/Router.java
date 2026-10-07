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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ecjkim.wayfarer.client.WayfarerConfig;
import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Direction;
import com.ecjkim.wayfarer.client.road.model.Node;
import com.ecjkim.wayfarer.client.road.model.Road;
import com.ecjkim.wayfarer.client.road.model.Segment;

public final class Router {
    private final RoadNetworkDatabase database;
    private final WayfarerConfig config;

    public Router(RoadNetworkDatabase database) {
        this.database = database;
        this.config = WayfarerConfig.getInstance();
    }

    /**
     * Routes between two single nodes. Signature and semantics are unchanged from the previous implementation; the
     * search itself now goes through {@link GraphSearch}.
     */
    public Result route(Node start, Node end, double tripDistance) {
        if (start == null || end == null)
            return Result.failure("INVALID_INPUT");
        return route(List.of(start), List.of(end), tripDistance);
    }

    /**
     * Routes between candidate endpoints: the best pair out of {@code starts × ends} wins. When snapping onto the road
     * network is ambiguous there is no single node to use, so every candidate is offered to the search and the cheapest
     * combination is kept. A single candidate on each side is exactly the old single-point behaviour.
     */
    public Result route(List<Node> starts, List<Node> ends, double tripDistance) {
        List<Node> startCandidates = compact(starts);
        List<Node> endCandidates = compact(ends);
        if (startCandidates.isEmpty() || endCandidates.isEmpty())
            return Result.failure("INVALID_INPUT");

        // A candidate that is shared by both sides gives a zero-length route, exactly like route(start, end) does when
        // both nodes have the same id. When several candidates are shared, the smallest id wins, so the answer does not
        // depend on the order the candidates were handed in.
        UUID sharedId = null;
        for (Node start : startCandidates) {
            UUID id = start.getId();
            if (id == null)
                continue;
            for (Node end : endCandidates) {
                if (id.equals(end.getId()) && (sharedId == null || compareIds(id, sharedId) < 0)) {
                    sharedId = id;
                }
            }
        }
        if (sharedId != null) {
            for (Node start : startCandidates) {
                if (sharedId.equals(start.getId()))
                    return Result.success(new Route(List.of(start), List.of(), 0, 0, 0));
            }
        }

        GraphSearch.Graph graph = buildGraph(tripDistance);
        List<UUID> sources = presentIds(startCandidates, graph);
        List<UUID> targets = presentIds(endCandidates, graph);
        if (sources.isEmpty() || targets.isEmpty())
            return Result.failure("NO_ROAD");

        GraphSearch.Path path = GraphSearch.search(graph, sources, targets);
        if (path == null)
            return Result.failure("NO_ROUTE");

        List<Node> nodes = new ArrayList<>(path.nodes().size());
        for (UUID id : path.nodes()) {
            Node node = database.getNode(id);
            if (node == null)
                return Result.failure("NO_ROUTE");
            nodes.add(node);
        }
        double totalDistance = path.edgeLengths().stream().mapToDouble(Double::doubleValue).sum();
        return Result.success(new Route(nodes, new ArrayList<>(path.edgeLengths()), totalDistance, path.totalCost(),
            totalDistance / Math.max(0.1, effectiveSpeed("", tripDistance))));
    }

    private GraphSearch.Graph buildGraph(double tripDistance) {
        Map<UUID, List<GraphSearch.Edge>> adjacency = new HashMap<>();
        Map<UUID, double[]> positions = new HashMap<>();
        double maxSpeed = 0D;
        for (Segment segment : database.getAllSegments()) {
            List<Node> nodes = database.getNodesForSegment(segment.getId());
            String classification = "";
            Road road = segment.getRoadId() == null ? null : database.getRoad(segment.getRoadId());
            if (road != null && road.getClassification() != null)
                classification = road.getClassification();
            double speed = effectiveSpeed(classification, tripDistance);
            maxSpeed = Math.max(maxSpeed, speed);
            for (int i = 0; i + 1 < nodes.size(); i++) {
                Node a = nodes.get(i), b = nodes.get(i + 1);
                double length = distance(a, b);
                if (length == 0)
                    continue;
                double cost = length / speed;
                positions.put(a.getId(), new double[] {a.getX(), a.getZ()});
                positions.put(b.getId(), new double[] {b.getX(), b.getZ()});
                if (segment.getDirection() != Direction.BACKWARD)
                    adjacency.computeIfAbsent(a.getId(), ignored -> new ArrayList<>())
                        .add(new GraphSearch.Edge(b.getId(), length, cost));
                if (segment.getDirection() != Direction.FORWARD)
                    adjacency.computeIfAbsent(b.getId(), ignored -> new ArrayList<>())
                        .add(new GraphSearch.Edge(a.getId(), length, cost));
            }
        }
        // The heuristic divides by the fastest edge speed so that it never overestimates; on an empty graph the
        // default walking speed is a harmless stand-in.
        return new GraphSearch.Graph(adjacency, positions, maxSpeed > 0 ? maxSpeed : 4.3D);
    }

    private static List<Node> compact(List<Node> candidates) {
        List<Node> result = new ArrayList<>();
        if (candidates != null) {
            for (Node node : candidates) {
                if (node != null)
                    result.add(node);
            }
        }
        return result;
    }

    private static List<UUID> presentIds(List<Node> candidates, GraphSearch.Graph graph) {
        List<UUID> result = new ArrayList<>(candidates.size());
        for (Node node : candidates) {
            if (node.getId() != null && graph.contains(node.getId()) && !result.contains(node.getId()))
                result.add(node.getId());
        }
        return result;
    }

    private double effectiveSpeed(String classification, double tripDistance) {
        char code = classification == null || classification.isEmpty() ? 0 : classification.charAt(0);
        double preferred = config.getNavigationSpeed(code);
        if (tripDistance < config.getNavDistanceGate()) {
            double ratio = tripDistance / Math.max(1D, config.getNavDistanceGate());
            return 4.3D + (preferred - 4.3D) * ratio;
        }
        return preferred;
    }

    private static double distance(Node a, Node b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    /** Same id ordering as {@link GraphSearch} and the spatial index, so every tie-break in the pipeline agrees. */
    private static int compareIds(UUID left, UUID right) {
        return left.toString().compareTo(right.toString());
    }

    public record Result(Route route, String error) {
        public static Result success(Route route) {
            return new Result(route, null);
        }

        public static Result failure(String error) {
            return new Result(null, error);
        }

        public boolean isSuccess() {
            return route != null;
        }
    }
}
