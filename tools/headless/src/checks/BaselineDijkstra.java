package checks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;

import com.ecjkim.wayfarer.client.road.nav.GraphSearch;

/**
 * The pre-B2 search, kept verbatim as the differential baseline.
 *
 * <p>
 * This is a copy of the Dijkstra that used to live in {@code Router.route(Node, Node, double)}: a cost-only priority
 * queue, no heuristic, "break on first pop of the goal", and path reconstruction through {@code previous} /
 * {@code previousEdge}. It is intentionally written against a plain {@code Map<UUID, List<Edge>>} so it shares no code
 * with {@code GraphSearch}.
 * </p>
 */
public final class BaselineDijkstra {
    private BaselineDijkstra() {}

    public record Edge(UUID to, double length, double cost) {
    }

    public record Path(List<UUID> nodes, List<Double> edgeLengths, double totalCost) {
    }

    /**
     * Re-types a {@link GraphSearch}-shaped adjacency map into this baseline's own edge record, so the two searches
     * can be run over the exact same edge data without sharing a single line of search code.
     */
    public static Map<UUID, List<Edge>> from(Map<UUID, List<GraphSearch.Edge>> adjacency) {
        Map<UUID, List<Edge>> result = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<GraphSearch.Edge>> entry : adjacency.entrySet()) {
            List<Edge> edges = new ArrayList<>(entry.getValue().size());
            for (GraphSearch.Edge edge : entry.getValue()) {
                edges.add(new Edge(edge.to(), edge.length(), edge.cost()));
            }
            result.put(entry.getKey(), edges);
        }
        return result;
    }

    /**
     * @return the cheapest path, or {@code null} when {@code start} is not part of the graph or no path exists — the
     *         same two situations the old router reported as {@code NO_ROAD} / {@code NO_ROUTE}.
     */
    public static Path search(Map<UUID, List<Edge>> graph, UUID start, UUID end) {
        // The old router short-circuited identical endpoints before it ever looked at the graph; keep that order.
        if (start.equals(end)) {
            return new Path(List.of(start), List.of(), 0D);
        }
        if (!graph.containsKey(start) || !graph.containsKey(end)) {
            return null;
        }
        Map<UUID, Double> distance = new HashMap<>();
        Map<UUID, UUID> previous = new HashMap<>();
        Map<UUID, Edge> previousEdge = new HashMap<>();
        PriorityQueue<State> queue = new PriorityQueue<>(Comparator.comparingDouble(State::cost));
        distance.put(start, 0D);
        queue.add(new State(start, 0D));
        while (!queue.isEmpty()) {
            State state = queue.poll();
            if (state.cost() > distance.getOrDefault(state.id(), Double.POSITIVE_INFINITY)) {
                continue;
            }
            if (state.id().equals(end)) {
                break;
            }
            for (Edge edge : graph.getOrDefault(state.id(), List.of())) {
                double next = state.cost() + edge.cost();
                if (next < distance.getOrDefault(edge.to(), Double.POSITIVE_INFINITY)) {
                    distance.put(edge.to(), next);
                    previous.put(edge.to(), state.id());
                    previousEdge.put(edge.to(), edge);
                    queue.add(new State(edge.to(), next));
                }
            }
        }
        if (!distance.containsKey(end)) {
            return null;
        }
        ArrayDeque<UUID> nodes = new ArrayDeque<>();
        ArrayDeque<Double> lengths = new ArrayDeque<>();
        UUID current = end;
        nodes.addFirst(current);
        while (!current.equals(start)) {
            Edge edge = previousEdge.get(current);
            if (edge == null) {
                return null;
            }
            lengths.addFirst(edge.length());
            current = previous.get(current);
            nodes.addFirst(current);
        }
        return new Path(List.copyOf(nodes), List.copyOf(lengths), distance.get(end));
    }

    private record State(UUID id, double cost) {
    }
}
