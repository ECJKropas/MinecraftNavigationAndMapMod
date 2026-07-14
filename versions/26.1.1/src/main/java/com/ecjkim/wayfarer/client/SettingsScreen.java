package com.ecjkim.wayfarer.client;

import java.util.*;

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

    private static final int ROW_H = 24;
    private static final int GAP = 2;
    private static final int LABEL_W = 160;
    private static final int VALUE_W = 140;
    private static final int PAGE_W = LABEL_W + VALUE_W + 24;

    private final Screen parent;
    private final WayfarerConfig config;

    private EditBox defaultWidthBox;
    private int classificationIdx;
    private boolean useClassificationWidth;
    private final Map<String, EditBox> classifBoxes = new LinkedHashMap<>();
    private Button classifToggleBtn;

    private Button recHotkeyBtn;
    private Button menuHotkeyBtn;

    private boolean capturing;
    private String capturingAction;
    private Button capturingBtn;

    private int leftX;
    private int labelX;
    private int valueX;

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

        classifBoxes.clear();

        int y = 34;

        defaultWidthBox = new EditBox(this.font, valueX, y + 24, VALUE_W, 20,
            Component.literal("默认道路宽度"));
        defaultWidthBox.setMaxLength(8);
        defaultWidthBox.setValue(String.valueOf(config.defaultWidth));
        addRenderableWidget(defaultWidthBox);

        String clsText = CLASSIFICATIONS.get(classificationIdx);
        addRenderableWidget(Button.builder(
            Component.literal(clsText.isEmpty() ? "无" : clsText),
            btn -> {
                classificationIdx = (classificationIdx + 1) % CLASSIFICATIONS.size();
                String s = CLASSIFICATIONS.get(classificationIdx);
                btn.setMessage(Component.literal(s.isEmpty() ? "无" : s));
            }).bounds(valueX, y + ROW_H + GAP + 24, VALUE_W, 20).build());

        int sectionY = y + (ROW_H + GAP) * 2 + 24 + 8;

        classifToggleBtn = Button.builder(
            Component.literal((useClassificationWidth ? "✓ " : "   ") + "使用分级宽度替代手动输入"),
            btn -> {
                useClassificationWidth = !useClassificationWidth;
                btn.setMessage(Component.literal(
                    (useClassificationWidth ? "✓ " : "   ") + "使用分级宽度替代手动输入"));
                rebuildClassificationWidgets();
            }).bounds(leftX + 8, sectionY + 14, PAGE_W - 16, 20).build();
        addRenderableWidget(classifToggleBtn);

        if (useClassificationWidth) {
            buildClassifBoxes(sectionY + 14 + ROW_H + 4);
        }

        int hotkeySectionY = sectionY + 14 + ROW_H + 4;
        if (useClassificationWidth) {
            hotkeySectionY = sectionY + 14 + ROW_H + 4
                + config.classificationWidths.size() * (ROW_H + GAP) + 8;
        }

        buildHotkeyButtons(hotkeySectionY);

        int btnW = 100;
        int btnY = this.height - 28;
        addRenderableWidget(Button.builder(Component.literal("保存"), b -> saveAndClose())
            .bounds(cx - btnW - 2, btnY, btnW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose())
            .bounds(cx + 2, btnY, btnW, 20).build());
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
            int sectionY = 34 + (ROW_H + GAP) * 2 + 24 + 8;
            buildClassifBoxes(sectionY + 14 + ROW_H + 4);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ex, int mouseX, int mouseY, float partial) {
        ex.fill(0, 0, this.width, this.height, 0xCC000000);

        ex.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);

        int y = 34;
        ex.text(this.font, Component.literal("默认值"), labelX, y, 0xFFAAAAAA, false);
        y += 24;
        ex.text(this.font, Component.literal("默认道路宽度"), labelX, y + 1, 0xFFCCCCCC, false);
        y += ROW_H + GAP;
        ex.text(this.font, Component.literal("默认分级"), labelX, y + 1, 0xFFCCCCCC, false);

        y += ROW_H + GAP + 8;
        ex.text(this.font, Component.literal("分级宽度"), labelX, y, 0xFFAAAAAA, false);

        int classifLabelY = y + 14 + ROW_H + 4;
        if (useClassificationWidth) {
            for (Map.Entry<String, Double> e : config.classificationWidths.entrySet()) {
                ex.text(this.font, Component.literal(e.getKey()), labelX, classifLabelY + 1, 0xFFCCCCCC, false);
                classifLabelY += ROW_H + GAP;
            }
        } else {
            classifLabelY += ROW_H;
        }

        int hotkeyY = classifLabelY + 8;
        ex.text(this.font, Component.literal("按键"), labelX, hotkeyY, 0xFFAAAAAA, false);
        hotkeyY += 14;

        List<WayfarerConfig.HotkeyBind> recBinds = config.getHotkeysForAction("toggle_recording");
        if (!recBinds.isEmpty()) {
            ex.text(this.font, Component.literal("开始/停止录制"), labelX,
                hotkeyY + 8 + 1, 0xFFCCCCCC, false);
            hotkeyY += ROW_H + GAP;
        }
        List<WayfarerConfig.HotkeyBind> menuBinds = config.getHotkeysForAction("open_menu");
        if (!menuBinds.isEmpty()) {
            ex.text(this.font, Component.literal("打开主菜单"), labelX,
                hotkeyY + 8 + 1, 0xFFCCCCCC, false);
        }

        if (capturing) {
            ex.centeredText(this.font,
                Component.literal("按下按键进行绑定，ESC 取消"),
                this.width / 2, this.height - 36, 0xFFFFFF55);
        }

        super.extractRenderState(ex, mouseX, mouseY, partial);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (capturing && capturingAction != null) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                cancelCapture();
                return true;
            }

            int key = event.key();
            int modKey = detectModifier(key);
            WayfarerConfig.HotkeyBind bind = new WayfarerConfig.HotkeyBind(key, 0, modKey, 0);
            config.getHotkeys().put(capturingAction, new ArrayList<>(List.of(bind)));

            if (capturingBtn != null) {
                capturingBtn.setMessage(Component.literal(bind.toDisplayString()));
            }

            capturing = false;
            capturingAction = null;
            capturingBtn = null;
            return true;
        }
        return super.keyPressed(event);
    }

    private int detectModifier(int excludeKey) {
        long w = this.minecraft.getWindow().handle();
        for (int c : new int[] {
            GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT,
            GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL,
            GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT
        }) {
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

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(parent);
    }

    private void saveAndClose() {
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

        config.save();
        WayfarerClient.reloadHotkeys();
        this.minecraft.setScreenAndShow(parent);
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
