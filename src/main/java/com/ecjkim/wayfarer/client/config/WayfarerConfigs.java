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

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigString;

public class WayfarerConfigs {
    public static final int CURRENT_VERSION = 1;

    public static class Generic {
        public static final ConfigString DEFAULT_CLASSIFICATION =
            new ConfigString("defaultClassification", "", "新建道路时默认使用的分级代码，如 G国道、S省道");

        public static final ConfigBoolean AUTO_INTEGRAL =
            new ConfigBoolean("autoIntegral", true, "录制道路时是否自动将节点坐标取整（三轴均舍入到整数），默认开启");

        public static final ConfigDouble RDP_EPSILON =
            new ConfigDouble("rdpEpsilon", 1.0, 0.1, 100.0, "RDP 简化容差（格数），值越大简化越激进");

        public static final ImmutableList<IConfigBase> OPTIONS =
            ImmutableList.of(DEFAULT_CLASSIFICATION, AUTO_INTEGRAL, RDP_EPSILON);
    }

    public static List<IConfigBase> getAllConfigs() {
        return ImmutableList.<IConfigBase>builder().addAll(Generic.OPTIONS).addAll(WayfarerHotkeys.HOTKEY_LIST).build();
    }
}
