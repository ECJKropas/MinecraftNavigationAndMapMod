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

import com.ecjkim.wayfarer.client.config.WayfarerConfigs;
import com.ecjkim.wayfarer.client.config.WayfarerHotkeys;

import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybind;

public class WayfarerConfig {
    private static WayfarerConfig instance;

    private WayfarerConfig() {}

    // -- Live getters (read from malilib config each call, never stale) --

    public String getDefaultClassification() {
        return WayfarerConfigs.Generic.DEFAULT_CLASSIFICATION.getOptionListValue().getStringValue();
    }

    public boolean isAutoIntegral() {
        return WayfarerConfigs.Generic.AUTO_INTEGRAL.getBooleanValue();
    }

    public boolean isAutoSnapEndpoints() {
        return WayfarerConfigs.Generic.AUTO_SNAP_ENDPOINTS.getBooleanValue();
    }

    public double getRdpEpsilon() {
        return WayfarerConfigs.Generic.RDP_EPSILON.getDoubleValue();
    }

    public boolean isAutoDeleteOrphanNodes() {
        return WayfarerConfigs.Generic.AUTO_DELETE_ORPHAN_NODES.getBooleanValue();
    }

    public boolean isAutoGraphify() {
        return WayfarerConfigs.Generic.AUTO_GRAPHIFY.getBooleanValue();
    }

    public boolean isToolItemEnabled() {
        return WayfarerConfigs.Generic.TOOL_ITEM_ENABLED.getBooleanValue();
    }

    public String getToolItem() {
        return WayfarerConfigs.Generic.TOOL_ITEM.getStringValue();
    }

    public boolean isNodeIndicatorEnabled() {
        return WayfarerConfigs.Generic.NODE_INDICATOR_ENABLED.getBooleanValue();
    }

    public double getNodeIndicatorBeamHeight() {
        return WayfarerConfigs.Generic.NODE_INDICATOR_BEAM_HEIGHT.getDoubleValue();
    }

    public double getNodeIndicatorBeamAlpha() {
        return WayfarerConfigs.Generic.NODE_INDICATOR_BEAM_ALPHA.getDoubleValue();
    }

    public boolean isShowKeyHints() {
        return WayfarerConfigs.Generic.SHOW_KEY_HINTS.getBooleanValue();
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

    public void setToolItem(String value) {
        WayfarerConfigs.Generic.TOOL_ITEM.setValueFromString(value);
    }

    public List<HotkeyBind> getHotkeysForAction(String action) {
        if ("toggle_recording".equals(action)) {
            return List.of(new HotkeyBind(WayfarerHotkeys.TOGGLE_RECORDING));
        }
        if ("open_menu".equals(action)) {
            return List.of(new HotkeyBind(WayfarerHotkeys.OPEN_MENU));
        }
        if ("set_held_item_as_tool".equals(action)) {
            return List.of(new HotkeyBind(WayfarerHotkeys.SET_HELD_ITEM_AS_TOOL));
        }
        return List.of();
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
                // 组合键：最后一个键为主键，前面的为修饰键
                this.key = keys.get(size - 1);
                this.modifierKey = keys.get(0);
            }
            this.scanCode = 0;
        }
    }
}
