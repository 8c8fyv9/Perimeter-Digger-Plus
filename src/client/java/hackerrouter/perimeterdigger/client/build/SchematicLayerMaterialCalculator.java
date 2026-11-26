package hackerrouter.perimeterdigger.client.build;

import hackerrouter.perimeterdigger.client.config.PerimeterConfig;
import hackerrouter.perimeterdigger.client.detect.LitematicaFootprintReader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Calculates per-layer and grand-total item requirements (plus water placement / optimization
 * statistics) for the active Litematica placement. Pure aggregation/formatting methods have
 * zero Minecraft runtime dependencies so they can be unit-tested directly.
 */
public final class SchematicLayerMaterialCalculator {
	public record ItemRequirement(
			String itemId,
			String displayName,
			int required,
			int available,
			int missing,
			int inResourceChests,
			int combinedMissing
	) {
		public ItemRequirement(String itemId, String displayName, int required, int available, int missing) {
			this(itemId, displayName, required, available, missing, 0, missing);
		}
	}

	public record SufficiencyResult(
			int configuredChests,
			int scannedChests,
			boolean overallEnough,
			int totalLayers,
			int supportedLayerCount,
			int supportedThroughY,
			int firstBlockedLayerNo,
			int firstBlockedY,
			Map<String, Integer> firstBlockedShortages,
			Map<String, Integer> overallShortages
	) {
		public boolean hasConfiguredChests() {
			return configuredChests > 0;
		}

		public boolean hasScannedChests() {
			return scannedChests > 0;
		}
	}

	public record LayerEntry(
			int y,
			int blockCells,
			int waterloggedCells,
			int waterSourceCells,
			int plannedWaterSeeds,
			int savedWaterSeeds,
			int unloadedCells,
			List<ItemRequirement> items
	) {
		public int totalRequiredItems() {
			int sum = 0;
			for (ItemRequirement r : items) sum += r.required();
			return sum;
		}

		public int totalMissingItems() {
			int sum = 0;
			for (ItemRequirement r : items) sum += r.missing();
			return sum;
		}

		public boolean hasWork() {
			return blockCells > 0 || waterloggedCells > 0 || waterSourceCells > 0;
		}
	}

	public record Report(
			boolean ok,
			String errorMessage,
			String placementName,
			int minY,
			int maxY,
			boolean remainingOnly,
			boolean optimizeWater,
			List<LayerEntry> layers,
			LayerEntry grandTotal,
			SufficiencyResult sufficiency
	) {
		public Report(
				boolean ok,
				String errorMessage,
				String placementName,
				int minY,
				int maxY,
				boolean remainingOnly,
				boolean optimizeWater,
				List<LayerEntry> layers,
				LayerEntry grandTotal
		) {
			this(ok, errorMessage, placementName, minY, maxY, remainingOnly, optimizeWater, layers, grandTotal, null);
		}

		public static Report fail(String message) {
			return new Report(false, message, "", 0, 0, true, true, List.of(),
					new LayerEntry(0, 0, 0, 0, 0, 0, 0, List.of()), null);
		}
	}

	private SchematicLayerMaterialCalculator() {}

	/** Pure formatting helper: converts raw item count into Minecraft stack notation (e.g. 135 -> "135 (2组+7)"). */
	public static String formatStackCount(int count) {
		if (count < 64) {
			return Integer.toString(count);
		}
		int stacks = count / 64;
		int rem = count % 64;
		if (rem == 0) {
			return count + " (" + stacks + "组)";
		}
		return count + " (" + stacks + "组+" + rem + ")";
	}

	/**
	 * Pure aggregation & sorting helper for a single layer (or grand total): computes available and
	 * missing counts from inventory and sorts by required count descending, then item ID.
	 */
	public static List<ItemRequirement> buildSortedRequirements(
			Map<String, Integer> requiredById,
			Map<String, String> displayNamesById,
			Map<String, Integer> inventoryById
	) {
		return buildSortedRequirements(requiredById, displayNamesById, inventoryById, Map.of());
	}

	public static List<ItemRequirement> buildSortedRequirements(
			Map<String, Integer> requiredById,
			Map<String, String> displayNamesById,
			Map<String, Integer> inventoryById,
			Map<String, Integer> resourceChestsById
	) {
		if (requiredById == null || requiredById.isEmpty()) {
			return List.of();
		}
		List<ItemRequirement> list = new ArrayList<>(requiredById.size());
		for (Map.Entry<String, Integer> e : requiredById.entrySet()) {
			String id = e.getKey();
			int need = Math.max(0, e.getValue());
			if (need <= 0) continue;
			int have = inventoryById == null ? 0 : Math.max(0, inventoryById.getOrDefault(id, 0));
			int inChests = resourceChestsById == null ? 0 : Math.max(0, resourceChestsById.getOrDefault(id, 0));
			int miss = Math.max(0, need - have);
			int combinedMiss = Math.max(0, need - (have + inChests));
			String name = displayNamesById != null && displayNamesById.containsKey(id)
					? displayNamesById.get(id)
					: shortId(id);
			list.add(new ItemRequirement(id, name, need, have, miss, inChests, combinedMiss));
		}
		list.sort(Comparator.comparingInt(ItemRequirement::required).reversed()
				.thenComparing(ItemRequirement::itemId));
		return List.copyOf(list);
	}

	/** Pure helper aggregating multiple layers into a grand-total {@link LayerEntry}. */
	public static LayerEntry aggregateGrandTotal(List<LayerEntry> layers, Map<String, Integer> inventoryById) {
		return aggregateGrandTotal(layers, inventoryById, Map.of());
	}

	public static LayerEntry aggregateGrandTotal(
			List<LayerEntry> layers,
			Map<String, Integer> inventoryById,
			Map<String, Integer> resourceChestsById
	) {
		Map<String, Integer> totalReq = new LinkedHashMap<>();
		Map<String, String> names = new HashMap<>();
		int blocks = 0;
		int waterlogged = 0;
		int waterSources = 0;
		int waterSeeds = 0;
		int waterSaved = 0;
		int unloaded = 0;
		for (LayerEntry layer : layers) {
			blocks += layer.blockCells();
			waterlogged += layer.waterloggedCells();
			waterSources += layer.waterSourceCells();
			waterSeeds += layer.plannedWaterSeeds();
			waterSaved += layer.savedWaterSeeds();
			unloaded += layer.unloadedCells();
			for (ItemRequirement item : layer.items()) {
				totalReq.merge(item.itemId(), item.required(), Integer::sum);
				names.putIfAbsent(item.itemId(), item.displayName());
			}
		}
		return new LayerEntry(
				Integer.MIN_VALUE,
				blocks,
				waterlogged,
				waterSources,
				waterSeeds,
				waterSaved,
				unloaded,
				buildSortedRequirements(totalReq, names, inventoryById, resourceChestsById)
		);
	}

	/**
	 * Pure bottom-up simulation determining how many consecutive layers (and up to which Y level)
	 * can be built with {@code playerInventory + resourceChestPool}, plus whether total resources
	 * are sufficient for the entire schematic.
	 */
	public static SufficiencyResult evaluateSufficiency(
			List<LayerEntry> layers,
			LayerEntry grandTotal,
			Map<String, Integer> playerInventory,
			Map<String, Integer> resourceChestPool,
			int configuredChests,
			int scannedChests
	) {
		Map<String, Integer> remaining = new HashMap<>();
		if (playerInventory != null) {
			for (Map.Entry<String, Integer> e : playerInventory.entrySet()) {
				if (e.getValue() != null && e.getValue() > 0) {
					remaining.merge(e.getKey(), e.getValue(), Integer::sum);
				}
			}
		}
		if (resourceChestPool != null) {
			for (Map.Entry<String, Integer> e : resourceChestPool.entrySet()) {
				if (e.getValue() != null && e.getValue() > 0) {
					remaining.merge(e.getKey(), e.getValue(), Integer::sum);
				}
			}
		}

		int supportedCount = 0;
		int supportedThroughY = Integer.MIN_VALUE;
		int firstBlockedNo = 0;
		int firstBlockedY = Integer.MIN_VALUE;
		Map<String, Integer> blockedShortages = new LinkedHashMap<>();

		for (int i = 0; i < layers.size(); i++) {
			LayerEntry layer = layers.get(i);
			Map<String, Integer> layerDeficit = new LinkedHashMap<>();
			for (ItemRequirement req : layer.items()) {
				int avail = remaining.getOrDefault(req.itemId(), 0);
				if (avail < req.required()) {
					layerDeficit.put(req.itemId(), req.required() - avail);
				}
			}
			if (!layerDeficit.isEmpty()) {
				firstBlockedNo = i + 1;
				firstBlockedY = layer.y();
				blockedShortages = layerDeficit;
				break;
			}
			for (ItemRequirement req : layer.items()) {
				remaining.merge(req.itemId(), -req.required(), Integer::sum);
			}
			supportedCount++;
			supportedThroughY = layer.y();
		}

		Map<String, Integer> overallShortages = new LinkedHashMap<>();
		if (grandTotal != null) {
			for (ItemRequirement req : grandTotal.items()) {
				int combined = (playerInventory == null ? 0 : Math.max(0, playerInventory.getOrDefault(req.itemId(), 0)))
						+ (resourceChestPool == null ? 0 : Math.max(0, resourceChestPool.getOrDefault(req.itemId(), 0)));
				if (combined < req.required()) {
					overallShortages.put(req.itemId(), req.required() - combined);
				}
			}
		}
		boolean overallEnough = overallShortages.isEmpty();
		return new SufficiencyResult(
				configuredChests,
				scannedChests,
				overallEnough,
				layers.size(),
				supportedCount,
				supportedThroughY,
				firstBlockedNo,
				firstBlockedY,
				Map.copyOf(blockedShortages),
				Map.copyOf(overallShortages)
		);
	}

	/** Concise 1-line summary of resource-point sufficiency for UI banner and chat output. */
	public static String formatSufficiencyOneLine(Report report) {
		if (report == null || !report.ok() || report.sufficiency() == null) {
			return "暂无资源判定";
		}
		SufficiencyResult s = report.sufficiency();
		String poolTag = s.hasConfiguredChests()
				? "背包+资源点(已扫 " + s.scannedChests() + "/" + s.configuredChests() + " 箱)"
				: "仅背包(未配置资源点)";
		if (s.overallEnough()) {
			return "总体足够！可完整建完全部 " + s.totalLayers() + " 层 (Y=" + report.minY() + ".." + report.maxY() + ") [" + poolTag + "]";
		}
		if (s.supportedLayerCount() > 0) {
			return "总体不足 | 可支持建至第 " + s.supportedLayerCount() + "/" + s.totalLayers()
					+ " 层 (Y=" + s.supportedThroughY() + ")，卡在第 " + s.firstBlockedLayerNo()
					+ " 层 (Y=" + s.firstBlockedY() + ") 缺 "
					+ SchematicLayerPlanner.formatItemSummary(s.firstBlockedShortages(), 3)
					+ " [" + poolTag + "]";
		}
		return "总体不足 | 连第 " + s.firstBlockedLayerNo() + " 层 (Y=" + s.firstBlockedY() + ") 也不足，缺 "
				+ SchematicLayerPlanner.formatItemSummary(s.firstBlockedShortages(), 3)
				+ " [" + poolTag + "]";
	}

	/**
	 * Resolves the index in {@code report.layers()} of the active target placement layer:
	 * 1) If the builder is currently active/paused on {@code activeBuilderY} and that Y exists
	 *    in {@code report.layers()}, return that index;
	 * 2) Otherwise return the lowest unfinished layer ({@link LayerEntry#hasWork()});
	 * 3) Fallback to 0 if all layers are complete.
	 */
	public static int resolveTargetLayerIndex(Report report, Integer activeBuilderY) {
		if (report == null || !report.ok() || report.layers().isEmpty()) {
			return 0;
		}
		List<LayerEntry> layers = report.layers();
		if (activeBuilderY != null) {
			for (int i = 0; i < layers.size(); i++) {
				if (layers.get(i).y() == activeBuilderY) {
					return i;
				}
			}
		}
		for (int i = 0; i < layers.size(); i++) {
			if (layers.get(i).hasWork()) {
				return i;
			}
		}
		return 0;
	}

	/** Returns 0-based indices of all layers that still have remaining blocks or water work. */
	public static List<Integer> unfinishedLayerIndices(Report report) {
		if (report == null || !report.ok() || report.layers().isEmpty()) {
			return List.of();
		}
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < report.layers().size(); i++) {
			if (report.layers().get(i).hasWork()) {
				out.add(i);
			}
		}
		return List.copyOf(out);
	}

	/** Compact one-line summary of a layer's required items and water work for the unfinished-layers list. */
	public static String formatLayerBrief(LayerEntry layer, boolean optimizeWater) {
		if (layer == null) return "无";
		StringBuilder sb = new StringBuilder();
		if (!layer.items().isEmpty()) {
			int limit = Math.min(3, layer.items().size());
			for (int i = 0; i < limit; i++) {
				if (i > 0) sb.append(", ");
				ItemRequirement r = layer.items().get(i);
				sb.append(r.displayName()).append("x").append(r.required());
			}
			if (layer.items().size() > limit) {
				sb.append(" 等").append(layer.items().size()).append("种");
			}
		}
		if (layer.waterloggedCells() > 0 || layer.waterSourceCells() > 0) {
			if (!sb.isEmpty()) sb.append(" | ");
			if (layer.waterSourceCells() > 0) {
				sb.append("水").append(layer.waterSourceCells()).append("格(")
						.append(layer.plannedWaterSeeds()).append("次)");
			}
			if (layer.waterloggedCells() > 0) {
				if (layer.waterSourceCells() > 0) sb.append("+");
				sb.append("含水").append(layer.waterloggedCells());
			}
		}
		if (sb.isEmpty()) {
			return "已完成";
		}
		return sb.toString();
	}

	/** Concise chat summary for the unfinished-layers overview tab. */
	public static String formatUnfinishedOverviewChat(Report report, int targetLayerIndex) {
		if (report == null || !report.ok()) {
			return "[Perimeter] 无投影统计结果";
		}
		List<Integer> unfinished = unfinishedLayerIndices(report);
		if (unfinished.isEmpty()) {
			return "[Perimeter] 投影「" + report.placementName() + "」全部 " + report.layers().size() + " 层均已完成！";
		}
		int safeTarget = Math.max(0, Math.min(report.layers().size() - 1, targetLayerIndex));
		LayerEntry targetLayer = report.layers().get(safeTarget);
		StringBuilder sb = new StringBuilder();
		sb.append("[Perimeter] 投影「").append(report.placementName()).append("」未完成共 ")
				.append(unfinished.size()).append("/").append(report.layers().size())
				.append(" 层 | 当前目标第 ").append(safeTarget + 1).append(" 层(Y=").append(targetLayer.y()).append(")：")
				.append(formatLayerBrief(targetLayer, report.optimizeWater()));
		if (report.sufficiency() != null) {
			sb.append(" | ").append(formatSufficiencyOneLine(report));
		}
		return sb.toString();
	}

	/** Formats the complete per-layer + grand-total report into clean multi-line text for clipboard export. */
	public static String formatExportReport(Report report) {
		if (report == null || !report.ok()) {
			return report == null ? "无统计结果" : report.errorMessage();
		}
		StringBuilder sb = new StringBuilder();
		sb.append("投影逐层物品统计：").append(report.placementName())
				.append(" Y[").append(report.minY()).append("..").append(report.maxY()).append("]")
				.append("（口径：").append(report.remainingOnly() ? "仅待放" : "投影全部").append("）\n");
		if (report.sufficiency() != null) {
			sb.append("资源判定：").append(formatSufficiencyOneLine(report)).append("\n");
		}

		LayerEntry total = report.grandTotal();
		sb.append("\n=== 全部层总计 ===\n");
		appendLayerDetails(sb, total, report.optimizeWater());

		for (int i = 0; i < report.layers().size(); i++) {
			LayerEntry layer = report.layers().get(i);
			if (!layer.hasWork()) continue;
			sb.append("\n--- 第 ").append(i + 1).append("/").append(report.layers().size())
					.append(" 层 (Y=").append(layer.y()).append(") ---\n");
			appendLayerDetails(sb, layer, report.optimizeWater());
		}
		return sb.toString();
	}

	/** Formats a concise single-layer summary line for client chat output. */
	public static String formatSingleLayerChat(Report report, LayerEntry entry, int layerIndex1Based) {
		StringBuilder sb = new StringBuilder();
		if (entry.y() == Integer.MIN_VALUE) {
			sb.append("[Perimeter] 投影「").append(report.placementName()).append("」全部层总计（")
					.append(report.remainingOnly() ? "仅待放" : "投影全部").append("）：");
		} else {
			sb.append("[Perimeter] 投影第 ").append(layerIndex1Based).append("/").append(report.layers().size())
					.append(" 层 (Y=").append(entry.y()).append("，")
					.append(report.remainingOnly() ? "仅待放" : "投影全部").append(")：");
		}
		if (entry.items().isEmpty() && entry.waterloggedCells() == 0 && entry.waterSourceCells() == 0) {
			sb.append("无待放物品");
			return sb.toString();
		}
		if (!entry.items().isEmpty()) {
			int limit = Math.min(8, entry.items().size());
			for (int i = 0; i < limit; i++) {
				if (i > 0) sb.append("、");
				ItemRequirement r = entry.items().get(i);
				sb.append(r.displayName()).append(" x").append(formatStackCount(r.required()));
			}
			if (entry.items().size() > limit) {
				sb.append(" 等 ").append(entry.items().size()).append(" 种");
			}
		}
		if (entry.waterloggedCells() > 0 || entry.waterSourceCells() > 0) {
			sb.append(" | 水体：");
			if (entry.waterloggedCells() > 0) {
				sb.append("含水 ").append(entry.waterloggedCells()).append(" 格");
				if (entry.waterSourceCells() > 0) sb.append("，");
			}
			if (entry.waterSourceCells() > 0) {
				sb.append("水源 ").append(entry.waterSourceCells()).append(" 格");
				if (report.optimizeWater() && entry.savedWaterSeeds() > 0) {
					sb.append("（优化倒水 ").append(entry.plannedWaterSeeds())
							.append(" 次，省 ").append(entry.savedWaterSeeds()).append(" 次）");
				} else {
					sb.append("（倒水 ").append(entry.plannedWaterSeeds()).append(" 次）");
				}
			}
		}
		return sb.toString();
	}

	private static void appendLayerDetails(StringBuilder sb, LayerEntry layer, boolean optimizeWater) {
		sb.append("方块格数：").append(layer.blockCells());
		if (layer.waterloggedCells() > 0) {
			sb.append(" | 含水方块：").append(layer.waterloggedCells()).append(" 格");
		}
		if (layer.waterSourceCells() > 0) {
			sb.append(" | 水源：").append(layer.waterSourceCells()).append(" 格（")
					.append(optimizeWater ? "优化倒水 " : "倒水 ").append(layer.plannedWaterSeeds()).append(" 次");
			if (optimizeWater && layer.savedWaterSeeds() > 0) {
				sb.append("，省 ").append(layer.savedWaterSeeds()).append(" 次");
			}
			sb.append("）");
		}
		if (layer.unloadedCells() > 0) {
			sb.append(" | 未加载格：").append(layer.unloadedCells());
		}
		sb.append("\n");
		for (ItemRequirement item : layer.items()) {
			sb.append("  - ").append(item.displayName())
					.append(" (").append(item.itemId()).append("): ")
					.append(formatStackCount(item.required()));
			if (item.inResourceChests() > 0) {
				if (item.combinedMissing() > 0) {
					sb.append(" [背包 ").append(item.available())
							.append(" + 资源点 ").append(item.inResourceChests())
							.append("，仍缺 ").append(item.combinedMissing()).append("]");
				} else {
					sb.append(" [充足: 背包 ").append(item.available())
							.append(" + 资源点 ").append(item.inResourceChests()).append("]");
				}
			} else if (item.missing() > 0) {
				sb.append(" [背包有 ").append(item.available()).append("，缺 ").append(item.missing()).append("]");
			} else {
				sb.append(" [背包充足: ").append(item.available()).append("]");
			}
			sb.append("\n");
		}
	}

	/**
	 * Scans every Y layer in the active Litematica placement and tallies required items, inventory
	 * shortages, and water placement/optimization counts.
	 */
	public static Report computeAllLayers(Minecraft mc, PerimeterConfig config, boolean remainingOnly) {
		if (mc == null || mc.player == null || mc.level == null) {
			return Report.fail("当前未进入世界，无法计算投影所需物品");
		}
		LitematicaFootprintReader.ReadResult fpResult = LitematicaFootprintReader.readCurrentFootprint(mc);
		if (!fpResult.ok()) {
			return Report.fail(fpResult.userMessage());
		}
		LitematicaPrinterBridge bridge = new LitematicaPrinterBridge();
		Level schematicWorld = bridge.getSchematicWorld();
		if (schematicWorld == null) {
			return Report.fail("Litematica 投影世界尚未加载，请先启用并加载投影");
		}

		LitematicaFootprintReader.Footprint fp = fpResult.footprint();
		Level level = mc.level;
		LocalPlayer player = mc.player;
		boolean optimizeWater = config == null || config.schematicOptimizeWater;
		boolean replaceFluids = bridge.isReplaceFluidsEnabled();
		SchematicLayerPlanner.WrongBlockPolicy wrongPolicy = config == null
				? SchematicLayerPlanner.WrongBlockPolicy.SKIP
				: SchematicLayerPlanner.WrongBlockPolicy.parse(config.schematicWrongBlockPolicy);

		Map<String, Integer> invCounts = countInventory(player);
		SchematicResourceCache.AggregatedPool resourcePool = SchematicResourceCache.aggregateConfiguredResources(
				level, config == null ? Map.of() : config.schematicResourcePoints);
		Map<String, Integer> carriedShulkerItems = SchematicResourceCache.countCarriedShulkerContents(player);
		int carriedShulkerBoxes = SchematicResourceCache.countCarriedShulkerBoxes(player);
		Map<String, Integer> combinedResourcePool = new LinkedHashMap<>(resourcePool.itemCounts());
		for (Map.Entry<String, Integer> e : carriedShulkerItems.entrySet()) {
			combinedResourcePool.merge(e.getKey(), e.getValue(), Integer::sum);
		}
		int effectiveConfigured = resourcePool.uniqueChestCount() + (carriedShulkerBoxes > 0 ? 1 : 0);
		int effectiveScanned = resourcePool.scannedChestCount() + (carriedShulkerBoxes > 0 ? 1 : 0);
		Map<BlockState, List<ItemStack>> stateItemCache = new HashMap<>();
		List<LayerEntry> layers = new ArrayList<>(Math.max(1, fp.maxY() - fp.minY() + 1));
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		for (int y = fp.minY(); y <= fp.maxY(); y++) {
			Map<String, Integer> layerReq = new LinkedHashMap<>();
			Map<String, String> displayNames = new HashMap<>();
			int blockCells = 0;
			int waterloggedCells = 0;
			int waterSourceCells = 0;
			int unloadedCells = 0;
			boolean hasWaterOnLayer = false;

			for (int z = fp.minZ(); z <= fp.maxZ(); z++) {
				for (int x = fp.minX(); x <= fp.maxX(); x++) {
					cursor.set(x, y, z);
					if (!schematicWorld.isLoaded(cursor)) {
						unloadedCells++;
						continue;
					}
					BlockState target = schematicWorld.getBlockState(cursor);
					if (target.isAir() || SchematicWaterPlacer.isSchematicFlowingFluid(target)) {
						continue;
					}

					boolean worldLoaded = level.isLoaded(cursor);
					BlockState current = worldLoaded ? level.getBlockState(cursor) : null;

					if (SchematicWaterPlacer.isSchematicWaterSource(target)) {
						if (!remainingOnly || !worldLoaded || !SchematicWaterPlacer.isWorldWaterSource(current)) {
							if (!remainingOnly || !worldLoaded || canCountWaterTarget(current, wrongPolicy)) {
								waterSourceCells++;
								hasWaterOnLayer = true;
							}
						}
						continue;
					}

					if (SchematicWaterPlacer.isSchematicWaterlogged(target)) {
						boolean alreadyWaterlogged = worldLoaded && current != null
								&& current.getBlock() == target.getBlock()
								&& current.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
								&& current.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED);
						if (!remainingOnly || !alreadyWaterlogged) {
							waterloggedCells++;
							hasWaterOnLayer = true;
						}
					}

					if (remainingOnly && worldLoaded && current != null) {
						SchematicLayerPlanner.CellKind kind = SchematicBuildController.classifyCell(
								level, schematicWorld, cursor, replaceFluids);
						if (kind == SchematicLayerPlanner.CellKind.MATCHED
								|| kind == SchematicLayerPlanner.CellKind.EXTRA_BLOCK
								|| kind == SchematicLayerPlanner.CellKind.WRONG_INTERACTABLE) {
							continue;
						}
						if (kind == SchematicLayerPlanner.CellKind.WRONG_BLOCK
								&& wrongPolicy == SchematicLayerPlanner.WrongBlockPolicy.SKIP) {
							continue;
						}
					}

					blockCells++;
					List<ItemStack> reqStacks = stateItemCache.computeIfAbsent(
							target, st -> bridge.getRequiredBuildItems(st, schematicWorld, cursor));
					for (ItemStack stack : reqStacks) {
						if (stack == null || stack.isEmpty()) continue;
						String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
						layerReq.merge(id, stack.getCount(), Integer::sum);
						displayNames.putIfAbsent(id, stack.getHoverName().getString());
					}
				}
			}

			int plannedWaterSeeds = waterSourceCells;
			int savedWaterSeeds = 0;
			if (hasWaterOnLayer && waterSourceCells > 0 && optimizeWater) {
				if (remainingOnly) {
					SchematicWaterPlacer.WaterSummary ws = SchematicWaterPlacer.previewLayer(
							level, schematicWorld, fp, y, null, true);
					plannedWaterSeeds = ws.plannedSeedCount();
					savedWaterSeeds = ws.savedCount();
				} else {
					WaterSourceOptimizer.Plan fullPlan = computeSchematicOnlyWaterPlan(schematicWorld, fp, y);
					plannedWaterSeeds = fullPlan.totalSeedPlacements();
					savedWaterSeeds = fullPlan.savedPlacements();
				}
			}

			layers.add(new LayerEntry(
					y,
					blockCells,
					waterloggedCells,
					waterSourceCells,
					plannedWaterSeeds,
					savedWaterSeeds,
					unloadedCells,
					buildSortedRequirements(layerReq, displayNames, invCounts, combinedResourcePool)
			));
		}

		LayerEntry grandTotal = aggregateGrandTotal(layers, invCounts, combinedResourcePool);
		SufficiencyResult sufficiency = evaluateSufficiency(
				layers,
				grandTotal,
				invCounts,
				combinedResourcePool,
				effectiveConfigured,
				effectiveScanned
		);
		return new Report(
				true,
				"",
				fp.placementName(),
				fp.minY(),
				fp.maxY(),
				remainingOnly,
				optimizeWater,
				List.copyOf(layers),
				grandTotal,
				sufficiency
		);
	}

	private static boolean canCountWaterTarget(BlockState current, SchematicLayerPlanner.WrongBlockPolicy wrongPolicy) {
		if (current == null || current.isAir() || current.canBeReplaced()
				|| current.getBlock() instanceof LiquidBlock
				|| SchematicWaterPlacer.isWorldWaterSource(current)) {
			return true;
		}
		return wrongPolicy == SchematicLayerPlanner.WrongBlockPolicy.BREAK_AND_PLACE;
	}

	private static WaterSourceOptimizer.Plan computeSchematicOnlyWaterPlan(
			Level schematicWorld,
			LitematicaFootprintReader.Footprint fp,
			int y
	) {
		var convertible = new java.util.LinkedHashSet<WaterSourceOptimizer.Cell>();
		var forced = new java.util.LinkedHashSet<WaterSourceOptimizer.Cell>();
		var preexisting = new java.util.HashSet<WaterSourceOptimizer.Cell>();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int z = fp.minZ(); z <= fp.maxZ(); z++) {
			for (int x = fp.minX(); x <= fp.maxX(); x++) {
				cursor.set(x, y, z);
				if (!schematicWorld.isLoaded(cursor)) continue;
				BlockState target = schematicWorld.getBlockState(cursor);
				if (SchematicWaterPlacer.isSchematicWaterlogged(target)) {
					preexisting.add(new WaterSourceOptimizer.Cell(x, z));
				} else if (SchematicWaterPlacer.isSchematicWaterSource(target)) {
					BlockState below = schematicWorld.getBlockState(cursor.below());
					boolean supported = below.isSolid() || SchematicWaterPlacer.isWorldWaterSource(below);
					if (supported) convertible.add(new WaterSourceOptimizer.Cell(x, z));
					else forced.add(new WaterSourceOptimizer.Cell(x, z));
				}
			}
		}
		return WaterSourceOptimizer.plan(new WaterSourceOptimizer.WaterLayerInput(
				convertible, forced, preexisting, null, null));
	}

	private static Map<String, Integer> countInventory(LocalPlayer player) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		Inventory inv = player.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) continue;
			String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			counts.merge(id, stack.getCount(), Integer::sum);
		}
		return counts;
	}

	private static String shortId(String id) {
		if (id == null) return "unknown";
		String lower = id.toLowerCase(Locale.ROOT);
		return lower.startsWith("minecraft:") ? lower.substring("minecraft:".length()) : lower;
	}
}
