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
package com.cjkim.mcnav.client.road;

import java.util.function.BiConsumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

public class RoadMetadataScreen extends Screen {
    private static final int PANEL_WIDTH = 280;
    private static final int PANEL_HEIGHT = 184;

    private final BiConsumer<String, Double> onSave;
    private final Runnable onCancel;
    private EditBox nameBox;
    private EditBox widthBox;

    public RoadMetadataScreen(BiConsumer<String, Double> onSave, Runnable onCancel) {
        super(Component.literal("保存道路"));
        this.onSave = onSave;
        this.onCancel = onCancel;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int left = centerX - PANEL_WIDTH / 2;
        int top = this.height / 2 - PANEL_HEIGHT / 2;
        int fieldLeft = left + 20;
        int fieldWidth = PANEL_WIDTH - 40;
        int nameBoxY = top + 52;
        int widthBoxY = top + 94;
        int buttonY = top + PANEL_HEIGHT - 30;

        this.nameBox = new EditBox(this.font, fieldLeft, nameBoxY, fieldWidth, 20, Component.literal("道路名"));
        this.nameBox.setMaxLength(64);
        this.addRenderableWidget(this.nameBox);

        this.widthBox = new EditBox(this.font, fieldLeft, widthBoxY, fieldWidth, 20, Component.literal("道路宽度"));
        this.widthBox.setValue("7");
        this.addRenderableWidget(this.widthBox);

        this.addRenderableWidget(Button.builder(Component.literal("保存"), button -> {
            String roadName = this.nameBox.getValue().trim();
            if (roadName.isEmpty()) {
                roadName = "未命名道路";
            }
            double roadWidth = parseWidth(this.widthBox.getValue());
            this.onSave.accept(roadName, roadWidth);
            this.minecraft.setScreenAndShow(null);
        }).bounds(centerX - 116, buttonY, 112, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("取消"), button -> {
            this.onCancel.run();
            this.minecraft.setScreenAndShow(null);
        }).bounds(centerX + 4, buttonY, 112, 20).build());

        this.setInitialFocus(this.nameBox);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int centerX = this.width / 2;
        int left = centerX - PANEL_WIDTH / 2;
        int top = this.height / 2 - PANEL_HEIGHT / 2;
        int fieldLeft = left + 20;

        // 半透明遮罩背景
        graphics.fill(0, 0, this.width, this.height, 0xCC000000);

        // 对话框面板
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xE01B1F28);
        graphics.fill(left, top, left + PANEL_WIDTH, top + 1, 0xFF4E5768);
        graphics.fill(left, top + PANEL_HEIGHT - 1, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFF1A1F27);

        // 标题和提示
        graphics.centeredText(this.font, this.title, centerX, top + 14, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.literal("记录完成后补充名称和宽度"), centerX, top + 28, 0xFFAAAAAA);

        // 字段标签
        graphics.text(this.font, Component.literal("道路名"), fieldLeft, top + 40, 0xFFAAAAAA, true);
        graphics.text(this.font, Component.literal("道路宽度"), fieldLeft, top + 82, 0xFFAAAAAA, true);
        graphics.text(this.font, Component.literal("宽度默认 7 格，留空或输错都会自动回退。"), fieldLeft, top + 132, 0xFF888888, true);

        // 渲染控件（EditBox 和 Button）
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.onCancel.run();
            this.minecraft.setScreenAndShow(null);
            return true;
        }
        return super.keyPressed(event);
    }

    private double parseWidth(String input) {
        try {
            double parsed = Double.parseDouble(input.trim());
            return parsed <= 0.0D ? 7.0D : parsed;
        } catch (NumberFormatException exception) {
            return 7.0D;
        }
    }
}
