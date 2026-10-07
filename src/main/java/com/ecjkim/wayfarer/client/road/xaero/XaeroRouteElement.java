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

import com.ecjkim.wayfarer.client.road.model.Node;

/**
 * The active navigation route, as a single map element.
 *
 * <p>
 * Built fresh each render pass from {@link com.ecjkim.wayfarer.client.road.nav.NavigationSession#snapshot()} -- the route
 * is short and changes as the player walks or the session reroutes, so unlike the road layer it is not cached behind a
 * mutation stamp. Geometry is a flat copy of the route nodes, for the same thread-safety reason as
 * {@link XaeroRoadElement}: the map is drawn on the client thread while the in-game tick moves the player.
 */
public final class XaeroRouteElement implements XaeroPolyline {

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

    private XaeroRouteElement(double[] x, double[] z, int color, float widthBlocks, double anchorX, double anchorZ,
        double minX, double maxX, double minZ, double maxZ) {
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
    }

    /** Builds a route element, or {@code null} when there are too few vertices to draw. */
    public static XaeroRouteElement of(List<Node> nodes, int color, float widthBlocks) {
        if (nodes == null || nodes.size() < 2) {
            return null;
        }
        double[] x = new double[nodes.size()];
        double[] z = new double[nodes.size()];
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            x[i] = n.getX();
            z[i] = n.getZ();
            minX = Math.min(minX, x[i]);
            maxX = Math.max(maxX, x[i]);
            minZ = Math.min(minZ, z[i]);
            maxZ = Math.max(maxZ, z[i]);
        }
        return new XaeroRouteElement(x, z, color, widthBlocks, x[0], z[0], minX, maxX, minZ, maxZ);
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

    public double anchorX() {
        return anchorX;
    }

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

    /**
     * Whether the whole element lies outside a world rectangle. A polyline that crosses the view with both ends beyond
     * it has no vertex inside, so a box test (not a vertex test) is what keeps the most visible roads from being dropped.
     */
    public boolean outside(double minX, double minZ, double maxX, double maxZ) {
        return this.maxX < minX || this.minX > maxX || this.maxZ < minZ || this.minZ > maxZ;
    }
}
