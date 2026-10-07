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

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Attaches the road network to Xaero's world map, and decides whether it worked.
 *
 * <p>
 * Xaero is an optional integration: Wayfarer runs, and the legacy screen-space overlay stays in charge, when it is not
 * installed. That constraint shapes this class -- it deliberately mentions no Xaero type anywhere, so loading it is
 * always safe, and the one method that does touch Xaero lives in {@link XaeroRegistration} and is only ever reached
 * once the mod is known to be present.
 *
 * <p>
 * The registration cannot happen during mod initialisation: {@code WorldMap.mapElementRenderHandler} is null until
 * Xaero itself has initialised, and load order between two client mods is not something either of them controls. So the
 * handler is polled from the client tick until it appears, and the poll stops as soon as the question is answered
 * either way.
 */
public final class XaeroLayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Wayfarer|Xaero");
    private static final String XAERO_MOD_ID = "xaeroworldmap";

    private static boolean installed;
    private static boolean settled;
    private static volatile boolean active;

    private XaeroLayer() {}

    /** True once the road layer is drawing through Xaero, and the legacy overlay should stand down. */
    public static boolean isActive() {
        return active;
    }

    /** Hooks up the registration attempt. Idempotent. */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;

        if (!FabricLoader.getInstance().isModLoaded(XAERO_MOD_ID)) {
            settled = true;
            LOGGER.info(
                "Xaero's World Map is not installed; the road layer is off and the legacy overlay stays in charge.");
            return;
        }

        // Might already be up if Xaero initialised first, in which case there is nothing to poll for.
        tryRegister();
        if (!settled) {
            ClientTickEvents.END_CLIENT_TICK.register(client -> tryRegister());
        }
    }

    private static void tryRegister() {
        if (settled) {
            return;
        }
        try {
            if (!XaeroRegistration.attempt()) {
                return; // Xaero has not finished initialising yet.
            }
            settled = true;
            active = true;
            LOGGER.info(
                "Road layer registered with Xaero's World Map; the legacy screen-space overlay is standing down.");
        } catch (Throwable t) {
            // A integration that cannot be set up must not take the game with it: give up once, and let the legacy
            // overlay keep drawing.
            settled = true;
            LOGGER.error(
                "Failed to register the road layer with Xaero's World Map; the legacy overlay stays in charge.", t);
        }
    }
}
