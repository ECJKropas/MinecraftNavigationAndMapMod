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
 * 26.x stub: fabric-rendering-v1 25.x removed WorldRenderEvents. 3D node indicator rendering is not available for 26.x.
 */
public final class NodeIndicatorRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wayfarer|NodeIndicator");

    private NodeIndicatorRenderer() {}

    public static void register() {
        LOGGER.info("NodeIndicatorRenderer (26.x stub) registered");
    }
}
