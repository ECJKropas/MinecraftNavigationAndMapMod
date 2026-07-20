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
package com.ecjkim.wayfarer.client.gui;

import net.minecraft.client.Minecraft;

import com.ecjkim.wayfarer.client.Reference;
import com.ecjkim.wayfarer.client.config.WayfarerConfigs;

import fi.dy.masa.malilib.config.gui.GuiModConfigs;

public class WayfarerConfigScreen {
    public static void openConfigScreen() {
        Minecraft.getInstance().setScreen(new GuiModConfigs(Reference.MOD_ID, WayfarerConfigs.getAllConfigs(),
            "wayfarer.gui.title.configs", Minecraft.getInstance().screen));
    }
}
