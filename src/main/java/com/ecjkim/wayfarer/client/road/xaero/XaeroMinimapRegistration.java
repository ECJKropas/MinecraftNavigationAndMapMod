package com.ecjkim.wayfarer.client.road.xaero;

import xaero.hud.minimap.Minimap;
import xaero.hud.minimap.element.render.over.MinimapElementOverMapRendererHandler;

/** Registers the active route with Xaero's HUD minimap pipeline. */
final class XaeroMinimapRegistration {
    private XaeroMinimapRegistration() {}

    static boolean attempt() {
        if (xaero.minimap.XaeroMinimap.instance == null) {
            return false;
        }
        Minimap minimap = xaero.minimap.XaeroMinimap.instance.getMinimap();
        if (minimap == null) {
            return false;
        }
        MinimapElementOverMapRendererHandler handler = minimap.getOverMapRendererHandler();
        if (handler == null) {
            return false;
        }
        handler.add(new XaeroMinimapRouteRenderer(new XaeroMinimapRouteProvider(),
            new XaeroMinimapRouteReader(), new XaeroRoadContext()));
        return true;
    }
}
