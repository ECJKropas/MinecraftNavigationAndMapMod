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

/**
 * A snapshot of where Xaero's world map is looking, captured during the render pass.
 *
 * <h2>Why it is needed</h2> The element provider is asked for elements before any element is drawn, so it has no access
 * to the map's camera -- that only arrives with the first {@code renderElement} call. The projection inputs are
 * therefore cached here, one frame behind, and used to drop geometry that cannot be on screen.
 *
 * <h2>The mapping</h2> Xaero centres the camera on the screen, so a world point projects to
 *
 * <pre>
 * screen = (world - camera) * info.scale * poseScale + screenCentre
 * </pre>
 *
 * The {@code poseScale} term is easy to lose and expensive to lose: the pose carries no zoom of its own, only a
 * constant factor of about a quarter, so dropping it makes every derived distance four times too large and the map
 * appears to drift as it is panned.
 *
 * <h2>Being one frame old</h2> Culling against a stale view is what makes roads flicker at the edges, so callers are
 * expected to ask for a generous margin -- see {@link XaeroRoadProvider}. A zoom step halves or doubles the pixels per
 * block, which is the fastest the view can change, and the margin is chosen to cover it.
 *
 * <h2>Recording it once per pass</h2> The camera is the same for every element in a pass, and only the first element
 * drawn can supply it. {@link #beginPass()} is called at the start of each pass and {@link #claimRecording()} hands the
 * recording to exactly one of them, which keeps a window-size lookup and two divisions out of the per-element path --
 * the only per-element work left is the bounding box test the provider does.
 */
public final class XaeroViewState {

    /** Cached values are considered stale after this long without a render. */
    private static final long STALE_MILLIS = 1000L;
    private static final double MIN_PIXELS_PER_BLOCK = 1.0E-9;

    private static double cameraX;
    private static double cameraZ;
    private static double pixelsPerBlockX;
    private static double pixelsPerBlockZ;
    private static int screenWidth;
    private static int screenHeight;
    private static long updatedAt;

    /** Whether the pass that is currently drawing still needs its viewport recorded. */
    private static boolean recording;

    private XaeroViewState() {}

    /**
     * Marks the start of a render pass.
     *
     * <p>
     * Called by the element provider, which is the only place that sees a pass begin. Every pass re-arms the recording
     * even if the pass before it never drew anything, so a pass that culls everything away cannot leave the viewport
     * unrecorded for the pass after it.
     */
    public static void beginPass() {
        recording = true;
    }

    /**
     * Claims the right to record the viewport for the pass that is now drawing.
     *
     * <p>
     * Returns true for the first element of a pass and false for the rest. The pose Xaero builds is the same for every
     * element in a pass, so the scale read off the first one describes them all.
     *
     * <p>
     * A side effect worth knowing about: a pass in which nothing is drawn claims nothing, so the timestamp is not
     * refreshed and the state goes stale after {@link #STALE_MILLIS}. That is the intended self-healing -- the next
     * pass is then handed everything, draws something, and refreshes it again.
     */
    public static boolean claimRecording() {
        if (!recording) {
            return false;
        }
        recording = false;
        return true;
    }

    /**
     * Records the viewport of the pass that is currently drawing.
     *
     * @param pixelsPerBlockX screen pixels one block covers along X, i.e. {@code info.scale * poseScaleX}
     * @param pixelsPerBlockZ the same along Z
     */
    public static void update(double cameraX, double cameraZ, double pixelsPerBlockX, double pixelsPerBlockZ,
        int screenWidth, int screenHeight) {
        XaeroViewState.cameraX = cameraX;
        XaeroViewState.cameraZ = cameraZ;
        XaeroViewState.pixelsPerBlockX = pixelsPerBlockX;
        XaeroViewState.pixelsPerBlockZ = pixelsPerBlockZ;
        XaeroViewState.screenWidth = screenWidth;
        XaeroViewState.screenHeight = screenHeight;
        XaeroViewState.updatedAt = System.currentTimeMillis();
    }

    /**
     * Whether the cached viewport can be used.
     *
     * <p>
     * Self-healing by construction: if a frame culls so aggressively that nothing is drawn, nothing updates this state,
     * it goes stale, and the next frame hands everything over again.
     */
    public static boolean isValid() {
        return screenWidth > 0 && screenHeight > 0 && Double.isFinite(pixelsPerBlockX)
            && Double.isFinite(pixelsPerBlockZ) && Math.abs(pixelsPerBlockX) > MIN_PIXELS_PER_BLOCK
            && Math.abs(pixelsPerBlockZ) > MIN_PIXELS_PER_BLOCK
            && System.currentTimeMillis() - updatedAt < STALE_MILLIS;
    }

    public static int screenWidth() {
        return screenWidth;
    }

    public static int screenHeight() {
        return screenHeight;
    }

    /** The world X a screen column shows. */
    public static double toWorldX(double screenColumn) {
        return cameraX + (screenColumn - screenWidth / 2.0) / pixelsPerBlockX;
    }

    /** The world Z a screen row shows. */
    public static double toWorldZ(double screenRow) {
        return cameraZ + (screenRow - screenHeight / 2.0) / pixelsPerBlockZ;
    }

    /** Leftmost world X covered by the screen, extended by {@code marginPixels} on each side. */
    public static double minWorldX(double marginPixels) {
        return Math.min(toWorldX(-marginPixels), toWorldX(screenWidth + marginPixels));
    }

    /** Rightmost world X covered by the screen, extended by {@code marginPixels} on each side. */
    public static double maxWorldX(double marginPixels) {
        return Math.max(toWorldX(-marginPixels), toWorldX(screenWidth + marginPixels));
    }

    /** Topmost world Z covered by the screen, extended by {@code marginPixels} on each side. */
    public static double minWorldZ(double marginPixels) {
        return Math.min(toWorldZ(-marginPixels), toWorldZ(screenHeight + marginPixels));
    }

    /** Bottommost world Z covered by the screen, extended by {@code marginPixels} on each side. */
    public static double maxWorldZ(double marginPixels) {
        return Math.max(toWorldZ(-marginPixels), toWorldZ(screenHeight + marginPixels));
    }
}
