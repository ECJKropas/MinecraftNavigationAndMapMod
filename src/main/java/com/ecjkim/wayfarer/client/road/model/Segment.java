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
 * An ordered sequence of nodes forming a road segment.
 */
public class Segment {
    private UUID id;
    private List<UUID> nodeIds;
    private UUID roadId;
    private Source source;
    private Direction direction = Direction.BIDIRECTIONAL;
    private int version;
    private long modifiedAt;

    public Segment() {}

    public Segment(UUID id, List<UUID> nodeIds, UUID roadId, Source source, int version) {
        this.id = id;
        this.nodeIds = nodeIds;
        this.roadId = roadId;
        this.source = source;
        this.direction = Direction.BIDIRECTIONAL;
        this.version = version;
        this.modifiedAt = System.currentTimeMillis();
    }

    public Segment(UUID id, List<UUID> nodeIds, UUID roadId, Source source, Direction direction, int version) {
        this.id = id;
        this.nodeIds = nodeIds;
        this.roadId = roadId;
        this.source = source;
        this.direction = direction != null ? direction : Direction.BIDIRECTIONAL;
        this.version = version;
        this.modifiedAt = System.currentTimeMillis();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public List<UUID> getNodeIds() {
        return nodeIds;
    }

    public void setNodeIds(List<UUID> nodeIds) {
        this.nodeIds = nodeIds;
    }

    public UUID getRoadId() {
        return roadId;
    }

    public void setRoadId(UUID roadId) {
        this.roadId = roadId;
    }

    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    public Direction getDirection() {
        return direction;
    }

    public void setDirection(Direction direction) {
        this.direction = direction;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public long getModifiedAt() {
        return modifiedAt;
    }

    public void setModifiedAt(long modifiedAt) {
        this.modifiedAt = modifiedAt;
    }
}
