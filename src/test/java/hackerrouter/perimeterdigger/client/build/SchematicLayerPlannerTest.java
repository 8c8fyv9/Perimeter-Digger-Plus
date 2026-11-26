package hackerrouter.perimeterdigger.client.build;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SchematicLayerPlannerTest {
	@Test
	void summarizesPerLayerRequiredAndMissingMaterialsRespectingWrongBlockPolicy() {
		List<SchematicLayerPlanner.CellTask> tasks = List.of(
				new SchematicLayerPlanner.CellTask(1L, 0, 60, 0, SchematicLayerPlanner.CellKind.MISSING, "minecraft:stone", 1, true, false),
				new SchematicLayerPlanner.CellTask(2L, 1, 60, 0, SchematicLayerPlanner.CellKind.MISSING, "minecraft:stone_slab", 2, true, false),
				new SchematicLayerPlanner.CellTask(3L, 2, 60, 0, SchematicLayerPlanner.CellKind.WRONG_BLOCK, "minecraft:stone", 1, true, false),
				new SchematicLayerPlanner.CellTask(4L, 3, 60, 0, SchematicLayerPlanner.CellKind.EXTRA_BLOCK, "", 0, true, false)
		);
		Map<String, Integer> inv = Map.of("minecraft:stone", 1);

		// Under SKIP: wrong block is skipped and not tallied; stone needs 1 (have 1), stone_slab needs 2 (missing 2).
		var skipSummary = SchematicLayerPlanner.summarizeMaterials(
				tasks, inv, SchematicLayerPlanner.WrongBlockPolicy.SKIP, false, Set.of());
		assertEquals(1, skipSummary.required().get("minecraft:stone"));
		assertEquals(2, skipSummary.required().get("minecraft:stone_slab"));
		assertFalse(skipSummary.missing().containsKey("minecraft:stone"));
		assertEquals(2, skipSummary.missing().get("minecraft:stone_slab"));
		assertEquals(1, skipSummary.skippedWrongCells());
		assertEquals(0, skipSummary.wrongBlockCells());
		assertTrue(SchematicLayerPlanner.shouldPauseBeforeLayer(skipSummary, SchematicLayerPlanner.MissingMaterialPolicy.PAUSE_IMMEDIATELY));
		assertFalse(SchematicLayerPlanner.shouldPauseBeforeLayer(skipSummary, SchematicLayerPlanner.MissingMaterialPolicy.PLACE_AVAILABLE));

		// Under BREAK_AND_PLACE + clearExtraBlocks: wrong block requires 1 extra stone (total 2 -> missing 1), extra block counted.
		var breakSummary = SchematicLayerPlanner.summarizeMaterials(
				tasks, inv, SchematicLayerPlanner.WrongBlockPolicy.BREAK_AND_PLACE, true, Set.of());
		assertEquals(2, breakSummary.required().get("minecraft:stone"));
		assertEquals(1, breakSummary.missing().get("minecraft:stone"));
		assertEquals(1, breakSummary.wrongBlockCells());
		assertEquals(1, breakSummary.extraBlockCells());
	}

	@Test
	void prefersSupportedSolidBlocksBeforeAttachmentsAndRespectsInventoryAndCooldown() {
		var attachment = new SchematicLayerPlanner.CellTask(1L, 0, 60, 1, SchematicLayerPlanner.CellKind.MISSING, "minecraft:torch", 1, true, true);
		var solid = new SchematicLayerPlanner.CellTask(2L, 5, 60, 0, SchematicLayerPlanner.CellKind.MISSING, "minecraft:stone", 1, true, false);
		var noMaterial = new SchematicLayerPlanner.CellTask(3L, 0, 60, 0, SchematicLayerPlanner.CellKind.MISSING, "minecraft:glass", 1, true, false);
		List<SchematicLayerPlanner.CellTask> tasks = List.of(attachment, solid, noMaterial);
		Map<String, Integer> inv = Map.of("minecraft:torch", 10, "minecraft:stone", 10);

		// Even though attachment is closer, supported solid block is placed first.
		var picked1 = SchematicLayerPlanner.selectNextTask(
				tasks, 0, 60, 0,
				SchematicLayerPlanner.WrongBlockPolicy.SKIP, false,
				Set.of(), Map.of(), 1_000L, inv);
		assertEquals(solid, picked1);

		// If solid is on cooldown, falls back to attachment (still skips glass with no material).
		var picked2 = SchematicLayerPlanner.selectNextTask(
				tasks, 0, 60, 0,
				SchematicLayerPlanner.WrongBlockPolicy.SKIP, false,
				Set.of(), Map.of(2L, 10_000L), 1_000L, inv);
		assertEquals(attachment, picked2);
	}

	@Test
	void layerCompletionDiffersBetweenSkipAndBreakAndPlacePolicies() {
		var wrong = new SchematicLayerPlanner.CellTask(10L, 1, 60, 1, SchematicLayerPlanner.CellKind.WRONG_BLOCK, "minecraft:oak_planks", 1, true, false);
		var extra = new SchematicLayerPlanner.CellTask(11L, 2, 60, 1, SchematicLayerPlanner.CellKind.EXTRA_BLOCK, "", 0, true, false);
		List<SchematicLayerPlanner.CellTask> tasks = List.of(wrong, extra);

		assertTrue(SchematicLayerPlanner.isLayerComplete(tasks, SchematicLayerPlanner.WrongBlockPolicy.SKIP, false, Set.of()));
		assertFalse(SchematicLayerPlanner.isLayerComplete(tasks, SchematicLayerPlanner.WrongBlockPolicy.BREAK_AND_PLACE, false, Set.of()));
		assertTrue(SchematicLayerPlanner.isLayerComplete(tasks, SchematicLayerPlanner.WrongBlockPolicy.BREAK_AND_PLACE, false, Set.of(10L)));
		assertFalse(SchematicLayerPlanner.isLayerComplete(tasks, SchematicLayerPlanner.WrongBlockPolicy.BREAK_AND_PLACE, true, Set.of(10L)));
		assertTrue(SchematicLayerPlanner.isLayerComplete(tasks, SchematicLayerPlanner.WrongBlockPolicy.BREAK_AND_PLACE, true, Set.of(10L, 11L)));

		// Under BREAK_AND_PLACE, a wrong block is immediately actionable to break even if replacement item is not yet in inventory.
		var pickedWrong = SchematicLayerPlanner.selectNextTask(
				tasks, 0, 60, 0,
				SchematicLayerPlanner.WrongBlockPolicy.BREAK_AND_PLACE, true,
				Set.of(), Map.of(), 1_000L, Map.of());
		assertEquals(wrong, pickedWrong);
		assertFalse(SchematicLayerPlanner.hasOnlyMaterialBlockedTasks(
				List.of(wrong), SchematicLayerPlanner.WrongBlockPolicy.BREAK_AND_PLACE, true, Set.of(), Map.of()));
	}

	@Test
	void detectsMaterialBlockedLayerAndSkipsEmptyLayersBottomUp() {
		var missingBlock = new SchematicLayerPlanner.CellTask(1L, 0, 61, 0, SchematicLayerPlanner.CellKind.MISSING, "minecraft:prismarine", 1, true, false);
		assertTrue(SchematicLayerPlanner.hasOnlyMaterialBlockedTasks(
				List.of(missingBlock), SchematicLayerPlanner.WrongBlockPolicy.SKIP, false, Set.of(), Map.of()));
		assertFalse(SchematicLayerPlanner.hasOnlyMaterialBlockedTasks(
				List.of(missingBlock), SchematicLayerPlanner.WrongBlockPolicy.SKIP, false, Set.of(), Map.of("minecraft:prismarine", 1)));

		assertEquals(63, SchematicLayerPlanner.nextNonEmptyLayerY(60, 65, y -> y == 63 || y == 65));
		assertEquals(Integer.MIN_VALUE, SchematicLayerPlanner.nextNonEmptyLayerY(66, 65, y -> true));
	}

	@Test
	void formatsReadableMaterialSummaryOrderedByCount() {
		String text = SchematicLayerPlanner.formatItemSummary(
				Map.of("minecraft:torch", 4, "minecraft:stone", 64, "minecraft:glass", 12), 2);
		assertEquals("stone x64、glass x12 等 3 种", text);
	}
}
