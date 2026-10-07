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

import java.util.List;
import java.util.UUID;

import com.ecjkim.wayfarer.client.road.model.Node;
import com.ecjkim.wayfarer.client.road.model.Segment;

/**
 * One road segment, as a map element.
 *
 * <p>
 * The geometry is copied out of the database when the element is built rather than held as {@link Node} references. The
 * map is drawn on the client render thread while the built-in web editor writes from its own thread, so an element that
 * kept live references could be read half-edited; a flat copy is both cheaper to draw from and immune to that.
 */
public final class XaeroRoadElement implements XaeroPolyline {

    private final UUID segmentId;
    private final double[] x;
    private final double[] z;
    private final int color;
    private final float widthBlocks;
    private final double anchorX;
    private final double anchorZ;
    private final double minX;
    private final double maxX;
    private final double minZ;
    private final double maxZ;
    private final String roadName;
    private final String classification;
    private final double labelX;
    private final double labelZ;
    private final double labelAngle;

    private XaeroRoadElement(UUID segmentId, double[] x, double[] z, int color, float widthBlocks, double anchorX,
        double anchorZ, double minX, double maxX, double minZ, double maxZ, String roadName, String classification,
        double labelX, double labelZ, double labelAngle) {
        this.segmentId = segmentId;
        this.x = x;
        this.z = z;
        this.color = color;
        this.widthBlocks = widthBlocks;
        this.anchorX = anchorX;
        this.anchorZ = anchorZ;
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.roadName = roadName;
        this.classification = classification;
        this.labelX = labelX;
        this.labelZ = labelZ;
        this.labelAngle = labelAngle;
    }

    /**
     * Builds an element for a segment, or returns {@code null} when the segment cannot be drawn.
     *
     * @param classification the owning road's classification, or {@code null} for an unfiled segment
     */
    public static XaeroRoadElement of(Segment segment, List<Node> nodes, String classification) {
        return of(segment, nodes, classification, null);
    }

    public static XaeroRoadElement of(Segment segment, List<Node> nodes, String classification, String roadName) {
        if (segment == null || nodes == null || nodes.size() < 2) {
            return null;
        }
        double[] x = new double[nodes.size()];
        double[] z = new double[nodes.size()];
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            x[i] = node.getX();
            z[i] = node.getZ();
            minX = Math.min(minX, x[i]);
            maxX = Math.max(maxX, x[i]);
            minZ = Math.min(minZ, z[i]);
            maxZ = Math.max(maxZ, z[i]);
        }
        boolean unfiled = classification == null || classification.isEmpty();
        int color = unfiled ? XaeroRoadStyle.UNFILED_COLOR : XaeroRoadStyle.color(classification);
        float width = unfiled ? XaeroRoadStyle.UNFILED_WIDTH : XaeroRoadStyle.width(classification);
        // The first vertex is the anchor: Xaero builds the pose around it, so keeping it on the geometry keeps the
        // pose-local coordinates small and the float precision in the pose useful.
        int mid = Math.max(0, (nodes.size() - 1) / 2);
        int next = Math.min(nodes.size() - 1, mid + 1);
        double labelX = (x[mid] + x[next]) / 2.0;
        double labelZ = (z[mid] + z[next]) / 2.0;
        double angle = Math.atan2(z[next] - z[mid], x[next] - x[mid]);
        if (angle > Math.PI / 2 || angle < -Math.PI / 2) angle += Math.PI;
        return new XaeroRoadElement(segment.getId(), x, z, color, width, x[0], z[0], minX, maxX, minZ, maxZ,
            roadName, classification, labelX, labelZ, angle);
    }

    public UUID segmentId() {
        return segmentId;
    }

    public int vertexCount() {
        return x.length;
    }

    public double x(int index) {
        return x[index];
    }

    public double z(int index) {
        return z[index];
    }

    public int color() {
        return color;
    }

    public float widthBlocks() {
        return widthBlocks;
    }

    /** World X the pose is built around. */
    public double anchorX() {
        return anchorX;
    }

    /** World Z the pose is built around. */
    public double anchorZ() {
        return anchorZ;
    }

    public double minX() {
        return minX;
    }

    public double maxX() {
        return maxX;
    }

    public double minZ() {
        return minZ;
    }

    public double maxZ() {
        return maxZ;
    }

    public String roadName() { return roadName; }
    public String classification() { return classification; }
    public double labelX() { return labelX; }
    public double labelZ() { return labelZ; }
    public double labelAngle() { return labelAngle; }

    /**
     * Whether the whole element lies outside a world rectangle.
     *
     * <p>
     * Tested by bounding box and only rejected when the entire box is outside. A polyline that crosses the view with
     * both ends beyond it has no vertex inside, so a vertex-based test would discard exactly the longest and most
     * visible roads. A box that grazes the view without the line entering it costs one element that Xaero then throws
     * away, which is the cheap direction to be wrong in.
     */
    public boolean outside(double minX, double minZ, double maxX, double maxZ) {
        return this.maxX < minX || this.minX > maxX || this.maxZ < minZ || this.minZ > maxZ;
    }
}
