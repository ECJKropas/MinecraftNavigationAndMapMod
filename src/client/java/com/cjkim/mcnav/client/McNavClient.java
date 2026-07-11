package com.cjkim.mcnav.client;

import com.cjkim.mcnav.client.road.RoadDataStore;
import com.cjkim.mcnav.client.road.RoadListScreen;
import com.cjkim.mcnav.client.road.RoadPreviewServer;
import com.cjkim.mcnav.client.road.RoadRecordingManager;
import com.cjkim.mcnav.client.road.RoadMetadataScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

public class McNavClient implements ClientModInitializer {
    private static final RoadDataStore ROAD_DATA_STORE = new RoadDataStore();
    private static final RoadPreviewServer PREVIEW_SERVER = new RoadPreviewServer(ROAD_DATA_STORE);
    private static final RoadRecordingManager ROAD_MANAGER = new RoadRecordingManager(ROAD_DATA_STORE);
    private static final KeyMapping TOGGLE_RECORDING_KEY = KeyBindingHelper.registerKeyBinding(
            new KeyMapping(
                    "key.mcnav.toggle_road_recording",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_R,
                    "category.mcnav"
            )
    );
    private static final KeyMapping OPEN_ROAD_LIST_KEY = KeyBindingHelper.registerKeyBinding(
            new KeyMapping(
                    "key.mcnav.open_road_list",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_N,
                    "category.mcnav"
            )
    );

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
                    client.player.displayClientMessage(Component.literal("记录点太少，已取消这次道路记录。"), false);
                } else {
                    client.setScreen(new RoadMetadataScreen(ROAD_MANAGER::saveRecording, ROAD_MANAGER::discardRecording));
                    client.player.displayClientMessage(Component.literal("道路记录已停止，填写名称后保存。"), false);
                }
            } else {
                ROAD_MANAGER.startRecording();
                client.player.displayClientMessage(Component.literal("道路记录已开始。"), false);
            }
        }

        while (OPEN_ROAD_LIST_KEY.consumeClick()) {
            client.setScreen(new RoadListScreen(ROAD_DATA_STORE, PREVIEW_SERVER));
        }

        ROAD_MANAGER.tick(client);
    }
}
