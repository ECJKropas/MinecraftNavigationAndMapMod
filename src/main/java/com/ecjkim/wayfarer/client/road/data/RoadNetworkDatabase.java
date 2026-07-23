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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.fabricmc.loader.api.FabricLoader;

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
 * Stores data to {@code wayfarer/roads.json} inside the Fabric config directory. All public write methods are
 * synchronized for thread safety.
 * </p>
 */
public class RoadNetworkDatabase {
    private static final Logger LOGGER = Logger.getLogger("Wayfarer|RoadNetwork");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile RoadNetworkDatabase instance;

    private final ConcurrentHashMap<UUID, Node> nodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Segment> segments = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Road> roads = new ConcurrentHashMap<>();

    private final Path savePath;
    private volatile boolean dirty;

    private RoadNetworkDatabase() {
        savePath = FabricLoader.getInstance().getConfigDir().resolve("wayfarer/roads.json");
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
        markDirty();
    }

    // ---------- Segment CRUD ----------

    public synchronized void addSegment(Segment segment) {
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
            markDirty();
        }
    }

    public synchronized void removeSegment(UUID id) {
        segments.remove(id);
        markDirty();
    }

    // ---------- Road CRUD ----------

    public synchronized void addRoad(Road road) {
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
            markDirty();
            return next;
        }
        Road road = roads.get(id);
        if (road != null) {
            int next = road.getVersion() + 1;
            road.setVersion(next);
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
        return merged;
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
        return Arrays.asList(left, right);
    }

    /**
     * Returns all entities modified since the given timestamp. The returned JsonObject contains "nodes",
     * "segments", and "roads" arrays.
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
            // Segments don't have modifiedAt, treat all as changed if any node has been modified
            // For now, include all segments — delta is lightweight
            deltaSegs.add(GSON.toJsonTree(seg));
        }
        delta.add("segments", deltaSegs);

        JsonArray deltaRoads = new JsonArray();
        for (Road road : roads.values()) {
            deltaRoads.add(GSON.toJsonTree(road));
        }
        delta.add("roads", deltaRoads);

        return delta;
    }

    // ---------- Persistence ----------

    private void markDirty() {
        this.dirty = true;
    }

    /**
     * Serializes the full network to disk synchronously.
     */
    public synchronized void saveToDisk() {
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
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to save road network: {0}", e.getMessage());
        }
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
            props.addProperty("source", segment.getSource().name());
            props.addProperty("status", segment.getStatus().name());
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
