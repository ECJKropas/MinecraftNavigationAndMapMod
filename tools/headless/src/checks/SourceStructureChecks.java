package checks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Structural checks on the delivered sources.
 *
 * <p>
 * The B2 contract is not only about numbers: the search core must be decoupled from Minecraft, the router must have
 * given up its private Dijkstra, and the new candidate / k-nearest entry points must exist while the old ones survive.
 * Those are all falsifiable statements about the source text, so they are checked here instead of being asserted in
 * prose.
 * </p>
 */
public final class SourceStructureChecks {
    private static final List<String> MINECRAFT_MARKERS =
        List.of("net.minecraft", "net.fabricmc", "MinecraftClient", "Minecraft.getInstance");

    private SourceStructureChecks() {}

    /**
     * @param sourceRoot the {@code .../com/ecjkim/wayfarer/client/road} package directory of the repository
     */
    public static void run(Checks c, Path sourceRoot) {
        Path road = sourceRoot;
        String search = read(c, road.resolve("nav/GraphSearch.java"));
        String router = read(c, road.resolve("nav/Router.java"));
        String index = read(c, road.resolve("spatial/NodeSpatialIndex.java"));
        String snapper = read(c, road.resolve("nav/LocationSnapper.java"));
        String session = read(c, road.resolve("nav/NavigationSession.java"));

        // The pure logic lives in nav / spatial / model and must stay loadable without Minecraft on the classpath.
        for (String pkg : List.of("nav", "spatial", "model")) {
            noMinecraftDependency(c, road.resolve(pkg));
        }

        // Router gave up its private Dijkstra and delegates to the shared search core.
        c.isTrue("Router delegates to GraphSearch", router.contains("GraphSearch.search("));
        c.isTrue("Router no longer owns a priority queue", !router.contains("PriorityQueue"));
        c.isTrue("Router keeps the single-point signature",
            router.contains("route(Node start, Node end, double tripDistance)"));
        c.isTrue("Router offers a candidate endpoint overload",
            router.contains("route(List<Node> starts, List<Node> ends, double tripDistance)"));

        // Admissibility of the heuristic: divide by the fastest speed anywhere in the graph.
        c.isTrue("GraphSearch exposes the graph speed bound", search.contains("maxSpeed()"));
        c.isTrue("GraphSearch documents the admissibility argument", search.contains("admissible"));
        c.isTrue("GraphSearch has a deterministic open-set order", search.contains("ENTRY_ORDER"));
        c.isTrue("GraphSearch accepts candidate sources and targets",
            search.contains("Collection<UUID> sources") && search.contains("Collection<UUID> targets"));

        // k-nearest on the index, candidate query on the snapper, wiring in the session; old APIs untouched.
        c.isTrue("NodeSpatialIndex offers a k nearest query", index.contains("double radius, int limit"));
        c.isTrue("LocationSnapper offers candidate nodes",
            snapper.contains("public static List<Node> nearestNodes("));
        c.isTrue("LocationSnapper keeps the single node API",
            snapper.contains("public static Optional<Node> nearestNode("));
        c.isTrue("NavigationSession snaps candidates", session.contains("nearestNodes("));
        c.isTrue("NavigationSession keeps the historical error codes",
            session.contains("DESTINATION_NOT_NEAR_ROAD") && session.contains("START_NOT_NEAR_ROAD"));
    }

    private static void noMinecraftDependency(Checks c, Path packageDir) {
        if (!Files.isDirectory(packageDir)) {
            c.isTrue("package directory exists: " + packageDir, false);
            return;
        }
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> files = Files.walk(packageDir)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(sources::add);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        c.isTrue("package directory holds sources: " + packageDir, !sources.isEmpty());
        for (Path file : sources) {
            String content = read(c, file);
            for (String marker : MINECRAFT_MARKERS) {
                c.isTrue("no Minecraft dependency in " + file.getFileName() + " (" + marker + ")",
                    !content.contains(marker));
            }
        }
    }

    private static String read(Checks c, Path file) {
        try {
            c.isTrue("file exists: " + file, Files.isRegularFile(file));
            return Files.isRegularFile(file) ? Files.readString(file) : "";
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
