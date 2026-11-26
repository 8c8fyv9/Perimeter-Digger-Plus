package hackerrouter.perimeterdigger.client.ui;

import com.google.gson.Gson;
import hackerrouter.perimeterdigger.client.config.AdvancedConfig;
import hackerrouter.perimeterdigger.client.config.AdvancedOptions;
import hackerrouter.perimeterdigger.client.config.DetectedAreaConfig;
import hackerrouter.perimeterdigger.client.config.FunctionConfig;
import hackerrouter.perimeterdigger.client.config.PerimeterConfig;
import hackerrouter.perimeterdigger.client.config.PositionConfig;
import hackerrouter.perimeterdigger.client.config.ScanlineConfig;
import hackerrouter.perimeterdigger.client.state.AutomationState;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConfigGuiModelTest {
	@Test
	void tapHoldDetectorDistinguishesShortPressAndLongPress() {
		TapHoldDetector detector = new TapHoldDetector(400L);
		assertEquals(TapHoldDetector.Action.NONE, detector.update(true, true, 1000L));
		assertEquals(TapHoldDetector.Action.NONE, detector.update(true, false, 1250L));
		assertEquals(TapHoldDetector.Action.SHORT_PRESS, detector.update(false, false, 1300L));

		assertEquals(TapHoldDetector.Action.NONE, detector.update(true, true, 2000L));
		assertEquals(TapHoldDetector.Action.LONG_PRESS, detector.update(true, false, 2400L));
		assertEquals(TapHoldDetector.Action.NONE, detector.update(true, false, 2600L));
		assertEquals(TapHoldDetector.Action.NONE, detector.update(false, false, 2700L));

		// Sub-tick fast click (already released before tick poll)
		assertEquals(TapHoldDetector.Action.SHORT_PRESS, detector.update(false, true, 3000L));
	}

	@Test
	void f9ShortPressResolvesStartPauseResumeByControllerState() {
		assertEquals(ConfigGuiModel.HotkeyCommand.START, ConfigGuiModel.resolveShortPress(AutomationState.IDLE));
		assertEquals(ConfigGuiModel.HotkeyCommand.START, ConfigGuiModel.resolveShortPress(AutomationState.ERROR));
		assertEquals(ConfigGuiModel.HotkeyCommand.START, ConfigGuiModel.resolveShortPress(AutomationState.COMPLETE));
		assertEquals(ConfigGuiModel.HotkeyCommand.PAUSE, ConfigGuiModel.resolveShortPress(AutomationState.MINING));
		assertEquals(ConfigGuiModel.HotkeyCommand.PAUSE, ConfigGuiModel.resolveShortPress(AutomationState.COLLECTING_DROPS));
		assertEquals(ConfigGuiModel.HotkeyCommand.RESUME, ConfigGuiModel.resolveShortPress(AutomationState.PAUSED));
	}

	@Test
	void buildRectangleAreaNormalizesCornersAndRejectsInvertedY() {
		DetectedAreaConfig area = ConfigGuiModel.buildRectangleArea(10, 20, 5, 15, 55, 87);
		assertEquals("rectangle", area.boundaryBlock);
		assertEquals(5, area.minX);
		assertEquals(10, area.maxX);
		assertEquals(15, area.minZ);
		assertEquals(20, area.maxZ);
		assertEquals(36L, area.columnCount);
		assertEquals(6, area.scanlines.size());
		assertThrows(IllegalArgumentException.class, () -> ConfigGuiModel.buildRectangleArea(0, 0, 1, 1, 80, 55));
	}

	@Test
	void parseIdListNormalizesNamespacesAndDeduplicates() {
		assertEquals(List.of("minecraft:netherrack", "minecraft:dirt", "mod:block"),
				ConfigGuiModel.parseIdList("netherrack, minecraft:dirt; NETHERRACK  mod:block"));
		assertEquals(List.of(), ConfigGuiModel.parseIdList("   "));
	}

	@Test
	void pointGettersAndSettersRoundTripAllNamedPointsIncludingUnload() {
		PerimeterConfig cfg = new PerimeterConfig();
		assertNull(ConfigGuiModel.getPoint(cfg, "unloading_point"));
		ConfigGuiModel.setPoint(cfg, "unloading_point", new PositionConfig(10, 64, -20));
		assertEquals(new PositionConfig(10, 64, -20).toString(), ConfigGuiModel.getPoint(cfg, "unloading_point").toString());
		ConfigGuiModel.setPoint(cfg, "unloading_point", null);
		assertNull(ConfigGuiModel.getPoint(cfg, "unloading_point"));

		ConfigGuiModel.setPoint(cfg, "bed_point", new PositionConfig(1, 2, 3));
		assertEquals(1, cfg.bedPoint.x);
		assertEquals(2, cfg.bedPoint.y);
		assertEquals(3, cfg.bedPoint.z);
	}

	@Test
	void areaOverlayGeometryBuildsExactInclusiveBoxAndHandlesLargeCoordinates() {
		assertTrue(AreaOverlayGeometry.build(null, 55, 87).isEmpty());
		DetectedAreaConfig rect = ConfigGuiModel.buildRectangleArea(10851931, 10165118, 10852017, 10165205, 55, 87);
		AreaOverlayGeometry geom = AreaOverlayGeometry.build(rect, 55, 87);
		assertEquals(6, geom.quads().size());
		assertEquals(12, geom.lines().size());
		// First quad is bottom face at y = 55 - eps, spanning [10851931, 10852018] x [10165118, 10165206]
		AreaOverlayGeometry.Quad bottom = geom.quads().getFirst();
		assertEquals(10851931.0, bottom.x0(), 0.01);
		assertEquals(10852018.0, bottom.x1(), 0.01);
		assertEquals(55.0, bottom.y0(), 0.01);
		assertEquals(10165118.0, bottom.z0(), 0.01);
		assertEquals(10165206.0, bottom.z2(), 0.01);
		// Second quad is top face at y = 88 + eps
		assertEquals(88.0, geom.quads().get(1).y0(), 0.01);
	}

	@Test
	void areaOverlayGeometryBuildsExteriorWallsForPolygonScanlines() {
		DetectedAreaConfig poly = new DetectedAreaConfig();
		poly.boundaryBlock = "minecraft:glass";
		poly.boundaryY = 70;
		poly.minX = 0;
		poly.maxX = 5;
		poly.minZ = 0;
		poly.maxZ = 1;
		// L-shape: row z=0 is [0..5], row z=1 is [0..2]
		poly.scanlines.add(new ScanlineConfig(0, 0, 5));
		poly.scanlines.add(new ScanlineConfig(1, 0, 2));
		AreaOverlayGeometry geom = AreaOverlayGeometry.build(poly, 60, 70);
		assertFalse(geom.isEmpty());
		// 2 rows * (top + bottom + west + east = 4) + north(z=0: [0..5]) + south(z=0: [3..5]) + south(z=1: [0..2]) = 11 quads
		assertEquals(11, geom.quads().size());
		assertFalse(geom.lines().isEmpty());
	}

	@Test
	void legacyFunctionConfigWithoutRenderAreaDefaultsToTrue() {
		FunctionConfig parsed = new Gson().fromJson("{\"collectDrops\":false,\"unload\":false}", FunctionConfig.class);
		assertFalse(parsed.collectDrops);
		assertFalse(parsed.unload);
		assertTrue(parsed.durabilityProtection);
		assertTrue(parsed.renderArea);
		assertTrue(parsed.flightMovement);
		assertFalse(parsed.miningWhitelist);
		assertFalse(parsed.miningBlacklist);
	}

	@Test
	void advancedPagesCoverEveryAdvancedOptionWithoutDuplicates() {
		Set<String> guiKeys = new HashSet<>();
		for (ConfigGuiModel.AdvancedPage page : ConfigGuiModel.ADVANCED_PAGES) {
			for (ConfigGuiModel.AdvancedField field : page.fields()) {
				assertTrue(guiKeys.add(field.key()), "Duplicate GUI advanced key: " + field.key());
			}
		}
		assertEquals(new HashSet<>(AdvancedOptions.keys()), guiKeys);
	}

	@Test
	void applyAdvancedTextsSupportsZeroDurabilityAndSimultaneousPairUpdates() {
		AdvancedConfig cfg = new AdvancedConfig();
		Map<String, String> texts = ConfigGuiModel.readAdvancedTexts(cfg);
		texts.put("tool_durability_threshold", "0");
		texts.put("health_eating_threshold", "12.5");
		texts.put("food_resupply_trigger", "100");
		texts.put("food_resupply_target", "128");
		texts.put("mining_stall_movement_distance", "6");
		texts.put("mining_stall_cooldown_seconds", "10.0");
		ConfigGuiModel.applyAdvancedTexts(cfg, texts);
		assertEquals(0, cfg.toolDurabilityThreshold);
		assertEquals(12.5, cfg.healthEatingThreshold, 0.001);
		assertEquals(100, cfg.foodResupplyTrigger);
		assertEquals(128, cfg.foodResupplyTarget);
		assertEquals(6, cfg.miningStallMovementDistance);
		assertEquals(10.0, cfg.miningStallCooldownSeconds, 0.001);

		// Simultaneous decrease of coupled pair
		texts.put("food_resupply_trigger", "4");
		texts.put("food_resupply_target", "16");
		ConfigGuiModel.applyAdvancedTexts(cfg, texts);
		assertEquals(4, cfg.foodResupplyTrigger);
		assertEquals(16, cfg.foodResupplyTarget);

		// Inverted pair throws and leaves cfg untouched
		texts.put("food_resupply_trigger", "32");
		texts.put("food_resupply_target", "16");
		assertThrows(IllegalArgumentException.class, () -> ConfigGuiModel.applyAdvancedTexts(cfg, texts));
		assertEquals(4, cfg.foodResupplyTrigger);
	}

	@Test
	void namedUnloadingPointsSupportMultipleEntriesAddAndRemove() {
		PerimeterConfig cfg = new PerimeterConfig();
		ConfigGuiModel.setNamedUnloadingPoint(cfg, null, "north", new PositionConfig(10, 64, 20));
		ConfigGuiModel.setNamedUnloadingPoint(cfg, null, "south", new PositionConfig(30, 64, 40));
		assertEquals(List.of("north", "south"), ConfigGuiModel.unloadingPointNames(cfg));
		ConfigGuiModel.removeNamedUnloadingPoint(cfg, "north");
		assertEquals(List.of("south"), ConfigGuiModel.unloadingPointNames(cfg));
		assertThrows(IllegalArgumentException.class,
				() -> ConfigGuiModel.setNamedUnloadingPoint(cfg, null, "bad name!", new PositionConfig(1, 2, 3)));
	}

	@Test
	void copyIntoPreservesMiningWhitelistAndDefaultsDisabled() {
		PerimeterConfig src = new PerimeterConfig();
		assertFalse(src.functions.miningWhitelist);
		assertFalse(src.functions.miningBlacklist);
		assertEquals("global_top_down", src.miningStrategy);
		src.functions.miningWhitelist = true;
		src.functions.miningBlacklist = true;
		src.miningStrategy = ConfigGuiModel.cycle(ConfigGuiModel.MINING_STRATEGIES, src.miningStrategy);
		assertEquals("local_top_down", src.miningStrategy);
		src.miningStrategy = ConfigGuiModel.cycle(ConfigGuiModel.MINING_STRATEGIES, src.miningStrategy);
		assertEquals("chunk_top_down", src.miningStrategy);
		src.miningWhitelist = ConfigGuiModel.parseIdList("diamond_ore, deepslate_diamond_ore");
		src.miningBlacklist = ConfigGuiModel.parseIdList("mangrove_log, mud");
		PerimeterConfig dst = new PerimeterConfig();
		ConfigGuiModel.copyInto(src, dst);
		assertTrue(dst.functions.miningWhitelist);
		assertTrue(dst.functions.miningBlacklist);
		assertEquals("chunk_top_down", dst.miningStrategy);
		assertEquals(List.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"), dst.miningWhitelist);
		assertEquals(List.of("minecraft:mangrove_log", "minecraft:mud"), dst.miningBlacklist);
	}

	@Test
	void copyIntoPreservesDetectedAreaDimension() {
		PerimeterConfig src = new PerimeterConfig();
		src.detectedArea = ConfigGuiModel.buildRectangleArea(0, 0, 5, 5, 60, 70);
		src.detectedArea.dimension = "minecraft:overworld";
		assertTrue(src.functions.autoSwapBackpackTool);
		assertTrue(src.functions.autoReplaceLowDurabilityTool);
		assertTrue(src.functions.autoSwapBackpackFood);
		src.functions.autoSwapBackpackTool = false;
		src.functions.autoReplaceLowDurabilityTool = false;
		src.functions.autoSwapBackpackFood = false;
		PerimeterConfig dst = new PerimeterConfig();
		ConfigGuiModel.copyInto(src, dst);
		assertNotNull(dst.detectedArea);
		assertEquals("minecraft:overworld", dst.detectedArea.dimension);
		assertFalse(dst.functions.autoSwapBackpackTool);
		assertFalse(dst.functions.autoReplaceLowDurabilityTool);
		assertFalse(dst.functions.autoSwapBackpackFood);
	}

	@Test
	void scaffoldBlocksCopiesAndNormalizesCleanly() {
		PerimeterConfig src = new PerimeterConfig();
		src.scaffoldBlocks = null;
		src.normalize();
		assertEquals(List.of(), src.scaffoldBlocks);
		src.scaffoldBlocks = ConfigGuiModel.parseIdList("cobblestone, minecraft:mud");
		PerimeterConfig dst = new PerimeterConfig();
		ConfigGuiModel.copyInto(src, dst);
		assertEquals(List.of("minecraft:cobblestone", "minecraft:mud"), dst.scaffoldBlocks);
	}

	@Test
	void schematicWaterOptimizationDefaultsEnabledAndCopiesCleanly() {
		PerimeterConfig src = new PerimeterConfig();
		assertTrue(src.schematicOptimizeWater);
		src.schematicOptimizeWater = false;
		ConfigGuiModel.setNamedSchematicResourcePoint(src, null, "chest_a", new PositionConfig(10, 64, 20));
		ConfigGuiModel.setNamedSchematicResourcePoint(src, null, "chest_b", new PositionConfig(12, 64, 20));
		PerimeterConfig dst = new PerimeterConfig();
		ConfigGuiModel.copyInto(src, dst);
		assertFalse(dst.schematicOptimizeWater);
		assertEquals(List.of("chest_a", "chest_b"), ConfigGuiModel.schematicResourcePointNames(dst));
		ConfigGuiModel.removeNamedSchematicResourcePoint(dst, "chest_a");
		assertEquals(List.of("chest_b"), ConfigGuiModel.schematicResourcePointNames(dst));
		assertThrows(IllegalArgumentException.class,
				() -> ConfigGuiModel.setNamedSchematicResourcePoint(dst, null, "bad name!", new PositionConfig(1, 2, 3)));
	}
}
