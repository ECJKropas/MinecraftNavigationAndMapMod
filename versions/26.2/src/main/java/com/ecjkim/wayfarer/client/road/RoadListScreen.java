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
import java.util.function.Consumer;
import java.util.stream.Collectors;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

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
    private final Consumer<RoadPath> onContinueRecording;
    private RoadEntryList roadList;
    private RoadPath selectedRoad;
    private Component statusText = Component.literal("");
    private EditBox searchBox;
    private String searchText = "";
    private String renamingRoadId = null;
    private EditBox renameBox;
    private int detailButtonStartX, detailButtonStartY, detailButtonStartW;
    private int detailButtonEndX, detailButtonEndY, detailButtonEndW;
    private boolean detailButtonStartHovered, detailButtonEndHovered;

    public RoadListScreen(RoadDataStore roadDataStore, RoadPreviewServer previewServer,
        Consumer<RoadPath> onContinueRecording) {
        super(Component.literal("路线列表"));
        this.roadDataStore = roadDataStore;
        this.previewServer = previewServer;
        this.onContinueRecording = onContinueRecording;
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

        // 重命名模式下更新 EditBox 位置
        if (renameBox != null && renamingRoadId != null && roadList != null) {
            RoadEntry entry = roadList.findEntryById(renamingRoadId);
            if (entry != null) {
                int entryY = entry.getY();
                int listLeft = roadList.getX();
                int listWidth = roadList.getWidth();
                int iconWidth = 56;
                renameBox.setX(listLeft);
                renameBox.setWidth(listWidth - iconWidth - 2);
                renameBox.setY(entryY + (LIST_ROW_HEIGHT - 20) / 2);
            }
        }

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

        detailButtonStartX = detailButtonStartY = detailButtonStartW = 0;
        detailButtonEndX = detailButtonEndY = detailButtonEndW = 0;
        if (!selectedRoad.points.isEmpty()) {
            var first = selectedRoad.points.get(0);
            var last = selectedRoad.points.get(selectedRoad.points.size() - 1);
            int lineCount = lines.size();
            int btnY = textY + (lineCount + 1) * 13;

            String startLabel = "◎ " + directionTo((float)(first.x - last.x), (float)(first.z - last.z));
            String endLabel = "◎ " + directionTo((float)(last.x - first.x), (float)(last.z - first.z));

            detailButtonStartX = textX;
            detailButtonStartY = btnY;
            detailButtonStartW = this.font.width(startLabel);
            detailButtonEndX = textX;
            detailButtonEndY = btnY + 13;
            detailButtonEndW = this.font.width(endLabel);

            detailButtonStartHovered = mouseX >= detailButtonStartX && mouseX <= detailButtonStartX + detailButtonStartW
                && mouseY >= detailButtonStartY && mouseY <= detailButtonStartY + this.font.lineHeight;
            detailButtonEndHovered = mouseX >= detailButtonEndX && mouseX <= detailButtonEndX + detailButtonEndW
                && mouseY >= detailButtonEndY && mouseY <= detailButtonEndY + this.font.lineHeight;

            int startColor = detailButtonStartHovered ? 0xFF55FFFF : 0xFF6699CC;
            int endColor = detailButtonEndHovered ? 0xFF55FFFF : 0xFFCC9966;

            graphics.text(this.font, Component.literal(startLabel), textX, btnY, startColor, true);
            graphics.text(this.font, Component.literal(endLabel), textX, btnY + 13, endColor, true);
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
        String cn = (road.classification != null ? road.classification : "")
            + (road.number != null ? road.number : "");
        lines.add(Component.literal("分级/编号: " + (cn.isEmpty() ? "无" : cn)));
        lines.add(Component.literal("宽度: " + road.width + " 格"));
        lines.add(Component.literal("轨迹点: " + road.points.size()));
        lines.add(Component.literal("交叉点: " + road.intersections.size()));
        for (var inter : road.intersections) {
            lines.add(Component.literal("  └ " + safe(inter.roadName)));
        }
        lines.add(Component.literal("ID: " + safe(road.id)));
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
        if (renameBox != null && renameBox.isFocused()) {
            return renameBox.charTyped(event);
        }
        if (searchBox != null && searchBox.isFocused()) {
            return searchBox.charTyped(event);
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (renameBox != null && renameBox.isFocused()) {
            if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                commitRename();
                return true;
            }
            if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                cancelRename();
                return true;
            }
            return renameBox.keyPressed(event);
        }
        if (searchBox != null && searchBox.isFocused()) {
            return searchBox.keyPressed(event);
        }
        return super.keyPressed(event);
    }

    private void beginRename(RoadEntry entry) {
        if (renameBox != null) {
            this.removeWidget(renameBox);
        }
        this.renamingRoadId = entry.road.id;
        this.renameBox = new EditBox(this.font, 0, 0, 200, 20, Component.empty());
        this.renameBox.setValue(entry.road.name != null ? entry.road.name : "");
        this.renameBox.setMaxLength(64);
        this.renameBox.setFocused(true);
        this.addRenderableWidget(this.renameBox);
    }

    private void commitRename() {
        if (renamingRoadId == null || renameBox == null)
            return;
        String newName = renameBox.getValue().trim();
        if (newName.isEmpty())
            newName = "未命名道路";
        RoadPath ref = selectedRoad != null && selectedRoad.id.equals(renamingRoadId) ? selectedRoad : null;
        roadDataStore.updateRoad(renamingRoadId, newName,
            ref != null ? ref.width : 7.0D,
            ref != null ? ref.classification : "",
            ref != null ? ref.number : "");
        setStatus("已重命名: " + newName);
        cancelRename();
        reloadEntries();
    }

    private void cancelRename() {
        if (renameBox != null) {
            this.removeWidget(renameBox);
            renameBox = null;
        }
        renamingRoadId = null;
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

    private void openEditScreen(RoadPath road) {
        RoadMetadataScreen editScreen = new RoadMetadataScreen(RoadMetadataScreen.Mode.EDIT,
            (name, width, classification, number) -> {
                roadDataStore.updateRoad(road.id, name, width, classification, number);
                reloadEntries();
                setStatus("已修改: " + name);
            }, () -> {
            }, road.name, String.valueOf(road.width), road.classification, road.number);
        this.minecraft.setScreenAndShow(editScreen);
    }

    private void deleteSelectedRoad(RoadPath road) {
        String roadName = road.name;
        roadDataStore.deleteRoad(road.id);
        setStatus("已删除: " + roadName);
        reloadEntries();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isForwards) {
        if (event.button() != 0 || selectedRoad == null || selectedRoad.points.isEmpty()) {
            return super.mouseClicked(event, isForwards);
        }
        if (event.x() >= detailButtonStartX && event.x() <= detailButtonStartX + detailButtonStartW
            && event.y() >= detailButtonStartY && event.y() <= detailButtonStartY + this.font.lineHeight) {
            var first = selectedRoad.points.get(0);
            var last = selectedRoad.points.get(selectedRoad.points.size() - 1);
            clickEndpoint(selectedRoad.name, directionTo((float)(first.x - last.x), (float)(first.z - last.z)),
                (int)Math.floor(first.x), (int)Math.floor(first.z));
            return true;
        }
        if (event.x() >= detailButtonEndX && event.x() <= detailButtonEndX + detailButtonEndW
            && event.y() >= detailButtonEndY && event.y() <= detailButtonEndY + this.font.lineHeight) {
            var last = selectedRoad.points.get(selectedRoad.points.size() - 1);
            var first = selectedRoad.points.get(0);
            clickEndpoint(selectedRoad.name, directionTo((float)(last.x - first.x), (float)(last.z - first.z)),
                (int)Math.floor(last.x), (int)Math.floor(last.z));
            return true;
        }
        return super.mouseClicked(event, isForwards);
    }

    private static String directionTo(float dx, float dz) {
        double angle = Math.toDegrees(Math.atan2(dz, dx));
        if (angle < 0)
            angle += 360;
        if (angle <= 15 || angle >= 345)
            return "东端";
        if (angle >= 75 && angle <= 105)
            return "南端";
        if (angle >= 165 && angle <= 195)
            return "西端";
        if (angle >= 255 && angle <= 285)
            return "北端";
        if (angle > 15 && angle < 75)
            return "东南端";
        if (angle > 105 && angle < 165)
            return "西南端";
        if (angle > 195 && angle < 255)
            return "西北端";
        if (angle > 285 && angle < 345)
            return "东北端";
        return "端点";
    }

    private void clickEndpoint(String roadName, String direction, int x, int z) {
        if (minecraft.player == null)
            return;
        String coordStr = "[" + x + ", ~, " + z + "]";
        Component coord = Component.literal(coordStr)
            .withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent.SuggestCommand("/tp " + x + " ~ " + z))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击填入传送指令"))));
        Component msg = Component.literal(safe(roadName) + "的" + direction + "在").append(coord);
        minecraft.player.sendSystemMessage(msg);
        this.minecraft.setScreenAndShow(null);
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

        RoadEntry findEntryById(String roadId) {
            for (RoadEntry entry : this.children()) {
                if (entry.road.id != null && entry.road.id.equals(roadId)) {
                    return entry;
                }
            }
            return null;
        }
    }

    private final class RoadEntry extends ObjectSelectionList.Entry<RoadEntry> {
        private final RoadPath road;
        private static final int ICON_GAP = 18;
        private static final int ICON_RIGHT_MARGIN = 8;

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
            boolean isRenaming = road.id != null && road.id.equals(renamingRoadId);
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

            if (isRenaming)
                return;

            int iconRight = left + width - ICON_RIGHT_MARGIN;
            int editIconX = iconRight - ICON_GAP * 3 + 2;
            int continueIconX = iconRight - ICON_GAP * 2 + 2;
            int deleteIconX = iconRight - ICON_GAP + 2;
            int iconY = entryY + (height - RoadListScreen.this.font.lineHeight) / 2;

            int textColor = selected ? 0xFFF7F9FC : hovered ? 0xFFF4F7FF : 0xFFC8CDD6;
            int maxNameWidth = editIconX - left - 10;
            String displayName = RoadListScreen.this.font.plainSubstrByWidth(safe(road.name), maxNameWidth);
            graphics.text(RoadListScreen.this.font, displayName, left + 6, iconY, textColor, true);

            boolean hoverEdit =
                mouseX >= editIconX - 1 && mouseX <= editIconX + 13 && mouseY >= iconY - 1 && mouseY <= iconY + 11;
            boolean hoverContinue = mouseX >= continueIconX - 1 && mouseX <= continueIconX + 13 && mouseY >= iconY - 1
                && mouseY <= iconY + 11;
            boolean hoverDelete =
                mouseX >= deleteIconX - 1 && mouseX <= deleteIconX + 13 && mouseY >= iconY - 1 && mouseY <= iconY + 11;

            graphics.text(RoadListScreen.this.font, Component.literal("\u270E"), editIconX, iconY,
                hoverEdit ? 0xFF66BBFF : 0xFF888888, true);
            graphics.text(RoadListScreen.this.font, Component.literal("\u25B6"), continueIconX, iconY,
                hoverContinue ? 0xFF66FF66 : 0xFF888888, true);
            graphics.text(RoadListScreen.this.font, Component.literal("\u2715"), deleteIconX, iconY,
                hoverDelete ? 0xFFFF6666 : 0xFF888888, true);
        }

        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean isForwards) {
            int iconRight = getContentX() + getContentWidth() - ICON_RIGHT_MARGIN;
            int editIconX = iconRight - ICON_GAP * 3 + 2;
            int continueIconX = iconRight - ICON_GAP * 2 + 2;
            int deleteIconX = iconRight - ICON_GAP + 2;

            double mx = event.x();
            double my = event.y();
            int button = event.button();

            boolean clickEdit = mx >= editIconX - 1 && mx <= editIconX + 13;
            boolean clickContinue = mx >= continueIconX - 1 && mx <= continueIconX + 13;
            boolean clickDelete = mx >= deleteIconX - 1 && mx <= deleteIconX + 13;

            if (button == 0 && clickEdit) {
                openEditScreen(road);
                return true;
            }
            if (button == 0 && clickContinue) {
                if (onContinueRecording != null) {
                    onContinueRecording.accept(road);
                }
                onClose();
                return true;
            }
            if (button == 0 && clickDelete) {
                deleteSelectedRoad(road);
                return true;
            }
            if (button == 1) {
                beginRename(this);
                return true;
            }

            selectedRoad = road;
            if (RoadListScreen.this.roadList != null) {
                RoadListScreen.this.roadList.setSelected(this);
            }
            return true;
        }
    }
}
