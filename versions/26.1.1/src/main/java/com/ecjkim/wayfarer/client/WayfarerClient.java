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

import com.ecjkim.wayfarer.client.road.RoadMetadataScreen;
import com.ecjkim.wayfarer.client.road.RoadRecordingManager;
import com.ecjkim.wayfarer.client.road.XaeroMapOverlay;
import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Segment;

import org.lwjgl.glfw.GLFW;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

public class WayfarerClient implements ClientModInitializer {
    private static final RoadRecordingManager ROAD_MANAGER = new RoadRecordingManager();

    private final IntSet keysDownLastTick = new IntOpenHashSet();

    @Override
    public void onInitializeClient() {
        RoadNetworkDatabase.getInstance().loadFromDisk();

        XaeroMapOverlay.register();
        var lifecycles = net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING;
        lifecycles.register(client -> {
            RoadNetworkDatabase.getInstance().saveToDisk();
        });
        var ticks = net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK;
        ticks.register(this::handleClientTick);
    }

    public static void reloadHotkeys() {}

    private void handleClientTick(Minecraft client) {
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
