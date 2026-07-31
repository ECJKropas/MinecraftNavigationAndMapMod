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

/**
 * Survey mode specific hotkey definitions.
 *
 * <p>
 * These hotkeys control Survey mode operations:
 * <ul>
 * <li>START_RECORDING: Start recording (alternative to left-click on air)</li>
 * <li>STOP_RECORDING: Stop recording (alternative to left-click on air)</li>
 * <li>CANCEL_RECORDING: Cancel current recording without saving</li>
 * <li>CYCLE_CORNER_TYPE: Cycle through corner types (alternative to Ctrl+Scroll)</li>
 * <li>TOGGLE_RENDERING: Toggle node outline rendering visibility</li>
 * </ul>
 */
public class SurveyHotkeys {
    public static final ConfigHotkey START_RECORDING = new ConfigHotkey("surveyStartRecording", "", "Survey - 开始录制");

    public static final ConfigHotkey STOP_RECORDING = new ConfigHotkey("surveyStopRecording", "", "Survey - 结束录制");

    public static final ConfigHotkey CANCEL_RECORDING = new ConfigHotkey("surveyCancelRecording", "", "Survey - 取消录制");

    public static final ConfigHotkey CYCLE_CORNER_TYPE =
        new ConfigHotkey("surveyCycleCornerType", "", "Survey - 切换角落类型");

    public static final ConfigHotkey TOGGLE_RENDERING =
        new ConfigHotkey("surveyToggleRendering", "", "Survey - 切换渲染显示");

    public static final List<ConfigHotkey> SURVEY_HOTKEY_LIST =
        ImmutableList.of(START_RECORDING, STOP_RECORDING, CANCEL_RECORDING, CYCLE_CORNER_TYPE, TOGGLE_RENDERING);
}
