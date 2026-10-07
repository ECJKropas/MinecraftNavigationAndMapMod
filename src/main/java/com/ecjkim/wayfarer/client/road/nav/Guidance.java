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

import com.ecjkim.wayfarer.client.road.model.Node;

/**
 * The guidance layer between a planned {@link Route} and whoever consumes it (HUD, web panel, tests).
 *
 * <p>
 * The class is intentionally stateless and free of Minecraft, malilib and {@code WayfarerConfig} dependencies: every
 * value is derived from its arguments and every threshold is supplied by the caller through {@link Thresholds}. That is
 * what makes the whole layer runnable headless, on synthetic polylines and foot positions, without booting the game.
 * </p>
 *
 * <p>
 * The one thing that cannot be purely functional is the reroute hysteresis (an "off route for N consecutive samples + a
 * cooldown after the last reroute" filter). It is modelled as an immutable {@link RerouteState} that the caller owns
 * and threads back into {@link #evaluateReroute(RerouteState, boolean, long, Thresholds)} frame by frame, so the layer
 * itself keeps holding no mutable state while the decision stays fully testable.
 * </p>
 */
public final class Guidance {
    /** How far a vertex may bend before it stops counting as "straight", in degrees. */
    private static final double STRAIGHT_MAX_ANGLE_DEGREES = 15D;
    /** How far a vertex must reverse before it counts as a U-turn rather than a straight, in degrees. */
    private static final double UTURN_MIN_ANGLE_DEGREES = 150D;

    private static final double STRAIGHT_COS = Math.cos(Math.toRadians(STRAIGHT_MAX_ANGLE_DEGREES));
    private static final double UTURN_COS = Math.cos(Math.toRadians(UTURN_MIN_ANGLE_DEGREES));

    private Guidance() {}

    /** Turn classification for a route vertex. */
    public enum Turn {
        STRAIGHT, LEFT, RIGHT, UTURN
    }

    /**
     * Every threshold the guidance layer needs, owned and passed in by the caller so the layer never reads
     * {@code WayfarerConfig}. Values are clamped into sane ranges so a misconfigured caller cannot fabricate a reroute
     * storm.
     *
     * @param rerouteDistance lateral distance from the route, in blocks, above which the player counts as off route
     * @param arrivalRadius distance to the real end node, in blocks, at or below which arrival is declared
     * @param confirmSamples consecutive off-route samples required before a reroute is committed (>= 1)
     * @param cooldownMillis minimum time between two committed reroutes, in milliseconds (>= 0)
     */
    public record Thresholds(double rerouteDistance, double arrivalRadius, int confirmSamples, long cooldownMillis) {
        public Thresholds {
            if (rerouteDistance < 0D)
                rerouteDistance = 0D;
            if (arrivalRadius < 0D)
                arrivalRadius = 0D;
            if (confirmSamples < 1)
                confirmSamples = 1;
            if (cooldownMillis < 0L)
                cooldownMillis = 0L;
        }
    }

    /**
     * Where the player currently is relative to the route polyline.
     *
     * @param segmentIndex index of the edge the projection landed on (0 when the route is degenerate)
     * @param segmentFraction position along that edge in {@code [0, 1]}
     * @param alongDistance arc length from the route start to the projected point
     * @param remainingDistance arc length from the projected point to the route end, i.e.
     *            {@code totalDistance - alongDistance}
     * @param lateralDistance distance from the player to the projected point, i.e. how far off the route they are
     * @param totalDistance sum of the route's edge lengths
     */
    public record Projection(int segmentIndex, double segmentFraction, double alongDistance, double remainingDistance,
        double lateralDistance, double totalDistance) {
    }

    /**
     * The next meaningful turn ahead of the player.
     *
     * @param type never {@link Turn#STRAIGHT}; straight vertices are skipped while scanning forward
     * @param distance arc length from the player's projection to the turn vertex
     * @param node the route node at which the turn happens
     */
    public record NextTurn(Turn type, double distance, Node node) {
    }

    /** The direction of the road under the player compared with the player's current view direction. */
    public record Heading(Turn turn, double roadDistance) {
    }

    /** Everything a consumer needs for one frame, derived in a single pass over the route. */
    public record Reading(Projection projection, NextTurn nextTurn, Heading heading, double remainingDistance,
        double remainingTime, boolean offRoute, boolean arrived) {
    }

    /**
     * Immutable hysteresis state for {@link #evaluateReroute(RerouteState, boolean, long, Thresholds)}. The caller owns
     * one instance and replaces it with {@link RerouteDecision#nextState()} every frame; {@link #INITIAL} is the state
     * of a freshly started session.
     */
    public record RerouteState(int consecutiveOffRoute, long lastRerouteMillis, boolean hasRerouted) {
        public static final RerouteState INITIAL = new RerouteState(0, Long.MIN_VALUE, false);
    }

    /** Outcome of one reroute evaluation: whether to reroute now, and the state to carry into the next frame. */
    public record RerouteDecision(boolean reroute, RerouteState nextState) {
    }

    /**
     * Projects {@code (x, z)} onto the route polyline and reports the arc-length position, the remaining distance and
     * the lateral offset.
     *
     * <p>
     * Because the result is a projection rather than "the nearest node", the remaining distance changes continuously as
     * the player walks and never jumps by a whole edge when they pass the midpoint between two nodes.
     * </p>
     */
    public static Projection project(Route route, double x, double z) {
        List<Node> nodes = route.getNodes();
        List<Double> lengths = route.getEdgeLengths();
        double total = 0D;
        for (double length : lengths)
            total += length;
        if (nodes.size() < 2 || lengths.isEmpty()) {
            double lateral = nodes.isEmpty() ? 0D : Math.hypot(x - nodes.get(0).getX(), z - nodes.get(0).getZ());
            return new Projection(0, 0D, 0D, 0D, lateral, total);
        }
        int bestSegment = 0;
        double bestFraction = 0D;
        double bestLateral = Double.POSITIVE_INFINITY;
        double bestAlong = 0D;
        double cumulative = 0D;
        int segments = Math.min(lengths.size(), nodes.size() - 1);
        for (int i = 0; i < segments; i++) {
            Node from = nodes.get(i);
            Node to = nodes.get(i + 1);
            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            double squared = dx * dx + dz * dz;
            double fraction = squared <= 0D ? 0D : clamp01(((x - from.getX()) * dx + (z - from.getZ()) * dz) / squared);
            double projectedX = from.getX() + dx * fraction;
            double projectedZ = from.getZ() + dz * fraction;
            double lateral = Math.hypot(x - projectedX, z - projectedZ);
            if (lateral < bestLateral) {
                bestLateral = lateral;
                bestSegment = i;
                bestFraction = fraction;
                bestAlong = cumulative + lengths.get(i) * fraction;
            }
            cumulative += lengths.get(i);
        }
        double remaining = Math.max(0D, total - bestAlong);
        return new Projection(bestSegment, bestFraction, bestAlong, remaining, bestLateral, total);
    }

    /**
     * Returns the first non-straight vertex strictly ahead of {@code alongDistance}, or {@code null} when the rest of
     * the route is a straight line (including the trivial routes with fewer than three nodes).
     */
    public static NextTurn nextTurn(Route route, double alongDistance) {
        List<Node> nodes = route.getNodes();
        List<Double> lengths = route.getEdgeLengths();
        if (nodes.size() < 3 || lengths.size() < 2)
            return null;
        double cumulative = 0D;
        int segments = Math.min(lengths.size(), nodes.size() - 1);
        for (int i = 0; i < segments; i++) {
            cumulative += lengths.get(i);
            int vertex = i + 1;
            if (vertex + 1 >= nodes.size())
                break;
            if (cumulative < alongDistance)
                continue;
            Turn type = classifyTurn(nodes.get(i), nodes.get(vertex), nodes.get(vertex + 1));
            if (type != Turn.STRAIGHT)
                return new NextTurn(type, cumulative - alongDistance, nodes.get(vertex));
        }
        return null;
    }

    /** Compares the current route segment with the player's view direction. Minecraft yaw 0 faces +Z. */
    public static Heading heading(Route route, Projection projection, float playerYaw) {
        if (route == null || projection == null || route.getNodes().size() < 2)
            return new Heading(Turn.STRAIGHT, 0D);
        int segment = Math.max(0, Math.min(projection.segmentIndex(), route.getNodes().size() - 2));
        Node from = route.getNodes().get(segment);
        Node to = route.getNodes().get(segment + 1);
        double roadX = to.getX() - from.getX();
        double roadZ = to.getZ() - from.getZ();
        double roadLength = Math.hypot(roadX, roadZ);
        if (roadLength <= 0D)
            return new Heading(Turn.STRAIGHT, 0D);
        double yaw = Math.toRadians(playerYaw);
        double facingX = -Math.sin(yaw);
        double facingZ = Math.cos(yaw);
        roadX /= roadLength;
        roadZ /= roadLength;
        double cos = facingX * roadX + facingZ * roadZ;
        Turn turn;
        if (cos >= STRAIGHT_COS)
            turn = Turn.STRAIGHT;
        else if (cos <= UTURN_COS)
            turn = Turn.UTURN;
        else
            turn = facingX * roadZ - facingZ * roadX > 0D ? Turn.RIGHT : Turn.LEFT;
        double roadDistance = route.getEdgeLengths().isEmpty() ? 0D
            : Math.max(0D, route.getEdgeLengths().get(segment) * (1D - projection.segmentFraction()));
        return new Heading(turn, roadDistance);
    }

    /**
     * Classifies the bend at {@code vertex}: {@code previous -> vertex -> next}. With Minecraft's axes (+X east, +Z
     * south) a positive cross product of the incoming and outgoing heading means a right turn.
     */
    public static Turn classifyTurn(Node previous, Node vertex, Node next) {
        double inX = vertex.getX() - previous.getX();
        double inZ = vertex.getZ() - previous.getZ();
        double outX = next.getX() - vertex.getX();
        double outZ = next.getZ() - vertex.getZ();
        double inLength = Math.hypot(inX, inZ);
        double outLength = Math.hypot(outX, outZ);
        if (inLength == 0D || outLength == 0D)
            return Turn.STRAIGHT;
        double cos = (inX * outX + inZ * outZ) / (inLength * outLength);
        if (cos >= STRAIGHT_COS)
            return Turn.STRAIGHT;
        if (cos <= UTURN_COS)
            return Turn.UTURN;
        double cross = inX * outZ - inZ * outX;
        return cross > 0D ? Turn.RIGHT : Turn.LEFT;
    }

    /**
     * Remaining time, folded from {@code etaSeconds} by the fraction of the route still left. Kept deliberately simple
     * because {@link Route} carries no per-segment speed; per-classification ETA is a separate concern.
     */
    public static double remainingTime(Route route, double remainingDistance) {
        double total = route.getTotalDistance();
        if (total <= 0D || remainingDistance <= 0D)
            return 0D;
        double remaining = Math.min(remainingDistance, total);
        return route.getEtaSeconds() * (remaining / total);
    }

    /** The route's real end node, which is what arrival must be judged against. */
    public static Node destination(Route route) {
        if (route == null)
            return null;
        List<Node> nodes = route.getNodes();
        return nodes.isEmpty() ? null : nodes.get(nodes.size() - 1);
    }

    /** True when the player is within {@code arrivalRadius} of the route's real end node. */
    public static boolean hasArrived(Route route, double x, double z, double arrivalRadius) {
        Node end = destination(route);
        if (end == null)
            return false;
        return Math.hypot(x - end.getX(), z - end.getZ()) <= arrivalRadius;
    }

    /** True when the projection puts the player further than {@code threshold} blocks off the route. */
    public static boolean isOffRoute(Projection projection, double threshold) {
        return projection != null && projection.lateralDistance() > threshold;
    }

    /**
     * Derives every per-frame guidance value in one pass: projection, remaining distance, remaining time, next turn,
     * off-route flag and arrival flag.
     */
    public static Reading read(Route route, double x, double z, Thresholds thresholds) {
        return read(route, x, z, 0F, thresholds);
    }

    public static Reading read(Route route, double x, double z, float playerYaw, Thresholds thresholds) {
        Projection projection = project(route, x, z);
        double remaining = projection.remainingDistance();
        NextTurn nextTurn = nextTurn(route, projection.alongDistance());
        Heading heading = heading(route, projection, playerYaw);
        return new Reading(projection, nextTurn, heading, remaining, remainingTime(route, remaining),
            isOffRoute(projection, thresholds.rerouteDistance()), hasArrived(route, x, z, thresholds.arrivalRadius()));
    }

    /**
     * Decides whether to reroute this frame. Off-route must persist for {@link Thresholds#confirmSamples} consecutive
     * samples (hysteresis against a single noisy frame) and at least {@link Thresholds#cooldownMillis} must have passed
     * since the last committed reroute (protection against a reroute storm). The decision is a pure function of the
     * supplied state, so replaying a frame sequence reproduces it exactly.
     */
    public static RerouteDecision evaluateReroute(RerouteState previous, boolean offRoute, long nowMillis,
        Thresholds thresholds) {
        RerouteState state = previous == null ? RerouteState.INITIAL : previous;
        if (!offRoute)
            return new RerouteDecision(false, new RerouteState(0, state.lastRerouteMillis(), state.hasRerouted()));
        int consecutive = state.consecutiveOffRoute() + 1;
        boolean cooledDown =
            !state.hasRerouted() || nowMillis - state.lastRerouteMillis() >= thresholds.cooldownMillis();
        if (consecutive >= thresholds.confirmSamples() && cooledDown)
            return new RerouteDecision(true, new RerouteState(0, nowMillis, true));
        return new RerouteDecision(false,
            new RerouteState(consecutive, state.lastRerouteMillis(), state.hasRerouted()));
    }

    private static double clamp01(double value) {
        if (value < 0D)
            return 0D;
        if (value > 1D)
            return 1D;
        return value;
    }
}
