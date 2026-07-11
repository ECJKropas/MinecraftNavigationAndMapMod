package com.cjkim.mcnav.client.road;

import com.cjkim.mcnav.client.road.model.RoadPath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
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
    private static final int LIST_ROW_HEIGHT = 28;
    private static final int BUTTON_WIDTH = 78;

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
        int listTop = PANEL_TOP + 52;
        int listHeight = panelBottomY - 12 - listTop;
        this.roadList = new RoadEntryList(this.minecraft, LEFT_PANEL_WIDTH - 16, listHeight, listTop, panelBottomY - 12, LIST_ROW_HEIGHT);
        this.roadList.setLeftPos(MARGIN + 12);
        this.addRenderableWidget(this.roadList);

        this.searchBox = new EditBox(this.font, MARGIN + 12, PANEL_TOP + 28, LEFT_PANEL_WIDTH - 24, 20, Component.literal("搜索路线..."));
        this.searchBox.setMaxLength(64);
        this.searchBox.setResponder(this::onSearchTextChanged);
        this.addRenderableWidget(this.searchBox);

        reloadEntries();

        int buttonY = HEADER_TOP - 1;
        int closeX = this.width - MARGIN - BUTTON_WIDTH;
        int previewX = closeX - 8 - BUTTON_WIDTH;
        int refreshX = previewX - 8 - BUTTON_WIDTH;

        this.addRenderableWidget(Button.builder(Component.literal("刷新"), button -> {
            reloadEntries();
            setStatus("列表已刷新");
        }).bounds(refreshX, buttonY, BUTTON_WIDTH, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("打开预览"), button -> openPreviewInBrowser())
                .bounds(previewX, buttonY, BUTTON_WIDTH, 20)
                .build());

        this.addRenderableWidget(Button.builder(Component.literal("关闭"), button -> this.minecraft.setScreen(null))
                .bounds(closeX, buttonY, BUTTON_WIDTH, 20)
                .build());
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        graphics.fill(0, 0, this.width, this.height, 0xFF000000);
    }

    @Override
    public void renderDirtBackground(GuiGraphics graphics) {
        // overridden to suppress dirt texture
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xFF000000);

        int panelBottomY = this.height - PANEL_BOTTOM;
        int leftPanelX = MARGIN;
        int leftPanelRight = leftPanelX + LEFT_PANEL_WIDTH;
        int rightPanelX = leftPanelRight + 12;
        int rightPanelRight = this.width - MARGIN;

        graphics.drawString(this.font, this.title, MARGIN, HEADER_TOP, 16777215, false);
        graphics.drawString(this.font, Component.literal("当前实例: " + roadDataStore.getContextLabel()), MARGIN, HEADER_TOP + 12, 11184810, false);
        graphics.drawString(this.font, statusText, MARGIN, HEADER_TOP + 24, 8947848, false);

        drawPanel(graphics, leftPanelX, PANEL_TOP, leftPanelRight, panelBottomY, 0xCC1B1F28);
        drawPanel(graphics, rightPanelX, PANEL_TOP, rightPanelRight, panelBottomY, 0xCC171B22);

        graphics.drawString(this.font, Component.literal("路线列表"), leftPanelX + 12, PANEL_TOP + 10, 16777215, false);
        graphics.drawString(this.font, Component.literal("详情"), rightPanelX + 12, PANEL_TOP + 10, 16777215, false);

        List<RoadPath> roads = roadDataStore.getRoads();
        graphics.drawString(this.font, Component.literal("共 " + roads.size() + " 条路线"), leftPanelX + 12, PANEL_TOP + 22, 11184810, false);
        graphics.drawString(this.font, Component.literal("数据文件: " + roadDataStore.getDataFile()), MARGIN, panelBottomY + 4, 8947848, false);
        graphics.drawString(this.font, Component.literal("本地预览: " + previewServer.getUrl()), MARGIN, panelBottomY + 16, 8947848, false);

        super.render(graphics, mouseX, mouseY, partialTick);

        if (selectedRoad == null) {
            graphics.drawString(this.font, Component.literal("暂无选中路线"), rightPanelX + 12, PANEL_TOP + 34, 11184810, false);
            return;
        }

        List<Component> lines = buildDetailLines(selectedRoad);
        int textX = rightPanelX + 12;
        int textY = PANEL_TOP + 34;
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(this.font, lines.get(i), textX, textY + i * 13, 11184810, false);
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

    private final class RoadEntryList extends ObjectSelectionList<RoadEntry> {
        RoadEntryList(Minecraft minecraft, int width, int height, int top, int bottom, int itemHeight) {
            super(minecraft, width, height, top, bottom, itemHeight);
        }

        @Override
        public int getRowWidth() {
            return LEFT_PANEL_WIDTH - 28;
        }

        @Override
        public int getRowLeft() {
            return MARGIN + 4;
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
            int background = selected ? 0xFF2A3342 : hovered ? 0xFF232A37 : 0xFF1B1F28;
            graphics.fill(left, top, left + width, top + height - 1, background);
            graphics.fill(left, top, left + width, top + 1, selected ? 0xFF6A7A91 : 0xFF313847);

            int textColor = selected ? 0xFFF7F9FC : hovered ? 0xFFF4F7FF : 0xFFE2E6EE;
            int textY = top + (height - RoadListScreen.this.font.lineHeight) / 2;
            graphics.drawString(RoadListScreen.this.font, safe(road.name), left + 8, textY, textColor, false);
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
