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
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import com.mojang.blaze3d.vertex.PoseStack;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

import com.ecjkim.wayfarer.client.ToolItemManager;
import com.ecjkim.wayfarer.client.WayfarerConfig;
import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Node;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NodeIndicatorRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wayfarer|NodeIndicator");

    private static final BlockState END_ROD_STATE = Blocks.END_ROD.defaultBlockState();

    private NodeIndicatorRenderer() {}

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(NodeIndicatorRenderer::onRender);
        LOGGER.info("NodeIndicatorRenderer registered");
    }

    private static long lastLogTime = 0;
    private static int lastNodeCount = -1;
    private static int debugLogCount = 0;

    private static void onRender(WorldRenderContext ctx) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null)
            return;

        if (debugLogCount < 5) {
            debugLogCount++;
            LOGGER.info("NodeIndicatorRenderer: onRender called, player={}, level={}",
                client.player.getName().getString(), client.level.dimension().location());
        }

        WayfarerConfig config = WayfarerConfig.getInstance();
        boolean nodeIndicatorEnabled = config.isNodeIndicatorEnabled();
        boolean toolItemEnabled = config.isToolItemEnabled();
        if (!nodeIndicatorEnabled || !toolItemEnabled) {
            logOnce("NodeIndicatorRenderer: Skipping: nodeIndicator={} toolItem={}", nodeIndicatorEnabled,
                toolItemEnabled);
            return;
        }

        boolean hasTool = ToolItemManager.hasToolItem(client.player);
        if (!hasTool) {
            logOnce("NodeIndicatorRenderer: Skipping: no tool item held");
            return;
        }

        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        java.util.Collection<Node> allNodes = db.getAllNodes();
        if (allNodes.isEmpty()) {
            logOnce("NodeIndicatorRenderer: Skipping: no nodes in database");
            return;
        }

        long now = System.currentTimeMillis();
        boolean shouldLog = allNodes.size() != lastNodeCount || now - lastLogTime > 5000;

        Set<BlockPos> seen = new HashSet<>();

        BlockRenderDispatcher dispatcher = client.getBlockRenderer();

        MultiBufferSource.BufferSource bufferSource = client.renderBuffers().bufferSource();

        if (shouldLog) {
            lastNodeCount = allNodes.size();
            lastLogTime = now;
            LOGGER.info("NodeIndicatorRenderer: rendering {} nodes, bufferSource={}", allNodes.size(),
                bufferSource.getClass().getName());
        }

        // Use the context's matrix stack which already has camera transform
        PoseStack poseStack = ctx.matrixStack();

        var camera = ctx.camera();
        double camX = camera.getPosition().x;
        double camY = camera.getPosition().y;
        double camZ = camera.getPosition().z;

        int rendered = 0;
        for (Node node : allNodes) {
            int bx = (int)Math.floor(node.getX());
            int by = (int)Math.floor(node.getY());
            int bz = (int)Math.floor(node.getZ());
            BlockPos pos = new BlockPos(bx, by, bz);

            if (!seen.add(pos))
                continue;
            if (!client.level.isLoaded(pos))
                continue;

            poseStack.pushPose();
            // Camera-relative translation on top of the game's view matrix
            poseStack.translate(bx - camX, by + 1 - camY, bz - camZ);

            dispatcher.renderSingleBlock(END_ROD_STATE, poseStack, bufferSource, LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY);

            poseStack.popPose();
            rendered++;
        }

        bufferSource.endBatch();

        if (shouldLog) {
            LOGGER.info("NodeIndicatorRenderer: rendered {} blocks", rendered);
        }
    }

    private static void logOnce(String format, Object... args) {
        long now = System.currentTimeMillis();
        if (now - lastLogTime > 2000) {
            lastLogTime = now;
            LOGGER.info(format, args);
        }
    }
}
