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

public class RoadMetadataScreen extends Screen {
    private static final List<String> CLASSIFICATIONS = List.of("", "G国道", "G高速", "S省道", "S高架", "X乡道", "Y县道", "C村道");
    private static final int PANEL_WIDTH = 300;
    private static final int PANEL_HEIGHT = 224;

    private final Segment segment;
    private final Consumer<Road> onSave;
    private final Runnable onCancel;
    private final Runnable onDiscard;

    private Road selectedRoad;
    private EditBox nameBox;
    private EditBox numberBox;
    private int classificationIndex;
    private Button cycleButton;

    public RoadMetadataScreen(Segment segment, Consumer<Road> onSave, Runnable onCancel, Runnable onDiscard) {
        super(Component.translatable("wayfarer.road.gui.metadata.title_create"));
        this.segment = segment;
        this.onSave = onSave;
        this.onCancel = onCancel;
        this.onDiscard = onDiscard;
    }

    @Override
    protected void init() {
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

        // --- Select existing road button ---
        int buttonWidth = (int)(PANEL_WIDTH * 0.8);
        int buttonLeft = centerX - buttonWidth / 2;
        int selectButtonY = top + 24;

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
            }, () -> this.minecraft.setScreen(this), segment.getId()));
        }).bounds(buttonLeft, selectButtonY, buttonWidth, 20).build());

        // --- Create new road form ---
        int nameBoxY = top + 78;
        int classifRowY = top + 114;
        int buttonY = top + PANEL_HEIGHT - 24;

        int btnW = 88;
        int btnGap = 4;
        int totalW = btnW * 3 + btnGap * 2;
        int btnStartX = centerX - totalW / 2;

        this.addRenderableWidget(
            Button.builder(Component.literal(I18n.get("wayfarer.road.gui.metadata.button_save")), button -> {
                saveRoad();
            }).bounds(btnStartX, buttonY, btnW, 20).build());

        this.addRenderableWidget(
            Button.builder(Component.literal(I18n.get("wayfarer.road.gui.metadata.button_discard")), button -> {
                discardSegment();
            }).bounds(btnStartX + btnW + btnGap, buttonY, btnW, 20).build());

        this.addRenderableWidget(
            Button.builder(Component.literal(I18n.get("wayfarer.road.gui.metadata.button_close")), button -> {
                closeScreen();
            }).bounds(btnStartX + (btnW + btnGap) * 2, buttonY, btnW, 20).build());

        this.nameBox = new EditBox(this.font, fieldLeft, nameBoxY, fieldWidth, 20,
            Component.literal(I18n.get("wayfarer.road.gui.metadata.field_name")));
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
        this.numberBox = new EditBox(this.font, numberLeft, classifRowY, numberWidth, 20,
            Component.literal(I18n.get("wayfarer.road.gui.metadata.field_number")));
        this.numberBox.setMaxLength(16);
        this.numberBox.setValue("");
        this.addRenderableWidget(this.numberBox);

        this.setInitialFocus(this.nameBox);
    }

    private void saveRoad() {
        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        synchronized (db) {
            if (selectedRoad != null) {
                db.addSegmentToRoad(selectedRoad.getId(), segment.getId());
                if (!db.saveToDisk()) {
                    if (this.minecraft.player != null) {
                        this.minecraft.player.displayClientMessage(
                            Component.translatable("wayfarer.road.gui.metadata.save_failed_associate"), false);
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
                    roadName = I18n.get("wayfarer.road.gui.unnamed_road");
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
                        this.minecraft.player.displayClientMessage(
                            Component.translatable("wayfarer.road.gui.metadata.save_failed_create"), false);
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
        int fieldLeft = left + 20;
        int fieldWidth = PANEL_WIDTH - 40;

        // Panel background
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xE01B1F28);
        graphics.fill(left, top, left + PANEL_WIDTH, top + 1, 0xFF4E5768);
        graphics.fill(left, top + PANEL_HEIGHT - 1, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFF1A1F27);

        // Title
        graphics.drawCenteredString(this.font, this.title, centerX, top + 6, 16777215);

        // Separator line (just above the select button area)
        int sepY = top + 48;
        graphics.drawCenteredString(this.font, "——————————", centerX, sepY, 0xFF4E5768);

        // --- "Create new" section ---
        int createLabelY = top + 52;
        graphics.drawString(this.font, I18n.get("wayfarer.road.gui.metadata.create_new"), fieldLeft, createLabelY,
            11184810, false);

        // Name field label
        int nameLabelY = top + 68;
        graphics.drawString(this.font, I18n.get("wayfarer.road.gui.metadata.field_name"), fieldLeft, nameLabelY,
            11184810, false);

        // Classification / Number label
        int classifLabelY = top + 104;
        graphics.drawString(this.font, I18n.get("wayfarer.road.gui.metadata.classification_number"), fieldLeft,
            classifLabelY, 11184810, false);

        // Hint text (wrapped to fit panel width)
        String hintText = I18n.get("wayfarer.road.gui.metadata.hint");
        int hintY = top + 136;
        List<String> hintLines = new ArrayList<>();
        int start = 0;
        while (start < hintText.length()) {
            String remaining = hintText.substring(start);
            String line = this.font.plainSubstrByWidth(remaining, fieldWidth);
            if (line.isEmpty()) {
                line = remaining.substring(0, 1);
            }
            hintLines.add(line);
            start += line.length();
        }
        for (int i = 0; i < hintLines.size(); i++) {
            graphics.drawString(this.font, hintLines.get(i), fieldLeft, hintY + i * 12, 8947848, false);
        }

        // Segment info: dynamic placement below the hint text
        int infoY = hintY + hintLines.size() * 12 + 8;
        graphics.drawString(this.font, I18n.get("wayfarer.road.gui.metadata.segment_node_count",
            segment.getNodeIds() != null ? segment.getNodeIds().size() : 0), fieldLeft, infoY, 0xCCCCCC, false);

        if (selectedRoad != null) {
            graphics.drawString(this.font, I18n.get("wayfarer.road.gui.metadata.assigned_road", selectedRoad.getName()),
                fieldLeft, top + 40, 0x66AAFF, false);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void closeScreen() {
        this.onCancel.run();
        this.minecraft.setScreen(null);
    }

    private void discardSegment() {
        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        synchronized (db) {
            if (segment.getNodeIds() != null) {
                for (UUID nodeId : segment.getNodeIds()) {
                    // Only remove nodes exclusive to this segment (not shared with others)
                    if (db.getSegmentCountForNode(nodeId) <= 1) {
                        db.removeNode(nodeId);
                    }
                }
            }
            db.removeSegment(segment.getId());
            db.saveToDisk();
        }
        this.onDiscard.run();
        this.minecraft.setScreen(null);
    }

    private String classificationLabel() {
        String val = CLASSIFICATIONS.get(classificationIndex);
        return val.isEmpty() ? I18n.get("wayfarer.road.gui.metadata.classification_default") : val;
    }
}
