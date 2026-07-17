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

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

import com.ecjkim.wayfarer.client.WayfarerClient;
import com.ecjkim.wayfarer.client.road.model.RoadPath;
import com.ecjkim.wayfarer.client.road.model.RoadPoint;

import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders Wayfarer road network as an overlay on Xaero World Map when the GuiMap screen is open. Uses Fabric
 * ScreenEvents to detect GuiMap open and hook its per-screen afterRender phase, avoiding Mixin injection into Xaero's
 * internal rendering pipeline.
 */
public final class XaeroMapOverlay {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wayfarer|Overlay");
    private static final String GUIMAP_CLASS = "xaero.map.gui.GuiMap";

    private static Field scaleField;
    private static Field cameraXField;
    private static Field cameraZField;
    private static int reflectionFailCount;

    private XaeroMapOverlay() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (isGuiMap(screen)) {
                ScreenEvents.afterRender(screen).register(XaeroMapOverlay::onAfterScreenRender);
            }
        });
        LOGGER.info("XaeroMapOverlay registered");
    }

    private static void onAfterScreenRender(Screen screen, GuiGraphics graphics, int mouseX, int mouseY,
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

    private static void renderRoadNetwork(GuiGraphics graphics, List<RoadPath> roads, double scale, double cameraX,
        double cameraZ, int screenW, int screenH) {

        double centerX = screenW / 2.0;
        double centerY = screenH / 2.0;

        double halfWorldW = screenW / (2.0 * scale);
        double halfWorldH = screenH / (2.0 * scale);
        double margin = 50.0;
        double minWorldX = cameraX - halfWorldW - margin;
        double maxWorldX = cameraX + halfWorldW + margin;
        double minWorldZ = cameraZ - halfWorldH - margin;
        double maxWorldZ = cameraZ + halfWorldH + margin;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();

        for (RoadPath road : roads) {
            if (!isRoadVisible(road, minWorldX, maxWorldX, minWorldZ, maxWorldZ))
                continue;

            int color = classificationColor(road.classification);
            renderRoad(graphics, road, scale, cameraX, cameraZ, centerX, centerY, color);
        }

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
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

    private static void renderRoad(GuiGraphics graphics, RoadPath road, double scale, double cameraX, double cameraZ,
        double centerX, double centerY, int color) {

        List<RoadPoint> points = road.points;
        if (points.size() < 2)
            return;

        Matrix4f matrix = graphics.pose().last().pose();

        float lineWidth = "G".equals(road.classification) ? 4.0f : ("S".equals(road.classification) ? 3.0f : 2.0f);

        BufferBuilder builder = new BufferBuilder(256);

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.lineWidth(lineWidth);

        builder.begin(VertexFormat.Mode.DEBUG_LINE_STRIP, DefaultVertexFormat.POSITION_COLOR);

        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float a = 0.85f;

        for (RoadPoint pt : points) {
            float sx = (float)((pt.x - cameraX) * scale + centerX);
            float sy = (float)((pt.z - cameraZ) * scale + centerY);
            builder.vertex(matrix, sx, sy, 0.0f).color(r, g, b, a).endVertex();
        }

        BufferUploader.drawWithShader(builder.end());
        RenderSystem.lineWidth(1.0f);
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
