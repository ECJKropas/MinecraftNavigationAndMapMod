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

import com.mojang.blaze3d.vertex.PoseStack;

import xaero.lib.client.graphics.XaeroBufferProvider;
import xaero.map.element.MapElementGraphics;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderer;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;

/**
 * Draws the road network into Xaero's world map, on MC 26.x.
 *
 * <p>
 * This is the newest of the three builds and differs from 26.1.x in exactly one place: Xaero's element API here is
 * handed Xaero's own {@code XaeroBufferProvider} instead of vanilla's {@code MultiBufferSource.BufferSource}. 1.20.1 is
 * handed vanilla's {@code GuiGraphics}, 26.1.x Xaero's {@code MapElementGraphics} but still vanilla's buffer source.
 * The drawing is identical in all three -- only the declared parameter types differ -- so the projection and the
 * geometry live once, in {@link XaeroRoadStroke}, and there is only one place to fix if the mapping turns out to be
 * wrong.
 */
public final class XaeroRoadRenderer extends ElementRenderer<XaeroRoadElement, XaeroRoadContext, XaeroRoadRenderer> {

    /**
     * Below Xaero's waypoints, so a road never covers a player marker or a waypoint label.
     */
    private static final int ORDER = -100;

    private final XaeroRoadStroke stroke = new XaeroRoadStroke(this::fillRect);

    /** The graphics of the element currently being rendered; set for the duration of one call. */
    private MapElementGraphics graphics;

    public XaeroRoadRenderer(XaeroRoadContext context, XaeroRoadProvider provider, XaeroRoadReader reader) {
        super(context, provider, reader);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * The road layer belongs on the world map only. The minimap is a separate task: it is small enough that a full
     * network would be unreadable on it, and what belongs there is the active route rather than every road.
     */
    @Override
    public boolean shouldRender(ElementRenderLocation location, boolean inMenu) {
        return location == ElementRenderLocation.WORLD_MAP;
    }

    /**
     * The reader hands out raw world block coordinates, so Xaero's dimension scaling has to stay out of the way and
     * leave the anchor arithmetic in the same units.
     */
    @Override
    public boolean shouldBeDimScaled() {
        return false;
    }

    @Override
    public void preRender(ElementRenderInfo info, XaeroBufferProvider buffers,
        MultiTextureRenderTypeRendererProvider textureProvider, boolean inMenu) {}

    @Override
    public void postRender(ElementRenderInfo info, XaeroBufferProvider buffers,
        MultiTextureRenderTypeRendererProvider textureProvider, boolean inMenu) {}

    @Override
    public void renderElementShadow(XaeroRoadElement element, boolean hovered, float screenSizeBasedScale, double fracX,
        double fracY, ElementRenderInfo info, MapElementGraphics graphics, XaeroBufferProvider buffers,
        MultiTextureRenderTypeRendererProvider textureProvider) {}

    @Override
    public boolean renderElement(XaeroRoadElement element, boolean hovered, double depth, float screenSizeBasedScale,
        double fracX, double fracY, ElementRenderInfo info, MapElementGraphics graphics, XaeroBufferProvider buffers,
        MultiTextureRenderTypeRendererProvider textureProvider) {
        this.graphics = graphics;
        try {
            PoseStack pose = graphics.pose();
            var poseMatrix = pose.last().pose();
            double poseX = poseMatrix.m00();
            double poseY = poseMatrix.m11();
            if (XaeroViewState.claimRecording()) {
                recordView(info, poseX, poseY);
            }
            int color = hovered ? 0xFFFFFFFF : element.color();
            float widthScale = hovered ? 1.35f : 1.0f;
            stroke.draw(pose, element, info.scale, poseX, poseY, widthScale, color);
            if (!hovered && info.location == ElementRenderLocation.WORLD_MAP && info.scale >= 0.35
                && element.roadName() != null && !element.roadName().isBlank()) {
                drawRoadLabel(pose, element, info.scale, poseX, poseY);
            }
        } finally {
            this.graphics = null;
        }
        return true;
    }

    /**
     * Publishes the viewport for the next pass, which is what lets the provider cull before anything is drawn.
     *
     * <p>
     * Only one element per pass reaches here -- see {@link XaeroViewState#claimRecording()} -- so the window lookups
     * happen once per frame rather than once per road.
     */
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

    private void drawRoadLabel(PoseStack pose, XaeroRoadElement element, double scale, double poseX, double poseY) {
        pose.pushPose();
        try {
            double px = (element.labelX() - element.anchorX()) * scale * poseX;
            double pz = (element.labelZ() - element.anchorZ()) * scale * poseY;
            pose.scale((float)(1.0 / poseX), (float)(1.0 / poseY), 1.0F);
            pose.translate(px, pz, 0.0);
            pose.mulPose(new org.joml.Quaternionf().rotateZ((float)element.labelAngle()));
            graphics.drawCenteredString(Minecraft.getInstance().font, element.roadName(), 0, -5, 0xFFFFFFFF);
        } finally {
            pose.popPose();
        }
    }

    private static int round(double value) {
        return (int)Math.round(value);
    }
}
