package com.ecjkim.wayfarer.client.road.xaero;

/** Route geometry expressed in Xaero's rotated minimap coordinate basis. */
final class XaeroMinimapPolyline implements XaeroPolyline {
    private final XaeroRouteElement source;
    private final double sin;
    private final double cos;

    XaeroMinimapPolyline(XaeroRouteElement source, double sin, double cos) {
        this.source = source;
        this.sin = sin;
        this.cos = cos;
    }

    @Override public int vertexCount() { return source.vertexCount(); }

    @Override public double x(int index) {
        double dx = source.x(index) - source.anchorX();
        double dz = source.z(index) - source.anchorZ();
        return source.anchorX() + cos * dx + sin * dz;
    }

    @Override public double z(int index) {
        double dx = source.x(index) - source.anchorX();
        double dz = source.z(index) - source.anchorZ();
        return source.anchorZ() + sin * dx - cos * dz;
    }

    @Override public int color() { return source.color(); }
    @Override public float widthBlocks() { return source.widthBlocks(); }
    @Override public double anchorX() { return source.anchorX(); }
    @Override public double anchorZ() { return source.anchorZ(); }
}
