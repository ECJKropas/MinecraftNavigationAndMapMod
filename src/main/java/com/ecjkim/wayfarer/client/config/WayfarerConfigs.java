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
package com.ecjkim.wayfarer.client.config;

import java.util.List;

import com.google.common.collect.ImmutableList;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigColor;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.config.options.ConfigString;

public class WayfarerConfigs {
    public static final int CURRENT_VERSION = 1;

    public static class Generic {
        // The comment passed to malilib is only the fallback: malilib looks the tooltip up under
        // "config.comment.<lowercased name>", which is where the zh_cn / en_us translations live.
        public static final ConfigDouble NAV_SNAP_RADIUS = new ConfigDouble("navSnapRadius", 32.0, 1.0, 256.0,
            "Snap radius used to attach navigation start/end to nearby road nodes");
        public static final ConfigDouble NAV_REROUTE_THRESHOLD = new ConfigDouble("navRerouteThreshold", 8.0, 1.0,
            128.0, "Distance off-route that triggers automatic re-routing");
        public static final ConfigDouble NAV_ARRIVAL_RADIUS = new ConfigDouble("navArrivalRadius", 5.0, 1.0, 64.0,
            "Radius that counts as having reached the destination");
        public static final ConfigDouble NAV_DISTANCE_GATE = new ConfigDouble("navDistanceGate", 200.0, 0.0, 10000.0,
            "Straight-line distance threshold above which road class preference applies");
        public static final ConfigDouble NAV_SPEED_G =
            new ConfigDouble("navSpeedG", 5.5, 0.1, 100.0, "Navigation speed on national roads (blocks/second)");
        public static final ConfigDouble NAV_SPEED_S =
            new ConfigDouble("navSpeedS", 5.0, 0.1, 100.0, "Navigation speed on provincial roads (blocks/second)");
        public static final ConfigDouble NAV_SPEED_Y =
            new ConfigDouble("navSpeedY", 4.5, 0.1, 100.0, "Navigation speed on county roads (blocks/second)");
        public static final ConfigDouble NAV_SPEED_X =
            new ConfigDouble("navSpeedX", 4.0, 0.1, 100.0, "Navigation speed on township roads (blocks/second)");
        public static final ConfigDouble NAV_SPEED_C =
            new ConfigDouble("navSpeedC", 3.5, 0.1, 100.0, "Navigation speed on village roads (blocks/second)");
        public static final ConfigOptionList DEFAULT_CLASSIFICATION = new ConfigOptionList("defaultClassification",
            ClassificationEntry.NONE, "Classification applied by default when creating a new road");

        public static final ConfigBoolean AUTO_INTEGRAL = new ConfigBoolean("autoIntegral", true,
            "Whether recorded node coordinates are automatically rounded to whole blocks on all three axes");

        public static final ConfigBoolean AUTO_SNAP_ENDPOINTS = new ConfigBoolean("autoSnapEndpoints", true,
            "Snap the start/end of a recording to an existing node within rdpEpsilon, otherwise insert a new node at the closest point on a segment");

        public static final ConfigDouble RDP_EPSILON = new ConfigDouble("rdpEpsilon", 1.0, 0.1, 100.0,
            "Douglas-Peucker simplification tolerance in blocks; higher values simplify more aggressively");

        public static final ConfigBoolean AUTO_DELETE_ORPHAN_NODES = new ConfigBoolean("autoDeleteOrphanNodes", true,
            "Automatically delete nodes that are no longer referenced by any segment after every edit");

        public static final ConfigInteger WEB_MAX_ZOOM = new ConfigInteger("webMaxZoom", 10, 10, 20,
            "Maximum zoom level of the web map (10-20, default 10); higher values zoom in further");

        public static final ConfigBoolean AUTO_GRAPHIFY = new ConfigBoolean("autoGraphify", true,
            "Turns the road network into a graph in the informatics sense (every node with degree > 2 becomes an endpoint) so Dijkstra / A* can run on endpoints directly");

        public static final ConfigString TOOL_ITEM = new ConfigString("toolItem", "minecraft:wheat_seeds",
            "Survey tool item, e.g. minecraft:wheat_seeds, minecraft:wheat_seeds@0 or minecraft:wheat_seeds@0{NBT}");

        public static final ConfigBoolean TOOL_ITEM_ENABLED = new ConfigBoolean("toolItemEnabled", true,
            "Whether held-item detection for Survey mode is enabled; when off, holding the tool does nothing");

        public static final ConfigBoolean NODE_INDICATOR_ENABLED = new ConfigBoolean("nodeIndicatorEnabled", true,
            "Whether the node indicator (end rod + beam) is rendered while holding the Survey tool");

        public static final ConfigDouble NODE_INDICATOR_BEAM_HEIGHT = new ConfigDouble("nodeIndicatorBeamHeight", 32.0,
            4.0, 128.0, "Height of the node indicator beam in blocks");

        public static final ConfigDouble NODE_INDICATOR_BEAM_ALPHA =
            new ConfigDouble("nodeIndicatorBeamAlpha", 0.3, 0.05, 1.0, "Alpha of the node indicator beam (0-1)");

        public static final ConfigBoolean SHOW_KEY_HINTS = new ConfigBoolean("showKeyHints", true,
            "Show the key hint in the action bar when switching to the Survey tool item");

        // Classification colors (ConfigColor, "#AARRGGBB" format with alpha channel)
        public static final ConfigColor G_COLOR =
            new ConfigColor("gColor", "#FFC000FF", "Draw color of national roads on the map");
        public static final ConfigDouble G_WIDTH =
            new ConfigDouble("gWidth", 6.0, 1.0, 20.0, "Draw width of national roads on the map");
        public static final ConfigColor S_COLOR =
            new ConfigColor("sColor", "#FFD700FF", "Draw color of provincial roads on the map");
        public static final ConfigDouble S_WIDTH =
            new ConfigDouble("sWidth", 4.5, 1.0, 20.0, "Draw width of provincial roads on the map");
        public static final ConfigColor X_COLOR =
            new ConfigColor("xColor", "#FFFFFFFF", "Draw color of township roads on the map");
        public static final ConfigDouble X_WIDTH =
            new ConfigDouble("xWidth", 3.5, 1.0, 20.0, "Draw width of township roads on the map");
        public static final ConfigColor Y_COLOR =
            new ConfigColor("yColor", "#FFFFFFFF", "Draw color of county roads on the map");
        public static final ConfigDouble Y_WIDTH =
            new ConfigDouble("yWidth", 3.0, 1.0, 20.0, "Draw width of county roads on the map");
        public static final ConfigColor C_COLOR =
            new ConfigColor("cColor", "#888888FF", "Draw color of village roads on the map");
        public static final ConfigDouble C_WIDTH =
            new ConfigDouble("cWidth", 3.0, 1.0, 20.0, "Draw width of village roads on the map");
        public static final ConfigBoolean XAERO_SHOW_G =
            new ConfigBoolean("xaeroShowG", true, "Show national roads on the Xaero world map");
        public static final ConfigBoolean XAERO_SHOW_S =
            new ConfigBoolean("xaeroShowS", true, "Show provincial roads on the Xaero world map");
        public static final ConfigBoolean XAERO_SHOW_X =
            new ConfigBoolean("xaeroShowX", true, "Show township roads on the Xaero world map");
        public static final ConfigBoolean XAERO_SHOW_Y =
            new ConfigBoolean("xaeroShowY", true, "Show county roads on the Xaero world map");
        public static final ConfigBoolean XAERO_SHOW_C =
            new ConfigBoolean("xaeroShowC", true, "Show village roads on the Xaero world map");
        public static final ConfigBoolean XAERO_SHOW_UNCLASSIFIED =
            new ConfigBoolean("xaeroShowUnclassified", true, "Show unclassified roads on the Xaero world map");

        public static final ImmutableList<IConfigBase> OPTIONS =
            ImmutableList.of(NAV_SNAP_RADIUS, NAV_REROUTE_THRESHOLD, NAV_ARRIVAL_RADIUS, NAV_DISTANCE_GATE, NAV_SPEED_G,
                NAV_SPEED_S, NAV_SPEED_Y, NAV_SPEED_X, NAV_SPEED_C, DEFAULT_CLASSIFICATION, AUTO_INTEGRAL,
                AUTO_SNAP_ENDPOINTS, RDP_EPSILON, AUTO_DELETE_ORPHAN_NODES, WEB_MAX_ZOOM, AUTO_GRAPHIFY, TOOL_ITEM,
                TOOL_ITEM_ENABLED, NODE_INDICATOR_ENABLED, NODE_INDICATOR_BEAM_HEIGHT, NODE_INDICATOR_BEAM_ALPHA,
                SHOW_KEY_HINTS, G_COLOR, G_WIDTH, S_COLOR, S_WIDTH, X_COLOR, X_WIDTH, Y_COLOR, Y_WIDTH, C_COLOR,
                C_WIDTH, XAERO_SHOW_G, XAERO_SHOW_S, XAERO_SHOW_X, XAERO_SHOW_Y, XAERO_SHOW_C, XAERO_SHOW_UNCLASSIFIED);
    }

    public static List<IConfigBase> getAllConfigs() {
        return ImmutableList.<IConfigBase>builder().addAll(Generic.OPTIONS).addAll(WayfarerHotkeys.HOTKEY_LIST).build();
    }
}
