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
 * Renders translucent beams above road network nodes when the player holds the Survey tool item.
 */
public final class NodeIndicatorRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wayfarer|NodeIndicator");

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

        double camX = ctx.camera().getPosition().x;
        double camY = ctx.camera().getPosition().y;
        double camZ = ctx.camera().getPosition().z;

        float beamHeight = (float)config.getNodeIndicatorBeamHeight();
        float beamAlpha = (float)config.getNodeIndicatorBeamAlpha();

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

            renderBeam(bx + 0.5, by + 1.0, bz + 0.5, beamHeight, beamAlpha, r, g, b, camX, camY, camZ);
        }

        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    private static void renderBeam(double cx, double cy, double cz, float height, float alpha, float r, float g,
        float b, double camX, double camY, double camZ) {

        float hw = 0.15f;

        Matrix4f matrix = new Matrix4f();
        matrix.translate((float)(cx - camX), (float)(cy - camY), (float)(cz - camZ));

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder builder = tess.getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float topAlpha = alpha * 0.3f;

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
