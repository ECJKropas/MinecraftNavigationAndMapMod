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

import java.io.File;

import com.ecjkim.wayfarer.client.config.WayfarerConfigs;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.interfaces.IInitializationHandler;
import fi.dy.masa.malilib.util.FileUtils;

public class WayfarerInitHandler implements IInitializationHandler {
    private static final String CONFIG_FILE_NAME = "wayfarer.json";

    @Override
    public void registerModHandlers() {
        ConfigManager.getInstance().registerConfigHandler(Reference.MOD_ID, new WayfarerConfigHandler());
    }

    public static class WayfarerConfigHandler implements IConfigHandler {
        private final File configFile = new File(FileUtils.getConfigDirectory(), CONFIG_FILE_NAME);

        @Override
        public void load() {
            if (!this.configFile.exists()) {
                return;
            }
            try {
                JsonElement element = JsonParser.parseReader(new java.io.FileReader(this.configFile));
                if (!element.isJsonObject()) {
                    return;
                }
                JsonObject root = element.getAsJsonObject();
                for (IConfigBase cfg : WayfarerConfigs.getAllConfigs()) {
                    if (root.has(cfg.getName())) {
                        cfg.setValueFromJsonElement(root.get(cfg.getName()));
                    }
                }
            } catch (Exception e) {
                // ignore parse errors, keep defaults
            }
        }

        @Override
        public void save() {
            try {
                if (!this.configFile.getParentFile().exists()) {
                    this.configFile.getParentFile().mkdirs();
                }
                JsonObject root = new JsonObject();
                for (IConfigBase cfg : WayfarerConfigs.getAllConfigs()) {
                    root.add(cfg.getName(), cfg.getAsJsonElement());
                }
                java.nio.file.Files.write(this.configFile.toPath(),
                    root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                // ignore write errors
            }
        }
    }
}
