/*
 * Copyright (C) 2025  MinecraftNavigationAndMapMod contributors
 * https://github.com/ECJKropas/MinecraftNavigationAndMapMod
 */
package com.ecjkim.wayfarer.client.render;

/** HUD rendering is unavailable on MC 26.x because fabric-rendering-v1 removed HudRenderCallback. */
public final class NavHudRenderer {
    private NavHudRenderer() {}
    public static void register() {}
}
