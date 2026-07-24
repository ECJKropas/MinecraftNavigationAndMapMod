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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Node;
import com.ecjkim.wayfarer.client.road.model.Road;
import com.ecjkim.wayfarer.client.road.model.Segment;

import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders Wayfarer road network as an overlay on Xaero World Map when the GuiMap screen is open.
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
                ScreenEvents.afterRender(screen).register(XaeroMapOverlay::onAfterScreenRender);
            }
        });
        LOGGER.info("XaeroMapOverlay registered");
    }

    private static void onAfterScreenRender(Screen screen, GuiGraphics graphics, int mouseX, int mouseY,
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

    private static void renderRoadNetwork(GuiGraphics graphics, RoadNetworkDatabase db, Collection<Road> roads,
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

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();

        for (Road road : roads) {
            List<Segment> segments = db.getSegmentsForRoad(road.getId());
            if (segments.isEmpty())
                continue;

            int color = classificationColor(road.getClassification());
            float lineWidth = classificationLineWidth(road.getClassification());

            for (Segment segment : segments) {
                List<Node> nodes = db.getNodesForSegment(segment.getId());
                if (nodes == null || nodes.size() < 2)
                    continue;

                if (!isSegmentVisible(nodes, minWorldX, maxWorldX, minWorldZ, maxWorldZ))
                    continue;

                renderSegment(graphics, nodes, effectiveScale, cameraX, cameraZ, centerX, centerY, color, lineWidth);
            }
        }

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
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

    private static void renderSegment(GuiGraphics graphics, List<Node> nodes, double effectiveScale, double cameraX,
        double cameraZ, double centerX, double centerY, int color, float lineWidth) {

        // Use identity matrix: we already compute screen-space coordinates manually.
        // graphics.pose() may carry residual transformations from Xaero's render pass,
        // causing the overlay to shake instead of being locked to the map.
        Matrix4f matrix = new Matrix4f();
        float half = Math.max(0.5f, (float)(lineWidth * effectiveScale / 2.0));

        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float a = 0.85f;

        BufferBuilder builder = new BufferBuilder(2048);
        builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        for (int i = 0; i < nodes.size() - 1; i++) {
            Node n1 = nodes.get(i);
            Node n2 = nodes.get(i + 1);

            float sx1 = (float)((n1.getX() - cameraX) * effectiveScale + centerX);
            float sy1 = (float)((n1.getZ() - cameraZ) * effectiveScale + centerY);
            float sx2 = (float)((n2.getX() - cameraX) * effectiveScale + centerX);
            float sy2 = (float)((n2.getZ() - cameraZ) * effectiveScale + centerY);

            float dx = sx2 - sx1;
            float dy = sy2 - sy1;
            float len = (float)Math.sqrt(dx * dx + dy * dy);
            if (len < 0.001f)
                continue;
            float px = -dy / len * half;
            float py = dx / len * half;

            // Two triangles forming a thick quad
            builder.vertex(matrix, sx1 - px, sy1 - py, 0.0f).color(r, g, b, a).endVertex();
            builder.vertex(matrix, sx1 + px, sy1 + py, 0.0f).color(r, g, b, a).endVertex();
            builder.vertex(matrix, sx2 + px, sy2 + py, 0.0f).color(r, g, b, a).endVertex();
            builder.vertex(matrix, sx1 - px, sy1 - py, 0.0f).color(r, g, b, a).endVertex();
            builder.vertex(matrix, sx2 + px, sy2 + py, 0.0f).color(r, g, b, a).endVertex();
            builder.vertex(matrix, sx2 - px, sy2 - py, 0.0f).color(r, g, b, a).endVertex();
        }

        BufferUploader.drawWithShader(builder.end());
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

    private static float classificationLineWidth(String classification) {
        if (classification == null || classification.isEmpty())
            return 3.0f;
        return classification.charAt(0) == 'G' ? 6.0f : (classification.charAt(0) == 'S' ? 4.5f : 3.0f);
    }
}
