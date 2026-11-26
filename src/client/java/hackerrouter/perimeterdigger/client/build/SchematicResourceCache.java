package hackerrouter.perimeterdigger.client.build;

import hackerrouter.perimeterdigger.client.config.PositionConfig;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of scanned resource containers (chests, barrels, placed shulker boxes),
 * deduplicated across double-chest halves and recursively inspecting {@link DataComponents#CONTAINER}
 * inside shulker box items stored in chests or carried in the player's inventory.
 */
public final class SchematicResourceCache {
	public record ChestSnapshot(
			Map<String, Integer> itemCounts,
			Map<String, String> displayNames,
			int healthyToolCount,
			int shulkerBoxCount,
			long scannedAtMs
	) {}

	public record AggregatedPool(
			Map<String, Integer> itemCounts,
			int configuredCount,
			int uniqueChestCount,
			int scannedChestCount,
			int totalShulkerBoxes
	) {
		public boolean hasAnyScanned() {
			return scannedChestCount > 0;
		}
	}

	private static final Map<String, ChestSnapshot> CACHE = new ConcurrentHashMap<>();

	private SchematicResourceCache() {}

	public static boolean isResourceContainer(Block block) {
		return block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock;
	}

	public static boolean isShulkerBoxItem(ItemStack stack) {
		return stack != null && !stack.isEmpty()
				&& stack.getItem() instanceof BlockItem bi
				&& bi.getBlock() instanceof ShulkerBoxBlock;
	}

	/** Checks whether a shulker box item carries at least one requested item ID or healthy tool. */
	public static boolean shulkerContainsAny(
			ItemStack shulker,
			Set<String> neededItemIds,
			boolean needHealthyTool,
			int durabilityThreshold
	) {
		if (!isShulkerBoxItem(shulker)) return false;
		ItemContainerContents contents = shulker.get(DataComponents.CONTAINER);
		if (contents == null) return false;
		return contents.nonEmptyItemCopyStream().anyMatch(inner -> {
			if (inner.isEmpty()) return false;
			if (needHealthyTool && isHealthyTool(inner, durabilityThreshold)) return true;
			if (neededItemIds != null && !neededItemIds.isEmpty()) {
				String id = BuiltInRegistries.ITEM.getKey(inner.getItem()).toString();
				return neededItemIds.contains(id);
			}
			return false;
		});
	}

	/** Aggregates all items stored inside shulker box items carried in the player's own inventory. */
	public static Map<String, Integer> countCarriedShulkerContents(LocalPlayer player) {
		if (player == null) return Map.of();
		Map<String, Integer> counts = new LinkedHashMap<>();
		Inventory inv = player.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack stack = inv.getItem(i);
			if (!isShulkerBoxItem(stack)) continue;
			ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
			if (contents == null) continue;
			contents.nonEmptyItemCopyStream().forEach(inner -> {
				if (inner.isEmpty()) return;
				String id = BuiltInRegistries.ITEM.getKey(inner.getItem()).toString();
				counts.merge(id, inner.getCount(), Integer::sum);
			});
		}
		return counts;
	}

	public static int countCarriedShulkerBoxes(LocalPlayer player) {
		if (player == null) return 0;
		int count = 0;
		Inventory inv = player.getInventory();
		for (int i = 0; i < 36; i++) {
			if (isShulkerBoxItem(inv.getItem(i))) count++;
		}
		return count;
	}

	/**
	 * Resolves a double chest to its canonical half so both halves map to the same cache key
	 * and are never double-counted.
	 */
	public static BlockPos canonicalChestPos(Level level, BlockPos pos) {
		if (level == null || pos == null || !level.isLoaded(pos)) {
			return pos;
		}
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock
				&& state.hasProperty(ChestBlock.TYPE)
				&& state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			Direction conn = ChestBlock.getConnectedDirection(state);
			BlockPos other = pos.relative(conn);
			if (comparePos(other, pos) < 0) {
				return other;
			}
		}
		return pos;
	}

	public static String cacheKey(Level level, BlockPos pos) {
		String dim = level == null ? "unknown" : level.dimension().identifier().toString();
		BlockPos canon = canonicalChestPos(level, pos);
		return dim + "@" + canon.getX() + "," + canon.getY() + "," + canon.getZ();
	}

	/**
	 * Captures the container portion of an open {@link AbstractContainerMenu} into the cache,
	 * including all items packed inside any Shulker Box items in the container.
	 */
	public static ChestSnapshot recordOpenContainer(
			Level level,
			BlockPos chestPos,
			AbstractContainerMenu menu,
			int durabilityThreshold
	) {
		int containerSlots = Math.max(0, menu.slots.size() - 36);
		Map<String, Integer> counts = new LinkedHashMap<>();
		Map<String, String> names = new LinkedHashMap<>();
		int[] healthyTools = { 0 };
		int shulkerBoxes = 0;

		for (int i = 0; i < containerSlots; i++) {
			ItemStack stack = menu.getSlot(i).getItem();
			if (stack == null || stack.isEmpty()) continue;
			String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			counts.merge(id, stack.getCount(), Integer::sum);
			names.putIfAbsent(id, stack.getHoverName().getString());
			if (isHealthyTool(stack, durabilityThreshold)) {
				healthyTools[0]++;
			}
			if (isShulkerBoxItem(stack)) {
				shulkerBoxes++;
				ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
				if (contents != null) {
					contents.nonEmptyItemCopyStream().forEach(inner -> {
						if (inner.isEmpty()) return;
						String innerId = BuiltInRegistries.ITEM.getKey(inner.getItem()).toString();
						counts.merge(innerId, inner.getCount(), Integer::sum);
						names.putIfAbsent(innerId, inner.getHoverName().getString());
						if (isHealthyTool(inner, durabilityThreshold)) {
							healthyTools[0]++;
						}
					});
				}
			}
		}
		ChestSnapshot snap = new ChestSnapshot(
				Map.copyOf(counts), Map.copyOf(names), healthyTools[0], shulkerBoxes, System.currentTimeMillis());
		CACHE.put(cacheKey(level, chestPos), snap);
		return snap;
	}

	public static ChestSnapshot getSnapshot(Level level, PositionConfig pos) {
		if (pos == null) return null;
		return CACHE.get(cacheKey(level, new BlockPos(pos.x, pos.y, pos.z)));
	}

	public static void clear() {
		CACHE.clear();
	}

	/** Aggregates all scanned unique containers from {@code configured} into one combined item map. */
	public static AggregatedPool aggregateConfiguredResources(
			Level level,
			Map<String, PositionConfig> configured
	) {
		if (configured == null || configured.isEmpty()) {
			return new AggregatedPool(Map.of(), 0, 0, 0, 0);
		}
		Map<String, Integer> merged = new LinkedHashMap<>();
		Set<String> seenKeys = new HashSet<>();
		int scanned = 0;
		int shulkers = 0;
		for (PositionConfig pos : configured.values()) {
			if (pos == null) continue;
			String key = cacheKey(level, new BlockPos(pos.x, pos.y, pos.z));
			if (!seenKeys.add(key)) continue;
			ChestSnapshot snap = CACHE.get(key);
			if (snap != null) {
				scanned++;
				shulkers += snap.shulkerBoxCount();
				for (Map.Entry<String, Integer> e : snap.itemCounts().entrySet()) {
					merged.merge(e.getKey(), e.getValue(), Integer::sum);
				}
			}
		}
		return new AggregatedPool(Map.copyOf(merged), configured.size(), seenKeys.size(), scanned, shulkers);
	}

	/**
	 * Returns 2 if cached container (or any shulker box inside it) definitely contains a needed
	 * item/tool, 1 if never scanned yet, or 0 if scanned and known not to contain any requested item.
	 */
	public static int matchPriority(
			Level level,
			PositionConfig pos,
			Set<String> neededItemIds,
			boolean needHealthyTool
	) {
		ChestSnapshot snap = getSnapshot(level, pos);
		if (snap == null) return 1;
		if (needHealthyTool && snap.healthyToolCount() > 0) return 2;
		if (neededItemIds != null) {
			for (String id : neededItemIds) {
				if (snap.itemCounts().getOrDefault(id, 0) > 0) {
					return 2;
				}
			}
		}
		return 0;
	}

	public static boolean isHealthyTool(ItemStack stack, int durabilityThreshold) {
		if (stack == null || stack.isEmpty()) return false;
		if (!stack.getItem().components().has(DataComponents.TOOL)) return false;
		if (!stack.isDamageableItem() || durabilityThreshold < 0) return true;
		return (stack.getMaxDamage() - stack.getDamageValue()) > durabilityThreshold;
	}

	private static int comparePos(BlockPos a, BlockPos b) {
		int cmp = Integer.compare(a.getX(), b.getX());
		if (cmp != 0) return cmp;
		cmp = Integer.compare(a.getY(), b.getY());
		return cmp != 0 ? cmp : Integer.compare(a.getZ(), b.getZ());
	}
}
