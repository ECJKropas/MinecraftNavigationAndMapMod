package com.cjkim.mcnav.client.road;

import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.BiConsumer;

public class RoadMetadataScreen extends Screen {
    private static final int PANEL_WIDTH = 280;
    private static final int PANEL_HEIGHT = 184;

    private final BiConsumer<String, Double> onSave;
    private final Runnable onCancel;
    private EditBox nameBox;
    private EditBox widthBox;

    public RoadMetadataScreen(BiConsumer<String, Double> onSave, Runnable onCancel) {
        super(Component.literal("保存道路"));
        this.onSave = onSave;
        this.onCancel = onCancel;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int left = centerX - PANEL_WIDTH / 2;
        int top = this.height / 2 - PANEL_HEIGHT / 2;
        int fieldLeft = left + 20;
        int fieldWidth = PANEL_WIDTH - 40;
        int nameLabelY = top + 40;
        int nameBoxY = nameLabelY + 12;
        int widthLabelY = top + 82;
        int widthBoxY = widthLabelY + 12;
        int hintY = top + 132;
        int buttonY = top + PANEL_HEIGHT - 30;

        this.nameBox = new EditBox(this.font, fieldLeft, nameBoxY, fieldWidth, 20, Component.literal("道路名"));
        this.nameBox.setMaxLength(64);
        this.addRenderableWidget(this.nameBox);

        this.widthBox = new EditBox(this.font, fieldLeft, widthBoxY, fieldWidth, 20, Component.literal("道路宽度"));
        this.widthBox.setValue("7");
        this.addRenderableWidget(this.widthBox);

        this.addRenderableWidget(Button.builder(Component.literal("保存"), button -> {
            String roadName = this.nameBox.getValue().trim();
            if (roadName.isEmpty()) {
                roadName = "未命名道路";
            }
            double roadWidth = parseWidth(this.widthBox.getValue());
            this.onSave.accept(roadName, roadWidth);
            this.minecraft.setScreen(null);
        }).bounds(centerX - 116, buttonY, 112, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("取消"), button -> {
            this.onCancel.run();
            this.minecraft.setScreen(null);
        }).bounds(centerX + 4, buttonY, 112, 20).build());

        this.setInitialFocus(this.nameBox);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        int centerX = this.width / 2;
        int left = centerX - PANEL_WIDTH / 2;
        int top = this.height / 2 - PANEL_HEIGHT / 2;

        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xE01B1F28);
        graphics.fill(left, top, left + PANEL_WIDTH, top + 1, 0xFF4E5768);
        graphics.fill(left, top + PANEL_HEIGHT - 1, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFF1A1F27);
        graphics.drawCenteredString(this.font, this.title, centerX, top + 14, 16777215);
        graphics.drawCenteredString(this.font, Component.literal("记录完成后补充名称和宽度"), centerX, top + 28, 11184810);

        int fieldLeft = left + 20;
        graphics.drawString(this.font, Component.literal("道路名"), fieldLeft, top + 40, 11184810, false);
        graphics.drawString(this.font, Component.literal("道路宽度"), fieldLeft, top + 82, 11184810, false);
        graphics.drawString(this.font, Component.literal("宽度默认 7 格，留空或输错都会自动回退。"), fieldLeft, top + 132, 8947848, false);
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

    private double parseWidth(String input) {
        try {
            double parsed = Double.parseDouble(input.trim());
            return parsed <= 0.0D ? 7.0D : parsed;
        } catch (NumberFormatException exception) {
            return 7.0D;
        }
    }
}
