package com.ecjkim.wayfarer.client.road.xaero;

import net.minecraft.client.Minecraft;

import xaero.hud.minimap.element.render.MinimapElementReader;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;

final class XaeroMinimapRouteReader extends MinimapElementReader<XaeroRouteElement, XaeroRoadContext> {
    @Override public boolean isHidden(XaeroRouteElement element, XaeroRoadContext context) { return false; }
    @Override public double getRenderX(XaeroRouteElement element, XaeroRoadContext context, float partialTicks) { return element.anchorX(); }
    @Override public double getRenderY(XaeroRouteElement element, XaeroRoadContext context, float partialTicks) { return 0.0; }
    @Override public double getRenderZ(XaeroRouteElement element, XaeroRoadContext context, float partialTicks) { return element.anchorZ(); }
    @Override public int getInteractionBoxLeft(XaeroRouteElement e, XaeroRoadContext c, float p) { return (int)e.anchorX() - 4; }
    @Override public int getInteractionBoxRight(XaeroRouteElement e, XaeroRoadContext c, float p) { return (int)e.anchorX() + 4; }
    @Override public int getInteractionBoxTop(XaeroRouteElement e, XaeroRoadContext c, float p) { return (int)e.anchorZ() - 4; }
    @Override public int getInteractionBoxBottom(XaeroRouteElement e, XaeroRoadContext c, float p) { return (int)e.anchorZ() + 4; }
    @Override public int getRenderBoxLeft(XaeroRouteElement e, XaeroRoadContext c, float p) { return getInteractionBoxLeft(e, c, p); }
    @Override public int getRenderBoxRight(XaeroRouteElement e, XaeroRoadContext c, float p) { return getInteractionBoxRight(e, c, p); }
    @Override public int getRenderBoxTop(XaeroRouteElement e, XaeroRoadContext c, float p) { return getInteractionBoxTop(e, c, p); }
    @Override public int getRenderBoxBottom(XaeroRouteElement e, XaeroRoadContext c, float p) { return getInteractionBoxBottom(e, c, p); }
    @Override public int getLeftSideLength(XaeroRouteElement element, Minecraft minecraft) { return 0; }
    @Override public String getMenuName(XaeroRouteElement element) { return "Wayfarer"; }
    @Override public String getFilterName(XaeroRouteElement element) { return "Wayfarer"; }
    @Override public int getMenuTextFillLeftPadding(XaeroRouteElement element) { return 0; }
    @Override public int getRightClickTitleBackgroundColor(XaeroRouteElement element) { return element.color(); }
    @Override public boolean shouldScaleBoxWithOptionalScale() { return false; }
    @Override public boolean isInteractable(MinimapElementRenderLocation location, XaeroRouteElement element) { return false; }
}
