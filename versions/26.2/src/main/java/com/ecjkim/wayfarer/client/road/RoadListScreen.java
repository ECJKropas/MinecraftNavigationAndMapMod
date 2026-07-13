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

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.ecjkim.wayfarer.client.road.model.RoadPath;

public class RoadListScreen extends Screen {
    private static final int MARGIN = 16;
    private static final int HEADER_TOP = 14;
    private static final int PANEL_TOP = 56;
    private static final int PANEL_BOTTOM = 40;
    private static final int LEFT_PANEL_WIDTH = 304;
    private static final int LIST_ROW_HEIGHT = 24;

    private final RoadDataStore roadDataStore;
    private final RoadPreviewServer previewServer;
    private RoadEntryList roadList;
    private RoadPath selectedRoad;
    private Component statusText = Component.literal("");
    private EditBox searchBox;
    private String searchText = "";
    private net.minecraft.client.gui.components.Button editButton;
    private net.minecraft.client.gui.components.Button deleteButton;

    public RoadListScreen(RoadDataStore roadDataStore, RoadPreviewServer previewServer) {
        super(Component.literal("路线列表"));
        this.roadDataStore = roadDataStore;
        this.previewServer = previewServer;
    }

    @Override
    protected void init() {
        int panelBottomY = this.height - PANEL_BOTTOM;
        int listTop = PANEL_TOP + 42;
        int listHeight = panelBottomY - 6 - listTop;

        this.roadList = new RoadEntryList(this.minecraft, LEFT_PANEL_WIDTH - 12, listHeight, listTop, panelBottomY - 6);
        this.roadList.setX(MARGIN + 6);
        this.addRenderableWidget(this.roadList);

        this.searchBox = new EditBox(this.font, MARGIN + 12, PANEL_TOP + 18, LEFT_PANEL_WIDTH - 24, 20,
            Component.literal("搜索路线..."));
        this.searchBox.setMaxLength(64);
        this.searchBox.setResponder(this::onSearchTextChanged);
        this.addRenderableWidget(this.searchBox);

        int rightPanelX = MARGIN + LEFT_PANEL_WIDTH + 12;
        int buttonY = panelBottomY - 30;
        int btnWidth = 84;
        this.editButton = net.minecraft.client.gui.components.Button.builder(Component.literal("修改"), button -> {
            if (selectedRoad != null) {
                RoadMetadataScreen editScreen = new RoadMetadataScreen(
                    RoadMetadataScreen.Mode.EDIT,
                    (name, width) -> {
                        roadDataStore.updateRoad(selectedRoad.id, name, width);
                        reloadEntries();
                        setStatus("已修改: " + name);
                    },
                    () -> {},
                    selectedRoad.name,
                    String.valueOf(selectedRoad.width)
                );
                this.minecraft.setScreenAndShow(editScreen);
            }
        }).bounds(rightPanelX + 12, buttonY, btnWidth, 20).build();
        this.addRenderableWidget(this.editButton);

        this.deleteButton = net.minecraft.client.gui.components.Button.builder(Component.literal("删除"), button -> {
            if (selectedRoad != null) {
                String roadName = selectedRoad.name;
                roadDataStore.deleteRoad(selectedRoad.id);
                setStatus("已删除: " + roadName);
                reloadEntries();
            }
        }).bounds(rightPanelX + 12 + btnWidth + 6, buttonY, btnWidth, 20).build();
        this.addRenderableWidget(this.deleteButton);

        reloadEntries();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // 1. 绘制全屏半透明底色
        graphics.fill(0, 0, this.width, this.height, 0xCC000000);

        int panelBottomY = this.height - PANEL_BOTTOM;
        int leftPanelX = MARGIN;
        int leftPanelRight = leftPanelX + LEFT_PANEL_WIDTH;
        int rightPanelX = leftPanelRight + 12;
        int rightPanelRight = this.width - MARGIN;

        // 2. 绘制自定义的面板容器背景
        drawPanel(graphics, leftPanelX, PANEL_TOP, leftPanelRight, panelBottomY, 0xCC1B1F28);
        drawPanel(graphics, rightPanelX, PANEL_TOP, rightPanelRight, panelBottomY, 0xCC171B22);

        // 3. 渲染控件列表和搜索框
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        // 4. 绘制所有文本（位于最上层）
        graphics.text(this.font, this.title, MARGIN, HEADER_TOP, 0xFFFFFFFF, true);
        graphics.text(this.font, Component.literal("当前实例: " + roadDataStore.getContextLabel()), MARGIN, HEADER_TOP + 12,
            0xFFAAAAAA, true);
        graphics.text(this.font, statusText, MARGIN, HEADER_TOP + 24, 0xFF888888, true);

        graphics.text(this.font, Component.literal("路线列表"), leftPanelX + 12, PANEL_TOP + 6, 0xFFFFFFFF, true);
        graphics.text(this.font, Component.literal("详情"), rightPanelX + 12, PANEL_TOP + 6, 0xFFFFFFFF, true);

        List<RoadPath> roads = roadDataStore.getRoads();
        graphics.text(this.font, Component.literal("共 " + roads.size() + " 条路线"), leftPanelX + 120, PANEL_TOP + 6,
            0xFFAAAAAA, true);

        graphics.text(this.font, Component.literal("数据文件: " + roadDataStore.getDataFile()), MARGIN, panelBottomY + 4,
            0xFF888888, true);
        graphics.text(this.font, Component.literal("本地预览: " + previewServer.getUrl()), MARGIN, panelBottomY + 16,
            0xFF888888, true);

        boolean hasSelection = selectedRoad != null;
        if (editButton != null) editButton.active = hasSelection;
        if (deleteButton != null) deleteButton.active = hasSelection;

        if (selectedRoad == null) {
            graphics.text(this.font, Component.literal("暂无选中路线"), rightPanelX + 12, PANEL_TOP + 24, 0xFFAAAAAA, true);
            return;
        }

        List<Component> lines = buildDetailLines(selectedRoad);
        int textX = rightPanelX + 12;
        int textY = PANEL_TOP + 24;
        int maxWidth = rightPanelRight - textX - 12;
        for (int i = 0; i < lines.size(); i++) {
            Component line = lines.get(i);
            if (this.font.width(line) > maxWidth) {
                String truncated = this.font.plainSubstrByWidth(line.getString(), maxWidth - this.font.width("..."));
                line = Component.literal(truncated + "...");
            }
            graphics.text(this.font, line, textX, textY + i * 13, 0xFFAAAAAA, true);
        }
    }

    private void drawPanel(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int fillColor) {
        graphics.fill(left, top, right, bottom, fillColor);
        graphics.fill(left, top, right, top + 1, 0xFF4E5768);
        graphics.fill(left, bottom - 1, right, bottom, 0xFF1A1F27);
        graphics.fill(left, top, left + 1, bottom, 0xFF1A1F27);
        graphics.fill(right - 1, top, right, bottom, 0xFF1A1F27);
    }

    private List<Component> buildDetailLines(RoadPath road) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("名称: " + safe(road.name)));
        lines.add(Component.literal("宽度: " + road.width + " 格"));
        lines.add(Component.literal("轨迹点: " + road.points.size()));
        lines.add(Component.literal("交叉点: " + road.intersections.size()));
        for (var inter : road.intersections) {
            lines.add(Component.literal("  └ " + safe(inter.roadName)));
        }
        lines.add(Component.literal("ID: " + safe(road.id)));
        if (!road.points.isEmpty()) {
            var first = road.points.get(0);
            var last = road.points.get(road.points.size() - 1);
            lines.add(Component.literal(String.format("起点: %.1f, %.1f, %.1f", first.x, first.y, first.z)));
            lines.add(Component.literal(String.format("终点: %.1f, %.1f, %.1f", last.x, last.y, last.z)));
        }
        return lines;
    }

    private void reloadEntries() {
        roadDataStore.syncToCurrentContext();
        roadDataStore.reloadFromDisk();
        reloadFilteredEntries();
    }

    private void onSearchTextChanged(String text) {
        this.searchText = text.trim().toLowerCase();
        reloadFilteredEntries();
    }

    private void reloadFilteredEntries() {
        List<RoadPath> roads = roadDataStore.getRoads();
        if (!searchText.isEmpty()) {
            roads = roads.stream().filter(road -> road.name != null && road.name.toLowerCase().contains(searchText))
                .collect(Collectors.toList());
        }

        String selectedId = selectedRoad == null ? null : selectedRoad.id;
        if (roadList != null) {
            roadList.reload(roads);
        }

        selectedRoad = selectedId == null ? (roads.isEmpty() ? null : roads.get(0)) : roads.stream()
            .filter(road -> selectedId.equals(road.id)).findFirst().orElse(roads.isEmpty() ? null : roads.get(0));
        if (roadList != null) {
            roadList.setSelected(selectedRoad == null ? null : roadList.findEntry(selectedRoad));
        }
        setStatus(roads.isEmpty() ? "没有已保存的路线" : "共 " + roads.size() + " 条路线");
    }

    @Override
    public void tick() {
        super.tick();
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (searchBox != null && searchBox.isFocused()) {
            return searchBox.charTyped(event);
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (searchBox != null && searchBox.isFocused()) {
            return searchBox.keyPressed(event);
        }
        return super.keyPressed(event);
    }

    private void openPreviewInBrowser() {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(previewServer.getUrl()));
                setStatus("已在浏览器打开预览");
            } else {
                setStatus("当前系统不支持自动打开浏览器，请手动访问 " + previewServer.getUrl());
            }
        } catch (Exception exception) {
            setStatus("打开浏览器失败：请手动访问 " + previewServer.getUrl());
        }
    }

    private void setStatus(String message) {
        this.statusText = Component.literal(message);
    }

    private String safe(String input) {
        return input == null || input.isBlank() ? "未命名" : input;
    }

    // --- 内部列表组件类 ---
    private final class RoadEntryList extends ObjectSelectionList<RoadEntry> {
        RoadEntryList(Minecraft minecraft, int width, int height, int top, int bottom) {
            super(minecraft, width, height, top, bottom);
        }

        @Override
        protected int scrollBarX() {
            return this.getX() + this.width - 6;
        }

        @Override
        protected void extractListBackground(GuiGraphicsExtractor graphics) {
            // no-op: 我们在 extractRenderState 中已经绘制了面板背景
        }

        @Override
        protected void extractListSeparators(GuiGraphicsExtractor graphics) {
            // no-op
        }

        @Override
        protected void extractSelection(GuiGraphicsExtractor graphics, RoadEntry entry, int color) {
            // no-op: handled in RoadEntry.extractContent()
        }

        void reload(List<RoadPath> roads) {
            super.clearEntries();
            for (RoadPath road : roads) {
                super.addEntry(new RoadEntry(road), 24);
            }
        }

        RoadEntry findEntry(RoadPath road) {
            for (RoadEntry entry : this.children()) {
                if (entry.road.id != null && entry.road.id.equals(road.id)) {
                    return entry;
                }
            }
            return null;
        }
    }

    private final class RoadEntry extends ObjectSelectionList.Entry<RoadEntry> {
        private final RoadPath road;

        private RoadEntry(RoadPath road) {
            this.road = road;
        }

        @Override
        public Component getNarration() {
            return Component.literal(safe(road.name));
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered,
            float partialTick) {
            boolean selected = selectedRoad != null && selectedRoad.id != null && selectedRoad.id.equals(road.id);
            int background;
            if (selected) {
                background = 0xFF3A4560;
            } else if (hovered) {
                background = 0xFF2A3345;
            } else {
                background = (getY() / 24) % 2 == 0 ? 0xFF1E2633 : 0xFF161C26;
            }

            int entryY = getY();
            int left = getContentX();
            int width = getContentWidth();
            int height = getContentHeight();
            graphics.fill(left, entryY, left + width, entryY + height - 1, background);

            int textColor = selected ? 0xFFF7F9FC : hovered ? 0xFFF4F7FF : 0xFFC8CDD6;
            int textY = entryY + (height - RoadListScreen.this.font.lineHeight) / 2;
            graphics.text(RoadListScreen.this.font, safe(road.name), left + 6, textY, textColor, true);
        }

        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean isForwards) {
            selectedRoad = road;
            if (RoadListScreen.this.roadList != null) {
                RoadListScreen.this.roadList.setSelected(this);
            }
            return true;
        }
    }
}
