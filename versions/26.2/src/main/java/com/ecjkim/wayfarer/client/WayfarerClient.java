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

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import com.ecjkim.wayfarer.client.road.RoadDataStore;
import com.ecjkim.wayfarer.client.road.RoadListScreen;
import com.ecjkim.wayfarer.client.road.RoadMetadataScreen;
import com.ecjkim.wayfarer.client.road.RoadPreviewServer;
import com.ecjkim.wayfarer.client.road.RoadRecordingManager;
import com.ecjkim.wayfarer.client.road.model.RoadPath;

import org.lwjgl.glfw.GLFW;

public class WayfarerClient implements ClientModInitializer {
    private static final RoadDataStore ROAD_DATA_STORE = new RoadDataStore();
    private static final RoadPreviewServer PREVIEW_SERVER = new RoadPreviewServer(ROAD_DATA_STORE);
    private static final RoadRecordingManager ROAD_MANAGER = new RoadRecordingManager(ROAD_DATA_STORE);
    private static final KeyMapping TOGGLE_RECORDING_KEY =
        KeyMappingHelper.registerKeyMapping(new KeyMapping("key.wayfarer.toggle_road_recording",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, KeyMapping.Category.MISC));
    private static final KeyMapping OPEN_ROAD_LIST_KEY =
        KeyMappingHelper.registerKeyMapping(new KeyMapping("key.wayfarer.open_road_list", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_N, KeyMapping.Category.MISC));

    @Override
    public void onInitializeClient() {
        PREVIEW_SERVER.start();
        ClientTickEvents.END_CLIENT_TICK.register(this::handleClientTick);
    }

    private void handleClientTick(Minecraft client) {
        ROAD_DATA_STORE.syncToCurrentContext();

        while (TOGGLE_RECORDING_KEY.consumeClick()) {
            if (client.player == null) {
                continue;
            }

            if (ROAD_MANAGER.isRecording()) {
                ROAD_MANAGER.stopRecording();
                if (ROAD_MANAGER.getRecordedPointCount() < 2) {
                    ROAD_MANAGER.discardRecording();
                    client.player.sendSystemMessage(Component.literal("记录点太少，已取消这次道路记录。"));
                } else if (ROAD_MANAGER.isAppending()) {
                    client.setScreenAndShow(new RoadMetadataScreen(RoadMetadataScreen.Mode.EDIT,
                        ROAD_MANAGER::finishAppend, ROAD_MANAGER::discardRecording, ROAD_MANAGER.getAppendRoadName(),
                        String.valueOf(ROAD_MANAGER.getAppendRoadWidth())));
                    client.player.sendSystemMessage(Component.literal("继续录制已停止，确认后保存。"));
                } else {
                    client.setScreenAndShow(new RoadMetadataScreen(RoadMetadataScreen.Mode.CREATE,
                        ROAD_MANAGER::saveRecording, ROAD_MANAGER::discardRecording, null, null));
                    client.player.sendSystemMessage(Component.literal("道路记录已停止，填写名称后保存。"));
                }
            } else {
                ROAD_MANAGER.startRecording();
                client.player.sendSystemMessage(Component.literal("道路记录已开始。"));
            }
        }

        while (OPEN_ROAD_LIST_KEY.consumeClick()) {
            client.setScreenAndShow(new RoadListScreen(ROAD_DATA_STORE, PREVIEW_SERVER, this::startAppendRecording));
        }

        ROAD_MANAGER.tick(client);
    }

    private void startAppendRecording(RoadPath road) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null)
            return;

        ROAD_MANAGER.startAppend(road, client.player.getX(), client.player.getY(), client.player.getZ());
        client.player.sendSystemMessage(Component.literal("继续录制道路: " + road.name + "（按 R 结束并保存）"));
    }
}
