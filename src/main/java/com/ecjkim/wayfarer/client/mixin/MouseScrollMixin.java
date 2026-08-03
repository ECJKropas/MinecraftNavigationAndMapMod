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
package com.ecjkim.wayfarer.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.ecjkim.wayfarer.client.ToolItemManager;
import com.ecjkim.wayfarer.client.WayfarerClient;
import com.ecjkim.wayfarer.client.road.record.SurveySession;

import org.lwjgl.glfw.GLFW;

@Mixin(net.minecraft.client.MouseHandler.class)
public abstract class MouseScrollMixin {
    @Unique
    private static final int SCROLL_THRESHOLD = 0;

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void wayfarer$onMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null)
            return;

        long win = client.getWindow().getWindow();
        boolean ctrlDown = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(win, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;

        if (!ctrlDown)
            return;
        if (!ToolItemManager.hasToolItem(client.player))
            return;

        SurveySession session = WayfarerClient.getSurveySession();
        if (session == null)
            return;

        double scroll = vertical != 0 ? vertical : horizontal;
        if (Math.abs(scroll) < 0.01)
            return;

        if (scroll > 0) {
            session.cycleDirectionNext();
        } else {
            session.cycleDirectionPrev();
        }

        client.player.displayClientMessage(
            Component.translatable("wayfarer.road.survey.direction", session.getCurrentDirection().name()), true);
        ci.cancel();
    }
}
