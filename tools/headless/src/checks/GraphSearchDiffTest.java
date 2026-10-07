package checks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.ecjkim.wayfarer.client.road.nav.GraphSearch;

/**
 * Layer 1 — the search itself.
 *
 * <p>
 * The new multi-source / multi-target A* is compared against the copied pre-B2 Dijkstra on every graph family the B2
 * verification asks for: random digraphs, integer lattices (massive cost ties), one-way streets and unreachable
 * islands. Per query it checks reachability, total cost, and that the returned path really is a walk of the graph whose
 * costs sum up to the reported total.
 * </p>
 */
public final class GraphSearchDiffTest {
    private GraphSearchDiffTest() {}

    public static List<GraphFactory.Network> families() {
        List<GraphFactory.Network> families = new ArrayList<>();
        families.add(GraphFactory.randomNetwork(60, 90, 11L));
        families.add(GraphFactory.randomNetwork(120, 220, 22L));
        families.add(GraphFactory.randomNetwork(200, 80, 33L));
        families.add(GraphFactory.integerGridNetwork(14, 14, false, 44L));
        families.add(GraphFactory.integerGridNetwork(9, 17, true, 55L));
        families.add(GraphFactory.oneWayNetwork(40, 60, 66L));
        families.add(GraphFactory.oneWayNetwork(90, 150, 67L));
        families.add(GraphFactory.islandsNetwork(25, false, 77L));
        families.add(GraphFactory.islandsNetwork(30, true, 88L));
        return families;
    }

    public static void run(Checks c) {
        for (GraphFactory.Network network : families()) {
            perQueryDifferential(c, network, 150);
        }
        multiSourceMultiTarget(c);
        determinism(c);
        apiEdgeCases(c);
    }

    private static void perQueryDifferential(Checks c, GraphFactory.Network network, int queries) {
        Map<UUID, List<GraphSearch.Edge>> adjacency = GraphFactory.adjacency(network);
        Map<UUID, List<BaselineDijkstra.Edge>> baselineAdjacency = BaselineDijkstra.from(adjacency);
        GraphSearch.Graph graph =
            new GraphSearch.Graph(adjacency, GraphFactory.positions(network), GraphFactory.maxSpeed(network));
        List<UUID> ids = GraphFactory.ids(network.nodeCount());
        Random rnd = new Random(network.label().hashCode() * 31L + 7L);
        String tag = "A* vs Dijkstra, " + network.label();
        int reachable = 0;
        int unreachable = 0;
        for (int q = 0; q < queries; q++) {
            UUID start;
            UUID end;
            if (network.clusterSize() > 0 && q % 2 == 0) {
                int side = rnd.nextInt(2);
                start = ids.get(side * network.clusterSize() + rnd.nextInt(network.clusterSize()));
                end = ids.get((1 - side) * network.clusterSize() + rnd.nextInt(network.clusterSize()));
            } else {
                start = ids.get(rnd.nextInt(ids.size()));
                end = ids.get(rnd.nextInt(ids.size()));
            }

            GraphSearch.Path astar = GraphSearch.search(graph, List.of(start), List.of(end));
            BaselineDijkstra.Path baseline = BaselineDijkstra.search(baselineAdjacency, start, end);
            c.eq(tag + " reachable(" + start + "," + end + ")", astar != null, baseline != null);
            if (astar == null) {
                unreachable++;
                continue;
            }
            reachable++;
            c.closeTo(tag + " totalCost(" + start + "," + end + ")", astar.totalCost(), baseline.totalCost());
            c.eq(tag + " path[0](" + start + "," + end + ")", astar.nodes().get(0), start);
            c.eq(tag + " path[last](" + start + "," + end + ")", astar.nodes().get(astar.nodes().size() - 1), end);
            c.eq(tag + " edgeLengths.size(" + start + "," + end + ")", astar.edgeLengths().size(),
                astar.nodes().size() - 1);
            double summed = 0D;
            boolean walkOk = true;
            for (int i = 0; i + 1 < astar.nodes().size(); i++) {
                GraphSearch.Edge step = bestEdge(adjacency, astar.nodes().get(i), astar.nodes().get(i + 1));
                if (step == null) {
                    walkOk = false;
                    break;
                }
                c.exact(tag + " stepLength[" + i + "]", astar.edgeLengths().get(i), step.length());
                summed += step.cost();
            }
            c.isTrue(tag + " path is a walk(" + start + "," + end + ")", walkOk);
            if (walkOk) {
                c.closeTo(tag + " totalCost == sum(step costs)(" + start + "," + end + ")", astar.totalCost(), summed);
            }
        }
        c.isTrue(tag + " exercised reachable pairs", reachable > 0);
        if (network.clusterSize() > 0) {
            c.isTrue(tag + " exercised unreachable pairs", unreachable > 0);
        }
        System.out.println("  " + tag + ": queries=" + queries + " reachable=" + reachable + " unreachable="
            + unreachable);
    }

    /** Candidate sets: the A* answer must be the best of all source/target combinations, and stay inside the sets. */
    private static void multiSourceMultiTarget(Checks c) {
        for (GraphFactory.Network network : families()) {
            Map<UUID, List<GraphSearch.Edge>> adjacency = GraphFactory.adjacency(network);
            Map<UUID, List<BaselineDijkstra.Edge>> baselineAdjacency = BaselineDijkstra.from(adjacency);
            GraphSearch.Graph graph =
                new GraphSearch.Graph(adjacency, GraphFactory.positions(network), GraphFactory.maxSpeed(network));
            List<UUID> ids = GraphFactory.ids(network.nodeCount());
            Random rnd = new Random(network.label().hashCode() * 131L + 17L);
            String tag = "multi-source/target, " + network.label();
            for (int round = 0; round < 25; round++) {
                List<UUID> sources = sample(rnd, ids, 2 + rnd.nextInt(3));
                List<UUID> targets = sample(rnd, ids, 2 + rnd.nextInt(3));
                GraphSearch.Path astar = GraphSearch.search(graph, sources, targets);
                double best = Double.POSITIVE_INFINITY;
                for (UUID source : sources) {
                    for (UUID target : targets) {
                        BaselineDijkstra.Path path = BaselineDijkstra.search(baselineAdjacency, source, target);
                        if (path != null) {
                            best = Math.min(best, path.totalCost());
                        }
                    }
                }
                boolean anyReachable = Double.isFinite(best);
                c.eq(tag + " reachable round=" + round, astar != null, anyReachable);
                if (astar == null) {
                    continue;
                }
                c.closeTo(tag + " best cost round=" + round, astar.totalCost(), best);
                c.isTrue(tag + " source in candidates round=" + round, sources.contains(astar.nodes().get(0)));
                c.isTrue(tag + " target in candidates round=" + round,
                    targets.contains(astar.nodes().get(astar.nodes().size() - 1)));
                // A superset of candidates can never be worse, and the order of the candidates must not matter.
                List<UUID> widerSources = new ArrayList<>(sources);
                widerSources.add(ids.get(rnd.nextInt(ids.size())));
                widerSources.add(sources.get(0));
                GraphSearch.Path wider = GraphSearch.search(graph, widerSources, targets);
                c.isTrue(tag + " superset is not worse round=" + round, wider != null && wider.totalCost() <= astar
                    .totalCost() + 1e-9D);
                List<UUID> reversed = new ArrayList<>(sources);
                Collections.reverse(reversed);
                GraphSearch.Path sameSet = GraphSearch.search(graph, reversed, targets);
                c.eq(tag + " candidate order does not matter round=" + round,
                    sameSet == null ? null : sameSet.nodes(), astar.nodes());
            }
        }
    }

    private static void determinism(Checks c) {
        GraphFactory.Network network = GraphFactory.randomNetwork(90, 140, 99L);
        Map<UUID, List<GraphSearch.Edge>> adjacency = GraphFactory.adjacency(network);
        Map<UUID, double[]> positions = GraphFactory.positions(network);
        GraphSearch.Graph graph = new GraphSearch.Graph(adjacency, positions, GraphFactory.maxSpeed(network));
        List<UUID> ids = GraphFactory.ids(network.nodeCount());
        Random rnd = new Random(7L);
        for (int i = 0; i < 60; i++) {
            UUID start = ids.get(rnd.nextInt(ids.size()));
            UUID end = ids.get(rnd.nextInt(ids.size()));
            GraphSearch.Path first = GraphSearch.search(graph, List.of(start), List.of(end));
            GraphSearch.Path second = GraphSearch.search(graph, List.of(start), List.of(end));
            c.eq("repeat is identical", second == null ? null : second.nodes(), first == null ? null : first.nodes());
            if (first != null) {
                c.exact("repeat cost is identical", second.totalCost(), first.totalCost());
            }
        }
        for (int i = 0; i < 40; i++) {
            UUID start = ids.get(rnd.nextInt(ids.size()));
            UUID end = ids.get(rnd.nextInt(ids.size()));
            GraphSearch.Path original = GraphSearch.search(graph, List.of(start), List.of(end));
            GraphSearch.Graph shuffled = new GraphSearch.Graph(shuffle(rnd, adjacency), positions,
                GraphFactory.maxSpeed(network));
            GraphSearch.Path after = GraphSearch.search(shuffled, List.of(start), List.of(end));
            c.eq("edge insertion order does not matter(" + start + "," + end + ")",
                after == null ? null : after.nodes(), original == null ? null : original.nodes());
        }
        // Cost ties on the integer lattice must resolve to one deterministic path as well.
        GraphFactory.Network grid = GraphFactory.integerGridNetwork(14, 14, false, 44L);
        Map<UUID, List<GraphSearch.Edge>> gridAdjacency = GraphFactory.adjacency(grid);
        GraphSearch.Graph gridGraph =
            new GraphSearch.Graph(gridAdjacency, GraphFactory.positions(grid), GraphFactory.maxSpeed(grid));
        List<UUID> gridIds = GraphFactory.ids(grid.nodeCount());
        UUID gStart = gridIds.get(3);
        UUID gEnd = gridIds.get(gridIds.size() - 5);
        GraphSearch.Path expected = GraphSearch.search(gridGraph, List.of(gStart), List.of(gEnd));
        for (int i = 0; i < 20; i++) {
            GraphSearch.Path actual = GraphSearch.search(gridGraph, List.of(gStart), List.of(gEnd));
            c.eq("tie-heavy grid path is deterministic", actual.nodes(), expected.nodes());
            c.exact("tie-heavy grid cost is deterministic", actual.totalCost(), expected.totalCost());
        }
        c.closeTo("tie-heavy grid cost is optimal", expected.totalCost(),
            BaselineDijkstra.search(BaselineDijkstra.from(gridAdjacency), gStart, gEnd).totalCost());
    }

    private static void apiEdgeCases(Checks c) {
        GraphFactory.Network network = GraphFactory.randomNetwork(40, 60, 123L);
        Map<UUID, List<GraphSearch.Edge>> adjacency = GraphFactory.adjacency(network);
        GraphSearch.Graph graph =
            new GraphSearch.Graph(adjacency, GraphFactory.positions(network), GraphFactory.maxSpeed(network));
        List<UUID> ids = GraphFactory.ids(network.nodeCount());
        UUID known = ids.get(0);
        UUID other = ids.get(1);
        UUID unknown = new UUID(9L, 9L);
        c.eq("empty sources", GraphSearch.search(graph, List.of(), List.of(other)), null);
        c.eq("empty targets", GraphSearch.search(graph, List.of(known), List.of()), null);
        c.eq("null graph", GraphSearch.search(null, List.of(known), List.of(other)), null);
        c.eq("source outside graph", GraphSearch.search(graph, List.of(unknown), List.of(other)), null);
        c.eq("target outside graph", GraphSearch.search(graph, List.of(known), List.of(unknown)), null);
        c.eq("source == target", GraphSearch.search(graph, List.of(known), List.of(known)).totalCost(), 0D);
        GraphSearch.Path single = GraphSearch.search(graph, List.of(known), List.of(other));
        GraphSearch.Path withJunk = GraphSearch.search(graph, List.of(unknown, known, known), List.of(other, other));
        c.eq("unknown/duplicate candidates are ignored", withJunk == null ? null : withJunk.nodes(),
            single == null ? null : single.nodes());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The edge the search must have used between two consecutive path nodes: cheapest first, shortest length on a cost
     * tie. That mirrors {@code GraphSearch.Graph}'s own edge ordering, so a graph with parallel edges of equal cost but
     * different lengths cannot make this helper disagree with the search.
     */
    private static GraphSearch.Edge bestEdge(Map<UUID, List<GraphSearch.Edge>> adjacency, UUID from, UUID to) {
        GraphSearch.Edge best = null;
        for (GraphSearch.Edge edge : adjacency.getOrDefault(from, List.of())) {
            if (!edge.to().equals(to)) {
                continue;
            }
            if (best == null || edge.cost() < best.cost()
                || (edge.cost() == best.cost() && edge.length() < best.length())) {
                best = edge;
            }
        }
        return best;
    }

    private static List<UUID> sample(Random rnd, List<UUID> ids, int count) {
        List<UUID> pool = new ArrayList<>(ids);
        Collections.shuffle(pool, rnd);
        return new ArrayList<>(pool.subList(0, Math.min(count, pool.size())));
    }

    private static Map<UUID, List<GraphSearch.Edge>> shuffle(Random rnd, Map<UUID, List<GraphSearch.Edge>> source) {
        List<UUID> keys = new ArrayList<>(source.keySet());
        Collections.shuffle(keys, rnd);
        Map<UUID, List<GraphSearch.Edge>> result = new LinkedHashMap<>();
        for (UUID key : keys) {
            List<GraphSearch.Edge> edges = new ArrayList<>(source.get(key));
            Collections.shuffle(edges, rnd);
            result.put(key, edges);
        }
        return result;
    }
}
