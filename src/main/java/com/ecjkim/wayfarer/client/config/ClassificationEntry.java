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

import fi.dy.masa.malilib.config.IConfigOptionListEntry;

public enum ClassificationEntry implements IConfigOptionListEntry {
    NONE("", "\u9053\u8def\u5206\u7ea7"), G_GUODAO("G\u56fd\u9053"), G_GAOSU("G\u9ad8\u901f"),
    S_SHENGDAO("S\u7701\u9053"), S_GAOJIA("S\u9ad8\u67b6"), X_XIANGDAO("X\u4e61\u9053"), Y_XIANDAO("Y\u53bf\u9053"),
    C_CUNDAO("C\u6751\u9053");

    private final String configValue;
    private final String displayName;

    ClassificationEntry(String configValue) {
        this.configValue = configValue;
        this.displayName = configValue;
    }

    ClassificationEntry(String configValue, String displayName) {
        this.configValue = configValue;
        this.displayName = displayName;
    }

    @Override
    public String getStringValue() {
        return configValue;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public IConfigOptionListEntry cycle(boolean forward) {
        int idx = ordinal();
        int size = values().length;
        int next = forward ? (idx + 1) % size : (idx - 1 + size) % size;
        return values()[next];
    }

    @Override
    public IConfigOptionListEntry fromString(String value) {
        for (ClassificationEntry entry : values()) {
            if (entry.configValue.equals(value)) {
                return entry;
            }
        }
        return NONE;
    }
}
