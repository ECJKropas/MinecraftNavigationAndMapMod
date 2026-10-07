/*
 * Copyright (C) 2025  MinecraftNavigationAndMapMod contributors
 * https://github.com/ECJKropas/MinecraftNavigationAndMapMod

 * MinecraftNavigationAndMapMod is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * the Free Software Foundation, version 3 of the License.

 * MinecraftNavigationAndMapMod is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with MinecraftNavigationAndMapMod.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.ecjkim.wayfarer.client.road.xaero;

import net.minecraft.client.Minecraft;

import xaero.map.element.render.ElementReader;
import xaero.map.element.render.ElementRenderLocation;

/**
 * Describes the route element to Xaero's element pipeline.
 *
 * <p>
 * Culling is delegated to {@link XaeroRouteProvider}, which has the world bounding box, so {@link #isOnScreen} reports
 * always-on -- same reasoning as the road reader. The route is not interactive in this task: hover highlight and the
 * right-click menu are A4's concern, so {@link #isInteractable} stays false and the anchor boxes stay tight.
 */
public final class XaeroRouteReader extends ElementReader<XaeroRouteElement, XaeroRoadContext, XaeroRouteRenderer> {

    private static final int ANCHOR_BOX_HALF = 4;

    @Override
    public boolean isHidden(XaeroRouteElement element, XaeroRoadContext context) {
        return false;
    }

    @Override
    public double getRenderX(XaeroRouteElement element, XaeroRoadContext context, float partialTicks) {
        return element.anchorX();
    }

    @Override
    public double getRenderZ(XaeroRouteElement element, XaeroRoadContext context, float partialTicks) {
        return element.anchorZ();
    }

    @Override
    public boolean isOnScreen(XaeroRouteElement element, double cameraX, double cameraZ, int screenWidth,
        int screenHeight, double scale, double screenSizeBasedScale, double dimScale, XaeroRoadContext context,
        float partialTicks) {
        return true;
    }

    @Override
    public int getInteractionBoxLeft(XaeroRouteElement element, XaeroRoadContext context, float partialTicks) {
        return (int)element.anchorX() - ANCHOR_BOX_HALF;
    }

    @Override
    public int getInteractionBoxRight(XaeroRouteElement element, XaeroRoadContext context, float partialTicks) {
        return (int)element.anchorX() + ANCHOR_BOX_HALF;
    }

    @Override
    public int getInteractionBoxTop(XaeroRouteElement element, XaeroRoadContext context, float partialTicks) {
        return (int)element.anchorZ() - ANCHOR_BOX_HALF;
    }

    @Override
    public int getInteractionBoxBottom(XaeroRouteElement element, XaeroRoadContext context, float partialTicks) {
        return (int)element.anchorZ() + ANCHOR_BOX_HALF;
    }

    @Override
    public int getRenderBoxLeft(XaeroRouteElement element, XaeroRoadContext context, float partialTicks) {
        return (int)element.anchorX() - ANCHOR_BOX_HALF;
    }

    @Override
    public int getRenderBoxRight(XaeroRouteElement element, XaeroRoadContext context, float partialTicks) {
        return (int)element.anchorX() + ANCHOR_BOX_HALF;
    }

    @Override
    public int getRenderBoxTop(XaeroRouteElement element, XaeroRoadContext context, float partialTicks) {
        return (int)element.anchorZ() - ANCHOR_BOX_HALF;
    }

    @Override
    public int getRenderBoxBottom(XaeroRouteElement element, XaeroRoadContext context, float partialTicks) {
        return (int)element.anchorZ() + ANCHOR_BOX_HALF;
    }

    @Override
    public int getLeftSideLength(XaeroRouteElement element, Minecraft minecraft) {
        return 0;
    }

    @Override
    public String getMenuName(XaeroRouteElement element) {
        return "Wayfarer 路线";
    }

    @Override
    public String getFilterName(XaeroRouteElement element) {
        return "Wayfarer";
    }

    @Override
    public int getMenuTextFillLeftPadding(XaeroRouteElement element) {
        return 0;
    }

    @Override
    public int getRightClickTitleBackgroundColor(XaeroRouteElement element) {
        return element.color();
    }

    @Override
    public boolean shouldScaleBoxWithOptionalScale() {
        return false;
    }

    /**
     * Nothing on the map is clickable yet: hovering and the right-click menu are A4's task, and claiming them here
     * would put a hit target around a route anchor that does not correspond to anything the player can act on.
     */
    @Override
    public boolean isInteractable(ElementRenderLocation location, XaeroRouteElement element) {
        return false;
    }
}
