package hackerrouter.perimeterdigger.client.build;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Pure layer-by-layer schematic printing planner (zero Minecraft runtime dependencies).
 * Handles layer progression, per-layer material tally & shortage checks, wrong-block policies,
 * support-first / attachment-second ordering, and failure cooldown/skip logic.
 */
public final class SchematicLayerPlanner {
	public static final int MAX_TARGET_FAILURES = 3;
	public static final long TARGET_COOLDOWN_MS = 15_000L;

	public enum WrongBlockPolicy {
		SKIP,
		BREAK_AND_PLACE;

		public static WrongBlockPolicy parse(String raw) {
			if (raw != null && "break_and_place".equalsIgnoreCase(raw.trim())) {
				return BREAK_AND_PLACE;
			}
			return SKIP;
		}
	}

	public enum MissingMaterialPolicy {
		PLACE_AVAILABLE,
		PAUSE_IMMEDIATELY;

		public static MissingMaterialPolicy parse(String raw) {
			if (raw != null && "pause_immediately".equalsIgnoreCase(raw.trim())) {
				return PAUSE_IMMEDIATELY;
			}
			return PLACE_AVAILABLE;
		}
	}

	public enum CellKind {
		MATCHED,
		MISSING,
		WRONG_INTERACTABLE,
		WRONG_BLOCK,
		EXTRA_BLOCK
	}

	public record CellTask(
			long key,
			int x,
			int y,
			int z,
			CellKind kind,
			String itemId,
			int itemCount,
			boolean hasSupport,
			boolean attachment
	) {}

	public record MaterialSummary(
			Map<String, Integer> required,
			Map<String, Integer> available,
			Map<String, Integer> missing,
			int missingBlockCells,
			int wrongBlockCells,
			int extraBlockCells,
			int skippedWrongCells
	) {
		public boolean hasShortage() {
			return !missing.isEmpty();
		}

		public int totalRequiredItems() {
			int sum = 0;
			for (int count : required.values()) {
				sum += count;
			}
			return sum;
		}

		public int totalMissingItems() {
			int sum = 0;
			for (int count : missing.values()) {
				sum += count;
			}
			return sum;
		}
	}

	private SchematicLayerPlanner() {}

	/**
	 * Aggregates required items, available inventory counts, and missing shortages for all
	 * actionable cells in the current layer.
	 */
	public static MaterialSummary summarizeMaterials(
			List<CellTask> tasks,
			Map<String, Integer> inventoryCounts,
			WrongBlockPolicy wrongPolicy,
			boolean clearExtraBlocks,
			Set<Long> skippedKeys
	) {
		Map<String, Integer> required = new LinkedHashMap<>();
		int missingCells = 0;
		int wrongCells = 0;
		int extraCells = 0;
		int skippedWrong = 0;

		for (CellTask task : tasks) {
			if (task.kind() == CellKind.MATCHED) {
				continue;
			}
			if (task.kind() == CellKind.EXTRA_BLOCK) {
				if (wrongPolicy == WrongBlockPolicy.BREAK_AND_PLACE && clearExtraBlocks
						&& (skippedKeys == null || !skippedKeys.contains(task.key()))) {
					extraCells++;
				}
				continue;
			}
			if (task.kind() == CellKind.WRONG_BLOCK) {
				if (wrongPolicy == WrongBlockPolicy.SKIP) {
					skippedWrong++;
					continue;
				}
				if (skippedKeys != null && skippedKeys.contains(task.key())) {
					skippedWrong++;
					continue;
				}
				wrongCells++;
				addRequiredItem(required, task);
				continue;
			}
			if (skippedKeys != null && skippedKeys.contains(task.key())) {
				continue;
			}
			if (task.kind() == CellKind.MISSING) {
				missingCells++;
				addRequiredItem(required, task);
			}
		}

		Map<String, Integer> available = new LinkedHashMap<>();
		Map<String, Integer> missing = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : required.entrySet()) {
			String id = entry.getKey();
			int need = entry.getValue();
			int have = Math.max(0, inventoryCounts == null ? 0 : inventoryCounts.getOrDefault(id, 0));
			available.put(id, have);
			if (have < need) {
				missing.put(id, need - have);
			}
		}
		return new MaterialSummary(required, available, missing, missingCells, wrongCells, extraCells, skippedWrong);
	}

	private static void addRequiredItem(Map<String, Integer> required, CellTask task) {
		if (task.itemId() == null || task.itemId().isBlank() || task.itemCount() <= 0) {
			return;
		}
		required.merge(task.itemId(), task.itemCount(), Integer::sum);
	}

	public static boolean shouldPauseBeforeLayer(MaterialSummary summary, MissingMaterialPolicy policy) {
		return policy == MissingMaterialPolicy.PAUSE_IMMEDIATELY && summary.hasShortage();
	}

	public static boolean hasMaterialForTask(CellTask task, Map<String, Integer> inventoryCounts) {
		if (task.kind() == CellKind.EXTRA_BLOCK || task.kind() == CellKind.WRONG_INTERACTABLE) {
			return true;
		}
		if (task.itemId() == null || task.itemId().isBlank() || task.itemCount() <= 0) {
			return true;
		}
		int have = inventoryCounts == null ? 0 : inventoryCounts.getOrDefault(task.itemId(), 0);
		return have >= task.itemCount();
	}

	/**
	 * Picks the next actionable cell in the current layer:
	 * Pass 1: Nearest supported non-attachment MISSING (with material), WRONG_INTERACTABLE, or WRONG_BLOCK (if BREAK_AND_PLACE & with material).
	 * Pass 2: Nearest attachment or currently-unsupported MISSING (with material).
	 * Pass 3: Nearest EXTRA_BLOCK (only when BREAK_AND_PLACE && clearExtraBlocks).
	 */
	public static CellTask selectNextTask(
			List<CellTask> tasks,
			int playerX,
			int playerY,
			int playerZ,
			WrongBlockPolicy wrongPolicy,
			boolean clearExtraBlocks,
			Set<Long> skippedKeys,
			Map<Long, Long> cooldownUntil,
			long nowMs,
			Map<String, Integer> inventoryCounts
	) {
		return selectNextTask(
				tasks, playerX, playerY, playerZ,
				null, null,
				wrongPolicy, clearExtraBlocks, skippedKeys, cooldownUntil, nowMs, inventoryCounts);
	}

	public static CellTask selectNextTask(
			List<CellTask> tasks,
			int playerX,
			int playerY,
			int playerZ,
			Integer anchorX,
			Integer anchorZ,
			WrongBlockPolicy wrongPolicy,
			boolean clearExtraBlocks,
			Set<Long> skippedKeys,
			Map<Long, Long> cooldownUntil,
			long nowMs,
			Map<String, Integer> inventoryCounts
	) {
		CellTask bestPass1 = null;
		double bestPass1Dist = Double.MAX_VALUE;
		CellTask bestPass2 = null;
		double bestPass2Dist = Double.MAX_VALUE;
		CellTask bestPass3 = null;
		double bestPass3Dist = Double.MAX_VALUE;

		for (CellTask task : tasks) {
			if (task.kind() == CellKind.MATCHED) continue;
			if (skippedKeys != null && skippedKeys.contains(task.key())) continue;
			if (cooldownUntil != null) {
				Long until = cooldownUntil.get(task.key());
				if (until != null && until > nowMs) continue;
			}

			double distSq = distSq(task.x(), task.y(), task.z(), playerX, playerY, playerZ);
			if (anchorX != null && anchorZ != null) {
				double adx = task.x() - anchorX;
				double adz = task.z() - anchorZ;
				distSq += 0.35 * (adx * adx + adz * adz);
			}
			switch (task.kind()) {
				case WRONG_BLOCK -> {
					if (wrongPolicy != WrongBlockPolicy.BREAK_AND_PLACE) continue;
					if (distSq < bestPass1Dist) {
						bestPass1Dist = distSq;
						bestPass1 = task;
					}
				}
				case WRONG_INTERACTABLE -> {
					if (distSq < bestPass1Dist) {
						bestPass1Dist = distSq;
						bestPass1 = task;
					}
				}
				case MISSING -> {
					if (!hasMaterialForTask(task, inventoryCounts)) continue;
					if (task.hasSupport() && !task.attachment()) {
						if (distSq < bestPass1Dist) {
							bestPass1Dist = distSq;
							bestPass1 = task;
						}
					} else if (distSq < bestPass2Dist) {
						bestPass2Dist = distSq;
						bestPass2 = task;
					}
				}
				case EXTRA_BLOCK -> {
					if (wrongPolicy != WrongBlockPolicy.BREAK_AND_PLACE || !clearExtraBlocks) continue;
					if (distSq < bestPass3Dist) {
						bestPass3Dist = distSq;
						bestPass3 = task;
					}
				}
				case MATCHED -> {}
			}
		}
		if (bestPass1 != null) return bestPass1;
		if (bestPass2 != null) return bestPass2;
		return bestPass3;
	}

	/**
	 * Returns true when every non-skipped required cell in the current layer is satisfied.
	 * Skipped wrong blocks (under SKIP policy or after max failures) do not block advancing to Y + 1.
	 */
	public static boolean isLayerComplete(
			List<CellTask> tasks,
			WrongBlockPolicy wrongPolicy,
			boolean clearExtraBlocks,
			Set<Long> skippedKeys
	) {
		for (CellTask task : tasks) {
			if (task.kind() == CellKind.MATCHED) continue;
			if (skippedKeys != null && skippedKeys.contains(task.key())) continue;
			if (task.kind() == CellKind.WRONG_BLOCK) {
				if (wrongPolicy == WrongBlockPolicy.SKIP) continue;
				// Breaking a mismatched block is always actionable even before resupplying the replacement item.
				return false;
			}
			if (task.kind() == CellKind.EXTRA_BLOCK && (wrongPolicy != WrongBlockPolicy.BREAK_AND_PLACE || !clearExtraBlocks)) {
				continue;
			}
			return false;
		}
		return true;
	}

	/** Returns true when unfinished tasks remain only because required items are absent from inventory. */
	public static boolean hasOnlyMaterialBlockedTasks(
			List<CellTask> tasks,
			WrongBlockPolicy wrongPolicy,
			boolean clearExtraBlocks,
			Set<Long> skippedKeys,
			Map<String, Integer> inventoryCounts
	) {
		boolean sawBlocked = false;
		for (CellTask task : tasks) {
			if (task.kind() == CellKind.MATCHED) continue;
			if (skippedKeys != null && skippedKeys.contains(task.key())) continue;
			if (task.kind() == CellKind.WRONG_BLOCK) {
				if (wrongPolicy == WrongBlockPolicy.SKIP) continue;
				// Breaking a mismatched block is always actionable even before resupplying the replacement item.
				return false;
			}
			if (task.kind() == CellKind.EXTRA_BLOCK && (wrongPolicy != WrongBlockPolicy.BREAK_AND_PLACE || !clearExtraBlocks)) {
				continue;
			}
			if (hasMaterialForTask(task, inventoryCounts)) {
				return false;
			}
			sawBlocked = true;
		}
		return sawBlocked;
	}

	/**
	 * Advances from {@code startY} up to {@code maxY} and returns the first Y layer that contains
	 * at least one schematic block, or {@code Integer.MIN_VALUE} when no layers remain.
	 */
	public static int nextNonEmptyLayerY(int startY, int maxY, IntPredicate layerHasSchematicBlocks) {
		for (int y = startY; y <= maxY; y++) {
			if (layerHasSchematicBlocks.test(y)) {
				return y;
			}
		}
		return Integer.MIN_VALUE;
	}

	/** Formats top-N item counts cleanly for chat / status summaries. */
	public static String formatItemSummary(Map<String, Integer> items, int maxEntries) {
		if (items == null || items.isEmpty()) {
			return "无";
		}
		List<Map.Entry<String, Integer>> sorted = new ArrayList<>(items.entrySet());
		sorted.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
				.thenComparing(Map.Entry::getKey));
		StringBuilder sb = new StringBuilder();
		int limit = Math.min(Math.max(1, maxEntries), sorted.size());
		for (int i = 0; i < limit; i++) {
			if (i > 0) sb.append("、");
			Map.Entry<String, Integer> e = sorted.get(i);
			sb.append(shortItemName(e.getKey())).append(" x").append(e.getValue());
		}
		if (sorted.size() > limit) {
			sb.append(" 等 ").append(sorted.size()).append(" 种");
		}
		return sb.toString();
	}

	private static String shortItemName(String id) {
		if (id == null) return "unknown";
		String lower = id.toLowerCase(Locale.ROOT);
		return lower.startsWith("minecraft:") ? lower.substring("minecraft:".length()) : lower;
	}

	private static double distSq(int ax, int ay, int az, int bx, int by, int bz) {
		double dx = ax - bx;
		double dy = ay - by;
		double dz = az - bz;
		return dx * dx + dy * dy + dz * dz;
	}
}
