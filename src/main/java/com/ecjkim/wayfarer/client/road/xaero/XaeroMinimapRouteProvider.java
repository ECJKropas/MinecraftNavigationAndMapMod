package com.ecjkim.wayfarer.client.road.xaero;

import com.ecjkim.wayfarer.client.WayfarerClient;
import com.ecjkim.wayfarer.client.road.nav.NavigationSession;

import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
import xaero.hud.minimap.element.render.MinimapElementRenderProvider;

final class XaeroMinimapRouteProvider extends MinimapElementRenderProvider<XaeroRouteElement, XaeroRoadContext> {
    private XaeroRouteElement current;

    @Override
    public void begin(MinimapElementRenderLocation location, XaeroRoadContext context) {
        current = null;
        NavigationSession.Snapshot snapshot = WayfarerClient.getNavigationSession().snapshot();
        if (snapshot.state() == NavigationSession.State.ACTIVE && snapshot.route() != null) {
            current = XaeroRouteElement.of(snapshot.route().getNodes(), XaeroRouteProvider.ROUTE_COLOR,
                XaeroRouteProvider.ROUTE_WIDTH);
        }
    }

    @Override
    public boolean hasNext(MinimapElementRenderLocation location, XaeroRoadContext context) {
        return current != null;
    }

    @Override
    public XaeroRouteElement getNext(MinimapElementRenderLocation location, XaeroRoadContext context) {
        XaeroRouteElement result = current;
        current = null;
        return result;
    }

    @Override
    public void end(MinimapElementRenderLocation location, XaeroRoadContext context) {}
}
