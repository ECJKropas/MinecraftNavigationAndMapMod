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

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;

import com.mojang.blaze3d.vertex.PoseStack;

import xaero.map.element.MapElementGraphics;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderer;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;

/**
 * Draws the active navigation route into Xaero's world map, on MC 26.1.x.
 *
 * <p>
 * The route polyline reuses {@link XaeroRoadStroke} (the projection and geometry live there, free of Xaero types); this
 * class only adapts Xaero's signature, draws the line, and stamps a start (green) and end (red) marker at the route's
 * ends. The drawing is identical across the three MC builds -- only the declared parameter types differ -- so see
 * {@link XaeroRoadStroke} for the one place the world-to-screen mapping lives.
 */
public final class XaeroRouteRenderer extends ElementRenderer<XaeroRouteElement, XaeroRoadContext, XaeroRouteRenderer> {

    /** Above the road layer (-100) so the route sits on top of the network, below Xaero's waypoints. */
    private static final int ORDER = -90;
    private static final int START_COLOR = 0xFF34C759;
    private static final int END_COLOR = 0xFFFF3B30;

    private final XaeroRoadStroke stroke = new XaeroRoadStroke(this::fillRect);

    /** The graphics of the element currently being rendered; set for the duration of one call. */
    private MapElementGraphics graphics;

    public XaeroRouteRenderer(XaeroRoadContext context, XaeroRouteProvider provider, XaeroRouteReader reader) {
        super(context, provider, reader);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public boolean shouldRender(ElementRenderLocation location, boolean inMenu) {
        return location == ElementRenderLocation.WORLD_MAP || location == ElementRenderLocation.IN_MINIMAP
            || location == ElementRenderLocation.OVER_MINIMAP;
    }

    @Override
    public boolean shouldBeDimScaled() {
        return false;
    }

    @Override
    public void preRender(ElementRenderInfo info, MultiBufferSource.BufferSource buffers,
        MultiTextureRenderTypeRendererProvider textureProvider, boolean inMenu) {}

    @Override
    public void postRender(ElementRenderInfo info, MultiBufferSource.BufferSource buffers,
        MultiTextureRenderTypeRendererProvider textureProvider, boolean inMenu) {}

    @Override
    public void renderElementShadow(XaeroRouteElement element, boolean hovered, float screenSizeBasedScale,
        double fracX, double fracY, ElementRenderInfo info, MapElementGraphics graphics,
        MultiBufferSource.BufferSource buffers, MultiTextureRenderTypeRendererProvider textureProvider) {}

    @Override
    public boolean renderElement(XaeroRouteElement element, boolean hovered, double depth, float screenSizeBasedScale,
        double fracX, double fracY, ElementRenderInfo info, MapElementGraphics graphics,
        MultiBufferSource.BufferSource buffers, MultiTextureRenderTypeRendererProvider textureProvider) {
        this.graphics = graphics;
        try {
            PoseStack pose = graphics.pose();
            var poseMatrix = pose.last().pose();
            double poseX = poseMatrix.m00();
            double poseY = poseMatrix.m11();
            if (XaeroViewState.claimRecording()) {
                recordView(info, poseX, poseY);
            }
            double scale = info.scale;
            if (isUsable(scale) && isUsable(poseX) && isUsable(poseY) && element.vertexCount() >= 2) {
                double pixelsPerBlockX = scale * poseX;
                double pixelsPerBlockZ = scale * poseY;
                float widthScale = info.location == ElementRenderLocation.IN_MINIMAP
                    || info.location == ElementRenderLocation.OVER_MINIMAP ? 0.60f : 1.0f;
                stroke.draw(pose, element, scale, poseX, poseY, widthScale, element.color());
                double half = Math.max(XaeroRoadStyle.MIN_HALF_WIDTH_PX,
                    element.widthBlocks() * Math.sqrt(Math.abs(pixelsPerBlockX * pixelsPerBlockZ)) / 2.0);
                pose.pushPose();
                try {
                    pose.scale((float)(1.0 / poseX), (float)(1.0 / poseY), 1.0F);
                    drawMarker(pose, element, 0, element.anchorX(), element.anchorZ(), pixelsPerBlockX, pixelsPerBlockZ,
                        START_COLOR, half * 1.8);
                    drawMarker(pose, element, element.vertexCount() - 1, element.anchorX(), element.anchorZ(),
                        pixelsPerBlockX, pixelsPerBlockZ, END_COLOR, half * 1.8);
                } finally {
                    pose.popPose();
                }
            }
        } finally {
            this.graphics = null;
        }
        return true;
    }

    /** A square marker at route vertex {@code idx}, drawn in the rescaled (GUI-pixel) pose. */
    private void drawMarker(PoseStack pose, XaeroRouteElement el, int idx, double anchorX, double anchorZ,
        double pixelsPerBlockX, double pixelsPerBlockZ, int color, double r) {
        double sx = (el.x(idx) - anchorX) * pixelsPerBlockX;
        double sz = (el.z(idx) - anchorZ) * pixelsPerBlockZ;
        fillRect(sx - r, sz - r, sx + r, sz + r, color);
    }

    private static void recordView(ElementRenderInfo info, double poseX, double poseY) {
        XaeroViewState.update(info.renderPos.x, info.renderPos.z, info.scale * poseX, info.scale * poseY,
            Minecraft.getInstance().getWindow().getGuiScaledWidth(),
            Minecraft.getInstance().getWindow().getGuiScaledHeight());
    }

    private void fillRect(double x1, double y1, double x2, double y2, int color) {
        MapElementGraphics target = this.graphics;
        if (target == null) {
            return;
        }
        target.fill(round(x1), round(y1), round(x2), round(y2), color);
    }

    private static int round(double value) {
        return (int)Math.round(value);
    }

    private static boolean isUsable(double value) {
        return Double.isFinite(value) && Math.abs(value) > 1.0E-9;
    }
}
