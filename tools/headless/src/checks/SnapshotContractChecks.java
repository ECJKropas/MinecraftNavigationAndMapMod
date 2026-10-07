package checks;

import com.ecjkim.wayfarer.client.WayfarerConfig;
import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Direction;
import com.ecjkim.wayfarer.client.road.model.Node;
import com.ecjkim.wayfarer.client.road.nav.NavigationSession;
import com.ecjkim.wayfarer.client.road.nav.Route;

/**
 * The contract every consumer of a {@link NavigationSession.Snapshot} may rely on: the HUD renderer, the HTTP server
 * and the web panel all read this record and nothing else.
 *
 * <p>
 * Only invariants that hold regardless of how the remaining distance is computed are asserted here - the snapshot is
 * consistent with its route, the numbers are finite and non-negative, a fresh route reports the full distance, the
 * destination end reports zero, and {@code stop()} clears everything. The exact monotonicity of the remaining
 * distance along a walk is deliberately *not* pinned down: it is the subject of its own task.
 * </p>
 */
public final class SnapshotContractChecks {
    private SnapshotContractChecks() {}

    public static void run(Checks c) {
        WayfarerConfig.getInstance().setNavDistanceGate(200.0D);
        activeSnapshot(c);
        arrival(c);
        clearedAfterStop(c);
    }

    private static void activeSnapshot(Checks c) {
        String tag = "snapshot/active";
        RoadNetworkDatabase db = straightLine();
        NavigationSession session = new NavigationSession(db);
        session.updatePlayer(0, 0);

        NavigationSession.Result result = session.start(100, 0);
        c.isTrue(tag + " start succeeds", result.success());
        NavigationSession.Snapshot snapshot = result.snapshot();
        c.eq(tag + " state is ACTIVE", snapshot.state(), NavigationSession.State.ACTIVE);
        c.eq(tag + " error is empty", snapshot.error(), null);

        Route route = snapshot.route();
        c.isTrue(tag + " route is present", route != null);
        c.eq(tag + " node count", route.getNodes().size(), 3);
        c.eq(tag + " segment count", route.getEdgeLengths().size(), 2);
        c.eq(tag + " destination is the snapped end", snapshot.destination().getId(), route.getEndId());
        c.exact(tag + " total distance", route.getTotalDistance(), 100.0D);
        c.isTrue(tag + " eta is finite and non-negative",
            finite(route.getEtaSeconds()) && route.getEtaSeconds() >= 0.0D);
        c.isTrue(tag + " snapshot keeps the player position",
            snapshot.playerX() == 0.0D && snapshot.playerZ() == 0.0D);
        c.isTrue(tag + " non-negative finite remaining at start",
            finite(snapshot.remainingDistance()) && snapshot.remainingDistance() >= 0.0D);
        c.closeTo(tag + " remaining is the whole route at the start", snapshot.remainingDistance(), 100.0D);

        // Walking must keep the snapshot self-consistent. What the remaining distance actually does while
        // re-planning happens is not pinned down here - only that the record never goes non-finite, negative or
        // route-less, and that its route still ends where the snapshot says the destination is.
        for (double x = 0.0D; x <= 100.0D; x += 10.0D) {
            session.updatePlayer(x, 0);
            NavigationSession.Snapshot walking = session.snapshot();
            double remaining = walking.remainingDistance();
            c.isTrue(tag + " finite non-negative remaining at x=" + x, finite(remaining) && remaining >= 0.0D);
            c.isTrue(tag + " state stays on the way at x=" + x,
                walking.state() == NavigationSession.State.ACTIVE || walking.state() == NavigationSession.State.ARRIVED);
            c.isTrue(tag + " route survives the walk at x=" + x, walking.route() != null);
            if (walking.route() != null) {
                c.eq(tag + " route ends at the destination at x=" + x, walking.route().getEndId(),
                    walking.destination().getId());
            }
        }
    }

    private static void arrival(Checks c) {
        String tag = "snapshot/arrival";
        RoadNetworkDatabase db = straightLine();
        NavigationSession session = new NavigationSession(db);
        session.updatePlayer(0, 0);
        session.start(100, 0);

        session.updatePlayer(100, 0);
        NavigationSession.Snapshot arrived = session.snapshot();
        c.eq(tag + " state flips to ARRIVED", arrived.state(), NavigationSession.State.ARRIVED);
        c.isTrue(tag + " the route is kept for the arrival render", arrived.route() != null);
        c.isTrue(tag + " remaining at the destination is zero-ish", arrived.remainingDistance() < 1.0D);
    }

    private static void clearedAfterStop(Checks c) {
        String tag = "snapshot/stop";
        RoadNetworkDatabase db = straightLine();
        NavigationSession session = new NavigationSession(db);
        session.updatePlayer(0, 0);
        session.start(100, 0);
        session.stop();

        NavigationSession.Snapshot stopped = session.snapshot();
        c.eq(tag + " state is IDLE", stopped.state(), NavigationSession.State.IDLE);
        c.eq(tag + " route is dropped", stopped.route(), null);
        c.eq(tag + " destination is dropped", stopped.destination(), null);
        c.exact(tag + " remaining is zero", stopped.remainingDistance(), 0.0D);
        c.eq(tag + " error is cleared", stopped.error(), null);

        // A player update while idle must not resurrect anything.
        session.updatePlayer(42, 42);
        NavigationSession.Snapshot afterWalk = session.snapshot();
        c.eq(tag + " idle stays idle", afterWalk.state(), NavigationSession.State.IDLE);
        c.eq(tag + " idle keeps the new position readable", afterWalk.playerX(), 42.0D);
        c.eq(tag + " idle keeps no route", afterWalk.route(), null);
    }

    /** One bidirectional road holding three nodes at x = 0 / 50 / 100, the default 4.3 walk speed. */
    private static RoadNetworkDatabase straightLine() {
        RoadNetworkDatabase db = new RoadNetworkDatabase();
        Node a = NavigationSessionChecks.node(0, 0, 0);
        Node b = NavigationSessionChecks.node(1, 50, 0);
        Node d = NavigationSessionChecks.node(2, 100, 0);
        NavigationSessionChecks.addNode(db, a, b, d);
        NavigationSessionChecks.link(db, 0, "", Direction.BIDIRECTIONAL, a, b, d);
        return db;
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }
}
