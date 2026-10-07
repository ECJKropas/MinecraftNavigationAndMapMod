package com.ecjkim.wayfarer.client.road.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ecjkim.wayfarer.client.road.model.Node;
import com.ecjkim.wayfarer.client.road.model.Road;
import com.ecjkim.wayfarer.client.road.model.Segment;

/**
 * In-memory stand-in for the real file-backed {@code RoadNetworkDatabase} (which needs FabricLoader, Gson and malilib
 * to even load).
 *
 * <p>
 * The surface mirrors what {@code Router} uses, and {@link #getNodesForSegment(UUID)} reproduces the real behaviour
 * exactly: nodes are returned in {@code segment.getNodeIds()} order, missing ids are skipped.
 * </p>
 */
public final class RoadNetworkDatabase {
    private final Map<UUID, Node> nodes = new LinkedHashMap<>();
    private final Map<UUID, Segment> segments = new LinkedHashMap<>();
    private final Map<UUID, Road> roads = new LinkedHashMap<>();
    private long mutations;

    public void addNode(Node node) {
        nodes.put(node.getId(), node);
        mutations++;
    }

    public void addSegment(Segment segment) {
        segments.put(segment.getId(), segment);
        mutations++;
    }

    public void addRoad(Road road) {
        roads.put(road.getId(), road);
        mutations++;
    }

    public Node getNode(UUID id) {
        return nodes.get(id);
    }

    /** Snapshot copy, exactly like the real database's {@code getAllNodes()}. */
    public Collection<Node> getAllNodes() {
        return new ArrayList<>(nodes.values());
    }

    public Road getRoad(UUID id) {
        return roads.get(id);
    }

    public Collection<Segment> getAllSegments() {
        return new ArrayList<>(segments.values());
    }

    public List<Node> getNodesForSegment(UUID segmentId) {
        Segment segment = segments.get(segmentId);
        if (segment == null || segment.getNodeIds() == null) {
            return new ArrayList<>();
        }
        List<Node> result = new ArrayList<>();
        for (UUID nodeId : segment.getNodeIds()) {
            Node node = nodes.get(nodeId);
            if (node != null) {
                result.add(node);
            }
        }
        return result;
    }

    public int getNodeCount() {
        return nodes.size();
    }

    public long getMutationStamp() {
        return mutations;
    }
}
