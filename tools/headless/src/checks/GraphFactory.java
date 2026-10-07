package checks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Direction;
import com.ecjkim.wayfarer.client.road.model.Node;
import com.ecjkim.wayfarer.client.road.model.Road;
import com.ecjkim.wayfarer.client.road.model.Segment;
import com.ecjkim.wayfarer.client.road.model.Source;
import com.ecjkim.wayfarer.client.road.nav.GraphSearch;

/**
 * Generates the four graph families required by the B2 verification, in a single loader-independent description
 * ({@link Network}) that can be viewed either as a raw search graph or as a road network database.
 *
 * <p>
 * A {@code Network} is a list of {@link Chain}s — an ordered node list plus direction, speed and road classification —
 * which is exactly the unit {@code Router.buildGraph} consumes. The two views therefore describe the same edges.
 * </p>
 */
public final class GraphFactory {
    /** One polyline of the network; {@code FORWARD} means node[0] -> node[n-1]. */
    public record Chain(List<Integer> nodes, Direction direction, double speed, String classification) {
    }

    public record Network(String label, List<double[]> positions, List<Chain> chains, int clusterSize) {
        public int nodeCount() {
            return positions.size();
        }
    }

    private static final String[] CLASSIFICATIONS = { "", "G", "S", "Y", "X", "C" };
    private static final double[] SPEEDS = { 4.3D, 5.0D, 5.5D, 3.5D, 4.5D, 4.0D };

    private GraphFactory() {}

    public static UUID id(int index) {
        return new UUID(0L, index + 1L);
    }

    public static List<UUID> ids(int count) {
        List<UUID> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(id(i));
        }
        return result;
    }

    // ---------------------------------------------------------------- families

    /** Uniformly random positions plus random polylines in random directions. */
    public static Network randomNetwork(int nodeCount, int extraChains, long seed) {
        Random rnd = new Random(seed);
        List<double[]> positions = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            positions.add(new double[] { rnd.nextDouble() * 1000D, rnd.nextDouble() * 1000D });
        }
        List<Chain> chains = new ArrayList<>();
        // A spanning chain keeps most of the graph mutually reachable.
        List<Integer> spine = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            spine.add(i);
        }
        chains.add(new Chain(spine, Direction.BIDIRECTIONAL, SPEEDS[0], CLASSIFICATIONS[0]));
        for (int i = 0; i < extraChains; i++) {
            int length = 2 + rnd.nextInt(4);
            List<Integer> nodes = new ArrayList<>(length);
            for (int j = 0; j < length; j++) {
                nodes.add(rnd.nextInt(nodeCount));
            }
            if (hasAdjacentDuplicate(nodes)) {
                continue;
            }
            chains.add(new Chain(nodes, randomDirection(rnd), SPEEDS[rnd.nextInt(SPEEDS.length)],
                CLASSIFICATIONS[rnd.nextInt(CLASSIFICATIONS.length)]));
        }
        return new Network("random(n=" + nodeCount + ",extra=" + extraChains + ",seed=" + seed + ")", positions, chains,
            0);
    }

    /**
     * Integer lattice with unit steps and one shared speed by default, i.e. a graph where an enormous number of paths
     * have exactly the same total cost — the tie-break torture test.
     */
    public static Network integerGridNetwork(int width, int height, boolean mixedSpeeds, long seed) {
        Random rnd = new Random(seed);
        List<double[]> positions = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < height; z++) {
                positions.add(new double[] { x, z });
            }
        }
        List<Chain> chains = new ArrayList<>();
        for (int z = 0; z < height; z++) {
            List<Integer> row = new ArrayList<>();
            for (int x = 0; x < width; x++) {
                row.add(index(x, z, height));
            }
            double speed = mixedSpeeds ? SPEEDS[rnd.nextInt(SPEEDS.length)] : SPEEDS[0];
            chains.add(new Chain(row, Direction.BIDIRECTIONAL, speed, mixedSpeeds ? "G" : ""));
        }
        for (int x = 0; x < width; x++) {
            List<Integer> column = new ArrayList<>();
            for (int z = 0; z < height; z++) {
                column.add(index(x, z, height));
            }
            double speed = mixedSpeeds ? SPEEDS[rnd.nextInt(SPEEDS.length)] : SPEEDS[0];
            chains.add(new Chain(column, Direction.BIDIRECTIONAL, speed, mixedSpeeds ? "G" : ""));
        }
        return new Network(
            "grid(" + width + "x" + height + ",mixedSpeeds=" + mixedSpeeds + ",seed=" + seed + ")", positions, chains, 0);
    }

    /** One-way avenues (forward and backward) joined by two-way connectors: direction handling is observable. */
    public static Network oneWayNetwork(int nodeCount, int craft, long seed) {
        Random rnd = new Random(seed);
        List<double[]> positions = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            positions.add(new double[] { i * 3.0D + rnd.nextDouble(), rnd.nextDouble() * 40D });
        }
        List<Chain> chains = new ArrayList<>();
        List<Integer> avenue = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            avenue.add(i);
        }
        // Half the avenue is one-way in each direction, so most pairs only work the "right" way round.
        int split = nodeCount / 2;
        chains.add(new Chain(avenue.subList(0, split + 1), Direction.FORWARD, SPEEDS[0], "G"));
        chains.add(new Chain(avenue.subList(split, nodeCount), Direction.BACKWARD, SPEEDS[1], "S"));
        for (int i = 0; i < craft; i++) {
            int a = rnd.nextInt(nodeCount);
            int b = rnd.nextInt(nodeCount);
            if (a == b) {
                continue;
            }
            Direction direction = randomDirection(rnd);
            List<Integer> nodes = direction == Direction.BIDIRECTIONAL ? List.of(a, b)
                : direction == Direction.FORWARD ? List.of(a, b) : List.of(b, a);
            chains.add(new Chain(nodes,
                direction == Direction.BIDIRECTIONAL ? Direction.BIDIRECTIONAL : Direction.FORWARD,
                SPEEDS[rnd.nextInt(SPEEDS.length)], CLASSIFICATIONS[rnd.nextInt(CLASSIFICATIONS.length)]));
        }
        return new Network("oneWay(n=" + nodeCount + ",craft=" + craft + ",seed=" + seed + ")", positions, chains, 0);
    }

    /**
     * Two clusters with no edge between them (unreachable island), or a single one-way bridge so that exactly one
     * direction is reachable.
     */
    public static Network islandsNetwork(int perSide, boolean oneWayBridge, long seed) {
        Random rnd = new Random(seed);
        List<double[]> positions = new ArrayList<>();
        for (int i = 0; i < perSide; i++) {
            positions.add(new double[] { rnd.nextDouble() * 300D, rnd.nextDouble() * 300D });
        }
        for (int i = 0; i < perSide; i++) {
            positions.add(new double[] { 2000D + rnd.nextDouble() * 300D, 2000D + rnd.nextDouble() * 300D });
        }
        List<Chain> chains = new ArrayList<>();
        for (int side = 0; side < 2; side++) {
            List<Integer> cluster = new ArrayList<>();
            for (int i = 0; i < perSide; i++) {
                cluster.add(side * perSide + i);
            }
            chains.add(new Chain(cluster, Direction.BIDIRECTIONAL, SPEEDS[0], ""));
            for (int i = 0; i < perSide * 2; i++) {
                int a = side * perSide + rnd.nextInt(perSide);
                int b = side * perSide + rnd.nextInt(perSide);
                if (a != b) {
                    chains.add(new Chain(List.of(a, b), Direction.BIDIRECTIONAL, SPEEDS[0], ""));
                }
            }
        }
        if (oneWayBridge) {
            // Cluster A (node 0) -> cluster B (node perSide) only: B -> A stays unreachable.
            chains.add(new Chain(List.of(0, perSide), Direction.FORWARD, SPEEDS[0], ""));
        }
        return new Network("islands(perSide=" + perSide + ",bridge=" + oneWayBridge + ",seed=" + seed + ")",
            positions, chains, perSide);
    }

    // ---------------------------------------------------------------- views

    /** The plain search graph view, expanded with exactly the rules of {@code Router.buildGraph}. */
    public static Map<UUID, List<GraphSearch.Edge>> adjacency(Network network) {
        Map<UUID, List<GraphSearch.Edge>> graph = new LinkedHashMap<>();
        for (Chain chain : network.chains()) {
            List<Integer> nodes = chain.nodes();
            for (int i = 0; i + 1 < nodes.size(); i++) {
                double[] a = network.positions().get(nodes.get(i));
                double[] b = network.positions().get(nodes.get(i + 1));
                double length = Math.hypot(a[0] - b[0], a[1] - b[1]);
                if (length == 0) {
                    continue;
                }
                double cost = length / chain.speed();
                if (chain.direction() != Direction.BACKWARD) {
                    link(graph, id(nodes.get(i)), id(nodes.get(i + 1)), length, cost);
                }
                if (chain.direction() != Direction.FORWARD) {
                    link(graph, id(nodes.get(i + 1)), id(nodes.get(i)), length, cost);
                }
            }
        }
        return graph;
    }

    public static Map<UUID, double[]> positions(Network network) {
        Map<UUID, double[]> result = new LinkedHashMap<>();
        for (int i = 0; i < network.positions().size(); i++) {
            result.put(id(i), network.positions().get(i));
        }
        return result;
    }

    public static double maxSpeed(Network network) {
        double max = 0D;
        for (Chain chain : network.chains()) {
            max = Math.max(max, chain.speed());
        }
        return max;
    }

    /** The road-database view: one Segment and one Road per chain, nodes shared by index. */
    public static RoadNetworkDatabase database(Network network) {
        RoadNetworkDatabase database = new RoadNetworkDatabase();
        for (int i = 0; i < network.positions().size(); i++) {
            double[] position = network.positions().get(i);
            database.addNode(new Node(id(i), position[0], 0D, position[1], Source.USER, 1, 0L));
        }
        int index = 0;
        for (Chain chain : network.chains()) {
            UUID roadId = new UUID(1L, index + 1L);
            UUID segmentId = new UUID(2L, index + 1L);
            List<UUID> nodeIds = new ArrayList<>(chain.nodes().size());
            for (int node : chain.nodes()) {
                nodeIds.add(id(node));
            }
            database.addRoad(new Road(roadId, "road-" + index, "#FFFFFF", chain.classification(), "", List.of(segmentId),
                1));
            database.addSegment(
                new Segment(segmentId, nodeIds, roadId, Source.USER, chain.direction(), 1));
            index++;
        }
        return database;
    }

    /** The speed multiplier per chain, indexed the same way {@link #database(Network)} indexes segments. */
    public static List<Double> chainSpeeds(Network network) {
        List<Double> speeds = new ArrayList<>();
        for (Chain chain : network.chains()) {
            speeds.add(chain.speed());
        }
        return speeds;
    }

    // ---------------------------------------------------------------- helpers

    private static void link(Map<UUID, List<GraphSearch.Edge>> graph, UUID from, UUID to, double length, double cost) {
        graph.computeIfAbsent(from, ignored -> new ArrayList<>()).add(new GraphSearch.Edge(to, length, cost));
    }

    private static int index(int x, int z, int height) {
        return x * height + z;
    }

    private static boolean hasAdjacentDuplicate(List<Integer> nodes) {
        for (int i = 0; i + 1 < nodes.size(); i++) {
            if (nodes.get(i).equals(nodes.get(i + 1))) {
                return true;
            }
        }
        return false;
    }

    private static Direction randomDirection(Random rnd) {
        switch (rnd.nextInt(3)) {
            case 0:
                return Direction.FORWARD;
            case 1:
                return Direction.BACKWARD;
            default:
                return Direction.BIDIRECTIONAL;
        }
    }
}
