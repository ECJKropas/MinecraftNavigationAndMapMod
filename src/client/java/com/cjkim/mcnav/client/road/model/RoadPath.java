package com.cjkim.mcnav.client.road.model;

import java.util.ArrayList;
import java.util.List;

public class RoadPath {
    public String id;
    public String name;
    public double width;
    public List<RoadPoint> points = new ArrayList<>();
    public List<RoadIntersection> intersections = new ArrayList<>();
}
