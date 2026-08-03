/*
 * Copyright (C) 2025  MinecraftNavigationAndMapMod contributors
 * https://github.com/ECJKropas/MinecraftNavigationAndMapMod

 * MinecraftNavigationAndMapMod is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.

 * MinecraftNavigationAndMapMod is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with MinecraftNavigationAndMapMod.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.ecjkim.wayfarer.client.road.data;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.fabricmc.loader.api.FabricLoader;

import com.ecjkim.wayfarer.client.WayfarerConfig;
import com.ecjkim.wayfarer.client.road.model.CornerType;
import com.ecjkim.wayfarer.client.road.model.Node;
import com.ecjkim.wayfarer.client.road.model.Road;
import com.ecjkim.wayfarer.client.road.model.Segment;
import com.ecjkim.wayfarer.client.road.model.Source;
import com.ecjkim.wayfarer.client.road.model.Status;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

/**
 * Singleton database holding the complete road network in memory.
 *
 * <p>
 * Stores data to {@code wayfarer/roads.json} inside the game run directory. All public write methods are synchronized
 * for thread safety.
 * </p>
 */
public class RoadNetworkDatabase {
    private static final Logger LOGGER = Logger.getLogger("Wayfarer|RoadNetwork");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile RoadNetworkDatabase instance;

    private final ConcurrentHashMap<UUID, Node> nodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Segment> segments = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Road> roads = new ConcurrentHashMap<>();

    private Path savePath;
    private String worldKey;
    private volatile boolean dirty;

    private RoadNetworkDatabase() {
        savePath = FabricLoader.getInstance().getGameDir().resolve("wayfarer/roads.json");
        worldKey = null;
    }

    /**
     * Returns the singleton instance (double-checked locking).
     */
    public static RoadNetworkDatabase getInstance() {
        RoadNetworkDatabase result = instance;
        if (result == null) {
            synchronized (RoadNetworkDatabase.class) {
                result = instance;
                if (result == null) {
                    instance = new RoadNetworkDatabase();
                    result = instance;
                }
            }
        }
        return result;
    }

    /**
     * Switches the backing file to {@code wayfarer/{worldKey}/roads.json} in the game run directory, creates the target
     * directory, and calls {@link #loadFromDisk()}.
     */
    public synchronized void setWorldKey(String worldKey) {
        if (worldKey == null || worldKey.isEmpty()) {
            worldKey = "default";
        }
        // Sanitize worldKey to be safe for directory names (e.g., replace colons in IP addresses)
        worldKey = worldKey.replace(':', '_').replace('/', '_').replace('\\', '_').replace('*', '_').replace('?', '_')
            .replace('"', '_').replace('<', '_').replace('>', '_').replace('|', '_');
        if (worldKey.equals(this.worldKey)) {
            return;
        }

        // Save current world's data before switching (if dirty and already initialized)
        if (this.worldKey != null && dirty) {
            LOGGER.log(Level.INFO, "Auto-saving road network for world {0} before switching", this.worldKey);
            saveToDisk();
        }

        this.worldKey = worldKey;

        Path newDir = FabricLoader.getInstance().getGameDir().resolve("wayfarer").resolve(worldKey);
        this.savePath = newDir.resolve("roads.json");

        try {
            Files.createDirectories(newDir);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to create directory for world key {0}: {1}",
                new Object[] {worldKey, e.getMessage()});
            return;
        }

        loadFromDisk();
    }

    /** Returns the current world key, or null if not yet set. */
    public String getWorldKey() {
        return worldKey;
    }

    // ---------- Node CRUD ----------

    public synchronized void addNode(Node node) {
        nodes.put(node.getId(), node);
        node.setModifiedAt(System.currentTimeMillis());
        markDirty();
    }

    public Node getNode(UUID id) {
        return nodes.get(id);
    }

    public synchronized void updateNode(UUID id, Node updated) {
        Node existing = nodes.get(id);
        if (existing != null) {
            existing.setCornerType(updated.getCornerType());
            existing.setModifiedAt(System.currentTimeMillis());
            markDirty();
        }
    }

    /**
     * Updates node position (x/z) and increments version. Returns the new version, or -1 if not found.
     */
    public synchronized int updateNodePosition(UUID id, double x, double z) {
        Node existing = nodes.get(id);
        if (existing == null)
            return -1;
        existing.setX(x);
        existing.setZ(z);
        existing.setModifiedAt(System.currentTimeMillis());
        int nextVersion = existing.getVersion() + 1;
        existing.setVersion(nextVersion);
        markDirty();
        return nextVersion;
    }

    public synchronized void removeNode(UUID id) {
        nodes.remove(id);
        maybeCleanupOrphans();
    }

    /**
     * Merges nodeToDelete into targetNode: all segments referencing nodeToDelete are rewired to targetNode, consecutive
     * duplicates are collapsed, and nodeToDelete is removed.
     */
    public synchronized void mergeNodes(UUID nodeToDeleteId, UUID targetNodeId) {
        for (Segment seg : getAllSegments()) {
            List<UUID> ids = seg.getNodeIds();
            if (ids == null)
                continue;
            boolean changed = false;
            for (int i = 0; i < ids.size(); i++) {
                if (ids.get(i).equals(nodeToDeleteId)) {
                    ids.set(i, targetNodeId);
                    changed = true;
                }
            }
            if (!changed)
                continue;

            // collapse consecutive duplicates
            List<UUID> deduped = new ArrayList<>();
            UUID prev = null;
            for (UUID id : ids) {
                if (!id.equals(prev)) {
                    deduped.add(id);
                }
                prev = id;
            }
            seg.setNodeIds(deduped);
            seg.setVersion(seg.getVersion() + 1);
        }
        nodes.remove(nodeToDeleteId);
        maybeCleanupOrphans();
    }

    /**
     * Merges two nodes that are non-adjacent on the same segment. Shortens the segment by removing all intermediate
     * nodes, cleans them from any other segments that reference them, deletes the intermediate nodes, then performs the
     * normal merge.
     */
    public synchronized void mergeNodesWithCleanup(UUID nodeToDeleteId, UUID targetNodeId, UUID segmentId,
        List<UUID> intermediateIds) {
        Set<UUID> toRemove = new HashSet<>(intermediateIds);

        // 1. Shorten all segments by removing intermediate node IDs
        java.util.List<UUID> segsToRemove = new ArrayList<>();
        for (Segment seg : getAllSegments()) {
            List<UUID> ids = seg.getNodeIds();
            if (ids == null || ids.isEmpty())
                continue;

            List<UUID> filtered = new ArrayList<>();
            for (UUID id : ids) {
                if (!toRemove.contains(id)) {
                    filtered.add(id);
                }
            }

            if (filtered.size() < ids.size()) {
                seg.setNodeIds(filtered);
                seg.setVersion(seg.getVersion() + 1);
            }

            if (seg.getNodeIds().size() < 2) {
                segsToRemove.add(seg.getId());
            }
        }

        // 2. Remove invalid segments (fewer than 2 nodes)
        for (UUID segId : segsToRemove) {
            segments.remove(segId);
        }

        // 3. Delete intermediate nodes from the nodes map
        for (UUID midId : intermediateIds) {
            nodes.remove(midId);
        }

        // 4. Perform normal merge (rewires nodeToDelete → targetNode, collapses dupes, removes nodeToDelete)
        mergeNodes(nodeToDeleteId, targetNodeId);

        // 5. After merge, clean up any segments that became invalid (e.g. 2-node segment collapsed to 1 node)
        segsToRemove.clear();
        for (Segment seg : getAllSegments()) {
            if (seg.getNodeIds() == null || seg.getNodeIds().size() < 2) {
                segsToRemove.add(seg.getId());
            }
        }
        for (UUID segId : segsToRemove) {
            segments.remove(segId);
        }

        if (!segsToRemove.isEmpty()) {
            markDirty();
        }
        maybeCleanupOrphans();
        markDirty();
        // markDirty already called by mergeNodes; extra call harmless
    }

    /**
     * Soft-deletes a node with different strategies depending on its degree:
     * <ul>
     * <li>Degree 1 (endpoint): Shortens the segment by removing the endpoint.</li>
     * <li>Even degree (center node): Pairs opposite-direction segments and merges them, removing the center node and
     * collapsing the junction.</li>
     * <li>Odd degree >1 or 2-node segment endpoint: Returns error.</li>
     * </ul>
     *
     * @return a result object with keys "ok" (boolean), "action" (String), and optionally "error" and "message".
     */
    public synchronized JsonObject softDeleteNode(UUID nodeId) {
        Node center = nodes.get(nodeId);
        if (center == null) {
            return errorResult("not_found");
        }

        // Find all segments containing this node
        List<Segment> connectedSegments = new ArrayList<>();
        for (Segment seg : getAllSegments()) {
            if (seg.getNodeIds() != null && seg.getNodeIds().contains(nodeId)) {
                connectedSegments.add(seg);
            }
        }

        // Compute degree: count unique neighbour nodes across all segments
        Set<UUID> neighbours = new HashSet<>();
        for (Segment seg : connectedSegments) {
            List<UUID> ids = seg.getNodeIds();
            int idx = ids.indexOf(nodeId);
            if (idx > 0)
                neighbours.add(ids.get(idx - 1));
            if (idx < ids.size() - 1)
                neighbours.add(ids.get(idx + 1));
        }
        int degree = neighbours.size();

        // --- Degree 1: endpoint ---
        if (degree == 1) {
            Segment seg = connectedSegments.get(0);
            if (seg.getNodeIds().size() <= 2) {
                return errorResult("unsupported", "该节点不支持软删除");
            }
            // Shorten: remove endpoint from segment
            List<UUID> newIds = new ArrayList<>(seg.getNodeIds());
            newIds.remove(nodeId);
            seg.setNodeIds(newIds);
            seg.setVersion(seg.getVersion() + 1);
            nodes.remove(nodeId);
            maybeCleanupOrphans();
            saveToDisk();
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("action", "endpoint_shortened");
            return result;
        }

        // --- Non-even degree > 1: unsupported ---
        if (degree % 2 != 0) {
            return errorResult("unsupported", "该节点不支持软删除");
        }

        // --- Even degree: center node ---
        return softDeleteCenter(nodeId, connectedSegments, center);
    }

    /**
     * Soft-deletes an even-degree center node by pairing opposite-direction segments and merging each pair. Rolls back
     * if any pair fails the road-name constraint.
     */
    private JsonObject softDeleteCenter(UUID centerId, List<Segment> connectedSegments, Node center) {
        int n = connectedSegments.size();

        // Collect per-segment info: through-direction and adjacent node IDs
        double[][] dirs = new double[n][2];
        UUID[][] adjs = new UUID[n][2]; // [leftAdj, rightAdj]
        int[] idxs = new int[n];

        for (int i = 0; i < n; i++) {
            Segment seg = connectedSegments.get(i);
            List<UUID> ids = seg.getNodeIds();
            int idx = ids.indexOf(centerId);
            idxs[i] = idx;

            // Compute through-direction. If the center node is at an endpoint of this
            // segment, use the center's own position as the anchor on that side.
            double leftX, leftZ, rightX, rightZ;

            if (idx > 0) {
                Node leftNode = nodes.get(ids.get(idx - 1));
                if (leftNode == null)
                    return errorResult("not_found");
                leftX = leftNode.getX();
                leftZ = leftNode.getZ();
            } else {
                leftX = center.getX();
                leftZ = center.getZ();
            }

            if (idx < ids.size() - 1) {
                Node rightNode = nodes.get(ids.get(idx + 1));
                if (rightNode == null)
                    return errorResult("not_found");
                rightX = rightNode.getX();
                rightZ = rightNode.getZ();
            } else {
                rightX = center.getX();
                rightZ = center.getZ();
            }

            adjs[i][0] = idx > 0 ? ids.get(idx - 1) : centerId;
            adjs[i][1] = idx < ids.size() - 1 ? ids.get(idx + 1) : centerId;

            double dx = rightX - leftX;
            double dz = rightZ - leftZ;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-6) {
                return errorResult("unsupported", "该节点不支持软删除");
            }
            dirs[i][0] = dx / len;
            dirs[i][1] = dz / len;
        }

        // Greedy pairing: pick first unpaired, find best 180° match that passes road check
        boolean[] paired = new boolean[n];
        int[][] pairIndices = new int[n / 2][2];
        int pairCount = 0;

        for (int i = 0; i < n; i++) {
            if (paired[i])
                continue;

            // Sort remaining unpaired segments by dot product (ascending → closest to -1 = 180°)
            final int fi = i;
            List<Integer> candidates = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                if (j != fi && !paired[j]) {
                    candidates.add(j);
                }
            }
            candidates.sort((a, b) -> {
                double dotA = dirs[fi][0] * dirs[a][0] + dirs[fi][1] * dirs[a][1];
                double dotB = dirs[fi][0] * dirs[b][0] + dirs[fi][1] * dirs[b][1];
                return Double.compare(dotA, dotB); // smallest (closest to -1) first
            });

            boolean found = false;
            for (int j : candidates) {
                UUID segIdI = connectedSegments.get(fi).getId();
                UUID segIdJ = connectedSegments.get(j).getId();
                if (sameRoadOrUnfiled(segIdI, segIdJ)) {
                    pairIndices[pairCount][0] = fi;
                    pairIndices[pairCount][1] = j;
                    pairCount++;
                    paired[fi] = true;
                    paired[j] = true;
                    found = true;
                    break;
                }
            }

            if (!found) {
                return errorResult("road_mismatch", "软删除失败，有不同名路段");
            }
        }

        // All pairs matched successfully. Execute merges.
        for (int p = 0; p < pairCount; p++) {
            int i = pairIndices[p][0];
            int j = pairIndices[p][1];
            Segment segI = connectedSegments.get(i);
            Segment segJ = connectedSegments.get(j);
            mergeSegmentPair(segI, idxs[i], segJ, idxs[j], centerId);
        }

        // Delete center node
        nodes.remove(centerId);
        maybeCleanupOrphans();
        saveToDisk();

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("action", "center_merged");
        return result;
    }

    /**
     * Merges two segments at their shared center node by removing the center and concatenating the two segments' node
     * lists.
     */
    private void mergeSegmentPair(Segment s1, int idx1, Segment s2, int idx2, UUID centerId) {
        List<UUID> ids1 = s1.getNodeIds();
        List<UUID> ids2 = s2.getNodeIds();

        // Build merged list: s1_before + s1_after + s2_before + s2_after (center removed)
        List<UUID> mergedIds = new ArrayList<>();
        for (int k = 0; k < idx1; k++)
            mergedIds.add(ids1.get(k));
        for (int k = idx1 + 1; k < ids1.size(); k++)
            mergedIds.add(ids1.get(k));
        for (int k = 0; k < idx2; k++)
            mergedIds.add(ids2.get(k));
        for (int k = idx2 + 1; k < ids2.size(); k++)
            mergedIds.add(ids2.get(k));

        // Determine road membership
        Road road1 = findRoadForSegment(s1.getId());
        Road road2 = findRoadForSegment(s2.getId());

        Segment merged = new Segment(UUID.randomUUID(), mergedIds, null, s1.getSource(), s1.getStatus(), 1);
        segments.put(merged.getId(), merged);

        if (road1 != null && road2 != null && road1.getId().equals(road2.getId())) {
            road1.getSegmentIds().add(merged.getId());
            road1.getSegmentIds().remove(s1.getId());
            road1.getSegmentIds().remove(s2.getId());
        } else if (road1 != null) {
            road1.getSegmentIds().add(merged.getId());
            road1.getSegmentIds().remove(s1.getId());
        } else if (road2 != null) {
            road2.getSegmentIds().add(merged.getId());
            road2.getSegmentIds().remove(s2.getId());
        }

        // Remove original segments
        segments.remove(s1.getId());
        segments.remove(s2.getId());
        markDirty();
    }

    /**
     * Returns the road that contains the given segment, or null if unfiled.
     */
    private Road findRoadForSegment(UUID segmentId) {
        for (Road road : roads.values()) {
            if (road.getSegmentIds() != null && road.getSegmentIds().contains(segmentId)) {
                return road;
            }
        }
        return null;
    }

    /**
     * Checks whether two segments can be paired: same road or at least one is unfiled.
     */
    private boolean sameRoadOrUnfiled(UUID segId1, UUID segId2) {
        Road road1 = findRoadForSegment(segId1);
        Road road2 = findRoadForSegment(segId2);
        if (road1 == null || road2 == null) {
            return true; // at least one unfiled
        }
        return road1.getId().equals(road2.getId());
    }

    private static JsonObject errorResult(String error) {
        JsonObject obj = new JsonObject();
        obj.addProperty("ok", false);
        obj.addProperty("error", error);
        return obj;
    }

    private static JsonObject errorResult(String error, String message) {
        JsonObject obj = errorResult(error);
        obj.addProperty("message", message);
        return obj;
    }

    // ---------- Segment CRUD ----------

    public synchronized void addSegment(Segment segment) {
        segment.setModifiedAt(System.currentTimeMillis());
        segments.put(segment.getId(), segment);
        markDirty();
    }

    public Segment getSegment(UUID id) {
        return segments.get(id);
    }

    public synchronized void updateSegment(UUID id, Segment updated) {
        Segment existing = segments.get(id);
        if (existing != null) {
            existing.setStatus(updated.getStatus());
            existing.setRoadId(updated.getRoadId());
            if (updated.getNodeIds() != null) {
                existing.setNodeIds(updated.getNodeIds());
            }
            existing.setVersion(existing.getVersion() + 1);
            existing.setModifiedAt(System.currentTimeMillis());
            maybeCleanupOrphans();
        }
    }

    public synchronized void removeSegment(UUID id) {
        segments.remove(id);
        markDirty();
        maybeCleanupOrphans();
    }

    /**
     * Removes nodes that are not referenced by any segment's nodeIds list. Honors the
     * {@code WayfarerConfig.Generic.AUTO_DELETE_ORPHAN_NODES} toggle. Returns the number of nodes removed (0 if the
     * feature is disabled).
     */
    public synchronized int removeOrphanNodes() {
        if (!WayfarerConfig.getInstance().isAutoDeleteOrphanNodes()) {
            return 0;
        }
        java.util.Set<UUID> referenced = new java.util.HashSet<>();
        for (Segment seg : segments.values()) {
            List<UUID> ids = seg.getNodeIds();
            if (ids != null) {
                referenced.addAll(ids);
            }
        }
        int removed = 0;
        java.util.Iterator<java.util.Map.Entry<UUID, Node>> it = nodes.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<UUID, Node> e = it.next();
            if (!referenced.contains(e.getKey())) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            markDirty();
            LOGGER.log(Level.INFO, "Auto-removed {0} orphan node(s)", removed);
        }
        return removed;
    }

    /**
     * Runs {@link #removeOrphanNodes()} only when the auto-delete toggle is enabled. Convenience no-op wrapper for use
     * at the end of CRUD operations.
     */
    private void maybeCleanupOrphans() {
        if (WayfarerConfig.getInstance().isAutoDeleteOrphanNodes()) {
            removeOrphanNodes();
        }
    }

    // Guard against recursive graphify when splitting inside graphify
    private boolean graphifying = false;

    /**
     * Runs {@link #graphify()} when the auto-graphify toggle is enabled and we are not already inside a graphify call.
     */
    private void maybeGraphify() {
        if (!graphifying && WayfarerConfig.getInstance().isAutoGraphify()) {
            graphifying = true;
            try {
                graphify();
            } finally {
                graphifying = false;
            }
        }
    }

    /**
     * Auto-graphifies the road network: for every node with degree &gt; 2 that is still an interior node in any
     * segment, splits that segment at this node so the node becomes a proper graph vertex (an endpoint in all incident
     * segments). This ensures Dijkstra / A* can operate on endpoints only.
     *
     * @return number of segments split
     */
    public synchronized int graphify() {
        int splits = 0;

        // Phase 1: collect nodes with degree > 2 and their interior segments
        // Iterate snapshots to avoid concurrent modification
        java.util.List<Node> allNodes = new java.util.ArrayList<>(nodes.values());
        java.util.List<Segment> allSegments = new java.util.ArrayList<>(segments.values());

        // nodeId -> list of segIds where node is interior (not endpoint)
        java.util.LinkedHashMap<UUID, java.util.List<UUID>> nodeToInteriorSegments = new java.util.LinkedHashMap<>();

        for (Node node : allNodes) {
            UUID nodeId = node.getId();

            // Find all segments containing this node
            java.util.List<Segment> containingSegments = new java.util.ArrayList<>();
            for (Segment seg : allSegments) {
                if (seg.getNodeIds() != null && seg.getNodeIds().contains(nodeId)) {
                    containingSegments.add(seg);
                }
            }
            if (containingSegments.size() < 2)
                continue;

            // Compute degree: count unique neighbour nodes
            java.util.Set<UUID> neighbours = new java.util.HashSet<>();
            for (Segment seg : containingSegments) {
                java.util.List<UUID> ids = seg.getNodeIds();
                int idx = ids.indexOf(nodeId);
                if (idx > 0)
                    neighbours.add(ids.get(idx - 1));
                if (idx < ids.size() - 1)
                    neighbours.add(ids.get(idx + 1));
            }
            if (neighbours.size() <= 2)
                continue;

            // Find segments where node is interior (not an endpoint)
            java.util.List<UUID> interiorSegIds = new java.util.ArrayList<>();
            for (Segment seg : containingSegments) {
                java.util.List<UUID> ids = seg.getNodeIds();
                int idx = ids.indexOf(nodeId);
                if (idx > 0 && idx < ids.size() - 1) {
                    interiorSegIds.add(seg.getId());
                }
            }
            if (!interiorSegIds.isEmpty()) {
                nodeToInteriorSegments.put(nodeId, interiorSegIds);
            }
        }

        // Phase 2: split interior segments
        for (java.util.Map.Entry<UUID, java.util.List<UUID>> entry : nodeToInteriorSegments.entrySet()) {
            UUID nodeId = entry.getKey();
            for (UUID segId : entry.getValue()) {
                Segment seg = segments.get(segId);
                if (seg == null || seg.getNodeIds() == null)
                    continue;
                int idx = seg.getNodeIds().indexOf(nodeId);
                if (idx <= 0 || idx >= seg.getNodeIds().size() - 1)
                    continue;
                splitSegment(segId, idx);
                splits++;
            }
        }

        if (splits > 0) {
            saveToDisk();
        }
        return splits;
    }

    // ---------- Road CRUD ----------

    public synchronized void addRoad(Road road) {
        road.setModifiedAt(System.currentTimeMillis());
        roads.put(road.getId(), road);
        markDirty();
    }

    public Road getRoad(UUID id) {
        return roads.get(id);
    }

    public synchronized void updateRoad(UUID id, Road updated) {
        Road existing = roads.get(id);
        if (existing != null) {
            existing.setName(updated.getName());
            existing.setClassification(updated.getClassification());
            existing.setNumber(updated.getNumber());
            if (updated.getSegmentIds() != null) {
                existing.setSegmentIds(updated.getSegmentIds());
            }
            if (updated.getColor() != null) {
                existing.setColor(updated.getColor());
            }
            existing.setVersion(existing.getVersion() + 1);
            existing.setModifiedAt(System.currentTimeMillis());
            markDirty();
        }
    }

    public synchronized void removeRoad(UUID id) {
        Road road = roads.get(id);
        if (road != null && road.getSegmentIds() != null) {
            for (UUID segId : road.getSegmentIds()) {
                Segment seg = segments.get(segId);
                if (seg != null) {
                    seg.setRoadId(null);
                }
            }
        }
        roads.remove(id);
        markDirty();
    }

    // ---------- Version control ----------

    /**
     * Atomically increment and return the new version for the entity identified by {@code id}. The caller is
     * responsible for applying the new version to the corresponding entity.
     */
    public synchronized int incrementVersion(UUID id) {
        // Version bumps are tracked per-entity type by checking all maps.
        // This is a simple counter approach: we read the current version from whichever map
        // holds the id and bump it. Since version is stored on the entity itself, we
        // return the new value for the caller to apply.
        Node node = nodes.get(id);
        if (node != null) {
            int next = node.getVersion() + 1;
            node.setVersion(next);
            node.setModifiedAt(System.currentTimeMillis());
            markDirty();
            return next;
        }
        Segment segment = segments.get(id);
        if (segment != null) {
            int next = segment.getVersion() + 1;
            segment.setVersion(next);
            segment.setModifiedAt(System.currentTimeMillis());
            markDirty();
            return next;
        }
        Road road = roads.get(id);
        if (road != null) {
            int next = road.getVersion() + 1;
            road.setVersion(next);
            road.setModifiedAt(System.currentTimeMillis());
            markDirty();
            return next;
        }
        return -1;
    }

    /**
     * Checks whether the entity identified by {@code id} has the expected version. Returns {@code true} if the versions
     * match (no conflict).
     */
    public boolean checkVersion(UUID id, int expectedVersion) {
        Node node = nodes.get(id);
        if (node != null) {
            return node.getVersion() == expectedVersion;
        }
        Segment segment = segments.get(id);
        if (segment != null) {
            return segment.getVersion() == expectedVersion;
        }
        Road road = roads.get(id);
        if (road != null) {
            return road.getVersion() == expectedVersion;
        }
        return false;
    }

    // ---------- Helper queries ----------

    /**
     * Returns an unmodifiable collection of all Nodes (snapshot).
     */
    public java.util.Collection<Node> getAllNodes() {
        return new java.util.ArrayList<>(nodes.values());
    }

    /**
     * Returns how many segments reference the given node.
     */
    public int getSegmentCountForNode(UUID nodeId) {
        int count = 0;
        for (Segment seg : segments.values()) {
            if (seg.getNodeIds() != null && seg.getNodeIds().contains(nodeId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns an unmodifiable collection of all Roads (snapshot).
     */
    public java.util.Collection<Road> getRoads() {
        return new java.util.ArrayList<>(roads.values());
    }

    /**
     * Returns an unmodifiable collection of all Segments (snapshot).
     */
    public java.util.Collection<Segment> getAllSegments() {
        return new java.util.ArrayList<>(segments.values());
    }

    /**
     * Returns all Nodes belonging to the given Segment, in {@code nodeIds} order.
     */
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

    /**
     * Returns all Segments belonging to the given Road.
     */
    public List<Segment> getSegmentsForRoad(UUID roadId) {
        Road road = roads.get(roadId);
        if (road == null || road.getSegmentIds() == null) {
            return new ArrayList<>();
        }
        List<Segment> result = new ArrayList<>();
        for (UUID segId : road.getSegmentIds()) {
            Segment seg = segments.get(segId);
            if (seg != null) {
                result.add(seg);
            }
        }
        return result;
    }

    /**
     * Appends a Segment to an existing Road and updates the Segment's roadId.
     */
    public synchronized void addSegmentToRoad(UUID roadId, UUID segmentId) {
        Road road = roads.get(roadId);
        if (road == null)
            return;
        Segment segment = segments.get(segmentId);
        if (segment == null)
            return;

        if (road.getSegmentIds() == null) {
            road.setSegmentIds(new ArrayList<>());
        }
        road.getSegmentIds().add(segmentId);
        segment.setRoadId(roadId);
        markDirty();
    }

    // ---------- Merge / Split ----------

    /**
     * Merges multiple segments into one. The segments must be connected end-to-end; duplicate endpoints are
     * de-duplicated. Old segments are removed. Returns the merged Segment, or null if precondition fails.
     */
    public synchronized Segment mergeSegments(List<UUID> segmentIds) {
        if (segmentIds == null || segmentIds.size() < 2)
            return null;

        List<UUID> mergedNodeIds = new ArrayList<>();
        for (UUID segId : segmentIds) {
            Segment seg = segments.get(segId);
            if (seg == null || seg.getNodeIds() == null || seg.getNodeIds().size() < 2)
                return null;
            if (mergedNodeIds.isEmpty()) {
                mergedNodeIds.addAll(seg.getNodeIds());
            } else {
                // Skip the first node (duplicate with previous segment's tail)
                List<UUID> ids = seg.getNodeIds();
                for (int i = 1; i < ids.size(); i++) {
                    mergedNodeIds.add(ids.get(i));
                }
            }
        }

        Segment merged = new Segment(UUID.randomUUID(), mergedNodeIds, null, Source.USER, Status.CONFIRMED, 1);
        segments.put(merged.getId(), merged);

        for (UUID segId : segmentIds) {
            segments.remove(segId);
        }

        markDirty();
        maybeCleanupOrphans();
        return merged;
    }

    /**
     * Merges the two segments that share {@code nodeId} as a common endpoint. The node must have degree 2 (exactly two
     * segments meeting at this node as endpoints). Returns the merged Segment, or null if the node is not a valid merge
     * point.
     */
    public synchronized Segment mergeSegmentsAtNode(UUID nodeId) {
        List<Segment> candidates = new ArrayList<>();
        // position: 0 = starts with node; -1 = ends with node
        java.util.Map<Segment, Integer> positions = new java.util.HashMap<>();

        for (Segment seg : segments.values()) {
            List<UUID> ids = seg.getNodeIds();
            if (ids == null || ids.size() < 2)
                continue;
            if (ids.get(0).equals(nodeId)) {
                candidates.add(seg);
                positions.put(seg, 0);
            } else if (ids.get(ids.size() - 1).equals(nodeId)) {
                candidates.add(seg);
                positions.put(seg, -1);
            }
        }

        if (candidates.size() != 2)
            return null;

        Segment s1 = candidates.get(0);
        Segment s2 = candidates.get(1);
        int pos1 = positions.get(s1);
        int pos2 = positions.get(s2);

        // Road check: both must be same road or at least one Unfiled
        Road road1 = findRoadForSegment(s1.getId());
        Road road2 = findRoadForSegment(s2.getId());
        if (road1 != null && road2 != null && !road1.getId().equals(road2.getId()))
            return null;

        // Build merged node list
        List<UUID> merged = new ArrayList<>();
        List<UUID> ids1 = s1.getNodeIds();
        List<UUID> ids2 = s2.getNodeIds();

        if (pos1 == -1) {
            // s1 ends with node
            merged.addAll(ids1);
            if (pos2 == 0) {
                // s2 starts with node — skip first
                for (int i = 1; i < ids2.size(); i++)
                    merged.add(ids2.get(i));
            } else {
                // s2 also ends with node — reverse s2 and skip
                for (int i = ids2.size() - 2; i >= 0; i--)
                    merged.add(ids2.get(i));
            }
        } else {
            // s1 starts with node
            if (pos2 == -1) {
                // s2 ends with node — s2 + s1 (skip duplicate)
                merged.addAll(ids2);
                for (int i = 1; i < ids1.size(); i++)
                    merged.add(ids1.get(i));
            } else {
                // s2 also starts with node — reverse s1 + s2
                for (int i = ids1.size() - 2; i >= 0; i--)
                    merged.add(ids1.get(i));
                merged.addAll(ids2);
            }
        }

        Segment mergedSeg = new Segment(UUID.randomUUID(), merged, null, Source.USER, Status.CONFIRMED, 1);
        segments.put(mergedSeg.getId(), mergedSeg);

        // Road inheritance
        Road targetRoad = road1 != null ? road1 : road2;
        if (targetRoad != null && targetRoad.getSegmentIds() != null) {
            targetRoad.getSegmentIds().add(mergedSeg.getId());
            targetRoad.getSegmentIds().remove(s1.getId());
            targetRoad.getSegmentIds().remove(s2.getId());
        }

        segments.remove(s1.getId());
        segments.remove(s2.getId());
        markDirty();
        maybeCleanupOrphans();
        return mergedSeg;
    }

    /**
     * Splits a segment at the given node index. The node at nodeIndex belongs to both resulting segments. Returns the
     * two new Segments, or null on failure.
     */
    public synchronized List<Segment> splitSegment(UUID segId, int nodeIndex) {
        Segment seg = segments.get(segId);
        if (seg == null || seg.getNodeIds() == null)
            return null;
        List<UUID> ids = seg.getNodeIds();
        if (nodeIndex <= 0 || nodeIndex >= ids.size() - 1)
            return null;

        List<UUID> leftIds = new ArrayList<>(ids.subList(0, nodeIndex + 1));
        List<UUID> rightIds = new ArrayList<>(ids.subList(nodeIndex, ids.size()));

        Segment left = new Segment(UUID.randomUUID(), leftIds, seg.getRoadId(), Source.USER, Status.CONFIRMED, 1);
        Segment right = new Segment(UUID.randomUUID(), rightIds, seg.getRoadId(), Source.USER, Status.CONFIRMED, 1);

        segments.put(left.getId(), left);
        segments.put(right.getId(), right);

        // Update Road association
        if (seg.getRoadId() != null) {
            Road road = roads.get(seg.getRoadId());
            if (road != null && road.getSegmentIds() != null) {
                road.getSegmentIds().remove(segId);
                road.getSegmentIds().add(left.getId());
                road.getSegmentIds().add(right.getId());
            }
        }

        segments.remove(segId);
        markDirty();
        maybeCleanupOrphans();
        return Arrays.asList(left, right);
    }

    /**
     * Inserts a new node at a position along a segment, splitting the segment into two.
     *
     * @param segId the segment to split
     * @param insertIndex position in nodeIds where the new node is inserted (must be 1..size-1)
     * @param x X coordinate of the new node
     * @param z Z coordinate of the new node
     * @return the newly created Node, or null if the segment or index is invalid
     */
    public synchronized Node insertNodeIntoSegment(UUID segId, int insertIndex, double x, double z) {
        Segment seg = segments.get(segId);
        if (seg == null || seg.getNodeIds() == null)
            return null;
        List<UUID> ids = seg.getNodeIds();
        if (insertIndex < 1 || insertIndex >= ids.size())
            return null;

        long now = System.currentTimeMillis();
        double y = interpolateY(ids, insertIndex, x, z);
        Node newNode = new Node(UUID.randomUUID(), x, y, z, CornerType.AUTO, Source.USER, 1, now);
        nodes.put(newNode.getId(), newNode);

        List<UUID> leftIds = new ArrayList<>(ids.subList(0, insertIndex));
        leftIds.add(newNode.getId());
        List<UUID> rightIds = new ArrayList<>();
        rightIds.add(newNode.getId());
        rightIds.addAll(ids.subList(insertIndex, ids.size()));

        Segment left = new Segment(UUID.randomUUID(), leftIds, seg.getRoadId(), Source.USER, Status.CONFIRMED, 1);
        Segment right = new Segment(UUID.randomUUID(), rightIds, seg.getRoadId(), Source.USER, Status.CONFIRMED, 1);

        segments.put(left.getId(), left);
        segments.put(right.getId(), right);

        if (seg.getRoadId() != null) {
            Road road = roads.get(seg.getRoadId());
            if (road != null && road.getSegmentIds() != null) {
                road.getSegmentIds().remove(segId);
                road.getSegmentIds().add(left.getId());
                road.getSegmentIds().add(right.getId());
            }
        }

        segments.remove(segId);
        markDirty();
        maybeCleanupOrphans();
        return newNode;
    }

    /**
     * Inserts a single shared node at the intersection of two segments, splitting each into two. The two original
     * segments are replaced with four new ones (left + right of each), all referencing the same new node as their
     * junction point. Any road associations are preserved.
     */
    public synchronized Node insertNodeAtIntersection(UUID segIdA, int insertIndexA, UUID segIdB, int insertIndexB,
        double x, double z) {
        if (segIdA.equals(segIdB)) {
            return null;
        }
        long now = System.currentTimeMillis();
        double y = interpolateYForIntersection(segIdA, insertIndexA, segIdB, insertIndexB, x, z);
        Node newNode = new Node(UUID.randomUUID(), x, y, z, CornerType.AUTO, Source.USER, 1, now);
        nodes.put(newNode.getId(), newNode);

        splitSegmentWithNode(segIdA, insertIndexA, newNode.getId());
        splitSegmentWithNode(segIdB, insertIndexB, newNode.getId());

        markDirty();
        maybeCleanupOrphans();
        return newNode;
    }

    /** Splits a segment at {@code insertIndex}, inserting {@code newNodeId} at that position. */
    private void splitSegmentWithNode(UUID segId, int insertIndex, UUID newNodeId) {
        Segment seg = segments.get(segId);
        if (seg == null || seg.getNodeIds() == null)
            return;
        List<UUID> ids = seg.getNodeIds();
        if (insertIndex < 1 || insertIndex >= ids.size())
            return;

        List<UUID> leftIds = new ArrayList<>(ids.subList(0, insertIndex));
        leftIds.add(newNodeId);
        List<UUID> rightIds = new ArrayList<>();
        rightIds.add(newNodeId);
        rightIds.addAll(ids.subList(insertIndex, ids.size()));

        Segment left = new Segment(UUID.randomUUID(), leftIds, seg.getRoadId(), Source.USER, Status.CONFIRMED, 1);
        Segment right = new Segment(UUID.randomUUID(), rightIds, seg.getRoadId(), Source.USER, Status.CONFIRMED, 1);

        segments.put(left.getId(), left);
        segments.put(right.getId(), right);

        if (seg.getRoadId() != null) {
            Road road = roads.get(seg.getRoadId());
            if (road != null && road.getSegmentIds() != null) {
                road.getSegmentIds().remove(segId);
                road.getSegmentIds().add(left.getId());
                road.getSegmentIds().add(right.getId());
            }
        }

        segments.remove(segId);
    }

    /**
     * Returns all entities modified since the given timestamp. The returned JsonObject contains "nodes", "segments",
     * and "roads" arrays, plus a "serverTime" field for the client to use in the next delta request.
     *
     * <p>
     * Conflict resolution: non-conflicting edits (on different entities) are merged automatically by design — each
     * entity has its own version counter, so modifying Node A and Node B concurrently never conflicts. Only
     * modifications to the same entity produce a version conflict (409).
     * </p>
     */
    public JsonObject getDeltaSince(long since) {
        JsonObject delta = new JsonObject();

        JsonArray deltaNodes = new JsonArray();
        for (Node node : nodes.values()) {
            if (node.getModifiedAt() > since) {
                deltaNodes.add(GSON.toJsonTree(node));
            }
        }
        delta.add("nodes", deltaNodes);

        JsonArray deltaSegs = new JsonArray();
        for (Segment seg : segments.values()) {
            if (seg.getModifiedAt() > since) {
                deltaSegs.add(GSON.toJsonTree(seg));
            }
        }
        delta.add("segments", deltaSegs);

        JsonArray deltaRoads = new JsonArray();
        for (Road road : roads.values()) {
            if (road.getModifiedAt() > since) {
                deltaRoads.add(GSON.toJsonTree(road));
            }
        }
        delta.add("roads", deltaRoads);

        delta.addProperty("serverTime", System.currentTimeMillis());
        return delta;
    }

    // ---------- Persistence ----------

    private void markDirty() {
        this.dirty = true;
    }

    /**
     * Serializes the full network to disk synchronously. Does NOT run graphify — callers must explicitly invoke
     * {@link #maybeGraphify()} on the main thread before calling this method if needed.
     *
     * @return true if the save succeeded, false otherwise
     */
    public synchronized boolean saveToDisk() {
        try {
            Files.createDirectories(savePath.getParent());

            JsonObject root = new JsonObject();
            root.add("nodes", GSON.toJsonTree(new ArrayList<>(nodes.values())));
            root.add("segments", GSON.toJsonTree(new ArrayList<>(segments.values())));
            root.add("roads", GSON.toJsonTree(new ArrayList<>(roads.values())));

            Files.writeString(savePath, GSON.toJson(root), StandardCharsets.UTF_8);
            dirty = false;
            LOGGER.log(Level.INFO, "Saved {0} nodes, {1} segments, {2} roads to {3}",
                new Object[] {nodes.size(), segments.size(), roads.size(), savePath});
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to save road network: {0}", e.getMessage());
            return false;
        }
    }

    /**
     * Restores entities from a JSON snapshot, but only reverts entities that were modified exactly once since the
     * snapshot was taken (version == snapshotVersion + 1). Entities modified multiple times (concurrent in-game edits)
     * are preserved. Entities not in the snapshot are kept (they were created after the snapshot).
     *
     * @return a JsonObject with "ok", "revertedNodes/Segments/Roads" counts, and "skipped*" counts for conflicts
     */
    public synchronized JsonObject restoreFromJson(JsonObject root) {
        java.util.List<String> revertedNodes = new ArrayList<>();
        java.util.List<String> revertedSegments = new ArrayList<>();
        java.util.List<String> revertedRoads = new ArrayList<>();
        java.util.List<String> skippedNodes = new ArrayList<>();
        java.util.List<String> skippedSegments = new ArrayList<>();
        java.util.List<String> skippedRoads = new ArrayList<>();

        // Nodes
        if (root.has("nodes")) {
            Type nodeListType = new TypeToken<List<Node>>() {}.getType();
            List<Node> nodeList = GSON.fromJson(root.get("nodes"), nodeListType);
            if (nodeList != null) {
                for (Node snapNode : nodeList) {
                    Node serverNode = nodes.get(snapNode.getId());
                    if (serverNode == null) {
                        skippedNodes.add(snapNode.getId().toString());
                        continue;
                    }
                    if (serverNode.getVersion() == snapNode.getVersion() + 1) {
                        // Modified exactly once since snapshot — safe to revert
                        snapNode.setVersion(snapNode.getVersion() + 1);
                        snapNode.setModifiedAt(System.currentTimeMillis());
                        nodes.put(snapNode.getId(), snapNode);
                        revertedNodes.add(snapNode.getId().toString());
                    } else {
                        skippedNodes.add(snapNode.getId().toString());
                    }
                }
            }
        }

        // Segments
        if (root.has("segments")) {
            Type segListType = new TypeToken<List<Segment>>() {}.getType();
            List<Segment> segList = GSON.fromJson(root.get("segments"), segListType);
            if (segList != null) {
                for (Segment snapSeg : segList) {
                    Segment serverSeg = segments.get(snapSeg.getId());
                    if (serverSeg == null) {
                        skippedSegments.add(snapSeg.getId().toString());
                        continue;
                    }
                    if (serverSeg.getVersion() == snapSeg.getVersion() + 1) {
                        snapSeg.setVersion(snapSeg.getVersion() + 1);
                        snapSeg.setModifiedAt(System.currentTimeMillis());
                        segments.put(snapSeg.getId(), snapSeg);
                        revertedSegments.add(snapSeg.getId().toString());
                    } else {
                        skippedSegments.add(snapSeg.getId().toString());
                    }
                }
            }
        }

        // Roads
        if (root.has("roads")) {
            Type roadListType = new TypeToken<List<Road>>() {}.getType();
            List<Road> roadList = GSON.fromJson(root.get("roads"), roadListType);
            if (roadList != null) {
                for (Road snapRoad : roadList) {
                    Road serverRoad = roads.get(snapRoad.getId());
                    if (serverRoad == null) {
                        skippedRoads.add(snapRoad.getId().toString());
                        continue;
                    }
                    if (serverRoad.getVersion() == snapRoad.getVersion() + 1) {
                        snapRoad.setVersion(snapRoad.getVersion() + 1);
                        snapRoad.setModifiedAt(System.currentTimeMillis());
                        roads.put(snapRoad.getId(), snapRoad);
                        revertedRoads.add(snapRoad.getId().toString());
                    } else {
                        skippedRoads.add(snapRoad.getId().toString());
                    }
                }
            }
        }

        markDirty();
        saveToDisk();

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("revertedNodes", revertedNodes.size());
        result.addProperty("skippedNodes", skippedNodes.size());
        result.addProperty("revertedSegments", revertedSegments.size());
        result.addProperty("skippedSegments", skippedSegments.size());
        result.addProperty("revertedRoads", revertedRoads.size());
        result.addProperty("skippedRoads", skippedRoads.size());
        int totalSkipped = skippedNodes.size() + skippedSegments.size() + skippedRoads.size();
        if (totalSkipped > 0) {
            result.addProperty("warning",
                totalSkipped + " entity(ies) were modified in-game and could not be reverted");
        }
        return result;
    }

    /**
     * Loads the network from disk, replacing in-memory data.
     */
    public synchronized void loadFromDisk() {
        if (!Files.exists(savePath)) {
            LOGGER.log(Level.INFO, "No existing road network file at {0}, starting fresh", savePath);
            return;
        }

        try {
            String json = Files.readString(savePath, StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);

            nodes.clear();
            segments.clear();
            roads.clear();

            if (root.has("nodes")) {
                Type nodeListType = new TypeToken<List<Node>>() {}.getType();
                List<Node> nodeList = GSON.fromJson(root.get("nodes"), nodeListType);
                for (Node n : nodeList) {
                    nodes.put(n.getId(), n);
                }
            }
            if (root.has("segments")) {
                Type segListType = new TypeToken<List<Segment>>() {}.getType();
                List<Segment> segList = GSON.fromJson(root.get("segments"), segListType);
                for (Segment s : segList) {
                    segments.put(s.getId(), s);
                }
            }
            if (root.has("roads")) {
                Type roadListType = new TypeToken<List<Road>>() {}.getType();
                List<Road> roadList = GSON.fromJson(root.get("roads"), roadListType);
                for (Road r : roadList) {
                    roads.put(r.getId(), r);
                }
            }

            dirty = false;
            LOGGER.log(Level.INFO, "Loaded {0} nodes, {1} segments, {2} roads from {3}",
                new Object[] {nodes.size(), segments.size(), roads.size(), savePath});
            // Auto-graphify on load so that any legacy data gets cleaned up
            maybeGraphify();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to load road network: {0}", e.getMessage());
        }
    }

    /**
     * Saves to disk asynchronously on the common ForkJoinPool.
     */
    public void asyncSave() {
        CompletableFuture.runAsync(this::saveToDisk);
    }

    // ---------- Y coordinate interpolation ----------

    /**
     * Interpolates a Y coordinate for a node being inserted into a segment. Uses linear interpolation between the two
     * nearest neighbor nodes (before and after the insertion point).
     */
    private double interpolateY(List<UUID> ids, int insertIndex, double x, double z) {
        int beforeIdx = insertIndex - 1;
        int afterIdx = insertIndex;

        if (beforeIdx >= 0 && afterIdx < ids.size()) {
            Node before = nodes.get(ids.get(beforeIdx));
            Node after = nodes.get(ids.get(afterIdx));
            if (before != null && after != null) {
                // Linear interpolation between before and after
                double t = (double)beforeIdx / (beforeIdx + 1);
                // Use the actual position along the segment for interpolation
                // Find the actual position ratio based on distances
                double segLen =
                    Math.sqrt(Math.pow(after.getX() - before.getX(), 2) + Math.pow(after.getZ() - before.getZ(), 2));
                if (segLen > 0.001) {
                    double distFromBefore = Math.sqrt(Math.pow(x - before.getX(), 2) + Math.pow(z - before.getZ(), 2));
                    t = Math.min(1.0, Math.max(0.0, distFromBefore / segLen));
                }
                return before.getY() + t * (after.getY() - before.getY());
            }
        }

        // Fallback: use single neighbor's Y
        if (beforeIdx >= 0) {
            Node before = nodes.get(ids.get(beforeIdx));
            if (before != null)
                return before.getY();
        }
        if (afterIdx < ids.size()) {
            Node after = nodes.get(ids.get(afterIdx));
            if (after != null)
                return after.getY();
        }

        return 64.0; // Default Minecraft sea level
    }

    /**
     * Interpolates Y coordinate for an intersection insert by averaging Y from both segments.
     */
    private double interpolateYForIntersection(UUID segIdA, int idxA, UUID segIdB, int idxB, double x, double z) {
        double yA = interpolateYForSegment(segIdA, idxA, x, z);
        double yB = interpolateYForSegment(segIdB, idxB, x, z);
        if (Double.isNaN(yA) && Double.isNaN(yB))
            return 64.0;
        if (Double.isNaN(yA))
            return yB;
        if (Double.isNaN(yB))
            return yA;
        return (yA + yB) / 2.0;
    }

    private double interpolateYForSegment(UUID segId, int insertIndex, double x, double z) {
        Segment seg = segments.get(segId);
        if (seg == null || seg.getNodeIds() == null)
            return Double.NaN;
        List<UUID> ids = seg.getNodeIds();
        int beforeIdx = insertIndex - 1;
        int afterIdx = insertIndex;

        if (beforeIdx >= 0 && afterIdx < ids.size()) {
            Node before = nodes.get(ids.get(beforeIdx));
            Node after = nodes.get(ids.get(afterIdx));
            if (before != null && after != null) {
                double segLen =
                    Math.sqrt(Math.pow(after.getX() - before.getX(), 2) + Math.pow(after.getZ() - before.getZ(), 2));
                if (segLen > 0.001) {
                    double distFromBefore = Math.sqrt(Math.pow(x - before.getX(), 2) + Math.pow(z - before.getZ(), 2));
                    double t = Math.min(1.0, Math.max(0.0, distFromBefore / segLen));
                    return before.getY() + t * (after.getY() - before.getY());
                }
                return (before.getY() + after.getY()) / 2.0;
            }
            if (before != null)
                return before.getY();
            if (after != null)
                return after.getY();
        }

        if (beforeIdx >= 0) {
            Node before = nodes.get(ids.get(beforeIdx));
            if (before != null)
                return before.getY();
        }
        if (afterIdx < ids.size()) {
            Node after = nodes.get(ids.get(afterIdx));
            if (after != null)
                return after.getY();
        }
        return Double.NaN;
    }

    // ---------- GeoJSON export ----------

    /**
     * Exports the complete road network as a GeoJSON FeatureCollection.
     *
     * <p>
     * Each segment becomes a LineString Feature. Nodes are included as Point Features.
     * </p>
     */
    public JsonObject toGeoJSON() {
        JsonObject fc = new JsonObject();
        fc.addProperty("type", "FeatureCollection");
        JsonArray features = new JsonArray();

        // Export nodes as Point features
        for (Node node : nodes.values()) {
            JsonObject feature = new JsonObject();
            feature.addProperty("type", "Feature");

            JsonObject props = new JsonObject();
            props.addProperty("id", node.getId().toString());
            props.addProperty("cornerType", node.getCornerType().name());
            props.addProperty("source", node.getSource().name());
            props.addProperty("version", node.getVersion());
            feature.add("properties", props);

            JsonObject geom = new JsonObject();
            geom.addProperty("type", "Point");
            JsonArray coords = new JsonArray();
            coords.add(node.getX());
            coords.add(node.getY());
            coords.add(node.getZ());
            geom.add("coordinates", coords);
            feature.add("geometry", geom);

            features.add(feature);
        }

        // Export segments as LineString features, resolving node coordinates
        for (Segment segment : segments.values()) {
            JsonObject feature = new JsonObject();
            feature.addProperty("type", "Feature");

            JsonObject props = new JsonObject();
            props.addProperty("id", segment.getId().toString());
            props.addProperty("source", segment.getSource() != null ? segment.getSource().name() : null);
            props.addProperty("status", segment.getStatus() != null ? segment.getStatus().name() : null);
            props.addProperty("version", segment.getVersion());
            if (segment.getRoadId() != null) {
                props.addProperty("roadId", segment.getRoadId().toString());
            }
            feature.add("properties", props);

            JsonObject geom = new JsonObject();
            geom.addProperty("type", "LineString");
            JsonArray coords = new JsonArray();
            for (UUID nodeId : segment.getNodeIds()) {
                Node node = nodes.get(nodeId);
                if (node != null) {
                    JsonArray point = new JsonArray();
                    point.add(node.getX());
                    point.add(node.getY());
                    point.add(node.getZ());
                    coords.add(point);
                }
            }
            geom.add("coordinates", coords);
            feature.add("geometry", geom);

            features.add(feature);
        }

        // Include roads metadata
        JsonObject roadsObj = new JsonObject();
        for (Map.Entry<UUID, Road> entry : roads.entrySet()) {
            Road road = entry.getValue();
            JsonObject roadJson = new JsonObject();
            roadJson.addProperty("id", road.getId().toString());
            if (road.getName() != null) {
                roadJson.addProperty("name", road.getName());
            }
            roadJson.addProperty("color", road.getColor());
            roadJson.addProperty("version", road.getVersion());
            JsonArray segIds = new JsonArray();
            for (UUID segId : road.getSegmentIds()) {
                segIds.add(segId.toString());
            }
            roadJson.add("segmentIds", segIds);
            roadsObj.add(entry.getKey().toString(), roadJson);
        }
        if (roadsObj.size() > 0) {
            fc.add("roads", roadsObj);
        }

        fc.add("features", features);
        return fc;
    }
}
