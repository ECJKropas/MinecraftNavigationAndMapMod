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
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

import com.ecjkim.wayfarer.client.ToolItemManager;
import com.ecjkim.wayfarer.client.WayfarerClient;
import com.ecjkim.wayfarer.client.WayfarerConfig;
import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.CornerType;
import com.ecjkim.wayfarer.client.road.model.Node;
import com.ecjkim.wayfarer.client.road.record.SurveySession;
import com.ecjkim.wayfarer.client.road.record.SurveySession.State;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enhanced HUD overlay for Survey mode.
 *
 * <p>
 * Displays:
 * <ul>
 * <li>Mode indicator (IDLE / RECORDING) with color coding</li>
 * <li>Node count and total distance during recording</li>
 * <li>Corner type indicator with icon</li>
 * <li>Keyboard shortcut hints</li>
 * <li>Hovered node information (coordinates, type)</li>
 * </ul>
 */
public final class SurveyHud {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wayfarer|SurveyHUD");

    private static final int HUD_X = 5;
    private static final int HUD_MARGIN = 2;
    private static final int LINE_HEIGHT = 12;

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

        renderMainPanel(graphics, client, session, state, windowHeight);
        renderHoveredNodeInfo(graphics, client, player, windowWidth, windowHeight);
        renderShortcutHints(graphics, client, state, windowWidth, windowHeight);
    }

    private static void renderMainPanel(GuiGraphics graphics, Minecraft client, SurveySession session, State state,
        int windowHeight) {

        int y = windowHeight - HUD_MARGIN;
        int contentWidth = 200;

        CornerType cornerType = session.getCurrentCornerType();
        String cornerIcon = getCornerIcon(cornerType);
        String cornerName = cornerType.name();

        // First line: state indicator
        String statePrefix;
        int stateColor;
        if (state == State.IDLE) {
            statePrefix = "▶ Survey IDLE";
            stateColor = GRAY;
        } else {
            statePrefix = "● RECORDING";
            stateColor = RED;
        }

        String cornerPart = cornerIcon + " " + cornerName;
        String firstLine = statePrefix + "  |  " + cornerPart;

        int firstLineWidth = client.font.width(firstLine);
        contentWidth = Math.max(contentWidth, firstLineWidth + 4);

        // Second line: node count & distance (only during recording)
        int secondLineWidth = 0;
        String secondLine = "";
        if (state == State.RECORDING) {
            int nodeCount = session.getNodeCount();
            double totalDist = computeTotalDistance(session);
            secondLine = String.format("  ⌂ %d nodes  |  %.1f m", nodeCount, totalDist);
            secondLineWidth = client.font.width(secondLine);
            contentWidth = Math.max(contentWidth, secondLineWidth + 4);
        }

        // Draw background
        int panelHeight = (state == State.RECORDING) ? LINE_HEIGHT * 2 + 4 : LINE_HEIGHT + 4;
        int bgY = y - panelHeight;
        graphics.fill(HUD_X - 1, bgY, HUD_X + contentWidth + 1, y, BG_COLOR);
        graphics.fill(HUD_X - 1, bgY, HUD_X + contentWidth + 1, bgY + 1, BG_BORDER);
        graphics.fill(HUD_X - 1, y - 1, HUD_X + contentWidth + 1, y, BG_BORDER);

        // Draw first line
        int textY = bgY + 2;
        // State part
        graphics.drawString(client.font, statePrefix, HUD_X, textY, stateColor);
        int stateWidth = client.font.width(statePrefix);
        // Separator
        graphics.drawString(client.font, "  |  ", HUD_X + stateWidth, textY, DARK_GRAY);
        int sepWidth = client.font.width("  |  ");
        // Corner icon + name
        graphics.drawString(client.font, cornerIcon, HUD_X + stateWidth + sepWidth, textY, YELLOW);
        int iconWidth = client.font.width(cornerIcon);
        graphics.drawString(client.font, " " + cornerName, HUD_X + stateWidth + sepWidth + iconWidth, textY, GOLD);

        // Draw second line (recording stats)
        if (state == State.RECORDING) {
            textY += LINE_HEIGHT;
            graphics.drawString(client.font, secondLine, HUD_X + 4, textY, WHITE);
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

    private static void renderShortcutHints(GuiGraphics graphics, Minecraft client, State state, int windowWidth,
        int windowHeight) {

        int y = windowHeight - HUD_MARGIN;
        int hintY = y - 40;

        String hints;
        if (state == State.IDLE) {
            hints = "  [LMB+Air] Start  |  [Ctrl+Scroll] Switch Corner  |  [Q] Tool  |  [ESC] Cancel";
        } else {
            hints = "  [LMB+Air] End  |  [RMB+Air] Waypoint  |  [Ctrl+Scroll] Switch Corner  |  [ESC] Cancel";
        }

        int hintWidth = client.font.width(hints);
        int x = windowWidth - hintWidth - HUD_X;

        // Semi-transparent background for readability
        graphics.fill(x - 2, hintY - 2, windowWidth - 2, hintY + LINE_HEIGHT + 2, 0x80000000);
        graphics.drawString(client.font, hints, x, hintY, GRAY);
    }

    private static String getCornerIcon(CornerType type) {
        if (type == null) {
            return "?";
        }
        switch (type) {
            case SHARP:
                return "◆";
            case ROUND:
                return "●";
            case AUTO:
                return "■";
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
