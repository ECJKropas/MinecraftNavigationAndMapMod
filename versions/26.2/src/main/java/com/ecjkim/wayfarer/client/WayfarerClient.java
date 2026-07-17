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

import com.ecjkim.wayfarer.client.road.RoadDataStore;
import com.ecjkim.wayfarer.client.road.RoadMetadataScreen;
import com.ecjkim.wayfarer.client.road.RoadPreviewServer;
import com.ecjkim.wayfarer.client.road.RoadRecordingManager;
import com.ecjkim.wayfarer.client.road.model.RoadPath;

import org.lwjgl.glfw.GLFW;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

public class WayfarerClient implements ClientModInitializer {
    private static final RoadDataStore ROAD_DATA_STORE = new RoadDataStore();
    private static final RoadPreviewServer PREVIEW_SERVER = new RoadPreviewServer(ROAD_DATA_STORE);
    private static final RoadRecordingManager ROAD_MANAGER = new RoadRecordingManager(ROAD_DATA_STORE);

    private final IntSet keysDownLastTick = new IntOpenHashSet();

    @Override
    public void onInitializeClient() {
        PREVIEW_SERVER.start();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> PREVIEW_SERVER.stop());
        ClientTickEvents.END_CLIENT_TICK.register(this::handleClientTick);
    }

    public static void reloadHotkeys() {}

    private void handleClientTick(Minecraft client) {
        ROAD_DATA_STORE.syncToCurrentContext();

        if (client.gui.screen() != null) {
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
                client
                    .setScreenAndShow(new MainMenuScreen(ROAD_DATA_STORE, PREVIEW_SERVER, this::startAppendRecording));
                break;
            }
        }

        ROAD_MANAGER.tick(client);
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

        if (ROAD_MANAGER.isRecording()) {
            ROAD_MANAGER.stopRecording();
            if (ROAD_MANAGER.getRecordedPointCount() < 2) {
                ROAD_MANAGER.discardRecording();
                player.sendSystemMessage(Component.literal("记录点太少，已取消这次道路记录。"));
            } else if (ROAD_MANAGER.isAppending()) {
                client.setScreenAndShow(new RoadMetadataScreen(RoadMetadataScreen.Mode.EDIT, ROAD_MANAGER::finishAppend,
                    ROAD_MANAGER::discardRecording, ROAD_MANAGER.getAppendRoadName(),
                    String.valueOf(ROAD_MANAGER.getAppendRoadWidth()), ROAD_MANAGER.getAppendRoadClassification(),
                    ROAD_MANAGER.getAppendRoadNumber()));
                player.sendSystemMessage(Component.literal("继续录制已停止，确认后保存。"));
            } else {
                client.setScreenAndShow(new RoadMetadataScreen(RoadMetadataScreen.Mode.CREATE,
                    ROAD_MANAGER::saveRecording, ROAD_MANAGER::discardRecording, null, null, null, null));
                player.sendSystemMessage(Component.literal("道路记录已停止，填写名称后保存。"));
            }
        } else {
            ROAD_MANAGER.startRecording();
            player.sendSystemMessage(Component.literal("道路记录已开始。"));
        }
    }

    private void startAppendRecording(RoadPath road) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null)
            return;

        ROAD_MANAGER.startAppend(road, client.player.getX(), client.player.getY(), client.player.getZ());
        client.player.sendSystemMessage(Component.literal("继续录制道路: " + road.name + "（按 R 结束并保存）"));
    }
}
