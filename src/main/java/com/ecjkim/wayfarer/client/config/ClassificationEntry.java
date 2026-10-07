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

import net.minecraft.client.resources.language.I18n;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;

public enum ClassificationEntry implements IConfigOptionListEntry {
    // The config values are the strings persisted in saved road data and in the malilib config file,
    // so they must stay byte-for-byte identical; only the display name goes through i18n.
    NONE("", "classification.default"), G_GUODAO("G\u56fd\u9053", "classification.national"),
    G_GAOSU("G\u9ad8\u901f", "classification.highway"), S_SHENGDAO("S\u7701\u9053", "classification.provincial"),
    S_GAOJIA("S\u9ad8\u67b6", "classification.elevated"), X_XIANGDAO("X\u4e61\u9053", "classification.township"),
    Y_XIANDAO("Y\u53bf\u9053", "classification.county"), C_CUNDAO("C\u6751\u9053", "classification.village");

    private final String configValue;
    private final String displayNameKey;

    ClassificationEntry(String configValue, String displayNameKey) {
        this.configValue = configValue;
        this.displayNameKey = displayNameKey;
    }

    @Override
    public String getStringValue() {
        return configValue;
    }

    @Override
    public String getDisplayName() {
        return I18n.get(displayNameKey);
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
