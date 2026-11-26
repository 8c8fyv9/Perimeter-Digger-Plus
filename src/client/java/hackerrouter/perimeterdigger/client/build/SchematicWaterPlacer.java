package hackerrouter.perimeterdigger.client.build;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import hackerrouter.perimeterdigger.client.detect.LitematicaFootprintReader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Executes layer-by-layer water placement after Phase 1 solid blocks are built:
 * Phase 2: Waterlogs {@code waterlogged=true} blocks (which cannot auto-fill from flow).
 * Phase 3: Places minimal seed water sources computed by {@link WaterSourceOptimizer}
 *          (or all sources when optimization is disabled), with automatic on-site
 *          infinite-water bucket refilling in Survival.
 * Phase 4: Waits for 5-tick/wave source conversion, verifies completion, and falls back
 *          to direct bucketing if {@code waterSourceConversion} is disabled on the server.
 */
final class SchematicWaterPlacer {
	enum StepResult {
		RUNNING,
		DONE,
		MISSING_BUCKET
	}

	private enum Phase {
		IDLE,
		WATERLOGGING,
		WATER_SEEDS,
		PROPAGATING,
		DONE
	}

	record WaterSummary(
			int waterloggedCount,
			int waterSourceCount,
			int plannedSeedCount,
			int savedCount
	) {
		boolean hasWork() {
			return waterloggedCount > 0 || waterSourceCount > 0;
		}
	}

	@FunctionalInterface
	interface MoveCallback {
		void moveTo(Vec3 exactHover, Goal groundGoal);
	}

	private static final double BUCKET_REACH = 4.2;
	private static final int MOVE_TIMEOUT_TICKS = 140;
	private static final int CONFIRM_WAIT_TICKS = 8;
	private static final int REFILL_SOURCE_WAIT_TICKS = 10;
	private static final int MAX_CELL_ATTEMPTS = 2;
	private static final int REFILL_SEARCH_RADIUS = 24;
	private static final int REFILL_TARGET_WATER_BUCKETS = 4;
	private static final long CELL_COOLDOWN_MS = 6_000L;

	private Phase phase = Phase.IDLE;
	private boolean optimizeWater = true;
	private int layerY;
	private final Deque<BlockPos> waterlogQueue = new ArrayDeque<>();
	private final Deque<BlockPos> seedQueue = new ArrayDeque<>();
	private final Map<Long, Integer> cellAttempts = new HashMap<>();
	private final Map<Long, Long> cellCooldownUntil = new HashMap<>();
	private final Set<Long> failedRefillSources = new HashSet<>();

	private BlockPos activeTarget;
	private BlockPos firstSeedPos;
	private BlockPos refillTarget;
	private BlockPos pourPreparedTarget;
	private int pourPrepareTicks;
	private int refillWaterBucketsBeforeScoop;
	private int refillRegenWaitTicks;
	private boolean waitingConfirm;
	private boolean moveGoalDispatched;
	private boolean usingFarApproachGoal;
	private int moveGraceTicks;
	private int moveTicks;
	private int moveHardLimitTicks = MOVE_TIMEOUT_TICKS;
	private double bestMoveDistSq = Double.MAX_VALUE;
	private int noProgressMoveTicks;
	private int confirmTicks;
	private int refillWaitTicks;
	private int propagateTicks;
	private int propagateMaxTicks;
	private int propagateStallTicks;
	private int lastPropagateRemaining = Integer.MAX_VALUE;
	private int replanAttempts;
	private boolean fallbackAttempted;
	private int placedCount;
	private int plannedTotal;
	private String statusText = "";

	boolean isActive() {
		return phase != Phase.IDLE && phase != Phase.DONE;
	}

	String statusText() {
		return statusText;
	}

	/** Called when mob retaliation or eating interrupts walking so active targets are not falsely skipped on resume. */
	void onMovementInterrupted() {
		moveGoalDispatched = false;
		usingFarApproachGoal = false;
		moveGraceTicks = 0;
		moveTicks = 0;
		noProgressMoveTicks = 0;
		bestMoveDistSq = Double.MAX_VALUE;
		pourPreparedTarget = null;
		pourPrepareTicks = 0;
	}

	void reset() {
		phase = Phase.IDLE;
		waterlogQueue.clear();
		seedQueue.clear();
		cellAttempts.clear();
		cellCooldownUntil.clear();
		failedRefillSources.clear();
		activeTarget = null;
		firstSeedPos = null;
		refillTarget = null;
		pourPreparedTarget = null;
		pourPrepareTicks = 0;
		refillWaterBucketsBeforeScoop = 0;
		refillRegenWaitTicks = 0;
		waitingConfirm = false;
		onMovementInterrupted();
		confirmTicks = 0;
		refillWaitTicks = 0;
		propagateTicks = 0;
		propagateMaxTicks = 0;
		propagateStallTicks = 0;
		lastPropagateRemaining = Integer.MAX_VALUE;
		replanAttempts = 0;
		fallbackAttempted = false;
		placedCount = 0;
		plannedTotal = 0;
		statusText = "";
	}

	static boolean isWaterFluid(FluidState fluid) {
		return fluid != null && !fluid.isEmpty() && fluid.getType().isSame(Fluids.WATER);
	}

	static boolean isSchematicWaterSource(BlockState target) {
		FluidState fluid = target.getFluidState();
		return (target.getBlock() instanceof LiquidBlock || target.getBlock() instanceof BubbleColumnBlock)
				&& isWaterFluid(fluid)
				&& fluid.isSource();
	}

	static boolean isSchematicFlowingWater(BlockState target) {
		FluidState fluid = target.getFluidState();
		return isWaterFluid(fluid) && !fluid.isSource();
	}

	static boolean isSchematicFlowingFluid(BlockState target) {
		FluidState fluid = target.getFluidState();
		return !fluid.isEmpty() && !fluid.isSource();
	}

	static boolean isSchematicWaterlogged(BlockState target) {
		return !(target.getBlock() instanceof LiquidBlock)
				&& !(target.getBlock() instanceof BubbleColumnBlock)
				&& target.hasProperty(BlockStateProperties.WATERLOGGED)
				&& target.getValue(BlockStateProperties.WATERLOGGED);
	}

	static boolean isWorldWaterSource(BlockState state) {
		FluidState fluid = state.getFluidState();
		return isWaterFluid(fluid) && fluid.isSource();
	}

	static boolean isDownwardBubbleColumn(BlockState state) {
		return state != null
				&& state.getBlock() instanceof BubbleColumnBlock
				&& state.hasProperty(BubbleColumnBlock.DRAG_DOWN)
				&& state.getValue(BubbleColumnBlock.DRAG_DOWN);
	}

	static boolean hasBelowWaterSupport(Level level, BlockPos pos) {
		BlockPos belowPos = pos.below();
		if (!level.isLoaded(belowPos)) return false;
		BlockState below = level.getBlockState(belowPos);
		return below.isSolid() || isWorldWaterSource(below);
	}

	/** Preview water work & optimized seed savings at the start of a layer for chat tally. */
	static WaterSummary previewLayer(
			Level level,
			Level schematicWorld,
			LitematicaFootprintReader.Footprint footprint,
			int y,
			Set<Long> skipped,
			boolean optimizeWater
	) {
		int waterlogged = 0;
		Set<WaterSourceOptimizer.Cell> convertible = new LinkedHashSet<>();
		Set<WaterSourceOptimizer.Cell> forced = new LinkedHashSet<>();
		Set<WaterSourceOptimizer.Cell> preexisting = new HashSet<>();
		Set<Long> futureWaterKeys = new HashSet<>();

		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
			for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
				cursor.set(x, y, z);
				if (!level.isLoaded(cursor)) continue;
				BlockState target = schematicWorld.getBlockState(cursor);
				BlockState current = level.getBlockState(cursor);
				if (isSchematicWaterlogged(target)) {
					if (!(current.getBlock() == target.getBlock()
							&& current.hasProperty(BlockStateProperties.WATERLOGGED)
							&& current.getValue(BlockStateProperties.WATERLOGGED))) {
						waterlogged++;
					}
					// After Phase 2 finishes, this waterlogged block becomes a horizontal source.
					preexisting.add(new WaterSourceOptimizer.Cell(x, z));
				} else if (isSchematicWaterSource(target)) {
					if (isWorldWaterSource(current)) {
						preexisting.add(new WaterSourceOptimizer.Cell(x, z));
					} else if (skipped == null || !skipped.contains(cursor.asLong())) {
						futureWaterKeys.add(cursor.asLong());
						// Note: during preview before Phase 1, schematic below-layer is already built!
						if (hasBelowWaterSupport(level, cursor)) {
							convertible.add(new WaterSourceOptimizer.Cell(x, z));
						} else {
							forced.add(new WaterSourceOptimizer.Cell(x, z));
						}
					}
				} else if (isWorldWaterSource(current)) {
					preexisting.add(new WaterSourceOptimizer.Cell(x, z));
				}
			}
		}

		int totalSources = convertible.size() + forced.size();
		if (totalSources == 0) {
			return new WaterSummary(waterlogged, 0, 0, 0);
		}
		if (!optimizeWater) {
			return new WaterSummary(waterlogged, totalSources, totalSources, 0);
		}
		WaterSourceOptimizer.Plan plan = WaterSourceOptimizer.plan(new WaterSourceOptimizer.WaterLayerInput(
				convertible, forced, preexisting,
				(a, b) -> isPassableEdge(level, schematicWorld, y, a, b, futureWaterKeys, true),
				c -> hasClickableSupportNeighbor(level, schematicWorld, new BlockPos(c.x(), y, c.z()), futureWaterKeys, true)));
		return new WaterSummary(waterlogged, totalSources, plan.totalSeedPlacements(), plan.savedPlacements());
	}

	/** Starts Phases 2-4 on layer {@code y} after Phase 1 solid blocks are finished. */
	boolean beginWaterPhases(
			Level level,
			Level schematicWorld,
			LitematicaFootprintReader.Footprint footprint,
			int y,
			Set<Long> skipped,
			boolean optimizeWater
	) {
		reset();
		this.layerY = y;
		this.optimizeWater = optimizeWater;

		List<BlockPos> pendingWaterlog = scanPendingWaterlogged(level, schematicWorld, footprint, y, skipped);
		List<BlockPos> pendingSources = scanPendingWaterSources(level, schematicWorld, footprint, y, skipped);
		if (pendingWaterlog.isEmpty() && pendingSources.isEmpty()) {
			phase = Phase.DONE;
			return false;
		}
		if (Level.NETHER.equals(level.dimension())) {
			chat("[Perimeter] 当前位于下界，无法放置水，已跳过第 Y=" + y + " 层的水体（"
					+ (pendingWaterlog.size() + pendingSources.size()) + " 格）。");
			phase = Phase.DONE;
			return false;
		}

		if (!pendingWaterlog.isEmpty()) {
			waterlogQueue.addAll(orderByNearest(pendingWaterlog));
			plannedTotal = waterlogQueue.size();
			phase = Phase.WATERLOGGING;
			statusText = "第 Y=" + y + " 层含水方块 0/" + plannedTotal;
			return true;
		}

		return enterSeedPhase(level, schematicWorld, footprint, skipped);
	}

	private boolean enterSeedPhase(
			Level level,
			Level schematicWorld,
			LitematicaFootprintReader.Footprint footprint,
			Set<Long> skipped
	) {
		seedQueue.clear();
		cellCooldownUntil.clear();
		failedRefillSources.clear();
		activeTarget = null;
		firstSeedPos = null;
		refillTarget = null;
		refillRegenWaitTicks = 0;
		waitingConfirm = false;
		moveGoalDispatched = false;
		moveGraceTicks = 0;
		moveTicks = 0;
		confirmTicks = 0;
		refillWaitTicks = 0;

		Set<WaterSourceOptimizer.Cell> convertible = new LinkedHashSet<>();
		Set<WaterSourceOptimizer.Cell> forced = new LinkedHashSet<>();
		Set<WaterSourceOptimizer.Cell> preexisting = new HashSet<>();
		Set<Long> futureWaterKeys = new HashSet<>();

		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		// Scan footprint + 1-block ring around it for preexisting world water sources.
		for (int z = footprint.minZ() - 1; z <= footprint.maxZ() + 1; z++) {
			for (int x = footprint.minX() - 1; x <= footprint.maxX() + 1; x++) {
				cursor.set(x, layerY, z);
				if (!level.isLoaded(cursor)) continue;
				boolean inFootprint = x >= footprint.minX() && x <= footprint.maxX()
						&& z >= footprint.minZ() && z <= footprint.maxZ();
				BlockState current = level.getBlockState(cursor);
				if (!inFootprint) {
					if (isWorldWaterSource(current)) {
						preexisting.add(new WaterSourceOptimizer.Cell(x, z));
					}
					continue;
				}
				BlockState target = schematicWorld.getBlockState(cursor);
				if (isSchematicWaterSource(target)) {
					if (isWorldWaterSource(current)) {
						preexisting.add(new WaterSourceOptimizer.Cell(x, z));
					} else if ((skipped == null || !skipped.contains(cursor.asLong()))
							&& canReceiveWater(current)) {
						futureWaterKeys.add(cursor.asLong());
						if (hasBelowWaterSupport(level, cursor)) {
							convertible.add(new WaterSourceOptimizer.Cell(x, z));
						} else {
							forced.add(new WaterSourceOptimizer.Cell(x, z));
						}
					}
				} else if (isWorldWaterSource(current)) {
					preexisting.add(new WaterSourceOptimizer.Cell(x, z));
				}
			}
		}

		int totalMissing = convertible.size() + forced.size();
		if (totalMissing == 0) {
			phase = Phase.DONE;
			return false;
		}

		if (optimizeWater && !fallbackAttempted) {
			WaterSourceOptimizer.Plan plan = WaterSourceOptimizer.plan(new WaterSourceOptimizer.WaterLayerInput(
					convertible, forced, preexisting,
					(a, b) -> isPassableEdge(level, schematicWorld, layerY, a, b, futureWaterKeys, false),
					c -> hasClickableSupportNeighbor(level, schematicWorld, new BlockPos(c.x(), layerY, c.z()), futureWaterKeys, false)));
			for (WaterSourceOptimizer.Cell c : plan.allSeeds()) {
				seedQueue.add(new BlockPos(c.x(), layerY, c.z()));
			}
			propagateMaxTicks = Math.max(15, plan.estimatedWaves() * 5 + 12);
			placedCount = 0;
			plannedTotal = seedQueue.size();
			if (seedQueue.isEmpty()) {
				phase = Phase.PROPAGATING;
				propagateTicks = 0;
				statusText = "第 Y=" + layerY + " 层等待水流自动成源...";
				return true;
			}
			if (plan.savedPlacements() > 0) {
				chat("[Perimeter] 第 Y=" + layerY + " 层水体优化：仅需倒水 " + plan.totalSeedPlacements()
						+ " 次即可填满 " + plan.totalTargetCells() + " 格水源（省 " + plan.savedPlacements() + " 次）。");
			}
		} else {
			List<BlockPos> allMissing = new ArrayList<>(totalMissing);
			for (WaterSourceOptimizer.Cell c : forced) allMissing.add(new BlockPos(c.x(), layerY, c.z()));
			for (WaterSourceOptimizer.Cell c : convertible) allMissing.add(new BlockPos(c.x(), layerY, c.z()));
			seedQueue.addAll(orderByNearest(allMissing));
			propagateMaxTicks = 12;
			placedCount = 0;
			plannedTotal = seedQueue.size();
		}

		phase = Phase.WATER_SEEDS;
		statusText = "第 Y=" + layerY + " 层放水 0/" + plannedTotal;
		return true;
	}

	StepResult tick(
			IBaritone baritone,
			Minecraft mc,
			LocalPlayer player,
			Level level,
			Level schematicWorld,
			LitematicaFootprintReader.Footprint footprint,
			Set<Long> layerSkipped,
			boolean canFly,
			BooleanSupplier isWalking,
			MoveCallback moveCallback,
			Runnable stopMovement
	) {
		if (phase == Phase.IDLE || phase == Phase.DONE) {
			return StepResult.DONE;
		}
		if (mc.gameMode == null) {
			return StepResult.RUNNING;
		}

		// Sub-step: Refill empty bucket from a nearby safe self-regenerating water source.
		if (refillTarget != null) {
			return tickRefillBucket(baritone, mc, player, level, canFly, isWalking, moveCallback, stopMovement);
		}

		return switch (phase) {
			case WATERLOGGING -> tickWaterlogging(baritone, mc, player, level, schematicWorld, footprint, layerSkipped, canFly, isWalking, moveCallback, stopMovement);
			case WATER_SEEDS -> tickWaterSeeds(baritone, mc, player, level, schematicWorld, footprint, layerSkipped, canFly, isWalking, moveCallback, stopMovement);
			case PROPAGATING -> tickPropagating(level, schematicWorld, footprint, layerSkipped, stopMovement);
			default -> StepResult.DONE;
		};
	}

	private StepResult tickWaterlogging(
			IBaritone baritone,
			Minecraft mc,
			LocalPlayer player,
			Level level,
			Level schematicWorld,
			LitematicaFootprintReader.Footprint footprint,
			Set<Long> layerSkipped,
			boolean canFly,
			BooleanSupplier isWalking,
			MoveCallback moveCallback,
			Runnable stopMovement
	) {
		// Check active waterlog completion.
		if (activeTarget != null) {
			BlockState cur = level.getBlockState(activeTarget);
			BlockState tgt = schematicWorld.getBlockState(activeTarget);
			if (!isSchematicWaterlogged(tgt)
					|| cur.getBlock() != tgt.getBlock()
					|| (cur.hasProperty(BlockStateProperties.WATERLOGGED) && cur.getValue(BlockStateProperties.WATERLOGGED))) {
				placedCount++;
				activeTarget = null;
				waitingConfirm = false;
				moveGoalDispatched = false;
				moveGraceTicks = 0;
				moveTicks = 0;
				confirmTicks = 0;
				stopMovement.run();
			} else if (waitingConfirm) {
				if (++confirmTicks > CONFIRM_WAIT_TICKS) {
					handleTargetRetryOrSkip(activeTarget, waterlogQueue, layerSkipped, "含水放置未生效");
					stopMovement.run();
				}
				return StepResult.RUNNING;
			}
		}

		while (activeTarget == null && !waterlogQueue.isEmpty()) {
			BlockPos next = pollNearestAvailable(waterlogQueue, player.blockPosition(), System.currentTimeMillis());
			if (next == null) break;
			if (layerSkipped.contains(next.asLong()) || !level.isLoaded(next)) continue;
			BlockState cur = level.getBlockState(next);
			BlockState tgt = schematicWorld.getBlockState(next);
			if (isSchematicWaterlogged(tgt) && cur.getBlock() == tgt.getBlock()
					&& cur.hasProperty(BlockStateProperties.WATERLOGGED)
					&& !cur.getValue(BlockStateProperties.WATERLOGGED)) {
				activeTarget = next;
				moveTicks = 0;
				moveGoalDispatched = false;
				moveGraceTicks = 0;
				confirmTicks = 0;
				waitingConfirm = false;
			}
		}

		if (activeTarget == null) {
			stopMovement.run();
			if (!enterSeedPhase(level, schematicWorld, footprint, layerSkipped)) {
				return StepResult.DONE;
			}
			return StepResult.RUNNING;
		}

		StepResult bucketState = ensureWaterBucketOrStartRefill(baritone, player, level, stopMovement);
		if (bucketState != StepResult.RUNNING) {
			return bucketState;
		}
		if (refillTarget != null) {
			return StepResult.RUNNING;
		}

		statusText = "第 Y=" + layerY + " 层含水方块 " + placedCount + "/" + Math.max(placedCount, plannedTotal);
		AimTarget waterlogAim = findWaterlogAimTarget(level, player, activeTarget);
		if (waterlogAim == null || player.getEyePosition().distanceTo(waterlogAim.point()) > BUCKET_REACH
				|| isPlayerInside(player, activeTarget)) {
			pourPreparedTarget = null;
			pourPrepareTicks = 0;
			if (!stepMoveTowardWaterTarget(level, schematicWorld, player, activeTarget, canFly, false, isWalking, moveCallback, stopMovement)) {
				handleTargetRetryOrSkip(activeTarget, waterlogQueue, layerSkipped, "含水方块无法靠近");
				stopMovement.run();
			}
			return StepResult.RUNNING;
		}

		stopMovement.run();
		moveGoalDispatched = false;
		moveGraceTicks = 0;
		if (!selectHotbarItem(player, Items.WATER_BUCKET)) {
			return StepResult.RUNNING;
		}
		applyAndSyncRotation(mc, player, waterlogAim.yaw(), waterlogAim.pitch());
		if (!isPlayerSettled(player)) {
			if (!activeTarget.equals(pourPreparedTarget)) {
				pourPreparedTarget = activeTarget.immutable();
				pourPrepareTicks = 1;
				return StepResult.RUNNING;
			}
			if (pourPrepareTicks < 2) {
				pourPrepareTicks++;
				return StepResult.RUNNING;
			}
		}
		pourPreparedTarget = null;
		pourPrepareTicks = 0;
		mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
		player.swing(InteractionHand.MAIN_HAND);
		waitingConfirm = true;
		confirmTicks = 0;
		return StepResult.RUNNING;
	}

	private StepResult tickWaterSeeds(
			IBaritone baritone,
			Minecraft mc,
			LocalPlayer player,
			Level level,
			Level schematicWorld,
			LitematicaFootprintReader.Footprint footprint,
			Set<Long> layerSkipped,
			boolean canFly,
			BooleanSupplier isWalking,
			MoveCallback moveCallback,
			Runnable stopMovement
	) {
		if (activeTarget != null) {
			BlockState cur = level.getBlockState(activeTarget);
			if (isWorldWaterSource(cur)) {
				placedCount++;
				if (firstSeedPos == null) {
					firstSeedPos = activeTarget;
				}
				cellCooldownUntil.remove(activeTarget.asLong());
				activeTarget = null;
				waitingConfirm = false;
				moveGoalDispatched = false;
				moveGraceTicks = 0;
				moveTicks = 0;
				confirmTicks = 0;
				stopMovement.run();
			} else if (waitingConfirm) {
				if (++confirmTicks > CONFIRM_WAIT_TICKS) {
					handleTargetRetryOrSkip(activeTarget, seedQueue, layerSkipped, "倒水未生效");
					stopMovement.run();
				}
				return StepResult.RUNNING;
			}
		}

		if (activeTarget == null && !seedQueue.isEmpty()) {
			long now = System.currentTimeMillis();
			// Prune seeds that already converted into water sources on the fly.
			seedQueue.removeIf(p -> layerSkipped.contains(p.asLong())
					|| (level.isLoaded(p) && (isWorldWaterSource(level.getBlockState(p)) || !canReceiveWater(level.getBlockState(p)))));
			while (activeTarget == null && !seedQueue.isEmpty()) {
				BlockPos next = pollBestNearbySeed(level, schematicWorld, player, now);
				if (next == null) break;
				if (!level.isLoaded(next)) continue;
				BlockState cur = level.getBlockState(next);
				if (isWorldWaterSource(cur) || !canReceiveWater(cur)) continue;
				activeTarget = next;
				moveTicks = 0;
				moveGoalDispatched = false;
				moveGraceTicks = 0;
				confirmTicks = 0;
				waitingConfirm = false;
			}
		}

		if (activeTarget == null) {
			stopMovement.run();
			phase = Phase.PROPAGATING;
			propagateTicks = 0;
			propagateStallTicks = 0;
			lastPropagateRemaining = Integer.MAX_VALUE;
			statusText = "第 Y=" + layerY + " 层等待水流自动成源...";
			return StepResult.RUNNING;
		}

		StepResult bucketState = ensureWaterBucketOrStartRefill(baritone, player, level, stopMovement);
		if (bucketState != StepResult.RUNNING) {
			return bucketState;
		}
		if (refillTarget != null) {
			return StepResult.RUNNING;
		}

		statusText = "第 Y=" + layerY + " 层放水 " + placedCount + "/" + Math.max(placedCount, plannedTotal);
		AimTarget aim = findPourAimTarget(level, schematicWorld, player.getEyePosition(), player, activeTarget);
		if (aim == null || player.getEyePosition().distanceTo(aim.point()) > BUCKET_REACH
				|| isPlayerInside(player, activeTarget)) {
			pourPreparedTarget = null;
			pourPrepareTicks = 0;
			if (!stepMoveTowardWaterTarget(level, schematicWorld, player, activeTarget, canFly, true, isWalking, moveCallback, stopMovement)) {
				handleTargetRetryOrSkip(activeTarget, seedQueue, layerSkipped, "倒水点无法靠近");
				stopMovement.run();
			}
			return StepResult.RUNNING;
		}

		stopMovement.run();
		moveGoalDispatched = false;
		moveGraceTicks = 0;
		if (!selectHotbarItem(player, Items.WATER_BUCKET)) {
			return StepResult.RUNNING;
		}
		// ServerboundUseItemPacket carries (yRot, xRot) directly; only wait 1-2 ticks when the player
		// still has horizontal walking/swimming velocity so stationary multi-seed pours happen instantly.
		applyAndSyncRotation(mc, player, aim.yaw(), aim.pitch());
		if (!isPlayerSettled(player)) {
			if (!activeTarget.equals(pourPreparedTarget)) {
				pourPreparedTarget = activeTarget.immutable();
				pourPrepareTicks = 1;
				return StepResult.RUNNING;
			}
			if (pourPrepareTicks < 2) {
				pourPrepareTicks++;
				return StepResult.RUNNING;
			}
		}
		if (!isSchematicWaterSource(schematicWorld.getBlockState(activeTarget))
				|| !aim.supportPos().relative(aim.face()).equals(activeTarget)) {
			pourPreparedTarget = null;
			pourPrepareTicks = 0;
			return StepResult.RUNNING;
		}
		pourPreparedTarget = null;
		pourPrepareTicks = 0;
		mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
		player.swing(InteractionHand.MAIN_HAND);
		waitingConfirm = true;
		confirmTicks = 0;
		return StepResult.RUNNING;
	}

	private StepResult tickPropagating(
			Level level,
			Level schematicWorld,
			LitematicaFootprintReader.Footprint footprint,
			Set<Long> layerSkipped,
			Runnable stopMovement
	) {
		stopMovement.run();
		List<BlockPos> remaining = scanPendingWaterSources(level, schematicWorld, footprint, layerY, layerSkipped);
		if (remaining.isEmpty()) {
			phase = Phase.DONE;
			return StepResult.DONE;
		}
		propagateTicks++;
		if (remaining.size() < lastPropagateRemaining) {
			lastPropagateRemaining = remaining.size();
			propagateStallTicks = 0;
		} else {
			propagateStallTicks++;
		}
		statusText = "第 Y=" + layerY + " 层等待水流成源（剩余 " + remaining.size() + " 格）";
		// Wait while water is still actively converting (stall < 20 ticks = 1s) or within minimum wave wait.
		if (propagateTicks < 15 || (propagateStallTicks < 20 && propagateTicks <= Math.max(propagateMaxTicks, 200))) {
			return StepResult.RUNNING;
		}

		// Only suspect waterSourceConversion=false if at least one unconverted cell ACTUALLY has
		// >= 2 passable horizontal water source neighbors on solid/source floor right now and still did not convert!
		boolean conversionSeemsDisabled = hasUnconvertedEligibleCell(level, remaining);
		if (conversionSeemsDisabled && !fallbackAttempted && optimizeWater) {
			fallbackAttempted = true;
			chat("[Perimeter] 第 Y=" + layerY + " 层检测到满足无限水条件但未自动转化（剩余 " + remaining.size()
					+ " 格，可能服务端关闭了无限水规则），切换逐格补水...");
			if (enterSeedPhase(level, schematicWorld, footprint, layerSkipped)) {
				return StepResult.RUNNING;
			}
		} else if (!conversionSeemsDisabled && replanAttempts < 2) {
			// Infinite water is working, but some earlier seed was skipped/interrupted leaving dependent cells unseeded.
			replanAttempts++;
			chat("[Perimeter] 第 Y=" + layerY + " 层仍有 " + remaining.size()
					+ " 格尚未成源，保持水体优化并重新规划补水...");
			if (enterSeedPhase(level, schematicWorld, footprint, layerSkipped)) {
				return StepResult.RUNNING;
			}
		} else {
			chat("[Perimeter] 第 Y=" + layerY + " 层仍有 " + remaining.size() + " 格水未能放置，继续推进。");
		}
		phase = Phase.DONE;
		return StepResult.DONE;
	}

	private static boolean hasUnconvertedEligibleCell(Level level, List<BlockPos> remaining) {
		for (BlockPos pos : remaining) {
			if (!level.isLoaded(pos) || !hasBelowWaterSupport(level, pos)) continue;
			int sourceNeighbors = 0;
			for (Direction dir : Direction.Plane.HORIZONTAL) {
				BlockPos nbPos = pos.relative(dir);
				if (!level.isLoaded(nbPos)) continue;
				BlockState nbState = level.getBlockState(nbPos);
				if (!isWorldWaterSource(nbState)) continue;
				if (!Shapes.mergedFaceOccludes(
						nbState.getCollisionShape(level, nbPos),
						Shapes.empty(),
						dir.getOpposite())) {
					sourceNeighbors++;
					if (sourceNeighbors >= 2) return true;
				}
			}
		}
		return false;
	}

	private StepResult tickRefillBucket(
			IBaritone baritone,
			Minecraft mc,
			LocalPlayer player,
			Level level,
			boolean canFly,
			BooleanSupplier isWalking,
			MoveCallback moveCallback,
			Runnable stopMovement
	) {
		if (countEmptySlots(player.getInventory()) == 0) {
			tryConsolidateEmptyBuckets(baritone, player);
		}
		int currentWaterBuckets = countInventoryItem(player, Items.WATER_BUCKET);
		if (waitingConfirm) {
			if (currentWaterBuckets > refillWaterBucketsBeforeScoop) {
				// Scoop confirmed!
				failedRefillSources.clear();
				waitingConfirm = false;
				confirmTicks = 0;
				refillRegenWaitTicks = 0;
			} else if (++confirmTicks > CONFIRM_WAIT_TICKS) {
				waitingConfirm = false;
				confirmTicks = 0;
				if (refillTarget != null) {
					failedRefillSources.add(refillTarget.asLong());
					refillTarget = null;
					moveGoalDispatched = false;
					moveGraceTicks = 0;
					moveTicks = 0;
				}
			} else {
				return StepResult.RUNNING;
			}
		}

		// Consolidate scattered empty buckets (e.g., 4x single empty buckets left after pouring) into
		// stacks first so we free inventory slots and never drop overflow water buckets on scoop.
		if (tryConsolidateEmptyBuckets(baritone, player)) {
			stopMovement.run();
			statusText = "第 Y=" + layerY + " 层整理堆叠空水桶...";
			return StepResult.RUNNING;
		}

		// Finish refilling once we have at least 1 water bucket and either no more safely fillable
		// empty buckets or we have topped up to REFILL_TARGET_WATER_BUCKETS (4).
		if (currentWaterBuckets > 0 && !canRefillAnotherBucket(player, currentWaterBuckets)) {
			refillTarget = null;
			moveGoalDispatched = false;
			moveGraceTicks = 0;
			moveTicks = 0;
			confirmTicks = 0;
			refillRegenWaitTicks = 0;
			waitingConfirm = false;
			stopMovement.run();
			return StepResult.RUNNING;
		}

		// If the current refill cell was just scooped and is regenerating (5 ticks), first check if any
		// OTHER safe infinite source is already directly scoopable within reach without moving!
		if (refillTarget == null || !level.isLoaded(refillTarget) || !isSafeRegeneratingSource(level, refillTarget)) {
			BlockPos immediate = findDirectlyScoopableSource(level, player);
			if (immediate != null) {
				refillTarget = immediate;
				refillRegenWaitTicks = 0;
				moveGoalDispatched = false;
				moveGraceTicks = 0;
				moveTicks = 0;
			} else if (refillTarget != null && level.isLoaded(refillTarget) && ++refillRegenWaitTicks <= 8) {
				// Give the just-scooped infinite source up to 8 ticks (0.4s) to regenerate right in place.
				stopMovement.run();
				statusText = "第 Y=" + layerY + " 层等待无限水复原续桶...";
				return StepResult.RUNNING;
			} else {
				refillTarget = findNearestSafeRefillSource(level, player, player.blockPosition());
				refillRegenWaitTicks = 0;
				moveGoalDispatched = false;
				moveGraceTicks = 0;
				moveTicks = 0;
				if (refillTarget == null) {
					stopMovement.run();
					// If we already refilled at least 1 water bucket, go pour it instead of pausing!
					return currentWaterBuckets > 0 ? StepResult.RUNNING : StepResult.MISSING_BUCKET;
				}
			}
		}

		statusText = "第 Y=" + layerY + " 层就地无限水续桶（已备 " + currentWaterBuckets + " 桶）";
		Vec3 scoopAim = findScoopAimPoint(level, player, refillTarget);
		if (scoopAim == null) {
			// Check if ANY other safe infinite source is already scoopable right where we stand!
			BlockPos immediate = findDirectlyScoopableSource(level, player);
			if (immediate != null) {
				refillTarget = immediate;
				scoopAim = findScoopAimPoint(level, player, refillTarget);
			}
		}
		if (scoopAim == null) {
			if (!stepMoveTowardWaterTarget(level, null, player, refillTarget, canFly, false, isWalking, moveCallback, stopMovement)) {
				failedRefillSources.add(refillTarget.asLong());
				refillTarget = findNearestSafeRefillSource(level, player, player.blockPosition());
				moveGoalDispatched = false;
				moveGraceTicks = 0;
				moveTicks = 0;
				if (refillTarget == null) {
					stopMovement.run();
					return currentWaterBuckets > 0 ? StepResult.RUNNING : StepResult.MISSING_BUCKET;
				}
			}
			return StepResult.RUNNING;
		}

		stopMovement.run();
		moveGoalDispatched = false;
		moveGraceTicks = 0;
		if (!ensureSafeEmptyBucketOnHotbar(baritone, player)) {
			refillTarget = null;
			if (currentWaterBuckets == 0 && hasItemInInventory(player, Items.BUCKET)) {
				chat("[Perimeter] 背包已满且空水桶为堆叠状态，为防止丢出溢出水桶已停止舀水，请空出至少 1 格背包。");
			}
			return currentWaterBuckets > 0 ? StepResult.RUNNING : StepResult.MISSING_BUCKET;
		}
		// Final zero-drop invariant: vanilla BucketItem drops the filled water bucket if main hand holds
		// >1 empty buckets while inventory has 0 empty slots. Never allow that to execute.
		ItemStack heldBucket = player.getMainHandItem();
		if (!heldBucket.is(Items.BUCKET)
				|| (heldBucket.getCount() > 1 && countEmptySlots(player.getInventory()) == 0)) {
			refillTarget = null;
			return currentWaterBuckets > 0 ? StepResult.RUNNING : StepResult.MISSING_BUCKET;
		}
		refillWaterBucketsBeforeScoop = currentWaterBuckets;
		aimAt(player, scoopAim);
		applyAndSyncRotation(mc, player, player.getYRot(), player.getXRot());
		mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
		player.swing(InteractionHand.MAIN_HAND);
		waitingConfirm = true;
		confirmTicks = 0;
		return StepResult.RUNNING;
	}

	/**
	 * Moves toward {@code target} using /fly, fast {@link GoalNear} when far (>10 blocks),
	 * or a compact {@link GoalComposite} of the 12 nearest valid stand positions when close.
	 * Uses progress-aware stall timeouts so long 60-90 block walks/swims across large lakes
	 * are never falsely aborted after 7 seconds.
	 */
	private boolean stepMoveTowardWaterTarget(
			Level level,
			Level schematicWorld,
			LocalPlayer player,
			BlockPos target,
			boolean canFly,
			boolean pourVantage,
			BooleanSupplier isWalking,
			MoveCallback moveCallback,
			Runnable stopMovement
	) {
		double distSq = player.position().distanceToSqr(Vec3.atCenterOf(target));
		double dist = Math.sqrt(distSq);
		int stallLimit = dist <= 8.0 ? 45 : 80;
		if (!moveGoalDispatched) {
			bestMoveDistSq = distSq;
			noProgressMoveTicks = 0;
			moveTicks = 0;
			moveHardLimitTicks = Math.max(120, (int) (dist * 12.0) + 100);
		} else {
			if (distSq < bestMoveDistSq - 0.65 * 0.65) {
				bestMoveDistSq = distSq;
				noProgressMoveTicks = 0;
			} else if (++noProgressMoveTicks > stallLimit) {
				onMovementInterrupted();
				stopMovement.run();
				return false;
			}
		}
		if (++moveTicks > moveHardLimitTicks) {
			onMovementInterrupted();
			stopMovement.run();
			return false;
		}
		if (canFly) {
			BlockPos stand = findWaterVantage(level, target, player.position(), true);
			moveCallback.moveTo(new Vec3(stand.getX() + 0.5, stand.getY() + 0.1, stand.getZ() + 0.5), new GoalBlock(stand));
			moveGoalDispatched = true;
			return true;
		}
		if (!moveGoalDispatched) {
			if (pourVantage && level.isLoaded(target)
					&& !hasAnyVerifiedPourVantage(level, schematicWorld, player, target, BUCKET_REACH - 0.15)) {
				onMovementInterrupted();
				stopMovement.run();
				return false;
			}
			if (dist > 10.0) {
				usingFarApproachGoal = true;
				moveCallback.moveTo(Vec3.atCenterOf(target).add(0, 1.5, 0), new GoalNear(target, 3));
			} else {
				usingFarApproachGoal = false;
				BlockPos excludeFeet = player.getEyePosition().distanceTo(Vec3.atCenterOf(target)) <= BUCKET_REACH
						? player.blockPosition()
						: null;
				Goal goal = pourVantage
						? buildPourVantageGoal(level, schematicWorld, player, target, excludeFeet, BUCKET_REACH - 0.15)
						: buildGroundVantageGoal(level, target, excludeFeet, player.position(), BUCKET_REACH - 0.15);
				if (goal == null) {
					onMovementInterrupted();
					stopMovement.run();
					return false;
				}
				moveCallback.moveTo(Vec3.atCenterOf(target).add(0, 1.5, 0), goal);
			}
			moveGoalDispatched = true;
			moveGraceTicks = 0;
			return true;
		}
		if (++moveGraceTicks <= 2) {
			return true;
		}
		if (!isWalking.getAsBoolean()) {
			// If we just finished a fast far-approach GoalNear and are now close, seamlessly refine with exact GoalComposite!
			if (usingFarApproachGoal && dist <= 10.0) {
				usingFarApproachGoal = false;
				BlockPos excludeFeet = player.getEyePosition().distanceTo(Vec3.atCenterOf(target)) <= BUCKET_REACH
						? player.blockPosition()
						: null;
				Goal goal = pourVantage
						? buildPourVantageGoal(level, schematicWorld, player, target, excludeFeet, BUCKET_REACH - 0.15)
						: buildGroundVantageGoal(level, target, excludeFeet, player.position(), BUCKET_REACH - 0.15);
				if (goal == null) {
					onMovementInterrupted();
					stopMovement.run();
					return false;
				}
				moveCallback.moveTo(Vec3.atCenterOf(target).add(0, 1.5, 0), goal);
				moveGraceTicks = 0;
				return true;
			}
			onMovementInterrupted();
			stopMovement.run();
			return false;
		}
		return true;
	}

	private static boolean hasAnyVerifiedPourVantage(
			Level level,
			Level schematicWorld,
			LocalPlayer player,
			BlockPos target,
			double maxReach
	) {
		Vec3 targetCenter = Vec3.atCenterOf(target);
		int range = (int) Math.ceil(maxReach);
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dy = -2; dy <= 2; dy++) {
			for (int dz = -range; dz <= range; dz++) {
				for (int dx = -range; dx <= range; dx++) {
					cursor.set(target.getX() + dx, target.getY() + dy, target.getZ() + dz);
					if (cursor.equals(target) || cursor.above().equals(target)) continue;
					Vec3 eyeAtC = new Vec3(cursor.getX() + 0.5, cursor.getY() + 1.62, cursor.getZ() + 0.5);
					if (eyeAtC.distanceTo(targetCenter) > maxReach) continue;
					if (!level.isLoaded(cursor) || !level.isLoaded(cursor.above())) continue;
					BlockState feet = level.getBlockState(cursor);
					BlockState head = level.getBlockState(cursor.above());
					if (!feet.getCollisionShape(level, cursor).isEmpty()) continue;
					if (!head.getCollisionShape(level, cursor.above()).isEmpty()) continue;
					BlockState below = level.getBlockState(cursor.below());
					boolean belowSolid = !below.getCollisionShape(level, cursor.below()).isEmpty();
					boolean belowWater = isWaterFluid(below.getFluidState());
					boolean feetWater = isWaterFluid(feet.getFluidState());
					boolean belowDownwardBubble = isDownwardBubbleColumn(below);
					boolean feetDownwardBubble = isDownwardBubbleColumn(feet);
					if (!isValidBaritoneStandCell(belowSolid, belowWater, feetWater, belowDownwardBubble, feetDownwardBubble)) continue;
					if (findPourAimTarget(level, schematicWorld, eyeAtC, player, target) != null) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Builds a pour-verified vantage goal: prefers stand/swim positions from which
	 * {@link #findPourAimTarget(Level, Level, Vec3, LocalPlayer, BlockPos)} already succeeds with safe
	 * inner-face clearance (automatically moving the player off narrow rim walls into the tank
	 * instead of standing directly atop a wall where inner faces are occluded or risk outer spills).
	 */
	private static Goal buildPourVantageGoal(
			Level level,
			Level schematicWorld,
			LocalPlayer player,
			BlockPos target,
			BlockPos excludeFeet,
			double maxReach
	) {
		List<BlockPos> verifiedSafe = new ArrayList<>();
		List<BlockPos> verifiedBubbleFloor = new ArrayList<>();
		List<BlockPos> fallbackSafe = new ArrayList<>();
		List<BlockPos> fallbackBubbleFloor = new ArrayList<>();
		Vec3 targetCenter = Vec3.atCenterOf(target);
		Vec3 playerPos = player.position();
		int range = (int) Math.ceil(maxReach);
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dy = -2; dy <= 2; dy++) {
			for (int dz = -range; dz <= range; dz++) {
				for (int dx = -range; dx <= range; dx++) {
					cursor.set(target.getX() + dx, target.getY() + dy, target.getZ() + dz);
					if (cursor.equals(target) || cursor.above().equals(target)) continue;
					if (excludeFeet != null && cursor.equals(excludeFeet)) continue;
					Vec3 eyeAtC = new Vec3(cursor.getX() + 0.5, cursor.getY() + 1.62, cursor.getZ() + 0.5);
					if (eyeAtC.distanceTo(targetCenter) > maxReach) continue;
					if (!level.isLoaded(cursor) || !level.isLoaded(cursor.above())) continue;
					BlockState feet = level.getBlockState(cursor);
					BlockState head = level.getBlockState(cursor.above());
					if (!feet.getCollisionShape(level, cursor).isEmpty()) continue;
					if (!head.getCollisionShape(level, cursor.above()).isEmpty()) continue;
					BlockState below = level.getBlockState(cursor.below());
					boolean belowSolid = !below.getCollisionShape(level, cursor.below()).isEmpty();
					boolean belowWater = isWaterFluid(below.getFluidState());
					boolean feetWater = isWaterFluid(feet.getFluidState());
					boolean belowDownwardBubble = isDownwardBubbleColumn(below);
					boolean feetDownwardBubble = isDownwardBubbleColumn(feet);
					boolean headDownwardBubble = isDownwardBubbleColumn(head);
					if (!isValidBaritoneStandCell(belowSolid, belowWater, feetWater, belowDownwardBubble, feetDownwardBubble)) continue;
					BlockPos imm = cursor.immutable();
					boolean inDownwardBubble = feetDownwardBubble || headDownwardBubble;
					if (findPourAimTarget(level, schematicWorld, eyeAtC, player, target) != null) {
						if (inDownwardBubble) {
							verifiedBubbleFloor.add(imm);
						} else {
							verifiedSafe.add(imm);
						}
					} else {
						if (inDownwardBubble) {
							fallbackBubbleFloor.add(imm);
						} else {
							fallbackSafe.add(imm);
						}
					}
				}
			}
		}
		List<BlockPos> chosen;
		if (!verifiedSafe.isEmpty()) {
			chosen = verifiedSafe;
		} else if (!verifiedBubbleFloor.isEmpty()) {
			chosen = verifiedBubbleFloor;
		} else {
			// Never dispatch unverified fallback goals where findPourAimTarget is null from every candidate cell!
			return null;
		}
		if (chosen.isEmpty()) {
			return new GoalNear(target, 2);
		}
		int playerFeetY = player.blockPosition().getY();
		chosen.sort((a, b) -> Double.compare(
				vantageCost(playerPos, playerFeetY, a),
				vantageCost(playerPos, playerFeetY, b)));
		int limit = Math.min(12, chosen.size());
		if (limit == 1) {
			return new GoalBlock(chosen.getFirst());
		}
		Goal[] goals = new Goal[limit];
		for (int i = 0; i < limit; i++) {
			goals[i] = new GoalBlock(chosen.get(i));
		}
		return new GoalComposite(goals);
	}

	/**
	 * Matches Baritone's exact {@code MovementHelper.canWalkOn} rule when {@code assumeWalkOnWater=false}:
	 * either standing on solid ground (dry or wading) OR swimming with BOTH feet and below in water.
	 * Strictly rejects midair-over-water cells (air at feet, water below) and any cell floating above
	 * a downward bubble column ({@code DRAG_DOWN=true}), which pulls entities down and cannot be swum on.
	 */
	static boolean isValidBaritoneStandCell(boolean belowSolid, boolean belowWater, boolean feetWater) {
		return isValidBaritoneStandCell(belowSolid, belowWater, feetWater, false, false);
	}

	static boolean isValidBaritoneStandCell(
			boolean belowSolid,
			boolean belowWater,
			boolean feetWater,
			boolean belowDownwardBubble,
			boolean feetDownwardBubble
	) {
		if (belowDownwardBubble) {
			return false;
		}
		return belowSolid || (feetWater && belowWater && !feetDownwardBubble);
	}

	private static double vantageCost(Vec3 playerPos, int playerFeetY, BlockPos candidate) {
		double distSq = playerPos.distanceToSqr(candidate.getX() + 0.5, candidate.getY(), candidate.getZ() + 0.5);
		int dy = Math.abs(candidate.getY() - playerFeetY);
		return distSq + dy * 4.0;
	}

	static Goal buildGroundVantageGoal(Level level, BlockPos target, BlockPos excludeFeet, double maxReach) {
		return buildGroundVantageGoal(level, target, excludeFeet, Vec3.atCenterOf(target), maxReach);
	}

	static Goal buildGroundVantageGoal(Level level, BlockPos target, BlockPos excludeFeet, Vec3 playerPos, double maxReach) {
		List<BlockPos> safeCandidates = new ArrayList<>();
		List<BlockPos> bubbleFloorCandidates = new ArrayList<>();
		Vec3 targetCenter = Vec3.atCenterOf(target);
		int range = (int) Math.ceil(maxReach);
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dy = -2; dy <= 2; dy++) {
			for (int dz = -range; dz <= range; dz++) {
				for (int dx = -range; dx <= range; dx++) {
					cursor.set(target.getX() + dx, target.getY() + dy, target.getZ() + dz);
					if (cursor.equals(target) || cursor.above().equals(target)) continue;
					if (excludeFeet != null && cursor.equals(excludeFeet)) continue;
					Vec3 eyeAtC = new Vec3(cursor.getX() + 0.5, cursor.getY() + 1.62, cursor.getZ() + 0.5);
					if (eyeAtC.distanceTo(targetCenter) > maxReach) continue;
					if (!level.isLoaded(cursor) || !level.isLoaded(cursor.above())) continue;
					BlockState feet = level.getBlockState(cursor);
					BlockState head = level.getBlockState(cursor.above());
					if (!feet.getCollisionShape(level, cursor).isEmpty()) continue;
					if (!head.getCollisionShape(level, cursor.above()).isEmpty()) continue;
					BlockState below = level.getBlockState(cursor.below());
					boolean belowSolid = !below.getCollisionShape(level, cursor.below()).isEmpty();
					boolean belowWater = isWaterFluid(below.getFluidState());
					boolean feetWater = isWaterFluid(feet.getFluidState());
					boolean belowDownwardBubble = isDownwardBubbleColumn(below);
					boolean feetDownwardBubble = isDownwardBubbleColumn(feet);
					boolean headDownwardBubble = isDownwardBubbleColumn(head);
					if (!isValidBaritoneStandCell(belowSolid, belowWater, feetWater, belowDownwardBubble, feetDownwardBubble)) continue;
					if (feetDownwardBubble || headDownwardBubble) {
						bubbleFloorCandidates.add(cursor.immutable());
					} else {
						safeCandidates.add(cursor.immutable());
					}
				}
			}
		}
		List<BlockPos> candidates = !safeCandidates.isEmpty() ? safeCandidates : bubbleFloorCandidates;
		if (candidates.isEmpty()) {
			return new GoalNear(target, 2);
		}
		// Keep at most the 12 closest valid vantage cells so Baritone's GoalComposite heuristic stays blazing fast.
		candidates.sort((a, b) -> Double.compare(
				playerPos.distanceToSqr(a.getX() + 0.5, a.getY(), a.getZ() + 0.5),
				playerPos.distanceToSqr(b.getX() + 0.5, b.getY(), b.getZ() + 0.5)));
		int limit = Math.min(12, candidates.size());
		if (limit == 1) {
			return new GoalBlock(candidates.getFirst());
		}
		Goal[] goals = new Goal[limit];
		for (int i = 0; i < limit; i++) {
			goals[i] = new GoalBlock(candidates.get(i));
		}
		return new GoalComposite(goals);
	}

	private BlockPos pollNearestAvailable(Deque<BlockPos> queue, BlockPos origin, long now) {
		if (queue.isEmpty()) return null;
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : queue) {
			Long until = cellCooldownUntil.get(p.asLong());
			if (until != null && until > now) continue;
			double d = p.distSqr(origin);
			if (d < bestDist) {
				bestDist = d;
				best = p;
			}
		}
		if (best == null) {
			// All remaining targets are on cooldown; clear cooldowns and pick the nearest one.
			cellCooldownUntil.clear();
			for (BlockPos p : queue) {
				double d = p.distSqr(origin);
				if (d < bestDist) {
					bestDist = d;
					best = p;
				}
			}
		}
		if (best != null) {
			queue.remove(best);
		}
		return best;
	}

	/**
	 * Always prioritizes water seeds closest to the player so large pools are filled smoothly
	 * outward from where the player stands instead of walking to a far corner.
	 * Seeds already directly pourable from the player's current position (zero walking) win first.
	 * Only when establishing the very first on-site 2x2 infinite source (placedCount == 1 and no
	 * safe infinite source nearby yet) does it pick the seed closest to {@code firstSeedPos}.
	 */
	private BlockPos pollBestNearbySeed(Level level, Level schematicWorld, LocalPlayer player, long now) {
		if (seedQueue.isEmpty()) return null;
		BlockPos anchor = player.blockPosition();
		if (placedCount == 1 && firstSeedPos != null
				&& !player.getAbilities().instabuild
				&& findNearestSafeRefillSource(level, player, anchor) == null) {
			anchor = firstSeedPos;
		}

		BlockPos bestImmediate = null;
		double bestImmediateDist = Double.MAX_VALUE;
		BlockPos bestWalk = null;
		double bestWalkDist = Double.MAX_VALUE;

		for (BlockPos p : seedQueue) {
			Long until = cellCooldownUntil.get(p.asLong());
			if (until != null && until > now) continue;
			double d = p.distSqr(anchor);
			if (anchor == player.blockPosition()
					&& d <= (BUCKET_REACH + 1.0) * (BUCKET_REACH + 1.0)
					&& !isPlayerInside(player, p)
					&& level.isLoaded(p)
					&& findPourAimTarget(level, schematicWorld, player.getEyePosition(), player, p) != null) {
				if (d < bestImmediateDist) {
					bestImmediateDist = d;
					bestImmediate = p;
				}
			}
			if (d < bestWalkDist) {
				bestWalkDist = d;
				bestWalk = p;
			}
		}

		BlockPos chosen = bestImmediate != null ? bestImmediate : bestWalk;
		if (chosen == null) {
			cellCooldownUntil.clear();
			for (BlockPos p : seedQueue) {
				double d = p.distSqr(anchor);
				if (d < bestWalkDist) {
					bestWalkDist = d;
					chosen = p;
				}
			}
		}
		if (chosen != null) {
			seedQueue.remove(chosen);
		}
		return chosen;
	}

	private StepResult ensureWaterBucketOrStartRefill(
			IBaritone baritone,
			LocalPlayer player,
			Level level,
			Runnable stopMovement
	) {
		if (ensureItemOnHotbar(baritone, player, Items.WATER_BUCKET)) {
			refillWaitTicks = 0;
			return StepResult.RUNNING;
		}
		// Out of water buckets: try refilling empty buckets from a nearby safe infinite water source.
		if (!player.getAbilities().instabuild && hasItemInInventory(player, Items.BUCKET)) {
			if (countEmptySlots(player.getInventory()) == 0) {
				tryConsolidateEmptyBuckets(baritone, player);
			}
			if (!canRefillAnotherBucket(player, 0)) {
				stopMovement.run();
				chat("[Perimeter] 背包已满且空水桶无法堆叠腾出空位，为防止舀水丢出溢出水桶已暂停，请空出至少 1 格背包。");
				return StepResult.MISSING_BUCKET;
			}
			failedRefillSources.clear();
			BlockPos safeSource = findNearestSafeRefillSource(level, player, player.blockPosition());
			if (safeSource != null) {
				refillTarget = safeSource;
				moveGoalDispatched = false;
				moveGraceTicks = 0;
				moveTicks = 0;
				confirmTicks = 0;
				refillRegenWaitTicks = 0;
				waitingConfirm = false;
				refillWaitTicks = 0;
				stopMovement.run();
				return StepResult.RUNNING;
			}
			// Give freshly placed 2x2 seeds up to 12 ticks (0.6s) to finish forming an infinite source!
			if (++refillWaitTicks <= REFILL_SOURCE_WAIT_TICKS) {
				stopMovement.run();
				statusText = "第 Y=" + layerY + " 层等待现场无限水生成...";
				return StepResult.RUNNING;
			}
		}
		stopMovement.run();
		return StepResult.MISSING_BUCKET;
	}

	private void handleTargetRetryOrSkip(
			BlockPos pos,
			Deque<BlockPos> queue,
			Set<Long> layerSkipped,
			String reason
	) {
		long key = pos.asLong();
		int attempts = cellAttempts.merge(key, 1, Integer::sum);
		if (attempts >= MAX_CELL_ATTEMPTS) {
			layerSkipped.add(key);
			cellCooldownUntil.remove(key);
			chat("[Perimeter] 跳过第 Y=" + layerY + " 层无法处理水格 " + pos.toShortString() + "（" + reason + "）");
		} else {
			cellCooldownUntil.put(key, System.currentTimeMillis() + CELL_COOLDOWN_MS);
			queue.addLast(pos);
		}
		activeTarget = null;
		waitingConfirm = false;
		moveGoalDispatched = false;
		moveGraceTicks = 0;
		moveTicks = 0;
		confirmTicks = 0;
	}

	private BlockPos findDirectlyScoopableSource(Level level, LocalPlayer player) {
		BlockPos origin = player.blockPosition();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int r = (int) Math.ceil(BUCKET_REACH);
		for (int dy = -r; dy <= r; dy++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dx = -r; dx <= r; dx++) {
					cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					if (failedRefillSources.contains(cursor.asLong()) || !level.isLoaded(cursor)) continue;
					if (!isSafeRegeneratingSource(level, cursor)) continue;
					Vec3 aim = findScoopAimPoint(level, player, cursor);
					if (aim == null) continue;
					double d = player.getEyePosition().distanceToSqr(aim);
					if (d < bestDist) {
						bestDist = d;
						best = cursor.immutable();
					}
				}
			}
		}
		return best;
	}

	private BlockPos findNearestSafeRefillSource(Level level, LocalPlayer player, BlockPos origin) {
		BlockPos immediate = findDirectlyScoopableSource(level, player);
		if (immediate != null) {
			return immediate;
		}
		BlockPos bestOpenTop = null;
		double bestOpenDist = Double.MAX_VALUE;
		BlockPos bestAny = null;
		double bestAnyDist = Double.MAX_VALUE;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int minY = Math.min(origin.getY() - 6, layerY - 2);
		int maxY = Math.max(origin.getY() + 4, layerY + 2);
		for (int y = minY; y <= maxY; y++) {
			for (int dz = -REFILL_SEARCH_RADIUS; dz <= REFILL_SEARCH_RADIUS; dz++) {
				for (int dx = -REFILL_SEARCH_RADIUS; dx <= REFILL_SEARCH_RADIUS; dx++) {
					cursor.set(origin.getX() + dx, y, origin.getZ() + dz);
					if (failedRefillSources.contains(cursor.asLong()) || !level.isLoaded(cursor)) continue;
					if (!isSafeRegeneratingSource(level, cursor)) continue;
					double d = cursor.distSqr(origin);
					boolean openTop = level.isLoaded(cursor.above())
							&& level.getBlockState(cursor.above()).getCollisionShape(level, cursor.above()).isEmpty()
							&& level.getBlockState(cursor.above()).getFluidState().isEmpty();
					if (openTop && d < bestOpenDist) {
						bestOpenDist = d;
						bestOpenTop = cursor.immutable();
					}
					if (d < bestAnyDist) {
						bestAnyDist = d;
						bestAny = cursor.immutable();
					}
				}
			}
		}
		if (bestOpenTop != null && bestOpenDist <= bestAnyDist + 25.0) {
			return bestOpenTop;
		}
		return bestAny != null ? bestAny : bestOpenTop;
	}

	/**
	 * Finds an aim point inside {@code sourcePos} such that {@code BucketItem.use}
	 * ({@code ClipContext.Fluid.SOURCE_ONLY}) hits ANY safe self-regenerating water source
	 * (accepting closer water cells along the ray inside the same infinite water pool!).
	 */
	private static Vec3 findScoopAimPoint(Level level, LocalPlayer player, BlockPos sourcePos) {
		Vec3 eye = player.getEyePosition();
		Vec3[] probes = {
				new Vec3(sourcePos.getX() + 0.5, sourcePos.getY() + 0.75, sourcePos.getZ() + 0.5),
				Vec3.atCenterOf(sourcePos),
				new Vec3(sourcePos.getX() + 0.5, sourcePos.getY() + 0.25, sourcePos.getZ() + 0.5)
		};
		for (Vec3 pt : probes) {
			if (eye.distanceTo(pt) > BUCKET_REACH) continue;
			BlockHitResult hit = raycast(level, player, pt, ClipContext.Fluid.SOURCE_ONLY);
			if (hit.getType() == HitResult.Type.BLOCK
					&& eye.distanceTo(hit.getLocation()) <= BUCKET_REACH
					&& isSafeRegeneratingSource(level, hit.getBlockPos())) {
				return pt;
			}
		}
		return null;
	}

	/**
	 * Pure decision helper: determines whether scooping another water bucket is guaranteed safe
	 * (either in-place via a single-bucket slot, into an existing empty slot, or after merging
	 * at least two partial empty-bucket stacks to free an empty slot).
	 */
	static boolean canSafelyRefillMore(
			int currentWaterBuckets,
			int targetWaterBuckets,
			int emptySlots,
			int singleEmptyBucketSlots,
			int partialEmptyBucketSlots,
			int fullEmptyBucketSlots
	) {
		if (currentWaterBuckets >= targetWaterBuckets) return false;
		if (singleEmptyBucketSlots > 0) return true;
		if ((partialEmptyBucketSlots + fullEmptyBucketSlots) > 0 && emptySlots > 0) return true;
		return partialEmptyBucketSlots >= 2;
	}

	/**
	 * Pure helper finding two distinct inventory slots holding partial empty-bucket stacks
	 * ({@code 1..maxStack-1}) that can be merged into one stack to free an inventory slot.
	 * Prefers keeping the consolidated stack on the hotbar (slots 0..8) and merging smaller into larger.
	 *
	 * @return {@code int[]{srcInvSlot, dstInvSlot}} or {@code null} if no merge is possible.
	 */
	static int[] findMergeableEmptyBucketPair(int[] bucketCountsBySlot, int maxStack) {
		if (bucketCountsBySlot == null || maxStack <= 1) return null;
		int dst = -1;
		int src = -1;
		// Pick destination: prefer hotbar partial stack with largest count, else backpack largest partial.
		for (int i = 0; i < bucketCountsBySlot.length; i++) {
			int c = bucketCountsBySlot[i];
			if (c <= 0 || c >= maxStack) continue;
			if (dst < 0) {
				dst = i;
			} else {
				boolean curHotbar = i < 9;
				boolean dstHotbar = dst < 9;
				if ((curHotbar && !dstHotbar) || (curHotbar == dstHotbar && c > bucketCountsBySlot[dst])) {
					dst = i;
				}
			}
		}
		if (dst < 0) return null;
		// Pick source: smallest other partial stack (prefer backpack first so hotbar stays clean).
		for (int i = 0; i < bucketCountsBySlot.length; i++) {
			if (i == dst) continue;
			int c = bucketCountsBySlot[i];
			if (c <= 0 || c >= maxStack) continue;
			if (src < 0) {
				src = i;
			} else {
				boolean curBackpack = i >= 9;
				boolean srcBackpack = src >= 9;
				if ((curBackpack && !srcBackpack) || (curBackpack == srcBackpack && c < bucketCountsBySlot[src])) {
					src = i;
				}
			}
		}
		return src >= 0 ? new int[]{src, dst} : null;
	}

	/**
	 * Pure helper selecting the safest empty-bucket slot index (0..35) to hold for scooping:
	 * when {@code emptySlots == 0}, ONLY a slot with {@code count == 1} is safe (in-place transform);
	 * when {@code emptySlots > 0}, prefers hotbar single bucket -> hotbar any -> backpack single -> backpack any.
	 */
	static int pickSafeEmptyBucketSlot(int[] bucketCountsBySlot, int emptySlots) {
		if (bucketCountsBySlot == null) return -1;
		// 1) Hotbar single empty bucket (always safe even with 0 empty slots)
		for (int i = 0; i < Math.min(9, bucketCountsBySlot.length); i++) {
			if (bucketCountsBySlot[i] == 1) return i;
		}
		// 2) Backpack single empty bucket (safe even with 0 empty slots; can be swapped to hotbar)
		for (int i = 9; i < bucketCountsBySlot.length; i++) {
			if (bucketCountsBySlot[i] == 1) return i;
		}
		if (emptySlots <= 0) {
			return -1;
		}
		// 3) Hotbar stacked empty bucket (safe because emptySlots > 0)
		for (int i = 0; i < Math.min(9, bucketCountsBySlot.length); i++) {
			if (bucketCountsBySlot[i] > 1) return i;
		}
		// 4) Backpack stacked empty bucket (safe because emptySlots > 0)
		for (int i = 9; i < bucketCountsBySlot.length; i++) {
			if (bucketCountsBySlot[i] > 1) return i;
		}
		return -1;
	}

	private static boolean canRefillAnotherBucket(LocalPlayer player, int currentWaterBuckets) {
		Inventory inv = player.getInventory();
		int emptySlots = 0;
		int singleBucket = 0;
		int partialBucket = 0;
		int fullBucket = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) {
				emptySlots++;
			} else if (stack.is(Items.BUCKET)) {
				int c = stack.getCount();
				if (c == 1) singleBucket++;
				if (c < stack.getMaxStackSize()) partialBucket++;
				else fullBucket++;
			}
		}
		return canSafelyRefillMore(currentWaterBuckets, REFILL_TARGET_WATER_BUCKETS,
				emptySlots, singleBucket, partialBucket, fullBucket);
	}

	private static int countEmptySlots(Inventory inv) {
		int empty = 0;
		for (int i = 0; i < 36; i++) {
			if (inv.getItem(i).isEmpty()) empty++;
		}
		return empty;
	}

	private static boolean tryConsolidateEmptyBuckets(IBaritone baritone, LocalPlayer player) {
		if (baritone == null || !(player.containerMenu instanceof InventoryMenu menu)) {
			return false;
		}
		if (!menu.getCarried().isEmpty()) {
			return false;
		}
		Inventory inv = player.getInventory();
		int[] counts = new int[36];
		int maxStack = Items.BUCKET.getDefaultMaxStackSize();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(Items.BUCKET)) {
				counts[i] = s.getCount();
				maxStack = s.getMaxStackSize();
			}
		}
		int[] pair = findMergeableEmptyBucketPair(counts, maxStack);
		if (pair == null) {
			return false;
		}
		int srcMenuSlot = toInventoryMenuSlot(pair[0]);
		int dstMenuSlot = toInventoryMenuSlot(pair[1]);
		var pc = baritone.getPlayerContext().playerController();
		pc.windowClick(menu.containerId, srcMenuSlot, 0, ContainerInput.PICKUP, player);
		pc.windowClick(menu.containerId, dstMenuSlot, 0, ContainerInput.PICKUP, player);
		if (!menu.getCarried().isEmpty()) {
			pc.windowClick(menu.containerId, srcMenuSlot, 0, ContainerInput.PICKUP, player);
		}
		pc.syncHeldItem();
		return true;
	}

	private static boolean ensureSafeEmptyBucketOnHotbar(IBaritone baritone, LocalPlayer player) {
		Inventory inv = player.getInventory();
		int emptySlots = countEmptySlots(inv);
		int[] counts = new int[36];
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(Items.BUCKET)) {
				counts[i] = s.getCount();
			}
		}
		int safeSlot = pickSafeEmptyBucketSlot(counts, emptySlots);
		if (safeSlot < 0) {
			return false;
		}
		if (safeSlot < 9) {
			if (inv.getSelectedSlot() != safeSlot) {
				inv.setSelectedSlot(safeSlot);
			}
			return true;
		}
		if (baritone == null || !(player.containerMenu instanceof InventoryMenu)) {
			return false;
		}
		int hotbarSlot = pickSwapHotbarSlot(inv);
		baritone.getPlayerContext().playerController().windowClick(
				player.inventoryMenu.containerId,
				safeSlot,
				hotbarSlot,
				ContainerInput.SWAP,
				player
		);
		inv.setSelectedSlot(hotbarSlot);
		baritone.getPlayerContext().playerController().syncHeldItem();
		return true;
	}

	private static int toInventoryMenuSlot(int invIndex) {
		return invIndex < 9 ? 36 + invIndex : invIndex;
	}

	private static int countInventoryItem(LocalPlayer player, Item item) {
		Inventory inv = player.getInventory();
		int total = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.is(item)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/**
	 * A water source is safe to scoop infinitely if it is pure water (not a waterlogged block),
	 * sits on solid/water-source support, and has >= 2 passable horizontal water source neighbors
	 * so scooping it regenerates a full source in 5 ticks.
	 */
	private static boolean isSafeRegeneratingSource(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof LiquidBlock || state.getBlock() instanceof BubbleColumnBlock)
				|| !isWorldWaterSource(state)) {
			return false;
		}
		if (!hasBelowWaterSupport(level, pos)) {
			return false;
		}
		int sourceNeighbors = 0;
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos nbPos = pos.relative(dir);
			if (!level.isLoaded(nbPos)) continue;
			BlockState nbState = level.getBlockState(nbPos);
			if (!isWorldWaterSource(nbState)) continue;
			if (!Shapes.mergedFaceOccludes(
					nbState.getCollisionShape(level, nbPos),
					Shapes.empty(),
					dir.getOpposite())) {
				sourceNeighbors++;
				if (sourceNeighbors >= 2) return true;
			}
		}
		return false;
	}

	private record AimTarget(BlockPos supportPos, Direction face, Vec3 point, float yaw, float pitch) {}

	private static final double FACE_SIDE_MARGIN = 0.08;
	private static final double POUR_JITTER_XZ = 0.08;
	private static final double POUR_JITTER_Y = 0.06;

	/**
	 * Pure geometric safety check: returns true only when {@code eye} lies strictly on the
	 * target-cell side of {@code hitFace} by at least {@code margin} blocks, preventing grazing
	 * shots from atop or outside a rim wall from hitting the top/outer face and spilling water outside.
	 */
	static boolean isEyeOnTargetSideOfFace(
			Vec3 eye,
			BlockPos supportPos,
			AABB box,
			Direction hitFace,
			double margin
	) {
		if (eye == null || supportPos == null || box == null || hitFace == null) return false;
		return switch (hitFace) {
			case UP -> eye.y >= supportPos.getY() + box.maxY + margin;
			case DOWN -> eye.y <= supportPos.getY() + box.minY - margin;
			case NORTH -> eye.z <= supportPos.getZ() + box.minZ - margin;
			case SOUTH -> eye.z >= supportPos.getZ() + box.maxZ + margin;
			case WEST -> eye.x <= supportPos.getX() + box.minX - margin;
			case EAST -> eye.x >= supportPos.getX() + box.maxX + margin;
		};
	}

	/** Pure yaw/pitch calculation from {@code eye} to {@code target}. */
	static float[] computeYawPitch(Vec3 eye, Vec3 target) {
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		double horiz = Math.hypot(dx, dz);
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
		return new float[]{yaw, pitch};
	}

	/**
	 * Finds an unobstructed face on a neighbor of {@code targetPos} such that
	 * {@code BucketItem.use} raycast hits {@code supportPos} on {@code face} and places water
	 * into {@code targetPos}.
	 */
	/**
	 * Checks whether {@code targetPos} borders at least one solid/clickable block face (floor or wall)
	 * so {@code BucketItem.use} can place water directly into {@code targetPos}.
	 * On upper layers of 3D water tanks (water-over-water), only wall/pillar-adjacent cells return true.
	 */
	private static boolean hasClickableSupportNeighbor(
			Level level,
			Level schematicWorld,
			BlockPos targetPos,
			Set<Long> futureWaterKeys,
			boolean previewMode
	) {
		Direction[] dirs = {
				Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
		};
		for (Direction dir : dirs) {
			BlockPos nbPos = targetPos.relative(dir);
			if (futureWaterKeys.contains(nbPos.asLong())) continue;
			if (!level.isLoaded(nbPos)) continue;
			Level sampleLevel = (previewMode && dir != Direction.DOWN && dir != Direction.UP) ? schematicWorld : level;
			BlockState state = sampleLevel.getBlockState(nbPos);
			if (state.isAir() || state.getBlock() instanceof LiquidBlock || state.getBlock() instanceof BubbleColumnBlock) {
				if (previewMode && sampleLevel == schematicWorld) {
					state = level.getBlockState(nbPos);
					sampleLevel = level;
				}
			}
			if (state.isAir() || state.getBlock() instanceof LiquidBlock || state.getBlock() instanceof BubbleColumnBlock) {
				continue;
			}
			if (state.getShape(sampleLevel, nbPos).isEmpty()) continue;
			if (state.getBlock() instanceof LiquidBlockContainer
					&& state.hasProperty(BlockStateProperties.WATERLOGGED)
					&& !state.getValue(BlockStateProperties.WATERLOGGED)
					&& !previewMode) {
				continue;
			}
			return true;
		}
		return false;
	}

	private static AimTarget findPourAimTarget(
			Level level,
			Level schematicWorld,
			Vec3 eye,
			LocalPlayer player,
			BlockPos targetPos
	) {
		Direction[] order = {
				Direction.DOWN,
				Direction.NORTH,
				Direction.SOUTH,
				Direction.EAST,
				Direction.WEST,
				Direction.UP
		};
		double[] uSteps = { 0.50, 0.28, 0.72 };
		double[] vSteps = { 0.42, 0.56, 0.26 };
		for (Direction dirToSupport : order) {
			BlockPos supportPos = targetPos.relative(dirToSupport);
			if (!level.isLoaded(supportPos)) continue;
			BlockState supportState = level.getBlockState(supportPos);
			if (supportState.isAir()) continue;
			VoxelShape shape = supportState.getShape(level, supportPos);
			if (shape.isEmpty()) continue;
			// Do not click a dry LiquidBlockContainer: BucketItem would waterlog it instead of pouring into targetPos!
			if (supportState.getBlock() instanceof LiquidBlockContainer lbc
					&& lbc.canPlaceLiquid(player, level, supportPos, supportState, Fluids.WATER)) {
				continue;
			}
			Direction hitFace = dirToSupport.getOpposite();
			if (!supportPos.relative(hitFace).equals(targetPos)) continue;
			AABB box = shape.bounds();
			// Require the player's eye to be strictly on the inner/target side of the clicked face!
			if (!isEyeOnTargetSideOfFace(eye, supportPos, box, hitFace, FACE_SIDE_MARGIN)) {
				continue;
			}
			double dx = box.maxX - box.minX;
			double dy = box.maxY - box.minY;
			double dz = box.maxZ - box.minZ;
			for (double v : vSteps) {
				for (double u : uSteps) {
					double px = supportPos.getX() + box.minX + dx * u;
					double py = supportPos.getY() + box.minY + dy * v;
					double pz = supportPos.getZ() + box.minZ + dz * u;
					Vec3 probe = switch (hitFace) {
						case UP -> new Vec3(px, supportPos.getY() + box.maxY, supportPos.getZ() + box.minZ + dz * v);
						case DOWN -> new Vec3(px, supportPos.getY() + box.minY, supportPos.getZ() + box.minZ + dz * v);
						case NORTH -> new Vec3(px, py, supportPos.getZ() + box.minZ);
						case SOUTH -> new Vec3(px, py, supportPos.getZ() + box.maxZ);
						case WEST -> new Vec3(supportPos.getX() + box.minX, py, pz);
						case EAST -> new Vec3(supportPos.getX() + box.maxX, py, pz);
					};
					if (eye.distanceTo(probe) > BUCKET_REACH) continue;
					float[] yp = computeYawPitch(eye, probe);
					if (isQuantizedPourAimSafe(level, schematicWorld, player, eye, yp[0], yp[1], supportPos, hitFace, targetPos)) {
						return new AimTarget(supportPos, hitFace, probe, yp[0], yp[1]);
					}
				}
			}
		}
		return null;
	}

	/**
	 * Verifies that casting vanilla's exact {@code Vec3.directionFromRotation(pitch, yaw)} ray
	 * from {@code eye} strictly hits {@code supportPos} on {@code hitFace}, and that any jittered
	 * neighbor ray (accounting for minor position lag or water bobbing) NEVER hits a face that
	 * would place water outside the schematic water cells.
	 */
	private static boolean isQuantizedPourAimSafe(
			Level level,
			Level schematicWorld,
			LocalPlayer player,
			Vec3 eye,
			float yaw,
			float pitch,
			BlockPos supportPos,
			Direction hitFace,
			BlockPos targetPos
	) {
		BlockHitResult centerHit = raycastFromRotation(level, player, eye, yaw, pitch, ClipContext.Fluid.NONE);
		if (!hitsExactPourTarget(centerHit, supportPos, hitFace, targetPos)) {
			return false;
		}
		Vec3[] jitterOffsets = {
				new Vec3(POUR_JITTER_XZ, 0.0, 0.0),
				new Vec3(-POUR_JITTER_XZ, 0.0, 0.0),
				new Vec3(0.0, 0.0, POUR_JITTER_XZ),
				new Vec3(0.0, 0.0, -POUR_JITTER_XZ),
				new Vec3(0.0, -POUR_JITTER_Y, 0.0)
		};
		for (Vec3 off : jitterOffsets) {
			Vec3 jEye = eye.add(off);
			BlockPos jCell = BlockPos.containing(jEye);
			if (level.isLoaded(jCell) && !level.getBlockState(jCell).getCollisionShape(level, jCell).isEmpty()) {
				continue;
			}
			BlockHitResult jHit = raycastFromRotation(level, player, jEye, yaw, pitch, ClipContext.Fluid.NONE);
			if (jHit == null || jHit.getType() != HitResult.Type.BLOCK) {
				continue;
			}
			BlockPos placedPos = jHit.getBlockPos().relative(jHit.getDirection());
			if (placedPos.equals(targetPos)) {
				continue;
			}
			// Allow angled shots along an inner wall where adjacent cells are also schematic water sources,
			// but strictly reject any angle where slight jitter could spill onto a wall top or outside cell!
			if (schematicWorld == null
					|| !schematicWorld.isLoaded(placedPos)
					|| !isSchematicWaterSource(schematicWorld.getBlockState(placedPos))) {
				return false;
			}
		}
		return true;
	}

	private static boolean hitsExactPourTarget(
			BlockHitResult hit,
			BlockPos supportPos,
			Direction hitFace,
			BlockPos targetPos
	) {
		return hit != null
				&& hit.getType() == HitResult.Type.BLOCK
				&& hit.getBlockPos().equals(supportPos)
				&& hit.getDirection() == hitFace
				&& hit.getBlockPos().relative(hit.getDirection()).equals(targetPos);
	}

	private static AimTarget findWaterlogAimTarget(Level level, LocalPlayer player, BlockPos targetPos) {
		Vec3 eye = player.getEyePosition();
		Vec3[] probes = {
				Vec3.atCenterOf(targetPos),
				new Vec3(targetPos.getX() + 0.5, targetPos.getY() + 0.25, targetPos.getZ() + 0.5),
				new Vec3(targetPos.getX() + 0.5, targetPos.getY() + 0.75, targetPos.getZ() + 0.5)
		};
		for (Vec3 pt : probes) {
			if (eye.distanceTo(pt) > BUCKET_REACH) continue;
			float[] yp = computeYawPitch(eye, pt);
			BlockHitResult hit = raycastFromRotation(level, player, eye, yp[0], yp[1], ClipContext.Fluid.NONE);
			if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(targetPos)) {
				return new AimTarget(targetPos, hit.getDirection(), pt, yp[0], yp[1]);
			}
		}
		return null;
	}

	private static BlockHitResult raycastFromRotation(
			Level level,
			LocalPlayer player,
			Vec3 eye,
			float yaw,
			float pitch,
			ClipContext.Fluid fluidMode
	) {
		Vec3 look = Vec3.directionFromRotation(pitch, yaw);
		Vec3 end = eye.add(look.scale(BUCKET_REACH + 0.3));
		return level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, fluidMode, player));
	}

	private static void applyAndSyncRotation(Minecraft mc, LocalPlayer player, float yaw, float pitch) {
		player.setYRot(yaw);
		player.setXRot(pitch);
		player.yHeadRot = yaw;
		if (mc != null && mc.getConnection() != null) {
			mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
					yaw, pitch, player.onGround(), player.horizontalCollision));
		}
	}

	private static boolean isPlayerSettled(LocalPlayer player) {
		Vec3 vel = player.getDeltaMovement();
		double horizSq = vel.x * vel.x + vel.z * vel.z;
		return horizSq < 0.025;
	}

	private static boolean canRaycastSourceFluid(Level level, LocalPlayer player, BlockPos sourcePos, Vec3 aimPoint) {
		BlockHitResult hit = raycast(level, player, aimPoint, ClipContext.Fluid.SOURCE_ONLY);
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(sourcePos);
	}

	private static BlockHitResult raycast(Level level, LocalPlayer player, Vec3 targetPoint, ClipContext.Fluid fluidMode) {
		Vec3 eye = player.getEyePosition();
		Vec3 dir = targetPoint.subtract(eye);
		Vec3 end = dir.lengthSqr() < 1e-6 ? targetPoint : eye.add(dir.normalize().scale(BUCKET_REACH + 0.3));
		return level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, fluidMode, player));
	}

	private static BlockPos findWaterVantage(Level level, BlockPos target, Vec3 playerPos, boolean flying) {
		BlockPos[] candidates = flying ? new BlockPos[]{
				target.above(2),
				target.north(1).above(2),
				target.south(1).above(2),
				target.east(1).above(2),
				target.west(1).above(2),
				target.north(2).above(1),
				target.south(2).above(1),
				target.east(2).above(1),
				target.west(2).above(1),
				target.north(1).above(1),
				target.south(1).above(1),
				target.east(1).above(1),
				target.west(1).above(1)
		} : new BlockPos[]{
				target.north(1).above(1),
				target.south(1).above(1),
				target.east(1).above(1),
				target.west(1).above(1),
				target.north(1),
				target.south(1),
				target.east(1),
				target.west(1),
				target.north(2),
				target.south(2),
				target.east(2),
				target.west(2),
				target.north(2).above(1),
				target.south(2).above(1),
				target.east(2).above(1),
				target.west(2).above(1)
		};
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		Vec3 targetCenter = Vec3.atCenterOf(target);
		for (BlockPos c : candidates) {
			if (c.equals(target) || c.above().equals(target)) continue;
			if (!level.isLoaded(c) || !level.isLoaded(c.above())) continue;
			BlockState feet = level.getBlockState(c);
			BlockState head = level.getBlockState(c.above());
			if (!feet.getCollisionShape(level, c).isEmpty()) continue;
			if (!head.getCollisionShape(level, c.above()).isEmpty()) continue;
			if (isDownwardBubbleColumn(feet) || isDownwardBubbleColumn(head)) continue;
			if (!flying && level.getBlockState(c.below()).getCollisionShape(level, c.below()).isEmpty()) continue;
			Vec3 eyeAtC = new Vec3(c.getX() + 0.5, c.getY() + 1.62, c.getZ() + 0.5);
			if (eyeAtC.distanceTo(targetCenter) > BUCKET_REACH - 0.15) continue;
			double d = playerPos.distanceToSqr(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
			if (d < bestScore) {
				bestScore = d;
				best = c;
			}
		}
		return best != null ? best : (flying ? target.above(2) : target.north(1));
	}

	private static boolean isPassableEdge(
			Level level,
			Level schematicWorld,
			int y,
			WaterSourceOptimizer.Cell from,
			WaterSourceOptimizer.Cell to,
			Set<Long> futureWaterKeys,
			boolean previewMode
	) {
		int dx = to.x() - from.x();
		int dz = to.z() - from.z();
		Direction dir;
		if (dx == 1 && dz == 0) dir = Direction.EAST;
		else if (dx == -1 && dz == 0) dir = Direction.WEST;
		else if (dx == 0 && dz == 1) dir = Direction.SOUTH;
		else if (dx == 0 && dz == -1) dir = Direction.NORTH;
		else return false;

		BlockPos fromPos = new BlockPos(from.x(), y, from.z());
		BlockPos toPos = new BlockPos(to.x(), y, to.z());
		if (!level.isLoaded(fromPos) || !level.isLoaded(toPos)) return false;

		VoxelShape fromShape = futureWaterKeys.contains(fromPos.asLong())
				? Shapes.empty()
				: (previewMode ? schematicWorld.getBlockState(fromPos) : level.getBlockState(fromPos))
						.getCollisionShape(previewMode ? schematicWorld : level, fromPos);
		VoxelShape toShape = futureWaterKeys.contains(toPos.asLong())
				? Shapes.empty()
				: (previewMode ? schematicWorld.getBlockState(toPos) : level.getBlockState(toPos))
						.getCollisionShape(previewMode ? schematicWorld : level, toPos);
		return !Shapes.mergedFaceOccludes(fromShape, toShape, dir);
	}

	private static List<BlockPos> scanPendingWaterlogged(
			Level level,
			Level schematicWorld,
			LitematicaFootprintReader.Footprint footprint,
			int y,
			Set<Long> skipped
	) {
		List<BlockPos> list = new ArrayList<>();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
			for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
				cursor.set(x, y, z);
				if (!level.isLoaded(cursor)) continue;
				if (skipped != null && skipped.contains(cursor.asLong())) continue;
				BlockState tgt = schematicWorld.getBlockState(cursor);
				if (!isSchematicWaterlogged(tgt)) continue;
				BlockState cur = level.getBlockState(cursor);
				if (cur.getBlock() == tgt.getBlock()
						&& cur.hasProperty(BlockStateProperties.WATERLOGGED)
						&& !cur.getValue(BlockStateProperties.WATERLOGGED)) {
					list.add(cursor.immutable());
				}
			}
		}
		return list;
	}

	private static List<BlockPos> scanPendingWaterSources(
			Level level,
			Level schematicWorld,
			LitematicaFootprintReader.Footprint footprint,
			int y,
			Set<Long> skipped
	) {
		List<BlockPos> list = new ArrayList<>();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
			for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
				cursor.set(x, y, z);
				if (!level.isLoaded(cursor)) continue;
				if (skipped != null && skipped.contains(cursor.asLong())) continue;
				BlockState tgt = schematicWorld.getBlockState(cursor);
				if (!isSchematicWaterSource(tgt)) continue;
				BlockState cur = level.getBlockState(cursor);
				if (!isWorldWaterSource(cur) && canReceiveWater(cur)) {
					list.add(cursor.immutable());
				}
			}
		}
		return list;
	}

	private static boolean canReceiveWater(BlockState current) {
		return current.isAir()
				|| current.canBeReplaced()
				|| current.getBlock() instanceof LiquidBlock
				|| current.getBlock() instanceof BubbleColumnBlock;
	}

	private static boolean hasItemInInventory(LocalPlayer player, Item item) {
		Inventory inv = player.getInventory();
		for (int i = 0; i < 36; i++) {
			if (inv.getItem(i).is(item)) return true;
		}
		return false;
	}

	private static boolean selectHotbarItem(LocalPlayer player, Item item) {
		Inventory inv = player.getInventory();
		for (int i = 0; i < 9; i++) {
			if (inv.getItem(i).is(item)) {
				if (inv.getSelectedSlot() != i) {
					inv.setSelectedSlot(i);
				}
				return true;
			}
		}
		return false;
	}

	private static boolean ensureItemOnHotbar(IBaritone baritone, LocalPlayer player, Item item) {
		if (selectHotbarItem(player, item)) {
			return true;
		}
		Inventory inv = player.getInventory();
		int backpackSlot = -1;
		for (int i = 9; i < 36; i++) {
			if (inv.getItem(i).is(item)) {
				backpackSlot = i;
				break;
			}
		}
		if (backpackSlot < 0 || baritone == null || !(player.containerMenu instanceof InventoryMenu)) {
			return false;
		}
		int hotbarSlot = pickSwapHotbarSlot(inv);
		baritone.getPlayerContext().playerController().windowClick(
				player.inventoryMenu.containerId,
				backpackSlot,
				hotbarSlot,
				ContainerInput.SWAP,
				player
		);
		inv.setSelectedSlot(hotbarSlot);
		baritone.getPlayerContext().playerController().syncHeldItem();
		return true;
	}

	private static int pickSwapHotbarSlot(Inventory inv) {
		for (int i = 0; i < 9; i++) {
			if (inv.getItem(i).isEmpty()) return i;
		}
		for (int i = 0; i < 9; i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET)) return i;
		}
		return inv.getSelectedSlot();
	}

	private static boolean isPlayerInside(LocalPlayer player, BlockPos pos) {
		BlockPos feet = player.blockPosition();
		return feet.equals(pos) || feet.above().equals(pos);
	}

	private static List<BlockPos> orderByNearest(List<BlockPos> points) {
		if (points.size() <= 1) return points;
		List<BlockPos> remaining = new ArrayList<>(points);
		List<BlockPos> ordered = new ArrayList<>(remaining.size());
		BlockPos cur = remaining.removeFirst();
		ordered.add(cur);
		while (!remaining.isEmpty()) {
			int bestIdx = 0;
			double bestDist = cur.distSqr(remaining.getFirst());
			for (int i = 1; i < remaining.size(); i++) {
				double d = cur.distSqr(remaining.get(i));
				if (d < bestDist) {
					bestDist = d;
					bestIdx = i;
				}
			}
			cur = remaining.remove(bestIdx);
			ordered.add(cur);
		}
		return ordered;
	}

	private static void aimAt(LocalPlayer player, Vec3 target) {
		Vec3 eye = player.getEyePosition();
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		double horiz = Math.hypot(dx, dz);
		player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
		player.setXRot((float) Math.toDegrees(-Math.atan2(dy, horiz)));
	}

	private static void chat(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.gui != null) {
			mc.gui.chatListener().handleSystemMessage(Component.literal(text), false);
		}
	}
}
