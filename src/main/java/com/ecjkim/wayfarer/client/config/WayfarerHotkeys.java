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
    public static final ConfigHotkey TOGGLE_RECORDING = new ConfigHotkey("toggleRecording", "R", "切换道路录制开关");
    public static final ConfigHotkey OPEN_MENU = new ConfigHotkey("openMenu", "N", "打开导航菜单");

    public static final List<ConfigHotkey> HOTKEY_LIST = ImmutableList.of(TOGGLE_RECORDING, OPEN_MENU);
}
