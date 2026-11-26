package hackerrouter.perimeterdigger.client.ui;

import com.google.gson.Gson;
import hackerrouter.perimeterdigger.client.config.AdvancedConfig;
import hackerrouter.perimeterdigger.client.config.AdvancedOption;
import hackerrouter.perimeterdigger.client.config.AdvancedOptions;
import hackerrouter.perimeterdigger.client.config.DetectedAreaConfig;
import hackerrouter.perimeterdigger.client.config.PerimeterConfig;
import hackerrouter.perimeterdigger.client.config.PositionConfig;
import hackerrouter.perimeterdigger.client.config.ScanlineConfig;
import hackerrouter.perimeterdigger.client.config.UnloadingPointConfig;
import hackerrouter.perimeterdigger.client.state.AutomationState;
import hackerrouter.perimeterdigger.client.state.RetaliationPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure state/config helpers shared by the F9 hotkey and the configuration GUI. */
public final class ConfigGuiModel {
	private static final Gson GSON = new Gson();
	public static final List<String> LIQUID_POLICIES = List.of("seal_boundary", "replace", "avoid");
	public static final List<String> MINING_STRATEGIES = List.of("global_top_down", "local_top_down", "chunk_top_down");
	public static final List<String> REPAIR_MODES = List.of("repair_portal", "supply_point");
	public static final List<String> WORK_MODES = List.of("mine", "build_schematic");
	public static final List<String> SCHEMATIC_WRONG_BLOCK_POLICIES = List.of("skip", "break_and_place");
	public static final List<String> SCHEMATIC_MISSING_MATERIAL_POLICIES = List.of("place_available", "pause_immediately");
	public static final List<String> BUBBLE_COLUMN_ESCAPE_MODES = List.of("auto", "scaffold_first", "bucket_first", "walk_or_fly");

	public record AdvancedField(String key, String label) {}
	public record AdvancedPage(String title, List<AdvancedField> fields) {}

	public static final List<AdvancedPage> ADVANCED_PAGES = List.of(
			new AdvancedPage("耐久与进食", List.of(
					new AdvancedField("tool_durability_threshold", "工具耐久阈值 (≥0)"),
					new AdvancedField("elytra_durability_threshold", "鞘翅耐久阈值 (≥0)"),
					new AdvancedField("emergency_flight_durability_threshold", "紧急飞行鞘翅耐久 (≥0)"),
					new AdvancedField("food_level_threshold", "饥饿进食阈值 (0..20)"),
					new AdvancedField("health_eating_threshold", "血量进食阈值 (0..20)")
			)),
			new AdvancedPage("耗材补给", List.of(
					new AdvancedField("food_resupply_trigger", "食物补给触发量 (≥0)"),
					new AdvancedField("food_resupply_target", "食物补给目标量 (≥触发)"),
					new AdvancedField("firework_resupply_trigger", "烟花补给触发量 (≥0)"),
					new AdvancedField("firework_resupply_target", "烟花补给目标量 (≥触发)")
			)),
			new AdvancedPage("挖掘与卸货", List.of(
					new AdvancedField("inventory_reserved_slots", "物品栏预留空槽 (0..35)"),
					new AdvancedField("mining_blocks_per_empty_slot", "每空槽挖掘方块数 (≥1)"),
					new AdvancedField("drop_collection_radius", "掉落物收集半径 (≥1)"),
					new AdvancedField("drop_collection_stable_seconds", "掉落物稳定等待秒数 (>0)"),
					new AdvancedField("unload_landing_search_radius", "卸货着陆搜索半径 (≥1)"),
					new AdvancedField("unload_edge_inset", "卸货通道内缩 (0..0.3)")
			)),
			new AdvancedPage("寻路与飞行", List.of(
					new AdvancedField("elytra_navigation_min_distance", "鞘翅寻路最短距离 (≥0)"),
					new AdvancedField("navigation_stall_timeout_seconds", "寻路停滞超时秒数 (>0)"),
					new AdvancedField("navigation_retry_count", "寻路重试次数 (≥0)"),
					new AdvancedField("flight_retry_count", "鞘翅飞行重试次数 (≥1)"),
					new AdvancedField("portal_transition_cost", "传送门路线代价 (≥0)"),
					new AdvancedField("portal_transition_timeout_seconds", "传送门切换超时秒数 (>0)")
			)),
			new AdvancedPage("传送门出口", List.of(
					new AdvancedField("portal_exit_timeout_seconds", "出口搜索超时秒数 (>0)"),
					new AdvancedField("portal_exit_min_radius", "出口最小半径 (≥1)"),
					new AdvancedField("portal_exit_max_radius", "出口最大半径 (≥最小)"),
					new AdvancedField("portal_exit_vertical_radius", "出口垂直半径 (≥0)")
			)),
			new AdvancedPage("交互与停滞脱困", List.of(
					new AdvancedField("repair_experience_stable_seconds", "经验修复等待秒数 (>0)"),
					new AdvancedField("supply_interaction_timeout_seconds", "补给交互超时秒数 (>0)"),
					new AdvancedField("furnace_interaction_timeout_seconds", "熔炉交互超时秒数 (>0)"),
					new AdvancedField("mining_stall_timeout_seconds", "挖掘停滞判定秒数 (>0)"),
					new AdvancedField("mining_stall_movement_distance", "停滞脱困移动距离 (≥1)"),
					new AdvancedField("mining_stall_cooldown_seconds", "停滞脱困冷却秒数 (≥0)")
			))
	);

	private static final List<String[]> ORDERED_PAIRS = List.of(
			new String[]{"food_resupply_trigger", "food_resupply_target", "食物补给触发量不能大于目标量"},
			new String[]{"firework_resupply_trigger", "firework_resupply_target", "烟花补给触发量不能大于目标量"},
			new String[]{"portal_exit_min_radius", "portal_exit_max_radius", "传送门出口最小半径不能大于最大半径"}
	);

	public enum HotkeyCommand {
		START,
		PAUSE,
		RESUME
	}

	private ConfigGuiModel() {}

	public static HotkeyCommand resolveShortPress(AutomationState state) {
		if (state == AutomationState.PAUSED) return HotkeyCommand.RESUME;
		if (RetaliationPolicy.active(state)) return HotkeyCommand.PAUSE;
		return HotkeyCommand.START;
	}

	public static PerimeterConfig copy(PerimeterConfig source) {
		PerimeterConfig cloned = GSON.fromJson(GSON.toJson(source), PerimeterConfig.class);
		if (cloned == null) cloned = new PerimeterConfig();
		cloned.normalize();
		return cloned;
	}

	public static void copyInto(PerimeterConfig source, PerimeterConfig target) {
		PerimeterConfig fresh = copy(source);
		target.schemaVersion = fresh.schemaVersion;
		target.diggingMinY = fresh.diggingMinY;
		target.diggingMaxY = fresh.diggingMaxY;
		target.consumableSupplyPoint = fresh.consumableSupplyPoint;
		target.durabilitySupplyPoint = fresh.durabilitySupplyPoint;
		target.bedPoint = fresh.bedPoint;
		target.perimeterPortalOverworld = fresh.perimeterPortalOverworld;
		target.perimeterPortalNether = fresh.perimeterPortalNether;
		target.repairPortalOverworld = fresh.repairPortalOverworld;
		target.repairPortalNether = fresh.repairPortalNether;
		target.furnaceRowStart = fresh.furnaceRowStart;
		target.furnaceRowEnd = fresh.furnaceRowEnd;
		target.unloadingPoints = fresh.unloadingPoints;
		target.schematicResourcePoints = fresh.schematicResourcePoints;
		target.liquidPolicy = fresh.liquidPolicy;
		target.miningStrategy = fresh.miningStrategy;
		target.durabilityRecoveryMode = fresh.durabilityRecoveryMode;
		target.workMode = fresh.workMode;
		target.schematicWrongBlockPolicy = fresh.schematicWrongBlockPolicy;
		target.schematicMissingMaterialPolicy = fresh.schematicMissingMaterialPolicy;
		target.schematicClearExtraBlocks = fresh.schematicClearExtraBlocks;
		target.schematicOptimizeWater = fresh.schematicOptimizeWater;
		target.bubbleColumnEscapeMode = fresh.bubbleColumnEscapeMode;
		target.sealingBlocks = fresh.sealingBlocks;
		target.scaffoldBlocks = fresh.scaffoldBlocks;
		target.foods = fresh.foods;
		target.unloadingWhitelist = fresh.unloadingWhitelist;
		target.miningWhitelist = fresh.miningWhitelist;
		target.miningBlacklist = fresh.miningBlacklist;
		target.detectedArea = fresh.detectedArea;
		target.functions = fresh.functions;
		target.advanced = fresh.advanced;
		target.normalize();
	}

	public static DetectedAreaConfig buildRectangleArea(int x0, int z0, int x1, int z1, int minY, int maxY) {
		if (minY > maxY) {
			throw new IllegalArgumentException("最小 Y 不能大于最大 Y");
		}
		int minX = Math.min(x0, x1);
		int maxX = Math.max(x0, x1);
		int minZ = Math.min(z0, z1);
		int maxZ = Math.max(z0, z1);
		long width = (long) maxX - minX + 1L;
		long depth = (long) maxZ - minZ + 1L;
		if (depth > 65536L || width > 65536L) {
			throw new IllegalArgumentException("选区单边长度过大（最大 65536 格）");
		}
		DetectedAreaConfig area = new DetectedAreaConfig();
		area.boundaryBlock = "rectangle";
		area.boundaryY = maxY;
		area.minX = minX;
		area.maxX = maxX;
		area.minZ = minZ;
		area.maxZ = maxZ;
		area.columnCount = width * depth;
		for (int z = minZ; ; z++) {
			area.scanlines.add(new ScanlineConfig(z, minX, maxX));
			if (z == maxZ) break;
		}
		return area;
	}

	/** Parses comma/newline/semicolon-separated identifiers, adding default minecraft: namespace and deduplicating. */
	public static List<String> parseIdList(String raw) {
		if (raw == null || raw.isBlank()) return new ArrayList<>();
		LinkedHashSet<String> result = new LinkedHashSet<>();
		for (String token : raw.split("[,;\\s]+")) {
			String trimmed = token.trim().toLowerCase(Locale.ROOT);
			if (trimmed.isEmpty()) continue;
			result.add(trimmed.contains(":") ? trimmed : "minecraft:" + trimmed);
		}
		return new ArrayList<>(result);
	}

	public static String formatIdList(List<String> ids) {
		return ids == null || ids.isEmpty() ? "" : String.join(", ", ids);
	}

	public static String cycle(List<String> options, String current) {
		int index = options.indexOf(current);
		return options.get((index + 1) % options.size());
	}

	public static String primaryUnloadPointName(PerimeterConfig config) {
		if (config.unloadingPoints == null || config.unloadingPoints.isEmpty()) return "default";
		return config.unloadingPoints.keySet().iterator().next();
	}

	public static PositionConfig getPoint(PerimeterConfig config, String key) {
		return switch (key) {
			case "unloading_point" -> {
				if (config.unloadingPoints == null || config.unloadingPoints.isEmpty()) yield null;
				UnloadingPointConfig up = config.unloadingPoints.values().iterator().next();
				yield up == null ? null : new PositionConfig(up.x, up.minY, up.z);
			}
			case "consumable_supply_point" -> config.consumableSupplyPoint;
			case "durability_supply_point" -> config.durabilitySupplyPoint;
			case "bed_point" -> config.bedPoint;
			case "perimeter_portal_overworld" -> config.perimeterPortalOverworld;
			case "perimeter_portal_nether" -> config.perimeterPortalNether;
			case "repair_portal_overworld" -> config.repairPortalOverworld;
			case "repair_portal_nether" -> config.repairPortalNether;
			case "furnace_row_start" -> config.furnaceRowStart;
			case "furnace_row_end" -> config.furnaceRowEnd;
			default -> throw new IllegalArgumentException("Unknown point key: " + key);
		};
	}

	public static void setPoint(PerimeterConfig config, String key, PositionConfig value) {
		switch (key) {
			case "unloading_point" -> {
				String name = primaryUnloadPointName(config);
				if (value == null) config.unloadingPoints.remove(name);
				else config.unloadingPoints.put(name, new UnloadingPointConfig(value.x, value.y, value.z));
			}
			case "consumable_supply_point" -> config.consumableSupplyPoint = value;
			case "durability_supply_point" -> config.durabilitySupplyPoint = value;
			case "bed_point" -> config.bedPoint = value;
			case "perimeter_portal_overworld" -> config.perimeterPortalOverworld = value;
			case "perimeter_portal_nether" -> config.perimeterPortalNether = value;
			case "repair_portal_overworld" -> config.repairPortalOverworld = value;
			case "repair_portal_nether" -> config.repairPortalNether = value;
			case "furnace_row_start" -> config.furnaceRowStart = value;
			case "furnace_row_end" -> config.furnaceRowEnd = value;
			default -> throw new IllegalArgumentException("Unknown point key: " + key);
		}
	}

	public static Map<String, String> readAdvancedTexts(AdvancedConfig config) {
		Map<String, String> texts = new LinkedHashMap<>();
		for (AdvancedPage page : ADVANCED_PAGES) {
			for (AdvancedField field : page.fields()) {
				Number value = AdvancedOptions.get(field.key()).get(config);
				texts.put(field.key(), formatNumber(value));
			}
		}
		return texts;
	}

	public static void applyAdvancedTexts(AdvancedConfig target, Map<String, String> texts) {
		Map<String, String> labels = new LinkedHashMap<>();
		Map<String, Double> parsed = new LinkedHashMap<>();
		for (AdvancedPage page : ADVANCED_PAGES) {
			for (AdvancedField field : page.fields()) {
				labels.put(field.key(), field.label());
				String raw = texts.get(field.key());
				if (raw == null || raw.isBlank()) {
					throw new IllegalArgumentException("请填写 " + field.label());
				}
				try {
					parsed.put(field.key(), Double.parseDouble(raw.trim()));
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException(field.label() + " 必须是数字: " + raw);
				}
			}
		}
		for (String[] pair : ORDERED_PAIRS) {
			double lo = parsed.get(pair[0]);
			double hi = parsed.get(pair[1]);
			if (lo > hi) {
				throw new IllegalArgumentException(pair[2]);
			}
		}
		// Apply into a copy first so a single validation error never leaves target half-mutated.
		AdvancedConfig staging = GSON.fromJson(GSON.toJson(target), AdvancedConfig.class);
		if (staging == null) staging = new AdvancedConfig();
		// Pre-widen upper bounds of coupled pairs so simultaneous increases/decreases never trip ordering checks.
		for (String[] pair : ORDERED_PAIRS) {
			AdvancedOption highOpt = AdvancedOptions.get(pair[1]);
			double currentHigh = highOpt.get(staging).doubleValue();
			double desiredHigh = parsed.get(pair[1]);
			if (desiredHigh > currentHigh) {
				setOptionWithLabel(highOpt, staging, desiredHigh, labels.get(pair[1]));
			}
		}
		for (Map.Entry<String, Double> entry : parsed.entrySet()) {
			AdvancedOption opt = AdvancedOptions.get(entry.getKey());
			setOptionWithLabel(opt, staging, entry.getValue(), labels.get(entry.getKey()));
		}
		// Copy validated values onto target via AdvancedOptions.
		for (String[] pair : ORDERED_PAIRS) {
			AdvancedOption highOpt = AdvancedOptions.get(pair[1]);
			double currentHigh = highOpt.get(target).doubleValue();
			double finalHigh = highOpt.get(staging).doubleValue();
			if (finalHigh > currentHigh) highOpt.set(target, finalHigh);
		}
		for (AdvancedOption opt : AdvancedOptions.all()) {
			if (parsed.containsKey(opt.key())) {
				opt.set(target, opt.get(staging).doubleValue());
			}
		}
	}

	private static void setOptionWithLabel(AdvancedOption opt, AdvancedConfig cfg, double value, String label) {
		try {
			opt.set(cfg, value);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(label + " 无效: " + e.getMessage());
		}
	}

	private static String formatNumber(Number n) {
		if (n instanceof Integer || n instanceof Long) return Long.toString(n.longValue());
		double d = n.doubleValue();
		if (d == Math.rint(d) && Math.abs(d) < 1_000_000) {
			return String.format(Locale.ROOT, "%.1f", d);
		}
		return Double.toString(d);
	}

	public static List<String> unloadingPointNames(PerimeterConfig config) {
		if (config.unloadingPoints == null || config.unloadingPoints.isEmpty()) return List.of();
		return new ArrayList<>(config.unloadingPoints.keySet());
	}

	public static void setNamedUnloadingPoint(PerimeterConfig config, String previousName, String rawName, PositionConfig pos) {
		String name = rawName == null ? "" : rawName.trim();
		if (name.isEmpty() || !name.matches("[A-Za-z0-9_\\-]+")) {
			throw new IllegalArgumentException("卸货点名称只能包含字母、数字、下划线或短横线");
		}
		if (pos == null) {
			throw new IllegalArgumentException("请完整填写卸货点坐标 X / Y / Z");
		}
		if (previousName != null && !previousName.equals(name)) {
			config.unloadingPoints.remove(previousName);
		}
		config.unloadingPoints.put(name, new UnloadingPointConfig(pos.x, pos.y, pos.z));
	}

	public static void removeNamedUnloadingPoint(PerimeterConfig config, String name) {
		if (name != null && config.unloadingPoints != null) {
			config.unloadingPoints.remove(name);
		}
	}

	public static List<String> schematicResourcePointNames(PerimeterConfig config) {
		if (config.schematicResourcePoints == null || config.schematicResourcePoints.isEmpty()) return List.of();
		return new ArrayList<>(config.schematicResourcePoints.keySet());
	}

	public static void setNamedSchematicResourcePoint(
			PerimeterConfig config,
			String previousName,
			String rawName,
			PositionConfig pos
	) {
		String name = rawName == null ? "" : rawName.trim();
		if (name.isEmpty() || !name.matches("[A-Za-z0-9_\\-]+")) {
			throw new IllegalArgumentException("资源点名称只能包含字母、数字、下划线或短横线");
		}
		if (pos == null) {
			throw new IllegalArgumentException("请完整填写资源点坐标 X / Y / Z");
		}
		if (config.schematicResourcePoints == null) {
			config.schematicResourcePoints = new LinkedHashMap<>();
		}
		if (previousName != null && !previousName.equals(name)) {
			config.schematicResourcePoints.remove(previousName);
		}
		config.schematicResourcePoints.put(name, new PositionConfig(pos.x, pos.y, pos.z));
	}

	public static void removeNamedSchematicResourcePoint(PerimeterConfig config, String name) {
		if (name != null && config.schematicResourcePoints != null) {
			config.schematicResourcePoints.remove(name);
		}
	}
}
