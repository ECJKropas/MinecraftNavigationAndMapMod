package com.ecjkim.wayfarer.client.road.xaero;

import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import xaero.hud.minimap.element.render.MinimapElementGraphics;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
import xaero.hud.minimap.element.render.MinimapElementRenderer;

final class XaeroMinimapRouteRenderer extends MinimapElementRenderer<XaeroRouteElement, XaeroRoadContext> {
    private final XaeroRoadStroke stroke = new XaeroRoadStroke(this::fillRect);
    private MinimapElementGraphics graphics;

    XaeroMinimapRouteRenderer(XaeroMinimapRouteProvider provider, XaeroMinimapRouteReader reader, XaeroRoadContext context) {
        super(reader, provider, context);
    }
    @Override public int getOrder() { return -90; }
    @Override public boolean shouldRender(MinimapElementRenderLocation location) {
        return location == MinimapElementRenderLocation.IN_MINIMAP || location == MinimapElementRenderLocation.OVER_MINIMAP;
    }
    @Override public void preRender(MinimapElementRenderInfo info, MultiBufferSource.BufferSource buffers,
        xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider textureProvider) {}
    @Override public void postRender(MinimapElementRenderInfo info, MultiBufferSource.BufferSource buffers,
        xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider textureProvider) {}
    @Override public boolean renderElement(XaeroRouteElement element, boolean hovered, boolean inWorld, double depth,
        float screenSizeBasedScale, double fracX, double fracY, MinimapElementRenderInfo info,
        MinimapElementGraphics graphics, MultiBufferSource.BufferSource buffers) {
        this.graphics = graphics;
        try {
            PoseStack pose = graphics.pose();
            var matrix = pose.last().pose();
            double sx = matrix.m00(), sz = matrix.m11();
            boolean clipped = XaeroMinimapClip.begin(pose, element, info);
            if (Math.abs(sx) > 1.0E-6 && Math.abs(sz) > 1.0E-6)
                {
                    double[] rotation = XaeroMinimapClip.rotation();
                    XaeroMinimapPolyline rotated = new XaeroMinimapPolyline(element, rotation[0], rotation[1]);
                    stroke.draw(pose, rotated, XaeroMinimapClip.zoom(), sx, sz, 0.72f, element.color());
                }
            XaeroMinimapClip.end(clipped);
        } finally { this.graphics = null; }
        return true;
    }
    private void fillRect(double x1, double y1, double x2, double y2, int color) {
        if (graphics != null) graphics.fill((int)Math.round(x1), (int)Math.round(y1), (int)Math.round(x2), (int)Math.round(y2), color);
    }
}
