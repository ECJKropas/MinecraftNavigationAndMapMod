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

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

import com.ecjkim.wayfarer.client.WayfarerClient;
import com.ecjkim.wayfarer.client.road.nav.Guidance;
import com.ecjkim.wayfarer.client.road.nav.NavigationSession;

public final class NavHudRenderer {
    private NavHudRenderer() {}

    public static void register() {
        HudRenderCallback.EVENT.register(NavHudRenderer::render);
    }

    private static void render(GuiGraphics graphics, float tickDelta) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.options.hideGui)
            return;
        NavigationSession.Snapshot snapshot = WayfarerClient.getNavigationSession().snapshot();
        if (snapshot.state() == NavigationSession.State.IDLE)
            return;
        String text;
        int color;
        if (snapshot.state() == NavigationSession.State.ARRIVED) {
            text = I18n.get("wayfarer.nav.arrived");
            color = 0xFF55FF55;
        } else {
            String turn = turnText(snapshot.headingTurn());
            if (snapshot.nextTurn() != null && snapshot.nextTurn().distance() <= 12D) {
                turn = turnText(snapshot.nextTurn().type())
                    + I18n.get("wayfarer.nav.turn_in", snapshot.nextTurn().distance());
            } else if (snapshot.headingTurn() == Guidance.Turn.STRAIGHT) {
                turn = I18n.get("wayfarer.nav.continue_road", snapshot.currentRoadDistance());
            }
            text = I18n.get("wayfarer.nav.hud", turn, snapshot.remainingDistance(),
                snapshot.route() == null ? 0 : snapshot.route().getEtaSeconds());
            color = 0xFFFFFFFF;
        }
        int width = client.font.width(text) + 12;
        int x = (client.getWindow().getGuiScaledWidth() - width) / 2;
        int y = client.getWindow().getGuiScaledHeight() - 62;
        graphics.fill(x, y, x + width, y + 16, 0x90000000);
        graphics.drawString(client.font, text, x + 6, y + 4, color);
    }

    private static String turnText(Guidance.Turn turn) {
        return switch (turn) {
            case LEFT -> I18n.get("wayfarer.nav.turn.left");
            case RIGHT -> I18n.get("wayfarer.nav.turn.right");
            case UTURN -> I18n.get("wayfarer.nav.turn.uturn");
            case STRAIGHT -> I18n.get("wayfarer.nav.turn.straight");
        };
    }
}
