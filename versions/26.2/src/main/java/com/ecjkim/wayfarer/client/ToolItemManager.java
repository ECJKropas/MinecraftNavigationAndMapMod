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
package com.ecjkim.wayfarer.client;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Manages the survey tool item — the item the player must hold to enter Survey mode.
 *
 * <p>
 * Supports three config-string formats:
 * <ul>
 * <li>{@code minecraft:stick} — item ID only</li>
 * <li>{@code minecraft:stick@0} — item ID with optional damage/meta</li>
 * <li>{@code minecraft:stick@0{NBT}} — item ID with damage and NBT</li>
 * </ul>
 * Matching logic (inspired by Litematica): durability is always ignored; NBT is checked only when the configured tool
 * has non-empty NBT.
 * </p>
 */
public final class ToolItemManager {
    private static final Pattern TOOL_PATTERN =
        Pattern.compile("^([a-z0-9_.-]+:[a-z0-9_./-]+)(?:@(\\d+))?(?:\\{(.*)})?$");
    private static ItemStack toolItem = ItemStack.EMPTY;

    private ToolItemManager() {}

    public static ItemStack getToolItem() {
        return toolItem;
    }

    public static void setToolItem(String configStr) {
        toolItem = parseToolItem(configStr);
    }

    public static void setHeldItemAsTool(Player player) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            toolItem = ItemStack.EMPTY;
            return;
        }
        toolItem = held.copy();
        writeBackToConfig(toolItem);
    }

    /**
     * Checks whether the given entity is holding the configured tool item in either hand.
     */
    public static boolean hasToolItem(Entity entity) {
        if (toolItem.isEmpty()) {
            return false;
        }
        if (!(entity instanceof Player player)) {
            return false;
        }
        return matchesTool(player.getMainHandItem()) || matchesTool(player.getOffhandItem());
    }

    private static boolean matchesTool(ItemStack held) {
        if (held.isEmpty() || toolItem.isEmpty()) {
            return false;
        }
        // Item ID must match
        if (!held.getItem().equals(toolItem.getItem())) {
            return false;
        }
        // If configured tool has NBT (CustomData), compare NBT
        if (toolItem.has(DataComponents.CUSTOM_DATA)) {
            CustomData toolCustom = toolItem.get(DataComponents.CUSTOM_DATA);
            CustomData heldCustom = held.get(DataComponents.CUSTOM_DATA);
            if (toolCustom == null || heldCustom == null) {
                return false;
            }
            // Strip damage from comparison (Litematica behavior)
            CompoundTag heldTag = heldCustom.copyTag();
            heldTag.remove("Damage");
            CompoundTag toolTag = toolCustom.copyTag();
            toolTag.remove("Damage");
            if (!heldTag.equals(toolTag)) {
                return false;
            }
        }
        return true;
    }

    static ItemStack parseToolItem(String configStr) {
        if (configStr == null || configStr.isEmpty()) {
            return ItemStack.EMPTY;
        }
        Matcher m = TOOL_PATTERN.matcher(configStr);
        if (!m.matches()) {
            // Try as plain item ID
            Identifier id = Identifier.tryParse(configStr);
            if (id == null) {
                return ItemStack.EMPTY;
            }
            return new ItemStack(BuiltInRegistries.ITEM.getValue(id));
        }
        String itemId = m.group(1);
        String damageStr = m.group(2);
        String nbtStr = m.group(3);

        Identifier id = Identifier.tryParse(itemId);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        int damage = 0;
        if (damageStr != null && !damageStr.isEmpty()) {
            try {
                damage = Integer.parseInt(damageStr);
            } catch (NumberFormatException ignored) {
            }
        }
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(id));
        if (damage > 0) {
            stack.setDamageValue(damage);
        }
        if (nbtStr != null && !nbtStr.isEmpty()) {
            try {
                CompoundTag tag = TagParser.parseCompoundFully(nbtStr);
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            } catch (Exception ignored) {
            }
        }
        return stack;
    }

    private static void writeBackToConfig(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        StringBuilder sb = new StringBuilder(id.toString());
        int damage = stack.getDamageValue();
        boolean hasDamage = damage > 0;
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        boolean hasNbt = customData != null && !customData.isEmpty();

        if (hasDamage || hasNbt) {
            sb.append('@').append(damage);
        }
        if (hasNbt) {
            sb.append(customData.copyTag().toString());
        }
        WayfarerConfig config = WayfarerConfig.getInstance();
        config.setToolItem(sb.toString());
        config.save();
    }

    /** Returns true if the tool item config is enabled and a tool is set. */
    public static boolean isEnabled() {
        return WayfarerConfig.getInstance().toolItemEnabled && !toolItem.isEmpty();
    }

    public static void reload() {
        WayfarerConfig config = WayfarerConfig.getInstance();
        setToolItem(config.toolItem);
    }
}
