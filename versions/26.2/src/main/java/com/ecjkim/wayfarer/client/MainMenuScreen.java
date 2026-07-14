package com.ecjkim.wayfarer.client;

import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.ecjkim.wayfarer.client.road.RoadDataStore;
import com.ecjkim.wayfarer.client.road.RoadListScreen;
import com.ecjkim.wayfarer.client.road.RoadPreviewServer;
import com.ecjkim.wayfarer.client.road.model.RoadPath;

public class MainMenuScreen extends Screen {
    private static final int PANEL_WIDTH = 240;
    private static final int PANEL_HEIGHT = 200;

    private final RoadDataStore roadDataStore;
    private final RoadPreviewServer previewServer;
    private final Consumer<RoadPath> onContinueRecording;

    public MainMenuScreen(RoadDataStore roadDataStore, RoadPreviewServer previewServer,
        Consumer<RoadPath> onContinueRecording) {
        super(Component.literal("Wayfarer 主菜单"));
        this.roadDataStore = roadDataStore;
        this.previewServer = previewServer;
        this.onContinueRecording = onContinueRecording;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int top = this.height / 2 - PANEL_HEIGHT / 2;
        int buttonWidth = PANEL_WIDTH - 40;

        this.addRenderableWidget(Button.builder(Component.literal("道路管理"), button -> {
            this.minecraft.setScreenAndShow(new RoadListScreen(roadDataStore, previewServer, onContinueRecording));
        }).bounds(centerX - buttonWidth / 2, top + 45, buttonWidth, 22).build());

        this.addRenderableWidget(Button.builder(Component.literal("设置"), button -> {
            this.minecraft.setScreenAndShow(new SettingsScreen(this));
        }).bounds(centerX - buttonWidth / 2, top + 75, buttonWidth, 22).build());

        this.addRenderableWidget(Button.builder(Component.literal("关闭"), button -> {
            this.onClose();
        }).bounds(centerX - buttonWidth / 2, top + 125, buttonWidth, 22).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float tickDelta) {
        extractor.fill(0, 0, this.width, this.height, 0xCC000000);

        int centerX = this.width / 2;
        int left = centerX - PANEL_WIDTH / 2;
        int top = this.height / 2 - PANEL_HEIGHT / 2;

        extractor.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xCC1B1F28);
        extractor.fill(left, top, left + PANEL_WIDTH, top + 1, 0xFF4E5768);
        extractor.fill(left, top + PANEL_HEIGHT - 1, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFF1A1F27);

        extractor.text(this.font, this.title, centerX - this.font.width(this.title) / 2, top + 12, 16777215, false);
        extractor.text(this.font, Component.literal("当前实例: " + roadDataStore.getContextLabel()),
            left + 16, top + PANEL_HEIGHT - 20, 11184810, false);

        super.extractRenderState(extractor, mouseX, mouseY, tickDelta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
