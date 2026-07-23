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

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import com.ecjkim.wayfarer.client.gui.WayfarerConfigScreen;
import com.ecjkim.wayfarer.client.road.RoadListScreen;

public class MainMenuScreen extends Screen {
    private static final int PANEL_WIDTH = 200;
    private static final int PANEL_HEIGHT = 120;
    private static final int BUTTON_WIDTH = 160;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;

    public MainMenuScreen() {
        super(Component.literal("Wayfarer"));
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int panelTop = this.height / 2 - PANEL_HEIGHT / 2;
        int btnX = centerX - BUTTON_WIDTH / 2;

        int y = panelTop + 36;

        addRenderableWidget(Button.builder(Component.literal(I18n.get("wayfarer.main.road_management")), btn -> {
            this.minecraft.setScreen(new RoadListScreen());
        }).bounds(btnX, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += BUTTON_HEIGHT + BUTTON_GAP;

        addRenderableWidget(Button.builder(Component.literal(I18n.get("wayfarer.main.settings")), btn -> {
            WayfarerConfigScreen.openConfigScreen();
        }).bounds(btnX, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xFF1B1F28);

        int panelLeft = this.width / 2 - PANEL_WIDTH / 2;
        int panelTop = this.height / 2 - PANEL_HEIGHT / 2;

        graphics.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + PANEL_HEIGHT, 0xE01B1F28);
        graphics.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + 1, 0xFF4E5768);
        graphics.fill(panelLeft, panelTop + PANEL_HEIGHT - 1, panelLeft + PANEL_WIDTH, panelTop + PANEL_HEIGHT,
            0xFF1A1F27);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, panelTop + 10, 0xFFFFFFFF);

        int sepY = panelTop + 27;
        graphics.fill(panelLeft + 16, sepY, panelLeft + PANEL_WIDTH - 16, sepY + 1, 0xFF3A3F4A);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
