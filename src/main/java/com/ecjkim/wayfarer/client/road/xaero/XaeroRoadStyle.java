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

import com.ecjkim.wayfarer.client.WayfarerConfig;

/**
 * Colour and stroke width rules for the road layer drawn on Xaero's map.
 *
 * <h2>Why there is a palette in here at all</h2> On 1.20.1 the colours and widths come from the user's config, and that
 * is the only source they should have. On 26.x they cannot: the config's colour entries are read through malilib, which
 * is not a dependency of the 26.x build and is not present at runtime, so asking for a colour there fails with a
 * {@link NoClassDefFoundError} rather than returning a default. The 26.x config is a plain JSON file that no longer
 * carries the palette.
 *
 * <p>
 * Availability is therefore probed once, on the first lookup, and remembered. Probing on every element would mean one
 * thrown error per road per frame, which is slower than the drawing it guards.
 */
public final class XaeroRoadStyle {
    /** Fallback colour for a road with no classification, and for every unfiled segment. */
    public static final int DEFAULT_COLOR = 0xFFFFFFFF;
    public static final int UNFILED_COLOR = 0xFFFFD700;

    public static final float DEFAULT_WIDTH = 3.0f;
    public static final float UNFILED_WIDTH = 2.5f;

    /** Stroke width is never allowed below this, in GUI pixels, so a road stays visible at the widest zoom levels. */
    public static final double MIN_HALF_WIDTH_PX = 0.5;

    /** The classification codes the palette covers, in a fixed order so {@link #stamp()} is stable across calls. */
    private static final char[] CODES = {'G', 'S', 'X', 'Y', 'C'};

    /** Whether the config palette has been probed, and whether it answered. */
    private static volatile Boolean configReadable;

    private XaeroRoadStyle() {}

    /** ARGB colour for a road classification code, or the default colour when there is none. */
    public static int color(String classification) {
        if (classification == null || classification.isEmpty()) {
            return DEFAULT_COLOR;
        }
        return colorFor(classification.charAt(0));
    }

    /** Stroke width in world blocks for a road classification code. */
    public static float width(String classification) {
        if (classification == null || classification.isEmpty()) {
            return DEFAULT_WIDTH;
        }
        return widthFor(classification.charAt(0));
    }

    public static boolean isVisible(String classification) {
        try {
            return WayfarerConfig.getInstance().isXaeroClassificationVisible(classification);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * A token that changes whenever the palette does, so that callers caching coloured geometry can tell when their
     * cache has gone out of date.
     *
     * <p>
     * It is derived from the values rather than from a counter because there is no counter to derive it from: the
     * palette lives in the user's config, it can be changed at any moment, and on 1.20.1 it is changed through a
     * library that does not announce itself. Reading the ten values costs a fixed amount per pass -- small enough to
     * run unconditionally, and it keeps a colour change visible on the next frame rather than at the next edit.
     */
    public static long stamp() {
        long hash = 17L;
        for (char code : CODES) {
            hash = hash * 31L + colorFor(code);
            hash = hash * 31L + Float.floatToIntBits(widthFor(code));
        }
        return hash;
    }

    /**
     * The colour behind {@link #color(String)}, taking the code rather than the string so that {@link #stamp()} can
     * walk the palette without allocating a string per code.
     */
    private static int colorFor(char code) {
        if (configReadable()) {
            return parseColor(WayfarerConfig.getInstance().getClassificationColor(code));
        }
        return builtInColor(code);
    }

    /** The width behind {@link #width(String)}, for the same reason as {@link #colorFor(char)}. */
    private static float widthFor(char code) {
        if (configReadable()) {
            float value = WayfarerConfig.getInstance().getClassificationWidth(code);
            return value > 0.0f ? value : DEFAULT_WIDTH;
        }
        return builtInWidth(code);
    }

    /**
     * Probes once whether the config can be read at all.
     *
     * <p>
     * The probe asks for a colour rather than a width on purpose: on 26.2 the width accessors read plain fields and
     * answer happily while the colour accessors still reach for malilib, so a width probe would report the config as
     * available and then the next call would throw.
     */
    private static boolean configReadable() {
        Boolean known = configReadable;
        if (known != null) {
            return known;
        }
        boolean readable;
        try {
            WayfarerConfig.getInstance().getClassificationColor('G');
            readable = true;
        } catch (Throwable t) {
            readable = false;
        }
        configReadable = readable;
        return readable;
    }

    /**
     * Parses a config colour string into ARGB.
     *
     * <p>
     * malilib's {@code ConfigColor} reports {@code "#AARRGGBB"}; the legacy 6-digit form is RGB and taken as opaque.
     */
    private static int parseColor(String value) {
        if (value == null) {
            return DEFAULT_COLOR;
        }
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (hex.length() == 6) {
            hex = "FF" + hex;
        }
        try {
            return (int)Long.parseLong(hex, 16);
        } catch (NumberFormatException e) {
            return DEFAULT_COLOR;
        }
    }

    /** The 26.x palette: the same colours the screen-space overlay used before the layer moved into Xaero. */
    private static int builtInColor(char code) {
        switch (code) {
            case 'G':
                return 0xFFFF8800;
            case 'S':
                return 0xFFFFFF00;
            case 'X':
                return 0xFF00FF00;
            case 'Y':
                return 0xFF4488FF;
            case 'C':
                return 0xFF888888;
            default:
                return DEFAULT_COLOR;
        }
    }

    private static float builtInWidth(char code) {
        switch (code) {
            case 'G':
                return 6.0f;
            case 'S':
                return 4.5f;
            case 'X':
                return 3.5f;
            case 'Y':
                return 3.0f;
            case 'C':
                return 3.0f;
            default:
                return DEFAULT_WIDTH;
        }
    }
}
