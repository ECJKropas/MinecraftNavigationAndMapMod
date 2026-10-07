package checks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.ecjkim.wayfarer.client.WayfarerConfig;
import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Direction;
import com.ecjkim.wayfarer.client.road.model.Node;
import com.ecjkim.wayfarer.client.road.model.Road;
import com.ecjkim.wayfarer.client.road.model.Segment;
import com.ecjkim.wayfarer.client.road.model.Source;
import com.ecjkim.wayfarer.client.road.nav.Router;
import com.ecjkim.wayfarer.client.road.spatial.NodeSpatialIndex;

/**
 * Layer 2 — the real {@code Router}, compiled unmodified against a headless stand-in database.
 *
 * <p>
 * The baseline is an independent re-implementation of the pre-B2 {@code buildGraph} (same segment walking, same
 * direction rules, same {@code length / effectiveSpeed} cost, same distance-gate interpolation) driving the copied
 * Dijkstra. Every query compares success/failure, totalCost, totalDistance and ETA; single-candidate calls must
 * reproduce the single-point behaviour bit for bit.
 * </p>
 */
public final class RouterDiffTest {
    private static final double[] TRIP_DISTANCE_SCALE = { 0.25D, 1.0D, 4.0D };
    private static final double[] GATES = { 0.0D, 200.0D, 100000.0D };

    private RouterDiffTest() {}

    private record Fixture(String label, RoadNetworkDatabase database, List<Node> nodes, int clusterSize,
        UUID isolatedId) {
    }

    public static void run(Checks c) {
        for (GraphFactory.Network network : GraphSearchDiffTest.families()) {
            Fixture fixture = fixture(network, true);
            for (double gate : GATES) {
                WayfarerConfig.getInstance().setNavDistanceGate(gate);
                Router router = new Router(fixture.database());
                perQueryDifferential(c, router, fixture, gate);
            }
        }
        WayfarerConfig.getInstance().setNavDistanceGate(200.0D);
        degenerateSingleCandidate(c);
        candidateSets(c);
        errorCodes(c);
        indexDrivenCandidates(c);
    }

    // ---------------------------------------------------------------- per graph differential

    private static void perQueryDifferential(Checks c, Router router, Fixture fixture, double gate) {
        List<Node> nodes = fixture.nodes();
        Random rnd = new Random(fixture.label().hashCode() * 53L + (long)(gate * 7));
        String tag = "Router vs Dijkstra, " + fixture.label() + ", gate=" + gate;
        int reachable = 0;
        int unreachable = 0;
        int queries = 60;
        for (int q = 0; q < queries; q++) {
            Node start;
            Node end;
            if (fixture.clusterSize() > 0 && q % 2 == 0) {
                int side = rnd.nextInt(2);
                start = nodes.get(side * fixture.clusterSize() + rnd.nextInt(fixture.clusterSize()));
                end = nodes.get((1 - side) * fixture.clusterSize() + rnd.nextInt(fixture.clusterSize()));
            } else {
                start = nodes.get(rnd.nextInt(nodes.size()));
                end = nodes.get(rnd.nextInt(nodes.size()));
            }
            double tripDistance = (rnd.nextDouble() * 4D + 0.25D) * 50D * TRIP_DISTANCE_SCALE[q % 3];
            Router.Result result = router.route(start, end, tripDistance);
            // The pre-B2 cost function interpolates every edge speed against the trip distance, so the baseline graph
            // has to be rebuilt for the trip distance of this very query.
            Map<UUID, List<BaselineDijkstra.Edge>> baseline = baselineGraph(fixture.database(), tripDistance);
            BaselineDijkstra.Path expected = BaselineDijkstra.search(baseline, start.getId(), end.getId());
            String where = "(" + start.getId() + "," + end.getId() + ")";
            c.eq(tag + " reachable " + where, result.isSuccess(), expected != null);
            if (expected == null) {
                unreachable++;
                // Pre-B2 semantics: an endpoint that is not on any segment is NO_ROAD, two unconnected components are
                // NO_ROUTE. The isolated fixture node keeps both situations in play.
                boolean endpointsOnGraph = baseline.containsKey(start.getId()) && baseline.containsKey(end.getId());
                c.eq(tag + " error " + where, result.error(), endpointsOnGraph ? "NO_ROUTE" : "NO_ROAD");
                continue;
            }
            reachable++;
            Route route = route(result);
            c.closeTo(tag + " totalCost " + where, route.cost(), expected.totalCost());
            c.closeTo(tag + " totalDistance " + where, route.distance(),
                expected.edgeLengths().stream().mapToDouble(Double::doubleValue).sum());
            c.closeTo(tag + " eta " + where, route.eta(),
                route.distance() / Math.max(0.1D, effectiveSpeed("", tripDistance, gate)));
            c.eq(tag + " startId " + where, route.startId(), start.getId());
            c.eq(tag + " endId " + where, route.endId(), end.getId());
            c.eq(tag + " node count vs edges " + where, route.lengths().size(), route.nodes().size() - 1);
        }
        c.isTrue(tag + " exercised reachable pairs", reachable > 0);
        if (fixture.clusterSize() > 0) {
            c.isTrue(tag + " exercised unreachable pairs", unreachable > 0);
        }
        System.out.println(
            "  " + tag + ": queries=" + queries + " reachable=" + reachable + " unreachable=" + unreachable);
    }

    // ---------------------------------------------------------------- degeneracy + candidates

    /** A single candidate must behave exactly like the pre-B2 single-point call — same numbers, same nodes. */
    private static void degenerateSingleCandidate(Checks c) {
        for (GraphFactory.Network network : GraphSearchDiffTest.families()) {
            Fixture fixture = fixture(network, true);
            Router router = new Router(fixture.database());
            List<Node> nodes = fixture.nodes();
            Random rnd = new Random(network.label().hashCode() * 71L + 5L);
            String tag = "single candidate degenerates, " + network.label();
            for (int i = 0; i < 80; i++) {
                Node start = nodes.get(rnd.nextInt(nodes.size()));
                Node end = nodes.get(rnd.nextInt(nodes.size()));
                double tripDistance = 40D + rnd.nextDouble() * 400D;
                Router.Result single = router.route(start, end, tripDistance);
                Router.Result asCandidates =
                    router.route(List.of(start), List.of(end), tripDistance);
                c.eq(tag + " success", asCandidates.isSuccess(), single.isSuccess());
                c.eq(tag + " error", asCandidates.error(), single.error());
                if (!single.isSuccess() || !asCandidates.isSuccess()) {
                    continue;
                }
                Route a = route(single);
                Route b = route(asCandidates);
                c.listEq(tag + " nodes", b.nodes(), a.nodes());
                c.listEq(tag + " edge lengths", b.lengths(), a.lengths());
                c.listEq(tag + " node ids", b.nodeIds(), a.nodeIds());
                c.exact(tag + " totalDistance", b.distance(), a.distance());
                c.exact(tag + " totalCost", b.cost(), a.cost());
                c.exact(tag + " eta", b.eta(), a.eta());
            }
        }
    }

    /** Candidate sets must reach the same endpoints as any single candidate, and never be worse than the best pair. */
    private static void candidateSets(Checks c) {
        for (GraphFactory.Network network : GraphSearchDiffTest.families()) {
            Fixture fixture = fixture(network, false);
            Router router = new Router(fixture.database());
            List<Node> nodes = fixture.nodes();
            Random rnd = new Random(network.label().hashCode() * 97L + 3L);
            String tag = "candidate sets, " + network.label();
            for (int round = 0; round < 40; round++) {
                List<Node> starts = pick(rnd, nodes, 2 + rnd.nextInt(3));
                List<Node> ends = pick(rnd, nodes, 2 + rnd.nextInt(3));
                double tripDistance = 60D + rnd.nextDouble() * 300D;
                Map<UUID, List<BaselineDijkstra.Edge>> baseline = baselineGraph(fixture.database(), tripDistance);
                Router.Result result = router.route(starts, ends, tripDistance);
                double best = Double.POSITIVE_INFINITY;
                int bestIndex = -1;
                for (int s = 0; s < starts.size(); s++) {
                    for (Node end : ends) {
                        BaselineDijkstra.Path path = BaselineDijkstra.search(baseline, starts.get(s).getId(),
                            end.getId());
                        if (path != null && path.totalCost() < best) {
                            best = path.totalCost();
                            bestIndex = s;
                        }
                    }
                }
                c.eq(tag + " reachable round=" + round, result.isSuccess(), bestIndex >= 0);
                if (bestIndex < 0) {
                    continue;
                }
                Route route = route(result);
                c.closeTo(tag + " optimal cost round=" + round, route.cost(), best);
                c.isTrue(tag + " start from candidates round=" + round, containsId(starts, route.startId()));
                c.isTrue(tag + " end from candidates round=" + round, containsId(ends, route.endId()));
                List<Node> reversed = new ArrayList<>(starts);
                Collections.reverse(reversed);
                Router.Result shuffled = router.route(reversed, ends, tripDistance);
                c.isTrue(tag + " reversed candidates succeed round=" + round, shuffled.isSuccess());
                if (shuffled.isSuccess()) {
                    c.listEq(tag + " candidate order does not matter round=" + round, route(shuffled).nodeIds(),
                        route.nodeIds());
                }
                // Adding candidates can only help or stay equal.
                List<Node> wider = new ArrayList<>(starts);
                wider.add(nodes.get(rnd.nextInt(nodes.size())));
                Router.Result superset = router.route(wider, ends, tripDistance);
                c.isTrue(tag + " superset is not worse round=" + round,
                    superset.isSuccess() && route(superset).cost() <= route.cost() + 1e-9D);
            }
        }
    }

    private static void errorCodes(Checks c) {
        GraphFactory.Network network = GraphFactory.islandsNetwork(20, false, 4242L);
        Fixture fixture = fixture(network, true);
        Router router = new Router(fixture.database());
        Node isolated = fixture.nodes().get(fixture.nodes().size() - 1);
        Node inside = fixture.nodes().get(0);
        Node otherIsland = fixture.nodes().get(fixture.clusterSize());

        c.eq("null start", router.route(null, inside, 100D).error(), "INVALID_INPUT");
        c.eq("null end", router.route(inside, null, 100D).error(), "INVALID_INPUT");
        c.eq("no candidates", router.route(List.of(), List.of(inside), 100D).error(), "INVALID_INPUT");
        c.eq("no targets", router.route(List.of(inside), List.of(), 100D).error(), "INVALID_INPUT");
        c.eq("isolated start is NO_ROAD", router.route(isolated, inside, 100D).error(), "NO_ROAD");
        c.eq("isolated end is NO_ROAD", router.route(inside, isolated, 100D).error(), "NO_ROAD");
        c.eq("isolated candidate set is NO_ROAD", router.route(List.of(isolated), List.of(inside), 100D).error(),
            "NO_ROAD");
        c.eq("cross island is NO_ROUTE", router.route(inside, otherIsland, 100D).error(), "NO_ROUTE");
        c.eq("cross island candidates is NO_ROUTE", router.route(List.of(inside), List.of(otherIsland), 100D).error(),
            "NO_ROUTE");
        c.isTrue("identical endpoints succeed", router.route(inside, inside, 100D).isSuccess());
        c.exact("identical endpoints cost 0", route(router.route(inside, inside, 100D)).cost(), 0D);
        c.isTrue("identical isolated endpoints still succeed (pre-B2 short circuit)",
            router.route(isolated, isolated, 100D).isSuccess());
        // An unreachable member must not change the verdict, and a candidate shared by both sides must resolve to the
        // same zero-length route whatever order the candidates arrive in.
        c.eq("candidate set with an isolated member stays NO_ROUTE",
            router.route(List.of(isolated, inside), List.of(otherIsland), 100D).error(), "NO_ROUTE");
        c.isTrue("candidate set ignores the isolated member",
            router.route(List.of(isolated, inside), List.of(inside), 100D).isSuccess());
        Node neighbour = fixture.nodes().get(1);
        Route forward = route(router.route(List.of(inside, neighbour), List.of(inside), 100D));
        Route backward = route(router.route(List.of(neighbour, inside), List.of(inside), 100D));
        c.exact("shared candidate yields a zero cost route", forward.cost(), 0D);
        c.eq("shared candidate is order independent", backward.startId(), forward.startId());
    }

    /** The real k-nearest index feeding the real router: candidates from the index, optimum from the baseline. */
    private static void indexDrivenCandidates(Checks c) {
        for (GraphFactory.Network network : GraphSearchDiffTest.families()) {
            Fixture fixture = fixture(network, false);
            Router router = new Router(fixture.database());
            NodeSpatialIndex index = new NodeSpatialIndex();
            NodeSpatialIndex.NodeSource source = source(fixture.nodes());
            Random rnd = new Random(network.label().hashCode() * 173L + 11L);
            String tag = "index candidates -> router, " + network.label();
            for (int round = 0; round < 40; round++) {
                double startX = rnd.nextDouble() * 1200D - 100D;
                double startZ = rnd.nextDouble() * 1200D - 100D;
                double endX = rnd.nextDouble() * 1200D - 100D;
                double endZ = rnd.nextDouble() * 1200D - 100D;
                List<Node> starts = index.nearest(source, startX, startZ, 4000D, 4);
                List<Node> ends = index.nearest(source, endX, endZ, 4000D, 4);
                if (starts.isEmpty() || ends.isEmpty()) {
                    c.isTrue(tag + " candidates found round=" + round, false);
                    continue;
                }
                double tripDistance = Math.hypot(endX - startX, endZ - startZ);
                Map<UUID, List<BaselineDijkstra.Edge>> baseline = baselineGraph(fixture.database(), tripDistance);
                Router.Result result = router.route(starts, ends, tripDistance);
                double best = Double.POSITIVE_INFINITY;
                for (Node start : starts) {
                    for (Node end : ends) {
                        BaselineDijkstra.Path path = BaselineDijkstra.search(baseline, start.getId(), end.getId());
                        if (path != null) {
                            best = Math.min(best, path.totalCost());
                        }
                    }
                }
                boolean anyReachable = Double.isFinite(best);
                c.eq(tag + " reachable round=" + round, result.isSuccess(), anyReachable);
                if (!anyReachable || !result.isSuccess()) {
                    continue;
                }
                c.closeTo(tag + " optimal cost round=" + round, route(result).cost(), best);
                c.isTrue(tag + " snapped start round=" + round, containsId(starts, route(result).startId()));
                c.isTrue(tag + " snapped end round=" + round, containsId(ends, route(result).endId()));
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Fixture fixture(GraphFactory.Network network, boolean addIsolatedNode) {
        RoadNetworkDatabase database = GraphFactory.database(network);
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < network.nodeCount(); i++) {
            nodes.add(database.getNode(GraphFactory.id(i)));
        }
        UUID isolatedId = null;
        if (addIsolatedNode) {
            isolatedId = new UUID(5L, 5L);
            Node isolated = new Node(isolatedId, 90000D, 0D, 90000D, Source.USER, 1, 0L);
            database.addNode(isolated);
            nodes.add(isolated);
        }
        return new Fixture(network.label() + (addIsolatedNode ? "+isolated" : ""), database, nodes,
            network.clusterSize(), isolatedId);
    }

    /**
     * Independent re-implementation of the pre-B2 {@code Router.buildGraph}: same segment walking, same direction
     * rules and the same {@code length / effectiveSpeed(classification, tripDistance)} cost. The pre-B2 router
     * interpolated every edge's speed against the <em>query's</em> trip distance, so the caller must pass that very
     * distance (not the edge length) for the costs to be comparable.
     */
    private static Map<UUID, List<BaselineDijkstra.Edge>> baselineGraph(RoadNetworkDatabase database,
        double tripDistance) {
        double gate = WayfarerConfig.getInstance().getNavDistanceGate();
        Map<UUID, List<BaselineDijkstra.Edge>> graph = new LinkedHashMap<>();
        for (Segment segment : database.getAllSegments()) {
            List<Node> nodes = database.getNodesForSegment(segment.getId());
            for (int i = 0; i + 1 < nodes.size(); i++) {
                Node a = nodes.get(i);
                Node b = nodes.get(i + 1);
                double length = Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
                if (length == 0) {
                    continue;
                }
                String classification = "";
                Road road = segment.getRoadId() == null ? null : database.getRoad(segment.getRoadId());
                if (road != null && road.getClassification() != null) {
                    classification = road.getClassification();
                }
                double cost = length / effectiveSpeed(classification, tripDistance, gate);
                if (segment.getDirection() != Direction.BACKWARD) {
                    add(graph, a.getId(), b.getId(), length, cost);
                }
                if (segment.getDirection() != Direction.FORWARD) {
                    add(graph, b.getId(), a.getId(), length, cost);
                }
            }
        }
        return graph;
    }

    private static void add(Map<UUID, List<BaselineDijkstra.Edge>> graph, UUID from, UUID to, double length,
        double cost) {
        graph.computeIfAbsent(from, ignored -> new ArrayList<>()).add(new BaselineDijkstra.Edge(to, length, cost));
    }

    private static double effectiveSpeed(String classification, double tripDistance, double gate) {
        char code = classification == null || classification.isEmpty() ? 0 : classification.charAt(0);
        double preferred = WayfarerConfig.getInstance().getNavigationSpeed(code);
        if (tripDistance < gate) {
            double ratio = tripDistance / Math.max(1D, gate);
            return 4.3D + (preferred - 4.3D) * ratio;
        }
        return preferred;
    }

    // ---------------------------------------------------------------- record shims

    private record Route(List<Node> nodes, List<Double> lengths, double distance, double cost, double eta,
        UUID startId, UUID endId) {
        List<UUID> nodeIds() {
            List<UUID> ids = new ArrayList<>(nodes.size());
            for (Node node : nodes) {
                ids.add(node.getId());
            }
            return ids;
        }
    }

    private static Route route(Router.Result result) {
        com.ecjkim.wayfarer.client.road.nav.Route value = result.route();
        return new Route(value.getNodes(), value.getEdgeLengths(), value.getTotalDistance(), value.getTotalCost(),
            value.getEtaSeconds(), value.getStartId(), value.getEndId());
    }

    private static boolean containsId(List<Node> nodes, UUID id) {
        for (Node node : nodes) {
            if (node.getId().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static List<Node> pick(Random rnd, List<Node> nodes, int count) {
        List<Node> pool = new ArrayList<>(nodes);
        Collections.shuffle(pool, rnd);
        return new ArrayList<>(pool.subList(0, Math.min(count, pool.size())));
    }

    private static NodeSpatialIndex.NodeSource source(List<Node> nodes) {
        List<Node> snapshot = new ArrayList<>(nodes);
        return new NodeSpatialIndex.NodeSource() {
            @Override
            public Collection<Node> nodes() {
                return snapshot;
            }

            @Override
            public int nodeCount() {
                return snapshot.size();
            }

            @Override
            public long revision() {
                return 0L;
            }
        };
    }
}
