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

import java.util.Locale;

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
        String direction;
        String title = "Navigation";
        int accent;
        if (snapshot.state() == NavigationSession.State.ARRIVED) {
            direction = arrivalText(snapshot.destinationSide());
            title = "Arrived";
            accent = 0xFF55D68A;
        } else {
            String turn = turnText(snapshot.headingTurn());
            if (snapshot.nextTurn() != null && snapshot.nextTurn().distance() <= 12D) {
                turn = turnText(snapshot.nextTurn().type()) + " · "
                    + formatBlocks(snapshot.nextTurn().distance());
            }
            direction = turn;
            accent = snapshot.offRoute() ? 0xFFFFB347 : 0xFF67B7FF;
        }
        String distance = formatBlocks(snapshot.remainingDistance());
        String eta = String.format(Locale.ROOT, "%d min", Math.max(0, Math.round((float)snapshot.remainingTime() / 60F)));
        String meta = distance + "  •  " + eta + "  •  " + direction;
        int width = Math.max(210, Math.min(280, client.font.width(meta) + 28));
        int height = 38;
        int x = (client.getWindow().getGuiScaledWidth() - width) / 2;
        int y = client.getWindow().getGuiScaledHeight() - 92;

        // Layered rectangles keep the card crisp at every GUI scale and leave the hotbar unobstructed.
        graphics.fill(x + 2, y + 2, x + width + 2, y + height + 2, 0x45000000);
        graphics.fill(x, y, x + width, y + height, 0xD9101620);
        graphics.fill(x, y, x + 4, y + height, accent);
        graphics.fill(x + 12, y + 28, x + width - 12, y + 30, 0x554E6A85);
        graphics.fill(x + 12, y + 28, x + 12 + Math.max(8, (int)((width - 24) * progress(snapshot))), y + 30,
            accent);
        graphics.drawString(client.font, title, x + 13, y + 6, 0xFFFFFFFF, true);
        graphics.drawString(client.font, meta, x + 13, y + 18, 0xFFB9C7D8, false);
    }

    private static double progress(NavigationSession.Snapshot snapshot) {
        if (snapshot.route() == null || snapshot.route().getTotalDistance() <= 0D)
            return 0D;
        return Math.max(0D, Math.min(1D, 1D - snapshot.remainingDistance() / snapshot.route().getTotalDistance()));
    }

    private static String formatBlocks(double blocks) {
        return String.format(Locale.ROOT, "%.0f blocks", blocks);
    }

    private static String turnText(Guidance.Turn turn) {
        return switch (turn) {
            case LEFT -> I18n.get("wayfarer.nav.turn.left");
            case RIGHT -> I18n.get("wayfarer.nav.turn.right");
            case UTURN -> I18n.get("wayfarer.nav.turn.uturn");
            case STRAIGHT -> I18n.get("wayfarer.nav.turn.straight");
        };
    }

    private static String arrivalText(Guidance.Turn side) {
        return switch (side) {
            case LEFT -> I18n.get("wayfarer.nav.destination_left");
            case RIGHT -> I18n.get("wayfarer.nav.destination_right");
            case UTURN -> I18n.get("wayfarer.nav.destination_behind");
            case STRAIGHT -> I18n.get("wayfarer.nav.destination_ahead");
        };
    }
}
