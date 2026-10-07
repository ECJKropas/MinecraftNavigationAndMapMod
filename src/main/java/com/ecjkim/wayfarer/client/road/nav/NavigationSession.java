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

import java.util.List;

import com.ecjkim.wayfarer.client.WayfarerConfig;
import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Node;

/** Thread-safe navigation state shared by the client tick, HUD and HTTP server. */
public final class NavigationSession {
    public enum State {
        IDLE, ACTIVE, ARRIVED
    }

    /**
     * How many nearby nodes each endpoint offers the router. When snapping is ambiguous the router picks the cheapest
     * candidate pair; a single candidate reproduces the historical single-point behaviour exactly.
     */
    private static final int SNAP_CANDIDATE_LIMIT = 4;
    /**
     * Off-route samples that must pile up before a reroute is committed. Not a config entry yet: it is a guidance
     * concern the caller owns, and keeping it here leaves {@link Guidance} free of any configuration dependency.
     */
    private static final int REROUTE_CONFIRM_SAMPLES = 4;
    /** Minimum time between two committed reroutes, in milliseconds. */
    private static final long REROUTE_COOLDOWN_MILLIS = 2000L;

    private final RoadNetworkDatabase database;
    private final Router router;
    private final WayfarerConfig config = WayfarerConfig.getInstance();
    private final Guidance.Thresholds thresholds;
    private State state = State.IDLE;
    private Route route;
    private Node destination;
    private double playerX;
    private double playerZ;
    private float playerYaw;
    private boolean playerKnown;
    private Guidance.RerouteState rerouteState = Guidance.RerouteState.INITIAL;
    private String error;
    private long arrivedAt;

    public NavigationSession(RoadNetworkDatabase database) {
        this.database = database;
        this.router = new Router(database);
        this.thresholds = new Guidance.Thresholds(config.getNavRerouteThreshold(), config.getNavArrivalRadius(),
            REROUTE_CONFIRM_SAMPLES, REROUTE_COOLDOWN_MILLIS);
    }

    public synchronized Result start(double destinationX, double destinationZ) {
        List<Node> ends = LocationSnapper.nearestNodes(database, destinationX, destinationZ, config.getNavSnapRadius(),
            SNAP_CANDIDATE_LIMIT);
        if (ends.isEmpty())
            return fail("DESTINATION_NOT_NEAR_ROAD");
        List<Node> starts =
            LocationSnapper.nearestNodes(database, playerX, playerZ, config.getNavSnapRadius(), SNAP_CANDIDATE_LIMIT);
        if (starts.isEmpty())
            return fail("START_NOT_NEAR_ROAD");
        Router.Result result = router.route(starts, ends, distance(playerX, playerZ, ends.get(0)));
        if (!result.isSuccess())
            return fail(result.error());
        route = result.route();
        // The snapped candidate handed to the router is not necessarily the node the search actually reached, so the
        // destination is taken from the route's real endpoint instead. Arrival and rerouting both target that node.
        destination = Guidance.destination(route);
        rerouteState = Guidance.RerouteState.INITIAL;
        error = null;
        state = State.ACTIVE;
        return new Result(true, null, snapshot());
    }

    public synchronized void updatePlayer(double x, double z) {
        updatePlayer(x, z, playerYaw);
    }

    public synchronized void updatePlayer(double x, double z, float yaw) {
        playerX = x;
        playerZ = z;
        playerYaw = yaw;
        playerKnown = true;
        if (state == State.ARRIVED && System.currentTimeMillis() - arrivedAt > 4000) {
            stop();
            return;
        }
        if (state != State.ACTIVE || route == null)
            return;
        Guidance.Reading reading = Guidance.read(route, x, z, yaw, thresholds);
        if (reading.arrived()) {
            state = State.ARRIVED;
            arrivedAt = System.currentTimeMillis();
            rerouteState = Guidance.RerouteState.INITIAL;
            return;
        }
        Guidance.RerouteDecision decision =
            Guidance.evaluateReroute(rerouteState, reading.offRoute(), System.currentTimeMillis(), thresholds);
        rerouteState = decision.nextState();
        if (decision.reroute())
            reroute();
    }

    private void reroute() {
        if (destination == null)
            return;
        List<Node> starts =
            LocationSnapper.nearestNodes(database, playerX, playerZ, config.getNavSnapRadius(), SNAP_CANDIDATE_LIMIT);
        if (starts.isEmpty())
            return;
        // Rerouting goes through the same candidate overload as the initial plan; the destination is re-snapped for the
        // same reason it is on start.
        List<Node> ends = LocationSnapper.nearestNodes(database, destination.getX(), destination.getZ(),
            config.getNavSnapRadius(), SNAP_CANDIDATE_LIMIT);
        if (ends.isEmpty())
            ends = List.of(destination);
        Router.Result result = router.route(starts, ends, distance(playerX, playerZ, destination));
        if (result.isSuccess()) {
            route = result.route();
            destination = Guidance.destination(route);
            rerouteState = Guidance.RerouteState.INITIAL;
            error = null;
        }
    }

    public synchronized void stop() {
        state = State.IDLE;
        route = null;
        destination = null;
        rerouteState = Guidance.RerouteState.INITIAL;
        error = null;
    }

    public synchronized Snapshot snapshot() {
        Guidance.Reading reading = route == null ? null : Guidance.read(route, playerX, playerZ, playerYaw, thresholds);
        return new Snapshot(state, route, playerX, playerZ, playerKnown, destination,
            reading == null ? 0D : reading.remainingDistance(), reading == null ? 0D : reading.remainingTime(),
            reading == null ? null : reading.nextTurn(),
            reading == null ? Guidance.Turn.STRAIGHT : reading.heading().turn(),
            reading == null ? 0D : reading.heading().roadDistance(), playerYaw, reading != null && reading.offRoute(),
            error);
    }

    private Result fail(String message) {
        state = State.IDLE;
        route = null;
        destination = null;
        rerouteState = Guidance.RerouteState.INITIAL;
        error = message;
        return new Result(false, message, snapshot());
    }

    private static double distance(double x, double z, Node node) {
        return Math.hypot(x - node.getX(), z - node.getZ());
    }

    public record Result(boolean success, String error, Snapshot snapshot) {
    }

    public record Snapshot(State state, Route route, double playerX, double playerZ, boolean playerKnown,
        Node destination, double remainingDistance, double remainingTime, Guidance.NextTurn nextTurn,
        Guidance.Turn headingTurn, double currentRoadDistance, float playerYaw, boolean offRoute, String error) {
    }
}
