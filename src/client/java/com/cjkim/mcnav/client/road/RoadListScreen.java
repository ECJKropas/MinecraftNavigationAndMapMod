package com.cjkim.mcnav.client.road;

import com.cjkim.mcnav.client.road.model.RoadPath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class RoadListScreen extends Screen {
    private static final int MARGIN = 16;
    private static final int HEADER_TOP = 14;
    private static final int PANEL_TOP = 56;
    private static final int PANEL_BOTTOM = 40;
    private static final int LEFT_PANEL_WIDTH = 304;
    private static final int LIST_ROW_HEIGHT = 24; // 调整行高使间距更紧凑美观

    private final RoadDataStore roadDataStore;
    private final RoadPreviewServer previewServer;
    private RoadEntryList roadList;
    private RoadPath selectedRoad;
    private Component statusText = Component.literal("");
    private EditBox searchBox;
    private String searchText = "";

    public RoadListScreen(RoadDataStore roadDataStore, RoadPreviewServer previewServer) {
        super(Component.literal("路线列表"));
        this.roadDataStore = roadDataStore;
        this.previewServer = previewServer;
    }

    @Override
    protected void init() {
        int panelBottomY = this.height - PANEL_BOTTOM;
        // 关键点1：精确计算List的顶部位置，防止文字和搜索框被列表项遮挡
        int listTop = PANEL_TOP + 42;
        int listHeight = panelBottomY - 6 - listTop;

        // 关键点2：将列表宽度完全贴合左侧面板内部
        this.roadList = new RoadEntryList(this.minecraft, LEFT_PANEL_WIDTH - 12, listHeight, listTop, panelBottomY - 6, LIST_ROW_HEIGHT);
        this.roadList.setLeftPos(MARGIN + 6); // 居中居左对齐
        this.roadList.setRenderBackground(false);
        this.roadList.setRenderTopAndBottom(false);
        this.addRenderableWidget(this.roadList);

        this.searchBox = new EditBox(this.font, MARGIN + 12, PANEL_TOP + 18, LEFT_PANEL_WIDTH - 24, 20, Component.literal("搜索路线..."));
        this.searchBox.setMaxLength(64);
        this.searchBox.setResponder(this::onSearchTextChanged);
        this.addRenderableWidget(this.searchBox);

        reloadEntries();
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        // no-op
    }

    @Override
    public void renderDirtBackground(GuiGraphics graphics) {
        // no-op
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
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

        // 3. 核心修复：先调用超级渲染来渲染列表（带有裁剪区），确保文字和页眉最后绘制，从而解决覆盖重叠问题
        super.render(graphics, mouseX, mouseY, partialTick);

        // 4. 绘制所有文本（位于最上层，绝不会被滚动的列表条目遮挡）
        graphics.drawString(this.font, this.title, MARGIN, HEADER_TOP, 16777215, false);
        graphics.drawString(this.font, Component.literal("当前实例: " + roadDataStore.getContextLabel()), MARGIN, HEADER_TOP + 12, 11184810, false);
        graphics.drawString(this.font, statusText, MARGIN, HEADER_TOP + 24, 8947848, false);

        // 面板头部小标题（调高了位置，使其不会和列表内容冲突）
        graphics.drawString(this.font, Component.literal("路线列表"), leftPanelX + 12, PANEL_TOP + 6, 16777215, false);
        graphics.drawString(this.font, Component.literal("详情"), rightPanelX + 12, PANEL_TOP + 6, 16777215, false);

        List<RoadPath> roads = roadDataStore.getRoads();
        graphics.drawString(this.font, Component.literal("共 " + roads.size() + " 条路线"), leftPanelX + 120, PANEL_TOP + 6, 11184810, false);

        graphics.drawString(this.font, Component.literal("数据文件: " + roadDataStore.getDataFile()), MARGIN, panelBottomY + 4, 8947848, false);
        graphics.drawString(this.font, Component.literal("本地预览: " + previewServer.getUrl()), MARGIN, panelBottomY + 16, 8947848, false);

        if (selectedRoad == null) {
            graphics.drawString(this.font, Component.literal("暂无选中路线"), rightPanelX + 12, PANEL_TOP + 24, 11184810, false);
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
            graphics.drawString(this.font, line, textX, textY + i * 13, 11184810, false);
        }
    }

    private void drawPanel(GuiGraphics graphics, int left, int top, int right, int bottom, int fillColor) {
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
            roads = roads.stream()
                    .filter(road -> road.name != null && road.name.toLowerCase().contains(searchText))
                    .collect(Collectors.toList());
        }

        String selectedId = selectedRoad == null ? null : selectedRoad.id;
        if (roadList != null) {
            roadList.reload(roads);
        }

        selectedRoad = selectedId == null ? (roads.isEmpty() ? null : roads.get(0)) : roads.stream()
                .filter(road -> selectedId.equals(road.id))
                .findFirst()
                .orElse(roads.isEmpty() ? null : roads.get(0));
        if (roadList != null) {
            roadList.setSelected(selectedRoad == null ? null : roadList.findEntry(selectedRoad));
        }
        setStatus(roads.isEmpty() ? "没有已保存的路线" : "共 " + roads.size() + " 条路线");
    }

    @Override
    public void tick() {
        super.tick();
        if (searchBox != null) {
            searchBox.tick();
        }
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (searchBox != null && searchBox.isFocused()) {
            return searchBox.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchBox != null && searchBox.isFocused()) {
            return searchBox.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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

    // --- 内部列表组件类优化 ---
    private final class RoadEntryList extends ObjectSelectionList<RoadEntry> {
        RoadEntryList(Minecraft minecraft, int width, int height, int top, int bottom, int itemHeight) {
            super(minecraft, width, height, top, bottom, itemHeight);
        }

        @Override
        public int getRowWidth() {
            return this.width - 6;
        }

        @Override
        protected int getScrollbarPosition() {
            return this.x0 + this.width - 6;
        }

        @Override
        protected void renderSelection(GuiGraphics graphics, int top, int width, int height, int outerColor, int innerColor) {
            // no-op: handled in RoadEntry.render()
        }

        void reload(List<RoadPath> roads) {
            super.clearEntries();
            for (RoadPath road : roads) {
                super.addEntry(new RoadEntry(road));
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
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float partialTick) {
            boolean selected = selectedRoad != null && selectedRoad.id != null && selectedRoad.id.equals(road.id);
            int background;
            if (selected) {
                background = 0xFF3A4560;
            } else if (hovered) {
                background = 0xFF2A3345;
            } else {
                background = index % 2 == 0 ? 0xFF1E2633 : 0xFF161C26;
            }

            // 关键点6：使用传入的真正 left 和 width 进行渲染，保证高亮选区完美贴合
            graphics.fill(left, top, left + width, top + height - 1, background);

            int textColor = selected ? 0xFFF7F9FC : hovered ? 0xFFF4F7FF : 0xFFC8CDD6;
            int textY = top + (height - RoadListScreen.this.font.lineHeight) / 2;
            graphics.drawString(RoadListScreen.this.font, safe(road.name), left + 6, textY, textColor, false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            selectedRoad = road;
            if (RoadListScreen.this.roadList != null) {
                RoadListScreen.this.roadList.setSelected(this);
            }
            return true;
        }
    }
}
