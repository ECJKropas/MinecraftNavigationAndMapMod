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
package com.ecjkim.wayfarer.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import com.ecjkim.wayfarer.client.render.NodeIndicatorRenderer;
import com.ecjkim.wayfarer.client.render.SurveyHud;
import com.ecjkim.wayfarer.client.render.SurveyRenderer;
import com.ecjkim.wayfarer.client.road.RoadMetadataScreen;
import com.ecjkim.wayfarer.client.road.RoadRecordingManager;
import com.ecjkim.wayfarer.client.road.XaeroMapOverlay;
import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Segment;
import com.ecjkim.wayfarer.client.road.record.SurveySession;
import com.ecjkim.wayfarer.client.road.server.WayfarerHttpServer;

import org.lwjgl.glfw.GLFW;

import fi.dy.masa.malilib.event.InitializationHandler;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

public class WayfarerClient implements ClientModInitializer {
    private static final RoadRecordingManager ROAD_MANAGER = new RoadRecordingManager();
    private static final SurveySession SURVEY_SESSION = new SurveySession();
    private static volatile WayfarerHttpServer httpServer;
    private static volatile Thread httpThread;

    private final IntSet keysDownLastTick = new IntOpenHashSet();
    private boolean hadToolLastTick = false;
    private boolean worldInitialized = false;

    public static SurveySession getSurveySession() {
        return SURVEY_SESSION;
    }

    @Override
    public void onInitializeClient() {
        InitializationHandler.getInstance().registerInitializationHandler(new WayfarerInitHandler());

        startHttpServer();

        XaeroMapOverlay.register();
        NodeIndicatorRenderer.register();
        SurveyRenderer.register();
        SurveyHud.register();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            stopHttpServer();
            RoadNetworkDatabase.getInstance().saveToDisk();
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::handleClientTick);
    }

    private static void startHttpServer() {
        if (httpServer != null)
            return;
        httpServer = new WayfarerHttpServer();
        httpThread = new Thread(httpServer, "Wayfarer-HTTP-Main");
        httpThread.setDaemon(true);
        httpThread.start();
    }

    private static void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        httpThread = null;
    }

    public static void reloadHotkeys() {}

    /**
     * Determines the world key for the current context and switches the road network database to the corresponding
     * per-world storage file.
     */
    private void initForWorld(Minecraft client) {
        String worldKey;
        if (client.getSingleplayerServer() != null) {
            worldKey = client.getSingleplayerServer().getWorldData().getLevelName();
        } else if (client.getCurrentServer() != null) {
            worldKey = client.getCurrentServer().ip;
        } else {
            worldKey = "default";
        }
        // Sanitize worldKey to be a valid directory name: replace colons and other problematic characters
        worldKey = sanitizeWorldKey(worldKey);
        RoadNetworkDatabase.getInstance().setWorldKey(worldKey);
    }

    /**
     * Sanitizes a world key string to be safe for use as a directory name.
     * Replaces characters that are invalid in file paths (e.g., colons in IP addresses like "127.0.0.1:25565").
     */
    private static String sanitizeWorldKey(String key) {
        if (key == null || key.isEmpty()) {
            return "default";
        }
        // Replace characters that are problematic in directory names across platforms
        return key.replace(':', '_').replace('/', '_').replace('\\', '_').replace('*', '_').replace('?', '_').replace('"', '_').replace('<', '_').replace('>', '_').replace('|', '_');
    }

    private void handleClientTick(Minecraft client) {
        // Detect world join to switch storage to the per-world file
        if (client.level != null && !worldInitialized) {
            initForWorld(client);
            worldInitialized = true;
        } else if (client.level == null) {
            worldInitialized = false;
        }

        if (client.screen != null) {
            keysDownLastTick.clear();
            ROAD_MANAGER.tick(client);
            return;
        }

        long window = client.getWindow().getWindow();
        WayfarerConfig config = WayfarerConfig.getInstance();

        for (WayfarerConfig.HotkeyBind bind : config.getHotkeysForAction("toggle_recording")) {
            if (consumeHotkey(window, bind)) {
                handleToggleRecording(client);
                break;
            }
        }

        for (WayfarerConfig.HotkeyBind bind : config.getHotkeysForAction("open_menu")) {
            if (consumeHotkey(window, bind)) {
                client.setScreen(new MainMenuScreen());
                break;
            }
        }

        for (WayfarerConfig.HotkeyBind bind : config.getHotkeysForAction("set_held_item_as_tool")) {
            if (consumeHotkey(window, bind)) {
                handleSetHeldItemAsTool(client);
                break;
            }
        }

        // Survey mode hotkeys
        handleSurveyHotkeys(client, window);

        tickSurvey(client, window);
        ROAD_MANAGER.tick(client);
    }

    private void handleSurveyHotkeys(Minecraft client, long window) {
        // Ctrl+Wheel corner type switching is handled by MouseScrollMixin
        // Left-click and Right-click for node creation are handled by SurveySession.processMouseClicks()
        // No additional hotkeys needed - survey mode uses mouse clicks for node placement
    }

    private void tickSurvey(Minecraft client, long window) {
        LocalPlayer player = client.player;
        if (player == null) {
            hadToolLastTick = false;
            return;
        }
        if (!WayfarerConfig.getInstance().isToolItemEnabled()) {
            hadToolLastTick = false;
            return;
        }

        boolean hasTool = ToolItemManager.hasToolItem(player);
        if (hasTool && !hadToolLastTick) {
            // Tool picked up: resume paused session or announce readiness
            SURVEY_SESSION.onToolPickedUp(player);
        }
        hadToolLastTick = hasTool;

        SURVEY_SESSION.tick(client, window);
    }

    private void handleSetHeldItemAsTool(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null)
            return;
        ToolItemManager.setHeldItemAsTool(player);
        if (ToolItemManager.getToolItem().isEmpty()) {
            player.displayClientMessage(Component.translatable("wayfarer.road.survey.tool_cleared_empty"), false);
        } else {
            player.displayClientMessage(Component.translatable("wayfarer.road.survey.tool_set",
                ToolItemManager.getToolItem().getHoverName().getString()), false);
        }
    }

    private boolean consumeHotkey(long window, WayfarerConfig.HotkeyBind bind) {
        boolean down = GLFW.glfwGetKey(window, bind.key) == GLFW.GLFW_PRESS;
        int id = bind.key * 10000 + Math.max(0, bind.modifierKey);
        boolean wasDown = keysDownLastTick.contains(id);

        if (down) {
            keysDownLastTick.add(id);
        } else {
            keysDownLastTick.remove(id);
        }

        if (!down || wasDown)
            return false;

        if (bind.modifierKey > 0) {
            return GLFW.glfwGetKey(window, bind.modifierKey) == GLFW.GLFW_PRESS;
        }
        return true;
    }

    private void handleToggleRecording(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null)
            return;

        // Auto / Survey mutual exclusion
        if (ToolItemManager.hasToolItem(player) && WayfarerConfig.getInstance().isToolItemEnabled()) {
            if (!ROAD_MANAGER.isRecording()) {
                player.displayClientMessage(Component.translatable("wayfarer.road.survey.in_survey_mode"), false);
            }
            return;
        }

        if (ROAD_MANAGER.isRecording()) {
            ROAD_MANAGER.stopRecording();
            if (ROAD_MANAGER.getRecordedPointCount() < 2) {
                ROAD_MANAGER.discardRecording();
                player.displayClientMessage(Component.translatable("wayfarer.road.survey.too_few_points_cancel"),
                    false);
            } else {
                Segment segment = ROAD_MANAGER.saveRecording();
                if (segment != null) {
                    client.setScreen(new RoadMetadataScreen(segment, savedRoad -> {
                        player.displayClientMessage(
                            Component.translatable("wayfarer.road.survey.road_saved", savedRoad.getName()), false);
                    }, () -> {
                        // Close without saving — segment stays as unfiled
                        player.displayClientMessage(
                            Component.translatable("wayfarer.road.gui.metadata.segment_left_unfiled"), false);
                    }, ROAD_MANAGER::discardRecording));
                    player.displayClientMessage(Component.translatable("wayfarer.road.survey.recording_ended"), false);
                }
            }
        } else {
            ROAD_MANAGER.startRecording();
            player.displayClientMessage(Component.translatable("wayfarer.road.survey.recording_started"), false);
        }
    }
}
