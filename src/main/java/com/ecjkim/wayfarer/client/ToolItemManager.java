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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

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
    private static final Logger LOGGER = Logger.getLogger("Wayfarer|ToolItem");
    private static final Pattern TOOL_PATTERN =
        Pattern.compile("^([a-z0-9_.-]+:[a-z0-9_./-]+)(?:@(\\d+))?(?:\\{(.*)})?$");
    private static ItemStack toolItem = ItemStack.EMPTY;
    private static final List<Consumer<ItemStack>> listeners = new ArrayList<>();

    private ToolItemManager() {}

    /**
     * Register a listener that will be called when the tool item changes.
     * 
     * @param listener callback receiving the new tool item stack
     */
    public static void addChangeListener(Consumer<ItemStack> listener) {
        listeners.add(listener);
    }

    private static void notifyListeners(ItemStack newItem) {
        for (Consumer<ItemStack> listener : listeners) {
            try {
                listener.accept(newItem);
            } catch (Exception e) {
                LOGGER.warning("Error in tool item change listener: " + e.getMessage());
            }
        }
    }

    public static ItemStack getToolItem() {
        return toolItem;
    }

    public static void setToolItem(String configStr) {
        ItemStack newItem = parseToolItem(configStr);
        if (!ItemStack.matches(toolItem, newItem)) {
            toolItem = newItem;
            notifyListeners(newItem);
        } else {
            toolItem = newItem;
        }
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
        if (!(entity instanceof Player)) {
            return false;
        }
        Player player = (Player)entity;
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
        // If configured tool has NBT, compare NBT
        if (toolItem.hasTag()) {
            CompoundTag toolTag = toolItem.getTag();
            CompoundTag heldTag = held.getTag();
            if (toolTag == null || heldTag == null) {
                return false;
            }
            // Strip damage from comparison (Litematica behavior)
            CompoundTag heldCopy = heldTag.copy();
            heldCopy.remove("Damage");
            CompoundTag toolCopy = toolTag.copy();
            toolCopy.remove("Damage");
            if (!heldCopy.equals(toolCopy)) {
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
            ResourceLocation id = ResourceLocation.tryParse(configStr);
            if (id == null) {
                return ItemStack.EMPTY;
            }
            return new ItemStack(BuiltInRegistries.ITEM.get(id));
        }
        String itemId = m.group(1);
        String damageStr = m.group(2);
        String nbtStr = m.group(3);

        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        int damage = 0;
        if (damageStr != null && !damageStr.isEmpty()) {
            try {
                damage = Integer.parseInt(damageStr);
            } catch (NumberFormatException e) {
                LOGGER.warning("Invalid damage value in tool config '" + configStr + "': " + e.getMessage());
            }
        }
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(id));
        if (damage > 0) {
            stack.setDamageValue(damage);
        }
        if (nbtStr != null && !nbtStr.isEmpty()) {
            try {
                CompoundTag tag = TagParser.parseTag(nbtStr);
                stack.setTag(tag);
            } catch (Exception e) {
                LOGGER.warning("Invalid NBT in tool config '" + configStr + "': " + e.getMessage());
            }
        }
        return stack;
    }

    private static void writeBackToConfig(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        StringBuilder sb = new StringBuilder(id.toString());
        int damage = stack.getDamageValue();
        boolean hasDamage = damage > 0;
        boolean hasNbt = stack.hasTag() && !stack.getTag().isEmpty();

        if (hasDamage || hasNbt) {
            sb.append('@').append(damage);
        }
        if (hasNbt) {
            sb.append(stack.getTag().toString());
        }
        WayfarerConfig config = WayfarerConfig.getInstance();
        config.setToolItem(sb.toString());
        config.save();
    }

    /** Returns true if the tool item config is enabled and a tool is set. */
    public static boolean isEnabled() {
        return WayfarerConfig.getInstance().isToolItemEnabled() && !toolItem.isEmpty();
    }

    public static void reload() {
        WayfarerConfig config = WayfarerConfig.getInstance();
        setToolItem(config.getToolItem());
    }
}
