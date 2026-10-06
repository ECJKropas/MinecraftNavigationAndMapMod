/*
 * Copyright (C) 2025  MinecraftNavigationAndMapMod contributors
 * https://github.com/ECJKropas/MinecraftNavigationAndMapMod
 *
 * MinecraftNavigationAndMapMod is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.ecjkim.wayfarer.client.road.nav;

import java.util.Collection;
import java.util.Optional;

import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Node;

public final class LocationSnapper {
    private LocationSnapper() {}

    public static Optional<Node> nearestNode(RoadNetworkDatabase database, double x, double z, double radius) {
        Node nearest = null;
        double bestSquared = radius * radius;
        Collection<Node> nodes = database.getAllNodes();
        for (Node node : nodes) {
            double dx = x - node.getX();
            double dz = z - node.getZ();
            double distanceSquared = dx * dx + dz * dz;
            if (distanceSquared <= bestSquared) {
                bestSquared = distanceSquared;
                nearest = node;
            }
        }
        return Optional.ofNullable(nearest);
    }
}
