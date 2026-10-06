/*
 * Copyright (C) 2025  MinecraftNavigationAndMapMod contributors
 * https://github.com/ECJKropas/MinecraftNavigationAndMapMod

 * MinecraftNavigationAndMapMod is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.ecjkim.wayfarer.client.road.nav;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
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

    public Result route(Node start, Node end, double tripDistance) {
        if (start == null || end == null) return Result.failure("INVALID_INPUT");
        if (start.getId().equals(end.getId())) {
            return Result.success(new Route(List.of(start), List.of(), 0, 0, 0));
        }
        Map<UUID, List<Edge>> graph = buildGraph(tripDistance);
        if (!graph.containsKey(start.getId()) || !graph.containsKey(end.getId())) {
            return Result.failure("NO_ROAD");
        }
        Map<UUID, Double> distance = new HashMap<>();
        Map<UUID, UUID> previous = new HashMap<>();
        Map<UUID, Edge> previousEdge = new HashMap<>();
        PriorityQueue<State> queue = new PriorityQueue<>(Comparator.comparingDouble(s -> s.cost));
        distance.put(start.getId(), 0D);
        queue.add(new State(start.getId(), 0D));
        while (!queue.isEmpty()) {
            State state = queue.poll();
            if (state.cost > distance.getOrDefault(state.id, Double.POSITIVE_INFINITY)) continue;
            if (state.id.equals(end.getId())) break;
            for (Edge edge : graph.getOrDefault(state.id, List.of())) {
                double next = state.cost + edge.cost;
                if (next < distance.getOrDefault(edge.to, Double.POSITIVE_INFINITY)) {
                    distance.put(edge.to, next);
                    previous.put(edge.to, state.id);
                    previousEdge.put(edge.to, edge);
                    queue.add(new State(edge.to, next));
                }
            }
        }
        if (!distance.containsKey(end.getId())) return Result.failure("NO_ROUTE");
        ArrayDeque<Node> nodes = new ArrayDeque<>();
        ArrayDeque<Double> lengths = new ArrayDeque<>();
        UUID current = end.getId();
        nodes.addFirst(end);
        while (!current.equals(start.getId())) {
            Edge edge = previousEdge.get(current);
            if (edge == null) return Result.failure("NO_ROUTE");
            lengths.addFirst(edge.length);
            current = previous.get(current);
            nodes.addFirst(database.getNode(current));
        }
        double totalDistance = lengths.stream().mapToDouble(Double::doubleValue).sum();
        return Result.success(new Route(new ArrayList<>(nodes), new ArrayList<>(lengths), totalDistance,
            distance.get(end.getId()), totalDistance / Math.max(0.1, effectiveSpeed("", tripDistance))));
    }

    private Map<UUID, List<Edge>> buildGraph(double tripDistance) {
        Map<UUID, List<Edge>> graph = new HashMap<>();
        for (Segment segment : database.getAllSegments()) {
            List<Node> nodes = database.getNodesForSegment(segment.getId());
            for (int i = 0; i + 1 < nodes.size(); i++) {
                Node a = nodes.get(i), b = nodes.get(i + 1);
                double length = distance(a, b);
                if (length == 0) continue;
                String classification = "";
                Road road = segment.getRoadId() == null ? null : database.getRoad(segment.getRoadId());
                if (road != null && road.getClassification() != null) classification = road.getClassification();
                double cost = length / effectiveSpeed(classification, tripDistance);
                if (segment.getDirection() != Direction.BACKWARD)
                    graph.computeIfAbsent(a.getId(), ignored -> new ArrayList<>()).add(new Edge(b.getId(), length, cost));
                if (segment.getDirection() != Direction.FORWARD)
                    graph.computeIfAbsent(b.getId(), ignored -> new ArrayList<>()).add(new Edge(a.getId(), length, cost));
            }
        }
        return graph;
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

    private record Edge(UUID to, double length, double cost) {}
    private record State(UUID id, double cost) {}

    public record Result(Route route, String error) {
        public static Result success(Route route) { return new Result(route, null); }
        public static Result failure(String error) { return new Result(null, error); }
        public boolean isSuccess() { return route != null; }
    }
}
