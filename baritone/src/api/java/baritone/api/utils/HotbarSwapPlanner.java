/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.api.utils;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pure inventory slot planner shared by Baritone ground-mining and creative /fly mining:
 * 1) Swaps a needed effective tool from backpack (9..35) into an unneeded hotbar slot (0..8).
 * 2) Replaces any hotbar tool that reached low durability with the highest-durability matching tool in backpack.
 */
public final class HotbarSwapPlanner {
    public enum ToolCategory {
        PICKAXE,
        SHOVEL,
        AXE,
        HOE,
        SHEARS,
        SWORD,
        OTHER_TOOL,
        NONE
    }

    public record SlotInfo(
            int slot,
            boolean empty,
            boolean isTool,
            boolean isWeapon,
            boolean isProtectedItem,
            boolean isLowDurability,
            int remainingDurability,
            String itemId,
            ToolCategory category,
            double speedVsTarget
    ) {}

    public record SwapPlan(int backpackSlot, int hotbarSlot) {}

    public record FoodSwapPlan(int sourceSlot, int hotbarSlot, boolean fromBackpack, boolean displacedImportantItem) {}

    private HotbarSwapPlanner() {}

    /**
     * Finds the best healthy tool in backpack (9..35) for the current target block when it is
     * meaningfully faster than every healthy hotbar option, and picks the least disruptive hotbar
     * slot (0..8) to swap with.
     */
    public static Optional<SwapPlan> planBackpackToolSwap(
            List<SlotInfo> slots,
            int currentSelectedHotbar,
            boolean allowSword
    ) {
        if (slots == null || slots.isEmpty()) {
            return Optional.empty();
        }
        double bestHotbarSpeed = Double.NEGATIVE_INFINITY;
        for (SlotInfo s : slots) {
            if (s.slot() < 0 || s.slot() >= 9) continue;
            if (s.isLowDurability()) continue;
            if (!allowSword && s.isWeapon()) continue;
            if (s.speedVsTarget() > bestHotbarSpeed) {
                bestHotbarSpeed = s.speedVsTarget();
            }
        }

        SlotInfo bestBackpack = null;
        for (SlotInfo s : slots) {
            if (s.slot() < 9 || s.slot() >= 36) continue;
            if (s.empty() || !s.isTool() || s.isLowDurability()) continue;
            if (!allowSword && s.isWeapon()) continue;
            if (s.speedVsTarget() <= 0.0) continue;
            if (bestBackpack == null
                    || s.speedVsTarget() > bestBackpack.speedVsTarget() + 1.0E-6
                    || (Math.abs(s.speedVsTarget() - bestBackpack.speedVsTarget()) <= 1.0E-6
                        && s.remainingDurability() > bestBackpack.remainingDurability())) {
                bestBackpack = s;
            }
        }
        if (bestBackpack == null) {
            return Optional.empty();
        }

        boolean needsSwap = bestHotbarSpeed <= 0.0
                || bestBackpack.speedVsTarget() > bestHotbarSpeed * 1.25;
        if (!needsSwap) {
            return Optional.empty();
        }

        int victim = chooseHotbarVictim(slots, bestBackpack, currentSelectedHotbar);
        if (victim < 0) {
            return Optional.empty();
        }
        return Optional.of(new SwapPlan(bestBackpack.slot(), victim));
    }

    /**
     * Checks hotbar slots (0..8, prioritizing {@code currentSelectedHotbar}) for any tool at or
     * below the durability threshold and pairs it with the highest-durability healthy replacement
     * in the backpack (9..35): exact same item first, then same {@link ToolCategory}.
     */
    public static Optional<SwapPlan> planLowDurabilityReplacement(
            List<SlotInfo> slots,
            int currentSelectedHotbar
    ) {
        if (slots == null || slots.isEmpty()) {
            return Optional.empty();
        }
        // Check currently selected hotbar slot first so active mining swaps immediately.
        if (currentSelectedHotbar >= 0 && currentSelectedHotbar < 9) {
            SlotInfo selected = findSlot(slots, currentSelectedHotbar);
            if (selected != null && !selected.empty() && selected.isLowDurability()) {
                SlotInfo replacement = findBestHealthyBackpackReplacement(slots, selected);
                if (replacement != null) {
                    return Optional.of(new SwapPlan(replacement.slot(), selected.slot()));
                }
            }
        }
        for (int h = 0; h < 9; h++) {
            if (h == currentSelectedHotbar) continue;
            SlotInfo candidate = findSlot(slots, h);
            if (candidate == null || candidate.empty() || !candidate.isLowDurability()) continue;
            SlotInfo replacement = findBestHealthyBackpackReplacement(slots, candidate);
            if (replacement != null) {
                return Optional.of(new SwapPlan(replacement.slot(), candidate.slot()));
            }
        }
        return Optional.empty();
    }

    private static SlotInfo findSlot(List<SlotInfo> slots, int index) {
        for (SlotInfo s : slots) {
            if (s.slot() == index) return s;
        }
        return null;
    }

    private static SlotInfo findBestHealthyBackpackReplacement(List<SlotInfo> slots, SlotInfo low) {
        SlotInfo bestExact = null;
        SlotInfo bestCategory = null;
        boolean matchCategory = low.category() != ToolCategory.NONE && low.category() != ToolCategory.OTHER_TOOL;
        for (SlotInfo s : slots) {
            if (s.slot() < 9 || s.slot() >= 36) continue;
            if (s.empty() || !s.isTool() || s.isLowDurability()) continue;
            if (low.itemId() != null && low.itemId().equals(s.itemId())) {
                if (bestExact == null || s.remainingDurability() > bestExact.remainingDurability()) {
                    bestExact = s;
                }
            } else if (matchCategory && s.category() == low.category()) {
                if (bestCategory == null || s.remainingDurability() > bestCategory.remainingDurability()) {
                    bestCategory = s;
                }
            }
        }
        return bestExact != null ? bestExact : bestCategory;
    }

    private static int chooseHotbarVictim(List<SlotInfo> slots, SlotInfo incoming, int currentSelectedHotbar) {
        // Tier 1: empty hotbar slot (prefer current selected if empty, else lowest empty index).
        SlotInfo selectedSlot = findSlot(slots, currentSelectedHotbar);
        if (selectedSlot != null && selectedSlot.empty()) {
            return selectedSlot.slot();
        }
        for (int h = 0; h < 9; h++) {
            SlotInfo s = findSlot(slots, h);
            if (s != null && s.empty()) return h;
        }

        // Tier 2: low-durability tool on hotbar (same category first, then any low-durability tool).
        int anyLow = -1;
        for (int h = 0; h < 9; h++) {
            SlotInfo s = findSlot(slots, h);
            if (s == null || !s.isLowDurability()) continue;
            if (incoming.category() != ToolCategory.NONE && s.category() == incoming.category()) {
                return h;
            }
            if (anyLow < 0) anyLow = h;
        }
        if (anyLow >= 0) return anyLow;

        // Tier 3: strictly weaker healthy tool of the same ToolCategory on hotbar.
        if (incoming.category() != ToolCategory.NONE && incoming.category() != ToolCategory.OTHER_TOOL) {
            int weakestSame = -1;
            double weakestSpeed = Double.POSITIVE_INFINITY;
            for (int h = 0; h < 9; h++) {
                SlotInfo s = findSlot(slots, h);
                if (s != null && s.isTool() && s.category() == incoming.category()
                        && s.speedVsTarget() < incoming.speedVsTarget() - 1.0E-6) {
                    if (s.speedVsTarget() < weakestSpeed) {
                        weakestSpeed = s.speedVsTarget();
                        weakestSame = h;
                    }
                }
            }
            if (weakestSame >= 0) return weakestSame;
        }

        // Tier 4: unneeded / disposable non-protected item (prefer highest hotbar index 8..0).
        for (int h = 8; h >= 0; h--) {
            SlotInfo s = findSlot(slots, h);
            if (s != null && !s.isTool() && !s.isWeapon() && !s.isProtectedItem()) {
                return h;
            }
        }

        // Tier 5: duplicate healthy tool of the same category (swap out the lower-durability copy).
        Map<ToolCategory, Integer> counts = new EnumMap<>(ToolCategory.class);
        for (int h = 0; h < 9; h++) {
            SlotInfo s = findSlot(slots, h);
            if (s != null && s.isTool() && s.category() != ToolCategory.NONE && s.category() != ToolCategory.OTHER_TOOL) {
                counts.merge(s.category(), 1, Integer::sum);
            }
        }
        int duplicateVictim = -1;
        int duplicateDurability = Integer.MAX_VALUE;
        for (int h = 8; h >= 0; h--) {
            SlotInfo s = findSlot(slots, h);
            if (s != null && s.isTool() && counts.getOrDefault(s.category(), 0) >= 2) {
                if (s.remainingDurability() < duplicateDurability) {
                    duplicateDurability = s.remainingDurability();
                    duplicateVictim = h;
                }
            }
        }
        if (duplicateVictim >= 0) return duplicateVictim;

        // Tier 6: non-weapon tool that is not effective on this target, else last slot as fallback.
        for (int h = 8; h >= 0; h--) {
            SlotInfo s = findSlot(slots, h);
            if (s != null && s.isTool() && !s.isWeapon() && !s.isProtectedItem()) {
                return h;
            }
        }
        return currentSelectedHotbar >= 0 && currentSelectedHotbar < 9 ? currentSelectedHotbar : 8;
    }

    /**
     * Selects an allowed food stack to eat:
     * 1) If an allowed food is already on the hotbar (0..8), select it directly (no container swap).
     * 2) Otherwise, pick an allowed food in the backpack (9..35) and swap it into the least disruptive
     *    hotbar slot (empty -> low-durability tool -> expendable junk -> duplicate/other tool).
     */
    public static Optional<FoodSwapPlan> planBackpackFoodSwap(
            List<SlotInfo> slots,
            Set<String> allowedFoodIds,
            int currentSelectedHotbar,
            boolean allowBackpackSwap
    ) {
        if (slots == null || slots.isEmpty() || allowedFoodIds == null || allowedFoodIds.isEmpty()) {
            return Optional.empty();
        }
        SlotInfo selected = findSlot(slots, currentSelectedHotbar);
        if (selected != null && !selected.empty() && allowedFoodIds.contains(selected.itemId())) {
            return Optional.of(new FoodSwapPlan(selected.slot(), selected.slot(), false, false));
        }
        for (int h = 0; h < 9; h++) {
            SlotInfo s = findSlot(slots, h);
            if (s != null && !s.empty() && allowedFoodIds.contains(s.itemId())) {
                return Optional.of(new FoodSwapPlan(h, h, false, false));
            }
        }
        if (!allowBackpackSwap) {
            return Optional.empty();
        }
        SlotInfo backpackFood = null;
        for (SlotInfo s : slots) {
            if (s.slot() < 9 || s.slot() >= 36) continue;
            if (!s.empty() && allowedFoodIds.contains(s.itemId())) {
                backpackFood = s;
                break;
            }
        }
        if (backpackFood == null) {
            return Optional.empty();
        }
        // Tier 1: empty hotbar slot
        if (selected != null && selected.empty()) {
            return Optional.of(new FoodSwapPlan(backpackFood.slot(), selected.slot(), true, false));
        }
        for (int h = 0; h < 9; h++) {
            SlotInfo s = findSlot(slots, h);
            if (s != null && s.empty()) {
                return Optional.of(new FoodSwapPlan(backpackFood.slot(), h, true, false));
            }
        }
        // Tier 2: low-durability tool on hotbar
        for (int h = 8; h >= 0; h--) {
            SlotInfo s = findSlot(slots, h);
            if (s != null && s.isLowDurability()) {
                return Optional.of(new FoodSwapPlan(backpackFood.slot(), h, true, false));
            }
        }
        // Tier 3: expendable non-tool, non-weapon, non-protected item on hotbar
        for (int h = 8; h >= 0; h--) {
            SlotInfo s = findSlot(slots, h);
            if (s != null && !s.isTool() && !s.isWeapon() && !s.isProtectedItem()) {
                return Optional.of(new FoodSwapPlan(backpackFood.slot(), h, true, false));
            }
        }
        // Tier 4: duplicate healthy tool on hotbar (mark displacedImportantItem=true so caller restores after eating)
        Map<ToolCategory, Integer> counts = new EnumMap<>(ToolCategory.class);
        for (int h = 0; h < 9; h++) {
            SlotInfo s = findSlot(slots, h);
            if (s != null && s.isTool() && s.category() != ToolCategory.NONE && s.category() != ToolCategory.OTHER_TOOL) {
                counts.merge(s.category(), 1, Integer::sum);
            }
        }
        for (int h = 8; h >= 0; h--) {
            SlotInfo s = findSlot(slots, h);
            if (s != null && s.isTool() && counts.getOrDefault(s.category(), 0) >= 2) {
                return Optional.of(new FoodSwapPlan(backpackFood.slot(), h, true, true));
            }
        }
        // Tier 5: non-weapon tool, else slot 7 (restore after eating)
        for (int h = 8; h >= 0; h--) {
            SlotInfo s = findSlot(slots, h);
            if (s != null && s.isTool() && !s.isWeapon()) {
                return Optional.of(new FoodSwapPlan(backpackFood.slot(), h, true, true));
            }
        }
        return Optional.of(new FoodSwapPlan(backpackFood.slot(), 7, true, true));
    }
}
