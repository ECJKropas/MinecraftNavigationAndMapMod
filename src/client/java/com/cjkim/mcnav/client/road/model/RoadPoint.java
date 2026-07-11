package com.cjkim.mcnav.client.road.model;

public class RoadPoint {
    public double x;
    public double y;
    public double z;
    public long tick;

    public RoadPoint() {
    }

    public RoadPoint(double x, double y, double z, long tick) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.tick = tick;
    }
}
