/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.utils;

import baritone.Baritone;
import baritone.api.utils.HotbarSwapPlanner;
import baritone.api.utils.HotbarSwapPlanner.SlotInfo;
import baritone.api.utils.HotbarSwapPlanner.SwapPlan;
import baritone.api.utils.HotbarSwapPlanner.ToolCategory;
import baritone.api.utils.IPlayerContext;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Executes backpack-to-hotbar tool swaps and low-durability tool replacements for both
 * Baritone ground mining and Perimeter Digger's /fly miner.
 */
public final class BackpackToolManager {
    private static final long SWAP_COOLDOWN_MS = 250L;
    private static long lastSwapMillis = 0L;

    private BackpackToolManager() {}

    public static ToolCategory classifyCategory(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ToolCategory.NONE;
        if (stack.is(ItemTags.PICKAXES)) return ToolCategory.PICKAXE;
        if (stack.is(ItemTags.SHOVELS)) return ToolCategory.SHOVEL;
        if (stack.is(ItemTags.AXES)) return ToolCategory.AXE;
        if (stack.is(ItemTags.HOES)) return ToolCategory.HOE;
        if (stack.is(Items.SHEARS)) return ToolCategory.SHEARS;
        if (stack.is(ItemTags.SWORDS)) return ToolCategory.SWORD;
        if (stack.getItem().components().has(DataComponents.TOOL)) return ToolCategory.OTHER_TOOL;
        return ToolCategory.NONE;
    }

    public static boolean isProtectedHotbarItem(ItemStack stack, Collection<Item> extraProtected) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getItem().components().has(DataComponents.FOOD)) return true;
        if (stack.is(Items.ELYTRA) || stack.is(Items.FIREWORK_ROCKET)
                || stack.is(Items.SHIELD) || stack.is(Items.TOTEM_OF_UNDYING)
                || stack.is(Items.WATER_BUCKET)) {
            return true;
        }
        return extraProtected != null && extraProtected.contains(stack.getItem());
    }

    /**
     * Checks whether the current target block benefits from swapping a higher-durability replacement
     * or a faster effective tool from the main inventory (9..35) onto the hotbar (0..8).
     *
     * @return true if a swap packet was sent during this tick
     */
    public static boolean ensureBestToolOnHotbar(IPlayerContext ctx, BlockState targetState) {
        if (ctx == null || ctx.player() == null || ctx.playerController() == null) return false;
        LocalPlayer player = ctx.player();
        if (!(player.containerMenu instanceof InventoryMenu)) return false;

        boolean allowReplaceLow = Baritone.settings().autoReplaceLowDurabilityTools.value;
        boolean allowSwapNeeded = Baritone.settings().autoSwapBackpackTools.value;
        if (!allowReplaceLow && !allowSwapNeeded) return false;

        long now = System.currentTimeMillis();
        if (now - lastSwapMillis < SWAP_COOLDOWN_MS) return false;

        int lowThreshold = Math.max(0, Baritone.settings().itemSaverThreshold.value);
        List<Item> extraProtected = Baritone.settings().hotbarProtectedItems.value;
        List<SlotInfo> snapshot = snapshotInventory(player, targetState, lowThreshold, extraProtected);
        int selected = player.getInventory().getSelectedSlot();

        // Priority 1: replace any low-durability hotbar tool with the highest-durability healthy backpack tool.
        if (allowReplaceLow) {
            Optional<SwapPlan> lowPlan = HotbarSwapPlanner.planLowDurabilityReplacement(snapshot, selected);
            if (lowPlan.isPresent()) {
                executeSwap(ctx, player, lowPlan.get(), false);
                lastSwapMillis = now;
                return true;
            }
        }

        // Priority 2: bring a needed faster tool from backpack into an unneeded hotbar slot.
        if (allowSwapNeeded && targetState != null) {
            boolean allowSword = Baritone.settings().useSwordToMine.value;
            Optional<SwapPlan> swapPlan = HotbarSwapPlanner.planBackpackToolSwap(snapshot, selected, allowSword);
            if (swapPlan.isPresent()) {
                executeSwap(ctx, player, swapPlan.get(), true);
                lastSwapMillis = now;
                return true;
            }
        }
        return false;
    }

    public static List<SlotInfo> snapshotInventory(
            LocalPlayer player,
            BlockState targetState,
            int lowDurabilityThreshold,
            Collection<Item> extraProtected
    ) {
        Inventory inv = player.getInventory();
        List<SlotInfo> result = new ArrayList<>(36);
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            boolean empty = stack.isEmpty();
            boolean isTool = !empty && stack.getItem().components().has(DataComponents.TOOL);
            boolean isWeapon = !empty && stack.getItem().components().has(DataComponents.WEAPON);
            boolean isProtected = isProtectedHotbarItem(stack, extraProtected);
            int remaining = (!empty && stack.isDamageableItem())
                    ? (stack.getMaxDamage() - stack.getDamageValue())
                    : Integer.MAX_VALUE;
            boolean isLow = isTool && stack.isDamageableItem() && stack.getMaxDamage() > 1
                    && remaining <= lowDurabilityThreshold;
            String itemId = empty ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            ToolCategory category = classifyCategory(stack);
            double speed = targetState == null ? 1.0 : ToolSet.calculateSpeedVsBlock(stack, targetState);
            result.add(new SlotInfo(i, empty, isTool, isWeapon, isProtected, isLow, remaining, itemId, category, speed));
        }
        return result;
    }

    private static void executeSwap(IPlayerContext ctx, LocalPlayer player, SwapPlan plan, boolean selectAfter) {
        int menuSlot = plan.backpackSlot() < 9 ? plan.backpackSlot() + 36 : plan.backpackSlot();
        ctx.playerController().windowClick(
                player.inventoryMenu.containerId,
                menuSlot,
                plan.hotbarSlot(),
                ContainerInput.SWAP,
                player
        );
        if (selectAfter) {
            player.getInventory().setSelectedSlot(plan.hotbarSlot());
        }
        ctx.playerController().syncHeldItem();
    }
}
