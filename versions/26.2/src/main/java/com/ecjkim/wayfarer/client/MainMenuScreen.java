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

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.ecjkim.wayfarer.client.gui.WayfarerConfigScreen;
import com.ecjkim.wayfarer.client.road.RoadListScreen;

public class MainMenuScreen extends Screen {
    private static final int PANEL_WIDTH = 200;
    private static final int PANEL_HEIGHT = 120;

    public MainMenuScreen() {
        super(Component.literal("Wayfarer"));
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int top = this.height / 2 - PANEL_HEIGHT / 2;
        int buttonWidth = PANEL_WIDTH - 40;
        int btnX = centerX - buttonWidth / 2;
        int y = top + 36;

        this.addRenderableWidget(Button.builder(Component.literal("道路管理"), button -> {
            this.minecraft.setScreenAndShow(new RoadListScreen());
        }).bounds(btnX, y, buttonWidth, 20).build());
        y += 24;

        this.addRenderableWidget(Button.builder(Component.literal("设置"), button -> {
            WayfarerConfigScreen.openConfigScreen();
        }).bounds(btnX, y, buttonWidth, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ex, int mouseX, int mouseY, float tickDelta) {
        ex.fill(0, 0, this.width, this.height, 0xFF1B1F28);

        int centerX = this.width / 2;
        int left = centerX - PANEL_WIDTH / 2;
        int top = this.height / 2 - PANEL_HEIGHT / 2;

        ex.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xE01B1F28);
        ex.fill(left, top, left + PANEL_WIDTH, top + 1, 0xFF4E5768);
        ex.fill(left, top + PANEL_HEIGHT - 1, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFF1A1F27);

        ex.centeredText(this.font, this.title, centerX, top + 10, 0xFFFFFFFF);

        int sepY = top + 27;
        ex.fill(left + 16, sepY, left + PANEL_WIDTH - 16, sepY + 1, 0xFF3A3F4A);

        super.extractRenderState(ex, mouseX, mouseY, tickDelta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
