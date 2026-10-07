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

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

import com.ecjkim.wayfarer.client.WayfarerClient;
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
            text = "已到达目的地";
            color = 0xFF55FF55;
        } else {
            text = String.format("导航  剩余 %.1f 格  ETA %.0f 秒", snapshot.remainingDistance(),
                snapshot.route() == null ? 0 : snapshot.route().getEtaSeconds());
            color = 0xFFFFFFFF;
        }
        int width = client.font.width(text) + 12;
        int x = (client.getWindow().getGuiScaledWidth() - width) / 2;
        int y = client.getWindow().getGuiScaledHeight() - 62;
        graphics.fill(x, y, x + width, y + 16, 0x90000000);
        graphics.drawString(client.font, text, x + 6, y + 4, color);
    }
}
