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

import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

import com.ecjkim.wayfarer.client.ToolItemManager;
import com.ecjkim.wayfarer.client.WayfarerConfig;
import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Node;

import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders End Rod markers above road network nodes when the player holds the Survey tool item.
 *
 * <p>Uses the WorldRenderContext matrix for proper camera-relative transformation,
 * ensuring markers stay anchored to world positions regardless of camera rotation.
 */
public final class NodeIndicatorRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wayfarer|NodeIndicator");

    private static final float BASE_HEIGHT = 0.18f;
    private static final float BASE_HALF_WIDTH = 0.09f;
    private static final float ROD_HALF_WIDTH = 0.04f;

    private NodeIndicatorRenderer() {}

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(NodeIndicatorRenderer::onRender);
        LOGGER.info("NodeIndicatorRenderer registered");
    }

    private static void onRender(WorldRenderContext ctx) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null)
            return;

        WayfarerConfig config = WayfarerConfig.getInstance();
        if (!config.isNodeIndicatorEnabled() || !config.isToolItemEnabled())
            return;
        if (!ToolItemManager.hasToolItem(client.player))
            return;

        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        java.util.Collection<Node> allNodes = db.getAllNodes();
        if (allNodes.isEmpty())
            return;

        Set<BlockPos> seen = new HashSet<>();

        float rodHeight = (float)config.getNodeIndicatorBeamHeight();
        float rodAlpha = (float)config.getNodeIndicatorBeamAlpha();

        Matrix4f viewMatrix = RenderSystem.getModelViewMatrix();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        for (Node node : allNodes) {
            int bx = (int)Math.floor(node.getX());
            int by = (int)Math.floor(node.getY());
            int bz = (int)Math.floor(node.getZ());
            BlockPos pos = new BlockPos(bx, by, bz);

            if (!seen.add(pos))
                continue;
            if (client.level == null || !client.level.isLoaded(pos))
                continue;

            int segmentCount = db.getSegmentCountForNode(node.getId());
            int color = nodeColor(segmentCount);
            float r = ((color >> 16) & 0xFF) / 255.0f;
            float g = ((color >> 8) & 0xFF) / 255.0f;
            float b = (color & 0xFF) / 255.0f;

            double cx = bx + 0.5;
            double cy = by + 0.5;
            double cz = bz + 0.5;

            renderEndRodMarker(viewMatrix, cx, cy, cz, rodHeight, rodAlpha, r, g, b);
        }

        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    private static void renderEndRodMarker(Matrix4f viewMatrix, double cx, double cy, double cz, float rodHeight,
        float alpha, float r, float g, float b) {

        // Draw the End Rod base (small cube at bottom)
        renderBox(viewMatrix, cx, cy, cz, BASE_HEIGHT, BASE_HALF_WIDTH, r, g, b, alpha);

        // Draw the rod (thin beam extending upward)
        float rodBaseY = (float)cy + BASE_HEIGHT;
        renderBeam(viewMatrix, cx, rodBaseY, cz, rodHeight, ROD_HALF_WIDTH, r, g, b, alpha);
    }

    private static void renderBox(Matrix4f viewMatrix, double cx, double cy, double cz, float height, float hw,
        float r, float g, float b, float alpha) {

        Matrix4f matrix = new Matrix4f(viewMatrix);
        matrix.translate((float)cx, (float)cy, (float)cz);

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder builder = tess.getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float topY = height;

        // +X face
        builder.vertex(matrix, +hw, 0, -hw).color(r, g, b, alpha).endVertex();
        builder.vertex(matrix, +hw, topY, -hw).color(r, g, b, alpha).endVertex();
        builder.vertex(matrix, +hw, topY, +hw).color(r, g, b, alpha).endVertex();
        builder.vertex(matrix, +hw, 0, +hw).color(r, g, b, alpha).endVertex();
        // -X face
        builder.vertex(matrix, -hw, 0, +hw).color(r, g, b, alpha).endVertex();
        builder.vertex(matrix, -hw, topY, +hw).color(r, g, b, alpha).endVertex();
        builder.vertex(matrix, -hw, topY, -hw).color(r, g, b, alpha).endVertex();
        builder.vertex(matrix, -hw, 0, -hw).color(r, g, b, alpha).endVertex();
        // +Z face
        builder.vertex(matrix, -hw, 0, +hw).color(r, g, b, alpha).endVertex();
        builder.vertex(matrix, -hw, topY, +hw).color(r, g, b, alpha).endVertex();
        builder.vertex(matrix, +hw, topY, +hw).color(r, g, b, alpha).endVertex();
        builder.vertex(matrix, +hw, 0, +hw).color(r, g, b, alpha).endVertex();
        // -Z face
        builder.vertex(matrix, +hw, 0, -hw).color(r, g, b, alpha).endVertex();
        builder.vertex(matrix, +hw, topY, -hw).color(r, g, b, alpha).endVertex();
        builder.vertex(matrix, -hw, topY, -hw).color(r, g, b, alpha).endVertex();
        builder.vertex(matrix, -hw, 0, -hw).color(r, g, b, alpha).endVertex();
        // Top face
        builder.vertex(matrix, -hw, topY, -hw).color(r, g, b, alpha).endVertex();
        builder.vertex(matrix, -hw, topY, +hw).color(r, g, b, alpha).endVertex();
        builder.vertex(matrix, +hw, topY, +hw).color(r, g, b, alpha).endVertex();
        builder.vertex(matrix, +hw, topY, -hw).color(r, g, b, alpha).endVertex();
        // Bottom face
        builder.vertex(matrix, -hw, 0, -hw).color(r, g, b, alpha).endVertex();
        builder.vertex(matrix, +hw, 0, -hw).color(r, g, b, alpha).endVertex();
        builder.vertex(matrix, +hw, 0, +hw).color(r, g, b, alpha).endVertex();
        builder.vertex(matrix, -hw, 0, +hw).color(r, g, b, alpha).endVertex();

        BufferUploader.drawWithShader(builder.end());
    }

    private static void renderBeam(Matrix4f viewMatrix, double cx, double cy, double cz, float height, float hw,
        float r, float g, float b, float alpha) {

        Matrix4f matrix = new Matrix4f(viewMatrix);
        matrix.translate((float)cx, (float)cy, (float)cz);

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder builder = tess.getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float topAlpha = alpha * 0.2f;

        // +X face
        builder.vertex(matrix, +hw, 0, -hw).color(r, g, b, alpha).endVertex();
        builder.vertex(matrix, +hw, height, -hw).color(r, g, b, topAlpha).endVertex();
        builder.vertex(matrix, +hw, height, +hw).color(r, g, b, topAlpha).endVertex();
        builder.vertex(matrix, +hw, 0, +hw).color(r, g, b, alpha).endVertex();
        // -X face
        builder.vertex(matrix, -hw, 0, +hw).color(r, g, b, alpha).endVertex();
        builder.vertex(matrix, -hw, height, +hw).color(r, g, b, topAlpha).endVertex();
        builder.vertex(matrix, -hw, height, -hw).color(r, g, b, topAlpha).endVertex();
        builder.vertex(matrix, -hw, 0, -hw).color(r, g, b, alpha).endVertex();
        // +Z face
        builder.vertex(matrix, -hw, 0, +hw).color(r, g, b, alpha).endVertex();
        builder.vertex(matrix, -hw, height, +hw).color(r, g, b, topAlpha).endVertex();
        builder.vertex(matrix, +hw, height, +hw).color(r, g, b, topAlpha).endVertex();
        builder.vertex(matrix, +hw, 0, +hw).color(r, g, b, alpha).endVertex();
        // -Z face
        builder.vertex(matrix, +hw, 0, -hw).color(r, g, b, alpha).endVertex();
        builder.vertex(matrix, +hw, height, -hw).color(r, g, b, topAlpha).endVertex();
        builder.vertex(matrix, -hw, height, -hw).color(r, g, b, topAlpha).endVertex();
        builder.vertex(matrix, -hw, 0, -hw).color(r, g, b, alpha).endVertex();

        BufferUploader.drawWithShader(builder.end());
    }

    private static int nodeColor(int segmentCount) {
        if (segmentCount >= 3)
            return 0xFFFFD700;
        if (segmentCount == 0)
            return 0xFFFF4444;
        return 0xFFFFFFFF;
    }
}
