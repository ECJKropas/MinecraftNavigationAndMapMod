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
import java.util.Collection;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import com.mojang.blaze3d.platform.Window;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Node;
import com.ecjkim.wayfarer.client.road.model.Road;
import com.ecjkim.wayfarer.client.road.model.Segment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 26.x version: renders road network overlay on Xaero World Map GuiMap. Uses RoadNetworkDatabase for data and
 * GuiGraphicsExtractor.fill() for rendering.
 */
public final class XaeroMapOverlay {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wayfarer|Overlay");
    private static final String GUIMAP_CLASS = "xaero.map.gui.GuiMap";

    private static Field scaleField;
    private static Field cameraXField;
    private static Field cameraZField;
    private static int reflectionFailCount;
    private static double guiScale = 2.0;

    private XaeroMapOverlay() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (isGuiMap(screen)) {
                Window win = Minecraft.getInstance().getWindow();
                guiScale = (double)win.getScreenWidth() / win.getGuiScaledWidth();
                ScreenEvents.afterExtract(screen).register(XaeroMapOverlay::onAfterScreenRender);
            }
        });
        LOGGER.info("XaeroMapOverlay registered (26.x)");
    }

    private static void onAfterScreenRender(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY,
        float tickDelta) {
        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        Collection<Road> roads = db.getRoads();
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

            renderRoadNetwork(graphics, db, roads, scale, cameraX, cameraZ, screenW, screenH);
        } catch (Exception e) {
            if (reflectionFailCount < 3) {
                LOGGER.warn("Failed to render road overlay: {}", e.getMessage());
                reflectionFailCount++;
            }
        }
    }

    private static boolean isGuiMap(Screen screen) {
        if (screen == null)
            return false;
        return GUIMAP_CLASS.equals(screen.getClass().getName());
    }

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

    private static void renderRoadNetwork(GuiGraphicsExtractor graphics, RoadNetworkDatabase db, Collection<Road> roads,
        double scale, double cameraX, double cameraZ, int screenW, int screenH) {

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

        for (Road road : roads) {
            String classification = road.getClassification();
            int color = classificationColor(classification);

            List<Segment> segments = db.getSegmentsForRoad(road.getId());
            for (Segment seg : segments) {
                List<Node> nodes = db.getNodesForSegment(seg.getId());
                if (nodes.size() < 2)
                    continue;

                if (!isSegmentVisible(nodes, minWorldX, maxWorldX, minWorldZ, maxWorldZ))
                    continue;

                int lineWidth = 40;
                if ("G".equals(classification))
                    lineWidth = 80;
                else if ("S".equals(classification))
                    lineWidth = 60;

                renderSegment(graphics, nodes, effectiveScale, cameraX, cameraZ, centerX, centerY, color, lineWidth);
            }
        }
    }

    private static boolean isSegmentVisible(List<Node> nodes, double minX, double maxX, double minZ, double maxZ) {
        double segMinX = Double.MAX_VALUE, segMaxX = -Double.MAX_VALUE;
        double segMinZ = Double.MAX_VALUE, segMaxZ = -Double.MAX_VALUE;

        for (Node n : nodes) {
            if (n.getX() < segMinX)
                segMinX = n.getX();
            if (n.getX() > segMaxX)
                segMaxX = n.getX();
            if (n.getZ() < segMinZ)
                segMinZ = n.getZ();
            if (n.getZ() > segMaxZ)
                segMaxZ = n.getZ();
        }

        return !(segMaxX < minX || segMinX > maxX || segMaxZ < minZ || segMinZ > maxZ);
    }

    private static void renderSegment(GuiGraphicsExtractor graphics, List<Node> nodes, double effectiveScale,
        double cameraX, double cameraZ, double centerX, double centerY, int color, int thickness) {

        for (int i = 0; i < nodes.size() - 1; i++) {
            Node n1 = nodes.get(i);
            Node n2 = nodes.get(i + 1);

            int sx1 = (int)((n1.getX() - cameraX) * effectiveScale + centerX);
            int sy1 = (int)((n1.getZ() - cameraZ) * effectiveScale + centerY);
            int sx2 = (int)((n2.getX() - cameraX) * effectiveScale + centerX);
            int sy2 = (int)((n2.getZ() - cameraZ) * effectiveScale + centerY);

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

    private static int classificationColor(String classification) {
        if (classification == null || classification.isEmpty())
            return 0xFFFFFFFF;
        switch (classification.charAt(0)) {
            case 'G':
                return 0xFFFF8800;
            case 'S':
                return 0xFFFFFF00;
            case 'X':
                return 0xFF00FF00;
            case 'Y':
                return 0xFF4488FF;
            case 'C':
                return 0xFF888888;
            default:
                return 0xFFFFFFFF;
        }
    }
}
