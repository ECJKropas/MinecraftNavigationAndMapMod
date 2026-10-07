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

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;

import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;

import xaero.map.element.render.ElementReader;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

/**
 * Describes road elements to Xaero's element pipeline.
 *
 * <h2>Why culling is not left to Xaero</h2> Xaero's default {@code isOnScreen} tests the render box against the
 * viewport. That is the right hook, but the box cannot carry this layer's geometry: it is an offset from the anchor
 * that the default adds to a screen coordinate <em>without</em> multiplying by the map's scale, so it is a
 * constant-pixel quantity -- fine for a waypoint drawn at a fixed size, useless for a polyline whose extent is a number
 * of blocks that has to be turned into pixels using the zoom. Both ends of a segment can be off screen while the
 * segment runs right across it, which is exactly the longest and most visible kind of road.
 *
 * <p>
 * So the override reports everything as on screen and the real test happens in {@link XaeroRoadProvider}, which has the
 * world-space bounding box and a viewport to test it against. The boxes below stay tight around the anchor -- they are
 * the fallback the pipeline uses when {@code isOnScreen} is not overridden, and a wrong-but-tight box fails visibly
 * rather than silently.
 */
public final class XaeroRoadReader extends ElementReader<XaeroRoadElement, XaeroRoadContext, XaeroRoadRenderer> {

    private static final int BOX_MARGIN = 6;

    @Override
    public boolean isHidden(XaeroRoadElement element, XaeroRoadContext context) {
        return false;
    }

    @Override
    public double getRenderX(XaeroRoadElement element, XaeroRoadContext context, float partialTicks) {
        return element.anchorX();
    }

    @Override
    public double getRenderZ(XaeroRoadElement element, XaeroRoadContext context, float partialTicks) {
        return element.anchorZ();
    }

    @Override
    public boolean isOnScreen(XaeroRoadElement element, double cameraX, double cameraZ, int screenWidth,
        int screenHeight, double scale, double screenSizeBasedScale, double dimScale, XaeroRoadContext context,
        float partialTicks) {
        return true;
    }

    @Override
    public int getInteractionBoxLeft(XaeroRoadElement element, XaeroRoadContext context, float partialTicks) {
        return (int)Math.floor(element.minX() - element.anchorX()) - BOX_MARGIN;
    }

    @Override
    public int getInteractionBoxRight(XaeroRoadElement element, XaeroRoadContext context, float partialTicks) {
        return (int)Math.ceil(element.maxX() - element.anchorX()) + BOX_MARGIN;
    }

    @Override
    public int getInteractionBoxTop(XaeroRoadElement element, XaeroRoadContext context, float partialTicks) {
        return (int)Math.floor(element.minZ() - element.anchorZ()) - BOX_MARGIN;
    }

    @Override
    public int getInteractionBoxBottom(XaeroRoadElement element, XaeroRoadContext context, float partialTicks) {
        return (int)Math.ceil(element.maxZ() - element.anchorZ()) + BOX_MARGIN;
    }

    @Override
    public int getRenderBoxLeft(XaeroRoadElement element, XaeroRoadContext context, float partialTicks) {
        return getInteractionBoxLeft(element, context, partialTicks);
    }

    @Override
    public int getRenderBoxRight(XaeroRoadElement element, XaeroRoadContext context, float partialTicks) {
        return getInteractionBoxRight(element, context, partialTicks);
    }

    @Override
    public int getRenderBoxTop(XaeroRoadElement element, XaeroRoadContext context, float partialTicks) {
        return getInteractionBoxTop(element, context, partialTicks);
    }

    @Override
    public int getRenderBoxBottom(XaeroRoadElement element, XaeroRoadContext context, float partialTicks) {
        return getInteractionBoxBottom(element, context, partialTicks);
    }

    @Override
    public int getLeftSideLength(XaeroRoadElement element, Minecraft minecraft) {
        return 0;
    }

    @Override
    public String getMenuName(XaeroRoadElement element) {
        return "Wayfarer";
    }

    @Override
    public String getFilterName(XaeroRoadElement element) {
        return "Wayfarer";
    }

    @Override
    public int getMenuTextFillLeftPadding(XaeroRoadElement element) {
        return 0;
    }

    @Override
    public int getRightClickTitleBackgroundColor(XaeroRoadElement element) {
        return element.color();
    }

    @Override
    public boolean shouldScaleBoxWithOptionalScale() {
        return false;
    }

    /**
     * Nothing on the map is clickable yet: hovering and the right-click menu are their own task, and claiming them here
     * would put a hit target around a segment anchor that does not correspond to anything the player can see.
     */
    @Override
    public boolean isInteractable(ElementRenderLocation location, XaeroRoadElement element) {
        return location == ElementRenderLocation.WORLD_MAP;
    }

    @Override
    public ArrayList<RightClickOption> getRightClickOptions(XaeroRoadElement element, IRightClickableElement target) {
        ArrayList<RightClickOption> options = new ArrayList<>();
        options.add(new RightClickOption(I18n.get("wayfarer.xaero.open_web_editor"), 0, target) {
            @Override
            public void onAction(Screen screen) {
                String url = "http://localhost:7891/?x=" + element.anchorX() + "&z=" + element.anchorZ();
                try {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().browse(URI.create(url));
                    } else {
                        Minecraft.getInstance().keyboardHandler.setClipboard(url);
                    }
                } catch (Exception e) {
                    Minecraft.getInstance().keyboardHandler.setClipboard(url);
                }
            }
        });
        options.add(new RightClickOption(I18n.get("wayfarer.xaero.copy_coordinates"), 1, target) {
            @Override
            public void onAction(Screen screen) {
                Minecraft.getInstance().keyboardHandler
                    .setClipboard(String.format("%.2f, %.2f", element.anchorX(), element.anchorZ()));
            }
        });
        options.add(new RightClickOption(I18n.get("wayfarer.xaero.delete_segment"), 2, target) {
            @Override
            public void onAction(Screen screen) {
                RoadNetworkDatabase database = RoadNetworkDatabase.getInstance();
                database.removeSegment(element.segmentId());
                database.saveToDisk();
            }
        });
        return options;
    }
}
