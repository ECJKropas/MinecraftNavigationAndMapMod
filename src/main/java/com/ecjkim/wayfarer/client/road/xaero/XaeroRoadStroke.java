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

import com.mojang.blaze3d.vertex.PoseStack;

import org.joml.Quaternionf;

/**
 * Turns a road polyline into thick strokes, through whatever rectangle painter the caller supplies.
 *
 * <h2>The projection</h2> Xaero builds the pose around the element's anchor, so a world point lands at
 *
 * <pre>
 * screen = (world - anchor) * info.scale * poseScale + poseTranslate
 * </pre>
 *
 * where {@code poseScale} and {@code poseTranslate} are read off the pose Xaero left on the stack. That scale term is
 * the one that is easy to lose: the pose does not carry the map's zoom, only a constant factor, so a renderer that
 * applies {@code info.scale} alone draws the network at several times its true size and drifts as the map pans.
 *
 * <h2>Why the pose is rescaled before drawing</h2> Rectangle fills take integer coordinates. The pose's own unit is a
 * fraction of a screen pixel, so filling in the pose's frame would quantise every road to a handful of pixels. The pose
 * is therefore scaled so that one unit is exactly one GUI pixel, which puts the integer coordinates in the units the
 * widths are expressed in.
 *
 * <p>
 * This class is deliberately free of any Xaero type: it is the part of the layer that can be reasoned about, and
 * compiled, without the map mod present.
 */
public final class XaeroRoadStroke {

    /** Fills one axis-aligned rectangle, in the current (already rescaled) pose frame. */
    public interface Painter {
        void rect(double x1, double y1, double x2, double y2, int color);
    }

    private final Painter painter;

    public XaeroRoadStroke(Painter painter) {
        this.painter = painter;
    }

    /**
     * Draws one element.
     *
     * @param pose the pose Xaero is rendering the element with; it is pushed and popped, never mutated permanently
     * @param scale {@code ElementRenderInfo.scale}
     * @param poseX pose's x scale (its {@code m00})
     * @param poseY pose's z scale (its {@code m11})
     */
    public void draw(PoseStack pose, XaeroPolyline element, double scale, double poseX, double poseY) {
        draw(pose, element, scale, poseX, poseY, 1.0f, element.color());
    }

    public void draw(PoseStack pose, XaeroPolyline element, double scale, double poseX, double poseY,
        float widthScale, int colorOverride) {
        if (!isUsable(scale) || !isUsable(poseX) || !isUsable(poseY) || element.vertexCount() < 2) {
            return;
        }

        double pixelsPerBlockX = scale * poseX;
        double pixelsPerBlockZ = scale * poseY;
        // A road's width is a property of the road on the ground, so it tracks the zoom down to a floor: below that it
        // would vanish, and a map that stops showing roads at some zoom is a map with a hole in it.
        double pixelsPerBlock = Math.sqrt(Math.abs(pixelsPerBlockX * pixelsPerBlockZ));
        double halfWidth = Math.max(XaeroRoadStyle.MIN_HALF_WIDTH_PX,
            element.widthBlocks() * widthScale * pixelsPerBlock / 2.0);

        double anchorX = element.anchorX();
        double anchorZ = element.anchorZ();
        int color = colorOverride;

        pose.pushPose();
        try {
            // One unit becomes one GUI pixel; from here on the coordinates below are screen offsets from the pose
            // origin.
            pose.scale((float)(1.0 / poseX), (float)(1.0 / poseY), 1.0F);

            double previousX = (element.x(0) - anchorX) * pixelsPerBlockX;
            double previousY = (element.z(0) - anchorZ) * pixelsPerBlockZ;
            for (int i = 1; i < element.vertexCount(); i++) {
                double currentX = (element.x(i) - anchorX) * pixelsPerBlockX;
                double currentY = (element.z(i) - anchorZ) * pixelsPerBlockZ;
                drawStroke(pose, previousX, previousY, currentX, currentY, halfWidth, color);
                previousX = currentX;
                previousY = currentY;
            }
        } finally {
            // The pose is Xaero's, and everything drawn after this renderer -- the map's own UI included -- depends on
            // it being handed back exactly as it was found.
            pose.popPose();
        }
    }

    /**
     * One stroke, drawn as a rotated rectangle.
     *
     * <p>
     * The rectangle is extended by half a width at each end. Consecutive strokes of a polyline then overlap at the bend
     * between them, which is what closes the wedge-shaped notch a butt-ended pair leaves on the outside of every
     * corner. Doing it by extension rather than by stamping a disc at each vertex keeps a straight run as cheap as its
     * ends.
     */
    private void drawStroke(PoseStack pose, double x1, double y1, double x2, double y2, double halfWidth, int color) {
        double deltaX = x2 - x1;
        double deltaY = y2 - y1;
        double length = Math.sqrt(deltaX * deltaX + deltaY * deltaY);
        if (length < 1.0E-4) {
            // Coincident vertices are common in recorded geometry and would otherwise leave a rotated rectangle with
            // no direction to rotate to.
            return;
        }
        double halfLength = length / 2.0 + halfWidth;
        pose.pushPose();
        try {
            pose.translate((x1 + x2) / 2.0, (y1 + y2) / 2.0, 0.0);
            pose.mulPose(new Quaternionf().rotateZ((float)Math.atan2(deltaY, deltaX)));
            painter.rect(-halfLength, -halfWidth, halfLength, halfWidth, color);
        } finally {
            pose.popPose();
        }
    }

    private static boolean isUsable(double value) {
        return Double.isFinite(value) && Math.abs(value) > 1.0E-9;
    }
}
