package checks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ecjkim.wayfarer.client.WayfarerConfig;
import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Direction;
import com.ecjkim.wayfarer.client.road.model.Node;
import com.ecjkim.wayfarer.client.road.model.Road;
import com.ecjkim.wayfarer.client.road.model.Segment;
import com.ecjkim.wayfarer.client.road.model.Source;
import com.ecjkim.wayfarer.client.road.nav.LocationSnapper;
import com.ecjkim.wayfarer.client.road.nav.NavigationSession;
import com.ecjkim.wayfarer.client.road.nav.Route;
import com.ecjkim.wayfarer.client.road.nav.Router;

/**
 * Wiring checks for the B2 candidate snapping in {@code NavigationSession}.
 *
 * <p>
 * Three properties, all measured through the real {@link NavigationSession} entry points:
 * </p>
 * <ol>
 * <li>a position that snaps to exactly one node still behaves like the pre-B2 single-point path (same nodes, same
 * cost, same distance, same ETA as a plain {@code Router.route(start, end, trip)} call);</li>
 * <li>a position whose nearest node is a trap (here: a node whose only road leads away from the destination) now
 * succeeds, because the router is handed the whole candidate set and picks the reachable one - the single-point call
 * the old code made fails on the very same input;</li>
 * <li>an empty candidate set still reports the historical error codes.</li>
 * </ol>
 */
public final class NavigationSessionChecks {
    private static final double SNAP_RADIUS = 32.0D;

    private NavigationSessionChecks() {}

    public static void run(Checks c) {
        // Keep the distance gate at its shipped default so the cost numbers below stay predictable.
        WayfarerConfig.getInstance().setNavDistanceGate(200.0D);
        sparseSingleCandidate(c);
        trappedSnapCandidates(c);
        emptyCandidates(c);
    }

    /**
     * Nodes 200 blocks apart on a straight road, so each endpoint snaps to exactly one candidate. The session route
     * must be bit-identical to the historical single-point call.
     */
    private static void sparseSingleCandidate(Checks c) {
        String tag = "session/sparse";
        RoadNetworkDatabase db = new RoadNetworkDatabase();
        Node a = node(0, 0, 0);
        Node b = node(1, 100, 0);
        Node d = node(2, 200, 0);
        addNode(db, a, b, d);
        link(db, 0, "", Direction.BIDIRECTIONAL, a, b, d);

        List<Node> startCandidates = LocationSnapper.nearestNodes(db, 0, 0, SNAP_RADIUS, 4);
        List<Node> endCandidates = LocationSnapper.nearestNodes(db, 200, 0, SNAP_RADIUS, 4);
        c.eq(tag + " start candidate count", startCandidates.size(), 1);
        c.eq(tag + " start candidate", startCandidates.get(0).getId(), a.getId());
        c.eq(tag + " end candidate count", endCandidates.size(), 1);
        c.eq(tag + " end candidate", endCandidates.get(0).getId(), d.getId());

        double trip = Math.hypot(200.0D, 0.0D);
        Route legacy = new Router(db).route(a, d, trip).route();

        NavigationSession session = new NavigationSession(db);
        session.updatePlayer(0, 0);
        NavigationSession.Result result = session.start(200, 0);
        c.isTrue(tag + " start succeeds", result.success());

        Route actual = result.snapshot().route();
        c.listEq(tag + " nodes match legacy", ids(actual.getNodes()), ids(legacy.getNodes()));
        c.exact(tag + " cost matches legacy", actual.getTotalCost(), legacy.getTotalCost());
        c.exact(tag + " distance matches legacy", actual.getTotalDistance(), legacy.getTotalDistance());
        c.exact(tag + " eta matches legacy", actual.getEtaSeconds(), legacy.getEtaSeconds());
        c.exact(tag + " cost is length over speed", actual.getTotalCost(), 200.0D / 4.3D);
        c.eq(tag + " state", result.snapshot().state(), NavigationSession.State.ACTIVE);
        c.closeTo(tag + " remaining distance", result.snapshot().remainingDistance(), 200.0D);

        // Independent baseline over this very fixture: the pre-B2 Dijkstra on the two bidirectional 100-block edges
        // of the "" (default speed 4.3) road above. Rebuilt here from the fixture's own ids, because GraphFactory
        // mints UUIDs with a different scheme than this fixture.
        Map<UUID, List<BaselineDijkstra.Edge>> baselineAdjacency = new LinkedHashMap<>();
        both(baselineAdjacency, a, b, 100.0D, 4.3D);
        both(baselineAdjacency, b, d, 100.0D, 4.3D);
        var baseline = BaselineDijkstra.search(baselineAdjacency, a.getId(), d.getId());
        c.isTrue(tag + " baseline reaches the destination", baseline != null);
        c.closeTo(tag + " baseline cost matches the session", baseline.totalCost(), actual.getTotalCost());
    }

    /** Adds both directions of one edge, the way a bidirectional segment is walked. */
    private static void both(Map<UUID, List<BaselineDijkstra.Edge>> graph, Node from, Node to, double length,
        double speed) {
        double cost = length / speed;
        graph.computeIfAbsent(from.getId(), ignored -> new ArrayList<>())
            .add(new BaselineDijkstra.Edge(to.getId(), length, cost));
        graph.computeIfAbsent(to.getId(), ignored -> new ArrayList<>())
            .add(new BaselineDijkstra.Edge(from.getId(), length, cost));
    }

    /**
     * The player stands 0.6 blocks from node A, whose only road is a one-way leading 1000 blocks away from the
     * destination. The second nearest node, B, is 1.4 blocks away and connected to the destination. Pre-B2 the router
     * got A alone and failed; B2 hands it both and it succeeds from B.
     */
    private static void trappedSnapCandidates(Checks c) {
        String tag = "session/trap";
        RoadNetworkDatabase db = new RoadNetworkDatabase();
        Node trapSource = node(0, 0, 0);
        Node trapEnd = node(1, 1000, 0);
        Node good = node(2, 2, 0);
        Node destination = node(3, 100, 0);
        addNode(db, trapSource, trapEnd, good, destination);
        link(db, 0, "", Direction.FORWARD, trapSource, trapEnd);
        link(db, 1, "", Direction.BIDIRECTIONAL, good, destination);

        double playerX = 0.6D;
        double playerZ = 0.0D;
        double destinationX = 100.0D;
        double destinationZ = 0.0D;

        List<Node> startCandidates = LocationSnapper.nearestNodes(db, playerX, playerZ, SNAP_RADIUS, 4);
        List<Node> endCandidates = LocationSnapper.nearestNodes(db, destinationX, destinationZ, SNAP_RADIUS, 4);
        c.eq(tag + " candidate count", startCandidates.size(), 2);
        c.eq(tag + " nearest candidate is the trap source", startCandidates.get(0).getId(), trapSource.getId());
        c.eq(tag + " runner up is the useful node", startCandidates.get(1).getId(), good.getId());
        c.eq(tag + " end candidate count", endCandidates.size(), 1);
        c.eq(tag + " end candidate", endCandidates.get(0).getId(), destination.getId());

        double trip = Math.hypot(playerX - destinationX, playerZ - destinationZ);
        Router router = new Router(db);
        Router.Result singlePoint = router.route(trapSource, destination, trip);
        c.isTrue(tag + " single nearest point has no route (pre-B2 behaviour)", !singlePoint.isSuccess());
        c.eq(tag + " single nearest point error", singlePoint.error(), "NO_ROUTE");

        Router.Result candidates = router.route(startCandidates, endCandidates, trip);
        c.isTrue(tag + " candidate set has a route", candidates.isSuccess());

        NavigationSession session = new NavigationSession(db);
        session.updatePlayer(playerX, playerZ);
        NavigationSession.Result result = session.start(destinationX, destinationZ);
        c.isTrue(tag + " session start succeeds", result.success());

        Route actual = result.snapshot().route();
        c.eq(tag + " session starts at the reachable candidate", actual.getStartId(), good.getId());
        c.eq(tag + " session ends at the snapped destination", actual.getEndId(), destination.getId());
        c.exact(tag + " session cost matches the candidate route", actual.getTotalCost(),
            candidates.route().getTotalCost());
        c.exact(tag + " session cost is length over speed", actual.getTotalCost(), 98.0D / 4.3D);
    }

    /** Both endpoints need a fallback: an empty candidate set keeps the historical error codes. */
    private static void emptyCandidates(Checks c) {
        String tag = "session/empty";
        RoadNetworkDatabase db = new RoadNetworkDatabase();
        Node a = node(0, 0, 0);
        Node b = node(1, 100, 0);
        addNode(db, a, b);
        link(db, 0, "", Direction.BIDIRECTIONAL, a, b);

        NavigationSession playerFar = new NavigationSession(db);
        playerFar.updatePlayer(5000, 5000);
        NavigationSession.Result noStart = playerFar.start(0, 0);
        c.isTrue(tag + " start side failure", !noStart.success());
        c.eq(tag + " start side error", noStart.error(), "START_NOT_NEAR_ROAD");
        c.eq(tag + " start side state", noStart.snapshot().state(), NavigationSession.State.IDLE);

        NavigationSession destinationFar = new NavigationSession(db);
        destinationFar.updatePlayer(0, 0);
        NavigationSession.Result noEnd = destinationFar.start(5000, 5000);
        c.isTrue(tag + " destination side failure", !noEnd.success());
        c.eq(tag + " destination side error", noEnd.error(), "DESTINATION_NOT_NEAR_ROAD");
        c.eq(tag + " destination side state", noEnd.snapshot().state(), NavigationSession.State.IDLE);
    }

    static Node node(int index, double x, double z) {
        return new Node(new UUID(0L, index), x, 64.0D, z, Source.USER, 1, 0L);
    }

    static void addNode(RoadNetworkDatabase db, Node... nodes) {
        for (Node node : nodes) {
            db.addNode(node);
        }
    }

    static void link(RoadNetworkDatabase db, int index, String classification, Direction direction, Node... chain) {
        UUID roadId = new UUID(1L, index);
        UUID segmentId = new UUID(2L, index);
        List<UUID> nodeIds = new ArrayList<>();
        for (Node node : chain) {
            nodeIds.add(node.getId());
        }
        db.addRoad(new Road(roadId, "road-" + index, "#FFFFFF", classification, "", List.of(segmentId), 1));
        db.addSegment(new Segment(segmentId, nodeIds, roadId, Source.USER, direction, 1));
    }

    static List<UUID> ids(List<Node> nodes) {
        List<UUID> result = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            result.add(node.getId());
        }
        return result;
    }
}
