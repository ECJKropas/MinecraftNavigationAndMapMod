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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

import com.ecjkim.wayfarer.client.ToolItemManager;
import com.ecjkim.wayfarer.client.WayfarerClient;
import com.ecjkim.wayfarer.client.WayfarerConfig;
import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Node;
import com.ecjkim.wayfarer.client.road.record.SurveySession;
import com.ecjkim.wayfarer.client.road.record.SurveySession.State;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders node outlines and path connections for Survey mode.
 *
 * <p>
 * Rendering layers:
 * <ul>
 * <li>Node outlines: Wireframe boxes around nodes, color-coded by state/segment count</li>
 * <li>Path connections: Colored lines between recording nodes (RECORDING state only)</li>
 * <li>Hover highlight: Brighter/bigger outline on the node the player is looking at</li>
 * </ul>
 */
public final class SurveyRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wayfarer|SurveyRender");

    // Color constants (ARGB hex)
    private static final int COLOR_START = 0xFF00FF00; // Green  - first node
    private static final int COLOR_WAYPOINT = 0xFF00BFFF; // Cyan   - intermediate nodes
    private static final int COLOR_END = 0xFFFF4444; // Red    - last node
    private static final int COLOR_HOVER = 0xFFFFFF00; // Yellow - hovered node
    private static final int COLOR_ISOLATED = 0xFFFF8888; // Light red - node with 0 segments
    private static final int COLOR_CONNECTED = 0xFFFFFFFF; // White  - normal node
    private static final int COLOR_HUB = 0xFFFFD700; // Gold   - node with >= 3 segments
    private static final int COLOR_CONNECTION = 0xFFFFFF80; // Light yellow - path lines

    private static final float OUTLINE_SIZE = 1.002f;
    private static final float HOVER_SIZE = 1.06f;
    private static final float OUTLINE_WIDTH = 0.003f;
    private static final float CONNECTION_WIDTH = 2.5f;

    private SurveyRenderer() {}

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(SurveyRenderer::onRender);
        LOGGER.info("SurveyRenderer registered");
    }

    private static void onRender(WorldRenderContext ctx) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }

        WayfarerConfig config = WayfarerConfig.getInstance();
        if (!config.isToolItemEnabled()) {
            return;
        }
        if (!ToolItemManager.hasToolItem(client.player)) {
            return;
        }

        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        SurveySession session = WayfarerClient.getSurveySession();

        Entity camera = ctx.camera().getEntity();
        if (camera == null) {
            return;
        }

        List<Node> allNodes = new ArrayList<>(db.getAllNodes());
        if (allNodes.isEmpty()) {
            return;
        }

        double camX = camera.getX();
        double camY = camera.getY();
        double camZ = camera.getZ();

        UUID hoveredId = findHoveredNode(client);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        try {
            renderNodeOutlines(client, db, allNodes, session, hoveredId, camX, camY, camZ);

            if (session != null && session.getState() == State.RECORDING) {
                renderPathConnections(session, db, camX, camY, camZ);
            }
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        }
    }

    private static void renderNodeOutlines(Minecraft client, RoadNetworkDatabase db, List<Node> allNodes,
        SurveySession session, UUID hoveredId, double camX, double camY, double camZ) {

        RenderSystem.lineWidth(OUTLINE_WIDTH);

        for (Node node : allNodes) {
            int bx = (int)Math.floor(node.getX());
            int by = (int)Math.floor(node.getY());
            int bz = (int)Math.floor(node.getZ());

            BlockPos pos = new BlockPos(bx, by, bz);
            if (!client.level.isLoaded(pos)) {
                continue;
            }

            int color = getNodeColor(node, session, hoveredId);
            float r = ((color >> 16) & 0xFF) / 255.0f;
            float g = ((color >> 8) & 0xFF) / 255.0f;
            float b = (color & 0xFF) / 255.0f;
            float size = node.getId().equals(hoveredId) ? HOVER_SIZE : OUTLINE_SIZE;

            renderBoxOutline(bx + 0.5, by + 0.5, bz + 0.5, size, r, g, b, camX, camY, camZ);
        }
    }

    private static int getNodeColor(Node node, SurveySession session, UUID hoveredId) {
        if (node.getId().equals(hoveredId)) {
            return COLOR_HOVER;
        }

        if (session != null && session.getState() == State.RECORDING) {
            List<UUID> nodeIds = session.getNodeIds();
            int index = nodeIds.indexOf(node.getId());
            if (index == 0) {
                return COLOR_START;
            } else if (index == nodeIds.size() - 1) {
                return COLOR_END;
            } else if (index > 0) {
                return COLOR_WAYPOINT;
            }
        }

        int segmentCount = RoadNetworkDatabase.getInstance().getSegmentCountForNode(node.getId());
        if (segmentCount >= 3) {
            return COLOR_HUB;
        } else if (segmentCount == 0) {
            return COLOR_ISOLATED;
        } else {
            return COLOR_CONNECTED;
        }
    }

    private static UUID findHoveredNode(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            return null;
        }

        Vec3 eyePos = player.getEyePosition(1.0f);
        Vec3 lookVec = player.getViewVector(1.0f);

        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        double maxDist = 50.0;
        double hitRadius = 2.0;
        double closestDistSq = hitRadius * hitRadius;
        UUID closestId = null;

        for (Node node : db.getAllNodes()) {
            Vec3 nodePos = new Vec3(node.getX(), node.getY(), node.getZ());
            Vec3 eyeToNode = nodePos.subtract(eyePos);
            double t = eyeToNode.dot(lookVec);

            if (t < 0 || t > maxDist) {
                continue;
            }

            Vec3 closestPoint = eyePos.add(lookVec.scale(t));
            double distSq = closestPoint.distanceToSqr(nodePos);

            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closestId = node.getId();
            }
        }

        return closestId;
    }

    private static void renderPathConnections(SurveySession session, RoadNetworkDatabase db, double camX, double camY,
        double camZ) {

        List<UUID> nodeIds = session.getNodeIds();
        if (nodeIds.size() < 2) {
            return;
        }

        RenderSystem.lineWidth(CONNECTION_WIDTH);

        float r = ((COLOR_CONNECTION >> 16) & 0xFF) / 255.0f;
        float g = ((COLOR_CONNECTION >> 8) & 0xFF) / 255.0f;
        float b = (COLOR_CONNECTION & 0xFF) / 255.0f;

        for (int i = 0; i < nodeIds.size() - 1; i++) {
            Node a = db.getNode(nodeIds.get(i));
            Node bNode = db.getNode(nodeIds.get(i + 1));
            if (a == null || bNode == null) {
                continue;
            }

            double ax = a.getX() + 0.5 - camX;
            double ay = a.getY() + 0.5 - camY;
            double az = a.getZ() + 0.5 - camZ;
            double bx = bNode.getX() + 0.5 - camX;
            double by = bNode.getY() + 0.5 - camY;
            double bz = bNode.getZ() + 0.5 - camZ;

            renderLine(ax, ay, az, bx, by, bz, r, g, b);
        }

        RenderSystem.lineWidth(OUTLINE_WIDTH);
    }

    private static void renderBoxOutline(double cx, double cy, double cz, float size, float r, float g, float b,
        double camX, double camY, double camZ) {

        float hs = size * 0.5f;
        float minX = (float)(cx - camX) - hs;
        float minY = (float)(cy - camY) - hs;
        float minZ = (float)(cz - camZ) - hs;
        float maxX = (float)(cx - camX) + hs;
        float maxY = (float)(cy - camY) + hs;
        float maxZ = (float)(cz - camZ) + hs;

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder builder = tess.getBuilder();
        builder.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR);

        // Bottom face edges
        addLine(builder, minX, minY, minZ, maxX, minY, minZ, r, g, b);
        addLine(builder, maxX, minY, minZ, maxX, minY, maxZ, r, g, b);
        addLine(builder, maxX, minY, maxZ, minX, minY, maxZ, r, g, b);
        addLine(builder, minX, minY, maxZ, minX, minY, minZ, r, g, b);

        // Top face edges
        addLine(builder, minX, maxY, minZ, maxX, maxY, minZ, r, g, b);
        addLine(builder, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b);
        addLine(builder, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b);
        addLine(builder, minX, maxY, maxZ, minX, maxY, minZ, r, g, b);

        // Vertical edges
        addLine(builder, minX, minY, minZ, minX, maxY, minZ, r, g, b);
        addLine(builder, maxX, minY, minZ, maxX, maxY, minZ, r, g, b);
        addLine(builder, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b);
        addLine(builder, minX, minY, maxZ, minX, maxY, maxZ, r, g, b);

        BufferUploader.drawWithShader(builder.end());
    }

    private static void renderLine(double x1, double y1, double z1, double x2, double y2, double z2, float r, float g,
        float b) {

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder builder = tess.getBuilder();
        builder.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR);

        builder.vertex(x1, y1, z1).color(r, g, b, 1.0f).endVertex();
        builder.vertex(x2, y2, z2).color(r, g, b, 1.0f).endVertex();

        BufferUploader.drawWithShader(builder.end());
    }

    private static void addLine(BufferBuilder builder, float x1, float y1, float z1, float x2, float y2, float z2,
        float r, float g, float b) {
        builder.vertex(x1, y1, z1).color(r, g, b, 1.0f).endVertex();
        builder.vertex(x2, y2, z2).color(r, g, b, 1.0f).endVertex();
    }
}
