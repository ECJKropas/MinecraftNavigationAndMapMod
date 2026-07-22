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
            updated.setId(id);
            updated.setModifiedAt(System.currentTimeMillis());
            nodes.put(id, updated);
            markDirty();
        }
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
            updated.setId(id);
            segments.put(id, updated);
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
            updated.setId(id);
            roads.put(id, updated);
            markDirty();
        }
    }

    public synchronized void removeRoad(UUID id) {
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
     * Returns an unmodifiable collection of all Roads (snapshot).
     */
    public java.util.Collection<Road> getRoads() {
        return new java.util.ArrayList<>(roads.values());
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
