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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Road;
import com.ecjkim.wayfarer.client.road.model.Segment;

import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderProvider;

/**
 * Hands the road network to Xaero's element pipeline one element at a time.
 *
 * <h2>One rebuild per edit, one filter per pass</h2>
 *
 * <p>
 * The element list is the whole network, so building it is a walk of every segment that copies coordinates out of the
 * database, and it does not depend on where the map is looking. It is therefore built once and kept, and invalidated by
 * the database's mutation stamp -- the same token the navigation spatial index uses -- together with
 * {@link XaeroRoadStyle#stamp()} for the palette, which does not live in the database. A pass that finds both stamps
 * unchanged skips straight to culling.
 *
 * <p>
 * Culling is the per-pass half, and it is one bounding box test per element. Elements that pass it are collected into a
 * second list that is reused between passes, so a steady frame allocates nothing at all.
 *
 * <p>
 * Holding the geometry between passes is only safe because the stamp is trustworthy: it advances on every write to the
 * network that the renderer can observe, including a segment's node list and its filing. Note that
 * {@code WayfarerConfig} colours are <em>not</em> covered by it, which is what the separate palette stamp is for.
 *
 * <h2>Order</h2>
 *
 * <p>
 * Filed segments come first and unfiled ones after, so a classified road is drawn over the raw recorded geometry it was
 * cut from.
 */
public final class XaeroRoadProvider extends ElementRenderProvider<XaeroRoadElement, XaeroRoadContext> {

    /**
     * How far outside the screen the view rectangle is extended before anything is dropped.
     *
     * <p>
     * A full screen on each side, so the culled region is nine screens of world in area. That is deliberately generous.
     * The view this tests against was recorded during the *previous* pass, and the fastest it can change is a zoom
     * step, which halves or doubles the pixels per block -- at which point a rectangle built from the old scale covers
     * half the world the new scale needs. A quarter-screen margin, which is enough for panning, would make roads pop in
     * at the edges on every zoom; a full screen covers the zoom and the pan together and still removes eight ninths of
     * the work on a large network.
     */
    private static final double VIEW_MARGIN_FRACTION = 1.0;

    /** Every element, in draw order. Rebuilt only when the network or the palette changes. */
    private final List<XaeroRoadElement> all = new ArrayList<>();

    /** The part of {@link #all} the cached viewport can show. Rebuilt every pass, emptied by {@code clear()} only. */
    private final List<XaeroRoadElement> visible = new ArrayList<>();

    /** Guards against a road whose segment list repeats an id, while {@link #all} is being rebuilt. */
    private final Set<UUID> seen = new HashSet<>();

    /**
     * The stamps {@link #all} was last built from. {@code cached} says whether it has been built at all, since a stamp
     * of zero is a legal value and cannot stand in for "not yet".
     */
    private long cachedStamp;
    private long cachedStyleStamp;
    private boolean cached;

    private int index;

    @Override
    public void begin(ElementRenderLocation location, XaeroRoadContext context) {
        // The pass boundary: after this call the first element to be drawn records the new viewport.
        XaeroViewState.beginPass();

        RoadNetworkDatabase database = RoadNetworkDatabase.getInstance();
        long stamp = database.getMutationStamp();
        long styleStamp = XaeroRoadStyle.stamp();
        if (!cached || stamp != cachedStamp || styleStamp != cachedStyleStamp) {
            rebuild(database);
            cachedStamp = stamp;
            cachedStyleStamp = styleStamp;
            cached = true;
        }

        visible.clear();
        if (XaeroViewState.isValid()) {
            double marginX = XaeroViewState.screenWidth() * VIEW_MARGIN_FRACTION;
            double marginY = XaeroViewState.screenHeight() * VIEW_MARGIN_FRACTION;
            double minX = XaeroViewState.minWorldX(marginX);
            double maxX = XaeroViewState.maxWorldX(marginX);
            double minZ = XaeroViewState.minWorldZ(marginY);
            double maxZ = XaeroViewState.maxWorldZ(marginY);
            for (XaeroRoadElement element : all) {
                if (!element.outside(minX, minZ, maxX, maxZ)) {
                    visible.add(element);
                }
            }
        } else {
            // No trustworthy viewport yet, so nothing may be dropped. The first pass after this records one.
            visible.addAll(all);
        }
        index = 0;
    }

    /** Rebuilds {@link #all} from the database. */
    private void rebuild(RoadNetworkDatabase database) {
        all.clear();
        seen.clear();
        for (Road road : database.getRoads()) {
            String classification = road.getClassification();
            // getSegmentsForRoad copies the id list rather than handing out the live one: the web editor can add a
            // segment to a road at any moment, and iterating the live list here would take the render thread down
            // with a ConcurrentModificationException.
            for (Segment segment : database.getSegmentsForRoad(road.getId())) {
                add(database, segment, classification);
            }
        }
        for (Segment segment : database.getUnfiledSegments()) {
            add(database, segment, null);
        }
    }

    /**
     * Adds one segment to {@link #all} unless it has been added already or cannot be drawn.
     *
     * <p>
     * A duplicate segment is normally impossible -- a segment is reachable either through its road's list or through
     * the unfiled set, never both -- but a road whose list was written by hand can repeat an id, and drawing the same
     * stroke twice darkens it visibly as soon as the colour is not opaque.
     */
    private void add(RoadNetworkDatabase database, Segment segment, String classification) {
        if (segment == null || !seen.add(segment.getId())) {
            return;
        }
        if (!XaeroRoadStyle.isVisible(classification)) return;
        String roadName = null;
        if (segment.getRoadId() != null) {
            Road road = database.getRoad(segment.getRoadId());
            roadName = road == null ? null : road.getName();
        }
        XaeroRoadElement element = XaeroRoadElement.of(segment, database.getNodesForSegment(segment.getId()),
            classification, roadName);
        if (element != null) {
            all.add(element);
        }
    }

    @Override
    public boolean hasNext(ElementRenderLocation location, XaeroRoadContext context) {
        return index < visible.size();
    }

    @Override
    public XaeroRoadElement getNext(ElementRenderLocation location, XaeroRoadContext context) {
        return visible.get(index++);
    }

    @Override
    public void end(ElementRenderLocation location, XaeroRoadContext context) {
        index = 0;
    }
}
