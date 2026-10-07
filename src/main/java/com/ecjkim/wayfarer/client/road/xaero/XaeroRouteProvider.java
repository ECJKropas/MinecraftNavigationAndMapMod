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
package com.ecjkim.wayfarer.client.road.xaero;

import com.ecjkim.wayfarer.client.WayfarerClient;
import com.ecjkim.wayfarer.client.road.nav.NavigationSession;

import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderProvider;

/**
 * Hands the active navigation route to Xaero's element pipeline, as a single element per pass.
 *
 * <p>
 * The route is read from {@link NavigationSession#snapshot()} every pass. It is short -- a few hundred vertices at most
 * -- and changes as the player moves or the session reroutes, so it is rebuilt each pass rather than cached behind a
 * mutation stamp: the road layer's stamp machinery exists because the whole network is large, which a single route is
 * not. When there is no active route the provider yields nothing and the world map is left untouched.
 */
public final class XaeroRouteProvider extends ElementRenderProvider<XaeroRouteElement, XaeroRoadContext> {

    /** Route colour: Wayfarer blue (ARGB). */
    public static final int ROUTE_COLOR = 0xFF007AFF;
    /** Route stroke width in world blocks; a touch wider than a village road so it reads as "the path". */
    public static final float ROUTE_WIDTH = 4.0f;

    private XaeroRouteElement current;
    private int index;

    @Override
    public void begin(ElementRenderLocation location, XaeroRoadContext context) {
        XaeroViewState.beginPass();
        current = null;
        NavigationSession session = WayfarerClient.getNavigationSession();
        if (session != null) {
            NavigationSession.Snapshot snap = session.snapshot();
            if (snap.state() == NavigationSession.State.ACTIVE && snap.route() != null) {
                XaeroRouteElement el = XaeroRouteElement.of(snap.route().getNodes(), ROUTE_COLOR, ROUTE_WIDTH);
                if (el != null && inView(el)) {
                    current = el;
                }
            }
        }
        index = 0;
    }

    private static boolean inView(XaeroRouteElement el) {
        if (!XaeroViewState.isValid()) {
            return true;
        }
        double marginX = XaeroViewState.screenWidth();
        double marginZ = XaeroViewState.screenHeight();
        return !el.outside(XaeroViewState.minWorldX(marginX), XaeroViewState.minWorldZ(marginZ),
            XaeroViewState.maxWorldX(marginX), XaeroViewState.maxWorldZ(marginZ));
    }

    @Override
    public boolean hasNext(ElementRenderLocation location, XaeroRoadContext context) {
        return current != null && index == 0;
    }

    @Override
    public XaeroRouteElement getNext(ElementRenderLocation location, XaeroRoadContext context) {
        index++;
        return current;
    }

    @Override
    public void end(ElementRenderLocation location, XaeroRoadContext context) {
        index = 0;
    }
}
