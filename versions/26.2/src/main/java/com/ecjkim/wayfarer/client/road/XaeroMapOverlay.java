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
package com.ecjkim.wayfarer.client.road;

import java.lang.reflect.Field;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import com.mojang.blaze3d.platform.Window;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

import com.ecjkim.wayfarer.client.WayfarerClient;
import com.ecjkim.wayfarer.client.road.model.RoadPath;
import com.ecjkim.wayfarer.client.road.model.RoadPoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 26.x version: renders road network overlay on Xaero World Map GuiMap. Uses {@code GuiGraphicsExtractor.fill()} for
 * rendering to avoid the 26.x RenderSystem API breakage (enableBlend/lineWidth removed).
 */
public final class XaeroMapOverlay {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wayfarer|Overlay");
    private static final String GUIMAP_CLASS = "xaero.map.gui.GuiMap";

    private static Field scaleField;
    private static Field cameraXField;
    private static Field cameraZField;
    private static int reflectionFailCount;
    /** Xaero scale is in physical pixels/block; divide by guiScale for GUI-scaled coords */
    private static double guiScale = 2.0;

    private XaeroMapOverlay() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (isGuiMap(screen)) {
                // Compute guiScale once when GuiMap opens (Xaero scale is in physical px/block)
                Window win = Minecraft.getInstance().getWindow();
                guiScale = (double)win.getScreenWidth() / win.getGuiScaledWidth();
                ScreenEvents.afterExtract(screen).register(XaeroMapOverlay::onAfterScreenRender);
            }
        });
        LOGGER.info("XaeroMapOverlay registered (26.x)");
    }

    private static void onAfterScreenRender(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY,
        float tickDelta) {
        RoadDataStore store = WayfarerClient.getRoadDataStore();
        if (store == null)
            return;

        List<RoadPath> roads = store.getRoads();
        if (roads.isEmpty())
            return;

        try {
            double[] mapParams = getMapParams(screen);
            if (mapParams == null)
                return;

            double scale = mapParams[0];
            double cameraX = mapParams[1];
            double cameraZ = mapParams[2];

            int screenW = screen.width;
            int screenH = screen.height;

            renderRoadNetwork(graphics, roads, scale, cameraX, cameraZ, screenW, screenH);
        } catch (Exception e) {
            if (reflectionFailCount < 3) {
                LOGGER.warn("Failed to render road overlay: {}", e.getMessage());
                reflectionFailCount++;
            }
        }
    }

    // ------------------------------------------------------------------
    // GuiMap detection
    // ------------------------------------------------------------------

    private static boolean isGuiMap(Screen screen) {
        if (screen == null)
            return false;
        return GUIMAP_CLASS.equals(screen.getClass().getName());
    }

    // ------------------------------------------------------------------
    // Reflection helpers
    // ------------------------------------------------------------------

    private static double[] getMapParams(Screen screen) {
        try {
            if (scaleField == null) {
                scaleField = screen.getClass().getDeclaredField("scale");
                scaleField.setAccessible(true);
                cameraXField = screen.getClass().getDeclaredField("cameraX");
                cameraXField.setAccessible(true);
                cameraZField = screen.getClass().getDeclaredField("cameraZ");
                cameraZField.setAccessible(true);
            }

            double scale = scaleField.getDouble(screen);
            double cameraX = cameraXField.getDouble(screen);
            double cameraZ = cameraZField.getDouble(screen);

            return new double[] {scale, cameraX, cameraZ};
        } catch (NoSuchFieldException e) {
            LOGGER.warn("GuiMap field not found: {}", e.getMessage());
            return null;
        } catch (IllegalAccessException e) {
            LOGGER.warn("Cannot access GuiMap field: {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Road network rendering
    // ------------------------------------------------------------------

    private static void renderRoadNetwork(GuiGraphicsExtractor graphics, List<RoadPath> roads, double scale,
        double cameraX, double cameraZ, int screenW, int screenH) {

        // Xaero scale is in physical px/block. GuiMap renders to an FBO with a
        // 1/guiScale downscale, then blits 1:1 to screen (physical px). Since
        // we draw directly to the GUI layer, we must divide by guiScale twice:
        // once for FBO downscale, once for physical→GUI coordinate conversion.
        double effectiveScale = scale / (guiScale * guiScale);
        double centerX = screenW / 2.0;
        double centerY = screenH / 2.0;

        double halfWorldW = screenW / (2.0 * effectiveScale);
        double halfWorldH = screenH / (2.0 * effectiveScale);
        double margin = 50.0 / effectiveScale;
        double minWorldX = cameraX - halfWorldW - margin;
        double maxWorldX = cameraX + halfWorldW + margin;
        double minWorldZ = cameraZ - halfWorldH - margin;
        double maxWorldZ = cameraZ + halfWorldH + margin;

        for (RoadPath road : roads) {
            if (!isRoadVisible(road, minWorldX, maxWorldX, minWorldZ, maxWorldZ))
                continue;

            int lineWidth = "G".equals(road.classification) ? 3 : ("S".equals(road.classification) ? 2 : 1);
            int color = classificationColor(road.classification);
            renderRoad(graphics, road, effectiveScale, cameraX, cameraZ, centerX, centerY, color, lineWidth);
        }
    }

    private static boolean isRoadVisible(RoadPath road, double minX, double maxX, double minZ, double maxZ) {
        if (road.points == null || road.points.size() < 2)
            return false;

        double roadMinX = Double.MAX_VALUE, roadMaxX = -Double.MAX_VALUE;
        double roadMinZ = Double.MAX_VALUE, roadMaxZ = -Double.MAX_VALUE;

        for (RoadPoint pt : road.points) {
            if (pt.x < roadMinX)
                roadMinX = pt.x;
            if (pt.x > roadMaxX)
                roadMaxX = pt.x;
            if (pt.z < roadMinZ)
                roadMinZ = pt.z;
            if (pt.z > roadMaxZ)
                roadMaxZ = pt.z;
        }

        return !(roadMaxX < minX || roadMinX > maxX || roadMaxZ < minZ || roadMinZ > maxZ);
    }

    private static void renderRoad(GuiGraphicsExtractor graphics, RoadPath road, double effectiveScale, double cameraX,
        double cameraZ, double centerX, double centerY, int color, int thickness) {

        List<RoadPoint> points = road.points;
        if (points.size() < 2)
            return;

        for (int i = 0; i < points.size() - 1; i++) {
            RoadPoint p1 = points.get(i);
            RoadPoint p2 = points.get(i + 1);

            int sx1 = (int)((p1.x - cameraX) * effectiveScale + centerX);
            int sy1 = (int)((p1.z - cameraZ) * effectiveScale + centerY);
            int sx2 = (int)((p2.x - cameraX) * effectiveScale + centerX);
            int sy2 = (int)((p2.z - cameraZ) * effectiveScale + centerY);

            drawThickLine(graphics, sx1, sy1, sx2, sy2, color, thickness);
        }
    }

    private static void drawThickLine(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int color,
        int thickness) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int err = dx - dy;
        int half = thickness / 2;

        int cx = x1, cy = y1;
        while (true) {
            graphics.fill(cx - half, cy - half, cx + thickness - half, cy + thickness - half, color);

            if (cx == x2 && cy == y2)
                break;

            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                cx += sx;
            }
            if (e2 < dx) {
                err += dx;
                cy += sy;
            }
        }
    }

    // ------------------------------------------------------------------
    // Color mapping
    // ------------------------------------------------------------------

    private static int classificationColor(String classification) {
        if (classification == null)
            return 0xFFa0b0c0;
        return switch (classification) {
            case "G" -> 0xFFD9432B;
            case "S" -> 0xFFF0A030;
            case "X" -> 0xFF5a6a7a;
            case "Y" -> 0xFF8899aa;
            default -> 0xFFa0b0c0;
        };
    }
}
