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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import com.ecjkim.wayfarer.client.WayfarerConfig;
import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Road;
import com.ecjkim.wayfarer.client.road.model.Segment;

import org.lwjgl.glfw.GLFW;

public class RoadMetadataScreen extends Screen {
    private static final List<String> CLASSIFICATIONS = List.of("", "G国道", "G高速", "S省道", "S高架", "X乡道", "Y县道", "C村道");
    private static final int PANEL_WIDTH = 300;
    private static final int PANEL_HEIGHT = 280;

    private final Segment segment;
    private final Consumer<Road> onSave;
    private final Runnable onCancel;

    private Road selectedRoad;
    private EditBox nameBox;
    private EditBox numberBox;
    private int classificationIndex;
    private Button cycleButton;

    public RoadMetadataScreen(Segment segment, Consumer<Road> onSave, Runnable onCancel) {
        super(Component.literal("保存录制的道路"));
        this.segment = segment;
        this.onSave = onSave;
        this.onCancel = onCancel;
    }

    @Override
    protected void init() {
        // Initialize classification from config default
        String defaultCls = WayfarerConfig.getInstance().getDefaultClassification();
        if (defaultCls != null && !defaultCls.isEmpty()) {
            int idx = CLASSIFICATIONS.indexOf(defaultCls);
            if (idx >= 0) {
                classificationIndex = idx;
            }
        }

        int centerX = this.width / 2;
        int left = centerX - PANEL_WIDTH / 2;
        int top = this.height / 2 - PANEL_HEIGHT / 2;
        int fieldLeft = left + 20;
        int fieldWidth = PANEL_WIDTH - 40;

        // --- Select existing road button (upper half) ---
        int buttonWidth = (int)(PANEL_WIDTH * 0.8);
        int buttonLeft = centerX - buttonWidth / 2;
        int selectButtonY = top + 38;
        String selectLabel = selectedRoad != null
            ? (selectedRoad.getName()
                + (selectedRoad.getClassification() != null && !selectedRoad.getClassification().isEmpty()
                    ? " (" + selectedRoad.getClassification() + ")" : ""))
            : I18n.get("wayfarer.road.gui.metadata.select_road");
        this.addRenderableWidget(Button.builder(Component.literal(selectLabel), btn -> {
            this.minecraft.setScreen(new RoadListScreen(road -> {
                this.selectedRoad = road;
                this.minecraft.setScreen(this);
                this.rebuildWidgets();
            }, () -> this.minecraft.setScreen(this)));
        }).bounds(buttonLeft, selectButtonY, buttonWidth, 20).build());

        // --- Create new road form (lower half) ---
        int separatorY = selectButtonY + 30;
        int nameBoxY = separatorY + 52;
        int classifLabelY = nameBoxY + 28;
        int classifRowY = classifLabelY + 12;
        int buttonY = top + PANEL_HEIGHT - 30;

        this.nameBox = new EditBox(this.font, fieldLeft, nameBoxY, fieldWidth, 20, Component.literal("道路名"));
        this.nameBox.setMaxLength(64);
        this.nameBox.setValue("");
        this.addRenderableWidget(this.nameBox);

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
        this.numberBox.setValue("");
        this.addRenderableWidget(this.numberBox);

        this.addRenderableWidget(
            Button.builder(Component.literal(I18n.get("wayfarer.road.gui.metadata.button_save")), button -> {
                saveRoad();
            }).bounds(centerX - 116, buttonY, 112, 20).build());

        this.addRenderableWidget(
            Button.builder(Component.literal(I18n.get("wayfarer.road.gui.metadata.button_cancel")), button -> {
                this.onCancel.run();
                this.minecraft.setScreen(null);
            }).bounds(centerX + 4, buttonY, 112, 20).build());

        this.setInitialFocus(this.nameBox);
    }

    private void saveRoad() {
        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        synchronized (db) {
            if (selectedRoad != null) {
                db.addSegmentToRoad(selectedRoad.getId(), segment.getId());
                if (!db.saveToDisk()) {
                    if (this.minecraft.player != null) {
                        this.minecraft.player.displayClientMessage(Component.literal("保存失败，道路未关联。"), false);
                    }
                    return;
                }
                onSave.accept(selectedRoad);
            } else {
                String roadName = this.nameBox.getValue().trim();
                String classification = CLASSIFICATIONS.get(classificationIndex);
                String number = this.numberBox.getValue().trim();

                if (roadName.isEmpty() && !classification.isEmpty() && !number.isEmpty()) {
                    roadName = classification.substring(0, 1) + number;
                }
                if (roadName.isEmpty()) {
                    roadName = "未命名道路";
                }

                Road road = new Road(UUID.randomUUID(), roadName, "#FFFFFF", classification, number,
                    new ArrayList<>(List.of(segment.getId())), 1);
                segment.setRoadId(road.getId());
                db.addRoad(road);
                db.updateSegment(segment.getId(), segment);
                if (!db.saveToDisk()) {
                    // Roll back: undo the in-memory references so the user can retry.
                    db.removeRoad(road.getId());
                    segment.setRoadId(null);
                    if (this.minecraft.player != null) {
                        this.minecraft.player.displayClientMessage(Component.literal("保存失败，道路未创建。"), false);
                    }
                    return;
                }
                onSave.accept(road);
            }
        }

        this.minecraft.setScreen(null);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        int centerX = this.width / 2;
        int left = centerX - PANEL_WIDTH / 2;
        int top = this.height / 2 - PANEL_HEIGHT / 2;

        // Panel background
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xE01B1F28);
        graphics.fill(left, top, left + PANEL_WIDTH, top + 1, 0xFF4E5768);
        graphics.fill(left, top + PANEL_HEIGHT - 1, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFF1A1F27);

        graphics.drawCenteredString(this.font, this.title, centerX, top + 8, 16777215);
        int fieldLeft = left + 20;

        // Separator after select button
        int separatorY = top + 38 + 30;
        graphics.drawCenteredString(this.font, Component.literal("——————————"), centerX, separatorY, 0xFF4E5768);

        // Section label
        graphics.drawString(this.font, Component.literal(I18n.get("wayfarer.road.gui.metadata.create_new")), fieldLeft,
            separatorY + 20, 11184810, false);

        // Name field
        graphics.drawString(this.font, Component.literal(I18n.get("wayfarer.road.gui.metadata.field_name")), fieldLeft,
            separatorY + 40, 11184810, false);

        // Classification / Number
        int classifLabelY = separatorY + 80;
        graphics.drawString(this.font, Component.literal(I18n.get("wayfarer.road.gui.metadata.classification_number")),
            fieldLeft, classifLabelY, 11184810, false);

        graphics.drawString(this.font, Component.literal(I18n.get("wayfarer.road.gui.metadata.hint")), fieldLeft,
            classifLabelY + 46, 8947848, false);

        // Segment info
        int bottomInfoY = top + PANEL_HEIGHT - 44;
        graphics.drawString(this.font,
            Component.literal("线段节点数: " + (segment.getNodeIds() != null ? segment.getNodeIds().size() : 0)), fieldLeft,
            bottomInfoY, 0xCCCCCC, false);

        if (selectedRoad != null) {
            graphics.drawString(this.font, Component.literal("已关联道路: " + selectedRoad.getName()), fieldLeft,
                top + 38 + 8, 0x66AAFF, false);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onCancel.run();
            this.minecraft.setScreen(null);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void cleanupOrphanData() {
        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        synchronized (db) {
            if (segment.getNodeIds() != null) {
                for (UUID nodeId : segment.getNodeIds()) {
                    db.removeNode(nodeId);
                }
            }
            db.removeSegment(segment.getId());
            db.saveToDisk();
        }
    }

    private String classificationLabel() {
        String val = CLASSIFICATIONS.get(classificationIndex);
        return val.isEmpty() ? I18n.get("wayfarer.road.gui.metadata.classification_default") : val;
    }
}
