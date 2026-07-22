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

import java.util.LinkedHashMap;
import java.util.List;

import com.google.common.collect.ImmutableList;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigString;

public class WayfarerConfigs {
    public static final int CURRENT_VERSION = 1;

    public static class Generic {
        public static final ConfigBoolean USE_CLASSIFICATION_WIDTH =
            new ConfigBoolean("useClassificationWidth", false, "是否启用分级道路宽度");

        public static final ConfigDouble DEFAULT_WIDTH = new ConfigDouble("defaultWidth", 7.0, 0.5, 100.0, "道路默认宽度");
        public static final ConfigString DEFAULT_CLASSIFICATION =
            new ConfigString("defaultClassification", "", "新建道路时默认使用的分级代码，如 G国道、S省道");

        public static final ConfigInteger WIDTH_G_GUODAO = new ConfigInteger("widthGGuodao", 21, 1, 100, "G国道宽度");
        public static final ConfigInteger WIDTH_G_GAOSU = new ConfigInteger("widthGGaosu", 21, 1, 100, "G高速宽度");
        public static final ConfigInteger WIDTH_S_SHENGDAO = new ConfigInteger("widthSShengdao", 17, 1, 100, "S省道宽度");
        public static final ConfigInteger WIDTH_S_GAOJIA = new ConfigInteger("widthSGaojia", 17, 1, 100, "S高架宽度");
        public static final ConfigInteger WIDTH_X_XIANGDAO = new ConfigInteger("widthXXiangdao", 13, 1, 100, "X乡道宽度");
        public static final ConfigInteger WIDTH_Y_XIANDAO = new ConfigInteger("widthYXiaodao", 7, 1, 100, "Y县道宽度");
        public static final ConfigInteger WIDTH_C_CUNDAO = new ConfigInteger("widthCCundao", 3, 1, 100, "C村道宽度");

        public static final ConfigString RDP_EPSILON_FORMULA = new ConfigString("rdpEpsilonFormula", "[RW]/2",
            "RDP简化容差公式，[RW]=道路宽度 [DW]=等级默认宽度，支持 + - * / 运算，如 [RW]/2 或 [RW]*2");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(USE_CLASSIFICATION_WIDTH,
            DEFAULT_WIDTH, DEFAULT_CLASSIFICATION, RDP_EPSILON_FORMULA, WIDTH_G_GUODAO, WIDTH_G_GAOSU, WIDTH_S_SHENGDAO,
            WIDTH_S_GAOJIA, WIDTH_X_XIANGDAO, WIDTH_Y_XIANDAO, WIDTH_C_CUNDAO);

        public static LinkedHashMap<String, Integer> getClassificationWidths() {
            LinkedHashMap<String, Integer> map = new LinkedHashMap<>();
            map.put("G国道", WIDTH_G_GUODAO.getIntegerValue());
            map.put("G高速", WIDTH_G_GAOSU.getIntegerValue());
            map.put("S省道", WIDTH_S_SHENGDAO.getIntegerValue());
            map.put("S高架", WIDTH_S_GAOJIA.getIntegerValue());
            map.put("X乡道", WIDTH_X_XIANGDAO.getIntegerValue());
            map.put("Y县道", WIDTH_Y_XIANDAO.getIntegerValue());
            map.put("C村道", WIDTH_C_CUNDAO.getIntegerValue());
            return map;
        }
    }

    public static List<IConfigBase> getAllConfigs() {
        return ImmutableList.<IConfigBase>builder().addAll(Generic.OPTIONS).addAll(WayfarerHotkeys.HOTKEY_LIST).build();
    }
}
