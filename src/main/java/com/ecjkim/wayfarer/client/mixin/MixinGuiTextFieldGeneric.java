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
package com.ecjkim.wayfarer.client.mixin;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;

/**
 * Fixes infinite recursion in malilib 0.16.3's GuiTextFieldGeneric: setCursorPosition → moveCursorTo →
 * setCursorPosition (virtual dispatch loop).
 * <p>
 * Replaces the call to moveCursorTo with a direct super.setCursorPosition, breaking the virtual dispatch cycle.
 */
@Mixin(value = GuiTextFieldGeneric.class, remap = false)
public abstract class MixinGuiTextFieldGeneric extends EditBox {
    private MixinGuiTextFieldGeneric(Component message) {
        super(null, 0, 0, 0, 0, message);
    }

    @Inject(method = "setCursorPosition", at = @At("HEAD"), cancellable = true, remap = false)
    private void wayfarer$fixSetCursorPosition(int pos, CallbackInfo ci) {
        // Bypass malilib's moveCursorTo, call vanilla EditBox.setCursorPosition directly.
        // super.setCursorPosition does NOT use virtual dispatch, so it hits EditBox directly.
        super.setCursorPosition(pos);
        ci.cancel();
    }
}
