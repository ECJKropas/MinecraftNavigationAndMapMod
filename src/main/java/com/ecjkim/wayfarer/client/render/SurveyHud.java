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

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

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
 * Renders a Survey-mode HUD overlay at the bottom-left of the screen.
 *
 * <p>
 * IDLE: "Survey Ready | [SHARP]"
 * </p>
 * <p>
 * RECORDING: "REC Survey | ⌂ 5 nodes | 132.4 m | [SHARP]"
 * </p>
 */
public final class SurveyHud {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wayfarer|SurveyHUD");
    private static final int HUD_X = 5;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int GOLD = 0xFFFFD700;

    private SurveyHud() {}

    public static void register() {
        HudRenderCallback.EVENT.register(SurveyHud::onRender);
        LOGGER.info("SurveyHud registered");
    }

    private static void onRender(GuiGraphics graphics, float tickDelta) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null)
            return;
        if (client.options.hideGui)
            return;

        WayfarerConfig config = WayfarerConfig.getInstance();
        if (!config.toolItemEnabled)
            return;

        boolean hasTool = ToolItemManager.hasToolItem(client.player);
        if (!hasTool)
            return;

        SurveySession session = WayfarerClient.getSurveySession();
        if (session == null)
            return;

        State state = session.getState();
        int y = client.getWindow().getGuiScaledHeight() - 15;

        if (state == State.IDLE) {
            String text = "Survey Ready | [" + session.getCurrentCornerType().name() + "]";
            graphics.drawString(client.font, text, HUD_X, y, GRAY);
        } else if (state == State.RECORDING) {
            int nodeCount = session.getNodeCount();
            double totalDist = computeTotalDistance(client, session);

            String cornerName = session.getCurrentCornerType().name();
            String text = "REC Survey  |  " + "⌂ " + nodeCount + " nodes  |  " + String.format("%.1f", totalDist)
                + " m  |  [" + cornerName + "]";

            int segLen = text.length() - cornerName.length() - 2; // -2 for []
            int cornerStart = text.indexOf('[');

            // Draw prefix in white
            graphics.drawString(client.font, text.substring(0, cornerStart), HUD_X, y, WHITE);
            // Draw corner type in gold
            int prefixWidth = client.font.width(text.substring(0, cornerStart));
            graphics.drawString(client.font, "[" + cornerName + "]", HUD_X + prefixWidth, y, GOLD);
        }
    }

    /**
     * Compute total 2D Euclidean distance along the session's node chain.
     */
    private static double computeTotalDistance(Minecraft client, SurveySession session) {
        java.util.List<UUID> nodeIds = session.getNodeIds();
        if (nodeIds.size() < 2)
            return 0;

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
