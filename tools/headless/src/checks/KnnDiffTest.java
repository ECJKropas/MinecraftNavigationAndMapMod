package checks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import com.ecjkim.wayfarer.client.road.model.Node;
import com.ecjkim.wayfarer.client.road.model.Source;
import com.ecjkim.wayfarer.client.road.spatial.NodeSpatialIndex;

/**
 * Brute-force differential test for the k nearest node query that B2 added to {@link NodeSpatialIndex} and that
 * {@code LocationSnapper.nearestNodes} / {@code NavigationSession} now depend on.
 *
 * <p>
 * The ring pruning and the "closest, then smallest id" tie rule are the two places where an off-by-one would silently
 * drop a candidate, so every query is compared against a full scan over the same nodes with the same ordering, and the
 * scenarios deliberately include equidistant nodes, nodes sitting exactly on the radius and on cell boundaries,
 * negative coordinates and in-place mutations.
 * </p>
 */
public final class KnnDiffTest {
    private static int assertions;
    private static int failures;
    private static Checks bridge;

    public static void main(String[] args) {
        runScenarios();
        System.out.println("TOTAL assertions=" + assertions + " failures=" + failures);
        System.out.println("VERDICT: " + (failures == 0 ? "PASS" : "FAIL"));
        if (failures != 0) {
            System.exit(1);
        }
    }

    /**
     * Suite entry point: the very same scenarios, reported through the shared {@link Checks} bookkeeping so the
     * checker can be run as one batch.
     */
    public static void run(Checks c) {
        bridge = c;
        try {
            runScenarios();
        } finally {
            bridge = null;
        }
    }

    private static void runScenarios() {
        double[] cellSizes = {1D, 2D, 3.5D, 4D, 16D, 64D, 1000D};
        for (double cellSize : cellSizes) {
            runSet("unit grid", cellSize, gridNodes(20, 1D, 0D, 0D), unitQueries(), radii(), limits());
            runSet("spaced grid", cellSize, gridNodes(14, 8D, 0D, 0D), spacedQueries(), radii(), limits());
            runSet("negative grid", cellSize, gridNodes(12, 8D, -48D, -48D), negativeQueries(), radii(), limits());
            runSet("clustered", cellSize, clusteredNodes(), clusteredQueries(), radii(), limits());
            runSet("equidistant", cellSize, equidistantNodes(), equidistantQueries(), smallRadii(), limits());
            runSet("duplicates", cellSize, duplicateNodes(), duplicateQueries(), smallRadii(), limits());
            runSet("boundary", cellSize, boundaryNodes(cellSize), boundaryQueries(cellSize), boundaryRadii(cellSize),
                limits());
        }
        mutationCase();
        singleEqualsLimitedCase();
        if (bridge == null) {
            System.out.println("TOTAL assertions=" + assertions + " failures=" + failures);
            System.out.println("VERDICT: " + (failures == 0 ? "PASS" : "FAIL"));
        }
    }

    // ---------------------------------------------------------------- scenarios

    private static void runSet(String label, double cellSize, List<Node> nodes, List<double[]> queries,
        double[] radii, int[] limits) {
        NodeSpatialIndex index = new NodeSpatialIndex(cellSize);
        MutableSource source = new MutableSource(nodes);
        for (double[] query : queries) {
            for (double radius : radii) {
                for (int limit : limits) {
                    compare(label + " cell=" + cellSize + " q=(" + query[0] + "," + query[1] + ") r=" + radius
                        + " k=" + limit, nodes, index, source, query[0], query[1], radius, limit);
                }
            }
        }
    }

    /** A node whose coordinates are mutated in place must be re-indexed as soon as the revision changes. */
    private static void mutationCase() {
        NodeSpatialIndex index = new NodeSpatialIndex(4D);
        MutableSource source = new MutableSource(gridNodes(8, 4D, 0D, 0D));
        double[] query = {10.5D, 10.5D};
        compare("mutation before", new ArrayList<>(source.nodes()), index, source, query[0], query[1], 9D, 3);

        List<Node> far = gridNodes(8, 4D, 200D, 200D);
        source.set(far);
        compare("mutation after reload", far, index, source, query[0], query[1], 9D, 3);
        compare("mutation after reload, wide radius", far, index, source, query[0], query[1], 4000D, 4);
    }

    /** A single candidate must be exactly the historic nearest-node query. */
    private static void singleEqualsLimitedCase() {
        double[] cellSizes = {1D, 4D, 16D};
        for (double cellSize : cellSizes) {
            NodeSpatialIndex index = new NodeSpatialIndex(cellSize);
            MutableSource source = new MutableSource(clusteredNodes());
            for (double[] query : clusteredQueries()) {
                for (double radius : radii()) {
                    List<Node> limited = index.nearest(source, query[0], query[1], radius, 1);
                    Node single = index.nearest(source, query[0], query[1], radius).orElse(null);
                    String tag = "nearest==nearest(k=1) cell=" + cellSize + " q=(" + query[0] + "," + query[1] + ") r="
                        + radius;
                    if (limited.isEmpty()) {
                        eq(tag + " empty", String.valueOf(single == null), "true");
                    } else {
                        eq(tag, String.valueOf(single == null ? null : single.getId()),
                            String.valueOf(limited.get(0).getId()));
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- comparison

    private static void compare(String tag, List<Node> nodes, NodeSpatialIndex index, MutableSource source, double x,
        double z, double radius, int limit) {
        List<UUID> expected = bruteForce(nodes, x, z, radius, limit);
        List<Node> actualNodes = index.nearest(source, x, z, radius, limit);
        List<UUID> actual = new ArrayList<>(actualNodes.size());
        for (Node node : actualNodes) {
            actual.add(node.getId());
        }
        eq(tag, expected.toString(), actual.toString());
        if (actual.size() > limit) {
            eq(tag + " respects the limit", String.valueOf(limit), String.valueOf(actual.size()));
        }
    }

    private static List<UUID> bruteForce(List<Node> nodes, double x, double z, double radius, int limit) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        double radiusSquared = radius * radius;
        List<Node> hits = new ArrayList<>();
        for (Node node : nodes) {
            double dx = node.getX() - x;
            double dz = node.getZ() - z;
            if (dx * dx + dz * dz <= radiusSquared) {
                hits.add(node);
            }
        }
        hits.sort(
            Comparator.comparingDouble((Node node) -> distanceSquared(node, x, z)).thenComparing(node -> idOf(node)));
        List<UUID> result = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, hits.size()); i++) {
            result.add(hits.get(i).getId());
        }
        return result;
    }

    private static double distanceSquared(Node node, double x, double z) {
        double dx = node.getX() - x;
        double dz = node.getZ() - z;
        return dx * dx + dz * dz;
    }

    private static String idOf(Node node) {
        return node.getId() == null ? "" : node.getId().toString();
    }

    // ---------------------------------------------------------------- fixtures

    private static List<Node> gridNodes(int side, double spacing, double originX, double originZ) {
        List<Node> nodes = new ArrayList<>();
        int i = 0;
        for (int gx = 0; gx < side; gx++) {
            for (int gz = 0; gz < side; gz++) {
                nodes.add(node(i++, originX + gx * spacing, originZ + gz * spacing));
            }
        }
        return nodes;
    }

    private static List<Node> clusteredNodes() {
        List<Node> nodes = new ArrayList<>();
        int i = 0;
        for (double[] centre : new double[][] {{0D, 0D}, {100D, -30D}, {-250D, 180D}, {7.5D, 7.5D}}) {
            for (int n = 0; n < 12; n++) {
                double angle = n * Math.PI * 2D / 12D;
                nodes.add(node(i++, centre[0] + Math.cos(angle) * 3D, centre[1] + Math.sin(angle) * 3D));
            }
        }
        return nodes;
    }

    /** Four nodes at distance sqrt(0.5) from the query, so only the id tie rule can order them. */
    private static List<Node> equidistantNodes() {
        List<Node> nodes = new ArrayList<>();
        nodes.add(node(9, 1D, 1D));
        nodes.add(node(4, 0D, 0D));
        nodes.add(node(7, 0D, 1D));
        nodes.add(node(3, 1D, 0D));
        nodes.add(node(1, 5D, 5D));
        return nodes;
    }

    private static List<Node> duplicateNodes() {
        List<Node> nodes = new ArrayList<>();
        nodes.add(node(2, 3D, 3D));
        nodes.add(node(5, 3D, 3D));
        nodes.add(node(1, 3D, 3D));
        nodes.add(node(8, -3D, -3D));
        nodes.add(node(0, -3D, -3D));
        return nodes;
    }

    /** Nodes exactly on cell corners and on cell edges, where floor-based cell assignment flips. */
    private static List<Node> boundaryNodes(double cellSize) {
        List<Node> nodes = new ArrayList<>();
        int i = 0;
        nodes.add(node(i++, 0D, 0D));
        nodes.add(node(i++, cellSize, 0D));
        nodes.add(node(i++, 0D, cellSize));
        nodes.add(node(i++, cellSize, cellSize));
        nodes.add(node(i++, -cellSize, -cellSize));
        nodes.add(node(i++, 2D * cellSize, 3D * cellSize));
        nodes.add(node(i++, 0.5D * cellSize, 0.5D * cellSize));
        nodes.add(node(i++, -0.5D * cellSize, 1.5D * cellSize));
        return nodes;
    }

    // ---------------------------------------------------------------- query sets

    private static List<double[]> unitQueries() {
        List<double[]> queries = new ArrayList<>();
        double[] exact = {0D, 1D, 2D, 8D, 19D, 19.5D, 40D, -1D, -0.5D, -20D};
        for (double x : exact) {
            for (double z : exact) {
                queries.add(new double[] {x, z});
            }
        }
        queries.add(new double[] {0.5D, 0.5D});
        queries.add(new double[] {9.5D, 9.5D});
        return queries;
    }

    private static List<double[]> spacedQueries() {
        List<double[]> queries = new ArrayList<>();
        double[] exact = {0D, 8D, 16D, 52D, 56D, 60D, 104D, -8D, -4D, 30.5D};
        for (double x : exact) {
            for (double z : exact) {
                queries.add(new double[] {x, z});
            }
        }
        return queries;
    }

    private static List<double[]> negativeQueries() {
        List<double[]> queries = new ArrayList<>();
        double[] exact = {-48D, -40D, -20D, -12.5D, -8D, -1D, 0D, 4D, -50D, -44D};
        for (double x : exact) {
            for (double z : exact) {
                queries.add(new double[] {x, z});
            }
        }
        return queries;
    }

    private static List<double[]> clusteredQueries() {
        List<double[]> queries = new ArrayList<>();
        double[] exact = {0D, 1.5D, 3D, 6D, 100D, 103D, -250D, 7.5D, 200D, -400D};
        for (double x : exact) {
            for (double z : exact) {
                queries.add(new double[] {x, z});
            }
        }
        return queries;
    }

    private static List<double[]> equidistantQueries() {
        List<double[]> queries = new ArrayList<>();
        queries.add(new double[] {0.5D, 0.5D});
        queries.add(new double[] {0D, 0D});
        queries.add(new double[] {1D, 1D});
        queries.add(new double[] {0D, 1D});
        queries.add(new double[] {0.5D, 0D});
        queries.add(new double[] {5D, 5D});
        return queries;
    }

    private static List<double[]> duplicateQueries() {
        List<double[]> queries = new ArrayList<>();
        queries.add(new double[] {3D, 3D});
        queries.add(new double[] {0D, 0D});
        queries.add(new double[] {-3D, -3D});
        queries.add(new double[] {3.4D, 2.6D});
        return queries;
    }

    private static List<double[]> boundaryQueries(double cellSize) {
        List<double[]> queries = new ArrayList<>();
        double[] exact = {0D, cellSize, 2D * cellSize, -cellSize, 0.5D * cellSize, 1.5D * cellSize, 3D * cellSize};
        for (double x : exact) {
            for (double z : exact) {
                queries.add(new double[] {x, z});
            }
        }
        return queries;
    }

    private static double[] radii() {
        return new double[] {0D, 0.25D, 5D, 20D, 200D, 5000D};
    }

    private static double[] smallRadii() {
        return new double[] {0D, 0.5D, 1D, 2D, 6D, 50D};
    }

    private static double[] boundaryRadii(double cellSize) {
        double d = Math.sqrt(2D) * cellSize;
        return new double[] {0D, 0.5D * cellSize, cellSize, d, 2D * cellSize, 3D * cellSize, 10D * cellSize};
    }

    private static int[] limits() {
        return new int[] {0, 1, 2, 3, 4, 5, 12};
    }

    // ---------------------------------------------------------------- plumbing

    private static Node node(int index, double x, double z) {
        return new Node(new UUID(0x5EEDL, index), x, 64D, z, Source.USER, 1, 0L);
    }

    private static void eq(String tag, String expected, String actual) {
        if (bridge != null) {
            bridge.eq(tag, actual, expected);
            return;
        }
        assertions++;
        if (!expected.equals(actual)) {
            failures++;
            if (failures <= 20) {
                System.out.println("FAIL " + tag + "\n  expected=" + expected + "\n  actual  =" + actual);
            }
        }
    }

    private static final class MutableSource implements NodeSpatialIndex.NodeSource {
        private List<Node> nodes;
        private long revision;

        MutableSource(List<Node> nodes) {
            this.nodes = new ArrayList<>(nodes);
        }

        void set(List<Node> next) {
            this.nodes = new ArrayList<>(next);
            this.revision++;
        }

        @Override
        public Collection<Node> nodes() {
            return nodes;
        }

        @Override
        public int nodeCount() {
            return nodes.size();
        }

        @Override
        public long revision() {
            return revision;
        }
    }

    private KnnDiffTest() {}

    static {
        new Random(1L);
    }
}
