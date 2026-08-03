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

import com.ecjkim.wayfarer.client.render.NodeIndicatorRenderer;
import com.ecjkim.wayfarer.client.render.SurveyHud;
import com.ecjkim.wayfarer.client.road.RoadMetadataScreen;
import com.ecjkim.wayfarer.client.road.RoadRecordingManager;
import com.ecjkim.wayfarer.client.road.XaeroMapOverlay;
import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Segment;
import com.ecjkim.wayfarer.client.road.record.SurveySession;
import com.ecjkim.wayfarer.client.road.server.WayfarerHttpServer;

import org.lwjgl.glfw.GLFW;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

public class WayfarerClient implements ClientModInitializer {
    private static final RoadRecordingManager ROAD_MANAGER = new RoadRecordingManager();
    private static final SurveySession SURVEY_SESSION = new SurveySession();
    private static volatile WayfarerHttpServer httpServer;
    private static volatile Thread httpThread;

    private final IntSet keysDownLastTick = new IntOpenHashSet();
    private boolean hadToolLastTick = false;
    private volatile double pendingScrollDelta;
    private boolean worldInitialized = false;

    public static SurveySession getSurveySession() {
        return SURVEY_SESSION;
    }

    @Override
    public void onInitializeClient() {
        startHttpServer();

        XaeroMapOverlay.register();
        NodeIndicatorRenderer.register();
        SurveyHud.register();
        var lifecycles = net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING;
        lifecycles.register(client -> {
            stopHttpServer();
            RoadNetworkDatabase.getInstance().saveToDisk();
        });
        var ticks = net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK;
        ticks.register(this::handleClientTick);

        // Register scroll callback for Ctrl-scroll corner type switching
        GLFW.glfwSetScrollCallback(Minecraft.getInstance().getWindow().handle(), (win, dx, dy) -> {
            pendingScrollDelta = dy;
        });
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
        RoadNetworkDatabase.getInstance().setWorldKey(worldKey);
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

        if (client.player == null)
            return;

        long window = client.getWindow().handle();
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

        tickSurvey(client, window);
        ROAD_MANAGER.tick(client);
    }

    private void tickSurvey(Minecraft client, long window) {
        LocalPlayer player = client.player;
        if (player == null) {
            hadToolLastTick = false;
            return;
        }
        if (!WayfarerConfig.getInstance().toolItemEnabled) {
            hadToolLastTick = false;
            return;
        }

        boolean hasTool = ToolItemManager.hasToolItem(player);
        if (hasTool && !hadToolLastTick) {
            SURVEY_SESSION.onToolPickedUp(player);
        }
        hadToolLastTick = hasTool;

        // Handle Ctrl-scroll for corner type switching
        double scroll = pendingScrollDelta;
        if (scroll != 0) {
            pendingScrollDelta = 0;
            boolean ctrlDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
            if (ctrlDown && hasTool) {
                if (scroll > 0) {
                    SURVEY_SESSION.cycleDirectionNext();
                } else {
                    SURVEY_SESSION.cycleDirectionPrev();
                }
                player.sendSystemMessage(Component.literal("方向类型: " + SURVEY_SESSION.getCurrentDirection().name()));
            }
        }

        SURVEY_SESSION.tick(client, window);
    }

    private void handleSetHeldItemAsTool(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null)
            return;
        ToolItemManager.setHeldItemAsTool(player);
        if (ToolItemManager.getToolItem().isEmpty()) {
            player.sendSystemMessage(Component.literal("手持物品为空，已清除 Survey 工具设置。"));
        } else {
            player.sendSystemMessage(
                Component.literal("已将手持物品设为 Survey 工具: " + ToolItemManager.getToolItem().getHoverName().getString()));
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
        if (ToolItemManager.hasToolItem(player) && WayfarerConfig.getInstance().toolItemEnabled) {
            if (!ROAD_MANAGER.isRecording()) {
                player.sendSystemMessage(Component.literal("正在 Survey 模式，请切换手中物品后重试"));
            }
            return;
        }

        if (ROAD_MANAGER.isRecording()) {
            ROAD_MANAGER.stopRecording();
            if (ROAD_MANAGER.getRecordedPointCount() < 2) {
                ROAD_MANAGER.discardRecording();
                player.sendSystemMessage(Component.literal("记录点太少，已取消这次道路记录。"));
            } else {
                Segment segment = ROAD_MANAGER.saveRecording();
                if (segment != null) {
                    client.setScreen(new RoadMetadataScreen(segment, savedRoad -> {
                        player.sendSystemMessage(Component.literal("道路已保存: " + savedRoad.getName()));
                    }, ROAD_MANAGER::discardRecording));
                    player.sendSystemMessage(Component.literal("道路记录已停止，选择或创建道路后保存。"));
                }
            }
        } else {
            ROAD_MANAGER.startRecording();
            player.sendSystemMessage(Component.literal("道路记录已开始。"));
        }
    }
}
