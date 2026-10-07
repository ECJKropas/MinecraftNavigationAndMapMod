package checks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Headless entry point for the batched checks.
 *
 * <p>
 * Run {@code ./run.sh} next to this file: it compiles the real {@code nav} / {@code spatial} / {@code model} sources
 * against the stub config and database in {@code src/stub}, then calls this class. Exit code 0 means every check
 * passed, 1 means at least one check failed, 2 means the repository could not be found.
 * </p>
 *
 * <p>
 * Six suites: the spatial index against a full scan, the shared search against a copied pre-refactor Dijkstra, the
 * real {@code Router} against an independent graph build, the {@code NavigationSession} wiring, the snapshot contract
 * the HUD/HTTP consumers depend on, and the structural properties of the delivered sources.
 * </p>
 */
public final class Master {
    private static final String DEFAULT_REPO = "/Users/cjkim/Documents/MinecraftNavigationAndMapMod";

    private Master() {}

    public static void main(String[] args) {
        Path repo = Path.of(args.length > 0 && !args[0].isBlank() ? args[0] : DEFAULT_REPO);
        Path sourceRoot = repo.resolve("src/main/java/com/ecjkim/wayfarer/client/road");
        if (!Files.isDirectory(sourceRoot)) {
            System.out.println("repo source root not found: " + sourceRoot + " (usage: Master [repo-root])");
            System.exit(2);
            return;
        }

        List<Suite> suites = List.of(new Suite("spatial-index-vs-brute-force", KnnDiffTest::run),
            new Suite("graph-search-vs-dijkstra", GraphSearchDiffTest::run),
            new Suite("router-vs-dijkstra", RouterDiffTest::run),
            new Suite("navigation-session-wiring", NavigationSessionChecks::run),
            new Suite("snapshot-contract", SnapshotContractChecks::run),
            new Suite("source-structure", checks -> SourceStructureChecks.run(checks, sourceRoot)));

        System.out.println("Wayfarer headless checks - navigation core");
        System.out.println("repo: " + repo);
        int totalChecks = 0;
        int totalFailures = 0;
        for (Suite suite : suites) {
            Checks checks = new Checks(suite.name());
            long started = System.nanoTime();
            System.out.println();
            System.out.println("-- suite " + suite.name());
            try {
                suite.body().accept(checks);
            } catch (RuntimeException | Error exception) {
                System.out.println("  ABORTED: " + exception);
                exception.printStackTrace(System.out);
                totalFailures++;
            }
            totalChecks += checks.checks();
            totalFailures += checks.failures();
            System.out.printf("-- suite %s: assertions=%d failures=%d elapsed=%dms%n", suite.name(), checks.checks(),
                checks.failures(), (System.nanoTime() - started) / 1_000_000L);
        }

        System.out.println();
        System.out.printf("TOTAL assertions=%d failures=%d%n", totalChecks, totalFailures);
        System.out.println(totalFailures == 0 ? "VERDICT: PASS" : "VERDICT: FAIL");
        System.exit(totalFailures == 0 ? 0 : 1);
    }

    private record Suite(String name, Consumer<Checks> body) {
    }
}
