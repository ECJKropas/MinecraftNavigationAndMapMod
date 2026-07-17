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
package com.ecjkim.wayfarer.client.road;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.minecraft.client.Minecraft;

import com.ecjkim.wayfarer.client.road.model.RoadBook;
import com.ecjkim.wayfarer.client.road.model.RoadIntersection;
import com.ecjkim.wayfarer.client.road.model.RoadPath;
import com.ecjkim.wayfarer.client.road.model.RoadPoint;
import com.ecjkim.wayfarer.client.road.model.RoadSegment;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class RoadDataStore {
    private static final Logger LOGGER = Logger.getLogger("Wayfarer|RoadData");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path baseDirectory = Path.of(System.getProperty("user.dir"), "config", "wayfarer");
    private final Path legacyDataFile = baseDirectory.resolve("roads.json");
    private final Path legacyBackupFile = baseDirectory.resolve("roads.legacy.json");
    private final Path legacyMigrationMarker = baseDirectory.resolve(".legacy-migrated");
    private RoadStorageContext currentContext = RoadStorageContext.resolve(Minecraft.getInstance());
    private RoadBook roadBook = loadRoadBook(currentContext);

    public synchronized List<RoadPath> getRoads() {
        syncToCurrentContext();
        return new ArrayList<>(roadBook.roads);
    }

    public Path getDataFile() {
        syncToCurrentContext();
        return currentContext.resolveDataFile(baseDirectory);
    }

    public synchronized String getContextLabel() {
        syncToCurrentContext();
        return currentContext.getDisplayName();
    }

    public synchronized boolean hasBoundContext() {
        syncToCurrentContext();
        return currentContext.isBound();
    }

    public synchronized void syncToCurrentContext() {
        syncToContext(RoadStorageContext.resolve(Minecraft.getInstance()));
    }

    public synchronized void reloadFromDisk() {
        syncToCurrentContext();
        roadBook = loadRoadBook(currentContext);
    }

    public synchronized RoadBook snapshot() {
        syncToCurrentContext();
        return normalize(GSON.fromJson(GSON.toJson(roadBook), RoadBook.class));
    }

    public synchronized void addRoad(RoadPath road) {
        syncToCurrentContext();
        roadBook.roads.add(road);
        persist();
    }

    public synchronized void updateRoad(String roadId, String name, double width, String classification,
        String number) {
        syncToCurrentContext();
        for (RoadPath road : roadBook.roads) {
            if (road.id != null && road.id.equals(roadId)) {
                road.name = name;
                road.width = width;
                road.classification = classification;
                road.number = number;
                persist();
                return;
            }
        }
    }

    public synchronized void deleteRoad(String roadId) {
        syncToCurrentContext();
        roadBook.roads.removeIf(road -> road.id != null && road.id.equals(roadId));
        for (RoadPath road : roadBook.roads) {
            if (road.intersections != null) {
                road.intersections.removeIf(isect -> roadId.equals(isect.roadId));
            }
        }
        persist();
    }

    /**
     * Check every existing road (excluding {@code newRoad} itself) and snap its endpoints onto {@code newRoad} when
     * they are within snapping threshold of an interior segment. This makes roads recorded earlier connect cleanly
     * to a road that was recorded later (the reverse direction of the snap that happens during
     * {@link RoadRecordingManager#saveRoad saveRoad}).
     *
     * <p>
     * Any road whose endpoints were moved is updated in-place; the entire road book is persisted if at least one
     * change occurred.
     * </p>
     */
    public synchronized void snapRoadsToRoad(RoadPath newRoad) {
        syncToCurrentContext();
        if (newRoad == null) {
            return;
        }

        boolean anySnapped = false;
        for (RoadPath existing : roadBook.roads) {
            if (existing.id != null && existing.id.equals(newRoad.id)) {
                continue;
            }
            if (existing.points == null || existing.points.size() < 2) {
                continue;
            }
            if (RoadRecordingManager.snapEndpointsToRoad(existing, newRoad)) {
                anySnapped = true;
            }
        }

        if (anySnapped) {
            persist();
        }
    }

    /**
     * Update the intersections list of the road in the store whose {@code id} matches {@code road.id}. This is
     * used after a post-add re-detection of intersections refreshes the in-memory copy.
     */
    public synchronized void refreshRoadIntersections(RoadPath road) {
        syncToCurrentContext();
        if (road == null || road.id == null) {
            return;
        }
        for (RoadPath stored : roadBook.roads) {
            if (stored.id != null && stored.id.equals(road.id)) {
                stored.intersections = road.intersections;
                persist();
                return;
            }
        }
    }

    public synchronized String toJson() {
        syncToCurrentContext();
        return GSON.toJson(snapshot().roads);
    }

    /**
     * Export all roads as a GeoJSON FeatureCollection.
     *
     * <p>
     * Each road is a Feature with a LineString geometry (x → longitude, z → latitude). Properties include id, name,
     * number, classification, width, length, and intersection count.
     * </p>
     */
    public synchronized String toGeoJson() {
        syncToCurrentContext();
        List<RoadPath> roads = getRoads();

        StringBuilder sb = new StringBuilder(65536);
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[");
        boolean first = true;
        for (RoadPath road : roads) {
            if (road.points == null || road.points.size() < 2)
                continue;
            if (!first)
                sb.append(',');
            first = false;

            // classification defaults to C, normalize to first letter for JS compatibility
            String rawCls = road.classification != null ? road.classification : "C";
            String cls = rawCls.length() > 1 ? rawCls.substring(0, 1) : rawCls;

            // compute length
            double length = 0.0;
            RoadPoint prev = null;
            for (RoadPoint pt : road.points) {
                if (prev != null) {
                    double dx = pt.x - prev.x;
                    double dz = pt.z - prev.z;
                    length += Math.sqrt(dx * dx + dz * dz);
                }
                prev = pt;
            }

            sb.append("{\"type\":\"Feature\",\"properties\":{");
            appendJsonProperty(sb, "id", road.id);
            sb.append(',');
            appendJsonProperty(sb, "name", road.name);
            sb.append(',');
            appendJsonProperty(sb, "number", road.number);
            sb.append(',');
            appendJsonProperty(sb, "classification", cls);
            sb.append(',');
            sb.append("\"width\":").append(road.width);
            sb.append(',');
            sb.append("\"length\":").append(Math.round(length * 10.0) / 10.0);
            int intersectionCount = road.intersections != null ? road.intersections.size() : 0;
            sb.append(",\"intersectionCount\":").append(intersectionCount);
            // intersections detail
            if (road.intersections != null && !road.intersections.isEmpty()) {
                sb.append(",\"intersectionDetails\":[");
                boolean intFirst = true;
                for (RoadIntersection isect : road.intersections) {
                    if (!intFirst)
                        sb.append(',');
                    intFirst = false;
                    sb.append('{');
                    if (isect.id != null) {
                        appendJsonProperty(sb, "id", isect.id);
                        sb.append(',');
                    }
                    if (isect.position != null) {
                        sb.append("\"position\":{\"x\":").append(isect.position.x).append(",\"y\":")
                            .append(isect.position.y).append(",\"z\":").append(isect.position.z).append('}');
                    } else {
                        sb.append("\"position\":{\"x\":").append(isect.x).append(",\"y\":").append(isect.y)
                            .append(",\"z\":").append(isect.z).append('}');
                    }
                    if (isect.type != null) {
                        sb.append(',');
                        appendJsonProperty(sb, "type", isect.type);
                    }
                    if (isect.name != null) {
                        sb.append(',');
                        appendJsonProperty(sb, "name", isect.name);
                    }
                    sb.append('}');
                }
                sb.append(']');
            }
            // style override
            if (road.style != null) {
                sb.append(",\"style\":{");
                boolean styleFirst = true;
                if (road.style.color != null) {
                    appendJsonProperty(sb, "color", road.style.color);
                    styleFirst = false;
                }
                if (road.style.lineWidth != null) {
                    if (!styleFirst)
                        sb.append(',');
                    sb.append("\"lineWidth\":").append(road.style.lineWidth);
                    styleFirst = false;
                }
                if (road.style.dashPattern != null) {
                    if (!styleFirst)
                        sb.append(',');
                    appendJsonProperty(sb, "dashPattern", road.style.dashPattern);
                }
                sb.append('}');
            }
            sb.append("},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[");
            boolean ptFirst = true;
            for (RoadPoint pt : road.points) {
                if (!ptFirst)
                    sb.append(',');
                ptFirst = false;
                sb.append('[').append(pt.x).append(',').append(pt.z).append(']');
            }
            sb.append("]}}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * Export a single road as a GeoJSON Feature, or {@code null} if not found.
     */
    public synchronized String toGeoJsonFeature(String roadId) {
        syncToCurrentContext();
        for (RoadPath road : getRoads()) {
            if (road.id != null && road.id.equals(roadId)) {
                return toGeoJsonFeature(road);
            }
        }
        return null;
    }

    private String toGeoJsonFeature(RoadPath road) {
        String rawCls = road.classification != null ? road.classification : "C";
        String cls = rawCls.length() > 1 ? rawCls.substring(0, 1) : rawCls;
        double length = 0.0;
        RoadPoint prev = null;
        for (RoadPoint pt : road.points) {
            if (prev != null) {
                double dx = pt.x - prev.x;
                double dz = pt.z - prev.z;
                length += Math.sqrt(dx * dx + dz * dz);
            }
            prev = pt;
        }

        StringBuilder sb = new StringBuilder(8192);
        sb.append("{\"type\":\"Feature\",\"properties\":{");
        appendJsonProperty(sb, "id", road.id);
        sb.append(',');
        appendJsonProperty(sb, "name", road.name);
        sb.append(',');
        appendJsonProperty(sb, "number", road.number);
        sb.append(',');
        appendJsonProperty(sb, "classification", cls);
        sb.append(',');
        sb.append("\"width\":").append(road.width);
        sb.append(',');
        sb.append("\"length\":").append(Math.round(length * 10.0) / 10.0);
        int intersectionCount = road.intersections != null ? road.intersections.size() : 0;
        sb.append(",\"intersectionCount\":").append(intersectionCount);
        // segments
        if (road.segments != null && !road.segments.isEmpty()) {
            sb.append(",\"segments\":[");
            boolean segFirst = true;
            for (RoadSegment seg : road.segments) {
                if (!segFirst)
                    sb.append(',');
                segFirst = false;
                appendJsonProperty(sb, null, seg.id);
            }
            sb.append(']');
        }
        // intersections detail
        if (road.intersections != null && !road.intersections.isEmpty()) {
            sb.append(",\"intersectionDetails\":[");
            boolean intFirst = true;
            for (RoadIntersection isect : road.intersections) {
                if (!intFirst)
                    sb.append(',');
                intFirst = false;
                sb.append('{');
                if (isect.id != null) {
                    appendJsonProperty(sb, "id", isect.id);
                    sb.append(',');
                }
                if (isect.position != null) {
                    sb.append("\"position\":{\"x\":").append(isect.position.x).append(",\"y\":")
                        .append(isect.position.y).append(",\"z\":").append(isect.position.z).append('}');
                } else {
                    // fallback to legacy fields
                    sb.append("\"position\":{\"x\":").append(isect.x).append(",\"y\":").append(isect.y)
                        .append(",\"z\":").append(isect.z).append('}');
                }
                if (isect.type != null) {
                    sb.append(',');
                    appendJsonProperty(sb, "type", isect.type);
                }
                if (isect.name != null) {
                    sb.append(',');
                    appendJsonProperty(sb, "name", isect.name);
                }
                sb.append('}');
            }
            sb.append(']');
        }
        // style
        if (road.style != null) {
            sb.append(",\"style\":{");
            boolean styleFirst = true;
            if (road.style.color != null) {
                appendJsonProperty(sb, "color", road.style.color);
                styleFirst = false;
            }
            if (road.style.lineWidth != null) {
                if (!styleFirst)
                    sb.append(',');
                sb.append("\"lineWidth\":").append(road.style.lineWidth);
                styleFirst = false;
            }
            if (road.style.dashPattern != null) {
                if (!styleFirst)
                    sb.append(',');
                appendJsonProperty(sb, "dashPattern", road.style.dashPattern);
            }
            sb.append('}');
        }
        sb.append("},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[");
        boolean ptFirst = true;
        for (RoadPoint pt : road.points) {
            if (!ptFirst)
                sb.append(',');
            ptFirst = false;
            sb.append('[').append(pt.x).append(',').append(pt.z).append(']');
        }
        sb.append("]}}");
        return sb.toString();
    }

    private static void appendJsonProperty(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"').append(escapeJson(value)).append('"');
        }
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int)c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private void syncToContext(RoadStorageContext nextContext) {
        RoadStorageContext safeContext = nextContext == null ? RoadStorageContext.unbound() : nextContext;
        if (currentContext != null && currentContext.getStorageKey().equals(safeContext.getStorageKey())) {
            return;
        }

        if (!safeContext.isBound() && currentContext != null && currentContext.isBound()) {
            return;
        }

        currentContext = safeContext;
        roadBook = loadRoadBook(currentContext);
    }

    private RoadBook loadRoadBook(RoadStorageContext context) {
        Path dataFile = context.resolveDataFile(baseDirectory);
        if (!context.isBound()) {
            return readRoadBookIfExists(dataFile);
        }

        if (!Files.exists(dataFile)) {
            migrateLegacyRoadBook(dataFile);
        }

        if (!Files.exists(dataFile)) {
            return new RoadBook();
        }

        return readRoadBookIfExists(dataFile);
    }

    private RoadBook readRoadBookIfExists(Path dataFile) {
        if (!Files.exists(dataFile)) {
            return new RoadBook();
        }

        try {
            String json = Files.readString(dataFile, StandardCharsets.UTF_8);
            RoadBook loaded = GSON.fromJson(json, RoadBook.class);
            return normalize(loaded);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Failed to read road data from {0}: {1}",
                new Object[] {dataFile, exception.getMessage()});
            return new RoadBook();
        }
    }

    private void migrateLegacyRoadBook(Path targetDataFile) {
        if (Files.exists(legacyMigrationMarker) || !Files.exists(legacyDataFile)) {
            return;
        }

        try {
            Files.createDirectories(targetDataFile.getParent());
            Files.createDirectories(legacyBackupFile.getParent());
            Files.copy(legacyDataFile, legacyBackupFile, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(legacyDataFile, targetDataFile, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(legacyMigrationMarker,
                "migrated to " + currentContext.getDisplayName() + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to migrate legacy road data", exception);
        }
    }

    private void persist() {
        try {
            Path dataFile = currentContext.resolveDataFile(baseDirectory);
            Files.createDirectories(dataFile.getParent());
            Files.writeString(dataFile, GSON.toJson(roadBook), StandardCharsets.UTF_8);
            LOGGER.log(Level.INFO, "Persisted {0} road(s) to {1}", new Object[] {roadBook.roads.size(), dataFile});
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, "Failed to persist roads: {0}", exception.getMessage());
            throw new IllegalStateException("Failed to save road data", exception);
        }
    }

    private RoadBook normalize(RoadBook roadBook) {
        RoadBook safeRoadBook = roadBook == null ? new RoadBook() : roadBook;
        if (safeRoadBook.roads == null) {
            safeRoadBook.roads = new ArrayList<>();
        }
        return safeRoadBook;
    }
}
