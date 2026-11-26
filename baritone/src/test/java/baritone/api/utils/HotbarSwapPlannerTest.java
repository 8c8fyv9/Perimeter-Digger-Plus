package baritone.api.utils;

import baritone.api.utils.HotbarSwapPlanner.FoodSwapPlan;
import baritone.api.utils.HotbarSwapPlanner.SlotInfo;
import baritone.api.utils.HotbarSwapPlanner.SwapPlan;
import baritone.api.utils.HotbarSwapPlanner.ToolCategory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HotbarSwapPlannerTest {
    private static SlotInfo empty(int slot) {
        return new SlotInfo(slot, true, false, false, false, false, 0, "minecraft:air", ToolCategory.NONE, 1.0);
    }

    private static SlotInfo junk(int slot, String id) {
        return new SlotInfo(slot, false, false, false, false, false, 0, id, ToolCategory.NONE, 1.0);
    }

    private static SlotInfo protectedItem(int slot, String id) {
        return new SlotInfo(slot, false, false, false, true, false, 0, id, ToolCategory.NONE, 1.0);
    }

    private static SlotInfo tool(int slot, String id, ToolCategory cat, int dur, boolean low, double speed) {
        return new SlotInfo(slot, false, true, cat == ToolCategory.SWORD, false, low, dur, id, cat, speed);
    }

    @Test
    void swapsBackpackShovelIntoUnneededHotbarSlotWithoutDisplacingToolsFoodOrSealing() {
        List<SlotInfo> slots = new ArrayList<>();
        slots.add(tool(0, "minecraft:diamond_pickaxe", ToolCategory.PICKAXE, 1000, false, 1.0));
        slots.add(tool(1, "minecraft:diamond_sword", ToolCategory.SWORD, 1000, false, 1.0));
        slots.add(protectedItem(2, "minecraft:cooked_beef"));
        slots.add(protectedItem(3, "minecraft:netherrack"));
        slots.add(junk(4, "minecraft:dirt"));
        slots.add(junk(5, "minecraft:cobblestone"));
        slots.add(protectedItem(6, "minecraft:firework_rocket"));
        slots.add(junk(7, "minecraft:mud"));
        slots.add(tool(8, "minecraft:diamond_axe", ToolCategory.AXE, 900, false, 1.0));
        slots.add(tool(15, "minecraft:diamond_shovel", ToolCategory.SHOVEL, 1400, false, 8.0));

        Optional<SwapPlan> plan = HotbarSwapPlanner.planBackpackToolSwap(slots, 0, false);
        assertTrue(plan.isPresent());
        assertEquals(15, plan.get().backpackSlot());
        // Should pick junk slot 7 (highest expendable slot), NOT pickaxe/sword/food/sealing/axe
        assertEquals(7, plan.get().hotbarSlot());
    }

    @Test
    void prefersEmptyHotbarSlotOverJunkWhenSwappingFromBackpack() {
        List<SlotInfo> slots = List.of(
                tool(0, "minecraft:diamond_pickaxe", ToolCategory.PICKAXE, 1000, false, 1.0),
                junk(1, "minecraft:dirt"),
                empty(2),
                junk(3, "minecraft:cobblestone"),
                tool(12, "minecraft:netherite_shovel", ToolCategory.SHOVEL, 2000, false, 9.0)
        );
        Optional<SwapPlan> plan = HotbarSwapPlanner.planBackpackToolSwap(slots, 0, false);
        assertTrue(plan.isPresent());
        assertEquals(12, plan.get().backpackSlot());
        assertEquals(2, plan.get().hotbarSlot());
    }

    @Test
    void replacesWeakerSameCategoryHotbarToolWithStrongerBackpackTool() {
        List<SlotInfo> slots = List.of(
                tool(0, "minecraft:iron_shovel", ToolCategory.SHOVEL, 200, false, 6.0),
                protectedItem(1, "minecraft:cooked_beef"),
                junk(2, "minecraft:dirt"),
                tool(18, "minecraft:netherite_shovel", ToolCategory.SHOVEL, 1900, false, 35.0)
        );
        Optional<SwapPlan> plan = HotbarSwapPlanner.planBackpackToolSwap(slots, 0, false);
        assertTrue(plan.isPresent());
        assertEquals(18, plan.get().backpackSlot());
        assertEquals(0, plan.get().hotbarSlot());
    }

    @Test
    void lowDurabilityReplacementPicksHighestDurabilityExactMatchFirstThenSameCategory() {
        List<SlotInfo> slots = List.of(
                tool(0, "minecraft:diamond_pickaxe", ToolCategory.PICKAXE, 10, true, 8.0),
                tool(1, "minecraft:diamond_shovel", ToolCategory.SHOVEL, 800, false, 1.0),
                tool(10, "minecraft:diamond_pickaxe", ToolCategory.PICKAXE, 400, false, 8.0),
                tool(11, "minecraft:diamond_pickaxe", ToolCategory.PICKAXE, 1500, false, 8.0),
                tool(12, "minecraft:netherite_pickaxe", ToolCategory.PICKAXE, 2000, false, 9.0)
        );
        Optional<SwapPlan> exactPlan = HotbarSwapPlanner.planLowDurabilityReplacement(slots, 0);
        assertTrue(exactPlan.isPresent());
        assertEquals(11, exactPlan.get().backpackSlot());
        assertEquals(0, exactPlan.get().hotbarSlot());

        // Without exact match, falls back to same ToolCategory (netherite_pickaxe for low diamond_pickaxe)
        List<SlotInfo> fallbackSlots = List.of(
                tool(0, "minecraft:diamond_pickaxe", ToolCategory.PICKAXE, 1200, false, 8.0),
                tool(2, "minecraft:diamond_shovel", ToolCategory.SHOVEL, 5, true, 8.0),
                tool(14, "minecraft:iron_shovel", ToolCategory.SHOVEL, 220, false, 6.0),
                tool(15, "minecraft:netherite_shovel", ToolCategory.SHOVEL, 1800, false, 9.0)
        );
        // Even though selected slot is 0 (pickaxe), worn-out shovel in slot 2 is replaced by slot 15!
        Optional<SwapPlan> catPlan = HotbarSwapPlanner.planLowDurabilityReplacement(fallbackSlots, 0);
        assertTrue(catPlan.isPresent());
        assertEquals(15, catPlan.get().backpackSlot());
        assertEquals(2, catPlan.get().hotbarSlot());
    }

    @Test
    void skipsSwapWhenHotbarAlreadyHasComparableHealthyTool() {
        List<SlotInfo> slots = List.of(
                tool(0, "minecraft:diamond_shovel", ToolCategory.SHOVEL, 1200, false, 8.0),
                junk(1, "minecraft:dirt"),
                tool(10, "minecraft:diamond_shovel", ToolCategory.SHOVEL, 1500, false, 8.0)
        );
        Optional<SwapPlan> plan = HotbarSwapPlanner.planBackpackToolSwap(slots, 0, false);
        assertTrue(plan.isEmpty());
    }

    @Test
    void foodSwapUsesHotbarDirectlyIfFoodAlreadyPresent() {
        List<SlotInfo> slots = List.of(
                tool(0, "minecraft:diamond_pickaxe", ToolCategory.PICKAXE, 1000, false, 1.0),
                protectedItem(3, "minecraft:cooked_beef"),
                protectedItem(15, "minecraft:cooked_beef")
        );
        Optional<FoodSwapPlan> plan = HotbarSwapPlanner.planBackpackFoodSwap(slots, Set.of("minecraft:cooked_beef"), 0, true);
        assertTrue(plan.isPresent());
        assertFalse(plan.get().fromBackpack());
        assertEquals(3, plan.get().hotbarSlot());
        assertFalse(plan.get().displacedImportantItem());
    }

    @Test
    void foodSwapPullsFromBackpackIntoExpendableSlotProtectingToolsAndSealing() {
        List<SlotInfo> slots = List.of(
                tool(0, "minecraft:diamond_pickaxe", ToolCategory.PICKAXE, 1000, false, 1.0),
                tool(1, "minecraft:diamond_shovel", ToolCategory.SHOVEL, 1000, false, 1.0),
                protectedItem(2, "minecraft:netherrack"),
                junk(5, "minecraft:dirt"),
                tool(7, "minecraft:diamond_sword", ToolCategory.SWORD, 1000, false, 1.0),
                protectedItem(18, "minecraft:golden_carrot")
        );
        Optional<FoodSwapPlan> plan = HotbarSwapPlanner.planBackpackFoodSwap(slots, Set.of("minecraft:golden_carrot"), 0, true);
        assertTrue(plan.isPresent());
        assertTrue(plan.get().fromBackpack());
        assertEquals(18, plan.get().sourceSlot());
        // Must pick junk slot 5 instead of clobbering sword in slot 7!
        assertEquals(5, plan.get().hotbarSlot());
        assertFalse(plan.get().displacedImportantItem());
    }

    @Test
    void foodSwapMarksRestoreWhenHotbarHasOnlyToolsAndProtectedItems() {
        List<SlotInfo> slots = List.of(
                tool(0, "minecraft:diamond_pickaxe", ToolCategory.PICKAXE, 1000, false, 1.0),
                tool(1, "minecraft:diamond_shovel", ToolCategory.SHOVEL, 1000, false, 1.0),
                protectedItem(2, "minecraft:netherrack"),
                protectedItem(3, "minecraft:firework_rocket"),
                protectedItem(4, "minecraft:shield"),
                protectedItem(5, "minecraft:water_bucket"),
                protectedItem(6, "minecraft:totem_of_undying"),
                tool(7, "minecraft:diamond_sword", ToolCategory.SWORD, 1000, false, 1.0),
                tool(8, "minecraft:diamond_hoe", ToolCategory.HOE, 1000, false, 1.0),
                protectedItem(20, "minecraft:cooked_beef")
        );
        Optional<FoodSwapPlan> plan = HotbarSwapPlanner.planBackpackFoodSwap(slots, Set.of("minecraft:cooked_beef"), 0, true);
        assertTrue(plan.isPresent());
        assertTrue(plan.get().fromBackpack());
        assertEquals(20, plan.get().sourceSlot());
        assertEquals(8, plan.get().hotbarSlot());
        assertTrue(plan.get().displacedImportantItem());
    }
}
