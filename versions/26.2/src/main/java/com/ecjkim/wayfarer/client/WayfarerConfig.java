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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ecjkim.wayfarer.client.config.WayfarerHotkeys;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybind;

public class WayfarerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
        Path.of(System.getProperty("user.dir"), "config", "wayfarer", "settings.json");

    private static WayfarerConfig instance;

    public double rdpEpsilon = 1.0;
    public boolean autoIntegral = true;
    public boolean autoSnapEndpoints = true;
    public boolean autoDeleteOrphanNodes = true;
    public boolean autoGraphify = true;
    public int webMaxZoom = 10;
    public String defaultClassification = "";
    public Map<String, List<HotkeyBind>> hotkeys = defaultHotkeys();

    private WayfarerConfig() {}

    public static WayfarerConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static void reload() {
        instance = load();
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
        if ("toggle_recording".equals(action)) {
            return List.of(new HotkeyBind(WayfarerHotkeys.TOGGLE_RECORDING));
        }
        if ("open_menu".equals(action)) {
            return List.of(new HotkeyBind(WayfarerHotkeys.OPEN_MENU));
        }
        return Collections.emptyList();
    }

    public static Map<String, List<HotkeyBind>> defaultHotkeys() {
        Map<String, List<HotkeyBind>> map = new LinkedHashMap<>();
        map.put("toggle_recording", new ArrayList<>(List.of(new HotkeyBind(82, 0)))); // R
        map.put("open_menu", new ArrayList<>(List.of(new HotkeyBind(78, 0)))); // N
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

        public HotkeyBind(ConfigHotkey hotkey) {
            IKeybind keybind = hotkey.getKeybind();
            java.util.List<Integer> keys = keybind.getKeys();
            int size = keys.size();
            if (size == 0) {
                this.key = -1;
                this.modifierKey = -1;
            } else if (size == 1) {
                this.key = keys.get(0);
                this.modifierKey = -1;
            } else {
                this.key = keys.get(size - 1);
                this.modifierKey = keys.get(0);
            }
            this.scanCode = 0;
        }

        public String toDisplayString() {
            String keyName = glfwKeyName(key);
            if (modifierKey > 0) {
                String modName = glfwKeyName(modifierKey);
                return modName + "+" + keyName;
            }
            return keyName;
        }

        private static String glfwKeyName(int key) {
            String name = org.lwjgl.glfw.GLFW.glfwGetKeyName(key, 0);
            if (name != null && !name.isEmpty()) {
                return name.toUpperCase();
            }
            return "KEY" + key;
        }
    }
}
