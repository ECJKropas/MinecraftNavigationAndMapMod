package com.ecjkim.wayfarer.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

public class SettingsScreen extends Screen {
    private static final List<String> CLASSIFICATIONS = List.of(
        "", "G国道", "G高速", "S省道", "S高架", "X乡道", "Y县道", "C村道");

    private static final int PANEL_WIDTH = 340;

    private final Screen parent;
    private final WayfarerConfig config;

    private EditBox defaultWidthBox;
    private int classificationIndex;
    private Button classificationCycleBtn;
    private boolean useClassificationWidth;
    private final Map<String, EditBox> classificationWidthBoxes = new LinkedHashMap<>();
    private final List<HotkeyRow> hotkeyRows = new ArrayList<>();
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

        int y = top + 38;
        this.defaultWidthBox = new EditBox(this.font, fieldLeft, y, fieldWidth, 20, Component.literal("默认道路宽度"));
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

        Button toggleBtn = Button.builder(
            Component.literal((useClassificationWidth ? "✓" : "✗") + " 使用分级宽度替代手动输入"),
            btn -> {
                useClassificationWidth = !useClassificationWidth;
                btn.setMessage(Component.literal(
                    (useClassificationWidth ? "✓" : "✗") + " 使用分级宽度替代手动输入"));
                rebuildClassificationBoxes();
            }).bounds(labelX, y + 12, PANEL_WIDTH - 40, 20).build();
        this.addRenderableWidget(toggleBtn);

        int classifY = y + 40;
        rebuildClassificationBoxes();

        // 热键
        int hotkeyTop = classifY + (useClassificationWidth ? classificationRowCount() * 24 + 8 : 0);
        hotkeyRows.clear();
        y = hotkeyTop + 14;
        for (WayfarerConfig.HotkeyBind binding : config.getHotkeysForAction("toggle_recording")) {
            hotkeyRows.add(new HotkeyRow("toggle_recording", "开始/停止录制", binding, y));
            y += 24;
        }
        for (WayfarerConfig.HotkeyBind binding : config.getHotkeysForAction("open_menu")) {
            hotkeyRows.add(new HotkeyRow("open_menu", "打开主菜单", binding, y));
            y += 24;
        }

        int btnY = top + 410;
        addRenderableWidget(Button.builder(Component.literal("保存"), btn -> {
            saveAndClose();
        }).bounds(centerX - 116, btnY, 112, 20).build());

        addRenderableWidget(Button.builder(Component.literal("取消"), btn -> {
            this.minecraft.setScreenAndShow(parent);
        }).bounds(centerX + 4, btnY, 112, 20).build());

        int idx = CLASSIFICATIONS.indexOf(config.defaultClassification);
        this.classificationIndex = idx >= 0 ? idx : 0;
    }

    private int classificationRowCount() {
        return config.classificationWidths.size();
    }

    private void rebuildClassificationBoxes() {
        for (EditBox box : classificationWidthBoxes.values()) {
            this.removeWidget(box);
        }
        classificationWidthBoxes.clear();

        if (!useClassificationWidth)
            return;

        int left = this.width / 2 - PANEL_WIDTH / 2;
        int y = this.height / 2 - 215 + 156;
        int widthBoxX = left + 75;

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

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float tickDelta) {
        extractor.fill(0, 0, this.width, this.height, 0xCC000000);

        int centerX = this.width / 2;
        int left = centerX - PANEL_WIDTH / 2;
        int top = this.height / 2 - 215;
        int panelBottom = top + 430;

        extractor.fill(left, top, left + PANEL_WIDTH, panelBottom, 0xE01B1F28);
        extractor.fill(left, top, left + PANEL_WIDTH, top + 1, 0xFF4E5768);
        extractor.fill(left, panelBottom - 1, left + PANEL_WIDTH, panelBottom, 0xFF1A1F27);

        extractor.centeredText(this.font, this.title, centerX, top + 8, 0xFFFFFFFF);

        int y = top + 26;
        extractor.text(this.font, Component.literal("▎默认值"), left + 16, y, 0xFF888888, false);
        y += 14;
        extractor.text(this.font, Component.literal("道路宽度"), left + 20, y, 0xFFAAAAAA, false);
        y += 26;
        extractor.text(this.font, Component.literal("默认分级"), left + 20, y, 0xFFAAAAAA, false);
        y += 28;

        extractor.text(this.font, Component.literal("▎分级宽度"), left + 16, y, 0xFF888888, false);

        if (useClassificationWidth) {
            y += 42;
            int labelX = left + 20;
            for (Map.Entry<String, Double> entry : config.classificationWidths.entrySet()) {
                extractor.text(this.font, Component.literal(entry.getKey()), labelX, y + 1, 0xFFAAAAAA, false);
                y += 22;
            }
        } else {
            y += 40;
        }

        y += 10;
        extractor.text(this.font, Component.literal("▎按键"), left + 16, y, 0xFF888888, false);
        y += 14;

        for (HotkeyRow row : hotkeyRows) {
            boolean capturing = capturingHotkey && row.action.equals(capturingAction);
            String display = capturing ? "等待按键..." : row.binding.toDisplayString();
            extractor.text(this.font, Component.literal(row.label), left + 20, y + 1, 0xFFAAAAAA, false);
            extractor.text(this.font, Component.literal(display), left + 160, y + 1,
                capturing ? 0xFF55FFFF : 0xFFFFFFFF, false);

            int btnX = left + 260;
            boolean hoverBtn = mouseX >= btnX && mouseX <= btnX + 50 && mouseY >= y && mouseY <= y + 18;
            extractor.text(this.font,
                Component.literal(capturing ? "按ESC取消" : "[绑定]"), btnX, y + 1,
                hoverBtn ? 0xFF66BBFF : 0xFF888888, false);
            y += 24;
        }

        super.extractRenderState(extractor, mouseX, mouseY, tickDelta);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean hovered) {
        if (event.button() != 0 || capturingHotkey)
            return super.mouseClicked(event, hovered);

        double mouseX = event.x();
        double mouseY = event.y();
        int left = this.width / 2 - PANEL_WIDTH / 2;

        for (HotkeyRow row : hotkeyRows) {
            int btnX = left + 260;
            if (mouseX >= btnX && mouseX <= btnX + 50 && mouseY >= row.y && mouseY <= row.y + 18) {
                capturingHotkey = true;
                capturingAction = row.action;
                return true;
            }
        }

        return super.mouseClicked(event, hovered);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (capturingHotkey && capturingAction != null) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                capturingHotkey = false;
                capturingAction = null;
                return true;
            }

            int keyCode = event.key();
            int scanCode = 0;

            WayfarerConfig.HotkeyBind newBind = new WayfarerConfig.HotkeyBind(keyCode, scanCode, -1, 0);
            config.getHotkeys().put(capturingAction, new ArrayList<>(List.of(newBind)));
            refreshHotkeyRows();

            capturingHotkey = false;
            capturingAction = null;
            return true;
        }

        return super.keyPressed(event);
    }

    private void refreshHotkeyRows() {
        this.init();
    }

    private void saveAndClose() {
        config.defaultWidth = parseWidth(defaultWidthBox.getValue());
        config.defaultClassification = CLASSIFICATIONS.get(classificationIndex);
        config.useClassificationWidth = useClassificationWidth;

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
        this.minecraft.setScreenAndShow(parent);
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
