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

import com.mojang.blaze3d.platform.InputConstants;

import org.lwjgl.glfw.GLFW;

public class SettingsScreen extends Screen {
    private static final List<String> CLASSIFICATIONS = List.of(
        "", "G国道", "G高速", "S省道", "S高架", "X乡道", "Y县道", "C村道");

    private static final int PANEL_WIDTH = 340;
    private static final int COL1_X = 0; // relative, set in init
    private static final int COL2_X_OFFSET = 60;

    private final Screen parent;
    private final WayfarerConfig config;

    // 默认值
    private EditBox defaultWidthBox;
    private int classificationIndex;
    private Button classificationCycleBtn;

    // 分级宽度开关
    private boolean useClassificationWidth;

    // 分级宽度编辑框
    private final Map<String, EditBox> classificationWidthBoxes = new LinkedHashMap<>();

    // 热键显示
    private final List<HotkeyRow> hotkeyRows = new ArrayList<>();

    // 热键捕获状态
    private boolean capturingHotkey = false;
    private String capturingAction = null;

    public SettingsScreen(Screen parent) {
        super(Component.literal("越陌度阡 · 设置"));
        this.parent = parent;
        this.config = WayfarerConfig.getInstance();
        this.useClassificationWidth = config.useClassificationWidth;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int left = centerX - PANEL_WIDTH / 2;
        int top = this.height / 2 - 215;
        int fieldLeft = left + 130;
        int fieldWidth = 120;
        int labelX = left + 20;

        // === 默认值 ===
        int y = top + 38;
        drawSectionLabel(y - 10, "默认值");

        this.defaultWidthBox = new EditBox(this.font, fieldLeft, y, fieldWidth, 20,
            Component.literal("默认道路宽度"));
        this.defaultWidthBox.setMaxLength(8);
        this.defaultWidthBox.setValue(String.valueOf(config.defaultWidth));
        this.addRenderableWidget(this.defaultWidthBox);
        y += 26;

        this.classificationCycleBtn = Button.builder(Component.literal(classificationLabel()), btn -> {
            classificationIndex = (classificationIndex + 1) % CLASSIFICATIONS.size();
            btn.setMessage(Component.literal(classificationLabel()));
        }).bounds(fieldLeft, y, 120, 20).build();
        this.addRenderableWidget(this.classificationCycleBtn);
        y += 28;

        // === 分级宽度开关 ===
        drawSectionLabel(y, "分级宽度");
        y += 12;
        Button toggleBtn = Button.builder(
            Component.literal((useClassificationWidth ? "✓" : "✗") + " 使用分级宽度替代手动输入"),
            btn -> {
                useClassificationWidth = !useClassificationWidth;
                btn.setMessage(Component.literal(
                    (useClassificationWidth ? "✓" : "✗") + " 使用分级宽度替代手动输入"));
                rebuildClassificationBoxes();
            }).bounds(labelX, y, PANEL_WIDTH - 40, 20).build();
        this.addRenderableWidget(toggleBtn);
        y += 28;

        // === 分级宽度对照表 ===
        int classifY = y;
        rebuildClassificationBoxes();
        y = classifY + (useClassificationWidth ? classificationRowCount() * 24 + 8 : 0);

        // === 按键 ===
        drawSectionLabel(y, "按键");
        y += 14;
        hotkeyRows.clear();
        for (WayfarerConfig.HotkeyBind binding : config.getHotkeysForAction("toggle_recording")) {
            hotkeyRows.add(new HotkeyRow("toggle_recording", "开始/停止录制", binding, y));
            y += 24;
        }
        for (WayfarerConfig.HotkeyBind binding : config.getHotkeysForAction("open_menu")) {
            hotkeyRows.add(new HotkeyRow("open_menu", "打开主菜单", binding, y));
            y += 24;
        }
        // 添加新热键按钮
        addRenderableWidget(Button.builder(Component.literal("+ 添加热键"), btn -> {
            // 简化: 打开GUI让用户选择要绑定的操作
        }).bounds(labelX, y, 100, 18).build());

        // === 底部按钮 ===
        int btnY = top + 410;
        addRenderableWidget(Button.builder(Component.literal("保存"), btn -> {
            saveAndClose();
        }).bounds(centerX - 116, btnY, 112, 20).build());

        addRenderableWidget(Button.builder(Component.literal("取消"), btn -> {
            this.minecraft.setScreen(parent);
        }).bounds(centerX + 4, btnY, 112, 20).build());

        int idx = CLASSIFICATIONS.indexOf(config.defaultClassification);
        this.classificationIndex = idx >= 0 ? idx : 0;
    }

    private int classificationRowCount() {
        return config.classificationWidths.size();
    }

    private void rebuildClassificationBoxes() {
        // 清除旧的
        for (EditBox box : classificationWidthBoxes.values()) {
            this.removeWidget(box);
        }
        classificationWidthBoxes.clear();

        if (!useClassificationWidth)
            return;

        int left = this.width / 2 - PANEL_WIDTH / 2;
        int y = this.height / 2 - 215 + 156;
        int labelX = left + 20;
        int widthBoxX = labelX + 50;

        for (Map.Entry<String, Double> entry : config.classificationWidths.entrySet()) {
            String key = entry.getKey();
            EditBox box = new EditBox(this.font, widthBoxX, y, 50, 18, Component.literal(key));
            box.setMaxLength(6);
            box.setValue(String.valueOf(entry.getValue().intValue()));
            this.addRenderableWidget(box);
            classificationWidthBoxes.put(key, box);
            y += 22;
        }
    }

    private void drawSectionLabel(int y, String label) {
        // 在 render 中绘制
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xCC000000);

        int centerX = this.width / 2;
        int left = centerX - PANEL_WIDTH / 2;
        int top = this.height / 2 - 215;
        int panelBottom = top + 430;

        // 面板背景
        graphics.fill(left, top, left + PANEL_WIDTH, panelBottom, 0xE01B1F28);
        graphics.fill(left, top, left + PANEL_WIDTH, top + 1, 0xFF4E5768);
        graphics.fill(left, panelBottom - 1, left + PANEL_WIDTH, panelBottom, 0xFF1A1F27);

        // 标题
        graphics.drawCenteredString(this.font, this.title, centerX, top + 8, 0xFFFFFFFF);

        // 分节标题 + 标签
        int y = top + 26;
        graphics.drawString(this.font, Component.literal("▎默认值"), left + 16, y, 0xFF888888, false);
        y += 14;
        graphics.drawString(this.font, Component.literal("道路宽度"), left + 20, y, 0xFFAAAAAA, false);
        y += 26;
        graphics.drawString(this.font, Component.literal("默认分级"), left + 20, y, 0xFFAAAAAA, false);
        y += 28;

        graphics.drawString(this.font, Component.literal("▎分级宽度"), left + 16, y, 0xFF888888, false);

        if (useClassificationWidth) {
            y += 42;
            int labelX = left + 20;
            int widthX = labelX + 55;
            for (Map.Entry<String, Double> entry : config.classificationWidths.entrySet()) {
                graphics.drawString(this.font, Component.literal(entry.getKey()), labelX, y + 1, 0xFFAAAAAA, false);
                y += 22;
            }
        } else {
            y += 40;
        }

        y += 10;
        graphics.drawString(this.font, Component.literal("▎按键"), left + 16, y, 0xFF888888, false);
        y += 14;

        // 热键行
        for (HotkeyRow row : hotkeyRows) {
            boolean capturing = capturingHotkey && row.action.equals(capturingAction);
            String display = capturing ? "等待按键..." : row.binding.toDisplayString();
            graphics.drawString(this.font, Component.literal(row.label), left + 20, y + 1, 0xFFAAAAAA, false);
            graphics.drawString(this.font, Component.literal(display), left + 160, y + 1,
                capturing ? 0xFF55FFFF : 0xFFFFFFFF, false);

            // 绑定按钮区域
            int btnX = left + 260;
            boolean hoverBtn = mouseX >= btnX && mouseX <= btnX + 50 && mouseY >= y && mouseY <= y + 18;
            graphics.drawString(this.font,
                Component.literal(capturing ? "按ESC取消" : "[绑定]"), btnX, y + 1,
                hoverBtn ? 0xFF66BBFF : 0xFF888888, false);
            y += 24;
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || capturingHotkey)
            return super.mouseClicked(mouseX, mouseY, button);

        int left = this.width / 2 - PANEL_WIDTH / 2;
        int top = this.height / 2 - 215;

        // 检查热键绑定按钮点击
        int y = top + 280; // approximate, but we recalc
        // Actually we need the exact y. Let me just check if click is on any hotkey row button.
        for (HotkeyRow row : hotkeyRows) {
            int btnX = left + 260;
            if (mouseX >= btnX && mouseX <= btnX + 50 && mouseY >= row.y && mouseY <= row.y + 18) {
                capturingHotkey = true;
                capturingAction = row.action;
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (capturingHotkey && capturingAction != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                capturingHotkey = false;
                capturingAction = null;
                return true;
            }

            // 检查是否有修饰键按下
            int modifierKey = -1;
            int modifierScanCode = 0;
            // 检查 Shift/Ctrl/Alt 作为可能的 combo 修饰键
            // 但我们允许多键组合，这里先简化：如果有其他键同时按下，记录
            // 实际 GLFW 不支持同时获取两个键，这里我们通过检查当前按下的其他键
            // 简化处理：如果 keyCode 是普通字母键，检查 C/U 等是否按下
            long window = this.minecraft.getWindow().getWindow();
            for (int candidate : new int[] {GLFW.GLFW_KEY_C, GLFW.GLFW_KEY_U, GLFW.GLFW_KEY_LEFT_SHIFT,
                GLFW.GLFW_KEY_RIGHT_SHIFT, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL,
                GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT}) {
                if (candidate != keyCode && GLFW.glfwGetKey(window, candidate) == GLFW.GLFW_PRESS) {
                    modifierKey = candidate;
                    modifierScanCode = 0;
                    break;
                }
            }

            WayfarerConfig.HotkeyBind newBind = new WayfarerConfig.HotkeyBind();
            newBind.key = keyCode;
            newBind.scanCode = scanCode;
            newBind.modifierKey = modifierKey;
            newBind.modifierScanCode = modifierScanCode;

            // 替换该 action 下的所有绑定（简化：一个 action 一个绑定，未来可扩展）
            config.getHotkeys().put(capturingAction, new ArrayList<>(List.of(newBind)));

            // 刷新热键行
            refreshHotkeyRows();

            capturingHotkey = false;
            capturingAction = null;
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void refreshHotkeyRows() {
        hotkeyRows.clear();
        int left = this.width / 2 - PANEL_WIDTH / 2;
        int ySection = this.height / 2 - 215 + 300; // approximate, will be recalculated in render
        // Actually, we need proper y positioning. Let me re-init instead.
        this.init();
    }

    private void saveAndClose() {
        // 保存默认值
        config.defaultWidth = parseWidth(defaultWidthBox.getValue());
        config.defaultClassification = CLASSIFICATIONS.get(classificationIndex);
        config.useClassificationWidth = useClassificationWidth;

        // 保存分级宽度
        for (Map.Entry<String, EditBox> entry : classificationWidthBoxes.entrySet()) {
            try {
                double val = Double.parseDouble(entry.getValue().getValue().trim());
                if (val > 0) {
                    config.classificationWidths.put(entry.getKey(), val);
                }
            } catch (NumberFormatException ignored) {}
        }

        config.save();
        WayfarerClient.reloadHotkeys();
        this.minecraft.setScreen(parent);
    }

    private String classificationLabel() {
        String val = CLASSIFICATIONS.get(classificationIndex);
        return val.isEmpty() ? "道路分级" : val;
    }

    private double parseWidth(String input) {
        try {
            double parsed = Double.parseDouble(input.trim());
            return parsed <= 0.0D ? 7.0D : parsed;
        } catch (NumberFormatException e) {
            return 7.0D;
        }
    }

    private static class HotkeyRow {
        final String action;
        final String label;
        final WayfarerConfig.HotkeyBind binding;
        final int y;

        HotkeyRow(String action, String label, WayfarerConfig.HotkeyBind binding, int y) {
            this.action = action;
            this.label = label;
            this.binding = binding;
            this.y = y;
        }
    }
}
