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
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
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

    private static final BlockState END_ROD_STATE =
        Blocks.END_ROD.defaultBlockState().setValue(DirectionalBlock.FACING, Direction.UP);

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

        BlockRenderDispatcher dispatcher = client.getBlockRenderer();
        MultiBufferSource.BufferSource bufferSource = client.renderBuffers().bufferSource();

        for (Node node : allNodes) {
            int bx = (int)Math.floor(node.getX());
            int by = (int)Math.floor(node.getY());
            int bz = (int)Math.floor(node.getZ());
            BlockPos pos = new BlockPos(bx, by, bz);

            if (!seen.add(pos))
                continue;
            if (client.level == null || !client.level.isLoaded(pos))
                continue;

            PoseStack poseStack = new PoseStack();
            poseStack.pushPose();
            poseStack.translate(bx, by + 1, bz);

            dispatcher.renderSingleBlock(END_ROD_STATE, poseStack, bufferSource, 0xF000F0, OverlayTexture.NO_OVERLAY);

            poseStack.popPose();
        }

        bufferSource.endBatch();
    }
}