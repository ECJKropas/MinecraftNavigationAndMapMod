package com.ecjkim.wayfarer.client;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.ecjkim.wayfarer.client.road.RoadDataStore;
import com.ecjkim.wayfarer.client.road.RoadListScreen;
import com.ecjkim.wayfarer.client.road.RoadPreviewServer;
import com.ecjkim.wayfarer.client.road.model.RoadPath;

public class MainMenuScreen extends Screen {
    private static final int PANEL_WIDTH = 220;
    private static final int BUTTON_WIDTH = 180;
    private static final int BUTTON_HEIGHT = 22;
    private static final int BUTTON_GAP = 6;

    private final RoadDataStore roadDataStore;
    private final RoadPreviewServer previewServer;
    private final Consumer<RoadPath> onContinueRecording;
    private int panelLeft;
    private int panelTop;

    public MainMenuScreen(RoadDataStore roadDataStore, RoadPreviewServer previewServer,
        Consumer<RoadPath> onContinueRecording) {
        super(Component.literal("越陌度阡"));
        this.roadDataStore = roadDataStore;
        this.previewServer = previewServer;
        this.onContinueRecording = onContinueRecording;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        this.panelLeft = centerX - PANEL_WIDTH / 2;
        this.panelTop = this.height / 2 - 150;

        int btnX = centerX - BUTTON_WIDTH / 2;
        int y = panelTop + 40;

        addRenderableWidget(Button.builder(Component.literal("道路管理"), btn -> {
            this.minecraft.setScreen(
                new RoadListScreen(roadDataStore, previewServer, onContinueRecording));
        }).bounds(btnX, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += BUTTON_HEIGHT + BUTTON_GAP;

        addRenderableWidget(Button.builder(Component.literal("设置"), btn -> {
            this.minecraft.setScreen(new SettingsScreen(this));
        }).bounds(btnX, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += BUTTON_HEIGHT + BUTTON_GAP;

        // 关闭按钮
        y += BUTTON_GAP * 2;
        addRenderableWidget(Button.builder(Component.literal("关闭"), btn -> {
            this.minecraft.setScreen(null);
        }).bounds(btnX, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xCC000000);

        int panelBottom = panelTop + 240;
        graphics.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelBottom, 0xE01B1F28);
        graphics.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + 1, 0xFF4E5768);
        graphics.fill(panelLeft, panelBottom - 1, panelLeft + PANEL_WIDTH, panelBottom, 0xFF1A1F27);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, panelTop + 10, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font,
            Component.literal("当前实例: " + roadDataStore.getContextLabel()),
            this.width / 2, panelTop + 24, 0xFFAAAAAA);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
