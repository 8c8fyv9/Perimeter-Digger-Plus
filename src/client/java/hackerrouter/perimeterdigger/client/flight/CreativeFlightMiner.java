package hackerrouter.perimeterdigger.client.flight;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.process.area.AreaMiningLiquidPolicy;
import baritone.api.process.area.AreaMiningOptions;
import baritone.api.process.area.AreaMiningStatus;
import baritone.api.process.area.AreaMiningStrategy;
import baritone.api.process.area.AreaMiningStrategyPlanner;
import baritone.api.process.area.SealedPocketDetector;
import baritone.utils.BackpackToolManager;
import hackerrouter.perimeterdigger.client.state.ConfiguredColumnarArea;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Top-down flying area miner for servers/worlds where the player has /fly permission (abilities.mayfly).
 * Flies smoothly in 3D to clear blocks layer-by-layer and seal boundary liquids without getting stuck
 * on terrain, water, or vertical gaps.
 */
public final class CreativeFlightMiner {
	public enum Outcome {
		RUNNING,
		BLOCK_LIMIT_REACHED,
		COMPLETE,
		NO_SEALING_BLOCKS,
		LOST_FLIGHT
	}

	private static final double REACH = 4.25;
	private static final long TARGET_COOLDOWN_MS = 15_000L;
	private static final int MAX_TICKS_PER_TARGET = 140;
	private static final int MAX_RETRIES_PER_TARGET = 2;
	private static final int LOCAL_SCAN_RADIUS = 20;

	private final CreativeFlightNavigator navigator = new CreativeFlightNavigator();
	private final Map<Long, Long> cooldownUntil = new HashMap<>();
	private final Map<Long, Integer> failCounts = new HashMap<>();
	private final Set<Long> permanentlySkipped = new HashSet<>();
	private final Set<Block> protectedBlocks = new HashSet<>();
	private boolean targetBlocksOnly;
	private final Set<Block> targetBlocks = new HashSet<>();
	private final Set<Long> minedPositions = new HashSet<>();

	private ConfiguredColumnarArea area;
	private AreaMiningOptions options;
	private boolean active;
	private long minedBlocks;
	private long knownRemaining = -1L;

	private BlockPos targetPos;
	private boolean targetIsSeal;
	private int targetTicks;
	private int flightBlockedTicks;
	private boolean breakingStarted;
	private int sealAttempts;
	private boolean hasActiveChunk;
	private int activeChunkX;
	private int activeChunkZ;
	private boolean hasLocalAnchor;
	private int localAnchorX;
	private int localAnchorZ;

	public void start(ConfiguredColumnarArea area, AreaMiningOptions options) {
		this.area = area;
		this.options = options;
		this.active = true;
		this.minedBlocks = 0L;
		this.minedPositions.clear();
		this.knownRemaining = -1L;
		this.cooldownUntil.clear();
		this.failCounts.clear();
		this.permanentlySkipped.clear();
		this.protectedBlocks.clear();
		this.protectedBlocks.addAll(options.protectedBlocks());
		this.targetBlocksOnly = options.targetBlocksOnly();
		this.targetBlocks.clear();
		this.targetBlocks.addAll(options.targetBlocks());
		this.hasActiveChunk = false;
		this.hasLocalAnchor = false;
		clearTarget(null);
	}

	/**
	 * Resumes flying mining after a temporary interruption (stall recovery, eating, unload)
	 * while preserving permanently skipped targets, failure counts, and active chunk/local anchor
	 * whenever the area bounds and block filters are unchanged.
	 */
	public void resume(ConfiguredColumnarArea area, AreaMiningOptions options) {
		if (this.area == null || this.options == null
				|| !sameAreaBounds(this.area, area)
				|| !sameMiningFilters(this.options, options)) {
			start(area, options);
			return;
		}
		this.area = area;
		this.options = options;
		this.active = true;
		this.minedBlocks = 0L;
		this.minedPositions.clear();
		this.knownRemaining = -1L;
		this.cooldownUntil.clear();
		clearTarget(null);
	}

	private static boolean sameAreaBounds(ConfiguredColumnarArea a, ConfiguredColumnarArea b) {
		return a.minX() == b.minX() && a.maxX() == b.maxX()
				&& a.minY() == b.minY() && a.maxY() == b.maxY()
				&& a.minZ() == b.minZ() && a.maxZ() == b.maxZ()
				&& a.estimatedBlockCount() == b.estimatedBlockCount();
	}

	private static boolean sameMiningFilters(AreaMiningOptions a, AreaMiningOptions b) {
		return a.liquidPolicy() == b.liquidPolicy()
				&& a.strategy() == b.strategy()
				&& a.targetBlocksOnly() == b.targetBlocksOnly()
				&& a.protectedBlocks().equals(b.protectedBlocks())
				&& a.targetBlocks().equals(b.targetBlocks());
	}

	public boolean isActive() {
		return active;
	}

	public void stop(IBaritone baritone) {
		active = false;
		clearTarget(baritone);
		navigator.stop(baritone);
	}

	/** Called by external stall recovery to cool down and drop the currently stuck block target. */
	public void deferCurrentTarget(IBaritone baritone) {
		if (targetPos != null) {
			long now = System.currentTimeMillis();
			long key = targetPos.asLong();
			int fails = failCounts.merge(key, 1, Integer::sum);
			if (fails >= MAX_RETRIES_PER_TARGET) {
				permanentlySkipped.add(key);
				cooldownUntil.remove(key);
			} else {
				cooldownUntil.put(key, now + TARGET_COOLDOWN_MS);
			}
		}
		clearTarget(baritone);
	}

	public AreaMiningStatus status() {
		long total = area == null ? 0L : area.estimatedBlockCount();
		long limit = options == null ? Long.MAX_VALUE : options.blockLimit();
		AreaMiningStatus.State state = active ? AreaMiningStatus.State.RUNNING : AreaMiningStatus.State.IDLE;
		return new AreaMiningStatus(state, total, knownRemaining, AreaMiningStatus.PauseReason.NONE,
				minedBlocks, limit, minedPositions.size());
	}

	public Outcome tick(IBaritone baritone, int toolDurabilityThreshold) {
		if (!active || area == null || options == null) return Outcome.COMPLETE;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		Level level = mc.level;
		if (player == null || level == null || mc.gameMode == null) return Outcome.RUNNING;
		if (!CreativeFlightNavigator.ensureFlying(player)) {
			stop(baritone);
			return Outcome.LOST_FLIGHT;
		}

		long now = System.currentTimeMillis();
		if (targetPos != null) {
			if (isTargetCompleted(level, targetPos, targetIsSeal)) {
				if (!targetIsSeal) {
					minedBlocks++;
					minedPositions.add(targetPos.asLong());
				}
				clearTarget(baritone);
				if (minedBlocks >= options.blockLimit()) {
					stop(baritone);
					return Outcome.BLOCK_LIMIT_REACHED;
				}
			} else if (++targetTicks > MAX_TICKS_PER_TARGET) {
				recordTargetFailure(level, player, targetPos, now);
				clearTarget(baritone);
			}
		}

		if (targetPos == null) {
			Selection pick = selectNextTarget(level, player, now);
			knownRemaining = pick.remainingCount;
			if (pick.pos == null) {
				if (pick.onlyUnsealableLiquids) {
					stop(baritone);
					return Outcome.NO_SEALING_BLOCKS;
				}
				if (!cooldownUntil.isEmpty()) {
					cooldownUntil.clear();
					return Outcome.RUNNING;
				}
				stop(baritone);
				return Outcome.COMPLETE;
			}
			if (!pick.isSeal && isFilterActive() && markIfSealedPocket(level, player, pick.pos)) {
				return Outcome.RUNNING;
			}
			targetPos = pick.pos;
			targetIsSeal = pick.isSeal;
			targetTicks = 0;
			breakingStarted = false;
			sealAttempts = 0;
		}

		Vec3 targetCenter = Vec3.atCenterOf(targetPos);
		double dist = player.getEyePosition().distanceTo(targetCenter);
		if (dist > REACH) {
			if (breakingStarted) {
				mc.gameMode.stopDestroyBlock();
				breakingStarted = false;
			}
			Vec3 hover = findHoverPosition(level, targetPos, player.position());
			if (!navigator.isActive() || navigator.target() == null || navigator.target().distanceToSqr(hover) > 1.0) {
				navigator.flyTo(hover, 0.85, 0.75, false);
			}
			navigator.tick(baritone);
			if (navigator.isBlockedOrStalled() || player.horizontalCollision || player.verticalCollision) {
				if (++flightBlockedTicks >= 10) {
					BlockPos blocker = findBlockingAreaBlock(level, player, targetPos, BaritoneAPI.getSettings().blocksToDisallowBreaking.value);
					if (blocker != null) {
						navigator.stop(baritone);
						targetPos = blocker;
						targetIsSeal = false;
						targetTicks = 0;
						flightBlockedTicks = 0;
						breakingStarted = false;
						sealAttempts = 0;
					} else {
						flightBlockedTicks = 0;
					}
				}
			} else {
				flightBlockedTicks = 0;
			}
			return Outcome.RUNNING;
		}
		flightBlockedTicks = 0;

		// Never attempt to break a fully enclosed/buried block through non-breakable/protected blocks.
		if (!targetIsSeal && isFilterActive() && !hasExposedFace(level, targetPos)) {
			if (!markIfSealedPocket(level, player, targetPos)) {
				recordTargetFailure(level, player, targetPos, now);
			}
			clearTarget(baritone);
			return Outcome.RUNNING;
		}

		// Within reach: hover steady and execute native break or seal action.
		navigator.stop(baritone);
		aimAt(player, targetCenter);
		if (targetIsSeal) {
			executeSeal(mc, player, level, now);
		} else {
			executeBreak(baritone, mc, player, level, toolDurabilityThreshold);
		}
		return Outcome.RUNNING;
	}

	private void executeBreak(IBaritone baritone, Minecraft mc, LocalPlayer player, Level level, int toolDurabilityThreshold) {
		BlockState state = level.getBlockState(targetPos);
		BackpackToolManager.ensureBestToolOnHotbar(baritone.getPlayerContext(), state);
		selectBestTool(mc, player, state, toolDurabilityThreshold);
		if (isProtectedLowDurabilityTool(player.getMainHandItem(), toolDurabilityThreshold)) {
			if (breakingStarted) {
				mc.gameMode.stopDestroyBlock();
				breakingStarted = false;
			}
			return;
		}
		Direction face = closestFace(targetPos, player.getEyePosition());
		if (!breakingStarted) {
			mc.gameMode.startDestroyBlock(targetPos, face);
			breakingStarted = true;
		} else {
			mc.gameMode.continueDestroyBlock(targetPos, face);
		}
		player.swing(InteractionHand.MAIN_HAND);
	}

	private void executeSeal(Minecraft mc, LocalPlayer player, Level level, long now) {
		int slot = selectSealingBlock(mc, player);
		if (slot < 0) {
			recordTargetFailure(level, player, targetPos, now);
			clearTarget(null);
			return;
		}
		BlockHitResult hit = findSealHit(level, targetPos, player.getEyePosition());
		mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
		player.swing(InteractionHand.MAIN_HAND);
		if (++sealAttempts >= 12) {
			recordTargetFailure(level, player, targetPos, now);
			clearTarget(null);
		}
	}

	private static BlockHitResult findSealHit(Level level, BlockPos target, Vec3 eye) {
		for (Direction dir : Direction.values()) {
			BlockPos support = target.relative(dir);
			BlockState state = level.getBlockState(support);
			if (!state.isAir() && !state.canBeReplaced() && !state.getShape(level, support).isEmpty()) {
				Direction face = dir.getOpposite();
				Vec3 hitVec = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
				return new BlockHitResult(hitVec, face, support, false);
			}
		}
		Direction face = closestFace(target, eye);
		return new BlockHitResult(Vec3.atCenterOf(target), face, target, false);
	}

	private int selectSealingBlock(Minecraft mc, LocalPlayer player) {
		Inventory inv = player.getInventory();
		List<Block> allowed = options.sealingBlocks();
		for (int i = 0; i < 9; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.getItem() instanceof BlockItem bi && allowed.contains(bi.getBlock())) {
				inv.setSelectedSlot(i);
				return i;
			}
		}
		if (player.containerMenu instanceof InventoryMenu) {
			for (int i = 9; i < 36; i++) {
				ItemStack stack = inv.getItem(i);
				if (stack.getItem() instanceof BlockItem bi && allowed.contains(bi.getBlock())) {
					int hotbar = inv.getSelectedSlot();
					mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, i, hotbar, ContainerInput.SWAP, player);
					return hotbar;
				}
			}
		}
		return -1;
	}

	private static void selectBestTool(Minecraft mc, LocalPlayer player, BlockState targetState, int durabilityThreshold) {
		Inventory inv = player.getInventory();
		int bestSlot = inv.getSelectedSlot();
		float bestSpeed = scoreTool(inv.getItem(bestSlot), targetState, durabilityThreshold);
		for (int i = 0; i < 9; i++) {
			float speed = scoreTool(inv.getItem(i), targetState, durabilityThreshold);
			if (speed > bestSpeed + 0.01F) {
				bestSpeed = speed;
				bestSlot = i;
			}
		}
		if (bestSlot != inv.getSelectedSlot()) {
			inv.setSelectedSlot(bestSlot);
		}
		// Emergency fallback: if every hotbar slot is a protected low-durability tool, swap in any non-protected backpack slot.
		if (bestSpeed < 0.0F && player.containerMenu instanceof InventoryMenu) {
			int invBest = -1;
			float invSpeed = bestSpeed;
			for (int i = 9; i < 36; i++) {
				float speed = scoreTool(inv.getItem(i), targetState, durabilityThreshold);
				if (speed > invSpeed + 0.01F) {
					invSpeed = speed;
					invBest = i;
				}
			}
			if (invBest >= 0) {
				int targetHotbar = inv.getSelectedSlot();
				mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, invBest, targetHotbar, ContainerInput.SWAP, player);
			}
		}
	}

	private static boolean isProtectedLowDurabilityTool(ItemStack stack, int durabilityThreshold) {
		if (durabilityThreshold < 0 || stack.isEmpty()) return false;
		if (!stack.isDamageableItem() || !stack.getItem().components().has(DataComponents.TOOL)) return false;
		return (stack.getMaxDamage() - stack.getDamageValue()) <= durabilityThreshold;
	}

	private static float scoreTool(ItemStack stack, BlockState state, int durabilityThreshold) {
		if (stack.isEmpty()) return 1.0F;
		if (isProtectedLowDurabilityTool(stack, durabilityThreshold)) {
			return -1.0F;
		}
		return stack.getDestroySpeed(state);
	}

	private static boolean isTargetCompleted(Level level, BlockPos pos, boolean isSeal) {
		BlockState state = level.getBlockState(pos);
		if (isSeal) return state.getFluidState().isEmpty();
		return state.getBlock() instanceof AirBlock || state.getBlock() instanceof LiquidBlock;
	}

	private void clearTarget(IBaritone baritone) {
		if (breakingStarted && Minecraft.getInstance().gameMode != null) {
			Minecraft.getInstance().gameMode.stopDestroyBlock();
		}
		targetPos = null;
		targetIsSeal = false;
		targetTicks = 0;
		flightBlockedTicks = 0;
		breakingStarted = false;
		sealAttempts = 0;
		if (baritone != null) navigator.stop(baritone);
	}

	private record Selection(BlockPos pos, boolean isSeal, boolean onlyUnsealableLiquids, long remainingCount, boolean hasCooledDown) {}

	private Selection selectNextTarget(Level level, LocalPlayer player, long now) {
		boolean hasSealing = hasSealingBlockInInventory(player);
		List<Block> disallowed = BaritoneAPI.getSettings().blocksToDisallowBreaking.value;
		int px = player.getBlockX();
		int pz = player.getBlockZ();
		AreaMiningStrategy strategy = options.strategy();

		if (strategy == AreaMiningStrategy.CHUNK_TOP_DOWN) {
			if (hasActiveChunk) {
				int cMinX = Math.max(area.minX(), activeChunkX << 4);
				int cMaxX = Math.min(area.maxX(), (activeChunkX << 4) + 15);
				int cMinZ = Math.max(area.minZ(), activeChunkZ << 4);
				int cMaxZ = Math.min(area.maxZ(), (activeChunkZ << 4) + 15);
				Selection current = scanBounds(level, px, pz, cMinX, cMaxX, cMinZ, cMaxZ, hasSealing, disallowed, now);
				if (current.pos != null) return current;
				if (current.hasCooledDown) {
					clearCooldownsInBounds(cMinX - 1, cMaxX + 1, cMinZ - 1, cMaxZ + 1);
					current = scanBounds(level, px, pz, cMinX, cMaxX, cMinZ, cMaxZ, hasSealing, disallowed, now);
					if (current.pos != null) return current;
				}
				hasActiveChunk = false;
			}
			boolean sawUnsealable = false;
			for (AreaMiningStrategyPlanner.ChunkCoord chunk : AreaMiningStrategyPlanner.chunksOrderedByDistance(
					area.minX(), area.maxX(), area.minZ(), area.maxZ(), px, pz)) {
				Selection chunkPick = scanBounds(level, px, pz,
						Math.max(area.minX(), chunk.minBlockX()), Math.min(area.maxX(), chunk.maxBlockX()),
						Math.max(area.minZ(), chunk.minBlockZ()), Math.min(area.maxZ(), chunk.maxBlockZ()),
						hasSealing, disallowed, now);
				if (chunkPick.pos != null) {
					activeChunkX = chunk.chunkX();
					activeChunkZ = chunk.chunkZ();
					hasActiveChunk = true;
					return chunkPick;
				}
				sawUnsealable |= chunkPick.onlyUnsealableLiquids;
			}
			return new Selection(null, false, sawUnsealable, 0L, false);
		}

		if (strategy == AreaMiningStrategy.LOCAL_TOP_DOWN) {
			int r = AreaMiningStrategyPlanner.LOCAL_FOCUS_RADIUS;
			if (hasLocalAnchor) {
				int lMinX = Math.max(area.minX(), localAnchorX - r);
				int lMaxX = Math.min(area.maxX(), localAnchorX + r);
				int lMinZ = Math.max(area.minZ(), localAnchorZ - r);
				int lMaxZ = Math.min(area.maxZ(), localAnchorZ + r);
				Selection focused = scanBounds(level, px, pz, lMinX, lMaxX, lMinZ, lMaxZ, hasSealing, disallowed, now);
				if (focused.pos != null) return focused;
				if (focused.hasCooledDown) {
					clearCooldownsInBounds(lMinX - 1, lMaxX + 1, lMinZ - 1, lMaxZ + 1);
					focused = scanBounds(level, px, pz, lMinX, lMaxX, lMinZ, lMaxZ, hasSealing, disallowed, now);
					if (focused.pos != null) return focused;
				}
				hasLocalAnchor = false;
			}
			BlockPos nearestAnchor = findNearestAnchor(level, px, pz, hasSealing, disallowed, now);
			if (nearestAnchor != null) {
				localAnchorX = nearestAnchor.getX();
				localAnchorZ = nearestAnchor.getZ();
				hasLocalAnchor = true;
				Selection focused = scanBounds(level, px, pz,
						Math.max(area.minX(), localAnchorX - r), Math.min(area.maxX(), localAnchorX + r),
						Math.max(area.minZ(), localAnchorZ - r), Math.min(area.maxZ(), localAnchorZ + r),
						hasSealing, disallowed, now);
				if (focused.pos != null) return focused;
			}
			return scanBounds(level, px, pz, area.minX(), area.maxX(), area.minZ(), area.maxZ(), hasSealing, disallowed, now);
		}

		// GLOBAL_TOP_DOWN: strictly highest non-empty Y layer across the entire digging area, nearest XZ first.
		return scanBounds(level, px, pz, area.minX(), area.maxX(), area.minZ(), area.maxZ(), hasSealing, disallowed, now);
	}

	private void clearCooldownsInBounds(int minX, int maxX, int minZ, int maxZ) {
		cooldownUntil.keySet().removeIf(key -> {
			int x = BlockPos.getX(key);
			int z = BlockPos.getZ(key);
			return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
		});
	}

	private BlockPos findNearestAnchor(Level level, int px, int pz, boolean hasSealing, List<Block> disallowed, long now) {
		BlockPos best = null;
		double bestDistSq = Double.MAX_VALUE;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int y = area.maxY(); y >= area.minY(); y--) {
			for (int z = area.minZ(); z <= area.maxZ(); z++) {
				for (int x = area.minX(); x <= area.maxX(); x++) {
					double dSq = distSqXZ(x, z, px, pz);
					if (dSq >= bestDistSq) continue;
					if (!area.contains(x, y, z)) continue;
					cursor.set(x, y, z);
					long key = cursor.asLong();
					if (permanentlySkipped.contains(key)) continue;
					Long until = cooldownUntil.get(key);
					if (until != null && until > now) continue;
					if (!level.isLoaded(cursor)) continue;
					BlockState state = level.getBlockState(cursor);
					Block block = state.getBlock();
					if (block instanceof AirBlock) continue;
					if (disallowed.contains(block) || protectedBlocks.contains(block)) continue;
					if (targetBlocksOnly && !targetBlocks.contains(block)) continue;
					if (!state.getFluidState().isEmpty() && block instanceof LiquidBlock) {
						if (targetBlocksOnly) continue;
						if (options.liquidPolicy() == AreaMiningLiquidPolicy.AVOID || !hasSealing || !state.getFluidState().isSource()) continue;
					} else {
						if (state.getDestroySpeed(level, cursor) < 0.0F) continue;
						if (options.liquidPolicy() == AreaMiningLiquidPolicy.AVOID && touchesLiquid(level, cursor)) continue;
					}
					bestDistSq = dSq;
					best = cursor.immutable();
				}
			}
		}
		return best;
	}

	private Selection scanBounds(
			Level level,
			int px,
			int pz,
			int minX,
			int maxX,
			int minZ,
			int maxZ,
			boolean hasSealing,
			List<Block> disallowed,
			long now
	) {
		BlockPos bestSealPos = null;
		double bestSealDistSq = Double.MAX_VALUE;
		BlockPos bestBreakPos = null;
		double bestBreakDistSq = Double.MAX_VALUE;
		BlockPos fallbackPos = null;
		double fallbackDistSq = Double.MAX_VALUE;
		boolean sawUnsealableLiquid = false;
		boolean hasCooledDown = false;
		long remaining = 0L;
		boolean filterActive = isFilterActive();
		boolean sealBoundary = options.liquidPolicy() == AreaMiningLiquidPolicy.SEAL_BOUNDARY && !targetBlocksOnly;
		int margin = sealBoundary ? 1 : 0;
		int scanMaxY = sealBoundary ? area.maxY() + 1 : area.maxY();
		int scanMinX = minX - margin;
		int scanMaxX = maxX + margin;
		int scanMinZ = minZ - margin;
		int scanMaxZ = maxZ + margin;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		for (int y = scanMaxY; y >= area.minY(); y--) {
			for (int z = scanMinZ; z <= scanMaxZ; z++) {
				for (int x = scanMinX; x <= scanMaxX; x++) {
					boolean inside = area.contains(x, y, z);
					if (!inside) {
						if (!sealBoundary || !touchesSideOrTopBoundary(x, y, z)) continue;
					}
					cursor.set(x, y, z);
					long key = cursor.asLong();
					if (permanentlySkipped.contains(key)) continue;
					if (!level.isLoaded(cursor)) continue;
					BlockState state = level.getBlockState(cursor);
					Block block = state.getBlock();
					if (block instanceof AirBlock) continue;

					if (!inside) {
						if (state.getFluidState().isEmpty() || !(block instanceof LiquidBlock) || !state.getFluidState().isSource()) {
							continue;
						}
						Long until = cooldownUntil.get(key);
						if (until != null) {
							if (until > now) {
								hasCooledDown = true;
								continue;
							}
							cooldownUntil.remove(key);
						}
						if (!hasSealing) {
							sawUnsealableLiquid = true;
							continue;
						}
						remaining++;
						double dSq = distSqXZ(x, z, px, pz);
						if (dSq < bestSealDistSq) {
							bestSealDistSq = dSq;
							bestSealPos = cursor.immutable();
						}
						continue;
					}

					if (disallowed.contains(block) || protectedBlocks.contains(block)) continue;
					if (targetBlocksOnly && !targetBlocks.contains(block)) continue;

					Long until = cooldownUntil.get(key);
					if (until != null) {
						if (until > now) {
							hasCooledDown = true;
							continue;
						}
						cooldownUntil.remove(key);
					}

					if (!state.getFluidState().isEmpty() && block instanceof LiquidBlock) {
						if (targetBlocksOnly) continue;
						if (options.liquidPolicy() == AreaMiningLiquidPolicy.AVOID) continue;
						if (!state.getFluidState().isSource()) continue;
						if (!hasSealing) {
							sawUnsealableLiquid = true;
							continue;
						}
						remaining++;
						double dSq = distSqXZ(x, z, px, pz);
						if (dSq < bestSealDistSq) {
							bestSealDistSq = dSq;
							bestSealPos = cursor.immutable();
						}
						continue;
					}

					if (state.getDestroySpeed(level, cursor) < 0.0F) continue;

					if (options.liquidPolicy() == AreaMiningLiquidPolicy.AVOID && touchesLiquid(level, cursor)) {
						continue;
					}
					remaining++;
					double dSq = distSqXZ(x, z, px, pz);
					if (filterActive && !hasExposedFace(level, cursor)) {
						if (dSq < fallbackDistSq) {
							fallbackDistSq = dSq;
							fallbackPos = cursor.immutable();
						}
						continue;
					}
					if (dSq < bestBreakDistSq) {
						bestBreakDistSq = dSq;
						bestBreakPos = cursor.immutable();
					}
				}
			}
			// Top-down layer sweep: seal all liquid sources on this layer before breaking solid blocks
			// so exterior water cannot flood in and interior pools cannot regenerate infinite sources.
			if (bestSealPos != null) {
				return new Selection(bestSealPos, true, false, remaining, hasCooledDown);
			}
			if (bestBreakPos != null) {
				return new Selection(bestBreakPos, false, false, remaining, hasCooledDown);
			}
		}
		if (fallbackPos != null) {
			return new Selection(fallbackPos, false, false, remaining, hasCooledDown);
		}
		return new Selection(null, false, sawUnsealableLiquid, 0L, hasCooledDown);
	}

	private boolean touchesSideOrTopBoundary(int x, int y, int z) {
		if (y == area.maxY() + 1 && area.containsXZ(x, z)) {
			return true;
		}
		if (y < area.minY() || y > area.maxY()) {
			return false;
		}
		return area.containsXZ(x + 1, z)
				|| area.containsXZ(x - 1, z)
				|| area.containsXZ(x, z + 1)
				|| area.containsXZ(x, z - 1);
	}

	private boolean hasSealingBlockInInventory(LocalPlayer player) {
		List<Block> allowed = options.sealingBlocks();
		if (allowed.isEmpty()) return false;
		Inventory inv = player.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.getItem() instanceof BlockItem bi && allowed.contains(bi.getBlock())) return true;
		}
		return false;
	}

	private boolean isBoundaryColumn(int x, int y, int z) {
		return !area.contains(x - 1, y, z)
				|| !area.contains(x + 1, y, z)
				|| !area.contains(x, y, z - 1)
				|| !area.contains(x, y, z + 1);
	}

	private static boolean touchesLiquid(Level level, BlockPos pos) {
		for (Direction d : new Direction[]{Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
			if (!level.getFluidState(pos.relative(d)).isEmpty()) return true;
		}
		return false;
	}

	private boolean isFilterActive() {
		return !protectedBlocks.isEmpty() || targetBlocksOnly;
	}

	private void recordTargetFailure(Level level, LocalPlayer player, BlockPos pos, long now) {
		long key = pos.asLong();
		int fails = failCounts.merge(key, 1, Integer::sum);
		if (fails >= MAX_RETRIES_PER_TARGET) {
			permanentlySkipped.add(key);
			cooldownUntil.remove(key);
			if (isFilterActive()) {
				markIfSealedPocket(level, player, pos);
			}
		} else {
			cooldownUntil.put(key, now + TARGET_COOLDOWN_MS);
		}
	}

	private boolean markIfSealedPocket(Level level, LocalPlayer player, BlockPos pos) {
		if (permanentlySkipped.contains(pos.asLong())) return true;
		List<Block> disallowed = BaritoneAPI.getSettings().blocksToDisallowBreaking.value;
		BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
		SealedPocketDetector.Result result = SealedPocketDetector.check(
				pos.getX(), pos.getY(), pos.getZ(),
				player.getBlockX(), player.getBlockY(), player.getBlockZ(),
				area.maxY(),
				(x, y, z) -> classifyCell(level, probe.set(x, y, z), disallowed),
				2048
		);
		if (!result.sealed()) return false;
		for (int i = 0; i < result.size(); i++) {
			long key = BlockPos.asLong(result.x(i), result.y(i), result.z(i));
			permanentlySkipped.add(key);
			cooldownUntil.remove(key);
		}
		return true;
	}

	private SealedPocketDetector.CellType classifyCell(Level level, BlockPos pos, List<Block> disallowed) {
		if (!level.isLoaded(pos)) return SealedPocketDetector.CellType.OPEN;
		BlockState state = level.getBlockState(pos);
		Block block = state.getBlock();
		if (block instanceof AirBlock || (state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty())) {
			return SealedPocketDetector.CellType.OPEN;
		}
		if (protectedBlocks.contains(block) || disallowed.contains(block)) {
			return SealedPocketDetector.CellType.BARRIER;
		}
		if (targetBlocksOnly && !targetBlocks.contains(block)) {
			return SealedPocketDetector.CellType.BARRIER;
		}
		if (!area.contains(pos.getX(), pos.getY(), pos.getZ())) {
			return SealedPocketDetector.CellType.BARRIER;
		}
		if (block instanceof LiquidBlock) {
			return options.liquidPolicy() == AreaMiningLiquidPolicy.AVOID
					? SealedPocketDetector.CellType.BARRIER
					: SealedPocketDetector.CellType.OPEN;
		}
		if (state.getDestroySpeed(level, pos) < 0.0F) {
			return SealedPocketDetector.CellType.BARRIER;
		}
		return SealedPocketDetector.CellType.BREAKABLE;
	}

	private BlockPos findBlockingAreaBlock(Level level, LocalPlayer player, BlockPos dest, List<Block> disallowed) {
		Vec3 eye = player.getEyePosition();
		Vec3 pos = player.position();
		BlockPos feet = player.blockPosition();
		// Check directly overhead (canopy/overhang) and forward toward destination for a breakable area block.
		List<BlockPos> probes = new java.util.ArrayList<>();
		probes.add(feet.above(2));
		probes.add(feet.above(1));
		Vec3 toDest = Vec3.atCenterOf(dest).subtract(eye);
		double len = toDest.length();
		if (len > 1.0E-4) {
			Vec3 dir = toDest.scale(1.0 / len);
			for (double step = 0.8; step <= 2.6; step += 0.6) {
				probes.add(BlockPos.containing(eye.x + dir.x * step, eye.y + dir.y * step, eye.z + dir.z * step));
				probes.add(BlockPos.containing(pos.x + dir.x * step, pos.y + 0.3 + dir.y * step, pos.z + dir.z * step));
			}
		}
		for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
			probes.add(feet.relative(d).above(1));
			probes.add(feet.relative(d));
		}
		for (BlockPos candidate : probes) {
			if (candidate.equals(dest)) continue;
			if (!area.contains(candidate.getX(), candidate.getY(), candidate.getZ())) continue;
			if (permanentlySkipped.contains(candidate.asLong())) continue;
			if (eye.distanceTo(Vec3.atCenterOf(candidate)) > REACH) continue;
			if (!level.isLoaded(candidate)) continue;
			BlockState state = level.getBlockState(candidate);
			Block block = state.getBlock();
			if (block instanceof AirBlock || block instanceof LiquidBlock) continue;
			if (state.getCollisionShape(level, candidate).isEmpty()) continue;
			if (disallowed.contains(block) || protectedBlocks.contains(block)) continue;
			if (targetBlocksOnly && !targetBlocks.contains(block)) continue;
			if (state.getDestroySpeed(level, candidate) < 0.0F) continue;
			if (options.liquidPolicy() == AreaMiningLiquidPolicy.AVOID && touchesLiquid(level, candidate)) continue;
			return candidate.immutable();
		}
		return null;
	}

	private static boolean hasExposedFace(Level level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			BlockPos n = pos.relative(d);
			if (!level.isLoaded(n)) return true;
			BlockState s = level.getBlockState(n);
			if (s.getBlock() instanceof AirBlock || s.getCollisionShape(level, n).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private static Vec3 findHoverPosition(Level level, BlockPos target, Vec3 playerPos) {
		// Prefer dry open 2-high air 1-2 blocks above target, then sides and underneath (for protected overhangs).
		// Never hover inside lava; only allow water as a fallback if no dry position is available.
		BlockPos[] candidates = {
				target.above(2),
				target.above(1),
				target.north(2).above(1),
				target.south(2).above(1),
				target.east(2).above(1),
				target.west(2).above(1),
				target.north(1),
				target.south(1),
				target.east(1),
				target.west(1),
				target.north(1).below(1),
				target.south(1).below(1),
				target.east(1).below(1),
				target.west(1).below(1),
				target.below(2)
		};
		BlockPos bestDry = null;
		double bestDryScore = Double.MAX_VALUE;
		BlockPos bestWet = null;
		double bestWetScore = Double.MAX_VALUE;
		for (BlockPos c : candidates) {
			if (!level.isLoaded(c) || !level.isLoaded(c.above())) continue;
			BlockState feet = level.getBlockState(c);
			BlockState head = level.getBlockState(c.above());
			if (!feet.getCollisionShape(level, c).isEmpty()) continue;
			if (!head.getCollisionShape(level, c.above()).isEmpty()) continue;
			if (isLava(feet) || isLava(head)) continue;
			double d = playerPos.distanceToSqr(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
			boolean dry = feet.getFluidState().isEmpty() && head.getFluidState().isEmpty();
			if (dry) {
				if (d < bestDryScore) {
					bestDryScore = d;
					bestDry = c;
				}
			} else if (d < bestWetScore) {
				bestWetScore = d;
				bestWet = c;
			}
		}
		BlockPos chosen = bestDry != null ? bestDry : (bestWet != null ? bestWet : target.above(2));
		return new Vec3(chosen.getX() + 0.5, chosen.getY() + 0.1, chosen.getZ() + 0.5);
	}

	private static boolean isLava(BlockState state) {
		return state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
	}

	private static Direction closestFace(BlockPos pos, Vec3 eye) {
		double dx = eye.x - (pos.getX() + 0.5);
		double dy = eye.y - (pos.getY() + 0.5);
		double dz = eye.z - (pos.getZ() + 0.5);
		double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
		if (ay >= ax && ay >= az) return dy >= 0 ? Direction.UP : Direction.DOWN;
		if (ax >= az) return dx >= 0 ? Direction.EAST : Direction.WEST;
		return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
	}

	private static void aimAt(LocalPlayer player, Vec3 target) {
		Vec3 eye = player.getEyePosition();
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		double horiz = Math.hypot(dx, dz);
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
		player.setYRot(yaw);
		player.setXRot(pitch);
	}

	private static double distSqXZ(int x, int z, int px, int pz) {
		double dx = x - px;
		double dz = z - pz;
		return dx * dx + dz * dz;
	}
}
