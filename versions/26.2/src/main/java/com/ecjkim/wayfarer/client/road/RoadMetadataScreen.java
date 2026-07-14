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
package com.ecjkim.wayfarer.client.road;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.ecjkim.wayfarer.client.WayfarerConfig;

import org.lwjgl.glfw.GLFW;

public class RoadMetadataScreen extends Screen {
    public enum Mode {
        CREATE, EDIT
    }

    private static final List<String> CLASSIFICATIONS = List.of("", "G国道", "G高速", "S省道", "S高架", "X乡道", "Y县道", "C村道");
    private static final int PANEL_WIDTH = 280;
    private static final int PANEL_HEIGHT = 230;

    public interface OnSaveCallback {
        void accept(String name, double width, String classification, String number);
    }

    private final Mode mode;
    private final OnSaveCallback onSave;
    private final Runnable onCancel;
    private EditBox nameBox;
    private EditBox widthBox;
    private EditBox numberBox;
    private int classificationIndex;
    private Button cycleButton;

    private final String prefillName;
    private final String prefillWidth;
    private final String prefillClassification;
    private final String prefillNumber;

    public RoadMetadataScreen(Mode mode, OnSaveCallback onSave, Runnable onCancel, String prefillName,
        String prefillWidth, String prefillClassification, String prefillNumber) {
        super(Component.literal(mode == Mode.EDIT ? "修改道路" : "保存道路"));
        this.mode = mode;
        this.onSave = onSave;
        this.onCancel = onCancel;
        this.prefillName = prefillName;
        this.prefillWidth = prefillWidth;
        this.prefillClassification = prefillClassification;
        this.prefillNumber = prefillNumber;
        int idx = prefillClassification != null ? CLASSIFICATIONS.indexOf(prefillClassification) : -1;
        this.classificationIndex = idx >= 0 ? idx : 0;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int left = centerX - PANEL_WIDTH / 2;
        boolean useClassify = WayfarerConfig.getInstance().useClassificationWidth;
        int panelHeight = useClassify ? PANEL_HEIGHT - 42 : PANEL_HEIGHT;
        int top = this.height / 2 - panelHeight / 2;
        int fieldLeft = left + 20;
        int fieldWidth = PANEL_WIDTH - 40;
        int nameBoxY = top + 52;
        int widthBoxY = top + 94;
        int classifRowY = useClassify ? top + 94 : top + 136;
        int buttonY = top + panelHeight - 30;

        this.nameBox = new EditBox(this.font, fieldLeft, nameBoxY, fieldWidth, 20, Component.literal("道路名"));
        this.nameBox.setMaxLength(64);
        this.nameBox.setValue(prefillName != null ? prefillName : "");
        this.addRenderableWidget(this.nameBox);

        if (!useClassify) {
            this.widthBox = new EditBox(this.font, fieldLeft, widthBoxY, fieldWidth, 20, Component.literal("道路宽度"));
            this.widthBox.setValue(prefillWidth != null ? prefillWidth : "7");
            this.addRenderableWidget(this.widthBox);
        }

        int halfGap = 8;
        int cycleButtonWidth = 110;
        this.cycleButton = Button.builder(Component.literal(classificationLabel()), btn -> {
            classificationIndex = (classificationIndex + 1) % CLASSIFICATIONS.size();
            btn.setMessage(Component.literal(classificationLabel()));
        }).bounds(fieldLeft, classifRowY, cycleButtonWidth, 20).build();
        this.addRenderableWidget(this.cycleButton);

        int numberLeft = fieldLeft + cycleButtonWidth + halfGap;
        int numberWidth = fieldWidth - cycleButtonWidth - halfGap;
        this.numberBox = new EditBox(this.font, numberLeft, classifRowY, numberWidth, 20, Component.literal("编号"));
        this.numberBox.setMaxLength(16);
        this.numberBox.setValue(prefillNumber != null ? prefillNumber : "");
        this.addRenderableWidget(this.numberBox);

        String cancelLabel = mode == Mode.EDIT ? "放弃修改" : "放弃";

        this.addRenderableWidget(Button.builder(Component.literal("保存"), button -> {
            String roadName = this.nameBox.getValue().trim();
            String classification = CLASSIFICATIONS.get(classificationIndex);
            String number = this.numberBox.getValue().trim();

            if (roadName.isEmpty() && !classification.isEmpty() && !number.isEmpty()) {
                roadName = classification.substring(0, 1) + number;
            }
            if (roadName.isEmpty()) {
                roadName = "未命名道路";
            }

            double roadWidth;
            if (useClassify && !classification.isEmpty()) {
                roadWidth = WayfarerConfig.getInstance().getWidthForClassification(classification);
            } else {
                roadWidth = widthBox != null ? parseWidth(widthBox.getValue()) : 7.0D;
            }
            this.onSave.accept(roadName, roadWidth, classification, number);
            this.minecraft.setScreenAndShow(null);
        }).bounds(centerX - 116, buttonY, 112, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal(cancelLabel), button -> {
            this.onCancel.run();
            this.minecraft.setScreenAndShow(null);
        }).bounds(centerX + 4, buttonY, 112, 20).build());

        this.setInitialFocus(this.nameBox);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int centerX = this.width / 2;
        int left = centerX - PANEL_WIDTH / 2;
        boolean useClassify = WayfarerConfig.getInstance().useClassificationWidth;
        int panelHeight = useClassify ? PANEL_HEIGHT - 42 : PANEL_HEIGHT;
        int top = this.height / 2 - panelHeight / 2;
        int fieldLeft = left + 20;

        graphics.fill(0, 0, this.width, this.height, 0xCC000000);
        graphics.fill(left, top, left + PANEL_WIDTH, top + panelHeight, 0xE01B1F28);
        graphics.fill(left, top, left + PANEL_WIDTH, top + 1, 0xFF4E5768);
        graphics.fill(left, top + panelHeight - 1, left + PANEL_WIDTH, top + panelHeight, 0xFF1A1F27);

        String subtitle = useClassify ? "记录完成后补充名称和分级" : "记录完成后补充名称和宽度";
        graphics.centeredText(this.font, this.title, centerX, top + 8, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.literal(subtitle), centerX, top + 22, 0xFFAAAAAA);

        graphics.text(this.font, Component.literal("道路名"), fieldLeft, top + 40, 0xFFAAAAAA, true);
        if (!useClassify) {
            graphics.text(this.font, Component.literal("道路宽度"), fieldLeft, top + 82, 0xFFAAAAAA, true);
            graphics.text(this.font, Component.literal("道路分级 / 编号"), fieldLeft, top + 124, 0xFFAAAAAA, true);
            graphics.text(this.font, Component.literal("分级与编号非必填；若名称留空则默认用「分级+编号」组合。"), fieldLeft, top + 170,
                0xFF888888, true);
        } else {
            graphics.text(this.font, Component.literal("道路分级 / 编号"), fieldLeft, top + 82, 0xFFAAAAAA, true);
            graphics.text(this.font, Component.literal("分级与编号非必填；若名称留空则默认用「分级+编号」组合。"), fieldLeft, top + 128,
                0xFF888888, true);
        }

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

    private String classificationLabel() {
        String val = CLASSIFICATIONS.get(classificationIndex);
        return val.isEmpty() ? "道路分级" : val;
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
