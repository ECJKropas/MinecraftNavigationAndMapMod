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
package com.ecjkim.wayfarer.client.road.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ecjkim.wayfarer.client.config.WayfarerConfigs;
import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Node;
import com.ecjkim.wayfarer.client.road.model.Road;
import com.ecjkim.wayfarer.client.road.model.Segment;
import com.ecjkim.wayfarer.client.road.model.Source;
import com.ecjkim.wayfarer.client.road.model.Status;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Embedded HTTP server that serves the web frontend and REST API for the road network.
 *
 * <p>
 * Port 7891 by default; falls back to 7892 if occupied.
 * </p>
 */
public class WayfarerHttpServer implements Runnable {
    private static final Logger LOGGER = Logger.getLogger("Wayfarer|HttpServer");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int DEFAULT_PORT = 7891;
    private static final int FALLBACK_PORT = 7892;

    private final RoadNetworkDatabase database;
    private final List<Route> routes = new ArrayList<>();
    private volatile boolean running;
    private int actualPort;

    public WayfarerHttpServer() {
        this.database = RoadNetworkDatabase.getInstance();
        registerRoutes();
    }

    public int getActualPort() {
        return actualPort;
    }

    // -------- Route registration --------

    private void registerRoutes() {
        routes.add(new Route("GET", "/", this::serveIndexHtml));
        routes.add(new Route("GET", "/api/config", this::handleGetConfig));
        routes.add(new Route("GET", Pattern.compile("/static/(.+)"), this::serveStaticFile));
        routes.add(new Route("GET", "/api/roads", this::handleGetRoads));
        routes.add(new Route("GET", Pattern.compile("/api/roads/delta(?:\\?.*)?"), this::handleGetDelta));
        routes.add(new Route("PUT", Pattern.compile("/api/nodes/([0-9a-f-]+)"), this::handleUpdateNode));
        routes.add(new Route("DELETE", Pattern.compile("/api/nodes/([0-9a-f-]+)"), this::handleDeleteNode));
        routes.add(new Route("POST", "/api/nodes/merge", this::handleMergeNodes));
        routes.add(new Route("POST", "/api/nodes/merge-clean", this::handleMergeCleanNodes));
        routes.add(new Route("POST", "/api/nodes/soft-delete", this::handleSoftDeleteNode));
        routes.add(new Route("POST", "/api/nodes/merge-segments", this::handleMergeSegmentsAtNode));
        routes.add(new Route("POST", "/api/segments", this::handleCreateSegment));
        routes.add(new Route("DELETE", Pattern.compile("/api/segments/([0-9a-f-]+)"), this::handleDeleteSegment));
        routes.add(new Route("POST", "/api/merge", this::handleMerge));
        routes.add(new Route("POST", Pattern.compile("/api/split/([0-9a-f-]+)"), this::handleSplit));
        routes.add(new Route("POST", "/api/segments/intersection", this::handleSegmentIntersection));
        routes.add(new Route("POST", Pattern.compile("/api/segments/([0-9a-f-]+)/insert"), this::handleInsertNode));
        routes.add(new Route("PATCH", Pattern.compile("/api/roads/([0-9a-f-]+)"), this::handleUpdateRoad));
        routes.add(new Route("DELETE", Pattern.compile("/api/roads/([0-9a-f-]+)"), this::handleDeleteRoad));
        routes.add(new Route("POST", "/api/roads/restore", this::handleRestoreRoads));
    }

    // -------- Server lifecycle --------

    @Override
    public void run() {
        int port = DEFAULT_PORT;
        HttpServer server = null;

        while (server == null && port <= FALLBACK_PORT) {
            try {
                server = HttpServer.create(new InetSocketAddress(port), 0);
                actualPort = port;
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Port {0} occupied, trying next port", port);
                port++;
            }
        }

        if (server == null) {
            LOGGER.log(Level.SEVERE, "Failed to bind HTTP server on ports {0}-{1}",
                new Object[] {DEFAULT_PORT, FALLBACK_PORT});
            return;
        }

        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "Wayfarer-HTTP");
            t.setDaemon(true);
            return t;
        }));

        server.createContext("/", exchange -> {
            try {
                dispatch(exchange);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Unhandled error: {0}", e.getMessage());
                sendResponse(exchange, 500, "text/plain", "Internal Server Error");
            }
        });

        server.start();
        running = true;
        LOGGER.log(Level.INFO, "HTTP server started on port {0}", actualPort);
    }

    public void stop() {
        running = false;
    }

    // -------- Request dispatch --------

    private void dispatch(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        String path = exchange.getRequestURI().getPath();

        // Normalize path
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        for (Route route : routes) {
            if (!route.method.equals(method))
                continue;

            if (route.pattern != null) {
                Matcher m = route.pattern.matcher(path);
                if (m.matches()) {
                    Map<String, String> pathParams = new HashMap<>();
                    for (int i = 1; i <= m.groupCount(); i++) {
                        pathParams.put("id", m.group(1)); // simplified: all patterns capture one group
                    }
                    route.handler.accept(createRequest(exchange, pathParams));
                    return;
                }
            } else if (route.literal != null && route.literal.equals(path)) {
                route.handler.accept(createRequest(exchange, Map.of()));
                return;
            }
        }

        // 404
        sendResponse(exchange, 404, "text/plain", "Not Found");
    }

    private Request createRequest(HttpExchange exchange, Map<String, String> pathParams) {
        String body = null;
        try (InputStream is = exchange.getRequestBody()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            if (body.isEmpty())
                body = null;
        } catch (IOException ignored) {
        }

        Map<String, String> query = new HashMap<>();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    query.put(kv[0], kv[1]);
                }
            }
        }

        return new Request(exchange, body, query, pathParams);
    }

    // -------- Static file handlers --------

    private void serveIndexHtml(Request req) {
        serveResource(req, "web/index.html", "text/html; charset=utf-8");
    }

    private void serveStaticFile(Request req) {
        String filePath = req.pathParams.getOrDefault("id", "");
        String resourcePath = "web/static/" + filePath;

        String contentType;
        if (filePath.endsWith(".js"))
            contentType = "application/javascript; charset=utf-8";
        else if (filePath.endsWith(".css"))
            contentType = "text/css; charset=utf-8";
        else if (filePath.endsWith(".png"))
            contentType = "image/png";
        else if (filePath.endsWith(".svg"))
            contentType = "image/svg+xml";
        else
            contentType = "application/octet-stream";

        serveResource(req, resourcePath, contentType);
    }

    private void serveResource(Request req, String resourcePath, String contentType) {
        HttpExchange exchange = req.exchange;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                sendResponse(exchange, 404, "text/plain", "Resource not found: " + resourcePath);
                return;
            }
            byte[] data = is.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to serve resource {0}: {1}", new Object[] {resourcePath, e.getMessage()});
        }
    }

    // -------- API handlers --------

    private void handleGetConfig(Request req) {
        JsonObject config = new JsonObject();
        config.addProperty("maxZoom", WayfarerConfigs.Generic.WEB_MAX_ZOOM.getIntegerValue());
        sendJson(req.exchange, 200, config);
    }

    private void handleGetRoads(Request req) {
        JsonObject geojson = database.toGeoJSON();
        // Add full nodes/segments/roads data for the editor
        JsonArray nodesArr = new JsonArray();
        for (Node n : database.getAllNodes()) {
            nodesArr.add(GSON.toJsonTree(n));
        }
        geojson.add("nodes", nodesArr);

        JsonArray segsArr = new JsonArray();
        for (Segment s : database.getAllSegments()) {
            segsArr.add(GSON.toJsonTree(s));
        }
        geojson.add("segments", segsArr);

        JsonObject roadsObj = new JsonObject();
        for (Road r : database.getRoads()) {
            roadsObj.add(r.getId().toString(), GSON.toJsonTree(r));
        }
        geojson.add("roads", roadsObj);

        sendJson(req.exchange, 200, geojson);
    }

    private void handleGetDelta(Request req) {
        String sinceStr = req.query.getOrDefault("since", "0");
        long since;
        try {
            since = Long.parseLong(sinceStr);
        } catch (NumberFormatException e) {
            since = 0;
        }
        JsonObject delta = database.getDeltaSince(since);
        sendJson(req.exchange, 200, delta);
    }

    private void handleUpdateNode(Request req) {
        String idStr = req.pathParams.getOrDefault("id", "");
        UUID id;
        try {
            id = UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            sendJson(req.exchange, 400, errorJson("Invalid node ID"));
            return;
        }

        if (req.body == null) {
            sendJson(req.exchange, 400, errorJson("Missing request body"));
            return;
        }

        try {
            JsonObject body = JsonParser.parseString(req.body).getAsJsonObject();
            int expectedVersion = body.has("expectedVersion") ? body.get("expectedVersion").getAsInt() : -1;

            // Atomic version check + mutation
            synchronized (database) {
                Node existing = database.getNode(id);
                if (existing == null) {
                    sendJson(req.exchange, 404, errorJson("Node not found"));
                    return;
                }

                if (expectedVersion >= 0 && existing.getVersion() != expectedVersion) {
                    JsonObject err = new JsonObject();
                    err.addProperty("error", "Version conflict. Entity was modified in-game.");
                    err.addProperty("currentVersion", existing.getVersion());
                    sendJson(req.exchange, 409, err);
                    return;
                }

                boolean positionChanged = false;
                if (body.has("x")) {
                    existing.setX(body.get("x").getAsDouble());
                    positionChanged = true;
                }
                if (body.has("z")) {
                    existing.setZ(body.get("z").getAsDouble());
                    positionChanged = true;
                }

                if (positionChanged) {
                    existing.setModifiedAt(System.currentTimeMillis());
                    int nextVer = existing.getVersion() + 1;
                    existing.setVersion(nextVer);
                }

                database.saveToDisk();
                sendJson(req.exchange, 200, GSON.toJsonTree(existing));
            }
        } catch (Exception e) {
            sendJson(req.exchange, 400, errorJson("Invalid JSON: " + e.getMessage()));
        }
    }

    private void handleDeleteNode(Request req) {
        String idStr = req.pathParams.getOrDefault("id", "");
        UUID id;
        try {
            id = UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            sendJson(req.exchange, 400, errorJson("Invalid node ID"));
            return;
        }

        if (database.getNode(id) == null) {
            sendJson(req.exchange, 404, errorJson("Node not found"));
            return;
        }

        // Cascade: remove all segments containing this node
        for (Segment s : database.getAllSegments()) {
            if (s.getNodeIds() != null && s.getNodeIds().contains(id)) {
                database.removeSegment(s.getId());
            }
        }

        database.removeNode(id);
        database.saveToDisk();
        sendJson(req.exchange, 200, okJson("Node deleted"));
    }

    private void handleMergeNodes(Request req) {
        if (req.body == null) {
            sendJson(req.exchange, 400, errorJson("Missing request body"));
            return;
        }

        try {
            JsonObject body = JsonParser.parseString(req.body).getAsJsonObject();
            UUID nodeToDeleteId = UUID.fromString(body.get("nodeToDeleteId").getAsString());
            UUID targetNodeId = UUID.fromString(body.get("targetNodeId").getAsString());

            if (database.getNode(nodeToDeleteId) == null) {
                sendJson(req.exchange, 404, errorJson("Node not found: " + nodeToDeleteId));
                return;
            }
            if (database.getNode(targetNodeId) == null) {
                sendJson(req.exchange, 404, errorJson("Node not found: " + targetNodeId));
                return;
            }

            database.mergeNodes(nodeToDeleteId, targetNodeId);
            database.saveToDisk();

            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("deletedNodeId", nodeToDeleteId.toString());
            result.addProperty("targetNodeId", targetNodeId.toString());
            sendJson(req.exchange, 200, result);
        } catch (Exception e) {
            sendJson(req.exchange, 400, errorJson("Invalid JSON: " + e.getMessage()));
        }
    }

    private void handleMergeCleanNodes(Request req) {
        if (req.body == null) {
            sendJson(req.exchange, 400, errorJson("Missing request body"));
            return;
        }

        try {
            JsonObject body = JsonParser.parseString(req.body).getAsJsonObject();
            UUID nodeToDeleteId = UUID.fromString(body.get("nodeToDeleteId").getAsString());
            UUID targetNodeId = UUID.fromString(body.get("targetNodeId").getAsString());
            UUID segmentId = UUID.fromString(body.get("segmentId").getAsString());
            JsonArray midArr = body.getAsJsonArray("intermediateNodeIds");

            if (database.getNode(nodeToDeleteId) == null) {
                sendJson(req.exchange, 404, errorJson("Node not found: " + nodeToDeleteId));
                return;
            }
            if (database.getNode(targetNodeId) == null) {
                sendJson(req.exchange, 404, errorJson("Node not found: " + targetNodeId));
                return;
            }
            if (database.getSegment(segmentId) == null) {
                sendJson(req.exchange, 404, errorJson("Segment not found: " + segmentId));
                return;
            }

            List<UUID> intermediateIds = new ArrayList<>();
            for (int i = 0; i < midArr.size(); i++) {
                intermediateIds.add(UUID.fromString(midArr.get(i).getAsString()));
            }

            database.mergeNodesWithCleanup(nodeToDeleteId, targetNodeId, segmentId, intermediateIds);
            database.saveToDisk();

            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("deletedNodeId", nodeToDeleteId.toString());
            result.addProperty("targetNodeId", targetNodeId.toString());
            sendJson(req.exchange, 200, result);
        } catch (Exception e) {
            sendJson(req.exchange, 400, errorJson("Invalid JSON: " + e.getMessage()));
        }
    }

    private void handleSoftDeleteNode(Request req) {
        if (req.body == null) {
            sendJson(req.exchange, 400, errorJson("Missing request body"));
            return;
        }

        try {
            JsonObject body = JsonParser.parseString(req.body).getAsJsonObject();
            UUID nodeId = UUID.fromString(body.get("nodeId").getAsString());

            if (database.getNode(nodeId) == null) {
                sendJson(req.exchange, 404, errorJson("Node not found: " + nodeId));
                return;
            }

            JsonObject result = database.softDeleteNode(nodeId);
            sendJson(req.exchange, result.get("ok").getAsBoolean() ? 200 : 400, result);
        } catch (Exception e) {
            sendJson(req.exchange, 400, errorJson("Invalid JSON: " + e.getMessage()));
        }
    }

    private void handleMergeSegmentsAtNode(Request req) {
        if (req.body == null) {
            sendJson(req.exchange, 400, errorJson("Missing request body"));
            return;
        }

        try {
            JsonObject body = JsonParser.parseString(req.body).getAsJsonObject();
            UUID nodeId = UUID.fromString(body.get("nodeId").getAsString());

            if (database.getNode(nodeId) == null) {
                sendJson(req.exchange, 404, errorJson("Node not found: " + nodeId));
                return;
            }

            Segment merged = database.mergeSegmentsAtNode(nodeId);
            if (merged == null) {
                sendJson(req.exchange, 400,
                    errorJson("Merge failed: node must be a degree-2 shared endpoint of two compatible segments"));
                return;
            }

            database.saveToDisk();
            sendJson(req.exchange, 200, GSON.toJsonTree(merged));
        } catch (Exception e) {
            sendJson(req.exchange, 400, errorJson("Invalid JSON: " + e.getMessage()));
        }
    }

    private void handleCreateSegment(Request req) {
        if (req.body == null) {
            sendJson(req.exchange, 400, errorJson("Missing request body"));
            return;
        }

        try {
            JsonObject body = JsonParser.parseString(req.body).getAsJsonObject();
            JsonArray nodeIdsArr = body.getAsJsonArray("nodeIds");
            if (nodeIdsArr == null || nodeIdsArr.size() < 2) {
                sendJson(req.exchange, 400, errorJson("nodeIds must contain at least 2 IDs"));
                return;
            }

            List<UUID> nodeIds = new ArrayList<>();
            for (int i = 0; i < nodeIdsArr.size(); i++) {
                nodeIds.add(UUID.fromString(nodeIdsArr.get(i).getAsString()));
            }

            // Verify all nodes exist
            for (UUID nid : nodeIds) {
                if (database.getNode(nid) == null) {
                    sendJson(req.exchange, 400, errorJson("Node not found: " + nid));
                    return;
                }
            }

            Segment segment = new Segment(UUID.randomUUID(), nodeIds, null, Source.USER, Status.CONFIRMED, 1);
            database.addSegment(segment);
            database.saveToDisk();
            sendJson(req.exchange, 201, GSON.toJsonTree(segment));
        } catch (Exception e) {
            sendJson(req.exchange, 400, errorJson("Invalid JSON: " + e.getMessage()));
        }
    }

    private void handleDeleteSegment(Request req) {
        String idStr = req.pathParams.getOrDefault("id", "");
        UUID id;
        try {
            id = UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            sendJson(req.exchange, 400, errorJson("Invalid segment ID"));
            return;
        }

        if (database.getSegment(id) == null) {
            sendJson(req.exchange, 404, errorJson("Segment not found"));
            return;
        }

        database.removeSegment(id);
        database.saveToDisk();
        sendJson(req.exchange, 200, okJson("Segment deleted"));
    }

    private void handleMerge(Request req) {
        if (req.body == null) {
            sendJson(req.exchange, 400, errorJson("Missing request body"));
            return;
        }

        try {
            JsonObject body = JsonParser.parseString(req.body).getAsJsonObject();
            JsonArray segIdsArr = body.getAsJsonArray("segmentIds");
            if (segIdsArr == null || segIdsArr.size() < 2) {
                sendJson(req.exchange, 400, errorJson("segmentIds must contain at least 2 IDs"));
                return;
            }

            // Atomic version check + mutation
            synchronized (database) {
                if (body.has("expectedVersions")) {
                    JsonObject expectedVersions = body.getAsJsonObject("expectedVersions");
                    for (int i = 0; i < segIdsArr.size(); i++) {
                        String segIdStr = segIdsArr.get(i).getAsString();
                        UUID segId = UUID.fromString(segIdStr);
                        if (expectedVersions.has(segIdStr)) {
                            int expected = expectedVersions.get(segIdStr).getAsInt();
                            Segment seg = database.getSegment(segId);
                            if (seg == null || seg.getVersion() != expected) {
                                int cur = seg != null ? seg.getVersion() : -1;
                                JsonObject err = new JsonObject();
                                err.addProperty("error",
                                    "Version conflict on " + segIdStr + ". Entity was modified in-game.");
                                err.addProperty("currentVersion", cur);
                                sendJson(req.exchange, 409, err);
                                return;
                            }
                        }
                    }
                }

                List<UUID> segmentIds = new ArrayList<>();
                for (int i = 0; i < segIdsArr.size(); i++) {
                    segmentIds.add(UUID.fromString(segIdsArr.get(i).getAsString()));
                }

                Segment merged = database.mergeSegments(segmentIds);
                if (merged == null) {
                    sendJson(req.exchange, 400, errorJson("Merge failed: segments may not be connected"));
                    return;
                }

                database.saveToDisk();
                sendJson(req.exchange, 200, GSON.toJsonTree(merged));
            }
        } catch (Exception e) {
            sendJson(req.exchange, 400, errorJson("Invalid JSON: " + e.getMessage()));
        }
    }

    private void handleSplit(Request req) {
        String segIdStr = req.pathParams.getOrDefault("id", "");
        UUID segId;
        try {
            segId = UUID.fromString(segIdStr);
        } catch (IllegalArgumentException e) {
            sendJson(req.exchange, 400, errorJson("Invalid segment ID"));
            return;
        }

        if (req.body == null) {
            sendJson(req.exchange, 400, errorJson("Missing request body"));
            return;
        }

        try {
            JsonObject body = JsonParser.parseString(req.body).getAsJsonObject();
            int nodeIndex = body.get("nodeIndex").getAsInt();

            // Atomic version check + mutation
            synchronized (database) {
                if (body.has("expectedVersion")) {
                    int expectedVersion = body.get("expectedVersion").getAsInt();
                    Segment seg = database.getSegment(segId);
                    int cur = seg != null ? seg.getVersion() : -1;
                    if (seg == null || cur != expectedVersion) {
                        JsonObject err = new JsonObject();
                        err.addProperty("error", "Version conflict. Entity was modified in-game.");
                        err.addProperty("currentVersion", cur);
                        sendJson(req.exchange, 409, err);
                        return;
                    }
                }

                List<Segment> result = database.splitSegment(segId, nodeIndex);
                if (result == null) {
                    sendJson(req.exchange, 400, errorJson("Split failed: invalid nodeIndex"));
                    return;
                }

                database.saveToDisk();
                JsonArray arr = new JsonArray();
                for (Segment s : result) {
                    arr.add(GSON.toJsonTree(s));
                }
                sendJson(req.exchange, 200, arr);
            }
        } catch (Exception e) {
            sendJson(req.exchange, 400, errorJson("Invalid JSON: " + e.getMessage()));
        }
    }

    private void handleInsertNode(Request req) {
        String segIdStr = req.pathParams.getOrDefault("id", "");
        UUID segId;
        try {
            segId = UUID.fromString(segIdStr);
        } catch (IllegalArgumentException e) {
            sendJson(req.exchange, 400, errorJson("Invalid segment ID"));
            return;
        }

        if (req.body == null) {
            sendJson(req.exchange, 400, errorJson("Missing request body"));
            return;
        }

        try {
            JsonObject body = JsonParser.parseString(req.body).getAsJsonObject();
            double x = body.get("x").getAsDouble();
            double z = body.get("z").getAsDouble();
            int insertIndex = body.get("insertIndex").getAsInt();

            // Atomic version check + mutation
            synchronized (database) {
                if (body.has("expectedVersion")) {
                    int expectedVersion = body.get("expectedVersion").getAsInt();
                    Segment seg = database.getSegment(segId);
                    int cur = seg != null ? seg.getVersion() : -1;
                    if (seg == null || cur != expectedVersion) {
                        JsonObject err = new JsonObject();
                        err.addProperty("error", "Version conflict. Entity was modified in-game.");
                        err.addProperty("currentVersion", cur);
                        sendJson(req.exchange, 409, err);
                        return;
                    }
                }

                Node newNode = database.insertNodeIntoSegment(segId, insertIndex, x, z);
                if (newNode == null) {
                    sendJson(req.exchange, 400, errorJson("Insert failed: invalid segment or insertIndex"));
                    return;
                }

                database.saveToDisk();

                JsonObject result = new JsonObject();
                result.add("node", GSON.toJsonTree(newNode));
                sendJson(req.exchange, 201, result);
            }
        } catch (Exception e) {
            sendJson(req.exchange, 400, errorJson("Invalid JSON: " + e.getMessage()));
        }
    }

    private void handleSegmentIntersection(Request req) {
        if (req.body == null) {
            sendJson(req.exchange, 400, errorJson("Missing request body"));
            return;
        }

        try {
            JsonObject body = JsonParser.parseString(req.body).getAsJsonObject();
            double x = body.get("x").getAsDouble();
            double z = body.get("z").getAsDouble();
            UUID segIdA = UUID.fromString(body.get("segmentIdA").getAsString());
            int indexA = body.get("insertIndexA").getAsInt();
            UUID segIdB = UUID.fromString(body.get("segmentIdB").getAsString());
            int indexB = body.get("insertIndexB").getAsInt();

            // Atomic version check + mutation
            synchronized (database) {
                // Version checks
                if (body.has("expectedVersionA")) {
                    int va = body.get("expectedVersionA").getAsInt();
                    Segment s = database.getSegment(segIdA);
                    int cur = s != null ? s.getVersion() : -1;
                    if (s == null || cur != va) {
                        JsonObject err = new JsonObject();
                        err.addProperty("error", "Version conflict on A. Entity was modified in-game.");
                        err.addProperty("currentVersion", cur);
                        sendJson(req.exchange, 409, err);
                        return;
                    }
                }
                if (body.has("expectedVersionB")) {
                    int vb = body.get("expectedVersionB").getAsInt();
                    Segment s = database.getSegment(segIdB);
                    int cur = s != null ? s.getVersion() : -1;
                    if (s == null || cur != vb) {
                        JsonObject err = new JsonObject();
                        err.addProperty("error", "Version conflict on B. Entity was modified in-game.");
                        err.addProperty("currentVersion", cur);
                        sendJson(req.exchange, 409, err);
                        return;
                    }
                }

                if (database.getSegment(segIdA) == null || database.getSegment(segIdB) == null) {
                    sendJson(req.exchange, 404, errorJson("Segment not found"));
                    return;
                }

                Node newNode = database.insertNodeAtIntersection(segIdA, indexA, segIdB, indexB, x, z);
                if (newNode == null) {
                    sendJson(req.exchange, 400, errorJson("Insertion failed (invalid indices or same segment)"));
                    return;
                }

                database.saveToDisk();
                JsonObject result = new JsonObject();
                result.add("node", GSON.toJsonTree(newNode));
                sendJson(req.exchange, 201, result);
            }
        } catch (Exception e) {
            sendJson(req.exchange, 400, errorJson("Invalid JSON: " + e.getMessage()));
        }
    }

    private void handleUpdateRoad(Request req) {
        String idStr = req.pathParams.getOrDefault("id", "");
        UUID id;
        try {
            id = UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            sendJson(req.exchange, 400, errorJson("Invalid road ID"));
            return;
        }

        if (req.body == null) {
            sendJson(req.exchange, 400, errorJson("Missing request body"));
            return;
        }

        try {
            JsonObject body = JsonParser.parseString(req.body).getAsJsonObject();
            int expectedVersion = body.has("expectedVersion") ? body.get("expectedVersion").getAsInt() : -1;

            // Atomic version check + mutation
            synchronized (database) {
                Road existing = database.getRoad(id);
                if (existing == null) {
                    sendJson(req.exchange, 404, errorJson("Road not found"));
                    return;
                }

                if (expectedVersion >= 0 && existing.getVersion() != expectedVersion) {
                    JsonObject err = new JsonObject();
                    err.addProperty("error", "Version conflict. Entity was modified in-game.");
                    err.addProperty("currentVersion", existing.getVersion());
                    sendJson(req.exchange, 409, err);
                    return;
                }

                boolean changed = false;
                if (body.has("name")) {
                    existing.setName(body.get("name").getAsString());
                    changed = true;
                }
                if (body.has("color")) {
                    existing.setColor(body.get("color").getAsString());
                    changed = true;
                }
                if (body.has("classification")) {
                    String cls = body.get("classification").getAsString();
                    if (cls.length() > 1 && cls.matches("^[GSXYC].*")) {
                        cls = cls.substring(0, 1);
                    }
                    existing.setClassification(cls);
                    changed = true;
                }
                if (body.has("number")) {
                    existing.setNumber(body.get("number").getAsString());
                    changed = true;
                }

                if (changed) {
                    int nextVer = existing.getVersion() + 1;
                    existing.setVersion(nextVer);
                    existing.setModifiedAt(System.currentTimeMillis());
                }

                database.saveToDisk();
                sendJson(req.exchange, 200, GSON.toJsonTree(existing));
            }
        } catch (Exception e) {
            sendJson(req.exchange, 400, errorJson("Invalid JSON: " + e.getMessage()));
        }
    }

    private void handleDeleteRoad(Request req) {
        String idStr = req.pathParams.getOrDefault("id", "");
        UUID id;
        try {
            id = UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            sendJson(req.exchange, 400, errorJson("Invalid road ID"));
            return;
        }

        if (database.getRoad(id) == null) {
            sendJson(req.exchange, 404, errorJson("Road not found"));
            return;
        }

        database.removeRoad(id);
        database.saveToDisk();
        sendJson(req.exchange, 200, okJson("Road deleted"));
    }

    /**
     * POST /api/roads/restore Replaces the entire in-memory road network with the given JSON snapshot.
     */
    private void handleRestoreRoads(Request req) {
        try {
            String raw = new String(req.exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject body = JsonParser.parseString(raw).getAsJsonObject();
            database.restoreFromJson(body);
            sendJson(req.exchange, 200, okJson("Restored"));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Restore failed: {0}", e.getMessage());
            sendJson(req.exchange, 400, errorJson("Invalid snapshot: " + e.getMessage()));
        }
    }

    // -------- Response helpers --------

    private static void sendResponse(HttpExchange exchange, int status, String contentType, String body) {
        try {
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(status, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send response: {0}", e.getMessage());
        }
    }

    private static void sendJson(HttpExchange exchange, int status, JsonObject json) {
        sendResponse(exchange, status, "application/json; charset=utf-8", GSON.toJson(json));
    }

    private static void sendJson(HttpExchange exchange, int status, JsonArray json) {
        sendResponse(exchange, status, "application/json; charset=utf-8", GSON.toJson(json));
    }

    private static void sendJson(HttpExchange exchange, int status, com.google.gson.JsonElement json) {
        sendResponse(exchange, status, "application/json; charset=utf-8", GSON.toJson(json));
    }

    private static JsonObject errorJson(String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", message);
        return obj;
    }

    private static JsonObject okJson(String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("ok", true);
        obj.addProperty("message", message);
        return obj;
    }

    // -------- Internal types --------

    private record Route(String method, Pattern pattern, String literal, Consumer<Request> handler) {
        Route(String method, String literal, Consumer<Request> handler) {
            this(method, null, literal, handler);
        }

        Route(String method, Pattern pattern, Consumer<Request> handler) {
            this(method, pattern, null, handler);
        }
    }

    private record Request(HttpExchange exchange, String body, Map<String, String> query,
        Map<String, String> pathParams) {
    }
}
