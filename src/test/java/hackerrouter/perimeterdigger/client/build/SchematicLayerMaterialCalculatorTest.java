package hackerrouter.perimeterdigger.client.build;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SchematicLayerMaterialCalculatorTest {
	@Test
	void formatsMinecraftStackQuantitiesCleanly() {
		assertEquals("0", SchematicLayerMaterialCalculator.formatStackCount(0));
		assertEquals("17", SchematicLayerMaterialCalculator.formatStackCount(17));
		assertEquals("64 (1组)", SchematicLayerMaterialCalculator.formatStackCount(64));
		assertEquals("135 (2组+7)", SchematicLayerMaterialCalculator.formatStackCount(135));
		assertEquals("256 (4组)", SchematicLayerMaterialCalculator.formatStackCount(256));
	}

	@Test
	void buildsSortedRequirementsAndAggregatesGrandTotalAcrossLayers() {
		Map<String, Integer> inv = Map.of("minecraft:stone", 100, "minecraft:glass", 20);
		Map<String, String> names = Map.of(
				"minecraft:stone", "石头",
				"minecraft:glass", "玻璃",
				"minecraft:magma_block", "岩浆块"
		);

		var layer1Items = SchematicLayerMaterialCalculator.buildSortedRequirements(
				Map.of("minecraft:stone", 80, "minecraft:glass", 10), names, inv);
		assertEquals("minecraft:stone", layer1Items.get(0).itemId());
		assertEquals(0, layer1Items.get(0).missing());
		assertEquals("minecraft:glass", layer1Items.get(1).itemId());
		assertEquals(0, layer1Items.get(1).missing());

		var layer2Items = SchematicLayerMaterialCalculator.buildSortedRequirements(
				Map.of("minecraft:stone", 50, "minecraft:magma_block", 128), names, inv);
		assertEquals("minecraft:magma_block", layer2Items.get(0).itemId());
		assertEquals(128, layer2Items.get(0).missing());

		var layer1 = new SchematicLayerMaterialCalculator.LayerEntry(54, 90, 0, 0, 0, 0, 0, layer1Items);
		var layer2 = new SchematicLayerMaterialCalculator.LayerEntry(55, 178, 4, 100, 10, 90, 0, layer2Items);
		var total = SchematicLayerMaterialCalculator.aggregateGrandTotal(List.of(layer1, layer2), inv);

		assertEquals(268, total.blockCells());
		assertEquals(4, total.waterloggedCells());
		assertEquals(100, total.waterSourceCells());
		assertEquals(10, total.plannedWaterSeeds());
		assertEquals(90, total.savedWaterSeeds());
		// Stone total = 80 + 50 = 130 (have 100 -> missing 30), ranked first over magma_block 128.
		assertEquals("minecraft:stone", total.items().get(0).itemId());
		assertEquals(130, total.items().get(0).required());
		assertEquals(30, total.items().get(0).missing());
		assertEquals("minecraft:magma_block", total.items().get(1).itemId());
		assertEquals(128, total.items().get(1).required());
	}

	@Test
	void formatsClipboardExportAndSingleLayerChatWithWaterSavings() {
		var item = new SchematicLayerMaterialCalculator.ItemRequirement("minecraft:smooth_stone", "平滑石头", 70, 64, 6);
		var layer = new SchematicLayerMaterialCalculator.LayerEntry(55, 70, 2, 100, 10, 90, 0, List.of(item));
		var report = new SchematicLayerMaterialCalculator.Report(
				true, "", "鱼场", 55, 55, true, true, List.of(layer), layer);

		String export = SchematicLayerMaterialCalculator.formatExportReport(report);
		assertTrue(export.contains("投影逐层物品统计：鱼场"));
		assertTrue(export.contains("70 (1组+6)"));
		assertTrue(export.contains("优化倒水 10 次，省 90 次"));
		assertTrue(export.contains("缺 6"));

		String chat = SchematicLayerMaterialCalculator.formatSingleLayerChat(report, layer, 1);
		assertTrue(chat.contains("第 1/1 层 (Y=55"));
		assertTrue(chat.contains("平滑石头 x70 (1组+6)"));
		assertTrue(chat.contains("优化倒水 10 次，省 90 次"));
	}

	@Test
	void evaluatesConsecutiveSupportedLayersAndOverallSufficiency() {
		Map<String, String> names = Map.of(
				"minecraft:stone", "石头",
				"minecraft:glass", "玻璃",
				"minecraft:magma_block", "岩浆块"
		);
		Map<String, Integer> inv = Map.of("minecraft:stone", 30, "minecraft:glass", 10);
		Map<String, Integer> partialChests = Map.of(
				"minecraft:stone", 100,
				"minecraft:glass", 40,
				"minecraft:magma_block", 100
		);

		var l1 = new SchematicLayerMaterialCalculator.LayerEntry(54, 96, 0, 0, 0, 0, 0,
				SchematicLayerMaterialCalculator.buildSortedRequirements(
						Map.of("minecraft:stone", 64, "minecraft:glass", 32), names, inv, partialChests));
		var l2 = new SchematicLayerMaterialCalculator.LayerEntry(55, 164, 0, 0, 0, 0, 0,
				SchematicLayerMaterialCalculator.buildSortedRequirements(
						Map.of("minecraft:stone", 64, "minecraft:magma_block", 100), names, inv, partialChests));
		var l3 = new SchematicLayerMaterialCalculator.LayerEntry(56, 64, 0, 0, 0, 0, 0,
				SchematicLayerMaterialCalculator.buildSortedRequirements(
						Map.of("minecraft:glass", 64), names, inv, partialChests));
		List<SchematicLayerMaterialCalculator.LayerEntry> layers = List.of(l1, l2, l3);
		var total = SchematicLayerMaterialCalculator.aggregateGrandTotal(layers, inv, partialChests);

		// Partial resources: enough for layers 1 & 2 (Y=54..55), blocked on layer 3 (Y=56) missing 46 glass.
		var partial = SchematicLayerMaterialCalculator.evaluateSufficiency(layers, total, inv, partialChests, 3, 3);
		assertFalse(partial.overallEnough());
		assertEquals(2, partial.supportedLayerCount());
		assertEquals(55, partial.supportedThroughY());
		assertEquals(3, partial.firstBlockedLayerNo());
		assertEquals(56, partial.firstBlockedY());
		assertEquals(46, partial.firstBlockedShortages().get("minecraft:glass"));
		assertEquals(46, partial.overallShortages().get("minecraft:glass"));

		var partialReport = new SchematicLayerMaterialCalculator.Report(
				true, "", "鱼场", 54, 56, true, true, layers, total, partial);
		String oneLine = SchematicLayerMaterialCalculator.formatSufficiencyOneLine(partialReport);
		assertTrue(oneLine.contains("总体不足"));
		assertTrue(oneLine.contains("第 2/3 层 (Y=55)"));

		// Full resources: add 46 glass -> all 3 layers supported and overall enough!
		Map<String, Integer> fullChests = Map.of(
				"minecraft:stone", 100,
				"minecraft:glass", 86,
				"minecraft:magma_block", 100
		);
		var full = SchematicLayerMaterialCalculator.evaluateSufficiency(layers, total, inv, fullChests, 3, 3);
		assertTrue(full.overallEnough());
		assertEquals(3, full.supportedLayerCount());
		assertEquals(56, full.supportedThroughY());
		assertEquals(0, full.firstBlockedLayerNo());
		assertTrue(full.overallShortages().isEmpty());

		// Empty resources: blocked right at layer 1 (Y=54).
		var none = SchematicLayerMaterialCalculator.evaluateSufficiency(layers, total, Map.of(), Map.of(), 2, 2);
		assertFalse(none.overallEnough());
		assertEquals(0, none.supportedLayerCount());
		assertEquals(1, none.firstBlockedLayerNo());
		assertEquals(54, none.firstBlockedY());
	}

	@Test
	void resolvesTargetLayerUnfinishedListAndCompactBriefs() {
		var done0 = new SchematicLayerMaterialCalculator.LayerEntry(54, 0, 0, 0, 0, 0, 0, List.of());
		var done1 = new SchematicLayerMaterialCalculator.LayerEntry(55, 0, 0, 0, 0, 0, 0, List.of());
		var blueIce = new SchematicLayerMaterialCalculator.ItemRequirement("minecraft:blue_ice", "蓝冰", 3, 0, 3);
		var layer2 = new SchematicLayerMaterialCalculator.LayerEntry(56, 3, 0, 6242, 80, 6162, 0, List.of(blueIce));
		var stone = new SchematicLayerMaterialCalculator.ItemRequirement("minecraft:smooth_stone", "平滑石头", 120, 0, 120);
		var layer3 = new SchematicLayerMaterialCalculator.LayerEntry(57, 120, 0, 0, 0, 0, 0, List.of(stone));
		var layers = List.of(done0, done1, layer2, layer3);
		var total = SchematicLayerMaterialCalculator.aggregateGrandTotal(layers, Map.of());
		var report = new SchematicLayerMaterialCalculator.Report(
				true, "", "鱼场", 54, 57, true, true, layers, total);

		// Idle builder falls back to the lowest unfinished layer (index 2, Y=56).
		assertEquals(2, SchematicLayerMaterialCalculator.resolveTargetLayerIndex(report, null));
		// Active/paused builder Y takes precedence when present in report.
		assertEquals(3, SchematicLayerMaterialCalculator.resolveTargetLayerIndex(report, 57));
		// Out-of-range builder Y falls back to lowest unfinished layer.
		assertEquals(2, SchematicLayerMaterialCalculator.resolveTargetLayerIndex(report, 999));

		assertEquals(List.of(2, 3), SchematicLayerMaterialCalculator.unfinishedLayerIndices(report));
		String brief = SchematicLayerMaterialCalculator.formatLayerBrief(layer2, true);
		assertTrue(brief.contains("蓝冰x3"));
		assertTrue(brief.contains("水6242格(80次)"));

		String overview = SchematicLayerMaterialCalculator.formatUnfinishedOverviewChat(report, 2);
		assertTrue(overview.contains("未完成共 2/4 层"));
		assertTrue(overview.contains("第 3 层(Y=56)"));
		assertTrue(overview.contains("蓝冰x3"));
	}
}
