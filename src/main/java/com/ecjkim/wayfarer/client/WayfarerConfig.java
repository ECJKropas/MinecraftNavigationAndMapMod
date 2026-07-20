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

import java.util.List;
import java.util.Map;

import com.ecjkim.wayfarer.client.config.WayfarerConfigs;
import com.ecjkim.wayfarer.client.config.WayfarerHotkeys;

import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybind;

public class WayfarerConfig {
    private static WayfarerConfig instance;

    public final double defaultWidth;
    public final String defaultClassification;
    public final boolean useClassificationWidth;
    public final Map<String, Integer> classificationWidths;
    public String tileProviderMode;

    private WayfarerConfig() {
        this.defaultWidth = WayfarerConfigs.Generic.DEFAULT_WIDTH.getDoubleValue();
        this.defaultClassification = WayfarerConfigs.Generic.DEFAULT_CLASSIFICATION.getStringValue();
        this.useClassificationWidth = WayfarerConfigs.Generic.USE_CLASSIFICATION_WIDTH.getBooleanValue();
        this.classificationWidths = WayfarerConfigs.Generic.getClassificationWidths();
        this.tileProviderMode =
            ((WayfarerConfigs.TileProviderMode)WayfarerConfigs.Generic.TILE_PROVIDER_MODE.getOptionListValue()).name();
    }

    public static WayfarerConfig getInstance() {
        if (instance == null) {
            instance = new WayfarerConfig();
        }
        return instance;
    }

    public void save() {
        ((fi.dy.masa.malilib.config.ConfigManager)ConfigManager.getInstance()).saveAllConfigs();
    }

    public List<HotkeyBind> getHotkeysForAction(String action) {
        if ("toggle_recording".equals(action)) {
            return List.of(new HotkeyBind(WayfarerHotkeys.TOGGLE_RECORDING));
        }
        if ("open_menu".equals(action)) {
            return List.of(new HotkeyBind(WayfarerHotkeys.OPEN_MENU));
        }
        return List.of();
    }

    /** 根据分级获取宽度 */
    public double getWidthForClassification(String classification) {
        if (classification == null || classification.isEmpty()) {
            return defaultWidth;
        }
        Integer w = classificationWidths.get(classification);
        return w != null ? w.doubleValue() : defaultWidth;
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
            if (!keys.isEmpty()) {
                this.key = keys.get(0);
            } else {
                this.key = -1;
            }
            this.scanCode = 0;
            this.modifierKey = -1;
        }
    }
}
