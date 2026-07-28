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
package com.ecjkim.wayfarer.client.render;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 26.1.1 stub: fabric-rendering-v1 23.0.4 does not have WorldRenderEvents. 3D node indicator rendering and Ctrl+scroll
 * are handled by WayfarerClient.
 */
public final class NodeIndicatorRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wayfarer|NodeIndicator");

    private NodeIndicatorRenderer() {}

    public static void register() {
        LOGGER.info("NodeIndicatorRenderer (26.1.1 stub) registered");
    }
}
