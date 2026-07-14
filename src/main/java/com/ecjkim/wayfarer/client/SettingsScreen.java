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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

public class SettingsScreen extends Screen {
    private static final List<String> CLASSIFICATIONS = List.of("", "G国道", "G高速", "S省道", "S高架", "X乡道", "Y县道", "C村道");

    private static final int ROW_H = 24;
    private static final int GAP = 2;
    private static final int LABEL_W = 160;
    private static final int VALUE_W = 140;
    private static final int PAGE_W = LABEL_W + VALUE_W + 24;
    private static final int CONTENT_TOP = 58;

    private final Screen parent;
    private final WayfarerConfig config;

    // 默认值
    private EditBox defaultWidthBox;
    private int classificationIdx;
    private Button classifBtn;

    // 分级开关
    private boolean useClassificationWidth;

    // 分级宽度编辑框
    private final Map<String, EditBox> classifBoxes = new LinkedHashMap<>();
    private Button classifToggleBtn;

    // 热键按钮
    private Button recHotkeyBtn;
    private Button menuHotkeyBtn;

    // 捕获状态
    private boolean capturing;
    private String capturingAction;
    private Button capturingBtn;

    // 布局
    private int leftX;
    private int labelX;
    private int valueX;

    // 滚动 & 搜索
    private int scrollOffset;
    private int totalContentHeight;
    private EditBox searchBox;
    private String searchText = "";

    public SettingsScreen(Screen parent) {
        super(Component.literal("越陌度阡 · 设置"));
        this.parent = parent;
        this.config = WayfarerConfig.getInstance();
        this.useClassificationWidth = config.useClassificationWidth;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        this.leftX = Math.max(10, cx - PAGE_W / 2);
        this.labelX = leftX + 8;
        this.valueX = leftX + PAGE_W - VALUE_W - 8;

        int idx = CLASSIFICATIONS.indexOf(config.defaultClassification);
        this.classificationIdx = idx >= 0 ? idx : 0;

        // 重置状态
        classifBoxes.clear();
        recHotkeyBtn = null;
        menuHotkeyBtn = null;

        // === 搜索框 ===
        searchBox = new EditBox(this.font, cx - 60, 30, 120, 20, Component.literal("搜索设置项..."));
        searchBox.setMaxLength(50);
        searchBox.setValue(searchText);
        searchBox.setResponder(text -> {
            searchText = text;
            scrollOffset = 0;
            updateWidgetVisibility();
        });
        addRenderableWidget(searchBox);

        int y = CONTENT_TOP;

        // === 默认值 section ===
        defaultWidthBox = new EditBox(this.font, valueX, y + 24, VALUE_W, 20, Component.literal("默认道路宽度"));
        defaultWidthBox.setMaxLength(8);
        defaultWidthBox.setValue(String.valueOf(config.defaultWidth));
        addRenderableWidget(defaultWidthBox);

        // 默认分级按钮
        String clsText = CLASSIFICATIONS.get(classificationIdx);
        classifBtn = Button.builder(Component.literal(clsText.isEmpty() ? "无" : clsText), btn -> {
            classificationIdx = (classificationIdx + 1) % CLASSIFICATIONS.size();
            String s = CLASSIFICATIONS.get(classificationIdx);
            btn.setMessage(Component.literal(s.isEmpty() ? "无" : s));
        }).bounds(valueX, y + ROW_H + GAP + 24, VALUE_W, 20).build();
        addRenderableWidget(classifBtn);

        // === 分级宽度 section ===
        int sectionY = y + (ROW_H + GAP) * 2 + 24 + 8;

        classifToggleBtn =
            Button.builder(Component.literal((useClassificationWidth ? "✓ " : "   ") + "使用分级宽度替代手动输入"), btn -> {
                useClassificationWidth = !useClassificationWidth;
                btn.setMessage(Component.literal((useClassificationWidth ? "✓ " : "   ") + "使用分级宽度替代手动输入"));
                rebuildClassificationWidgets();
            }).bounds(leftX + 8, sectionY + 14, PAGE_W - 16, 20).build();
        addRenderableWidget(classifToggleBtn);

        if (useClassificationWidth) {
            buildClassifBoxes(sectionY + 14 + ROW_H + 4);
        }

        // === 按键 section ===
        int hotkeySectionY = sectionY + 14 + ROW_H + 4;
        if (useClassificationWidth) {
            hotkeySectionY = sectionY + 14 + ROW_H + 4 + config.classificationWidths.size() * (ROW_H + GAP) + 8;
        }

        buildHotkeyButtons(hotkeySectionY);

        // 计算总内容高度
        computeTotalContentHeight();

        // 应用搜索过滤
        if (!searchText.isEmpty()) {
            updateWidgetVisibility();
        }
    }

    private void buildClassifBoxes(int startY) {
        int y = startY;
        for (Map.Entry<String, Double> e : config.classificationWidths.entrySet()) {
            EditBox box = new EditBox(this.font, valueX, y, 60, 20, Component.literal(e.getKey()));
            box.setMaxLength(6);
            box.setValue(String.valueOf(e.getValue().intValue()));
            addRenderableWidget(box);
            classifBoxes.put(e.getKey(), box);
            y += ROW_H + GAP;
        }
    }

    private void rebuildHotkeyButtons() {
        if (recHotkeyBtn != null) {
            removeWidget(recHotkeyBtn);
            recHotkeyBtn = null;
        }
        if (menuHotkeyBtn != null) {
            removeWidget(menuHotkeyBtn);
            menuHotkeyBtn = null;
        }

        int sectionY = CONTENT_TOP + (ROW_H + GAP) * 2 + 24 + 8;
        int hotkeySectionY = sectionY + 14 + ROW_H + 4;
        if (useClassificationWidth) {
            hotkeySectionY = sectionY + 14 + ROW_H + 4 + config.classificationWidths.size() * (ROW_H + GAP) + 8;
        }

        buildHotkeyButtons(hotkeySectionY);
        computeTotalContentHeight();
    }

    private void buildHotkeyButtons(int startY) {
        int y = startY + 14;

        List<WayfarerConfig.HotkeyBind> recBinds = config.getHotkeysForAction("toggle_recording");
        if (!recBinds.isEmpty()) {
            recHotkeyBtn = makeHotkeyBtn("toggle_recording", recBinds.get(0), y);
            addRenderableWidget(recHotkeyBtn);
            y += ROW_H + GAP;
        }

        List<WayfarerConfig.HotkeyBind> menuBinds = config.getHotkeysForAction("open_menu");
        if (!menuBinds.isEmpty()) {
            menuHotkeyBtn = makeHotkeyBtn("open_menu", menuBinds.get(0), y);
            addRenderableWidget(menuHotkeyBtn);
        }
    }

    private Button makeHotkeyBtn(String action, WayfarerConfig.HotkeyBind bind, int y) {
        boolean isCapturing = capturing && action.equals(capturingAction);
        String text = isCapturing ? "> " + bind.toDisplayString() + " <" : bind.toDisplayString();
        return Button.builder(Component.literal(text), btn -> {
            if (!capturing) {
                capturing = true;
                capturingAction = action;
                capturingBtn = btn;
                btn.setMessage(Component.literal("> " + bind.toDisplayString() + " <"));
            }
        }).bounds(valueX, y, VALUE_W, 20).build();
    }

    private void rebuildClassificationWidgets() {
        classifBoxes.values().forEach(this::removeWidget);
        classifBoxes.clear();

        if (useClassificationWidth) {
            int sectionY = CONTENT_TOP + (ROW_H + GAP) * 2 + 24 + 8;
            buildClassifBoxes(sectionY + 14 + ROW_H + 4);
        }

        // 重建热键按钮（修复重叠问题）
        rebuildHotkeyButtons();

        if (!searchText.isEmpty()) {
            updateWidgetVisibility();
        }
    }

    private void computeTotalContentHeight() {
        int sectionY = CONTENT_TOP + (ROW_H + GAP) * 2 + 24 + 8;
        int hotkeySectionY = sectionY + 14 + ROW_H + 4;
        if (useClassificationWidth) {
            hotkeySectionY = sectionY + 14 + ROW_H + 4 + config.classificationWidths.size() * (ROW_H + GAP) + 8;
        }

        int lastY = hotkeySectionY + 14;

        List<WayfarerConfig.HotkeyBind> recBinds = config.getHotkeysForAction("toggle_recording");
        if (!recBinds.isEmpty()) {
            lastY += ROW_H + GAP;
        }
        List<WayfarerConfig.HotkeyBind> menuBinds = config.getHotkeysForAction("open_menu");
        if (!menuBinds.isEmpty()) {
            lastY += ROW_H + GAP;
        }

        this.totalContentHeight = lastY + 20 - CONTENT_TOP;
    }

    // === 搜索过滤 ===

    private boolean matchesSearch(String... texts) {
        if (searchText.isEmpty())
            return true;
        String lower = searchText.toLowerCase();
        for (String t : texts) {
            if (t != null && t.toLowerCase().contains(lower))
                return true;
        }
        return false;
    }

    private void updateWidgetVisibility() {
        boolean filtering = !searchText.isEmpty();

        defaultWidthBox.visible = !filtering || matchesSearch("默认道路宽度", defaultWidthBox.getValue());

        classifBtn.visible = !filtering || matchesSearch("默认分级", classifBtn.getMessage().getString());

        classifToggleBtn.visible = !filtering
            || matchesSearch("使用分级宽度替代手动输入", classifToggleBtn.getMessage().getString());

        for (Map.Entry<String, EditBox> e : classifBoxes.entrySet()) {
            e.getValue().visible = !filtering || matchesSearch(e.getKey(), e.getValue().getValue());
        }

        if (recHotkeyBtn != null) {
            recHotkeyBtn.visible = !filtering || matchesSearch("开始/停止录制", recHotkeyBtn.getMessage().getString());
        }
        if (menuHotkeyBtn != null) {
            menuHotkeyBtn.visible = !filtering || matchesSearch("打开主菜单", menuHotkeyBtn.getMessage().getString());
        }

        computeTotalContentHeight();
    }

    // === 渲染 ===

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, this.width, this.height, 0xFF1B1F28);

        // 标题
        g.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);

        // 可见区域
        int contentBottom = this.height - (capturing ? 36 : 8);
        int visibleHeight = contentBottom - CONTENT_TOP;

        g.enableScissor(0, CONTENT_TOP, this.width, visibleHeight);

        boolean filtering = !searchText.isEmpty();
        int drawY = CONTENT_TOP - scrollOffset;

        // 默认值 section header
        if (!filtering || matchesSearch("默认值")) {
            g.drawString(this.font, Component.literal("默认值"), labelX, drawY, 0xFFAAAAAA, false);
        }
        drawY += 24;

        // 默认道路宽度
        if (!filtering || matchesSearch("默认道路宽度", defaultWidthBox.getValue())) {
            g.drawString(this.font, Component.literal("默认道路宽度"), labelX, drawY + 1, 0xFFCCCCCC, false);
        }
        drawY += ROW_H + GAP;

        // 默认分级
        if (!filtering || matchesSearch("默认分级", classifBtn.getMessage().getString())) {
            g.drawString(this.font, Component.literal("默认分级"), labelX, drawY + 1, 0xFFCCCCCC, false);
        }
        drawY += ROW_H + GAP;

        // 分级宽度 section header
        drawY += 8;
        if (!filtering || matchesSearch("分级宽度", "使用分级宽度替代手动输入")) {
            g.drawString(this.font, Component.literal("分级宽度"), labelX, drawY, 0xFFAAAAAA, false);
        }
        drawY += 14 + ROW_H + 4;

        // 分级宽度条目
        if (useClassificationWidth) {
            for (Map.Entry<String, Double> e : config.classificationWidths.entrySet()) {
                EditBox box = classifBoxes.get(e.getKey());
                String val = box != null ? box.getValue() : "";
                if (!filtering || matchesSearch(e.getKey(), val)) {
                    g.drawString(this.font, Component.literal(e.getKey()), labelX, drawY + 1, 0xFFCCCCCC, false);
                }
                drawY += ROW_H + GAP;
            }
        } else {
            drawY += ROW_H;
        }

        // 按键 section
        drawY += 8;
        if (!filtering || matchesSearch("按键", "开始/停止录制", "打开主菜单")) {
            g.drawString(this.font, Component.literal("按键"), labelX, drawY, 0xFFAAAAAA, false);
        }
        drawY += 14;

        List<WayfarerConfig.HotkeyBind> recBinds = config.getHotkeysForAction("toggle_recording");
        if (!recBinds.isEmpty()) {
            String btnText = recHotkeyBtn != null ? recHotkeyBtn.getMessage().getString() : "";
            if (!filtering || matchesSearch("开始/停止录制", btnText)) {
                g.drawString(this.font, Component.literal("开始/停止录制"), labelX, drawY + 8 + 1, 0xFFCCCCCC, false);
            }
            drawY += ROW_H + GAP;
        }
        List<WayfarerConfig.HotkeyBind> menuBinds = config.getHotkeysForAction("open_menu");
        if (!menuBinds.isEmpty()) {
            String btnText = menuHotkeyBtn != null ? menuHotkeyBtn.getMessage().getString() : "";
            if (!filtering || matchesSearch("打开主菜单", btnText)) {
                g.drawString(this.font, Component.literal("打开主菜单"), labelX, drawY + 8 + 1, 0xFFCCCCCC, false);
            }
        }

        g.disableScissor();

        // 滚动条指示器
        int maxScroll = Math.max(0, totalContentHeight - visibleHeight);
        if (maxScroll > 0) {
            int barHeight = Math.max(20, (int) ((double) visibleHeight / totalContentHeight * visibleHeight));
            int barY = CONTENT_TOP + (int) ((double) scrollOffset / maxScroll * (visibleHeight - barHeight));
            int barX = this.width - 4;
            g.fill(barX, barY, barX + 3, barY + barHeight, 0x66FFFFFF);
        }

        // 捕获提示
        if (capturing) {
            g.drawCenteredString(this.font, Component.literal("按下按键进行绑定，ESC 取消"), this.width / 2, this.height - 36,
                0xFFFFFF55);
        }

        super.render(g, mouseX, mouseY, partial);
    }

    // === 滚动 ===

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        int contentBottom = this.height - (capturing ? 36 : 8);
        int visibleHeight = contentBottom - CONTENT_TOP;
        int maxScroll = Math.max(0, totalContentHeight - visibleHeight);
        scrollOffset = Math.max(0, Math.min(scrollOffset - (int) (amount * 20), maxScroll));
        return true;
    }

    // === 键盘输入 ===

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (searchBox.isFocused() && key != GLFW.GLFW_KEY_ESCAPE) {
            return super.keyPressed(key, scan, mods);
        }

        if (capturing && capturingAction != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                cancelCapture();
                return true;
            }

            int modKey = detectModifier(key);
            WayfarerConfig.HotkeyBind bind = new WayfarerConfig.HotkeyBind(key, scan, modKey, 0);
            config.getHotkeys().put(capturingAction, new ArrayList<>(List.of(bind)));

            if (capturingBtn != null) {
                capturingBtn.setMessage(Component.literal(bind.toDisplayString()));
            }

            capturing = false;
            capturingAction = null;
            capturingBtn = null;
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    private int detectModifier(int excludeKey) {
        long w = this.minecraft.getWindow().getWindow();
        for (int c : new int[] {GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT, GLFW.GLFW_KEY_LEFT_CONTROL,
            GLFW.GLFW_KEY_RIGHT_CONTROL, GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT}) {
            if (c != excludeKey && GLFW.glfwGetKey(w, c) == GLFW.GLFW_PRESS) {
                return c;
            }
        }
        return -1;
    }

    private void cancelCapture() {
        if (capturingBtn != null && capturingAction != null) {
            List<WayfarerConfig.HotkeyBind> binds = config.getHotkeysForAction(capturingAction);
            if (!binds.isEmpty()) {
                capturingBtn.setMessage(Component.literal(binds.get(0).toDisplayString()));
            }
        }
        capturing = false;
        capturingAction = null;
        capturingBtn = null;
    }

    // === 保存 / 关闭 ===

    @Override
    public void onClose() {
        saveConfig();
        config.save();
        WayfarerClient.reloadHotkeys();
        this.minecraft.setScreen(parent);
    }

    private void saveConfig() {
        config.defaultWidth = parseWidth(defaultWidthBox.getValue());
        config.defaultClassification = CLASSIFICATIONS.get(classificationIdx);
        config.useClassificationWidth = useClassificationWidth;

        for (Map.Entry<String, EditBox> e : classifBoxes.entrySet()) {
            try {
                double v = Double.parseDouble(e.getValue().getValue().trim());
                if (v > 0)
                    config.classificationWidths.put(e.getKey(), v);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private double parseWidth(String s) {
        try {
            double v = Double.parseDouble(s.trim());
            return v > 0 ? v : 7.0;
        } catch (NumberFormatException e) {
            return 7.0;
        }
    }
}
