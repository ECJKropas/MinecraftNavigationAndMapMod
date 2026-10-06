/*
 * Copyright (C) 2025  MinecraftNavigationAndMapMod contributors
 * https://github.com/ECJKropas/MinecraftNavigationAndMapMod

 * MinecraftNavigationAndMapMod is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.ecjkim.wayfarer.client.road.nav;

import java.util.List;
import java.util.Optional;

import com.ecjkim.wayfarer.client.WayfarerConfig;
import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Node;

/** Thread-safe navigation state shared by the client tick, HUD and HTTP server. */
public final class NavigationSession {
    public enum State { IDLE, ACTIVE, ARRIVED }

    private final RoadNetworkDatabase database;
    private final Router router;
    private final WayfarerConfig config = WayfarerConfig.getInstance();
    private State state = State.IDLE;
    private Route route;
    private Node destination;
    private double playerX;
    private double playerZ;
    private int nearestIndex;
    private String error;
    private long arrivedAt;

    public NavigationSession(RoadNetworkDatabase database) {
        this.database = database;
        this.router = new Router(database);
    }

    public synchronized Result start(double destinationX, double destinationZ) {
        Optional<Node> end = LocationSnapper.nearestNode(database, destinationX, destinationZ, config.getNavSnapRadius());
        if (end.isEmpty()) return fail("DESTINATION_NOT_NEAR_ROAD");
        Optional<Node> start = LocationSnapper.nearestNode(database, playerX, playerZ, config.getNavSnapRadius());
        if (start.isEmpty()) return fail("START_NOT_NEAR_ROAD");
        Router.Result result = router.route(start.get(), end.get(), distance(playerX, playerZ, end.get()));
        if (!result.isSuccess()) return fail(result.error());
        route = result.route();
        destination = end.get();
        nearestIndex = 0;
        error = null;
        state = State.ACTIVE;
        return new Result(true, null, snapshot());
    }

    public synchronized void updatePlayer(double x, double z) {
        playerX = x;
        playerZ = z;
        if (state == State.ARRIVED && System.currentTimeMillis() - arrivedAt > 4000) {
            stop();
            return;
        }
        if (state != State.ACTIVE || route == null) return;
        nearestIndex = nearestRouteIndex(x, z);
        if (distance(x, z, destination) <= config.getNavArrivalRadius()) {
            state = State.ARRIVED;
            arrivedAt = System.currentTimeMillis();
        } else if (distanceToRoute(x, z) > config.getNavRerouteThreshold()) {
            reroute();
        }
    }

    private void reroute() {
        if (destination == null) return;
        Optional<Node> start = LocationSnapper.nearestNode(database, playerX, playerZ, config.getNavSnapRadius());
        if (start.isEmpty()) return;
        Router.Result result = router.route(start.get(), destination, distance(playerX, playerZ, destination));
        if (result.isSuccess()) {
            route = result.route();
            nearestIndex = 0;
            error = null;
        }
    }

    public synchronized void stop() {
        state = State.IDLE;
        route = null;
        destination = null;
        nearestIndex = 0;
        error = null;
    }

    public synchronized Snapshot snapshot() {
        double remaining = 0;
        if (route != null) {
            List<Double> lengths = route.getEdgeLengths();
            for (int i = nearestIndex; i < lengths.size(); i++) remaining += lengths.get(i);
            if (nearestIndex < route.getNodes().size()) {
                Node next = route.getNodes().get(nearestIndex);
                remaining += distance(playerX, playerZ, next);
            }
        }
        return new Snapshot(state, route, playerX, playerZ, destination, remaining, error);
    }

    private Result fail(String message) {
        state = State.IDLE;
        route = null;
        destination = null;
        error = message;
        return new Result(false, message, snapshot());
    }

    private int nearestRouteIndex(double x, double z) {
        int result = 0;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < route.getNodes().size(); i++) {
            Node node = route.getNodes().get(i);
            double d = distance(x, z, node);
            if (d < best) { best = d; result = i; }
        }
        return result;
    }

    private double distanceToRoute(double x, double z) {
        double best = Double.POSITIVE_INFINITY;
        for (Node node : route.getNodes()) best = Math.min(best, distance(x, z, node));
        return best;
    }

    private static double distance(double x, double z, Node node) {
        return Math.hypot(x - node.getX(), z - node.getZ());
    }

    public record Result(boolean success, String error, Snapshot snapshot) {}
    public record Snapshot(State state, Route route, double playerX, double playerZ, Node destination,
        double remainingDistance, String error) {}
}
