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
package com.ecjkim.wayfarer.client.road.model;

import java.util.List;
import java.util.UUID;

/**
 * A named road composed of ordered segments.
 */
public class Road {
    private UUID id;
    private String name;
    private String color;
    private List<UUID> segmentIds;
    private int version;

    public Road() {}

    public Road(UUID id, String name, String color, List<UUID> segmentIds, int version) {
        this.id = id;
        this.name = name;
        this.color = color != null ? color : "#FFFFFF";
        this.segmentIds = segmentIds;
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public List<UUID> getSegmentIds() {
        return segmentIds;
    }

    public void setSegmentIds(List<UUID> segmentIds) {
        this.segmentIds = segmentIds;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }
}
