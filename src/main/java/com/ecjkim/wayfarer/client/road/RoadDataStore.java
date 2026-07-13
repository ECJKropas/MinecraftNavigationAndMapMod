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

import net.minecraft.client.Minecraft;

import com.ecjkim.wayfarer.client.road.model.RoadBook;
import com.ecjkim.wayfarer.client.road.model.RoadPath;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class RoadDataStore {
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

    public synchronized void updateRoad(String roadId, String name, double width) {
        syncToCurrentContext();
        for (RoadPath road : roadBook.roads) {
            if (road.id != null && road.id.equals(roadId)) {
                road.name = name;
                road.width = width;
                persist();
                return;
            }
        }
    }

    public synchronized void deleteRoad(String roadId) {
        syncToCurrentContext();
        roadBook.roads.removeIf(road -> road.id != null && road.id.equals(roadId));
        persist();
    }

    public synchronized String toJson() {
        syncToCurrentContext();
        return GSON.toJson(snapshot().roads);
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
        } catch (IOException exception) {
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
