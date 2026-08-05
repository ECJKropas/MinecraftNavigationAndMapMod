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

import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

import com.ecjkim.wayfarer.client.ToolItemManager;
import com.ecjkim.wayfarer.client.WayfarerClient;
import com.ecjkim.wayfarer.client.WayfarerConfig;
import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Direction;
import com.ecjkim.wayfarer.client.road.model.Node;
import com.ecjkim.wayfarer.client.road.record.SurveySession;
import com.ecjkim.wayfarer.client.road.record.SurveySession.State;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enhanced HUD overlay for Survey mode.
 *
 * <p>
 * Displays a two-column panel above the hotbar to avoid overlap:
 * <ul>
 * <li>Left column: mode indicator + corner type</li>
 * <li>Right column: node count + total distance</li>
 * <li>Hovered node information above crosshair</li>
 * </ul>
 */
public final class SurveyHud {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wayfarer|SurveyHUD");

    private static final int MARGIN = 4;
    private static final int GAP = 6;
    private static final int LINE_HEIGHT = 12;
    private static final int HOTBAR_HEIGHT = 22;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int DARK_GRAY = 0xFF555555;
    private static final int GOLD = 0xFFFFD700;
    private static final int GREEN = 0xFF00FF00;
    private static final int RED = 0xFFFF4444;
    private static final int CYAN = 0xFF00BFFF;
    private static final int YELLOW = 0xFFFFFF00;

    private static final int BG_COLOR = 0x90000000;
    private static final int BG_BORDER = 0xFF404040;

    private SurveyHud() {}

    public static void register() {
        HudRenderCallback.EVENT.register(SurveyHud::onRender);
        LOGGER.info("SurveyHud registered");
    }

    private static void onRender(GuiGraphics graphics, float tickDelta) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.options.hideGui) {
            return;
        }

        WayfarerConfig config = WayfarerConfig.getInstance();
        if (!config.isToolItemEnabled()) {
            return;
        }

        if (!ToolItemManager.hasToolItem(client.player)) {
            return;
        }

        SurveySession session = WayfarerClient.getSurveySession();
        if (session == null) {
            return;
        }

        LocalPlayer player = client.player;
        State state = session.getState();

        int windowWidth = client.getWindow().getGuiScaledWidth();
        int windowHeight = client.getWindow().getGuiScaledHeight();

        renderTwoColumnPanel(graphics, client, session, state, windowWidth, windowHeight);
        renderHoveredNodeInfo(graphics, client, player, windowWidth, windowHeight);
    }

    private static void renderTwoColumnPanel(GuiGraphics graphics, Minecraft client, SurveySession session, State state,
        int windowWidth, int windowHeight) {

        Direction direction = session.getCurrentDirection();
        String directionIcon = getDirectionIcon(direction);
        String directionName = getDirectionDisplayName(direction);

        // State indicator
        String stateLabel;
        int stateColor;
        if (state == State.IDLE) {
            stateLabel = "▶ Survey IDLE";
            stateColor = GRAY;
        } else if (state == State.RECORDING) {
            stateLabel = "● RECORDING";
            stateColor = RED;
        } else {
            stateLabel = "⏸ PAUSED";
            stateColor = YELLOW;
        }

        // Build left column: state + direction
        String leftLine1 = stateLabel;
        String leftLine2 = directionIcon + " " + directionName;

        // Build right column: node count + distance
        String rightLine1;
        String rightLine2 = "";
        if (state == State.RECORDING || state == State.PAUSED) {
            int nodeCount = session.getNodeCount();
            double totalDist = computeTotalDistance(session);
            rightLine1 = String.format("⌂ %d nodes", nodeCount);
            rightLine2 = String.format("%.1f m%s", totalDist, state == State.PAUSED ? " (paused)" : "");
        } else {
            rightLine1 = "⌂ 0 nodes";
            rightLine2 = "0.0 m";
        }

        int leftWidth = Math.max(client.font.width(leftLine1), client.font.width(leftLine2)) + 6;
        int rightWidth = Math.max(client.font.width(rightLine1), client.font.width(rightLine2)) + 6;
        int totalWidth = leftWidth + GAP + rightWidth;

        // Position: above the hotbar, centered
        int barY = windowHeight - HOTBAR_HEIGHT - MARGIN;
        int panelY = barY - LINE_HEIGHT * 2 - 4;
        int panelX = (windowWidth - totalWidth) / 2;

        // Draw background
        graphics.fill(panelX - 1, panelY, panelX + totalWidth + 1, barY, BG_COLOR);
        graphics.fill(panelX - 1, panelY, panelX + totalWidth + 1, panelY + 1, BG_BORDER);
        graphics.fill(panelX - 1, barY - 1, panelX + totalWidth + 1, barY, BG_BORDER);

        // Draw left column
        int textY = panelY + 2;
        graphics.drawString(client.font, leftLine1, panelX + 3, textY, stateColor);
        textY += LINE_HEIGHT;
        graphics.drawString(client.font, directionIcon, panelX + 3, textY, YELLOW);
        int iconW = client.font.width(directionIcon);
        graphics.drawString(client.font, " " + directionName, panelX + 3 + iconW, textY, GOLD);

        // Draw right column
        int rightX = panelX + leftWidth + GAP;
        textY = panelY + 2;
        graphics.drawString(client.font, rightLine1, rightX + 3, textY, state == State.PAUSED ? GRAY : WHITE);
        textY += LINE_HEIGHT;
        if (!rightLine2.isEmpty()) {
            graphics.drawString(client.font, rightLine2, rightX + 3, textY, state == State.PAUSED ? GRAY : CYAN);
        }
    }

    private static void renderHoveredNodeInfo(GuiGraphics graphics, Minecraft client, LocalPlayer player,
        int windowWidth, int windowHeight) {

        if (player == null) {
            return;
        }

        Vec3 eyePos = player.getEyePosition(1.0f);
        Vec3 lookVec = player.getViewVector(1.0f);

        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        double maxDist = 50.0;
        double hitRadius = 2.0;
        double closestDistSq = hitRadius * hitRadius;
        Node closestNode = null;

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
                closestNode = node;
            }
        }

        if (closestNode == null) {
            return;
        }

        // Draw hovered node info above crosshair
        int centerX = windowWidth / 2;
        int infoY = windowHeight / 2 + 20;

        String coordText =
            String.format("Node [%.1f, %.1f, %.1f]", closestNode.getX(), closestNode.getY(), closestNode.getZ());
        int segCount = db.getSegmentCountForNode(closestNode.getId());
        String segText = String.format("Segments: %d", segCount);

        int coordWidth = client.font.width(coordText);
        int segWidth = client.font.width(segText);
        int maxWidth = Math.max(coordWidth, segWidth) + 8;

        // Background
        int bgX = centerX - maxWidth / 2;
        graphics.fill(bgX - 1, infoY, bgX + maxWidth + 1, infoY + LINE_HEIGHT * 2 + 4, BG_COLOR);

        // Coordinate line
        graphics.drawString(client.font, coordText, bgX + 4, infoY + 2, GRAY);
        // Segment count line
        graphics.drawString(client.font, segText, bgX + 4, infoY + LINE_HEIGHT + 2,
            segCount >= 3 ? GOLD : (segCount == 0 ? RED : WHITE));
    }

    private static String getDirectionIcon(Direction direction) {
        if (direction == null) {
            return "?";
        }
        switch (direction) {
            case BIDIRECTIONAL:
                return "⇆";
            case FORWARD:
                return "→";
            case BACKWARD:
                return "←";
            default:
                return "?";
        }
    }

    private static String getDirectionDisplayName(Direction direction) {
        if (direction == null) {
            return "?";
        }
        switch (direction) {
            case BIDIRECTIONAL:
                return I18n.get("wayfarer.road.survey.direction_type.bidirectional");
            case FORWARD:
                return I18n.get("wayfarer.road.survey.direction_type.forward");
            case BACKWARD:
                return I18n.get("wayfarer.road.survey.direction_type.backward");
            default:
                return "?";
        }
    }

    /**
     * Compute total 2D Euclidean distance along the session's node chain.
     */
    private static double computeTotalDistance(SurveySession session) {
        List<UUID> nodeIds = session.getNodeIds();
        if (nodeIds.size() < 2) {
            return 0;
        }

        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        double total = 0;
        Node prev = db.getNode(nodeIds.get(0));

        for (int i = 1; i < nodeIds.size(); i++) {
            Node curr = db.getNode(nodeIds.get(i));
            if (prev != null && curr != null) {
                double dx = curr.getX() - prev.getX();
                double dz = curr.getZ() - prev.getZ();
                total += Math.sqrt(dx * dx + dz * dz);
            }
            prev = curr;
        }

        return total;
    }
}
