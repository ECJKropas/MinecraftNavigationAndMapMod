/*
 * Copyright (C) 2025  MinecraftNavigationAndMapMod contributors
 * https://github.com/ECJKropas/MinecraftNavigationAndMapMod

 * MinecraftNavigationAndMapMod is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.ecjkim.wayfarer.client.road.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.ecjkim.wayfarer.client.road.model.Node;

public final class Route {
    private final List<Node> nodes;
    private final List<Double> edgeLengths;
    private final double totalDistance;
    private final double totalCost;
    private final double etaSeconds;

    public Route(List<Node> nodes, List<Double> edgeLengths, double totalDistance, double totalCost,
        double etaSeconds) {
        this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
        this.edgeLengths = Collections.unmodifiableList(new ArrayList<>(edgeLengths));
        this.totalDistance = totalDistance;
        this.totalCost = totalCost;
        this.etaSeconds = etaSeconds;
    }

    public List<Node> getNodes() { return nodes; }
    public List<Double> getEdgeLengths() { return edgeLengths; }
    public double getTotalDistance() { return totalDistance; }
    public double getTotalCost() { return totalCost; }
    public double getEtaSeconds() { return etaSeconds; }
    public UUID getStartId() { return nodes.get(0).getId(); }
    public UUID getEndId() { return nodes.get(nodes.size() - 1).getId(); }
}
