package com.ecjkim.wayfarer.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.glfw.GLFW;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;

public class WayfarerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
        Path.of(System.getProperty("user.dir"), "config", "wayfarer", "settings.json");

    private static WayfarerConfig instance;

    public boolean useClassificationWidth = false;
    public Map<String, Double> classificationWidths = defaultClassificationWidths();
    public double defaultWidth = 7.0;
    public String defaultClassification = "";
    public Map<String, List<HotkeyBind>> hotkeys = defaultHotkeys();

    private WayfarerConfig() {}

    public static WayfarerConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static WayfarerConfig load() {
        if (!Files.exists(CONFIG_PATH)) {
            WayfarerConfig config = new WayfarerConfig();
            config.save();
            return config;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            WayfarerConfig config = GSON.fromJson(json, WayfarerConfig.class);
            if (config == null)
                return new WayfarerConfig();
            if (config.classificationWidths == null || config.classificationWidths.isEmpty()) {
                config.classificationWidths = defaultClassificationWidths();
            }
            if (config.hotkeys == null || config.hotkeys.isEmpty()) {
                config.hotkeys = defaultHotkeys();
            }
            return config;
        } catch (IOException e) {
            return new WayfarerConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(this), StandardCharsets.UTF_8);
            instance = this;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save settings", e);
        }
    }

    public Map<String, List<HotkeyBind>> getHotkeys() {
        return hotkeys;
    }

    public List<HotkeyBind> getHotkeysForAction(String action) {
        List<HotkeyBind> binds = hotkeys.get(action);
        return binds != null ? binds : Collections.emptyList();
    }

    /** 根据分级获取宽度 */
    public double getWidthForClassification(String classification) {
        if (classification == null || classification.isEmpty())
            return defaultWidth;
        Double w = classificationWidths.get(classification);
        return w != null ? w : defaultWidth;
    }

    public static LinkedHashMap<String, Double> defaultClassificationWidths() {
        LinkedHashMap<String, Double> map = new LinkedHashMap<>();
        map.put("G国道", 21.0);
        map.put("G高速", 21.0);
        map.put("S省道", 17.0);
        map.put("S高架", 17.0);
        map.put("X乡道", 13.0);
        map.put("Y县道", 7.0);
        map.put("C村道", 3.0);
        return map;
    }

    public static Map<String, List<HotkeyBind>> defaultHotkeys() {
        Map<String, List<HotkeyBind>> map = new LinkedHashMap<>();
        map.put("toggle_recording", new ArrayList<>(List.of(new HotkeyBind(GLFW.GLFW_KEY_R, 0))));
        map.put("open_menu", new ArrayList<>(List.of(new HotkeyBind(GLFW.GLFW_KEY_N, 0))));
        return map;
    }

    // --- HotkeyBind ---

    public static class HotkeyBind {
        public int key;
        public int scanCode;
        public int modifierKey = -1;
        public int modifierScanCode = 0;

        public HotkeyBind() {}

        public HotkeyBind(int key, int scanCode) {
            this.key = key;
            this.scanCode = scanCode;
        }

        public HotkeyBind(int key, int scanCode, int modifierKey, int modifierScanCode) {
            this.key = key;
            this.scanCode = scanCode;
            this.modifierKey = modifierKey;
            this.modifierScanCode = modifierScanCode;
        }

        public String toDisplayString() {
            String keyName = InputConstants.getKey(key, scanCode).getDisplayName().getString();
            if (modifierKey > 0) {
                String modName = InputConstants.getKey(modifierKey, modifierScanCode).getDisplayName().getString();
                return modName + "+" + keyName;
            }
            return keyName;
        }
    }
}
