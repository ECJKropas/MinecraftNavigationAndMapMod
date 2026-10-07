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
package com.ecjkim.wayfarer.client.config;

import java.util.List;

import com.google.common.collect.ImmutableList;

import fi.dy.masa.malilib.config.options.ConfigHotkey;

public class WayfarerHotkeys {
    // The comment passed to malilib is only the fallback: malilib looks the tooltip up under
    // "config.comment.<lowercased name>", which is where the zh_cn / en_us translations live.
    public static final ConfigHotkey TOGGLE_RECORDING =
        new ConfigHotkey("toggleRecording", "R", "Start or stop automatic road recording");
    public static final ConfigHotkey OPEN_MENU = new ConfigHotkey("openMenu", "N", "Open the Wayfarer navigation menu");
    public static final ConfigHotkey SET_HELD_ITEM_AS_TOOL = new ConfigHotkey("setHeldItemAsTool",
        "LEFT_CONTROL,LEFT_ALT,T", "Set the currently held item as the Survey tool");
    public static final ConfigHotkey NAVIGATION =
        new ConfigHotkey("navigation", "G", "Stop the navigation that is currently running");

    public static final List<ConfigHotkey> HOTKEY_LIST =
        ImmutableList.of(TOGGLE_RECORDING, OPEN_MENU, SET_HELD_ITEM_AS_TOOL, NAVIGATION);
}
