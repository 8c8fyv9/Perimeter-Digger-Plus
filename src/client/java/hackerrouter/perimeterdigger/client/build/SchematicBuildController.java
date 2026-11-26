package hackerrouter.perimeterdigger.client.build;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.utils.BackpackToolManager;
import hackerrouter.perimeterdigger.client.config.PerimeterConfig;
import hackerrouter.perimeterdigger.client.detect.LitematicaFootprintReader;
import hackerrouter.perimeterdigger.client.flight.CreativeFlightNavigator;
import hackerrouter.perimeterdigger.client.navigation.BubbleColumnEscapeController;
import hackerrouter.perimeterdigger.client.navigation.NavigationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Layer-by-layer automated schematic builder delegating actual block placement to litematica-printer.
 * Controls Litematica's single-layer display from minY upward, counts per-layer materials,
 * moves the player into printing range (via /fly or Baritone ground walking), and resolves wrong
 * blocks by either skipping or breaking-and-replacing.
 */
public final class SchematicBuildController {
	public enum Outcome {
		RUNNING,
		PAUSED_MISSING_MATERIALS,
		COMPLETE,
		ERROR
	}

	private static final double BREAK_REACH = 4.2;
	private static final int PRINT_WAIT_TICKS = 25;
	private static final int MOVE_TIMEOUT_TICKS = 160;
	private static final int BREAK_TIMEOUT_TICKS = 120;
	private static final int SCHEMATIC_LOAD_WAIT_TICKS = 160;
	private static final long BUILD_FAILURE_TIMEOUT_MS = 1500L;

	private final LitematicaPrinterBridge bridge = new LitematicaPrinterBridge();
	private final CreativeFlightNavigator flightNav = new CreativeFlightNavigator();
	private final SchematicWaterPlacer waterPlacer = new SchematicWaterPlacer();
	private final SchematicSignPlacer signPlacer = new SchematicSignPlacer();
	private final SchematicResourceController resourceController = new SchematicResourceController();
	private final BubbleColumnEscapeController bubbleEscape = new BubbleColumnEscapeController();
	private final Set<Long> layerSkipped = new HashSet<>();
	private final Map<Long, Long> cooldownUntil = new HashMap<>();
	private final Map<Long, Integer> failCounts = new HashMap<>();

	private boolean active;
	private boolean suspended;
	private LitematicaFootprintReader.Footprint footprint;
	private LitematicaPrinterBridge.LayerRangeSnapshot savedLayerRange;
	private Boolean savedPrintMode;
	private Boolean savedAllowBreak;
	private List<Block> savedAllowBreakAnyway;
	private Boolean savedAllowWalkOnMagmaBlocks;
	private Long savedFailureTimeoutMs;

	private SchematicLayerPlanner.WrongBlockPolicy wrongBlockPolicy = SchematicLayerPlanner.WrongBlockPolicy.SKIP;
	private SchematicLayerPlanner.MissingMaterialPolicy missingMaterialPolicy = SchematicLayerPlanner.MissingMaterialPolicy.PLACE_AVAILABLE;
	private boolean clearExtraBlocks;
	private boolean optimizeWater = true;
	private PerimeterConfig activeConfig;
	private long resupplyCooldownUntil;
	private int currentY;
	private boolean layerInitialized;
	private boolean sawAnySchematicBlock;
	private int schematicWaitTicks;
	private int layerTotalTargets;
	private int layerPlacedCount;
	private String statusDetail = "";
	private String lastMissingSummary = "";

	private SchematicLayerPlanner.CellTask activeTask;
	private BlockPos activePos;
	private BlockPos lastAnchorPos;
	private boolean activeBreaking;
	private boolean moveGoalDispatched;
	private boolean usingFarApproachGoal;
	private int moveGraceTicks;
	private int moveTicks;
	private int moveHardLimitTicks = MOVE_TIMEOUT_TICKS;
	private double bestMoveDistSq = Double.MAX_VALUE;
	private int noProgressMoveTicks;
	private int waitTicks;
	private int breakTicks;
	private boolean breakingStarted;
	private BlockPos unloadedChunkTarget;

	public boolean isActive() {
		return active;
	}

	public boolean isUsingCreativeFlight() {
		return active && flightNav.isActive();
	}

	public String statusDetail() {
		return statusDetail;
	}

	public String lastMissingSummary() {
		return lastMissingSummary;
	}

	public int currentY() {
		return currentY;
	}

	/**
	 * Returns the active or paused target layer Y if a schematic build session is currently in
	 * progress or paused; returns null when idle/stopped.
	 */
	public Integer currentTargetY() {
		if ((active || savedLayerRange != null) && footprint != null
				&& currentY >= footprint.minY() && currentY <= footprint.maxY()) {
			return currentY;
		}
		return null;
	}

	public void syncConfig(PerimeterConfig config) {
		if (config == null) return;
		this.activeConfig = config;
		this.wrongBlockPolicy = SchematicLayerPlanner.WrongBlockPolicy.parse(config.schematicWrongBlockPolicy);
		this.missingMaterialPolicy = SchematicLayerPlanner.MissingMaterialPolicy.parse(config.schematicMissingMaterialPolicy);
		this.clearExtraBlocks = config.schematicClearExtraBlocks;
		this.optimizeWater = config.schematicOptimizeWater;
	}

	/**
	 * Validates Litematica + litematica-printer availability, reads the active placement footprint,
	 * snapshots user settings, and initializes bottom-up layer building.
	 *
	 * @return null on success, or a user-facing Chinese error message on failure.
	 */
	public String start(IBaritone baritone, NavigationService navigation, PerimeterConfig config) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return "当前未进入世界，无法启动自动放置投影";
		}
		LitematicaPrinterBridge.Availability avail = bridge.checkAvailability();
		if (!avail.ok()) {
			return avail.errorMessage();
		}
		LitematicaFootprintReader.ReadResult fpResult = LitematicaFootprintReader.readCurrentFootprint(mc);
		if (!fpResult.ok()) {
			return fpResult.userMessage();
		}
		syncConfig(config);
		stop(baritone, navigation);

		this.footprint = fpResult.footprint();
		this.savedLayerRange = bridge.snapshotLayerRange();
		this.savedPrintMode = bridge.getPrintMode();
		disablePathBreaking();

		this.currentY = footprint.minY();
		this.layerInitialized = false;
		this.sawAnySchematicBlock = false;
		this.schematicWaitTicks = 0;
		this.layerSkipped.clear();
		this.cooldownUntil.clear();
		this.failCounts.clear();
		this.layerTotalTargets = 0;
		this.layerPlacedCount = 0;
		this.lastMissingSummary = "";
		this.suspended = false;
		this.active = true;
		this.statusDetail = "投影打印已启动：" + footprint.placementName()
				+ " Y[" + footprint.minY() + ".." + footprint.maxY() + "]";
		chat("[Perimeter] 开始逐层自动放置投影「" + footprint.placementName() + "」Y["
				+ footprint.minY() + ".." + footprint.maxY() + "]，错误方块策略："
				+ (wrongBlockPolicy == SchematicLayerPlanner.WrongBlockPolicy.BREAK_AND_PLACE ? "破坏并重放" : "跳过")
				+ "，水体优化：" + (optimizeWater ? "开启" : "关闭"));
		return null;
	}

	/** Resumes a paused schematic build on the current layer (re-counting materials). */
	public boolean resume(IBaritone baritone, NavigationService navigation, PerimeterConfig config) {
		if (footprint == null) return false;
		LitematicaPrinterBridge.Availability avail = bridge.checkAvailability();
		if (!avail.ok()) {
			statusDetail = avail.errorMessage();
			return false;
		}
		syncConfig(config);
		disablePathBreaking();
		clearActiveTarget(baritone, navigation);
		waterPlacer.reset();
		this.cooldownUntil.clear();
		this.layerInitialized = false;
		this.schematicWaitTicks = 0;
		this.unloadedChunkTarget = null;
		this.suspended = false;
		this.active = true;
		return true;
	}

	/** Pauses without discarding currentY or LayerRange snapshot so resume continues in place. */
	public void pause(IBaritone baritone, NavigationService navigation) {
		clearActiveTarget(baritone, navigation);
		bubbleEscape.reset(baritone);
		waterPlacer.reset();
		resourceController.stop(baritone, navigation);
		bridge.setPrintMode(false);
		restoreAllowBreak();
		this.active = false;
		this.suspended = false;
	}

	/** Temporarily suspends printer & movement during eating or mob retaliation. */
	public void suspendForInterruption(IBaritone baritone, NavigationService navigation) {
		if (!active) return;
		suspended = true;
		clearActiveTarget(baritone, navigation);
		bubbleEscape.reset(baritone);
		waterPlacer.onMovementInterrupted();
		resourceController.stop(baritone, navigation);
		bridge.setPrintMode(false);
	}

	public void resumeAfterInterruption() {
		if (!active) return;
		suspended = false;
		moveGoalDispatched = false;
		usingFarApproachGoal = false;
		moveGraceTicks = 0;
		noProgressMoveTicks = 0;
		bestMoveDistSq = Double.MAX_VALUE;
		waterPlacer.onMovementInterrupted();
	}

	/** Stops schematic building completely and restores the user's LayerRange & PRINT_MODE. */
	public void stop(IBaritone baritone, NavigationService navigation) {
		clearActiveTarget(baritone, navigation);
		bubbleEscape.reset(baritone);
		waterPlacer.reset();
		resourceController.stop(baritone, navigation);
		if (savedPrintMode != null) {
			bridge.setPrintMode(savedPrintMode);
			savedPrintMode = null;
		} else if (active) {
			bridge.setPrintMode(false);
		}
		if (savedLayerRange != null) {
			bridge.restoreLayerRange(savedLayerRange);
			savedLayerRange = null;
		}
		restoreAllowBreak();
		this.active = false;
		this.suspended = false;
		this.layerInitialized = false;
		this.sawAnySchematicBlock = false;
		this.schematicWaitTicks = 0;
		this.unloadedChunkTarget = null;
		this.layerSkipped.clear();
		this.cooldownUntil.clear();
		this.failCounts.clear();
	}

	private void disablePathBreaking() {
		if (savedAllowBreak == null) {
			savedAllowBreak = BaritoneAPI.getSettings().allowBreak.value;
		}
		if (savedAllowBreakAnyway == null) {
			savedAllowBreakAnyway = new ArrayList<>(BaritoneAPI.getSettings().allowBreakAnyway.value);
		}
		if (savedAllowWalkOnMagmaBlocks == null) {
			savedAllowWalkOnMagmaBlocks = BaritoneAPI.getSettings().allowWalkOnMagmaBlocks.value;
		}
		if (savedFailureTimeoutMs == null) {
			savedFailureTimeoutMs = BaritoneAPI.getSettings().failureTimeoutMS.value;
		}
		BaritoneAPI.getSettings().allowBreak.value = false;
		BaritoneAPI.getSettings().allowBreakAnyway.value = new ArrayList<>();
		BaritoneAPI.getSettings().allowWalkOnMagmaBlocks.value = true;
		BaritoneAPI.getSettings().failureTimeoutMS.value = Math.min(savedFailureTimeoutMs, BUILD_FAILURE_TIMEOUT_MS);
	}

	private void restoreAllowBreak() {
		if (savedAllowBreak != null) {
			BaritoneAPI.getSettings().allowBreak.value = savedAllowBreak;
			savedAllowBreak = null;
		}
		if (savedAllowBreakAnyway != null) {
			BaritoneAPI.getSettings().allowBreakAnyway.value = new ArrayList<>(savedAllowBreakAnyway);
			savedAllowBreakAnyway = null;
		}
		if (savedAllowWalkOnMagmaBlocks != null) {
			BaritoneAPI.getSettings().allowWalkOnMagmaBlocks.value = savedAllowWalkOnMagmaBlocks;
			savedAllowWalkOnMagmaBlocks = null;
		}
		if (savedFailureTimeoutMs != null) {
			BaritoneAPI.getSettings().failureTimeoutMS.value = savedFailureTimeoutMs;
			savedFailureTimeoutMs = null;
		}
	}

	public Outcome tick(
			IBaritone baritone,
			NavigationService navigation,
			int toolDurabilityThreshold,
			List<Block> protectedBlocks
	) {
		if (!active || suspended || footprint == null) {
			return Outcome.RUNNING;
		}
		// Keep Baritone pathfinding strictly non-destructive every tick and allow traversing magma/bubble tanks.
		BaritoneAPI.getSettings().allowBreak.value = false;
		BaritoneAPI.getSettings().allowWalkOnMagmaBlocks.value = true;
		if (!BaritoneAPI.getSettings().allowBreakAnyway.value.isEmpty()) {
			BaritoneAPI.getSettings().allowBreakAnyway.value = new ArrayList<>();
		}
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		Level level = mc.level;
		Level schematicWorld = bridge.getSchematicWorld();
		if (player == null || level == null || mc.gameMode == null) {
			return Outcome.RUNNING;
		}
		SchematicSignPlacer.closeSignEditScreenIfOpen(mc);
		if (bubbleEscape.isActive()) {
			bridge.setPrintMode(false);
			if (bubbleEscape.tick(baritone, navigation, mc, player, level, bubbleEscapeMode())) {
				statusDetail = bubbleEscape.statusText();
				return Outcome.RUNNING;
			}
			moveGoalDispatched = false;
			waterPlacer.onMovementInterrupted();
		} else if (bubbleEscape.observeAndMaybeTrigger(baritone, navigation, mc, player, level, currentY, bubbleEscapeMode())) {
			bridge.setPrintMode(false);
			statusDetail = bubbleEscape.statusText();
			return Outcome.RUNNING;
		}
		if (resourceController.isActive()) {
			bridge.setPrintMode(false);
			SchematicResourceController.StepOutcome step = resourceController.tick(baritone, navigation);
			if (step == SchematicResourceController.StepOutcome.RUNNING) {
				statusDetail = resourceController.statusDetail();
				return Outcome.RUNNING;
			}
			resupplyCooldownUntil = System.currentTimeMillis()
					+ (step == SchematicResourceController.StepOutcome.DONE_WITH_PROGRESS ? 4_000L : 15_000L);
			if (step == SchematicResourceController.StepOutcome.DONE_WITH_PROGRESS && !waterPlacer.isActive()) {
				layerInitialized = false;
			}
			return Outcome.RUNNING;
		}
		if (schematicWorld == null) {
			if (++schematicWaitTicks <= SCHEMATIC_LOAD_WAIT_TICKS) {
				statusDetail = "正在等待投影世界重载...";
				return Outcome.RUNNING;
			}
			statusDetail = "投影世界不可用，已暂停（确认投影加载后短按 F9 继续）";
			chat("[Perimeter] " + statusDetail);
			pause(baritone, navigation);
			return Outcome.PAUSED_MISSING_MATERIALS;
		}

		// Step 0: Handle unloaded chunk approach if previously queued (before layer init or scanning).
		if (unloadedChunkTarget != null) {
			if (level.isLoaded(unloadedChunkTarget)) {
				unloadedChunkTarget = null;
				moveTicks = 0;
				stopMovement(baritone, navigation);
			} else if (++moveTicks > MOVE_TIMEOUT_TICKS) {
				unloadedChunkTarget = null;
				moveTicks = 0;
				stopMovement(baritone, navigation);
				statusDetail = "投影区域区块未加载且暂时无法靠近，已暂停（靠近投影后短按 F9 继续）";
				chat("[Perimeter] " + statusDetail);
				pause(baritone, navigation);
				return Outcome.PAUSED_MISSING_MATERIALS;
			} else {
				stepMoveTo(baritone, navigation, player, level, Vec3.atCenterOf(unloadedChunkTarget), unloadedChunkTarget);
				statusDetail = "第 Y=" + currentY + " 层 | 正在靠近未加载区块 " + unloadedChunkTarget.toShortString();
				return Outcome.RUNNING;
			}
		}

		// Step 1: Ensure current layer is selected, displayed in Litematica, and materials tallied.
		if (!layerInitialized) {
			int probeY = Math.max(footprint.minY(), Math.min(footprint.maxY(), currentY));
			BlockPos unloadedWorld = findNearestUnloadedPos(level, probeY, player.blockPosition());
			if (unloadedWorld != null) {
				if (++schematicWaitTicks <= 40) {
					statusDetail = "正在等待投影区域世界区块加载...";
					return Outcome.RUNNING;
				}
				unloadedChunkTarget = unloadedWorld;
				moveTicks = 0;
				schematicWaitTicks = 0;
				return Outcome.RUNNING;
			}

			BlockPos unloadedSchematic = findNearestUnloadedPos(schematicWorld, probeY, player.blockPosition());
			if (unloadedSchematic != null) {
				if (++schematicWaitTicks <= SCHEMATIC_LOAD_WAIT_TICKS) {
					statusDetail = "正在等待 Litematica 投影区块加载...";
					return Outcome.RUNNING;
				}
				statusDetail = "等待投影区块加载超时，已暂停（确认投影显示后短按 F9 继续）";
				chat("[Perimeter] " + statusDetail);
				pause(baritone, navigation);
				return Outcome.PAUSED_MISSING_MATERIALS;
			}

			int nextY = SchematicLayerPlanner.nextNonEmptyLayerY(
					currentY, footprint.maxY(), y -> layerHasSchematicBlocks(schematicWorld, y));
			if (nextY == Integer.MIN_VALUE) {
				// After death/respawn, Litematica briefly exposes empty SchematicWorld chunks before repopulating them.
				// Never declare COMPLETE when we haven't seen even a single schematic block since start()!
				if (!sawAnySchematicBlock) {
					if (++schematicWaitTicks <= SCHEMATIC_LOAD_WAIT_TICKS) {
						statusDetail = "重生/重载后正在等待投影方块同步...";
						return Outcome.RUNNING;
					}
					statusDetail = "未读取到投影方块（可能刚重生未回到投影附近或投影未加载），已暂停，请确认投影显示后短按 F9 继续";
					chat("[Perimeter] " + statusDetail);
					pause(baritone, navigation);
					return Outcome.PAUSED_MISSING_MATERIALS;
				}
				statusDetail = "全部投影层已放置完成 Y[" + footprint.minY() + ".." + footprint.maxY() + "]";
				chat("[Perimeter] 投影「" + footprint.placementName() + "」逐层放置完成！");
				stop(baritone, navigation);
				return Outcome.COMPLETE;
			}
			sawAnySchematicBlock = true;
			schematicWaitTicks = 0;
			currentY = nextY;
			if (!bridge.applySingleLayer(currentY)) {
				statusDetail = "切换 Litematica 单层显示失败（Y=" + currentY + "）";
				stop(baritone, navigation);
				return Outcome.ERROR;
			}
			layerSkipped.clear();
			cooldownUntil.clear();
			failCounts.clear();
			lastAnchorPos = null;
			unloadedChunkTarget = null;
			clearActiveTarget(baritone, navigation);

			List<SchematicLayerPlanner.CellTask> layerTasks = scanLayer(level, schematicWorld, currentY);
			Map<String, Integer> invCounts = countInventoryItems(player);
			SchematicLayerPlanner.MaterialSummary summary = SchematicLayerPlanner.summarizeMaterials(
					layerTasks, invCounts, wrongBlockPolicy, clearExtraBlocks, layerSkipped);
			SchematicWaterPlacer.WaterSummary waterSummary = SchematicWaterPlacer.previewLayer(
					level, schematicWorld, footprint, currentY, layerSkipped, optimizeWater);
			waterPlacer.reset();
			layerTotalTargets = summary.missingBlockCells() + summary.wrongBlockCells() + summary.extraBlockCells();
			layerPlacedCount = 0;
			layerInitialized = true;

			announceLayerMaterials(currentY, summary, waterSummary);
			boolean missingWaterBucket = waterSummary.hasWork()
					&& !player.getAbilities().instabuild
					&& !Level.NETHER.equals(level.dimension())
					&& invCounts.getOrDefault("minecraft:water_bucket", 0) <= 0;
			if (SchematicLayerPlanner.shouldPauseBeforeLayer(summary, missingMaterialPolicy)
					|| (missingMaterialPolicy == SchematicLayerPlanner.MissingMaterialPolicy.PAUSE_IMMEDIATELY && missingWaterBucket)) {
				if (tryStartResupply(baritone, navigation, level, player, summary.missing(),
						missingWaterBucket ? 2 : 0, toolDurabilityThreshold)) {
					return Outcome.RUNNING;
				}
				String blockMissing = summary.hasShortage()
						? SchematicLayerPlanner.formatItemSummary(summary.missing(), 6)
						: "";
				lastMissingSummary = missingWaterBucket
						? (blockMissing.isEmpty() ? "water_bucket x1" : blockMissing + "、water_bucket x1")
						: blockMissing;
				statusDetail = "第 Y=" + currentY + " 层缺料暂停：" + lastMissingSummary;
				pause(baritone, navigation);
				return Outcome.PAUSED_MISSING_MATERIALS;
			}
		}

		long now = System.currentTimeMillis();

		// Step 2.5: Execute Phases 2-4 (waterlogged blocks, optimized water seeds + auto-refill, propagation verify).
		if (waterPlacer.isActive()) {
			bridge.setPrintMode(false);
			SchematicWaterPlacer.StepResult step = waterPlacer.tick(
					baritone, mc, player, level, schematicWorld, footprint, layerSkipped,
					navigation != null && navigation.canUseCreativeFlight(),
					() -> navigation != null && navigation.isWalking(),
					(hover, groundGoal) -> stepMoveToGoal(baritone, navigation, hover, groundGoal),
					() -> stopMovement(baritone, navigation));
			if (step == SchematicWaterPlacer.StepResult.RUNNING) {
				statusDetail = waterPlacer.statusText();
				return Outcome.RUNNING;
			}
			if (step == SchematicWaterPlacer.StepResult.MISSING_BUCKET) {
				if (tryStartResupply(baritone, navigation, level, player, Map.of(), 2, toolDurabilityThreshold)) {
					return Outcome.RUNNING;
				}
				lastMissingSummary = "water_bucket x1";
				statusDetail = "第 Y=" + currentY + " 层缺少水桶，已暂停（生存带 2 个水桶可就地无限水续桶）";
				chat("[Perimeter] " + statusDetail + "（补齐水桶后短按 F9 继续）");
				pause(baritone, navigation);
				return Outcome.PAUSED_MISSING_MATERIALS;
			}
			// Water phases finished for this layer!
			advanceToNextLayer();
			return Outcome.RUNNING;
		}

		// Step 3: Check active target completion or timeout.
		if (activeTask != null && activePos != null) {
			SchematicLayerPlanner.CellKind liveKind = classifyCell(level, schematicWorld, activePos);
			if (liveKind == SchematicLayerPlanner.CellKind.MATCHED
					|| (activeTask.kind() == SchematicLayerPlanner.CellKind.EXTRA_BLOCK && liveKind != SchematicLayerPlanner.CellKind.EXTRA_BLOCK)) {
				layerPlacedCount++;
				lastAnchorPos = activePos;
				failCounts.remove(activeTask.key());
				cooldownUntil.remove(activeTask.key());
				clearActiveTarget(baritone, navigation);
			} else if (activeBreaking && liveKind == SchematicLayerPlanner.CellKind.MISSING) {
				// Wrong block was broken! Switch immediately to placing if we hold the replacement item;
				// otherwise clear target so normal scan/resupply handles pickup or chest restock cleanly.
				stopBreaking(mc);
				activeBreaking = false;
				breakTicks = 0;
				waitTicks = 0;
				SchematicLayerPlanner.CellTask nextTask = new SchematicLayerPlanner.CellTask(
						activeTask.key(), activeTask.x(), activeTask.y(), activeTask.z(),
						SchematicLayerPlanner.CellKind.MISSING,
						activeTask.itemId(), activeTask.itemCount(),
						hasSolidNeighbor(level, activePos), activeTask.attachment());
				if (SchematicLayerPlanner.hasMaterialForTask(nextTask, countInventoryItems(player))) {
					activeTask = nextTask;
				} else {
					clearActiveTarget(baritone, navigation);
				}
			} else if (!activeBreaking
					&& liveKind == SchematicLayerPlanner.CellKind.WRONG_BLOCK
					&& wrongBlockPolicy == SchematicLayerPlanner.WrongBlockPolicy.BREAK_AND_PLACE
					&& waitTicks >= 16) {
				// If a wrong block/orientation appeared while waiting for placement and has not been resolved
				// by a second-step interaction (e.g. double slab / cycle / strip) within 16 ticks, break and retry immediately.
				bridge.setPrintMode(false);
				activeBreaking = true;
				breakTicks = 0;
				breakingStarted = false;
				waitTicks = 0;
			}
		}

		// Step 4: Select next target if idle (first check proactive food/tool/scaffold resupply if resource points exist).
		if (activeTask == null) {
			if (tryProactiveSupportResupply(baritone, navigation, level, player, toolDurabilityThreshold)) {
				return Outcome.RUNNING;
			}
			List<SchematicLayerPlanner.CellTask> layerTasks = scanLayer(level, schematicWorld, currentY);
			Map<String, Integer> invCounts = countInventoryItems(player);
			SchematicLayerPlanner.CellTask picked = SchematicLayerPlanner.selectNextTask(
					layerTasks,
					player.getBlockX(), player.getBlockY(), player.getBlockZ(),
					lastAnchorPos != null ? lastAnchorPos.getX() : null,
					lastAnchorPos != null ? lastAnchorPos.getZ() : null,
					wrongBlockPolicy, clearExtraBlocks,
					layerSkipped, cooldownUntil, now, invCounts);
			if (picked == null) {
				return handleNoImmediateTask(baritone, navigation, level, schematicWorld, player, layerTasks, invCounts, toolDurabilityThreshold);
			}
			activeTask = picked;
			activePos = new BlockPos(picked.x(), picked.y(), picked.z());
			activeBreaking = picked.kind() == SchematicLayerPlanner.CellKind.WRONG_BLOCK
					|| picked.kind() == SchematicLayerPlanner.CellKind.EXTRA_BLOCK;
			moveTicks = 0;
			waitTicks = 0;
			breakTicks = 0;
			breakingStarted = false;
		}

		// Step 5: Execute active target (break wrong block OR move into printer range & yield to printer).
		statusDetail = formatRunningStatus();
		if (activeBreaking) {
			return tickBreakWrongBlock(baritone, navigation, mc, player, level, toolDurabilityThreshold, protectedBlocks, now);
		}
		return tickPlaceWithPrinter(baritone, navigation, mc, player, level, schematicWorld, now);
	}

	private Outcome handleNoImmediateTask(
			IBaritone baritone,
			NavigationService navigation,
			Level level,
			Level schematicWorld,
			LocalPlayer player,
			List<SchematicLayerPlanner.CellTask> layerTasks,
			Map<String, Integer> invCounts,
			int toolDurabilityThreshold
	) {
		BlockPos unloaded = findNearestUnloadedPos(level, currentY, baritone.getPlayerContext().playerFeet());
		if (unloaded != null) {
			unloadedChunkTarget = unloaded;
			moveTicks = 0;
			return Outcome.RUNNING;
		}
		if (SchematicLayerPlanner.isLayerComplete(layerTasks, wrongBlockPolicy, clearExtraBlocks, layerSkipped)) {
			bridge.setPrintMode(false);
			if (waterPlacer.beginWaterPhases(level, schematicWorld, footprint, currentY, layerSkipped, optimizeWater)) {
				statusDetail = waterPlacer.statusText();
				return Outcome.RUNNING;
			}
			advanceToNextLayer();
			return Outcome.RUNNING;
		}
		if (SchematicLayerPlanner.hasOnlyMaterialBlockedTasks(
				layerTasks, wrongBlockPolicy, clearExtraBlocks, layerSkipped, invCounts)) {
			SchematicLayerPlanner.MaterialSummary summary = SchematicLayerPlanner.summarizeMaterials(
					layerTasks, invCounts, wrongBlockPolicy, clearExtraBlocks, layerSkipped);
			if (tryStartResupply(baritone, navigation, level, player, summary.missing(), 0, toolDurabilityThreshold)) {
				return Outcome.RUNNING;
			}
			lastMissingSummary = SchematicLayerPlanner.formatItemSummary(summary.missing(), 6);
			statusDetail = "第 Y=" + currentY + " 层缺少材料，已暂停：" + lastMissingSummary;
			chat("[Perimeter] " + statusDetail + "（补齐材料后短按 F9 继续）");
			pause(baritone, navigation);
			return Outcome.PAUSED_MISSING_MATERIALS;
		}
		if (!cooldownUntil.isEmpty()) {
			cooldownUntil.clear();
			return Outcome.RUNNING;
		}
		// Remaining tasks could not be resolved; advance cleanly after logging.
		chat("[Perimeter] 第 Y=" + currentY + " 层剩余目标暂时无法继续，推进至下一层。");
		currentY++;
		layerInitialized = false;
		return Outcome.RUNNING;
	}

	private boolean tryStartResupply(
			IBaritone baritone,
			NavigationService navigation,
			Level level,
			LocalPlayer player,
			Map<String, Integer> missingItems,
			int needWaterBuckets,
			int toolDurabilityThreshold
	) {
		boolean hasResourceChests = activeConfig != null && activeConfig.schematicResourcePoints != null
				&& !activeConfig.schematicResourcePoints.isEmpty();
		boolean hasCarriedShulkers = SchematicResourceCache.countCarriedShulkerBoxes(player) > 0;
		if (!hasResourceChests && !hasCarriedShulkers) {
			return false;
		}
		if (player.getAbilities().instabuild) {
			return false;
		}
		long now = System.currentTimeMillis();
		if (now < resupplyCooldownUntil) {
			return false;
		}
		int needFood = computeFoodDeficit(player);
		int needScaffold = computeScaffoldDeficit(player, navigation);
		boolean needTool = needsHealthyTool(player, toolDurabilityThreshold);
		clearActiveTarget(baritone, navigation);
		bridge.setPrintMode(false);
		boolean started = resourceController.startResupply(
				activeConfig, level, player,
				missingItems, needWaterBuckets, needFood, needScaffold, needTool, toolDurabilityThreshold);
		if (started) {
			statusDetail = resourceController.statusDetail();
		}
		return started;
	}

	private boolean tryProactiveSupportResupply(
			IBaritone baritone,
			NavigationService navigation,
			Level level,
			LocalPlayer player,
			int toolDurabilityThreshold
	) {
		boolean hasResourceChests = activeConfig != null && activeConfig.schematicResourcePoints != null
				&& !activeConfig.schematicResourcePoints.isEmpty();
		boolean hasCarriedShulkers = SchematicResourceCache.countCarriedShulkerBoxes(player) > 0;
		if ((!hasResourceChests && !hasCarriedShulkers) || player.getAbilities().instabuild) {
			return false;
		}
		if (System.currentTimeMillis() < resupplyCooldownUntil) {
			return false;
		}
		int needFood = computeFoodDeficit(player);
		int needScaffold = computeScaffoldDeficit(player, navigation);
		boolean needTool = needsHealthyTool(player, toolDurabilityThreshold);
		if (needFood <= 0 && needScaffold <= 0 && !needTool) {
			return false;
		}
		clearActiveTarget(baritone, navigation);
		bridge.setPrintMode(false);
		boolean started = resourceController.startResupply(
				activeConfig, level, player,
				Map.of(), 0, needFood, needScaffold, needTool, toolDurabilityThreshold);
		if (started) {
			statusDetail = resourceController.statusDetail();
		} else {
			resupplyCooldownUntil = System.currentTimeMillis() + 20_000L;
		}
		return started;
	}

	private int computeFoodDeficit(LocalPlayer player) {
		if (activeConfig == null || !activeConfig.functions.eat || activeConfig.foods == null || activeConfig.foods.isEmpty()) {
			return 0;
		}
		int have = 0;
		Inventory inv = player.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
			if (activeConfig.foods.contains(id)) have += s.getCount();
		}
		if (have > activeConfig.advanced.foodResupplyTrigger) {
			return 0;
		}
		return Math.max(0, activeConfig.advanced.foodResupplyTarget - have);
	}

	private int computeScaffoldDeficit(LocalPlayer player, NavigationService navigation) {
		if (activeConfig == null || (navigation != null && navigation.canUseCreativeFlight())) {
			return 0;
		}
		List<String> scaffolds = (activeConfig.scaffoldBlocks != null && !activeConfig.scaffoldBlocks.isEmpty())
				? activeConfig.scaffoldBlocks
				: List.of();
		if (scaffolds.isEmpty()) {
			return 0;
		}
		int have = 0;
		Inventory inv = player.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
			if (scaffolds.contains(id)) have += s.getCount();
		}
		return have < 16 ? (64 - have) : 0;
	}

	private boolean needsHealthyTool(LocalPlayer player, int durabilityThreshold) {
		if (wrongBlockPolicy != SchematicLayerPlanner.WrongBlockPolicy.BREAK_AND_PLACE) {
			return false;
		}
		Inventory inv = player.getInventory();
		for (int i = 0; i < 36; i++) {
			if (SchematicResourceCache.isHealthyTool(inv.getItem(i), durabilityThreshold)) {
				return false;
			}
		}
		return true;
	}

	private Outcome tickBreakWrongBlock(
			IBaritone baritone,
			NavigationService navigation,
			Minecraft mc,
			LocalPlayer player,
			Level level,
			int toolDurabilityThreshold,
			List<Block> protectedBlocks,
			long now
	) {
		// Always disable printer while breaking a wrong block so it cannot steal hotbar/interaction.
		bridge.setPrintMode(false);
		BlockState state = level.getBlockState(activePos);
		if (state.isAir() || state.getDestroySpeed(level, activePos) < 0.0F
				|| (protectedBlocks != null && protectedBlocks.contains(state.getBlock()))) {
			layerSkipped.add(activeTask.key());
			clearActiveTarget(baritone, navigation);
			return Outcome.RUNNING;
		}
		Vec3 center = Vec3.atCenterOf(activePos);
		double dist = player.getEyePosition().distanceTo(center);
		if (dist > BREAK_REACH) {
			stopBreaking(mc);
			if (!stepMoveTowardTarget(baritone, navigation, player, level, activePos, BREAK_REACH - 0.35)) {
				recordFailure(baritone, navigation, now, "破坏点无法靠近");
			}
			return Outcome.RUNNING;
		}
		stopMovement(baritone, navigation);
		moveGoalDispatched = false;
		moveGraceTicks = 0;
		if (++breakTicks > BREAK_TIMEOUT_TICKS) {
			recordFailure(baritone, navigation, now, "破坏超时");
			return Outcome.RUNNING;
		}
		BackpackToolManager.ensureBestToolOnHotbar(baritone.getPlayerContext(), state);
		selectBestHealthyTool(player, state, toolDurabilityThreshold);
		if (isProtectedLowDurability(player.getMainHandItem(), toolDurabilityThreshold)) {
			stopBreaking(mc);
			recordFailure(baritone, navigation, now, "工具耐久受保护");
			return Outcome.RUNNING;
		}
		aimAt(player, center);
		player.yHeadRot = player.getYRot();
		if (mc.getConnection() != null) {
			mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
					player.getYRot(), player.getXRot(), player.onGround(), player.horizontalCollision));
		}
		Direction face = closestFace(activePos, player.getEyePosition());
		if (!breakingStarted) {
			mc.gameMode.startDestroyBlock(activePos, face);
			breakingStarted = true;
		} else {
			mc.gameMode.continueDestroyBlock(activePos, face);
		}
		player.swing(InteractionHand.MAIN_HAND);
		return Outcome.RUNNING;
	}

	private Outcome tickPlaceWithPrinter(
			IBaritone baritone,
			NavigationService navigation,
			Minecraft mc,
			LocalPlayer player,
			Level level,
			Level schematicWorld,
			long now
	) {
		double printRange = Math.min(bridge.getPrintingRange() - 0.45, 4.0);
		BlockState targetState = schematicWorld.getBlockState(activePos);

		// Place all signs directly with exact yaw/face sync instead of delegating to litematica-printer
		// (whose RotatingBlockGuide forces yaw=0 on WallSignBlocks and fails when Configs.ROTATE is off).
		if (SchematicSignPlacer.isSignTarget(targetState)) {
			bridge.setPrintMode(false);
			SchematicSignPlacer.PlacementPlan plan = SchematicSignPlacer.planPlacement(level, activePos, targetState);
			if (plan == null) {
				recordFailure(baritone, navigation, now, "告示牌支撑方块未就绪");
				return Outcome.RUNNING;
			}
			boolean blockingTarget = isPlayerBlockingTarget(player, activePos);
			double dist = player.getEyePosition().distanceTo(plan.hitPoint());
			if (dist > 4.2 || blockingTarget) {
				signPlacer.reset(baritone);
				if (!stepMoveTowardTarget(baritone, navigation, player, level, activePos, 3.8)) {
					recordFailure(baritone, navigation, now, "告示牌放置点无法靠近");
				}
				return Outcome.RUNNING;
			}
			stopMovement(baritone, navigation);
			moveGoalDispatched = false;
			moveGraceTicks = 0;
			if (!signPlacer.tickPlace(baritone, mc, player, level, activePos, targetState)) {
				recordFailure(baritone, navigation, now, "告示牌无法放置");
				return Outcome.RUNNING;
			}
			if (++waitTicks > PRINT_WAIT_TICKS) {
				recordFailure(baritone, navigation, now, "告示牌放置未生效");
			}
			return Outcome.RUNNING;
		}

		// Guard against litematica-printer snatching nearby unplaced signs with wrong rotation while moving/printing.
		BlockPos nearbySign = SchematicSignPlacer.findNearbyPlaceableSign(
				level, schematicWorld, player, currentY, printRange + 1.5,
				layerSkipped, bridge.isReplaceFluidsEnabled(), wrongBlockPolicy);
		if (nearbySign != null) {
			bridge.setPrintMode(false);
			stopMovement(baritone, navigation);
			moveGoalDispatched = false;
			moveGraceTicks = 0;
			BlockState signState = schematicWorld.getBlockState(nearbySign);
			if (signPlacer.tickPlace(baritone, mc, player, level, nearbySign, signState)) {
				return Outcome.RUNNING;
			}
		}

		signPlacer.reset(baritone);
		Vec3 targetCenter = Vec3.atCenterOf(activePos);
		double dist = player.getEyePosition().distanceTo(targetCenter);
		boolean blockingTarget = isPlayerBlockingTarget(player, activePos);

		if (dist > printRange || blockingTarget) {
			// Keep printer on while moving unless blocking target, so nearby layer blocks print along the way.
			bridge.setPrintMode(!blockingTarget);
			if (!stepMoveTowardTarget(baritone, navigation, player, level, activePos, printRange)) {
				recordFailure(baritone, navigation, now, "放置点无法靠近");
			}
			return Outcome.RUNNING;
		}

		// In printing range & clear of target hitbox: stop moving and let litematica-printer place.
		stopMovement(baritone, navigation);
		moveGoalDispatched = false;
		moveGraceTicks = 0;
		bridge.setPrintMode(true);
		if (++waitTicks > PRINT_WAIT_TICKS) {
			recordFailure(baritone, navigation, now, "打印机未在时限内放下");
		}
		return Outcome.RUNNING;
	}

	private void recordFailure(IBaritone baritone, NavigationService navigation, long now, String reason) {
		if (activeTask != null) {
			long key = activeTask.key();
			int fails = failCounts.merge(key, 1, Integer::sum);
			if (fails >= SchematicLayerPlanner.MAX_TARGET_FAILURES) {
				layerSkipped.add(key);
				cooldownUntil.remove(key);
				chat("[Perimeter] 跳过第 Y=" + currentY + " 层无法处理方块 "
						+ activePos.toShortString() + "（" + reason + "）");
			} else {
				cooldownUntil.put(key, now + SchematicLayerPlanner.TARGET_COOLDOWN_MS);
			}
		}
		clearActiveTarget(baritone, navigation);
	}

	private void clearActiveTarget(IBaritone baritone, NavigationService navigation) {
		stopBreaking(Minecraft.getInstance());
		stopMovement(baritone, navigation);
		signPlacer.reset(baritone);
		activeTask = null;
		activePos = null;
		activeBreaking = false;
		moveGoalDispatched = false;
		usingFarApproachGoal = false;
		moveGraceTicks = 0;
		moveTicks = 0;
		noProgressMoveTicks = 0;
		bestMoveDistSq = Double.MAX_VALUE;
		waitTicks = 0;
		breakTicks = 0;
	}

	private void stopBreaking(Minecraft mc) {
		if (breakingStarted && mc != null && mc.gameMode != null) {
			mc.gameMode.stopDestroyBlock();
		}
		breakingStarted = false;
	}

	private void stopMovement(IBaritone baritone, NavigationService navigation) {
		if (baritone != null) {
			flightNav.stop(baritone);
		}
		if (navigation != null) {
			navigation.stopWalking();
		}
	}

	private boolean stepMoveTowardTarget(
			IBaritone baritone,
			NavigationService navigation,
			LocalPlayer player,
			Level level,
			BlockPos target,
			double maxReach
	) {
		double distSq = player.position().distanceToSqr(Vec3.atCenterOf(target));
		double dist = Math.sqrt(distSq);
		if (!moveGoalDispatched) {
			bestMoveDistSq = distSq;
			noProgressMoveTicks = 0;
			moveTicks = 0;
			moveHardLimitTicks = Math.max(200, (int) (dist * 14.0) + 140);
		} else {
			if (distSq < bestMoveDistSq - 0.75 * 0.75) {
				bestMoveDistSq = distSq;
				noProgressMoveTicks = 0;
			} else if (++noProgressMoveTicks > 90) {
				moveGoalDispatched = false;
				usingFarApproachGoal = false;
				moveGraceTicks = 0;
				stopMovement(baritone, navigation);
				if (BubbleColumnEscapeController.isPlayerInDownwardBubble(level, player)
						&& bubbleEscape.tryStartEscape(baritone, navigation, Minecraft.getInstance(), player, level, bubbleEscapeMode())) {
					return true;
				}
				return false;
			}
		}
		if (++moveTicks > moveHardLimitTicks) {
			moveGoalDispatched = false;
			usingFarApproachGoal = false;
			moveGraceTicks = 0;
			stopMovement(baritone, navigation);
			if (BubbleColumnEscapeController.isPlayerInDownwardBubble(level, player)
					&& bubbleEscape.tryStartEscape(baritone, navigation, Minecraft.getInstance(), player, level, bubbleEscapeMode())) {
				return true;
			}
			return false;
		}
		boolean canFly = navigation != null && navigation.canUseCreativeFlight();
		if (canFly) {
			BlockPos stand = findStandPosition(level, target, player.position(), true, maxReach);
			stepMoveToGoal(baritone, navigation,
					new Vec3(stand.getX() + 0.5, stand.getY() + 0.1, stand.getZ() + 0.5),
					new GoalBlock(stand));
			moveGoalDispatched = true;
			return true;
		}
		if (!moveGoalDispatched) {
			if (dist > 10.0) {
				usingFarApproachGoal = true;
				stepMoveToGoal(baritone, navigation, Vec3.atCenterOf(target).add(0, 1.5, 0), new GoalNear(target, 3));
			} else {
				usingFarApproachGoal = false;
				BlockPos excludeFeet = isPlayerBlockingTarget(player, target) ? player.blockPosition() : null;
				Goal groundGoal = SchematicWaterPlacer.buildGroundVantageGoal(level, target, excludeFeet, player.position(), maxReach);
				stepMoveToGoal(baritone, navigation, Vec3.atCenterOf(target).add(0, 1.5, 0), groundGoal);
			}
			moveGoalDispatched = true;
			moveGraceTicks = 0;
			return true;
		}
		if (++moveGraceTicks <= 2) {
			return true;
		}
		if (navigation == null || !navigation.isWalking()) {
			if (usingFarApproachGoal && dist <= 10.0) {
				usingFarApproachGoal = false;
				BlockPos excludeFeet = isPlayerBlockingTarget(player, target) ? player.blockPosition() : null;
				Goal groundGoal = SchematicWaterPlacer.buildGroundVantageGoal(level, target, excludeFeet, player.position(), maxReach);
				stepMoveToGoal(baritone, navigation, Vec3.atCenterOf(target).add(0, 1.5, 0), groundGoal);
				moveGraceTicks = 0;
				return true;
			}
			moveGoalDispatched = false;
			usingFarApproachGoal = false;
			moveGraceTicks = 0;
			stopMovement(baritone, navigation);
			if (BubbleColumnEscapeController.isPlayerInDownwardBubble(level, player)
					&& bubbleEscape.tryStartEscape(baritone, navigation, Minecraft.getInstance(), player, level, bubbleEscapeMode())) {
				return true;
			}
			return false;
		}
		return true;
	}

	private String bubbleEscapeMode() {
		return activeConfig != null && activeConfig.bubbleColumnEscapeMode != null
				? activeConfig.bubbleColumnEscapeMode
				: "auto";
	}

	private void stepMoveToGoal(
			IBaritone baritone,
			NavigationService navigation,
			Vec3 exactHover,
			Goal groundGoal
	) {
		if (navigation != null && navigation.canUseCreativeFlight()) {
			if (!flightNav.isActive() || flightNav.target() == null
					|| flightNav.target().distanceToSqr(exactHover) > 1.0) {
				flightNav.flyTo(exactHover, 0.75, 0.65, false);
			}
			flightNav.tick(baritone);
		} else if (navigation != null && groundGoal != null) {
			if (!navigation.isWalking()) {
				navigation.walk(groundGoal);
			}
		}
	}

	private void stepMoveTo(
			IBaritone baritone,
			NavigationService navigation,
			LocalPlayer player,
			Level level,
			Vec3 exactHover,
			BlockPos blockStand
	) {
		if (navigation != null && navigation.canUseCreativeFlight()) {
			if (!flightNav.isActive() || flightNav.target() == null
					|| flightNav.target().distanceToSqr(exactHover) > 1.0) {
				flightNav.flyTo(exactHover, 0.75, 0.65, false);
			}
			flightNav.tick(baritone);
		} else if (navigation != null) {
			if (!navigation.isWalking()) {
				navigation.walk(blockStand.distSqr(player.blockPosition()) > 9.0
						? new GoalNear(blockStand, 1)
						: new GoalBlock(blockStand));
			}
		}
	}

	private void advanceToNextLayer() {
		chat("[Perimeter] 第 Y=" + currentY + " 层放置完成"
				+ (layerSkipped.isEmpty() ? "" : "（跳过 " + layerSkipped.size() + " 格）")
				+ (currentY < footprint.maxY() ? "，切换至下一层..." : "。"));
		currentY++;
		layerInitialized = false;
		waterPlacer.reset();
	}

	private boolean layerHasSchematicBlocks(Level schematicWorld, int y) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
			for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
				cursor.set(x, y, z);
				BlockState s = schematicWorld.getBlockState(cursor);
				if (!s.isAir() && !SchematicWaterPlacer.isSchematicFlowingFluid(s)) {
					return true;
				}
			}
		}
		return false;
	}

	private List<SchematicLayerPlanner.CellTask> scanLayer(Level level, Level schematicWorld, int y) {
		List<SchematicLayerPlanner.CellTask> tasks = new ArrayList<>();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
			for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
				cursor.set(x, y, z);
				if (!level.isLoaded(cursor)) {
					continue;
				}
				SchematicLayerPlanner.CellKind kind = classifyCell(level, schematicWorld, cursor);
				if (kind == SchematicLayerPlanner.CellKind.MATCHED) {
					continue;
				}
				BlockState targetState = schematicWorld.getBlockState(cursor);
				String itemId = "";
				int itemCount = 0;
				if (!targetState.isAir() && !SchematicWaterPlacer.isSchematicWaterSource(targetState)) {
					List<ItemStack> required = bridge.getRequiredBuildItems(targetState, schematicWorld, cursor);
					if (!required.isEmpty()) {
						ItemStack first = required.getFirst();
						itemId = BuiltInRegistries.ITEM.getKey(first.getItem()).toString();
						for (ItemStack s : required) {
							if (s.getItem() == first.getItem()) {
								itemCount += s.getCount();
							}
						}
					}
				}
				boolean support = hasSolidNeighbor(level, cursor);
				boolean attachment = !targetState.isAir() && targetState.getCollisionShape(schematicWorld, cursor).isEmpty();
				tasks.add(new SchematicLayerPlanner.CellTask(
						cursor.asLong(), x, y, z, kind, itemId, itemCount, support, attachment));
			}
		}
		return tasks;
	}

	private SchematicLayerPlanner.CellKind classifyCell(Level level, Level schematicWorld, BlockPos pos) {
		return classifyCell(level, schematicWorld, pos, bridge.isReplaceFluidsEnabled());
	}

	/**
	 * Single source of truth for classifying a schematic cell against the live world.
	 * Shared by {@link SchematicBuildController} and {@link SchematicLayerMaterialCalculator}
	 * so layer progress and material counts never diverge.
	 */
	static SchematicLayerPlanner.CellKind classifyCell(
			Level level,
			Level schematicWorld,
			BlockPos pos,
			boolean replaceFluids
	) {
		BlockState target = schematicWorld.getBlockState(pos);
		BlockState current = level.getBlockState(pos);
		if (SchematicWaterPlacer.isSchematicFlowingFluid(target)) {
			// Flowing non-source fluids form naturally from sources; never block layer progression.
			return SchematicLayerPlanner.CellKind.MATCHED;
		}
		if (SchematicWaterPlacer.isSchematicWaterSource(target)) {
			// Phase 1 only clears solid wrong blocks occupying a water source cell (under BREAK_AND_PLACE);
			// actual water bucketing happens in Phase 3 after solid containment walls are built.
			if (current.isAir() || current.canBeReplaced() || current.getBlock() instanceof LiquidBlock
					|| SchematicWaterPlacer.isWorldWaterSource(current)) {
				return SchematicLayerPlanner.CellKind.MATCHED;
			}
			return SchematicLayerPlanner.CellKind.WRONG_BLOCK;
		}
		if (target.isAir()) {
			if (current.isAir() || current.canBeReplaced()) {
				return SchematicLayerPlanner.CellKind.MATCHED;
			}
			return SchematicLayerPlanner.CellKind.EXTRA_BLOCK;
		}
		if (statesEquivalent(current, target)) {
			return SchematicLayerPlanner.CellKind.MATCHED;
		}
		if (current.isAir()) {
			return SchematicLayerPlanner.CellKind.MISSING;
		}
		if (current.getBlock() instanceof LiquidBlock) {
			if (replaceFluids || !current.getFluidState().isSource()) {
				return SchematicLayerPlanner.CellKind.MISSING;
			}
			return SchematicLayerPlanner.CellKind.WRONG_BLOCK;
		}
		if (current.canBeReplaced() && current.getBlock() != target.getBlock()) {
			return SchematicLayerPlanner.CellKind.MISSING;
		}
		if (current.getBlock() == target.getBlock() && isPrinterInteractable(current.getBlock())) {
			return SchematicLayerPlanner.CellKind.WRONG_INTERACTABLE;
		}
		return SchematicLayerPlanner.CellKind.WRONG_BLOCK;
	}

	static boolean statesEquivalent(BlockState current, BlockState target) {
		if (current.equals(target)) return true;
		if (current.getBlock() != target.getBlock()) return false;
		Block block = target.getBlock();
		boolean neighborConnected = block instanceof FenceBlock
				|| block instanceof IronBarsBlock
				|| block instanceof WallBlock
				|| block instanceof RedStoneWireBlock;
		// Compare properties except live environment/redstone signal states (e.g., leaf distance,
		// redstone wire power 0..15, powered/triggered flags) and auto-derived neighbor connection shapes
		// so BREAK_AND_PLACE never loops on blocks whose visual connection updates when neighbors appear.
		// For waterlogged=true targets, a matching dry block (waterlogged=false) passes Phase 1
		// so Phase 2 can waterlog it directly with a water bucket instead of breaking/replacing it.
		for (var prop : target.getProperties()) {
			String name = prop.getName();
			if ("distance".equals(name)
					|| "persistent".equals(name)
					|| "powered".equals(name)
					|| "power".equals(name)
					|| "triggered".equals(name)) {
				continue;
			}
			if (block instanceof StairBlock && "shape".equals(name)) {
				continue;
			}
			if (neighborConnected && ("north".equals(name) || "south".equals(name)
					|| "east".equals(name) || "west".equals(name) || "up".equals(name))) {
				continue;
			}
			Object curVal = current.getValue(prop);
			Object tgtVal = target.getValue(prop);
			if ("waterlogged".equals(name) && Boolean.TRUE.equals(tgtVal) && Boolean.FALSE.equals(curVal)) {
				continue;
			}
			if (!curVal.equals(tgtVal)) {
				return false;
			}
		}
		return true;
	}

	private static boolean isPrinterInteractable(Block block) {
		return block instanceof RepeaterBlock
				|| block instanceof ComparatorBlock
				|| block instanceof DiodeBlock
				|| block instanceof LeverBlock
				|| block instanceof NoteBlock
				|| block instanceof TrapDoorBlock
				|| block instanceof FenceGateBlock
				|| block instanceof DoorBlock
				|| block instanceof CampfireBlock;
	}

	private static boolean hasSolidNeighbor(Level level, BlockPos pos) {
		for (Direction dir : Direction.values()) {
			BlockPos n = pos.relative(dir);
			if (!level.isLoaded(n)) continue;
			BlockState s = level.getBlockState(n);
			if (!s.isAir() && !s.canBeReplaced() && !s.getShape(level, n).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private static boolean isPlayerBlockingTarget(LocalPlayer player, BlockPos target) {
		BlockPos feet = player.blockPosition();
		return feet.equals(target) || feet.above().equals(target);
	}

	private BlockPos findStandPosition(Level level, BlockPos target, Vec3 playerPos, boolean flying, double maxReach) {
		BlockPos[] candidates = flying ? new BlockPos[]{
				target.above(2),
				target.north(2).above(1),
				target.south(2).above(1),
				target.east(2).above(1),
				target.west(2).above(1),
				target.north(1).above(1),
				target.south(1).above(1),
				target.east(1).above(1),
				target.west(1).above(1),
				target.north(2),
				target.south(2),
				target.east(2),
				target.west(2),
				target.north(1),
				target.south(1),
				target.east(1),
				target.west(1)
		} : new BlockPos[]{
				target.north(1),
				target.south(1),
				target.east(1),
				target.west(1),
				target.north(2),
				target.south(2),
				target.east(2),
				target.west(2),
				target.north(1).above(1),
				target.south(1).above(1),
				target.east(1).above(1),
				target.west(1).above(1),
				target.north(1).below(1),
				target.south(1).below(1),
				target.east(1).below(1),
				target.west(1).below(1)
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
			if (SchematicWaterPlacer.isDownwardBubbleColumn(feet) || SchematicWaterPlacer.isDownwardBubbleColumn(head)) continue;
			if (!flying && level.getBlockState(c.below()).getCollisionShape(level, c.below()).isEmpty()) continue;
			Vec3 eyeAtC = new Vec3(c.getX() + 0.5, c.getY() + 1.62, c.getZ() + 0.5);
			if (eyeAtC.distanceTo(targetCenter) > maxReach) continue;
			double d = playerPos.distanceToSqr(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
			if (d < bestScore) {
				bestScore = d;
				best = c;
			}
		}
		return best != null ? best : (flying ? target.above(2) : target.north(1));
	}

	private BlockPos findNearestUnloadedPos(Level targetLevel, int y, BlockPos playerFeet) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int minCx = footprint.minX() >> 4;
		int maxCx = footprint.maxX() >> 4;
		int minCz = footprint.minZ() >> 4;
		int maxCz = footprint.maxZ() >> 4;
		for (int cz = minCz; cz <= maxCz; cz++) {
			int rz = Math.max(footprint.minZ(), Math.min(footprint.maxZ(), (cz << 4) + 8));
			for (int cx = minCx; cx <= maxCx; cx++) {
				int rx = Math.max(footprint.minX(), Math.min(footprint.maxX(), (cx << 4) + 8));
				cursor.set(rx, y, rz);
				if (!targetLevel.isLoaded(cursor)) {
					double d = cursor.distSqr(playerFeet);
					if (d < bestDist) {
						bestDist = d;
						best = cursor.immutable();
					}
				}
			}
		}
		return best;
	}

	private static Map<String, Integer> countInventoryItems(LocalPlayer player) {
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

	private static void selectBestHealthyTool(LocalPlayer player, BlockState state, int durabilityThreshold) {
		Inventory inv = player.getInventory();
		int bestSlot = inv.getSelectedSlot();
		float bestSpeed = scoreTool(inv.getItem(bestSlot), state, durabilityThreshold);
		for (int i = 0; i < 9; i++) {
			float speed = scoreTool(inv.getItem(i), state, durabilityThreshold);
			if (speed > bestSpeed + 0.01F) {
				bestSpeed = speed;
				bestSlot = i;
			}
		}
		if (bestSlot != inv.getSelectedSlot()) {
			inv.setSelectedSlot(bestSlot);
		}
	}

	private static boolean isProtectedLowDurability(ItemStack stack, int durabilityThreshold) {
		if (durabilityThreshold < 0 || stack.isEmpty()) return false;
		if (!stack.isDamageableItem() || !stack.getItem().components().has(DataComponents.TOOL)) return false;
		return (stack.getMaxDamage() - stack.getDamageValue()) <= durabilityThreshold;
	}

	private static float scoreTool(ItemStack stack, BlockState state, int durabilityThreshold) {
		if (stack.isEmpty()) return 1.0F;
		if (isProtectedLowDurability(stack, durabilityThreshold)) return -1.0F;
		return stack.getDestroySpeed(state);
	}

	private void announceLayerMaterials(
			int y,
			SchematicLayerPlanner.MaterialSummary summary,
			SchematicWaterPlacer.WaterSummary water
	) {
		int totalLayers = Math.max(1, footprint.maxY() - footprint.minY() + 1);
		int layerNo = y - footprint.minY() + 1;
		String req = SchematicLayerPlanner.formatItemSummary(summary.required(), 6);
		StringBuilder msg = new StringBuilder();
		msg.append("[Perimeter] 第 ").append(layerNo).append("/").append(totalLayers)
				.append(" 层 (Y=").append(y).append(") 统计：待放 ").append(summary.missingBlockCells()).append(" 格");
		if (summary.wrongBlockCells() > 0) {
			msg.append("，待破重放 ").append(summary.wrongBlockCells()).append(" 格");
		}
		if (summary.skippedWrongCells() > 0) {
			msg.append("，跳过错误块 ").append(summary.skippedWrongCells()).append(" 格");
		}
		msg.append(" | 所需方块：").append(req);
		if (water != null && water.hasWork()) {
			msg.append(" | 水体：");
			if (water.waterloggedCount() > 0) {
				msg.append("含水 ").append(water.waterloggedCount()).append(" 格");
				if (water.waterSourceCount() > 0) msg.append("，");
			}
			if (water.waterSourceCount() > 0) {
				msg.append("水源 ").append(water.waterSourceCount()).append(" 格");
				if (optimizeWater && water.savedCount() > 0) {
					msg.append("（优化倒水 ").append(water.plannedSeedCount())
							.append(" 次，省 ").append(water.savedCount())
							.append(" 次；生存带 2 桶可自动续水）");
				} else {
					msg.append("（倒水 ").append(water.plannedSeedCount()).append(" 次）");
				}
			}
		}
		if (summary.hasShortage()) {
			lastMissingSummary = SchematicLayerPlanner.formatItemSummary(summary.missing(), 6);
			msg.append(" | 背包缺料：").append(lastMissingSummary);
		} else {
			lastMissingSummary = "";
		}
		chat(msg.toString());
	}

	private String formatRunningStatus() {
		int totalLayers = Math.max(1, footprint.maxY() - footprint.minY() + 1);
		int layerNo = currentY - footprint.minY() + 1;
		String action = activeBreaking ? "清理错误块" : "打印机放置";
		return "投影第 " + layerNo + "/" + totalLayers + " 层(Y=" + currentY + ") "
				+ action + " " + layerPlacedCount + "/" + Math.max(layerPlacedCount, layerTotalTargets)
				+ (layerSkipped.isEmpty() ? "" : " 跳过:" + layerSkipped.size());
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

	private static Direction closestFace(BlockPos pos, Vec3 eye) {
		double dx = eye.x - (pos.getX() + 0.5);
		double dy = eye.y - (pos.getY() + 0.5);
		double dz = eye.z - (pos.getZ() + 0.5);
		double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
		if (ay >= ax && ay >= az) return dy >= 0 ? Direction.UP : Direction.DOWN;
		if (ax >= az) return dx >= 0 ? Direction.EAST : Direction.WEST;
		return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
	}

	private static void chat(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.gui != null) {
			mc.gui.chatListener().handleSystemMessage(Component.literal(text), false);
		}
	}
}
