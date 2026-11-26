/*
 * This file is part of the Perimeter Digger project, licensed under the
 * GNU General Public License v3.0 or later.
 *
 * Copyright (C) 2026  HackerRouter
 *
 * Perimeter Digger is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Perimeter Digger is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Perimeter Digger. If not, see <https://www.gnu.org/licenses/>.
 */

package hackerrouter.perimeterdigger.client.state;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.process.IAreaMineProcess;
import baritone.api.process.area.AreaMiningLiquidPolicy;
import baritone.api.process.area.AreaMiningOptions;
import baritone.api.process.area.AreaMiningStatus;
import baritone.api.process.area.AreaMiningStrategy;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.HotbarSwapPlanner;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.StuckCellPenalty;
import baritone.api.utils.input.Input;
import baritone.utils.BackpackToolManager;
import hackerrouter.perimeterdigger.client.config.PerimeterConfig;
import hackerrouter.perimeterdigger.PerimeterDigger;
import hackerrouter.perimeterdigger.client.config.FunctionConfig;
import hackerrouter.perimeterdigger.client.config.AdvancedConfig;
import hackerrouter.perimeterdigger.client.config.PositionConfig;
import hackerrouter.perimeterdigger.client.config.UnloadingPointConfig;
import hackerrouter.perimeterdigger.client.translation.LocalizedMessage;
import hackerrouter.perimeterdigger.client.translation.Translations;
import hackerrouter.perimeterdigger.client.build.SchematicBuildController;
import hackerrouter.perimeterdigger.client.build.SchematicResourceController;
import hackerrouter.perimeterdigger.client.flight.CreativeFlightMiner;
import hackerrouter.perimeterdigger.client.flight.CreativeFlightNavigator;
import hackerrouter.perimeterdigger.client.navigation.BubbleColumnEscapeController;
import hackerrouter.perimeterdigger.client.navigation.InteractionPositionFinder;
import hackerrouter.perimeterdigger.client.navigation.NavigationService;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class AutomationController {
	private static final int MONITOR_INTERVAL_TICKS = 10;
	private static final int MINING_PATHING_RECOVERY_LIMIT = 10;
	private static final int EATING_TIMEOUT_TICKS = 100;
	private static final int UNLOAD_POSITIONING_TIMEOUT_TICKS = 100;
	private static final int OFFHAND_INVENTORY_SLOT = 40;
	private static final int OFFHAND_MENU_SLOT = 45;
	private static final int CHEST_MENU_SLOT = 6;
	private static final int STATE_HISTORY_LIMIT = 64;
	private AutomationState state = AutomationState.IDLE;
	private AutomationState stateBeforeEating = AutomationState.IDLE;
	private final Retaliation retaliation = new Retaliation();
	private AutomationState pausedTask;
	private Runnable interruptedMining;
	private boolean deathRespawnPending;
	private final PendingContinuation continuation = new PendingContinuation();
	private Runnable originalTask;
	private PerimeterConfig latestConfig;
	private net.minecraft.client.player.LocalPlayer deadPlayer;
	private LocalizedMessage detail = Translations.DETAIL.message("stopped");
	private Instant changedAt = Instant.now();
	private IBaritone baritone;
	private IAreaMineProcess miningProcess;
	private ConfiguredColumnarArea area;
	private AreaMiningLiquidPolicy liquidPolicy;
	private AreaMiningStrategy miningStrategy = AreaMiningStrategy.GLOBAL_TOP_DOWN;
	private String durabilityRecoveryMode = "repair_portal";
	private List<Block> sealingBlocks = List.of();
	private List<Block> scaffoldBlocks = List.of();
	private List<Block> miningWhitelistBlocks = List.of();
	private List<Block> miningBlacklistBlocks = List.of();
	private Set<Item> allowedFoods = Set.of();
	private Set<Item> unloadingWhitelist = Set.of();
	private PositionConfig consumableSupplyPoint;
	private PositionConfig durabilitySupplyPoint;
	private PositionConfig bedPoint;
	private PositionConfig perimeterPortalOverworld;
	private PositionConfig perimeterPortalNether;
	private PositionConfig repairPortalOverworld;
	private PositionConfig repairPortalNether;
	private PositionConfig furnaceRowStart;
	private PositionConfig furnaceRowEnd;
	private List<UnloadFlow.NamedPoint> unloadingPoints = List.of();
	private BlockPos miningReturnPoint;
	private ResourceKey<Level> miningReturnDimension;
	private boolean returningByElytra;
	private int returnFlightAttempts;
	private BlockPos activeReturnTarget;
	private ReturnWaypoint activeReturnWaypoint;
	private ReturnWaypoint returnPortalWaypoint;
	private ReturnAction returnAction = ReturnAction.NONE;
	private Integer savedElytraMinimumDurability;
	private Integer savedElytraMinFireworksBeforeLanding;
	private Boolean savedRepairAllowBreak;
	private List<Block> savedBlocksToDisallowBreaking;
	private List<Item> savedAcceptableThrowawayItems;
	private final ArrayDeque<InventoryClick> inventoryClicks = new ArrayDeque<>();
	private int tickCounter;
	private AutomationState watchdogState;
	private BlockPos watchdogTarget;
	private Vec3 watchdogPosition;
	private int watchdogStationaryScans;
	private int watchdogRetries;
	private boolean navigationBacktrack;
	private BlockPos navigationBacktrackTarget;
	private int navigationBacktrackTicks;
	private int eatingTicks;
	private boolean eatingPausedCreativeMiner;
	private int eatingCooldownUntilTick;
	private int eatingPreviousSelectedSlot = -1;
	private int eatingSwappedBackpackSlot = -1;
	private int eatingSwappedHotbarSlot = -1;
	private int eatingSyncGraceTicks;
	private Vec3 miningStallPosition;
	private int miningStallStationaryScans;
	private long miningStallMinedBaseline;
	private int miningStallLastTriggerTick;
	private boolean miningStallRecovery;
	private BlockPos miningStallRecoveryTarget;
	private int miningStallRecoveryTicks;
	private Vec3 miningStallRecoveryStartPos;
	private int manualUnstuckTicks;
	private float manualUnstuckYaw;
	private int consecutiveStallCount;
	private final ArrayDeque<BlockPos> recentStallSpots = new ArrayDeque<>();
	private int miningPathingRecoveryAttempts;
	private long miningProgressBaseline = MiningEscapePolicy.initialBaseline();
	private long miningRawBaseline;
	private int miningProgressIdleScans;
	private Vec3 miningProgressLastPos;
	private int miningProgressTransitCredits;
	private int miningEscapeAttempts;
	private static final int AUTO_RESTART_LIMIT = 5;
	private int autoRestarts;
	private long nextAutoRestartAt;
	private boolean autoRestartAllowed;
	private boolean autoRestarting;
	private int stableInventoryScans;
	private int lastInventoryItemCount;
	private boolean miningCompletePending;
	private boolean collectDropsEnabled = true;
	private boolean unloadEnabled = true;
	private boolean eatEnabled = true;
	private boolean durabilityProtectionEnabled = true;
	private boolean durabilityRecoveryEnabled = true;
	private boolean crossDimensionRepairEnabled = true;
	private boolean resupplyEnabled = true;
	private boolean elytraNavigationEnabled = true;
	private boolean sleepEnabled;
	private boolean flightMovementEnabled = true;
	private boolean miningWhitelistEnabled;
	private boolean miningBlacklistEnabled;
	private boolean autoSwapBackpackToolEnabled = true;
	private boolean autoReplaceLowDurabilityToolEnabled = true;
	private boolean autoSwapBackpackFoodEnabled = true;
	private AdvancedConfig advanced = new AdvancedConfig();
	private final ArrayDeque<StateTransition> stateHistory = new ArrayDeque<>();
	private final InteractionPositionFinder interactionPositionFinder = new InteractionPositionFinder(2, 9.0);
	private final NavigationService navigation = new NavigationService();
	private final CreativeFlightMiner creativeMiner = new CreativeFlightMiner();
	private final SchematicBuildController schematicBuilder = new SchematicBuildController();
	private final SchematicResourceController resourceScanner = new SchematicResourceController();
	private final BubbleColumnEscapeController bubbleEscape = new BubbleColumnEscapeController();
	private final StallRecoveryTracker stallTracker = new StallRecoveryTracker();
	private final StallRecoveryTracker navOscillationTracker = new StallRecoveryTracker();
	private final SupplyFlow supply = new SupplyFlow();
	private final UnloadFlow unload = new UnloadFlow();
	private final RepairFlow repair = new RepairFlow();
	private final SleepFlow sleep = new SleepFlow();

	public List<String> start(PerimeterConfig config) {
		if (config != null && "build_schematic".equalsIgnoreCase(config.workMode)) {
			return startSchematicBuild(config);
		}
		PerimeterConfig saved = copyConfig(config);
		transition(AutomationState.VALIDATING, Translations.DETAIL.message("validating_mining"));
		List<String> missing = validate(config);
		if (!missing.isEmpty()) {
			transition(AutomationState.ERROR, Translations.DETAIL.message("invalid_configuration", String.join(", ", missing)));
			return missing;
		}
		stopProcesses();
		restoreAllowPlace();
		restoreElytraMinimumDurability();
		restoreElytraFireworkReserve();
		restoreRepairRestrictions();
		resetMiningStall();
		resetNavigationWatchdog();
		stallTracker.reset();
		StuckCellPenalty.clear();
		consecutiveStallCount = 0;
		recentStallSpots.clear();
		eatingCooldownUntilTick = 0;
		this.latestConfig = saved;
		originalTask = () -> start(this.latestConfig != null ? this.latestConfig : saved);
		this.baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
		this.miningProcess = baritone.getAreaMineProcess();
		navigation.bind(baritone);
		loadMiningConfiguration(config);
		this.miningCompletePending = false;
		this.unload.debugOnly = false;
		this.supply.debugOnly = false;
		this.repair.debugOnly = false;
		this.sleep.debugOnly = false;
		boolean fromAutoRestart = this.autoRestarting;
		this.autoRestarting = false;
		this.stableInventoryScans = 0;
		this.miningPathingRecoveryAttempts = 0;
		if (!fromAutoRestart) {
			// A manual start resets the retry budget; an automatic one must keep counting so a
			// genuinely finished area cannot restart forever.
			this.autoRestarts = 0;
			this.nextAutoRestartAt = 0L;
			this.miningEscapeAttempts = 0;
			this.miningProgressIdleScans = 0;
			this.miningProgressBaseline = MiningEscapePolicy.initialBaseline();
			this.miningRawBaseline = 0L;
		}
		this.autoRestartAllowed = true;
		this.inventoryClicks.clear();
		applyMiningSettings();
		ResourceCheck resources = inspectResources();
		boolean usableTool = false;
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = Minecraft.getInstance().player.getInventory().getItem(slot);
			if (stack.getItem().components().has(DataComponents.TOOL) && (!stack.isDamageableItem() || remainingDurability(stack) > durabilityThreshold(stack))) usableTool = true;
		}
		boolean missingElytra = usesElytraNavigation() && resources.repairRequired()
				&& BuiltInRegistries.ITEM.getKey(Items.ELYTRA).toString().equals(resources.repairItem());
		if (!usableTool || missingElytra) {
			transition(AutomationState.ERROR, Translations.DETAIL.message("missing_usable_tool"));
			return List.of("usable_tool");
		}
		if (resources.replacement() != null) performReplacement(resources.replacement());
		startMiningCycle();
		return List.of();
	}

	private static PerimeterConfig copyConfig(PerimeterConfig config) {
		PerimeterConfig copy = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(config), PerimeterConfig.class);
		if (copy == null) copy = new PerimeterConfig();
		copy.normalize();
		return copy;
	}

	private void loadMiningConfiguration(PerimeterConfig config) {
		this.area = new ConfiguredColumnarArea(config.detectedArea, config.diggingMinY, config.diggingMaxY);
		this.liquidPolicy = policy(config.liquidPolicy);
		this.miningStrategy = strategy(config.miningStrategy);
		this.durabilityRecoveryMode = config.durabilityRecoveryMode;
		this.sealingBlocks = sealingBlocks(config.sealingBlocks);
		this.scaffoldBlocks = sealingBlocks(config.scaffoldBlocks);
		this.miningWhitelistBlocks = miningWhitelistBlocks(config.miningWhitelist);
		this.miningBlacklistBlocks = miningBlacklistBlocks(config.miningBlacklist);
		this.allowedFoods = foods(config.foods);
		this.unloadingWhitelist = items(config.unloadingWhitelist);
		this.consumableSupplyPoint = config.consumableSupplyPoint;
		this.durabilitySupplyPoint = config.durabilitySupplyPoint;
		this.bedPoint = config.bedPoint;
		updateFunctions(config.functions);
		updateAdvanced(config.advanced);
		loadRepairConfiguration(config);
		this.unloadingPoints = config.unloadingPoints.entrySet().stream()
				.map(entry -> new UnloadFlow.NamedPoint(entry.getKey(), entry.getValue()))
				.toList();
	}

	private void applyMiningSettings() {
		BaritoneAPI.getSettings().itemSaver.value = durabilityProtectionEnabled;
		BaritoneAPI.getSettings().itemSaverThreshold.value = advanced.toolDurabilityThreshold;
		BaritoneAPI.getSettings().autoSwapBackpackTools.value = autoSwapBackpackToolEnabled;
		BaritoneAPI.getSettings().autoReplaceLowDurabilityTools.value = autoReplaceLowDurabilityToolEnabled;
		syncHotbarProtectedItemsSetting();
		BaritoneAPI.getSettings().elytraAutoSwap.value = true;
		BaritoneAPI.getSettings().elytraMinimumDurability.value = advanced.elytraDurabilityThreshold;
		BaritoneAPI.getSettings().elytraAutoJump.value = true;
		BaritoneAPI.getSettings().allowSprint.value = true;
		BaritoneAPI.getSettings().allowParkour.value = true;
		syncProtectedBlocksSetting();
		syncScaffoldBlocksSetting();
	}

	private void syncHotbarProtectedItemsSetting() {
		List<Item> protectedItems = new ArrayList<>(allowedFoods);
		for (Block block : sealingBlocks) {
			Item item = block.asItem();
			if (item != Items.AIR && !protectedItems.contains(item)) {
				protectedItems.add(item);
			}
		}
		BaritoneAPI.getSettings().hotbarProtectedItems.value = protectedItems;
	}

	public List<String> debugStage5(PerimeterConfig config) {
		PerimeterConfig saved = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(config), PerimeterConfig.class);
		originalTask = () -> debugStage5(saved);
		transition(AutomationState.VALIDATING, Translations.DETAIL.message("validating_unload_debug"));
		if (config.unloadingPoints.isEmpty()) {
			transition(AutomationState.ERROR, Translations.DETAIL.message("invalid_configuration", "unloading_points"));
			return List.of("unloading_points");
		}
		stopProcesses();
		this.baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
		this.miningProcess = baritone.getAreaMineProcess();
		navigation.bind(baritone);
		this.unloadingWhitelist = items(config.unloadingWhitelist);
		this.unloadingPoints = config.unloadingPoints.entrySet().stream()
				.map(entry -> new UnloadFlow.NamedPoint(entry.getKey(), entry.getValue()))
				.toList();
		this.inventoryClicks.clear();
		this.miningCompletePending = false;
		this.unload.debugOnly = true;
		updateFunctions(config.functions);
		updateAdvanced(config.advanced);
		BaritoneAPI.getSettings().elytraAutoSwap.value = true;
		BaritoneAPI.getSettings().elytraMinimumDurability.value = advanced.elytraDurabilityThreshold;
		BaritoneAPI.getSettings().elytraAutoJump.value = true;
		BaritoneAPI.getSettings().allowSprint.value = true;
		BaritoneAPI.getSettings().allowParkour.value = true;
		requestUnload();
		return state == AutomationState.ERROR ? List.of("unload_start") : List.of();
	}

	public List<String> debugStage6(PerimeterConfig config, boolean durability) {
		PerimeterConfig saved = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(config), PerimeterConfig.class);
		originalTask = () -> debugStage6(saved, durability);
		SupplyFlow.Kind kind = durability ? SupplyFlow.Kind.DURABILITY : SupplyFlow.Kind.CONSUMABLES;
		PositionConfig point = durability ? config.durabilitySupplyPoint : config.consumableSupplyPoint;
		transition(AutomationState.VALIDATING, Translations.DETAIL.message("validating_supply_debug"));
		if (point == null) {
			transition(AutomationState.ERROR, Translations.DETAIL.message("requested_supply_missing"));
			return List.of(durability ? "durability_supply_point" : "consumable_supply_point");
		}
		stopProcesses();
		this.baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
		this.miningProcess = baritone.getAreaMineProcess();
		navigation.bind(baritone);
		this.allowedFoods = foods(config.foods);
		this.consumableSupplyPoint = config.consumableSupplyPoint;
		this.durabilitySupplyPoint = config.durabilitySupplyPoint;
		updateFunctions(config.functions);
		updateAdvanced(config.advanced);
		this.inventoryClicks.clear();
		this.supply.debugOnly = true;
		BaritoneAPI.getSettings().elytraAutoSwap.value = true;
		BaritoneAPI.getSettings().elytraMinimumDurability.value = advanced.elytraDurabilityThreshold;
		BaritoneAPI.getSettings().elytraAutoJump.value = true;
		BaritoneAPI.getSettings().allowSprint.value = true;
		BaritoneAPI.getSettings().allowParkour.value = true;
		if (kind == SupplyFlow.Kind.DURABILITY && captureDurabilitySupplyPlan().targetHealthyCounts().isEmpty()) {
			transition(AutomationState.ERROR, Translations.DETAIL.message("no_debug_supply_target"));
			return List.of("low_durability_item");
		}
		beginSupply(kind, true);
		return state == AutomationState.ERROR ? List.of("supply_start") : List.of();
	}

	public List<String> debugStage7(PerimeterConfig config) {
		PerimeterConfig saved = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(config), PerimeterConfig.class);
		originalTask = () -> debugStage7(saved);
		transition(AutomationState.VALIDATING, Translations.DETAIL.message("validating_repair_debug"));
		List<String> missing = validateRepairConfiguration(config);
		if (!missing.isEmpty()) {
			transition(AutomationState.ERROR, Translations.DETAIL.message("invalid_repair_configuration", String.join(", ", missing)));
			return missing;
		}
		stopProcesses();
		bindBaritone();
		loadRepairConfiguration(config);
		this.allowedFoods = foods(config.foods);
		updateFunctions(config.functions);
		updateAdvanced(config.advanced);
		this.repair.debugOnly = true;
		BaritoneAPI.getSettings().elytraAutoSwap.value = true;
		BaritoneAPI.getSettings().elytraMinimumDurability.value = advanced.elytraDurabilityThreshold;
		BaritoneAPI.getSettings().elytraAutoJump.value = true;
		BaritoneAPI.getSettings().allowSprint.value = true;
		BaritoneAPI.getSettings().allowParkour.value = true;
		beginRepairFlow(true);
		if (state == AutomationState.ERROR) throw new IllegalStateException(detail.component().getString());
		return List.of();
	}

	public List<String> debugSleep(PerimeterConfig config) {
		PerimeterConfig saved = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(config), PerimeterConfig.class);
		originalTask = () -> debugSleep(saved);
		transition(AutomationState.VALIDATING, Translations.DETAIL.message("validating_sleep_debug"));
		if (config.bedPoint == null) {
			transition(AutomationState.ERROR, Translations.DETAIL.message("bed_point_missing"));
			return List.of("bed_point");
		}
		stopProcesses();
		bindBaritone();
		this.bedPoint = config.bedPoint;
		updateFunctions(config.functions);
		updateAdvanced(config.advanced);
		this.sleep.debugOnly = true;
		BaritoneAPI.getSettings().elytraAutoSwap.value = true;
		BaritoneAPI.getSettings().elytraMinimumDurability.value = advanced.elytraDurabilityThreshold;
		BaritoneAPI.getSettings().elytraAutoJump.value = true;
		BaritoneAPI.getSettings().allowSprint.value = true;
		BaritoneAPI.getSettings().allowParkour.value = true;
		if (!isBedTime()) {
			transition(AutomationState.ERROR, Translations.DETAIL.message("sleep_debug_wrong_time"));
			return List.of("night_time");
		}
		beginSleepFlow(true);
		return state == AutomationState.ERROR ? List.of("sleep_start") : List.of();
	}

	public void tick() {
		if (baritone == null || baritone.getPlayerContext().player() == null) return;
		var player = Minecraft.getInstance().player;
		if (player == null) return;
		if (player.isDeadOrDying()) {
			if (!deathRespawnPending && RetaliationPolicy.active(state)) {
				retaliation.cancel();
				suspendForRetaliation();
				Runnable task = originalTask;
				stopProcesses();
				inventoryClicks.clear();
				stateBeforeEating = AutomationState.IDLE;
				unload.reset();
				clearSupplyContext();
				restoreRepairNavigationSettings();
				restoreRepairRestrictions();
				clearRepairContext();
				clearSleepContext();
				resetMiningStall();
				deadPlayer = player;
				deathRespawnPending = true;
				continuation.schedule(task);
				player.respawn();
			}
			return;
		}
		if (deathRespawnPending) {
			if (player == deadPlayer) return;
			bindBaritone(); // both same-dimension and dimension-changing respawns replace LocalPlayer
			if (baritone.getPlayerContext().player() != player) return;
			deathRespawnPending = false;
			deadPlayer = null;
			continuation.run();
			return;
		}
		if (tickAutoRestart()) return;
		if (!isRetaliationEligible()) {
			retaliation.cancel();
		} else {
			boolean finishingCriticalMeal = state == AutomationState.EATING
					&& player.isUsingItem()
					&& player.getHealth() <= advanced.healthEatingThreshold;
			if (!finishingCriticalMeal && retaliation.tick(this::suspendForRetaliation, this::restoreAfterRetaliation, navigation)) {
				navigation.tickCreativeFlight();
				return;
			}
		}
		if (resourceScanner.isActive()) {
			BaritoneAPI.getSettings().allowBreak.value = false;
			SchematicResourceController.StepOutcome scanOut = resourceScanner.tick(baritone, navigation);
			if (scanOut == SchematicResourceController.StepOutcome.RUNNING) {
				detail = LocalizedMessage.literal(resourceScanner.statusDetail());
				return;
			}
			restoreAllowPlace();
			detail = LocalizedMessage.literal("资源点扫描完成");
			return;
		}
		if (!RetaliationPolicy.active(state)) return;
		navigation.tickCreativeFlight();
		executeInventoryClick();
		if (miningStallRecovery) {
			tickMiningStallRecovery();
			return;
		}
		if (navigationBacktrack) {
			tickNavigationBacktrack();
			return;
		}
		if (state == AutomationState.EATING) {
			tickEating();
			return;
		}
		if (state == AutomationState.SLEEPING) {
			tickSleeping();
			return;
		}
		if (state == AutomationState.UNLOADING) {
			tickUnloading();
			return;
		}
		if (state == AutomationState.POSITIONING_FOR_UNLOAD) {
			tickPositioningForUnload();
			return;
		}
		if (state == AutomationState.RESUPPLYING || state == AutomationState.SWAPPING_DURABILITY_AT_SUPPLY) {
			tickSupplyInteraction();
			return;
		}
		if (state == AutomationState.REPAIRING) {
			tickRepairing();
			return;
		}
		if (state == AutomationState.ENTERING_PERIMETER_PORTAL || state == AutomationState.ENTERING_REPAIR_PORTAL) {
			tickEnteringRepairPortal();
			return;
		}
		if (state == AutomationState.BUILDING && inventoryClicks.isEmpty()) {
			if (!baritone.getPlayerContext().player().isFallFlying() && !isInsideNetherPortal()
					&& shouldEat() && eatEnabled && beginEating()) {
				return;
			}
			SchematicBuildController.Outcome buildOutcome = schematicBuilder.tick(
					baritone, navigation, effectiveToolDurabilityThreshold(), activeProtectedBlocks());
			switch (buildOutcome) {
				case RUNNING -> synchronize(AutomationState.BUILDING, LocalizedMessage.literal(schematicBuilder.statusDetail()));
				case PAUSED_MISSING_MATERIALS -> {
					pausedTask = AutomationState.BUILDING;
					autoRestartAllowed = false;
					transition(AutomationState.PAUSED, LocalizedMessage.literal(schematicBuilder.statusDetail()));
				}
				case COMPLETE -> {
					autoRestartAllowed = false;
					transition(AutomationState.COMPLETE, LocalizedMessage.literal(schematicBuilder.statusDetail()));
				}
				case ERROR -> {
					autoRestartAllowed = false;
					transition(AutomationState.ERROR, LocalizedMessage.literal(schematicBuilder.statusDetail()));
				}
			}
			return;
		}
		if (state == AutomationState.MINING && creativeMiner.isActive() && inventoryClicks.isEmpty()) {
			CreativeFlightMiner.Outcome outcome = creativeMiner.tick(baritone, effectiveToolDurabilityThreshold());
			switch (outcome) {
				case RUNNING -> {}
				case BLOCK_LIMIT_REACHED -> { beginDropCollection(false); return; }
				case COMPLETE -> { beginDropCollection(true); return; }
				case NO_SEALING_BLOCKS -> {
					synchronize(AutomationState.PAUSED, Translations.DETAIL.message("mining_paused",
							"no_sealing_blocks", progress(creativeMiner.status()).component()));
					return;
				}
				case LOST_FLIGHT -> { startMiningCycle(); return; }
			}
		}
		if (++tickCounter % MONITOR_INTERVAL_TICKS != 0) return;
		recordSafeBreadcrumb();
		if (watchdogNavigationTarget() != null && !baritone.getPlayerContext().player().isFallFlying() && !isInsideNetherPortal()
				&& shouldEat()
				&& eatEnabled && beginEating()) return;
		if (tickNavigationWatchdog()) return;
		if (state == AutomationState.NAVIGATING_TO_UNLOAD) {
			tickNavigateToUnload();
			return;
		}
		if (state == AutomationState.APPROACHING_UNLOAD) {
			tickApproachingUnload();
			return;
		}
		if (state == AutomationState.RETURNING_TO_MINE) {
			tickReturningToMine();
			return;
		}
		if (state == AutomationState.NAVIGATING_TO_RESUPPLY || state == AutomationState.NAVIGATING_TO_DURABILITY_SUPPLY) {
			tickSupplyNavigation();
			return;
		}
		if (state == AutomationState.NAVIGATING_TO_BED) {
			tickBedNavigation();
			return;
		}
		if (state == AutomationState.NAVIGATING_TO_PERIMETER_PORTAL || state == AutomationState.NAVIGATING_TO_REPAIR_PORTAL
				|| state == AutomationState.NAVIGATING_TO_REPAIR_MACHINE) {
			tickRepairNavigation();
			return;
		}
		if (state == AutomationState.CLEARING_REPAIR_PORTAL) {
			tickClearRepairPortal();
			return;
		}
		if (state == AutomationState.IDLE || state == AutomationState.ERROR || state == AutomationState.COMPLETE || state == AutomationState.PAUSED
				) return;
		if (!inventoryClicks.isEmpty()) return;
		if (state == AutomationState.MINING && !creativeMiner.isActive() && advanced.miningStallDetectionEnabled && tickMiningStallDetection()) return;
		ResourceCheck resources = inspectResources();
		if (sleepEnabled && bedPoint != null && isBedTime()) {
			beginSleepFlow(false);
			return;
		}
		if (eatEnabled && shouldEat() && beginEating()) return;
		if (resources.replacement() != null) {
			performReplacement(resources.replacement());
			return;
		}
		if (hasConfiguredUnload() && (state == AutomationState.MINING || state == AutomationState.COLLECTING_DROPS) && emptyInventorySlots() == 0) {
			requestUnload();
			return;
		}
		if (resupplyEnabled && consumableSupplyPoint != null
				&& (configuredFoodCount() <= advanced.foodResupplyTrigger
				|| elytraNavigationEnabled && !navigation.canUseCreativeFlight() && fireworkCount() <= advanced.fireworkResupplyTrigger)) {
			beginSupply(SupplyFlow.Kind.CONSUMABLES, false);
			return;
		}
		if (durabilityRecoveryEnabled && resources.repairRequired()) {
			if (durabilityRecoveryMode.equals("supply_point") && durabilitySupplyPoint != null) {
				beginSupply(SupplyFlow.Kind.DURABILITY, false);
				return;
			} else if (durabilityRecoveryMode.equals("repair_portal")) {
				if (hasUsableRepairConfiguration()) {
					beginRepairFlow(false);
					return;
				} else if (durabilitySupplyPoint != null) {
					beginSupply(SupplyFlow.Kind.DURABILITY, false);
					return;
				}
			}
		}
		if (durabilityRecoveryEnabled && resources.repairRequired() && !hasUsableTool()) {
			// Repair/supply is not configured here, so nothing can restore durability: stop with an actionable reason.
			stopProcesses();
			transition(AutomationState.PAUSED, Translations.DETAIL.message("tool_worn_out"));
			return;
		}
		if (resupplyEnabled && consumableSupplyPoint != null
				&& baritone.getPlayerContext().player().getFoodData().getFoodLevel() <= advanced.foodLevelThreshold) {
			beginSupply(SupplyFlow.Kind.CONSUMABLES, false);
			return;
		}
		if (state == AutomationState.MINING) synchronizeMining();
		else if (state == AutomationState.COLLECTING_DROPS) tickDropCollection();
	}

	public void stop() {
		if (baritone != null && baritone.getPlayerContext().player() != null
				&& !(baritone.getPlayerContext().player().containerMenu instanceof InventoryMenu)) {
			baritone.getPlayerContext().player().closeContainer();
		}
		stopProcesses();
		restoreProtectedBlocksSetting();
		restoreScaffoldBlocksSetting();
		baritone = null;
		miningProcess = null;
		navigation.unbind();
		area = null;
		miningReturnPoint = null;
		miningReturnDimension = null;
		activeReturnTarget = null;
		activeReturnWaypoint = null;
		returnPortalWaypoint = null;
		inventoryClicks.clear();
		resetNavigationWatchdog();
		resetMiningStall();
		stallTracker.reset();
		StuckCellPenalty.clear();
		consecutiveStallCount = 0;
		recentStallSpots.clear();
		unload.reset();
		clearSleepContext();
		clearSupplyContext();
		clearRepairContext();
		autoRestartAllowed = false;
		originalTask = null;
		transition(AutomationState.IDLE, Translations.DETAIL.message("stopped"));
	}

	public void clearCachedState() {
		stop();
		stateBeforeEating = AutomationState.IDLE;
		unload.reset();
		stableInventoryScans = 0;
		lastInventoryItemCount = 0;
		miningCompletePending = false;
		tickCounter = 0;
		transition(AutomationState.IDLE, Translations.DETAIL.message("cache_cleared"));
	}

	public void updateFunctions(FunctionConfig functions) {
		FunctionConfig value = functions == null ? new FunctionConfig() : functions;
		List<Block> previousProtected = activeProtectedBlocks();
		boolean previousBlacklistEnabled = miningBlacklistEnabled;
		collectDropsEnabled = value.collectDrops;
		unloadEnabled = value.unload;
		eatEnabled = value.eat;
		durabilityProtectionEnabled = value.durabilityProtection;
		durabilityRecoveryEnabled = value.durabilityRecovery;
		crossDimensionRepairEnabled = value.crossDimensionRepair;
		resupplyEnabled = value.resupply;
		elytraNavigationEnabled = value.elytraNavigation;
		sleepEnabled = value.sleep;
		flightMovementEnabled = value.flightMovement;
		miningWhitelistEnabled = value.miningWhitelist;
		miningBlacklistEnabled = value.miningBlacklist;
		autoSwapBackpackToolEnabled = value.autoSwapBackpackTool;
		autoReplaceLowDurabilityToolEnabled = value.autoReplaceLowDurabilityTool;
		autoSwapBackpackFoodEnabled = value.autoSwapBackpackFood;
		navigation.setFlightMovementEnabled(flightMovementEnabled);
		if (baritone != null) {
			BaritoneAPI.getSettings().itemSaver.value = durabilityProtectionEnabled;
			BaritoneAPI.getSettings().autoSwapBackpackTools.value = autoSwapBackpackToolEnabled;
			BaritoneAPI.getSettings().autoReplaceLowDurabilityTools.value = autoReplaceLowDurabilityToolEnabled;
			syncHotbarProtectedItemsSetting();
			syncProtectedBlocksSetting();
		}
		if (baritone != null && state == AutomationState.MINING && !miningStallRecovery && area != null) {
			boolean filterChanged = !previousProtected.equals(activeProtectedBlocks())
					|| previousBlacklistEnabled != miningBlacklistEnabled;
			if (!flightMovementEnabled && creativeMiner.isActive()) {
				creativeMiner.stop(baritone);
				resumeMiningCycle(false);
			} else if (flightMovementEnabled && !creativeMiner.isActive() && navigation.canUseCreativeFlight()) {
				resumeMiningCycle(false);
			} else if (filterChanged) {
				resumeMiningCycle(false);
			}
		}
	}

	public void updateMiningWhitelist(List<String> blockIds) {
		List<Block> previousProtected = activeProtectedBlocks();
		this.miningWhitelistBlocks = miningWhitelistBlocks(blockIds);
		if (baritone != null) {
			syncProtectedBlocksSetting();
			if (state == AutomationState.MINING && !miningStallRecovery && area != null
					&& !previousProtected.equals(activeProtectedBlocks())) {
				resumeMiningCycle(false);
			}
		}
	}

	public void updateMiningBlacklist(List<String> blockIds) {
		List<Block> previousTargets = activeTargetBlocks();
		this.miningBlacklistBlocks = miningBlacklistBlocks(blockIds);
		if (baritone != null && state == AutomationState.MINING && !miningStallRecovery && area != null
				&& miningBlacklistEnabled && !previousTargets.equals(activeTargetBlocks())) {
			resumeMiningCycle(false);
		}
	}

	public void updateMiningStrategy(String strategyName) {
		AreaMiningStrategy next = strategy(strategyName);
		if (this.miningStrategy == next) return;
		this.miningStrategy = next;
		if (baritone != null && state == AutomationState.MINING && !miningStallRecovery && area != null) {
			resumeMiningCycle(false);
		}
	}

	public void syncConfig(PerimeterConfig config) {
		if (config == null) return;
		this.latestConfig = copyConfig(config);
		ConfiguredColumnarArea previousArea = this.area;
		AreaMiningLiquidPolicy previousLiquid = this.liquidPolicy;
		AreaMiningStrategy previousStrategy = this.miningStrategy;
		List<Block> previousSealing = this.sealingBlocks;
		List<Block> previousProtected = activeProtectedBlocks();
		boolean previousBlacklistEnabled = this.miningBlacklistEnabled;
		List<Block> previousTargets = activeTargetBlocks();
		boolean previousFlight = this.flightMovementEnabled;

		if (config.detectedArea != null && config.detectedArea.scanlines != null && !config.detectedArea.scanlines.isEmpty()
				&& config.diggingMinY != null && config.diggingMaxY != null && config.diggingMinY <= config.diggingMaxY) {
			this.area = new ConfiguredColumnarArea(config.detectedArea, config.diggingMinY, config.diggingMaxY);
		}
		if (List.of("avoid", "replace", "seal_boundary").contains(config.liquidPolicy)) {
			this.liquidPolicy = policy(config.liquidPolicy);
		}
		if (config.miningStrategy == null || List.of("global_top_down", "local_top_down", "chunk_top_down").contains(config.miningStrategy)) {
			this.miningStrategy = strategy(config.miningStrategy);
		}
		if (List.of("repair_portal", "supply_point").contains(config.durabilityRecoveryMode)) {
			this.durabilityRecoveryMode = config.durabilityRecoveryMode;
		}
		this.sealingBlocks = sealingBlocks(config.sealingBlocks);
		this.scaffoldBlocks = sealingBlocks(config.scaffoldBlocks);
		this.miningWhitelistBlocks = miningWhitelistBlocks(config.miningWhitelist);
		this.miningBlacklistBlocks = miningBlacklistBlocks(config.miningBlacklist);
		this.allowedFoods = foods(config.foods);
		this.unloadingWhitelist = items(config.unloadingWhitelist);
		this.consumableSupplyPoint = config.consumableSupplyPoint;
		this.durabilitySupplyPoint = config.durabilitySupplyPoint;
		this.bedPoint = config.bedPoint;
		loadRepairConfiguration(config);
		this.unloadingPoints = config.unloadingPoints.entrySet().stream()
				.map(entry -> new UnloadFlow.NamedPoint(entry.getKey(), entry.getValue()))
				.toList();

		FunctionConfig fn = config.functions == null ? new FunctionConfig() : config.functions;
		collectDropsEnabled = fn.collectDrops;
		unloadEnabled = fn.unload;
		eatEnabled = fn.eat;
		durabilityProtectionEnabled = fn.durabilityProtection;
		durabilityRecoveryEnabled = fn.durabilityRecovery;
		crossDimensionRepairEnabled = fn.crossDimensionRepair;
		resupplyEnabled = fn.resupply;
		elytraNavigationEnabled = fn.elytraNavigation;
		sleepEnabled = fn.sleep;
		flightMovementEnabled = fn.flightMovement;
		miningWhitelistEnabled = fn.miningWhitelist;
		miningBlacklistEnabled = fn.miningBlacklist;
		autoSwapBackpackToolEnabled = fn.autoSwapBackpackTool;
		autoReplaceLowDurabilityToolEnabled = fn.autoReplaceLowDurabilityTool;
		autoSwapBackpackFoodEnabled = fn.autoSwapBackpackFood;
		navigation.setFlightMovementEnabled(flightMovementEnabled);
		schematicBuilder.syncConfig(config);
		updateAdvanced(config.advanced);
		if (baritone != null) {
			BaritoneAPI.getSettings().itemSaver.value = durabilityProtectionEnabled;
			BaritoneAPI.getSettings().autoSwapBackpackTools.value = autoSwapBackpackToolEnabled;
			BaritoneAPI.getSettings().autoReplaceLowDurabilityTools.value = autoReplaceLowDurabilityToolEnabled;
			syncHotbarProtectedItemsSetting();
			syncProtectedBlocksSetting();
		}
		if (baritone != null && state == AutomationState.MINING && !miningStallRecovery && area != null) {
			boolean areaChanged = previousArea == null
					|| previousArea.minX() != area.minX() || previousArea.maxX() != area.maxX()
					|| previousArea.minY() != area.minY() || previousArea.maxY() != area.maxY()
					|| previousArea.minZ() != area.minZ() || previousArea.maxZ() != area.maxZ()
					|| previousArea.estimatedBlockCount() != area.estimatedBlockCount();
			boolean miningChanged = areaChanged
					|| previousLiquid != liquidPolicy
					|| previousStrategy != miningStrategy
					|| !previousSealing.equals(sealingBlocks)
					|| !previousProtected.equals(activeProtectedBlocks())
					|| previousBlacklistEnabled != miningBlacklistEnabled
					|| !previousTargets.equals(activeTargetBlocks())
					|| previousFlight != flightMovementEnabled;
			if (!flightMovementEnabled && creativeMiner.isActive()) {
				creativeMiner.stop(baritone);
				resumeMiningCycle(true);
			} else if (flightMovementEnabled && !creativeMiner.isActive() && navigation.canUseCreativeFlight()) {
				resumeMiningCycle(true);
			} else if (miningChanged) {
				resumeMiningCycle(true);
			}
		}
	}

	public void updateAdvanced(AdvancedConfig advanced) {
		this.advanced = advanced == null ? new AdvancedConfig() : advanced;
		if (baritone != null) {
			BaritoneAPI.getSettings().itemSaverThreshold.value = this.advanced.toolDurabilityThreshold;
			BaritoneAPI.getSettings().elytraMinimumDurability.value = this.advanced.elytraDurabilityThreshold;
		}
	}

	public void pause() {
		if (deathRespawnPending) {
			continuation.cancel();
			deathRespawnPending = false;
			pausedTask = null;
			transition(AutomationState.PAUSED, Translations.DETAIL.message("mining_paused_manually"));
			return;
		}
		if (!RetaliationPolicy.active(state)) throw new IllegalStateException("No active task can be paused.");
		retaliation.cancel();
		autoRestartAllowed = false;
		pausedTask = state;
		suspendForInterruption(false);
		transition(AutomationState.PAUSED, Translations.DETAIL.message("mining_paused_manually"));
	}

	public void resume(PerimeterConfig config) {
		if (state != AutomationState.PAUSED) throw new IllegalStateException("No paused task can be resumed.");
		if (Minecraft.getInstance().player == null || Minecraft.getInstance().player.isDeadOrDying() || deathRespawnPending)
			throw new IllegalStateException("Wait for respawn before resuming.");
		if (pausedTask != null) {
			AutomationState targetState = pausedTask;
			if (targetState == AutomationState.BUILDING) {
				pausedTask = null;
				this.latestConfig = copyConfig(config);
				this.allowedFoods = foods(config.foods);
				this.miningWhitelistBlocks = miningWhitelistBlocks(config.miningWhitelist);
				updateFunctions(config.functions);
				updateAdvanced(config.advanced);
				if (schematicBuilder.resume(baritone, navigation, config)) {
					transition(AutomationState.BUILDING, LocalizedMessage.literal("已恢复投影逐层放置"));
					return;
				}
				String err = schematicBuilder.statusDetail();
				transition(AutomationState.ERROR, LocalizedMessage.literal(err == null || err.isBlank() ? "无法恢复投影放置" : err));
				return;
			}
			if (targetState == AutomationState.MINING) {
				List<String> invalid = validate(config);
				if (!invalid.isEmpty()) throw new IllegalArgumentException("Cannot resume. Missing or invalid: " + String.join(", ", invalid) + ".");
				pausedTask = null;
				interruptedMining = null;
				miningPathingRecoveryAttempts = 0;
				autoRestarts = 0;
				nextAutoRestartAt = 0L;
				autoRestartAllowed = true;
				this.latestConfig = copyConfig(config);
				loadMiningConfiguration(config);
				applyMiningSettings();
				resumeMiningCycle(true);
				return;
			}
			syncConfig(config);
			autoRestarts = 0;
			nextAutoRestartAt = 0L;
			autoRestartAllowed = true;
			state = targetState;
			pausedTask = null;
			restoreAfterRetaliation();
			return;
		}
		if (miningProcess == null) throw new IllegalStateException("Restart this debug task after death.");
		List<String> invalid = validate(config);
		if (!invalid.isEmpty()) throw new IllegalArgumentException("Cannot resume. Missing or invalid: " + String.join(", ", invalid) + ".");
		miningPathingRecoveryAttempts = 0;
		autoRestarts = 0;
		nextAutoRestartAt = 0L;
		autoRestartAllowed = true;
		this.latestConfig = copyConfig(config);
		loadMiningConfiguration(config);
		applyMiningSettings();
		AreaMiningStatus status = miningProcess.getAreaMiningStatus();
		if (status.state() == AreaMiningStatus.State.PAUSED && status.pauseReason() == AreaMiningStatus.PauseReason.BLOCK_LIMIT_REACHED) beginDropCollection(false);
		else resumeMiningCycle(true);
	}

	public java.util.Map<String, Object> miningProgress() {
		if (creativeMiner.isActive()) {
			AreaMiningStatus s = creativeMiner.status();
			return java.util.Map.of("available", true, "state", s.state().name(), "pauseReason", s.pauseReason().name(), "estimatedTotalBlocks", s.estimatedTotalBlocks(), "knownRemainingBlocks", s.knownRemainingBlocks(), "batchMinedBlocks", s.minedBlocks(), "batchDistinctMinedBlocks", s.distinctMinedBlocks(), "batchBlockLimit", s.blockLimit());
		}
		if (miningProcess == null) return java.util.Map.of("available", false);
		AreaMiningStatus s = miningProcess.getAreaMiningStatus();
		return java.util.Map.of("available", true, "state", s.state().name(), "pauseReason", s.pauseReason().name(), "estimatedTotalBlocks", s.estimatedTotalBlocks(), "knownRemainingBlocks", s.knownRemainingBlocks(), "batchMinedBlocks", s.minedBlocks(), "batchDistinctMinedBlocks", s.distinctMinedBlocks(), "batchBlockLimit", s.blockLimit());
	}

	public AutomationState state() {
		return state;
	}

	public net.minecraft.network.chat.MutableComponent detail() {
		BlockPos target = baritone == null ? null : watchdogNavigationTarget();
		return target == null ? detail.component() : detail.component().append(Translations.DETAIL.tr("watchdog", target.toShortString(), watchdogRetries));
	}

	public Instant changedAt() {
		return changedAt;
	}

	public List<StateTransition> stateHistory() {
		return List.copyOf(stateHistory);
	}

	public void clearStateHistory() {
		stateHistory.clear();
	}

	public void handleTickFailure(RuntimeException exception) {
		emergencyCleanup();
		String message = exception.getMessage();
		String summary = exception.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
		recordTransition(state, AutomationState.ERROR, Translations.DETAIL.message("unhandled_error_short", summary));
		state = AutomationState.ERROR;
		detail = Translations.DETAIL.message("unhandled_error", summary);
		changedAt = Instant.now();
	}

	public void resetForWorldChange() {
		if (baritone != null && baritone.getPlayerContext().player() != null
				&& !(baritone.getPlayerContext().player().containerMenu instanceof InventoryMenu)) {
			baritone.getPlayerContext().player().closeContainer();
		}
		stopProcesses();
		restoreProtectedBlocksSetting();
		restoreScaffoldBlocksSetting();
		baritone = null;
		miningProcess = null;
		navigation.unbind();
		area = null;
		miningReturnPoint = null;
		miningReturnDimension = null;
		activeReturnTarget = null;
		activeReturnWaypoint = null;
		returnPortalWaypoint = null;
		inventoryClicks.clear();
		resetNavigationWatchdog();
		resetMiningStall();
		stallTracker.reset();
		StuckCellPenalty.clear();
		consecutiveStallCount = 0;
		recentStallSpots.clear();
		unload.debugOnly = false;
		supply.debugOnly = false;
		sleep.debugOnly = false;
		clearSleepContext();
		clearSupplyContext();
		clearRepairContext();
		transition(AutomationState.IDLE, Translations.DETAIL.message("world_changed"));
	}

	private void synchronizeMining() {
		if (creativeMiner.isActive()) {
			AreaMiningStatus status = creativeMiner.status();
			if (status.minedBlocks() > 0) autoRestarts = 0;
			trackMiningProgress(status);
			if (miningStallRecovery || state != AutomationState.MINING) return;
			synchronize(AutomationState.MINING, progress(status));
			return;
		}
		AreaMiningStatus status = miningProcess.getAreaMiningStatus();
		switch (status.state()) {
			case RUNNING -> {
				miningPathingRecoveryAttempts = MiningPausePolicy.resetOnProgress(miningPathingRecoveryAttempts, status.minedBlocks());
				if (status.minedBlocks() > 0) autoRestarts = 0;
				trackMiningProgress(status);
				if (miningStallRecovery || state != AutomationState.MINING) return;
				synchronize(AutomationState.MINING, progress(status));
			}
			case PAUSED -> {
				if (status.pauseReason() == AreaMiningStatus.PauseReason.BLOCK_LIMIT_REACHED) beginDropCollection(false);
				else if (MiningPausePolicy.recoverable(status.pauseReason() == AreaMiningStatus.PauseReason.PATHING_FAILED)) recoverMiningPathing(status);
				else synchronize(AutomationState.PAUSED, Translations.DETAIL.message("mining_paused",
						status.pauseReason().name().toLowerCase(Locale.ROOT), progress(status).component()));
			}
			case COMPLETE -> beginDropCollection(true);
			case CANCELLED -> transition(AutomationState.ERROR, Translations.DETAIL.message("mining_cancelled"));
			case IDLE -> transition(AutomationState.ERROR, Translations.DETAIL.message("mining_stopped_unexpectedly"));
		}
	}

	/**
	 * Baritone pauses area mining whenever one path calculation fails, and a paused builder never resumes by
	 * itself. Reposition once and restart the cycle instead of leaving the whole task frozen.
	 */
	private void recoverMiningPathing(AreaMiningStatus status) {
		if (miningStallRecovery) return;
		if (MiningPausePolicy.exhausted(miningPathingRecoveryAttempts, MINING_PATHING_RECOVERY_LIMIT)) {
			transition(AutomationState.ERROR, Translations.DETAIL.message("mining_pathing_failed",
					miningPathingRecoveryAttempts, progress(status).component()));
			return;
		}
		miningPathingRecoveryAttempts++;
		markStuckLocation();
		if (miningProcess != null) miningProcess.deferCurrentTargets(45_000L);
		int dist = Math.max(MiningPausePolicy.recoveryDistance(advanced.miningStallMovementDistance),
				4 * Math.min(4, miningPathingRecoveryAttempts));
		BlockPos target = reverseBacktrackTarget(Math.max(4.0, dist * 0.5), Math.max(14.0, dist * 2.0), true);
		if (target == null) target = randomNearbySafeTarget(dist);
		if (target == null) {
			resumeMiningCycle(false);
			return;
		}
		beginMiningRecoveryWalk(target);
		transition(AutomationState.MINING, Translations.DETAIL.message("mining_pathing_recovery", target.toShortString(), miningPathingRecoveryAttempts));
	}

	/**
	 * Escalating escape for "digging stopped making progress": rebuild the cycle, then relocate
	 * further each attempt in a rotating direction so the reachable target set actually changes.
	 */
	private void trackMiningProgress(AreaMiningStatus status) {
		// Unique positions mined: a place/dig loop over the same few blocks keeps the raw
		// counter growing while this one stalls, which is what has to trigger the escape.
		long score = status.distinctMinedBlocks() > 0L ? status.distinctMinedBlocks() : status.minedBlocks();
		boolean rawGrowing = status.minedBlocks() > miningRawBaseline;
		miningRawBaseline = status.minedBlocks();
		if (MiningEscapePolicy.hasProgressed(score, miningProgressBaseline)) {
			miningProgressBaseline = score;
			miningProgressIdleScans = 0;
			miningProgressLastPos = null;
			miningProgressTransitCredits = 0;
			miningEscapeAttempts = 0;
			consecutiveStallCount = 0;
			recentStallSpots.clear();
			return;
		}
		if (miningStallRecovery || !inventoryClicks.isEmpty()) {
			miningProgressLastPos = null;
			return;
		}
		Vec3 currentPos = baritone == null || baritone.getPlayerContext().player() == null
				? null
				: baritone.getPlayerContext().player().position();
		double movedDistSq = (currentPos != null && miningProgressLastPos != null)
				? currentPos.distanceToSqr(miningProgressLastPos)
				: 0.0;
		miningProgressLastPos = currentPos;
		boolean pathing = baritone != null && (creativeMiner.isActive() || baritone.getPathingBehavior().isPathing());
		boolean confinedOsc = stallTracker.isOscillatingConfined(6, 3.5);
		if (MiningEscapePolicy.isActiveTransit(movedDistSq, pathing, confinedOsc, rawGrowing, miningProgressTransitCredits)) {
			miningProgressTransitCredits++;
			return;
		}
		miningProgressIdleScans++;
		if (!MiningEscapePolicy.shouldEscape(miningProgressIdleScans, rawGrowing)) return;
		escapeMining();
	}

	private void escapeMining() {
		miningProgressIdleScans = 0;
		miningProgressLastPos = null;
		miningProgressTransitCredits = 0;
		stallTracker.clearWindow();
		markStuckLocation();
		if (creativeMiner.isActive()) {
			creativeMiner.deferCurrentTarget(baritone);
		} else if (miningProcess != null) {
			miningProcess.deferCurrentTargets(45_000L);
		}
		int attempt = ++miningEscapeAttempts;
		if (MiningEscapePolicy.giveUp(attempt)) {
			stopProcesses();
			transition(AutomationState.PAUSED, Translations.DETAIL.message("mining_no_progress_paused", attempt));
			return;
		}
		int distance = MiningEscapePolicy.escapeDistance(attempt);
		BlockPos target = null;
		if (distance > 0) {
			target = reverseBacktrackTarget(Math.max(5.0, distance * 0.5), Math.max(18.0, distance + 8.0), true);
			if (target == null) target = escapeTarget(distance, attempt);
		}
		if (target == null) {
			resumeMiningCycle(false);
			transition(AutomationState.MINING, Translations.DETAIL.message("mining_no_progress_rebuild", attempt));
			return;
		}
		beginMiningRecoveryWalk(target);
		transition(AutomationState.MINING, Translations.DETAIL.message("mining_no_progress_relocate", target.toShortString(), attempt, distance));
	}

	private BlockPos escapeTarget(int distance, int attempt) {
		BetterBlockPos feet = baritone.getPlayerContext().playerFeet();
		int base = MiningEscapePolicy.directionIndex(attempt);
		int[][] directions = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
		for (int index = 0; index < 4; index++) {
			int[] direction = directions[(base + index) % 4];
			int cx = feet.x + direction[0] * distance;
			int cz = feet.z + direction[1] * distance;
			BlockPos candidate = findSafePositionNearColumn(cx, feet.y, cz, true);
			if (candidate != null) return candidate;
		}
		return randomNearbySafeTarget(distance);
	}

	private boolean hasConfiguredUnload() {
		return unloadEnabled && !unloadingPoints.isEmpty();
	}

	private void startMiningCycle() {
		resetMiningStall();
		miningProgressBaseline = MiningEscapePolicy.initialBaseline();
		miningRawBaseline = 0L;
		miningProgressIdleScans = 0;
		int emptySlots = emptyInventorySlots();
		if (emptySlots == 0 && hasConfiguredUnload()) {
			requestUnload();
			return;
		}
		long blockLimit = MiningPausePolicy.miningBlockLimit(
				collectDropsEnabled, hasConfiguredUnload(), emptySlots, advanced.inventoryReservedSlots, advanced.miningBlocksPerEmptySlot);
		startMiningMode(area, new AreaMiningOptions(liquidPolicy, sealingBlocks, blockLimit, activeProtectedBlocks(), miningStrategy, miningBlacklistEnabled, activeTargetBlocks()));
		transition(AutomationState.MINING, Translations.DETAIL.message("mining_started", blockLimit, emptySlots));
	}

	private void resumeMiningCycle() {
		resumeMiningCycle(false);
	}

	private void resumeMiningCycle(boolean resetProgressTracking) {
		resetMiningStall();
		miningProgressBaseline = MiningEscapePolicy.initialBaseline();
		miningRawBaseline = 0L;
		if (resetProgressTracking) {
			miningProgressIdleScans = 0;
			miningProgressLastPos = null;
			miningProgressTransitCredits = 0;
			miningEscapeAttempts = 0;
		}
		int emptySlots = emptyInventorySlots();
		if (emptySlots == 0 && hasConfiguredUnload()) {
			requestUnload();
			return;
		}
		long blockLimit = MiningPausePolicy.miningBlockLimit(
				collectDropsEnabled, hasConfiguredUnload(), emptySlots, advanced.inventoryReservedSlots, advanced.miningBlocksPerEmptySlot);
		startMiningMode(area, new AreaMiningOptions(liquidPolicy, sealingBlocks, blockLimit, activeProtectedBlocks(), miningStrategy, miningBlacklistEnabled, activeTargetBlocks()), !resetProgressTracking);
		transition(AutomationState.MINING, Translations.DETAIL.message("mining_resumed"));
	}

	private void startMiningMode(ConfiguredColumnarArea targetArea, AreaMiningOptions options) {
		startMiningMode(targetArea, options, false);
	}

	private void startMiningMode(ConfiguredColumnarArea targetArea, AreaMiningOptions options, boolean preserveState) {
		navigation.stopWalking();
		if (navigation.canUseCreativeFlight()) {
			if (miningProcess != null) miningProcess.cancel();
			if (preserveState) creativeMiner.resume(targetArea, options);
			else creativeMiner.start(targetArea, options);
		} else {
			creativeMiner.stop(baritone);
			miningProcess.mineArea(targetArea, options, preserveState);
		}
	}

	private void beginDropCollection(boolean miningComplete) {
		miningCompletePending = miningComplete;
		creativeMiner.stop(baritone);
		schematicBuilder.stop(baritone, navigation);
		resourceScanner.stop(baritone, navigation);
		if (miningProcess != null) miningProcess.cancel();
		if (!collectDropsEnabled) {
			navigation.stopWalking();
			if (miningComplete) {
				if (hasConfiguredUnload()) requestUnload();
				else transition(AutomationState.COMPLETE, Translations.DETAIL.message("mining_complete_collection_unload_disabled"));
			} else {
				startMiningCycle();
			}
			return;
		}
		disablePlacementForNavigation();
		stableInventoryScans = 0;
		lastInventoryItemCount = inventoryItemCount();
		transition(AutomationState.COLLECTING_DROPS, Translations.DETAIL.message("collecting_drops", advanced.dropCollectionRadius));
		tickDropCollection();
	}

	private void tickDropCollection() {
		if (emptyInventorySlots() == 0) {
			if (hasConfiguredUnload()) requestUnload();
			else {
				restoreAllowPlace();
				if (miningCompletePending) transition(AutomationState.COMPLETE, Translations.DETAIL.message("mining_collection_complete_unload_disabled"));
				else startMiningCycle();
			}
			return;
		}
		int itemCount = inventoryItemCount();
		if (itemCount > lastInventoryItemCount) stableInventoryScans = 0;
		else stableInventoryScans++;
		lastInventoryItemCount = itemCount;
		List<Goal> goals = nearbyDropGoals();
		if (stableInventoryScans >= dropStableScanLimit()) {
			navigation.stopWalking();
			if (hasConfiguredUnload()) requestUnload();
			else {
				restoreAllowPlace();
				if (miningCompletePending) transition(AutomationState.COMPLETE, Translations.DETAIL.message("mining_collection_complete_unload_disabled"));
				else startMiningCycle();
			}
			return;
		}
		if (goals.isEmpty()) {
			navigation.stopWalking();
			synchronize(AutomationState.COLLECTING_DROPS, Translations.DETAIL.message("waiting_inventory_growth", itemCount, stableInventoryScans, dropStableScanLimit()));
			return;
		}
		navigation.walk(new GoalComposite(goals.toArray(new Goal[0])));
		synchronize(AutomationState.COLLECTING_DROPS, Translations.DETAIL.message("collecting_drop_targets", goals.size(), itemCount, stableInventoryScans, dropStableScanLimit()));
	}

	private List<Goal> nearbyDropGoals() {
		List<Goal> goals = new ArrayList<>();
		double radiusSquared = (double) advanced.dropCollectionRadius * advanced.dropCollectionRadius;
		for (Entity entity : baritone.getPlayerContext().entities()) {
			double dx = entity.getX() - baritone.getPlayerContext().player().getX();
			double dz = entity.getZ() - baritone.getPlayerContext().player().getZ();
			if (entity instanceof ItemEntity && entity.isAlive() && dx * dx + dz * dz <= radiusSquared) {
				goals.add(new GoalBlock(new BetterBlockPos(entity.position().x, entity.position().y + 0.1, entity.position().z)));
			}
		}
		return goals;
	}

	private void bindBaritone() {
		this.baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
		this.miningProcess = baritone.getAreaMineProcess();
		navigation.bind(baritone);
	}

	private void loadRepairConfiguration(PerimeterConfig config) {
		this.perimeterPortalOverworld = config.perimeterPortalOverworld;
		this.perimeterPortalNether = config.perimeterPortalNether;
		this.repairPortalOverworld = config.repairPortalOverworld;
		this.repairPortalNether = config.repairPortalNether;
		this.furnaceRowStart = config.furnaceRowStart;
		this.furnaceRowEnd = config.furnaceRowEnd;
	}

	private void beginRepairFlow(boolean debug) {
		if (crossDimensionRepairEnabled && !baritone.getPlayerContext().world().dimension().equals(Level.OVERWORLD)) {
			transition(AutomationState.ERROR, Translations.DETAIL.message("repair_requires_overworld"));
			return;
		}
		if (!validateRepairConfigurationValues()) return;
		repair.previousState = state;
		repair.debugOnly = debug;
		repair.crossDimension = crossDimensionRepairEnabled;
		miningReturnPoint = baritone.getPlayerContext().playerFeet();
		miningReturnDimension = baritone.getPlayerContext().world().dimension();
		returnAction = ReturnAction.AFTER_REPAIR;
		repair.plan = captureRepairPlan(debug);
		if (repair.plan.targetCounts().isEmpty()) {
			transition(AutomationState.ERROR, Translations.DETAIL.message(debug ? "no_debug_repair_target" : "no_repair_target"));
			return;
		}
		repair.furnaces = furnaceRow();
		repair.furnaceIndex = 0;
		stopProcesses();
		applyRepairRestrictions();
		if (repair.crossDimension) {
			repair.stage = RepairFlow.Stage.OUTBOUND_PERIMETER_PORTAL;
			startRepairNavigation(position(perimeterPortalOverworld), AutomationState.NAVIGATING_TO_PERIMETER_PORTAL);
		} else {
			repair.stage = RepairFlow.Stage.REPAIR_MACHINE;
			startCurrentFurnaceNavigation();
		}
	}

	private boolean validateRepairConfigurationValues() {
		if (furnaceRowStart == null || furnaceRowEnd == null || crossDimensionRepairEnabled
				&& (perimeterPortalOverworld == null || perimeterPortalNether == null || repairPortalOverworld == null
				|| repairPortalNether == null)) {
			transition(AutomationState.ERROR, Translations.DETAIL.message("repair_configuration_incomplete"));
			return false;
		}
		return true;
	}

	private boolean hasUsableRepairConfiguration() {
		if (furnaceRowStart == null || furnaceRowEnd == null || crossDimensionRepairEnabled
				&& (perimeterPortalOverworld == null || perimeterPortalNether == null || repairPortalOverworld == null
				|| repairPortalNether == null)) return false;
		int changedAxes = (furnaceRowStart.x == furnaceRowEnd.x ? 0 : 1)
				+ (furnaceRowStart.y == furnaceRowEnd.y ? 0 : 1)
				+ (furnaceRowStart.z == furnaceRowEnd.z ? 0 : 1);
		return changedAxes <= 1;
	}

	private void startRepairNavigation(BlockPos target, AutomationState navigationState) {
		repair.navigationTarget = target;
		repair.flying = false;
		repair.flightAttempts = 0;
		BetterBlockPos player = baritone.getPlayerContext().playerFeet();
		if (player.distSqr(target) > navigationFlightDistanceSquared() && canFlyForRepair()) {
			startFlyingForRepair(navigationState);
			return;
		}
		startWalkingForRepair(navigationState);
	}

	private boolean canFlyForRepair() {
		if (navigation.canUseCreativeFlight()) return false;
		ItemStack chest = baritone.getPlayerContext().player().getItemBySlot(EquipmentSlot.CHEST);
		return elytraNavigationEnabled && navigation.isFlightLoaded() && chest.is(Items.ELYTRA)
				&& remainingDurability(chest) > advanced.emergencyFlightDurabilityThreshold
				&& fireworkCount() > 0;
	}

	private void prepareRepairFlightSettings() {
		if (savedElytraMinimumDurability == null) savedElytraMinimumDurability = BaritoneAPI.getSettings().elytraMinimumDurability.value;
		if (savedElytraMinFireworksBeforeLanding == null) savedElytraMinFireworksBeforeLanding = BaritoneAPI.getSettings().elytraMinFireworksBeforeLanding.value;
		BaritoneAPI.getSettings().elytraMinimumDurability.value = advanced.emergencyFlightDurabilityThreshold;
		BaritoneAPI.getSettings().elytraMinFireworksBeforeLanding.value = -1;
	}

	private void startFlyingForRepair(AutomationState navigationState) {
		restoreAllowPlace();
		if (navigationState == AutomationState.NAVIGATING_TO_PERIMETER_PORTAL) enableBreakingForNavigation();
		prepareRepairFlightSettings();
		repair.flying = true;
		repair.flightAttempts++;
		navigation.fly(repair.navigationTarget);
		transition(navigationState, Translations.DETAIL.message("flying_repair_target", repair.navigationTarget.toShortString()));
	}

	private void startWalkingForRepair(AutomationState navigationState) {
		repair.flying = false;
		if (navigationState == AutomationState.NAVIGATING_TO_PERIMETER_PORTAL) enableBreakingForNavigation();
		else disablePlacementForNavigation();
		if (repair.stage == RepairFlow.Stage.REPAIR_MACHINE) {
			navigation.walk(new GoalBlock(repair.navigationTarget));
		} else {
			navigation.walk(new GoalBlock(repair.navigationTarget));
		}
		if (repair.stage == RepairFlow.Stage.REPAIR_MACHINE) {
			transition(navigationState, Translations.DETAIL.message("walking_furnace_stand",
					repair.furnaces.get(repair.furnaceIndex).toShortString(), repair.navigationTarget.toShortString()));
		} else transition(navigationState, Translations.DETAIL.message("walking_repair_target", repair.navigationTarget.toShortString()));
	}

	private void tickRepairNavigation() {
		boolean reached = repair.stage == RepairFlow.Stage.REPAIR_MACHINE
				? canReachFurnace(repair.furnaces.get(repair.furnaceIndex))
				: baritone.getPlayerContext().playerFeet().equals(repair.navigationTarget);
		if (reached) {
			navigation.stopFlying();
			navigation.stopWalking();
			restoreRepairNavigationSettings();
			if (repair.stage == RepairFlow.Stage.REPAIR_MACHINE) beginFurnaceInteraction();
			else if (repair.stage == RepairFlow.Stage.RETURN_TO_MACHINE_TAKEOFF) startRepairPortalReturn();
			else beginPortalEntry();
			return;
		}
		if (repair.flying) {
			if (navigation.isFlying()) return;
			restoreRepairNavigationSettings();
			startWalkingForRepair(state);
			return;
		}
		if (navigation.isWalking()) return;
		restoreAllowPlace();
		if (repair.stage == RepairFlow.Stage.REPAIR_MACHINE && tryNextFurnaceStand()) return;
		if (repair.navigationTarget.getY() > baritone.getPlayerContext().playerFeet().y
				&& repair.flightAttempts < advanced.flightRetryCount && canFlyForRepair()) {
			startFlyingForRepair(state);
			return;
		}
		restoreRepairNavigationSettings();
		if (repair.stage == RepairFlow.Stage.REPAIR_MACHINE) {
			transition(AutomationState.ERROR, Translations.DETAIL.message("furnace_unreachable",
					repair.furnaces.get(repair.furnaceIndex).toShortString(), repair.navigationTarget.toShortString()));
		} else transition(AutomationState.ERROR, Translations.DETAIL.message("repair_target_unreachable", repair.navigationTarget.toShortString()));
	}

	private void restoreRepairNavigationSettings() {
		restoreAllowPlace();
		restoreElytraMinimumDurability();
		restoreElytraFireworkReserve();
	}

	private void beginPortalEntry() {
		restoreRepairNavigationSettings();
		repair.portalWaitTicks = 0;
		repair.portalExitCandidates = List.of();
		repair.postPortalNavigationTarget = null;
		repair.postPortalNavigationState = null;
		AutomationState portalState = repair.stage == RepairFlow.Stage.OUTBOUND_PERIMETER_PORTAL || repair.stage == RepairFlow.Stage.RETURN_PERIMETER_PORTAL
				? AutomationState.ENTERING_PERIMETER_PORTAL : AutomationState.ENTERING_REPAIR_PORTAL;
		transition(portalState, Translations.DETAIL.message("waiting_repair_portal", repair.navigationTarget.toShortString()));
	}

	private void tickEnteringRepairPortal() {
		if (++repair.portalWaitTicks > ticks(advanced.portalTransitionTimeoutSeconds)) {
			transition(AutomationState.ERROR, Translations.DETAIL.message("repair_portal_timeout"));
			return;
		}
		baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
	}

	public void onRespawn() {
		retaliation.cancel();
		if (deathRespawnPending) bindBaritone();
	}

	public void handleWorldChange() {
		retaliation.cancel();
		if (deathRespawnPending) return;
		if (returnPortalWaypoint != null
				&& (state == AutomationState.ENTERING_PERIMETER_PORTAL || state == AutomationState.ENTERING_REPAIR_PORTAL)) {
			stopProcesses();
			bindBaritone();
			if (repair.stage != null) applyRepairRestrictions();
			restoreRepairNavigationSettings();
			BlockPos arrivalPortal = positionForReturnWaypoint(returnPortalWaypoint.counterpart());
			returnPortalWaypoint = null;
			beginClearRepairPortal(arrivalPortal, null, null);
			return;
		}
		if (repair.stage == null || state != AutomationState.ENTERING_PERIMETER_PORTAL && state != AutomationState.ENTERING_REPAIR_PORTAL) {
			resetForWorldChange();
			return;
		}
		stopProcesses();
		bindBaritone();
		applyRepairRestrictions();
		restoreRepairNavigationSettings();
		repair.portalWaitTicks = 0;
		switch (repair.stage) {
			case OUTBOUND_PERIMETER_PORTAL -> {
				repair.stage = RepairFlow.Stage.OUTBOUND_REPAIR_PORTAL;
				beginClearRepairPortal(position(perimeterPortalNether), position(repairPortalNether), AutomationState.NAVIGATING_TO_REPAIR_PORTAL);
			}
			case OUTBOUND_REPAIR_PORTAL -> {
				repair.stage = RepairFlow.Stage.REPAIR_MACHINE;
				beginClearRepairPortal(position(repairPortalOverworld), null, AutomationState.NAVIGATING_TO_REPAIR_MACHINE);
			}
			case RETURN_REPAIR_PORTAL -> {
				repair.stage = RepairFlow.Stage.RETURN_PERIMETER_PORTAL;
				beginClearRepairPortal(position(repairPortalNether), position(perimeterPortalNether), AutomationState.NAVIGATING_TO_PERIMETER_PORTAL);
			}
			case RETURN_PERIMETER_PORTAL -> {
				repair.stage = RepairFlow.Stage.RETURN_TO_MINE;
				beginClearRepairPortal(position(perimeterPortalOverworld), null, null);
			}
			default -> transition(AutomationState.ERROR, Translations.DETAIL.message("unexpected_repair_portal"));
		}
	}

	private void beginClearRepairPortal(BlockPos portal, BlockPos nextTarget, AutomationState nextState) {
		repair.portalExitOrigin = currentClientPlayerFeet();
		repair.portalExitSearchScans = 0;
		repair.portalExitCandidates = findPortalExitCandidates(repair.portalExitOrigin);
		repair.postPortalNavigationTarget = nextTarget;
		repair.postPortalNavigationState = nextState;
		disablePlacementForNavigation();
		if (!repair.portalExitCandidates.isEmpty()) startPortalExitPath();
		transition(AutomationState.CLEARING_REPAIR_PORTAL, Translations.DETAIL.message(
				repair.portalExitCandidates.isEmpty() ? "waiting_portal_exits" : "walking_clear_portal", portal.toShortString()));
	}

	private void tickClearRepairPortal() {
		if (repair.portalExitCandidates.contains(baritone.getPlayerContext().playerFeet())) {
			navigation.stopWalking();
			restoreAllowPlace();
			repair.portalExitCandidates = List.of();
			if (repair.postPortalNavigationState == AutomationState.NAVIGATING_TO_REPAIR_MACHINE) {
				startCurrentFurnaceNavigation();
			} else if (repair.postPortalNavigationTarget == null) beginReturnToMine();
			else startRepairNavigation(repair.postPortalNavigationTarget, repair.postPortalNavigationState);
			return;
		}
		if (navigation.isWalking()) return;
		if (++repair.portalExitSearchScans > monitorScans(advanced.portalExitTimeoutSeconds)) {
			restoreAllowPlace();
			transition(AutomationState.ERROR, Translations.DETAIL.message("portal_exit_timeout", advanced.portalExitTimeoutSeconds));
			return;
		}
		repair.portalExitOrigin = currentClientPlayerFeet();
		repair.portalExitCandidates = findPortalExitCandidates(repair.portalExitOrigin);
		if (!repair.portalExitCandidates.isEmpty()) {
			startPortalExitPath();
			synchronize(AutomationState.CLEARING_REPAIR_PORTAL, Translations.DETAIL.message("walking_clear_portal_short"));
			return;
		}
		synchronize(AutomationState.CLEARING_REPAIR_PORTAL, Translations.DETAIL.message("waiting_portal_exits_short"));
	}

	private void startPortalExitPath() {
		navigation.walk(new GoalComposite(repair.portalExitCandidates.stream().map(GoalBlock::new).toArray(Goal[]::new)));
	}

	private List<BlockPos> findPortalExitCandidates(BlockPos origin) {
		List<BlockPos> candidates = new ArrayList<>();
		BlockPos player = currentClientPlayerFeet();
		for (int dx = -advanced.portalExitMaxRadius; dx <= advanced.portalExitMaxRadius; dx++) {
			for (int dz = -advanced.portalExitMaxRadius; dz <= advanced.portalExitMaxRadius; dz++) {
				int horizontalDistanceSquared = dx * dx + dz * dz;
				if (horizontalDistanceSquared < advanced.portalExitMinRadius * advanced.portalExitMinRadius
						|| horizontalDistanceSquared > advanced.portalExitMaxRadius * advanced.portalExitMaxRadius) continue;
				for (int dy = -advanced.portalExitVerticalRadius; dy <= advanced.portalExitVerticalRadius; dy++) {
					BlockPos candidate = origin.offset(dx, dy, dz);
					if (isPortalExitPosition(candidate)) candidates.add(candidate);
				}
			}
		}
		candidates.sort(Comparator.comparingDouble(player::distSqr));
		return candidates;
	}

	private boolean isPortalExitPosition(BlockPos position) {
		var world = Minecraft.getInstance().level;
		if (world == null) return false;
		BlockPos floor = position.below();
		if (!world.isLoaded(floor) || !world.isLoaded(position.above(3))) return false;
		var floorState = world.getBlockState(floor);
		if (!floorState.isFaceSturdy(world, floor, Direction.UP)
				|| !floorState.getFluidState().isEmpty()
				|| floorState.is(Blocks.MAGMA_BLOCK)
				|| floorState.is(Blocks.CAMPFIRE)
				|| floorState.is(Blocks.SOUL_CAMPFIRE)) return false;
		for (int offset = 0; offset <= 3; offset++) {
			BlockPos clearance = position.above(offset);
			if (!world.isLoaded(clearance)) return false;
			var state = world.getBlockState(clearance);
			if (state.is(Blocks.NETHER_PORTAL) || !state.getFluidState().isEmpty()
					|| !state.getCollisionShape(world, clearance).isEmpty()) return false;
		}
		return true;
	}

	private BlockPos currentClientPlayerFeet() {
		if (Minecraft.getInstance().player == null) throw new IllegalStateException("No client player is loaded.");
		return Minecraft.getInstance().player.blockPosition();
	}

	private static BlockPos position(PositionConfig position) {
		return new BlockPos(position.x, position.y, position.z);
	}

	private List<BlockPos> furnaceRow() {
		BlockPos start = position(furnaceRowStart);
		BlockPos end = position(furnaceRowEnd);
		int dx = Integer.compare(end.getX(), start.getX());
		int dy = Integer.compare(end.getY(), start.getY());
		int dz = Integer.compare(end.getZ(), start.getZ());
		int steps = Math.max(Math.abs(end.getX() - start.getX()), Math.max(Math.abs(end.getY() - start.getY()), Math.abs(end.getZ() - start.getZ())));
		List<BlockPos> result = new ArrayList<>();
		for (int index = 0; index <= steps; index++) result.add(start.offset(dx * index, dy * index, dz * index));
		return result;
	}

	private List<BlockPos> furnaceStandPositions(BlockPos furnace) {
		return interactionPositionFinder.find(Minecraft.getInstance().level, furnace);
	}

	private List<BlockPos> interactionStandPositions(BlockPos target) {
		return interactionPositionFinder.find(Minecraft.getInstance().level, target);
	}

	private List<BlockPos> bedStandPositions(BlockPos bed) {
		return interactionPositionFinder.find(Minecraft.getInstance().level, bed);
	}

	private void startCurrentFurnaceNavigation() {
		BlockPos furnace = repair.furnaces.get(repair.furnaceIndex);
		if (canReachFurnace(furnace)) {
			beginFurnaceInteraction();
			return;
		}
		BetterBlockPos player = baritone.getPlayerContext().playerFeet();
		repair.furnaceStandCandidates = furnaceStandPositions(furnace).stream()
				.sorted(Comparator.comparingDouble(player::distSqr))
				.toList();
		repair.furnaceStandIndex = 0;
		if (repair.furnaceStandCandidates.isEmpty()) {
			repair.navigationTarget = furnace;
			transition(AutomationState.ERROR, Translations.DETAIL.message("furnace_no_safe_stand", furnace.toShortString()));
			return;
		}
		startRepairNavigation(repair.furnaceStandCandidates.getFirst(), AutomationState.NAVIGATING_TO_REPAIR_MACHINE);
	}

	private boolean tryNextFurnaceStand() {
		while (++repair.furnaceStandIndex < repair.furnaceStandCandidates.size()) {
			BlockPos candidate = repair.furnaceStandCandidates.get(repair.furnaceStandIndex);
			if (candidate.equals(baritone.getPlayerContext().playerFeet())) continue;
			repair.navigationTarget = candidate;
			repair.flying = false;
			startWalkingForRepair(AutomationState.NAVIGATING_TO_REPAIR_MACHINE);
			return true;
		}
		return false;
	}

	private Optional<BlockPos> closestInteractionStand(BlockPos target) {
		return interactionPositionFinder.closest(Minecraft.getInstance().level, target, baritone.getPlayerContext().playerFeet());
	}

	private Optional<BlockPos> closestBedStand(BlockPos bed) {
		return interactionPositionFinder.closest(Minecraft.getInstance().level, bed, baritone.getPlayerContext().playerFeet());
	}

	private BlockPos findSafeStandNear(BlockPos chest) {
		BetterBlockPos player = baritone.getPlayerContext().playerFeet();
		List<BlockPos> candidates = new ArrayList<>();
		for (int dy = -1; dy <= 1; dy++) {
			BlockPos base = chest.atY(chest.getY() + dy);
			for (int dx = -advanced.unloadLandingSearchRadius; dx <= advanced.unloadLandingSearchRadius; dx++) {
				for (int dz = -advanced.unloadLandingSearchRadius; dz <= advanced.unloadLandingSearchRadius; dz++) {
					if (dx == 0 && dz == 0 && dy == 0) continue;
					BlockPos position = base.offset(dx, 0, dz);
					if (isSafeStandingPosition(position)) {
						candidates.add(position);
					}
				}
			}
		}
		return candidates.stream().min(Comparator.comparingDouble(player::distSqr)).orElse(null);
	}

	private boolean canReachFurnace(BlockPos furnace) {
		return canReachInteractionTarget(furnace);
	}

	private boolean canReachInteractionTarget(BlockPos target) {
		return interactionPositionFinder.canReach(baritone.getPlayerContext().player(), target);
	}

	private boolean isAtInteractionStand(BlockPos target) {
		return interactionPositionFinder.isAtValidPosition(
				Minecraft.getInstance().level, target, baritone.getPlayerContext().playerFeet());
	}

	private boolean isAtBedStand(BlockPos bed) {
		return interactionPositionFinder.isAtValidPosition(
				Minecraft.getInstance().level, bed, baritone.getPlayerContext().playerFeet());
	}

	private void applyRepairRestrictions() {
		if (savedRepairAllowBreak == null) savedRepairAllowBreak = BaritoneAPI.getSettings().allowBreak.value;
		BaritoneAPI.getSettings().allowBreak.value = false;
	}

	private void restoreRepairRestrictions() {
		if (savedRepairAllowBreak != null) BaritoneAPI.getSettings().allowBreak.value = savedRepairAllowBreak;
		savedRepairAllowBreak = null;
	}

	private void beginSupply(SupplyFlow.Kind kind, boolean debug) {
		PositionConfig point = kind == SupplyFlow.Kind.CONSUMABLES ? consumableSupplyPoint : durabilitySupplyPoint;
		if (point == null) {
			stopProcesses();
			restoreAllowPlace();
			transition(AutomationState.ERROR, Translations.DETAIL.message("supply_point_missing", kind.displayName));
			return;
		}
		supply.previousState = state;
		supply.debugOnly = debug;
		miningReturnPoint = baritone.getPlayerContext().playerFeet();
		miningReturnDimension = baritone.getPlayerContext().world().dimension();
		returnAction = ReturnAction.AFTER_SUPPLY;
		supply.kind = kind;
		supply.point = point;
		BlockPos chest = position(point);
		supply.stand = closestInteractionStand(chest).orElse(chest.above());
		supply.flightAttempts = 0;
		supply.durabilityPlan = kind == SupplyFlow.Kind.DURABILITY ? captureDurabilitySupplyPlan() : null;
		if (kind == SupplyFlow.Kind.DURABILITY && supply.durabilityPlan.targetHealthyCounts().isEmpty()) {
			restoreAllowPlace();
			transition(AutomationState.ERROR, Translations.DETAIL.message("no_supply_replacement_target"));
			return;
		}
		stopProcesses();
		BetterBlockPos player = baritone.getPlayerContext().playerFeet();
		if (player.distSqr(supply.stand) > navigationFlightDistanceSquared() && canFlyToSupply()) {
			startFlyingToSupply();
			return;
		}
		startWalkingToSupply();
	}

	private boolean canFlyToDestination() {
		if (navigation.canUseCreativeFlight()) return false;
		ItemStack chest = baritone.getPlayerContext().player().getItemBySlot(EquipmentSlot.CHEST);
		return elytraNavigationEnabled && navigation.isFlightLoaded() && chest.is(Items.ELYTRA)
				&& remainingDurability(chest) > advanced.elytraDurabilityThreshold
				&& fireworkCount() > BaritoneAPI.getSettings().elytraMinFireworksBeforeLanding.value;
	}

	private boolean canFlyToSupply() {
		if (navigation.canUseCreativeFlight()) return false;
		ItemStack chest = baritone.getPlayerContext().player().getItemBySlot(EquipmentSlot.CHEST);
		return elytraNavigationEnabled && navigation.isFlightLoaded() && chest.is(Items.ELYTRA)
				&& remainingDurability(chest) > advanced.emergencyFlightDurabilityThreshold
				&& fireworkCount() > 0;
	}

	private AutomationState supplyNavigationState() {
		return supply.kind == SupplyFlow.Kind.CONSUMABLES
				? AutomationState.NAVIGATING_TO_RESUPPLY
				: AutomationState.NAVIGATING_TO_DURABILITY_SUPPLY;
	}

	private AutomationState supplyInteractionState() {
		return supply.kind == SupplyFlow.Kind.CONSUMABLES
				? AutomationState.RESUPPLYING
				: AutomationState.SWAPPING_DURABILITY_AT_SUPPLY;
	}

	private void startWalkingToSupply() {
		supply.flying = false;
		enableBreakingForNavigation();
		BlockPos chest = position(supply.point);
		if (isAtInteractionStand(chest)) {
			restoreAllowPlace();
			beginSupplyInteraction();
			return;
		}
		List<BlockPos> stands = interactionStandPositions(chest);
		if (!stands.isEmpty()) {
			supply.stand = stands.stream().min(Comparator.comparingDouble(baritone.getPlayerContext().playerFeet()::distSqr)).orElseThrow();
			navigation.walk(new GoalComposite(stands.stream().map(GoalBlock::new).toArray(Goal[]::new)));
			transition(supplyNavigationState(), Translations.DETAIL.message("walking_supply", supply.kind.displayName, supply.stand.toShortString()));
			return;
		}
		BlockPos widerStand = findSafeStandNear(chest);
		if (widerStand != null) {
			supply.stand = widerStand;
			navigation.walk(new GoalBlock(supply.stand));
			transition(supplyNavigationState(), Translations.DETAIL.message("walking_supply", supply.kind.displayName, supply.stand.toShortString()));
			return;
		}
		restoreAllowPlace();
		transition(AutomationState.ERROR, Translations.DETAIL.message("supply_no_safe_stand", supply.kind.displayName, chest.toShortString()));
	}

	private void tickSupplyNavigation() {
		if (isAtInteractionStand(position(supply.point)) || baritone.getPlayerContext().playerFeet().equals(supply.stand)) {
			navigation.stopFlying();
			navigation.stopWalking();
			restoreElytraMinimumDurability();
			restoreAllowPlace();
			beginSupplyInteraction();
			return;
		}
		if (supply.flying) {
			if (navigation.isFlying()) return;
			restoreElytraMinimumDurability();
			restoreElytraFireworkReserve();
			startWalkingToSupply();
			return;
		}
		if (navigation.isWalking()) return;
		restoreAllowPlace();
		if (supply.stand.getY() > baritone.getPlayerContext().playerFeet().y
				&& supply.flightAttempts < advanced.flightRetryCount && canFlyToSupply()) {
			startFlyingToSupply();
			return;
		}
		restoreElytraMinimumDurability();
		restoreElytraFireworkReserve();
		transition(AutomationState.ERROR, Translations.DETAIL.message("supply_unreachable", supply.kind.displayName, supply.stand.toShortString()));
	}

	private void startFlyingToSupply() {
		restoreAllowPlace();
		if (savedElytraMinFireworksBeforeLanding == null) {
			savedElytraMinFireworksBeforeLanding = BaritoneAPI.getSettings().elytraMinFireworksBeforeLanding.value;
		}
		BaritoneAPI.getSettings().elytraMinFireworksBeforeLanding.value = -1;
		if (savedElytraMinimumDurability == null) savedElytraMinimumDurability = BaritoneAPI.getSettings().elytraMinimumDurability.value;
		BaritoneAPI.getSettings().elytraMinimumDurability.value = advanced.emergencyFlightDurabilityThreshold;
		supply.flying = true;
		supply.flightAttempts++;
		navigation.fly(supply.stand);
		transition(supplyNavigationState(), Translations.DETAIL.message("flying_supply", supply.kind.displayName, supply.stand.toShortString()));
	}

	private void disablePlacementForNavigation() {
		navigation.disablePlacement();
	}

	private void disableBreakingAndPlacementForNavigation() {
		navigation.disableBreakingAndPlacement();
	}

	private void enableBreakingForNavigation() {
		navigation.enableBreakingWithoutPlacement();
	}

	private void restoreAllowPlace() {
		navigation.restoreDestinationSettings();
	}

	private void restoreElytraMinimumDurability() {
		if (savedElytraMinimumDurability == null) return;
		BaritoneAPI.getSettings().elytraMinimumDurability.value = savedElytraMinimumDurability;
		savedElytraMinimumDurability = null;
	}

	private void restoreElytraFireworkReserve() {
		if (savedElytraMinFireworksBeforeLanding == null) return;
		BaritoneAPI.getSettings().elytraMinFireworksBeforeLanding.value = savedElytraMinFireworksBeforeLanding;
		savedElytraMinFireworksBeforeLanding = null;
	}

	private void beginSupplyInteraction() {
		restoreElytraMinimumDurability();
		restoreElytraFireworkReserve();
		supply.phase = supply.kind == SupplyFlow.Kind.DURABILITY ? SupplyFlow.Phase.PREPARING : SupplyFlow.Phase.OPENING;
		supply.interactionTicks = 0;
		transition(supplyInteractionState(), Translations.DETAIL.message("preparing_supply", supply.kind.displayName));
	}

	private void tickSupplyInteraction() {
		if (!inventoryClicks.isEmpty()) return;
		switch (supply.phase) {
			case PREPARING -> prepareDurabilityEquipment();
			case OPENING -> openSupplyChest();
			case TRANSFERRING -> transferSupplyItems();
			case FINALIZING -> finalizeSupplyEquipment();
		}
	}

	private void prepareDurabilityEquipment() {
		Inventory inventory = baritone.getPlayerContext().player().getInventory();
		ItemStack chest = baritone.getPlayerContext().player().getItemBySlot(EquipmentSlot.CHEST);
		if (isLowMonitoredItem(chest)) {
			int empty = firstEmptyInventorySlot();
			if (empty < 0) {
				transition(AutomationState.ERROR, Translations.DETAIL.message("no_slot_chest_item"));
				return;
			}
			queuePickupSwap(CHEST_MENU_SLOT, menuSlot(empty));
			return;
		}
		ItemStack offhand = inventory.getItem(OFFHAND_INVENTORY_SLOT);
		if (isLowMonitoredItem(offhand)) {
			int empty = firstEmptyInventorySlot();
			if (empty < 0) {
				transition(AutomationState.ERROR, Translations.DETAIL.message("no_slot_offhand_item"));
				return;
			}
			queuePickupSwap(OFFHAND_MENU_SLOT, menuSlot(empty));
			return;
		}
		supply.phase = SupplyFlow.Phase.OPENING;
		supply.interactionTicks = 0;
	}

	private void openSupplyChest() {
		if (!(baritone.getPlayerContext().player().containerMenu instanceof InventoryMenu)) {
			supply.phase = SupplyFlow.Phase.TRANSFERRING;
			return;
		}
		if (supply.interactionTicks++ > ticks(advanced.supplyInteractionTimeoutSeconds)) {
			transition(AutomationState.ERROR, Translations.DETAIL.message("supply_open_timeout", supply.kind.displayName));
			return;
		}
		BlockPos chest = new BlockPos(supply.point.x, supply.point.y, supply.point.z);
		Vec3 hitLocation = new Vec3(supply.point.x + 0.5, supply.point.y + 1.0, supply.point.z + 0.5);
		baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(
				baritone.getPlayerContext().playerHead(), hitLocation, baritone.getPlayerContext().playerRotations()), true);
		if (supply.interactionTicks % 5 == 1) {
			baritone.getPlayerContext().playerController().processRightClickBlock(
					baritone.getPlayerContext().player(), baritone.getPlayerContext().world(), InteractionHand.MAIN_HAND,
					new BlockHitResult(hitLocation, Direction.UP, chest, false));
		}
	}

	private void transferSupplyItems() {
		if (baritone.getPlayerContext().player().containerMenu instanceof InventoryMenu) {
			transition(AutomationState.ERROR, Translations.DETAIL.message("supply_closed"));
			return;
		}
		if (supply.kind == SupplyFlow.Kind.CONSUMABLES) transferConsumables();
		else transferDurabilityItems();
	}

	private void transferConsumables() {
		if (configuredFoodCount() < advanced.foodResupplyTarget) {
			if (!queueConsumableTransfer(allowedFoods, advanced.foodResupplyTarget - configuredFoodCount(), "configured food")) return;
		}
		if (usesElytraNavigation() && fireworkCount() < advanced.fireworkResupplyTarget) {
			if (!queueConsumableTransfer(Set.of(Items.FIREWORK_ROCKET), advanced.fireworkResupplyTarget - fireworkCount(), "firework rockets")) return;
		}
		finishSupplyTransfer();
	}

	private boolean queueConsumableTransfer(Set<Item> accepted, int needed, String name) {
		int chestSlots = supplyChestSlots();
		for (int sourceSlot = 0; sourceSlot < chestSlots; sourceSlot++) {
			ItemStack source = baritone.getPlayerContext().player().containerMenu.getSlot(sourceSlot).getItem();
			if (source.isEmpty() || !accepted.contains(source.getItem())) continue;
			int targetInventorySlot = findInventoryTarget(source);
			if (targetInventorySlot < 0) {
				transition(AutomationState.ERROR, Translations.DETAIL.message("no_inventory_space", name));
				return false;
			}
			ItemStack target = baritone.getPlayerContext().player().getInventory().getItem(targetInventorySlot);
			int capacity = target.isEmpty() ? source.getMaxStackSize() : target.getMaxStackSize() - target.getCount();
			int transfer = Math.min(needed, Math.min(source.getCount(), capacity));
			boolean mergeWhole = transfer == source.getCount() || !target.isEmpty() && transfer == capacity;
			queueExactChestTransfer(sourceSlot, chestInventoryMenuSlot(targetInventorySlot, chestSlots), transfer, source.getCount(), mergeWhole);
			synchronize(supplyInteractionState(), Translations.DETAIL.message("supply_transferring", transfer, name));
			return false;
		}
		transition(AutomationState.ERROR, Translations.DETAIL.message("supply_insufficient", name));
		return false;
	}

	private void queueExactChestTransfer(int sourceSlot, int targetSlot, int count, int sourceCount, boolean mergeWhole) {
		inventoryClicks.add(new InventoryClick(sourceSlot, 0, ContainerInput.PICKUP));
		if (mergeWhole) inventoryClicks.add(new InventoryClick(targetSlot, 0, ContainerInput.PICKUP));
		else for (int index = 0; index < count; index++) inventoryClicks.add(new InventoryClick(targetSlot, 1, ContainerInput.PICKUP));
		if (count < sourceCount) inventoryClicks.add(new InventoryClick(sourceSlot, 0, ContainerInput.PICKUP));
	}

	private void transferDurabilityItems() {
		int chestSlots = supplyChestSlots();
		Inventory inventory = baritone.getPlayerContext().player().getInventory();
		for (int slot = 0; slot < 36; slot++) {
			if (isLowMonitoredItem(inventory.getItem(slot))) {
				inventoryClicks.add(new InventoryClick(chestInventoryMenuSlot(slot, chestSlots), 0, ContainerInput.QUICK_MOVE));
				return;
			}
		}
		for (Map.Entry<Item, Integer> requirement : supply.durabilityPlan.targetHealthyCounts().entrySet()) {
			if (healthyOwnedCount(requirement.getKey()) >= requirement.getValue()) continue;
			for (int chestSlot = 0; chestSlot < chestSlots; chestSlot++) {
				ItemStack source = baritone.getPlayerContext().player().containerMenu.getSlot(chestSlot).getItem();
				if (source.is(requirement.getKey()) && remainingDurability(source) > durabilityThreshold(requirement.getKey())) {
					inventoryClicks.add(new InventoryClick(chestSlot, 0, ContainerInput.QUICK_MOVE));
					return;
				}
			}
			transition(AutomationState.ERROR, Translations.DETAIL.message("no_healthy_replacement", BuiltInRegistries.ITEM.getKey(requirement.getKey())));
			return;
		}
		baritone.getPlayerContext().player().closeContainer();
		supply.phase = SupplyFlow.Phase.FINALIZING;
	}

	private void finalizeSupplyEquipment() {
		if (!(baritone.getPlayerContext().player().containerMenu instanceof InventoryMenu)) return;
		Inventory inventory = baritone.getPlayerContext().player().getInventory();
		if (supply.durabilityPlan.chestItem() != null) {
			ItemStack equipped = baritone.getPlayerContext().player().getItemBySlot(EquipmentSlot.CHEST);
			if (!equipped.is(supply.durabilityPlan.chestItem()) || remainingDurability(equipped) <= durabilityThreshold(equipped)) {
				int source = findHealthyInventoryItem(supply.durabilityPlan.chestItem());
				if (source < 0) {
					transition(AutomationState.ERROR, Translations.DETAIL.message("equip_elytra_failed"));
					return;
				}
				queuePickupSwap(menuSlot(source), CHEST_MENU_SLOT);
				return;
			}
		}
		if (supply.durabilityPlan.offhandItem() != null) {
			ItemStack offhand = inventory.getItem(OFFHAND_INVENTORY_SLOT);
			if (!offhand.is(supply.durabilityPlan.offhandItem()) || remainingDurability(offhand) <= durabilityThreshold(offhand)) {
				int source = findHealthyInventoryItem(supply.durabilityPlan.offhandItem());
				if (source < 0) {
					transition(AutomationState.ERROR, Translations.DETAIL.message("equip_offhand_failed"));
					return;
				}
				queuePickupSwap(menuSlot(source), OFFHAND_MENU_SLOT);
				return;
			}
		}
		if (supply.durabilityPlan.selectedItem() != null) {
			inventory.setSelectedSlot(supply.durabilityPlan.selectedSlot());
			ItemStack selected = inventory.getItem(supply.durabilityPlan.selectedSlot());
			if (!selected.is(supply.durabilityPlan.selectedItem()) || remainingDurability(selected) <= durabilityThreshold(selected)) {
				int source = findHealthyInventoryItem(supply.durabilityPlan.selectedItem());
				if (source < 0) {
					transition(AutomationState.ERROR, Translations.DETAIL.message("equip_tool_failed"));
					return;
				}
				baritone.getPlayerContext().playerController().windowClick(
						baritone.getPlayerContext().player().inventoryMenu.containerId,
						menuSlot(source), supply.durabilityPlan.selectedSlot(), ContainerInput.SWAP,
						baritone.getPlayerContext().player());
				return;
			}
		}
		finishSupplyTransfer();
	}

	private void finishSupplyTransfer() {
		if (!(baritone.getPlayerContext().player().containerMenu instanceof InventoryMenu)) {
			baritone.getPlayerContext().player().closeContainer();
		}
		supply.point = null;
		supply.stand = null;
		supply.phase = null;
		beginReturnToMine();
	}

	private void beginSleepFlow(boolean debug) {
		if (bedPoint == null) return;
		sleep.previousState = state;
		sleep.debugOnly = debug;
		miningReturnPoint = baritone.getPlayerContext().playerFeet();
		miningReturnDimension = baritone.getPlayerContext().world().dimension();
		returnAction = ReturnAction.AFTER_SLEEP;
		sleep.bed = position(bedPoint);
		sleep.stand = closestBedStand(sleep.bed).orElse(sleep.bed.above());
		sleep.flying = false;
		sleep.flightAttempts = 0;
		sleep.interactionTicks = 0;
		sleep.entered = false;
		stopProcesses();
		if (baritone.getPlayerContext().playerFeet().distSqr(sleep.stand) > navigationFlightDistanceSquared() && canFlyToSupply()) {
			startFlyingToBed();
		} else {
			startWalkingToBed();
		}
	}

	private void startWalkingToBed() {
		sleep.flying = false;
		disablePlacementForNavigation();
		if (isAtBedStand(sleep.bed)) {
			restoreAllowPlace();
			beginSleeping();
			return;
		}
		List<BlockPos> stands = bedStandPositions(sleep.bed);
		if (!stands.isEmpty()) {
			sleep.stand = stands.stream().min(Comparator.comparingDouble(baritone.getPlayerContext().playerFeet()::distSqr)).orElseThrow();
			navigation.walk(new GoalComposite(stands.stream().map(GoalBlock::new).toArray(Goal[]::new)));
		} else {
			sleep.stand = sleep.bed.above();
			navigation.walk(new GoalBlock(sleep.stand));
		}
		transition(AutomationState.NAVIGATING_TO_BED, Translations.DETAIL.message("walking_bed", sleep.bed.toShortString()));
	}

	private void startFlyingToBed() {
		restoreAllowPlace();
		if (savedElytraMinFireworksBeforeLanding == null) {
			savedElytraMinFireworksBeforeLanding = BaritoneAPI.getSettings().elytraMinFireworksBeforeLanding.value;
		}
		BaritoneAPI.getSettings().elytraMinFireworksBeforeLanding.value = -1;
		if (savedElytraMinimumDurability == null) savedElytraMinimumDurability = BaritoneAPI.getSettings().elytraMinimumDurability.value;
		BaritoneAPI.getSettings().elytraMinimumDurability.value = advanced.emergencyFlightDurabilityThreshold;
		sleep.flying = true;
		sleep.flightAttempts++;
		navigation.fly(sleep.stand);
		transition(AutomationState.NAVIGATING_TO_BED, Translations.DETAIL.message("flying_bed", sleep.bed.toShortString()));
	}

	private void tickBedNavigation() {
		if (!isBedTime()) {
			stopProcesses();
			restoreElytraMinimumDurability();
			restoreElytraFireworkReserve();
			restoreAllowPlace();
			beginReturnToMine();
			return;
		}
		if (isAtBedStand(sleep.bed)) {
			stopProcesses();
			restoreElytraMinimumDurability();
			restoreElytraFireworkReserve();
			restoreAllowPlace();
			beginSleeping();
			return;
		}
		if (sleep.flying) {
			if (navigation.isFlying()) return;
			restoreElytraMinimumDurability();
			restoreElytraFireworkReserve();
			startWalkingToBed();
			return;
		}
		if (navigation.isWalking()) return;
		if (sleep.stand.getY() > baritone.getPlayerContext().playerFeet().y
				&& sleep.flightAttempts < advanced.flightRetryCount && canFlyToSupply()) {
			startFlyingToBed();
			return;
		}
		restoreAllowPlace();
		transition(AutomationState.ERROR, Translations.DETAIL.message("bed_unreachable", sleep.bed.toShortString()));
	}

	private void beginSleeping() {
		var world = Minecraft.getInstance().level;
		if (world == null || !world.isLoaded(sleep.bed) || !(world.getBlockState(sleep.bed).getBlock() instanceof BedBlock)) {
			transition(AutomationState.ERROR, Translations.DETAIL.message("configured_bed_invalid", sleep.bed.toShortString()));
			return;
		}
		sleep.interactionTicks = 0;
		sleep.entered = false;
		transition(AutomationState.SLEEPING, Translations.DETAIL.message("entering_bed", sleep.bed.toShortString()));
	}

	private void tickSleeping() {
		if (baritone.getPlayerContext().player().isSleeping()) {
			sleep.entered = true;
			synchronize(AutomationState.SLEEPING, Translations.DETAIL.message("sleeping"));
			return;
		}
		if (sleep.entered || !isBedTime()) {
			beginReturnToMine();
			return;
		}
		if (sleep.interactionTicks++ > ticks(advanced.supplyInteractionTimeoutSeconds)) {
			transition(AutomationState.ERROR, Translations.DETAIL.message("bed_timeout", sleep.bed.toShortString()));
			return;
		}
		Vec3 hitLocation = Vec3.atCenterOf(sleep.bed);
		baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(
				baritone.getPlayerContext().playerHead(), hitLocation, baritone.getPlayerContext().playerRotations()), true);
		if (sleep.interactionTicks % 5 == 1) {
			baritone.getPlayerContext().playerController().processRightClickBlock(
					baritone.getPlayerContext().player(), baritone.getPlayerContext().world(), InteractionHand.MAIN_HAND,
					new BlockHitResult(hitLocation, Direction.UP, sleep.bed, false));
		}
	}

	private boolean isBedTime() {
		var world = Minecraft.getInstance().level;
		if (world == null || !world.dimension().equals(Level.OVERWORLD)) return false;
		var clock = world.dimensionType().defaultClock();
		if (clock.isEmpty()) return false;
		long dayTime = Math.floorMod(world.clockManager().getTotalTicks(clock.get()), 24000L);
		return dayTime >= 12542L && dayTime < 23460L;
	}

	private void clearSleepContext() {
		restoreElytraMinimumDurability();
		restoreElytraFireworkReserve();
		restoreAllowPlace();
		sleep.reset();
	}

	private void beginFurnaceInteraction() {
		restoreRepairNavigationSettings();
		if (repair.machineTakeoffPoint == null) repair.machineTakeoffPoint = baritone.getPlayerContext().playerFeet();
		repair.furnacePhase = RepairFlow.FurnacePhase.PREPARING;
		repair.interactionTicks = 0;
		transition(AutomationState.REPAIRING, Translations.DETAIL.message("preparing_furnace",
				repair.furnaceIndex + 1, repair.furnaces.size(), incompleteRepairTargets()));
	}

	private void tickRepairing() {
		if (!inventoryClicks.isEmpty()) return;
		switch (repair.furnacePhase) {
			case PREPARING -> prepareRepairItem();
			case OPENING -> openRepairFurnace();
			case TAKING_OUTPUT -> takeRepairOutput();
			case WAITING_FOR_REPAIR -> finishRepairFurnace();
		}
	}

	private void prepareRepairItem() {
		selectNextRepairTool();
		repair.furnacePhase = RepairFlow.FurnacePhase.OPENING;
		repair.interactionTicks = 0;
	}

	private boolean selectNextRepairTool() {
		Inventory inventory = baritone.getPlayerContext().player().getInventory();
		int selected = inventory.getSelectedSlot();
		ItemStack selectedStack = inventory.getItem(selected);
		if (!selectedStack.isEmpty() && selectedStack.getItem().components().has(DataComponents.TOOL)
				&& repairItemNeedsMoreFullStacks(selectedStack.getItem())
				&& remainingDurability(selectedStack) < selectedStack.getMaxDamage()) return false;
		int candidate = -1;
		int durability = Integer.MAX_VALUE;
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty() && stack.getItem().components().has(DataComponents.TOOL)
					&& repairItemNeedsMoreFullStacks(stack.getItem()) && remainingDurability(stack) < stack.getMaxDamage()
					&& remainingDurability(stack) < durability) {
				candidate = slot;
				durability = remainingDurability(stack);
			}
		}
		boolean changed = candidate >= 0 && candidate != selected;
		if (changed) {
			if (candidate < 9) inventory.setSelectedSlot(candidate);
			else baritone.getPlayerContext().playerController().windowClick(
					baritone.getPlayerContext().player().inventoryMenu.containerId,
					menuSlot(candidate), selected, ContainerInput.SWAP, baritone.getPlayerContext().player());
		}
		baritone.getPlayerContext().playerController().syncHeldItem();
		return changed;
	}

	private void openRepairFurnace() {
		if (baritone.getPlayerContext().player().containerMenu instanceof AbstractFurnaceMenu) {
			repair.furnacePhase = RepairFlow.FurnacePhase.TAKING_OUTPUT;
			return;
		}
		if (!(baritone.getPlayerContext().player().containerMenu instanceof InventoryMenu)) {
			baritone.getPlayerContext().player().closeContainer();
			return;
		}
		BlockPos furnace = repair.furnaces.get(repair.furnaceIndex);
		if (!canReachFurnace(furnace)) {
			startCurrentFurnaceNavigation();
			return;
		}
		if (repair.interactionTicks++ > ticks(advanced.furnaceInteractionTimeoutSeconds)) {
			transition(AutomationState.ERROR, Translations.DETAIL.message("furnace_open_timeout",
					repair.furnaceIndex + 1, furnace.toShortString(), repair.navigationTarget.toShortString()));
			return;
		}
		Vec3 hitLocation = Vec3.atCenterOf(furnace);
		baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(
				baritone.getPlayerContext().playerHead(), hitLocation, baritone.getPlayerContext().playerRotations()), true);
		if (repair.interactionTicks % 5 == 1) {
			baritone.getPlayerContext().playerController().processRightClickBlock(
					baritone.getPlayerContext().player(), baritone.getPlayerContext().world(), InteractionHand.MAIN_HAND,
					new BlockHitResult(hitLocation, Direction.UP, furnace, false));
		}
	}

	private void takeRepairOutput() {
		if (!(baritone.getPlayerContext().player().containerMenu instanceof AbstractFurnaceMenu menu)) {
			transition(AutomationState.ERROR, Translations.DETAIL.message("furnace_closed"));
			return;
		}
		ItemStack output = menu.getSlot(2).getItem();
		if (output.isEmpty()) {
			baritone.getPlayerContext().player().closeContainer();
			advanceRepairFurnace();
			return;
		}
		inventoryClicks.add(new InventoryClick(2, 1, ContainerInput.THROW));
		repair.furnacePhase = RepairFlow.FurnacePhase.WAITING_FOR_REPAIR;
		repair.durabilitySnapshot = repairDurabilityTotal();
		repair.stableTicks = 0;
			synchronize(AutomationState.REPAIRING, Translations.DETAIL.message("collecting_experience"));
	}

	private void finishRepairFurnace() {
		if (repairTargetsFull()) {
			if (!(baritone.getPlayerContext().player().containerMenu instanceof InventoryMenu)) {
				baritone.getPlayerContext().player().closeContainer();
			}
			beginRepairReturn();
			return;
		}
		if (selectNextRepairTool()) {
			repair.stableTicks = 0;
			synchronize(AutomationState.REPAIRING, Translations.DETAIL.message("switched_repair_tool"));
			return;
		}
		int durability = repairDurabilityTotal();
		if (durability > repair.durabilitySnapshot) {
			repair.durabilitySnapshot = durability;
			repair.stableTicks = 0;
			return;
		}
		if (++repair.stableTicks < ticks(advanced.repairExperienceStableSeconds)) return;
		if (!(baritone.getPlayerContext().player().containerMenu instanceof InventoryMenu)) {
			baritone.getPlayerContext().player().closeContainer();
		}
		advanceRepairFurnace();
	}

	private void advanceRepairFurnace() {
		if (++repair.furnaceIndex >= repair.furnaces.size()) {
			if (!repairTargetsFull()) transition(AutomationState.ERROR, Translations.DETAIL.message("furnaces_exhausted"));
			else beginRepairReturn();
			return;
		}
		repair.stage = RepairFlow.Stage.REPAIR_MACHINE;
		startCurrentFurnaceNavigation();
	}

	private RepairFlow.Plan captureRepairPlan(boolean includeAllDamaged) {
		Map<Item, Integer> targets = new LinkedHashMap<>();
		Map<Item, Integer> baselineFull = new LinkedHashMap<>();
		Inventory inventory = baritone.getPlayerContext().player().getInventory();
		for (int slot = 0; slot < 36; slot++) collectRepairPlanItem(inventory.getItem(slot), targets, baselineFull, includeAllDamaged);
		collectRepairPlanItem(inventory.getItem(OFFHAND_INVENTORY_SLOT), targets, baselineFull, includeAllDamaged);
		collectRepairPlanItem(baritone.getPlayerContext().player().getItemBySlot(EquipmentSlot.CHEST), targets, baselineFull, includeAllDamaged);
		return new RepairFlow.Plan(Map.copyOf(targets), Map.copyOf(baselineFull));
	}

	private void collectRepairPlanItem(ItemStack stack, Map<Item, Integer> targets, Map<Item, Integer> baselineFull, boolean includeAllDamaged) {
		if (!isMonitoredItem(stack)) return;
		if (remainingDurability(stack) <= durabilityThreshold(stack)
				|| includeAllDamaged && remainingDurability(stack) < stack.getMaxDamage()) targets.merge(stack.getItem(), 1, Integer::sum);
		else if (remainingDurability(stack) == stack.getMaxDamage()) baselineFull.merge(stack.getItem(), 1, Integer::sum);
	}

	private boolean repairTargetsFull() {
		for (Map.Entry<Item, Integer> target : repair.plan.targetCounts().entrySet()) {
			int required = repair.plan.baselineFullCounts().getOrDefault(target.getKey(), 0) + target.getValue();
			if (fullMonitoredItemCount(target.getKey()) < required) return false;
		}
		return true;
	}

	private String incompleteRepairTargets() {
		List<String> targets = new ArrayList<>();
		for (Map.Entry<Item, Integer> target : repair.plan.targetCounts().entrySet()) {
			int required = repair.plan.baselineFullCounts().getOrDefault(target.getKey(), 0) + target.getValue();
			int full = fullMonitoredItemCount(target.getKey());
			if (full < required) targets.add(BuiltInRegistries.ITEM.getKey(target.getKey()) + " full=" + full + "/" + required);
		}
		return targets.isEmpty() ? "none" : String.join(", ", targets);
	}

	private boolean repairItemNeedsMoreFullStacks(Item item) {
		Integer targets = repair.plan.targetCounts().get(item);
		if (targets == null) return false;
		return fullMonitoredItemCount(item) < repair.plan.baselineFullCounts().getOrDefault(item, 0) + targets;
	}

	private int fullMonitoredItemCount(Item item) {
		int count = 0;
		Inventory inventory = baritone.getPlayerContext().player().getInventory();
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(item) && remainingDurability(stack) == stack.getMaxDamage()) count++;
		}
		ItemStack offhand = inventory.getItem(OFFHAND_INVENTORY_SLOT);
		if (offhand.is(item) && remainingDurability(offhand) == offhand.getMaxDamage()) count++;
		ItemStack chest = baritone.getPlayerContext().player().getItemBySlot(EquipmentSlot.CHEST);
		if (chest.is(item) && remainingDurability(chest) == chest.getMaxDamage()) count++;
		return count;
	}

	private int repairDurabilityTotal() {
		int total = 0;
		Inventory inventory = baritone.getPlayerContext().player().getInventory();
		for (int slot = 0; slot < 36; slot++) total += repairDurability(inventory.getItem(slot));
		total += repairDurability(inventory.getItem(OFFHAND_INVENTORY_SLOT));
		total += repairDurability(baritone.getPlayerContext().player().getItemBySlot(EquipmentSlot.CHEST));
		return total;
	}

	private int repairDurability(ItemStack stack) {
		return stack.isEmpty() || !repair.plan.targetCounts().containsKey(stack.getItem()) ? 0 : remainingDurability(stack);
	}

	private void beginRepairReturn() {
		if (!(baritone.getPlayerContext().player().containerMenu instanceof InventoryMenu)) {
			baritone.getPlayerContext().player().closeContainer();
		}
		if (repair.machineTakeoffPoint != null && !baritone.getPlayerContext().playerFeet().equals(repair.machineTakeoffPoint)) {
			repair.stage = RepairFlow.Stage.RETURN_TO_MACHINE_TAKEOFF;
			repair.navigationTarget = repair.machineTakeoffPoint;
			repair.flying = false;
			repair.flightAttempts = advanced.flightRetryCount;
			startWalkingForRepair(AutomationState.NAVIGATING_TO_REPAIR_MACHINE);
			return;
		}
		startRepairPortalReturn();
	}

	private void startRepairPortalReturn() {
		repair.stage = RepairFlow.Stage.RETURN_TO_MINE;
		beginReturnToMine();
	}

	private SupplyFlow.DurabilityPlan captureDurabilitySupplyPlan() {
		Inventory inventory = baritone.getPlayerContext().player().getInventory();
		Map<Item, Integer> lowCounts = new LinkedHashMap<>();
		Map<Item, Integer> healthyCounts = new LinkedHashMap<>();
		for (int slot = 0; slot < 36; slot++) collectDurabilityCount(inventory.getItem(slot), lowCounts, healthyCounts);
		collectDurabilityCount(inventory.getItem(OFFHAND_INVENTORY_SLOT), lowCounts, healthyCounts);
		collectDurabilityCount(baritone.getPlayerContext().player().getItemBySlot(EquipmentSlot.CHEST), lowCounts, healthyCounts);
		Map<Item, Integer> targets = new LinkedHashMap<>();
		lowCounts.forEach((item, count) -> targets.put(item, count + healthyCounts.getOrDefault(item, 0)));
		ItemStack chest = baritone.getPlayerContext().player().getItemBySlot(EquipmentSlot.CHEST);
		ItemStack offhand = inventory.getItem(OFFHAND_INVENTORY_SLOT);
		int selectedSlot = inventory.getSelectedSlot();
		ItemStack selected = inventory.getItem(selectedSlot);
		return new SupplyFlow.DurabilityPlan(
				targets,
				isLowMonitoredItem(chest) ? chest.getItem() : null,
				isLowMonitoredItem(offhand) ? offhand.getItem() : null,
				isLowMonitoredItem(selected) ? selected.getItem() : null,
				selectedSlot
		);
	}

	private void collectDurabilityCount(ItemStack stack, Map<Item, Integer> lowCounts, Map<Item, Integer> healthyCounts) {
		if (!isMonitoredItem(stack)) return;
		Map<Item, Integer> target = remainingDurability(stack) <= durabilityThreshold(stack) ? lowCounts : healthyCounts;
		target.merge(stack.getItem(), 1, Integer::sum);
	}

	private boolean usesElytraNavigation() {
		return elytraNavigationEnabled && !navigation.canUseCreativeFlight();
	}

	private boolean isMonitoredItem(ItemStack stack) {
		return !stack.isEmpty() && stack.isDamageableItem()
				&& (stack.getItem().components().has(DataComponents.TOOL)
				|| usesElytraNavigation() && stack.is(Items.ELYTRA));
	}

	private boolean isLowMonitoredItem(ItemStack stack) {
		return isMonitoredItem(stack) && remainingDurability(stack) <= durabilityThreshold(stack);
	}

	private int configuredFoodCount() {
		return inventoryCount(allowedFoods);
	}

	private int fireworkCount() {
		return inventoryCount(Set.of(Items.FIREWORK_ROCKET));
	}

	private int inventoryCount(Set<Item> accepted) {
		int count = 0;
		Inventory inventory = baritone.getPlayerContext().player().getInventory();
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (accepted.contains(stack.getItem())) count += stack.getCount();
		}
		return count;
	}

	private int firstEmptyInventorySlot() {
		Inventory inventory = baritone.getPlayerContext().player().getInventory();
		for (int slot = 0; slot < 36; slot++) if (inventory.getItem(slot).isEmpty()) return slot;
		return -1;
	}

	private int findInventoryTarget(ItemStack source) {
		Inventory inventory = baritone.getPlayerContext().player().getInventory();
		for (int slot = 0; slot < 36; slot++) {
			ItemStack target = inventory.getItem(slot);
			if (ItemStack.isSameItemSameComponents(source, target) && target.getCount() < target.getMaxStackSize()) return slot;
		}
		return firstEmptyInventorySlot();
	}

	private int healthyOwnedCount(Item item) {
		int count = healthyInventoryCount(item);
		ItemStack offhand = baritone.getPlayerContext().player().getInventory().getItem(OFFHAND_INVENTORY_SLOT);
		if (offhand.is(item) && remainingDurability(offhand) > durabilityThreshold(item)) count++;
		ItemStack chest = baritone.getPlayerContext().player().getItemBySlot(EquipmentSlot.CHEST);
		if (chest.is(item) && remainingDurability(chest) > durabilityThreshold(item)) count++;
		return count;
	}

	private int healthyInventoryCount(Item item) {
		int count = 0;
		Inventory inventory = baritone.getPlayerContext().player().getInventory();
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(item) && remainingDurability(stack) > durabilityThreshold(item)) count++;
		}
		return count;
	}

	private int findHealthyInventoryItem(Item item) {
		Inventory inventory = baritone.getPlayerContext().player().getInventory();
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(item) && remainingDurability(stack) > durabilityThreshold(item)) return slot;
		}
		return -1;
	}

	private int supplyChestSlots() {
		int slots = baritone.getPlayerContext().player().containerMenu.slots.size() - 36;
		if (slots <= 0) throw new IllegalStateException("The opened container has no supply storage slots.");
		return slots;
	}

	private void clearSupplyContext() {
		restoreAllowPlace();
		restoreElytraMinimumDurability();
		restoreElytraFireworkReserve();
		supply.reset();
		returnAction = ReturnAction.NONE;
	}

	private void clearRepairContext() {
		restoreRepairNavigationSettings();
		restoreRepairRestrictions();
		repair.reset();
	}

	private static int chestInventoryMenuSlot(int inventorySlot, int chestSlots) {
		return chestSlots + (inventorySlot < 9 ? 27 + inventorySlot : inventorySlot - 9);
	}

	private void requestUnload() {
		if (!unload.debugOnly && !hasConfiguredUnload()) {
			navigation.stopWalking();
			restoreAllowPlace();
			if (miningCompletePending) transition(AutomationState.COMPLETE, Translations.DETAIL.message("mining_complete_unload_disabled"));
			else startMiningCycle();
			return;
		}
		if (!unload.debugOnly) {
			miningReturnPoint = baritone.getPlayerContext().playerFeet();
			miningReturnDimension = baritone.getPlayerContext().world().dimension();
			returnAction = ReturnAction.AFTER_UNLOAD;
		}
		stopProcesses();
		if (unloadingPoints.isEmpty()) {
			restoreAllowPlace();
			transition(AutomationState.ERROR, Translations.DETAIL.message("unload_point_missing"));
			return;
		}
		BetterBlockPos player = baritone.getPlayerContext().playerFeet();
		unload.point = unloadingPoints.stream()
				.min(Comparator.comparingLong(point -> horizontalDistanceSquared(player, point.point())))
				.orElseThrow();
		int preferredY = Math.max(player.y, unload.point.point().minY);
		UnloadFlow.Candidate preferred = findUnloadCandidates(unload.point.point(), preferredY).stream().findFirst().orElse(null);
		BlockPos flightTarget = preferred == null
				? new BlockPos(unload.point.point().x, player.y, unload.point.point().z)
				: preferred.position();
		unload.flightAttempts = 0;
		if (player.distSqr(flightTarget) <= navigationFlightDistanceSquared() || !canFlyToDestination()) {
			beginUnloadApproach();
			return;
		}
		startFlyingToUnload(flightTarget);
	}

	private void tickNavigateToUnload() {
		if (navigation.isFlying()) {
			synchronize(AutomationState.NAVIGATING_TO_UNLOAD, Translations.DETAIL.message("flying_unload", unload.point.name()));
			return;
		}
		beginUnloadApproach();
	}

	private void beginUnloadApproach() {
		navigation.stopFlying();
		disablePlacementForNavigation();
		int currentY = baritone.getPlayerContext().playerFeet().y;
		int preferredY = Math.max(currentY, unload.point.point().minY);
		unload.candidates = findUnloadCandidates(unload.point.point(), preferredY);
		unload.candidateIndex = 0;
		if (unload.candidates.isEmpty()) {
			restoreAllowPlace();
			transition(AutomationState.ERROR, Translations.DETAIL.message("unload_no_loaded_stand", unload.point.name()));
			return;
		}
		pathToUnloadCandidate();
	}

	private void pathToUnloadCandidate() {
		UnloadFlow.Candidate candidate = unload.candidates.get(unload.candidateIndex);
		navigation.walk(new GoalBlock(candidate.position()));
		transition(AutomationState.APPROACHING_UNLOAD, Translations.DETAIL.message("walking_unload_edge", candidate.position().toShortString(), unload.point.name()));
	}

	private void tickApproachingUnload() {
		UnloadFlow.Candidate candidate = unload.candidates.get(unload.candidateIndex);
		if (candidate.position().equals(baritone.getPlayerContext().playerFeet())) {
			navigation.stopWalking();
			restoreAllowPlace();
			unload.edgePosition = unloadEdgePosition(candidate.position(), unload.point.point());
			unload.positioningTicks = 0;
			transition(AutomationState.POSITIONING_FOR_UNLOAD, Translations.DETAIL.message("positioning_unload", unload.point.name()));
			return;
		}
		if (navigation.isWalking()) return;
		if (candidate.position().getY() > baritone.getPlayerContext().playerFeet().y
				&& unload.flightAttempts < advanced.flightRetryCount && canFlyToDestination()) {
			startFlyingToUnload(candidate.position());
			return;
		}
		if (++unload.candidateIndex >= unload.candidates.size()) {
			restoreAllowPlace();
			transition(AutomationState.ERROR, Translations.DETAIL.message("unload_no_reachable_stand", unload.point.name()));
			return;
		}
		pathToUnloadCandidate();
	}

	private void startFlyingToUnload(BlockPos target) {
		restoreAllowPlace();
		unload.flightAttempts++;
		navigation.fly(target);
		transition(AutomationState.NAVIGATING_TO_UNLOAD, Translations.DETAIL.message("flying_unload_xz", unload.point.name(), unload.point.point().x, unload.point.point().z));
	}

	private void tickPositioningForUnload() {
		var player = baritone.getPlayerContext().player();
		Vec3 playerPosition = player.position();
		double dx = unload.edgePosition.x - playerPosition.x;
		double dz = unload.edgePosition.z - playerPosition.z;
		double distanceSquared = dx * dx + dz * dz;
		unload.positioningTicks++;
		if (player.getAbilities().flying) {
			baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
			baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
			BlockPos feet = baritone.getPlayerContext().playerFeet();
			if (isSafeStandingPosition(feet)) {
				player.getAbilities().flying = false;
				player.onUpdateAbilities();
				return;
			}
			// Hovering without safe floor directly beneath: never press SNEAK while flying (would descend into pit).
			if (distanceSquared <= 0.36 || unload.positioningTicks >= UNLOAD_POSITIONING_TIMEOUT_TICKS) {
				unload.settleTicks = 5;
				unload.positioningTicks = 0;
				transition(AutomationState.UNLOADING, Translations.DETAIL.message("facing_unload", unload.point.name()));
				return;
			}
			Vec3 lookTarget = new Vec3(unload.edgePosition.x, baritone.getPlayerContext().playerHead().y, unload.edgePosition.z);
			baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(
					baritone.getPlayerContext().playerHead(), lookTarget, baritone.getPlayerContext().playerRotations()), false);
			baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
			return;
		}
		if (!player.onGround() && unload.positioningTicks < 10) {
			baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
			baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
			return;
		}
		baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
		boolean timedOut = unload.positioningTicks >= UNLOAD_POSITIONING_TIMEOUT_TICKS;
		if (distanceSquared > 0.0016 && !(timedOut && distanceSquared <= 0.36)) {
			if (timedOut) {
				baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
				baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
				if (++unload.candidateIndex >= unload.candidates.size()) {
					transition(AutomationState.ERROR, Translations.DETAIL.message("unload_no_reachable_stand", unload.point.name()));
					return;
				}
				pathToUnloadCandidate();
				return;
			}
			Vec3 lookTarget = new Vec3(unload.edgePosition.x, baritone.getPlayerContext().playerHead().y, unload.edgePosition.z);
			baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(
					baritone.getPlayerContext().playerHead(), lookTarget, baritone.getPlayerContext().playerRotations()), false);
			baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
			synchronize(AutomationState.POSITIONING_FOR_UNLOAD, Translations.DETAIL.message("positioning_unload", unload.point.name()));
			return;
		}
		baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
		unload.settleTicks = 5;
		unload.positioningTicks = 0;
		transition(AutomationState.UNLOADING, Translations.DETAIL.message("facing_unload", unload.point.name()));
	}

	private void tickUnloading() {
		UnloadingPointConfig point = unload.point.point();
		Vec3 target = new Vec3(point.x + 0.5, point.minY + 0.5, point.z + 0.5);
		var player = baritone.getPlayerContext().player();
		baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, !player.getAbilities().flying);
		baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
		baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(
				baritone.getPlayerContext().playerHead(), target, baritone.getPlayerContext().playerRotations()).withPitch(90.0F), false);
		if (!inventoryClicks.isEmpty()) return;
		if (unload.settleTicks > 0) {
			unload.settleTicks--;
			return;
		}
		int disposableSlot = firstDisposableSlot();
		if (disposableSlot >= 0) {
			inventoryClicks.add(new InventoryClick(menuSlot(disposableSlot), 1, ContainerInput.THROW));
			unload.settleTicks = 1;
			synchronize(AutomationState.UNLOADING, Translations.DETAIL.message("dropping_unload", unload.point.name()));
			return;
		}
		boolean debug = unload.debugOnly;
		unload.reset();
		baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
		if (debug) {
			transition(AutomationState.COMPLETE, Translations.DETAIL.message("unload_debug_complete"));
		} else if (miningCompletePending) transition(AutomationState.COMPLETE, Translations.DETAIL.message("mining_unload_complete"));
		else beginReturnToMine();
	}

	private void beginReturnToMine() {
		if (miningReturnPoint == null) {
			startMiningCycle();
			return;
		}
		activeReturnWaypoint = chooseReturnWaypoint();
		activeReturnTarget = positionForReturnWaypoint(activeReturnWaypoint);
		returnFlightAttempts = 0;
		BetterBlockPos player = baritone.getPlayerContext().playerFeet();
		if (player.distSqr(activeReturnTarget) > navigationFlightDistanceSquared() && canFlyForReturn()) {
			startFlyingBackToMine();
			return;
		}
		startWalkingBackToMine();
	}

	private ReturnWaypoint chooseReturnWaypoint() {
		ResourceKey<Level> currentDimension = baritone.getPlayerContext().world().dimension();
		BlockPos current = baritone.getPlayerContext().playerFeet();
		if (repair.stage != null && !repair.crossDimension) {
			if (!currentDimension.equals(miningReturnDimension)) {
				throw new IllegalStateException("Local repair cannot return across dimensions.");
			}
			return ReturnWaypoint.MINE;
		}
		List<ReturnRouteCandidate> candidates = new ArrayList<>();
		if (currentDimension.equals(miningReturnDimension)) {
			candidates.add(new ReturnRouteCandidate(ReturnWaypoint.MINE, distance(current, miningReturnPoint)));
		}
		if (hasReturnPortalConfiguration()) {
			if (miningReturnDimension.equals(Level.OVERWORLD)) {
				if (currentDimension.equals(Level.OVERWORLD)) {
					candidates.add(new ReturnRouteCandidate(ReturnWaypoint.REPAIR_OVERWORLD,
						distance(current, position(repairPortalOverworld)) + advanced.portalTransitionCost
								+ distance(position(repairPortalNether), position(perimeterPortalNether)) + advanced.portalTransitionCost
									+ distance(position(perimeterPortalOverworld), miningReturnPoint)));
					candidates.add(new ReturnRouteCandidate(ReturnWaypoint.PERIMETER_OVERWORLD,
						distance(current, position(perimeterPortalOverworld)) + advanced.portalTransitionCost
								+ distance(position(perimeterPortalNether), position(repairPortalNether)) + advanced.portalTransitionCost
									+ distance(position(repairPortalOverworld), miningReturnPoint)));
				} else if (currentDimension.equals(Level.NETHER)) {
					candidates.add(new ReturnRouteCandidate(ReturnWaypoint.REPAIR_NETHER,
						distance(current, position(repairPortalNether)) + advanced.portalTransitionCost
									+ distance(position(repairPortalOverworld), miningReturnPoint)));
					candidates.add(new ReturnRouteCandidate(ReturnWaypoint.PERIMETER_NETHER,
						distance(current, position(perimeterPortalNether)) + advanced.portalTransitionCost
									+ distance(position(perimeterPortalOverworld), miningReturnPoint)));
				}
			} else if (miningReturnDimension.equals(Level.NETHER)) {
				if (currentDimension.equals(Level.NETHER)) {
					candidates.add(new ReturnRouteCandidate(ReturnWaypoint.REPAIR_NETHER,
						distance(current, position(repairPortalNether)) + advanced.portalTransitionCost
								+ distance(position(repairPortalOverworld), position(perimeterPortalOverworld)) + advanced.portalTransitionCost
									+ distance(position(perimeterPortalNether), miningReturnPoint)));
					candidates.add(new ReturnRouteCandidate(ReturnWaypoint.PERIMETER_NETHER,
						distance(current, position(perimeterPortalNether)) + advanced.portalTransitionCost
								+ distance(position(perimeterPortalOverworld), position(repairPortalOverworld)) + advanced.portalTransitionCost
									+ distance(position(repairPortalNether), miningReturnPoint)));
				} else if (currentDimension.equals(Level.OVERWORLD)) {
					candidates.add(new ReturnRouteCandidate(ReturnWaypoint.REPAIR_OVERWORLD,
						distance(current, position(repairPortalOverworld)) + advanced.portalTransitionCost
									+ distance(position(repairPortalNether), miningReturnPoint)));
					candidates.add(new ReturnRouteCandidate(ReturnWaypoint.PERIMETER_OVERWORLD,
						distance(current, position(perimeterPortalOverworld)) + advanced.portalTransitionCost
									+ distance(position(perimeterPortalNether), miningReturnPoint)));
				}
			}
		}
		return candidates.stream().min(Comparator.comparingDouble(ReturnRouteCandidate::cost))
				.map(ReturnRouteCandidate::waypoint)
				.orElseThrow(() -> new IllegalStateException("No return route exists from the current dimension to the mining departure point."));
	}

	private boolean hasReturnPortalConfiguration() {
		return perimeterPortalOverworld != null && perimeterPortalNether != null
				&& repairPortalOverworld != null && repairPortalNether != null;
	}

	private static double distance(BlockPos first, BlockPos second) {
		return Math.sqrt(first.distSqr(second));
	}

	private BlockPos positionForReturnWaypoint(ReturnWaypoint waypoint) {
		return switch (waypoint) {
			case MINE -> miningReturnPoint;
			case REPAIR_OVERWORLD -> position(repairPortalOverworld);
			case REPAIR_NETHER -> position(repairPortalNether);
			case PERIMETER_OVERWORLD -> position(perimeterPortalOverworld);
			case PERIMETER_NETHER -> position(perimeterPortalNether);
		};
	}

	private boolean canFlyForReturn() {
		return repair.stage != null ? canFlyForRepair() : canFlyToDestination();
	}

	private void startWalkingBackToMine() {
		returningByElytra = false;
		if (activeReturnWaypoint == ReturnWaypoint.PERIMETER_OVERWORLD || activeReturnWaypoint == ReturnWaypoint.PERIMETER_NETHER) {
			enableBreakingForNavigation();
		} else {
			disablePlacementForNavigation();
		}
		if (baritone.getPlayerContext().playerFeet().equals(activeReturnTarget)) {
			restoreAllowPlace();
			finishReturnWaypoint();
			return;
		}
		navigation.walk(new GoalBlock(activeReturnTarget));
		transition(AutomationState.RETURNING_TO_MINE, Translations.DETAIL.message("walking_return", activeReturnWaypoint.displayName, activeReturnTarget.toShortString()));
	}

	private void tickReturningToMine() {
		BetterBlockPos feet = baritone.getPlayerContext().playerFeet();
		boolean reached = feet.equals(activeReturnTarget)
				|| (activeReturnWaypoint == ReturnWaypoint.MINE && navigation.canUseCreativeFlight() && feet.distSqr(activeReturnTarget) <= 2.0);
		if (reached) {
			navigation.stopFlying();
			navigation.stopWalking();
			restoreRepairNavigationSettings();
			restoreAllowPlace();
			finishReturnWaypoint();
			return;
		}
		if (returningByElytra) {
			if (navigation.isFlying()) {
				synchronize(AutomationState.RETURNING_TO_MINE, Translations.DETAIL.message("flying_return", activeReturnWaypoint.displayName, activeReturnTarget.toShortString()));
				return;
			}
			restoreRepairNavigationSettings();
			startWalkingBackToMine();
			return;
		}
		if (navigation.isWalking()) return;
		if (returnFlightAttempts < advanced.flightRetryCount && canFlyForReturn()) {
			startFlyingBackToMine();
			return;
		}
		restoreAllowPlace();
		transition(AutomationState.ERROR, Translations.DETAIL.message("return_unreachable", activeReturnTarget.toShortString()));
	}

	private void startFlyingBackToMine() {
		restoreAllowPlace();
		if (activeReturnWaypoint == ReturnWaypoint.PERIMETER_OVERWORLD || activeReturnWaypoint == ReturnWaypoint.PERIMETER_NETHER) {
			enableBreakingForNavigation();
		}
		if (repair.stage != null) prepareRepairFlightSettings();
		returningByElytra = true;
		returnFlightAttempts++;
		navigation.fly(activeReturnTarget);
		transition(AutomationState.RETURNING_TO_MINE, Translations.DETAIL.message("flying_return", activeReturnWaypoint.displayName, activeReturnTarget.toShortString()));
	}

	private void finishReturnWaypoint() {
		if (activeReturnWaypoint == ReturnWaypoint.MINE) {
			finishReturnToMine();
			return;
		}
		returnPortalWaypoint = activeReturnWaypoint;
		repair.portalWaitTicks = 0;
		AutomationState portalState = activeReturnWaypoint == ReturnWaypoint.REPAIR_OVERWORLD || activeReturnWaypoint == ReturnWaypoint.REPAIR_NETHER
				? AutomationState.ENTERING_REPAIR_PORTAL : AutomationState.ENTERING_PERIMETER_PORTAL;
		transition(portalState, Translations.DETAIL.message("waiting_return_portal", activeReturnTarget.toShortString()));
	}

	private void finishReturnToMine() {
		restoreAllowPlace();
		miningReturnPoint = null;
		miningReturnDimension = null;
		activeReturnTarget = null;
		activeReturnWaypoint = null;
		returnPortalWaypoint = null;
		returningByElytra = false;
		ReturnAction completedAction = returnAction;
		boolean supplyDebug = supply.debugOnly;
		AutomationState supplyPrevious = supply.previousState;
		boolean repairDebug = repair.debugOnly;
		AutomationState repairPrevious = repair.previousState;
		boolean sleepDebug = sleep.debugOnly;
		AutomationState sleepPrevious = sleep.previousState;
		returnAction = ReturnAction.NONE;
		if (completedAction == ReturnAction.AFTER_SUPPLY) clearSupplyContext();
		if (completedAction == ReturnAction.AFTER_SUPPLY && supplyDebug) {
			transition(AutomationState.COMPLETE, Translations.DETAIL.message("supply_debug_complete"));
		} else if (completedAction == ReturnAction.AFTER_SUPPLY && supplyPrevious == AutomationState.COLLECTING_DROPS) {
			beginDropCollection(miningCompletePending);
		} else if (completedAction == ReturnAction.AFTER_REPAIR && repairDebug) {
			clearRepairContext();
			transition(AutomationState.COMPLETE, Translations.DETAIL.message("repair_debug_complete"));
		} else if (completedAction == ReturnAction.AFTER_REPAIR) {
			clearRepairContext();
			if (repairPrevious == AutomationState.COLLECTING_DROPS) beginDropCollection(miningCompletePending);
			else startMiningCycle();
		} else if (completedAction == ReturnAction.AFTER_SLEEP) {
			clearSleepContext();
			if (sleepDebug) transition(AutomationState.COMPLETE, Translations.DETAIL.message("sleep_debug_complete"));
			else if (sleepPrevious == AutomationState.COLLECTING_DROPS) beginDropCollection(miningCompletePending);
			else startMiningCycle();
		} else {
			startMiningCycle();
		}
	}

	private boolean tickNavigationWatchdog() {
		BlockPos target = watchdogNavigationTarget();
		if (target == null) {
			resetNavigationWatchdog();
			return false;
		}
		Vec3 position = baritone.getPlayerContext().player().position();
		int requiredScans = monitorScans(advanced.navigationStallTimeoutSeconds);
		if (state != watchdogState || !target.equals(watchdogTarget)) {
			watchdogState = state;
			watchdogTarget = target;
			watchdogPosition = position;
			watchdogStationaryScans = 0;
			watchdogRetries = 0;
			navOscillationTracker.clearWindow();
			navOscillationTracker.recordSample(position.x, position.y, position.z, requiredScans);
			return false;
		}
		navOscillationTracker.recordSample(position.x, position.y, position.z, requiredScans);
		boolean stationary = watchdogPosition != null && position.distanceToSqr(watchdogPosition) <= 0.0625;
		if (!stationary) {
			watchdogPosition = position;
			watchdogStationaryScans = 0;
		} else {
			watchdogStationaryScans++;
		}
		boolean oscillating = navOscillationTracker.isOscillatingConfined(requiredScans, 2.5);
		if (watchdogStationaryScans < requiredScans && !oscillating) return false;
		watchdogStationaryScans = 0;
		navOscillationTracker.clearWindow();
		if (++watchdogRetries > advanced.navigationRetryCount) {
			stopProcesses();
			transition(AutomationState.ERROR, Translations.DETAIL.message("navigation_stalled", advanced.navigationRetryCount, target.toShortString()));
			return true;
		}
		markStuckLocation();
		BlockPos backtrack = reverseBacktrackTarget(5.0, 18.0, false);
		if (backtrack != null) {
			stopProcesses();
			navigationBacktrack = true;
			navigationBacktrackTarget = backtrack;
			navigationBacktrackTicks = 0;
			miningStallRecoveryStartPos = baritone.getPlayerContext().player().position();
			manualUnstuckTicks = 0;
			navigation.walk(new GoalBlock(backtrack));
			return true;
		}
		restartWatchedNavigation();
		return true;
	}

	private void tickNavigationBacktrack() {
		if (manualUnstuckTicks > 0) {
			if (stepManualUnstuck()) return;
			navigationBacktrack = false;
			navigationBacktrackTarget = null;
			navigationBacktrackTicks = 0;
			navOscillationTracker.clearWindow();
			restartWatchedNavigation();
			return;
		}
		navigationBacktrackTicks++;
		BlockPos feet = baritone.getPlayerContext().playerFeet();
		int timeoutTicks = MiningEscapePolicy.recoveryWalkTimeoutTicks(advanced.navigationStallTimeoutSeconds);
		if (!navigation.isWalking() && !navigation.canUseCreativeFlight()
				&& miningStallRecoveryStartPos != null
				&& baritone.getPlayerContext().player().position().distanceToSqr(miningStallRecoveryStartPos) < 2.25
				&& navigationBacktrackTicks < timeoutTicks) {
			beginManualUnstuck(navigationBacktrackTarget);
			return;
		}
		if (navigationBacktrackTarget == null
				|| feet.distSqr(navigationBacktrackTarget) <= 2.0
				|| !navigation.isWalking()
				|| navigationBacktrackTicks >= timeoutTicks) {
			navigation.stopWalking();
			navigationBacktrack = false;
			navigationBacktrackTarget = null;
			navigationBacktrackTicks = 0;
			navOscillationTracker.clearWindow();
			restartWatchedNavigation();
		}
	}

	private BlockPos watchdogNavigationTarget() {
		return switch (state) {
			case RETURNING_TO_MINE -> activeReturnTarget;
			case NAVIGATING_TO_RESUPPLY, NAVIGATING_TO_DURABILITY_SUPPLY -> supply.stand;
			case NAVIGATING_TO_BED -> sleep.stand;
			case NAVIGATING_TO_PERIMETER_PORTAL, NAVIGATING_TO_REPAIR_PORTAL, NAVIGATING_TO_REPAIR_MACHINE -> repair.navigationTarget;
			case NAVIGATING_TO_UNLOAD -> unload.point == null ? null
					: new BlockPos(unload.point.point().x, baritone.getPlayerContext().playerFeet().y, unload.point.point().z);
			case APPROACHING_UNLOAD -> unload.candidateIndex < unload.candidates.size() ? unload.candidates.get(unload.candidateIndex).position() : null;
			default -> null;
		};
	}

	private boolean isManagedNavigationState(AutomationState candidate) {
		return candidate == AutomationState.RETURNING_TO_MINE
				|| candidate == AutomationState.NAVIGATING_TO_RESUPPLY
				|| candidate == AutomationState.NAVIGATING_TO_DURABILITY_SUPPLY
				|| candidate == AutomationState.NAVIGATING_TO_BED
				|| candidate == AutomationState.NAVIGATING_TO_PERIMETER_PORTAL
				|| candidate == AutomationState.NAVIGATING_TO_REPAIR_PORTAL
				|| candidate == AutomationState.NAVIGATING_TO_REPAIR_MACHINE
				|| candidate == AutomationState.NAVIGATING_TO_UNLOAD
				|| candidate == AutomationState.APPROACHING_UNLOAD;
	}

	private boolean isInsideNetherPortal() {
		BlockPos feet = currentClientPlayerFeet();
		var world = Minecraft.getInstance().level;
		return world != null && (world.getBlockState(feet).is(Blocks.NETHER_PORTAL) || world.getBlockState(feet.above()).is(Blocks.NETHER_PORTAL));
	}

	private void restartWatchedNavigation() {
		stopProcesses();
		switch (state) {
			case RETURNING_TO_MINE -> beginReturnToMine();
			case NAVIGATING_TO_RESUPPLY, NAVIGATING_TO_DURABILITY_SUPPLY -> {
				BetterBlockPos player = baritone.getPlayerContext().playerFeet();
				if (player.distSqr(supply.stand) > navigationFlightDistanceSquared() && canFlyToSupply()) startFlyingToSupply();
				else startWalkingToSupply();
			}
			case NAVIGATING_TO_BED -> {
				BetterBlockPos player = baritone.getPlayerContext().playerFeet();
				if (player.distSqr(sleep.stand) > navigationFlightDistanceSquared() && canFlyToSupply()) startFlyingToBed();
				else startWalkingToBed();
			}
			case NAVIGATING_TO_PERIMETER_PORTAL, NAVIGATING_TO_REPAIR_PORTAL, NAVIGATING_TO_REPAIR_MACHINE -> {
				if (repair.stage == RepairFlow.Stage.RETURN_TO_MACHINE_TAKEOFF) {
					repair.flying = false;
					repair.flightAttempts = advanced.flightRetryCount;
					startWalkingForRepair(state);
				} else startRepairNavigation(repair.navigationTarget, state);
			}
			case NAVIGATING_TO_UNLOAD -> beginUnloadApproach();
			case APPROACHING_UNLOAD -> pathToUnloadCandidate();
			default -> {
			}
		}
	}

	private void resetNavigationWatchdog() {
		watchdogState = null;
		watchdogTarget = null;
		watchdogPosition = null;
		watchdogStationaryScans = 0;
		watchdogRetries = 0;
		navigationBacktrack = false;
		navigationBacktrackTarget = null;
		navigationBacktrackTicks = 0;
		navOscillationTracker.clearWindow();
	}

	private void recordSafeBreadcrumb() {
		if (baritone == null || isInsideNetherPortal()) return;
		var player = baritone.getPlayerContext().player();
		if (player == null) return;
		BetterBlockPos feet = baritone.getPlayerContext().playerFeet();
		if (player.getAbilities().flying || isSafeStandingPosition(feet)) {
			stallTracker.recordBreadcrumb(feet.x, feet.y, feet.z);
		}
	}

	private void markStuckLocation() {
		if (baritone == null || baritone.getPlayerContext().player() == null) return;
		BetterBlockPos feet = baritone.getPlayerContext().playerFeet();
		StuckCellPenalty.penalizeNeighborhood(feet.x, feet.y, feet.z, 2, 1, 60_000L);
		BlockPos pos = new BlockPos(feet.x, feet.y, feet.z);
		if (recentStallSpots.isEmpty() || recentStallSpots.peekLast().distSqr(pos) >= 2.0) {
			recentStallSpots.addLast(pos);
			while (recentStallSpots.size() > 6) recentStallSpots.removeFirst();
		}
	}

	private boolean isNearRecentStallSpot(BlockPos candidate) {
		for (BlockPos spot : recentStallSpots) {
			if (spot.distSqr(candidate) <= 12.25) return true; // within 3.5 blocks of a recent stall spot
		}
		return false;
	}

	private BlockPos reverseBacktrackTarget(double minDist, double maxDist, boolean preferInsideArea) {
		BetterBlockPos feet = baritone.getPlayerContext().playerFeet();
		boolean flying = navigation.canUseCreativeFlight();
		StallRecoveryTracker.Breadcrumb chosen = stallTracker.selectReverseBacktrack(
				feet.x, feet.y, feet.z, minDist, maxDist,
				crumb -> {
					BlockPos pos = new BlockPos(crumb.x(), crumb.y(), crumb.z());
					if (preferInsideArea && area != null && !area.contains(new BetterBlockPos(pos))) return false;
					if (isNearRecentStallSpot(pos)) return false;
					return flying ? isSafeHoverOrStandingPosition(pos) : isSafeStandingPosition(pos);
				}
		);
		return chosen == null ? null : new BlockPos(chosen.x(), chosen.y(), chosen.z());
	}

	private boolean isSafeHoverOrStandingPosition(BlockPos pos) {
		if (isSafeStandingPosition(pos)) return true;
		var world = Minecraft.getInstance().level;
		if (world == null || !world.isLoaded(pos)) return false;
		return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()
				&& world.getBlockState(pos.above()).getCollisionShape(world, pos.above()).isEmpty();
	}

	private BlockPos findSafePositionNearColumn(int x, int baseY, int z, boolean insideAreaOnly) {
		int[] dyOffsets = {0, 1, -1, 2, -2, 3, -3};
		boolean flying = navigation.canUseCreativeFlight();
		for (int dy : dyOffsets) {
			BlockPos candidate = new BlockPos(x, baseY + dy, z);
			if (area != null && baseY >= area.minY() && candidate.getY() < area.minY()) continue;
			if (insideAreaOnly && area != null && !area.contains(new BetterBlockPos(candidate))) continue;
			if (flying ? isSafeHoverOrStandingPosition(candidate) : isSafeStandingPosition(candidate)) {
				return candidate;
			}
		}
		return null;
	}

	private boolean tickMiningStallDetection() {
		Vec3 position = baritone.getPlayerContext().player().position();
		if (miningProcess != null) {
			AreaMiningStatus status = miningProcess.getAreaMiningStatus();
			long distinctScore = status.distinctMinedBlocks() > 0L ? status.distinctMinedBlocks() : status.minedBlocks();
			if (distinctScore < miningStallMinedBaseline) {
				miningStallMinedBaseline = distinctScore;
			} else if (distinctScore > miningStallMinedBaseline) {
				// Actively breaking new blocks (e.g. standing still clearing a wall in reach) is NOT a stall.
				miningStallMinedBaseline = distinctScore;
				miningStallPosition = position;
				miningStallStationaryScans = 0;
				stallTracker.clearWindow();
				return false;
			}
		}
		int requiredScans = monitorScans(advanced.miningStallTimeoutSeconds);
		stallTracker.recordSample(position.x, position.y, position.z, requiredScans);
		boolean stationary = miningStallPosition != null && position.distanceToSqr(miningStallPosition) <= 0.25;
		if (!stationary) {
			miningStallPosition = position;
			miningStallStationaryScans = 0;
		} else {
			miningStallStationaryScans++;
		}
		int oscScans = Math.max(8, requiredScans * 3 / 4);
		boolean oscillating = stallTracker.isOscillatingConfined(oscScans, 2.6);
		if (miningStallStationaryScans < requiredScans && !oscillating) return false;
		int cooldownTicks = ticks(advanced.miningStallCooldownSeconds);
		if (tickCounter - miningStallLastTriggerTick < cooldownTicks) return false;
		markStuckLocation();
		if (miningProcess != null) miningProcess.deferCurrentTargets(45_000L);
		int baseDist = Math.max(4, advanced.miningStallMovementDistance);
		int escalatedDist = Math.min(Math.max(24, baseDist * 5), baseDist * (1 << Math.min(2, consecutiveStallCount)));
		consecutiveStallCount++;
		BlockPos target = reverseBacktrackTarget(Math.max(4.5, escalatedDist * 0.6), Math.max(14.0, escalatedDist * 2.5), true);
		if (target == null) {
			target = randomNearbySafeTarget(escalatedDist);
		}
		if (target == null) return false;
		miningStallLastTriggerTick = tickCounter;
		stallTracker.clearWindow();
		miningProcess.pause();
		beginMiningRecoveryWalk(target);
		transition(AutomationState.MINING, Translations.DETAIL.message("mining_stall_recovery", target.toShortString()));
		return true;
	}

	private void beginMiningRecoveryWalk(BlockPos target) {
		creativeMiner.stop(baritone);
		miningStallPosition = null;
		miningStallStationaryScans = 0;
		stallTracker.clearWindow();
		miningStallRecovery = true;
		miningStallRecoveryTarget = target;
		miningStallRecoveryTicks = 0;
		miningStallRecoveryStartPos = baritone.getPlayerContext().player().position();
		manualUnstuckTicks = 0;
		navigation.walk(new GoalBlock(target));
	}

	private void tickMiningStallRecovery() {
		var mc = Minecraft.getInstance();
		if (bubbleEscape.isActive()) {
			if (bubbleEscape.tick(baritone, navigation, mc, mc.player, mc.level,
					latestConfig != null ? latestConfig.bubbleColumnEscapeMode : "auto")) {
				return;
			}
			resetMiningStall();
			miningProgressIdleScans = MiningEscapePolicy.idleScansAfterStallRecovery(miningProgressIdleScans);
			miningProgressLastPos = null;
			resumeMiningCycle(false);
			return;
		}
		if (manualUnstuckTicks > 0) {
			if (stepManualUnstuck()) return;
			resetMiningStall();
			miningProgressIdleScans = MiningEscapePolicy.idleScansAfterStallRecovery(miningProgressIdleScans);
			miningProgressLastPos = null;
			resumeMiningCycle(false);
			return;
		}
		miningStallRecoveryTicks++;
		BlockPos feet = baritone.getPlayerContext().playerFeet();
		int timeoutTicks = MiningEscapePolicy.recoveryWalkTimeoutTicks(advanced.navigationStallTimeoutSeconds);
		if (!navigation.isWalking() && !navigation.canUseCreativeFlight()
				&& miningStallRecoveryStartPos != null
				&& baritone.getPlayerContext().player().position().distanceToSqr(miningStallRecoveryStartPos) < 2.25
				&& miningStallRecoveryTicks < timeoutTicks) {
			beginManualUnstuck(miningStallRecoveryTarget);
			return;
		}
		if (feet.equals(miningStallRecoveryTarget) || !navigation.isWalking() || miningStallRecoveryTicks >= timeoutTicks) {
			navigation.stopWalking();
			resetMiningStall();
			miningProgressIdleScans = MiningEscapePolicy.idleScansAfterStallRecovery(miningProgressIdleScans);
			miningProgressLastPos = null;
			resumeMiningCycle(false);
		}
	}

	private void beginManualUnstuck(BlockPos preferredTarget) {
		navigation.stopWalking();
		var mc = Minecraft.getInstance();
		var player = baritone.getPlayerContext().player();
		var world = mc.level;
		if (BubbleColumnEscapeController.isPlayerInDownwardBubble(world, player)
				&& bubbleEscape.tryStartEscape(baritone, navigation, mc, player, world,
						latestConfig != null ? latestConfig.bubbleColumnEscapeMode : "auto")) {
			return;
		}
		float yaw = player.getYRot() + 180.0F;
		if (preferredTarget != null) {
			double dx = preferredTarget.getX() + 0.5 - player.getX();
			double dz = preferredTarget.getZ() + 0.5 - player.getZ();
			if (dx * dx + dz * dz > 0.25) {
				yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			}
		}
		if (world != null) {
			BlockPos feet = player.blockPosition();
			int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
			double bestScore = -Double.MAX_VALUE;
			for (int[] d : dirs) {
				BlockPos n = feet.offset(d[0], 0, d[1]);
				if (!world.isLoaded(n)) continue;
				if (!world.getBlockState(n).getCollisionShape(world, n).isEmpty()) continue;
				if (!world.getBlockState(n.above()).getCollisionShape(world, n.above()).isEmpty()) continue;
				double score = world.getFluidState(n).isEmpty() ? 10.0 : 0.0;
				if (preferredTarget != null) {
					score -= Math.sqrt(n.distSqr(preferredTarget)) * 0.25;
				}
				if (score > bestScore) {
					bestScore = score;
					yaw = (float) Math.toDegrees(Math.atan2(-d[0], d[1]));
				}
			}
		}
		manualUnstuckYaw = yaw;
		manualUnstuckTicks = 25;
	}

	private boolean stepManualUnstuck() {
		var player = baritone.getPlayerContext().player();
		manualUnstuckTicks--;
		boolean movedFarEnough = miningStallRecoveryStartPos != null
				&& player.position().distanceToSqr(miningStallRecoveryStartPos) >= 6.25;
		if (movedFarEnough || manualUnstuckTicks <= 0) {
			manualUnstuckTicks = 0;
			baritone.getInputOverrideHandler().clearAllKeys();
			return false;
		}
		player.setYRot(manualUnstuckYaw);
		var handler = baritone.getInputOverrideHandler();
		handler.setInputForceState(Input.SNEAK, false);
		handler.setInputForceState(Input.MOVE_FORWARD, true);
		handler.setInputForceState(Input.JUMP, true);
		return true;
	}

	private BlockPos randomNearbySafeTarget(int distance) {
		BlockPos feet = baritone.getPlayerContext().playerFeet();
		double[] angles = new double[8];
		for (int i = 0; i < 8; i++) angles[i] = Math.random() * 2.0 * Math.PI;
		for (double angle : angles) {
			int dx = (int) Math.round(Math.sin(angle) * distance);
			int dz = (int) Math.round(Math.cos(angle) * distance);
			if (dx == 0 && dz == 0) continue;
			BlockPos candidate = findSafePositionNearColumn(feet.getX() + dx, feet.getY(), feet.getZ() + dz, false);
			if (candidate != null && !isNearRecentStallSpot(candidate)) return candidate;
		}
		for (int dx = -distance; dx <= distance; dx++) {
			for (int dz = -distance; dz <= distance; dz++) {
				if (dx == 0 && dz == 0) continue;
				if (dx * dx + dz * dz > distance * distance) continue;
				BlockPos candidate = findSafePositionNearColumn(feet.getX() + dx, feet.getY(), feet.getZ() + dz, false);
				if (candidate != null && !isNearRecentStallSpot(candidate)) return candidate;
			}
		}
		for (int dx = -distance; dx <= distance; dx++) {
			for (int dz = -distance; dz <= distance; dz++) {
				if (dx == 0 && dz == 0) continue;
				if (dx * dx + dz * dz > distance * distance) continue;
				BlockPos candidate = findSafePositionNearColumn(feet.getX() + dx, feet.getY(), feet.getZ() + dz, false);
				if (candidate != null) return candidate;
			}
		}
		return null;
	}

	private void resetMiningStall() {
		bubbleEscape.reset(baritone);
		miningStallPosition = null;
		miningStallStationaryScans = 0;
		miningStallMinedBaseline = 0L;
		stallTracker.clearWindow();
		miningStallRecovery = false;
		miningStallRecoveryTarget = null;
		miningStallRecoveryTicks = 0;
		miningStallRecoveryStartPos = null;
		manualUnstuckTicks = 0;
	}

	private int firstDisposableSlot() {
		Inventory inventory = baritone.getPlayerContext().player().getInventory();
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty() && !shouldKeepWhenUnloading(stack)) return slot;
		}
		return -1;
	}

	private boolean shouldKeepWhenUnloading(ItemStack stack) {
		return stack.isDamageableItem()
				|| stack.is(Items.ELYTRA)
				|| stack.is(Items.FIREWORK_ROCKET)
				|| stack.getItem().components().has(DataComponents.TOOL)
				|| stack.getItem().components().has(DataComponents.EQUIPPABLE)
				|| stack.getItem().components().has(DataComponents.FOOD)
				|| unloadingWhitelist.contains(stack.getItem());
	}

	private List<UnloadFlow.Candidate> findUnloadCandidates(UnloadingPointConfig point, int preferredY) {
		List<UnloadFlow.Candidate> candidates = new ArrayList<>();
		int minY = baritone.getPlayerContext().world().dimensionType().minY() + 1;
		int maxY = baritone.getPlayerContext().world().dimensionType().minY()
				+ baritone.getPlayerContext().world().dimensionType().height() - 2;
		BetterBlockPos player = baritone.getPlayerContext().playerFeet();
		for (int dx = -advanced.unloadLandingSearchRadius; dx <= advanced.unloadLandingSearchRadius; dx++) {
			for (int dz = -advanced.unloadLandingSearchRadius; dz <= advanced.unloadLandingSearchRadius; dz++) {
				int horizontalDistanceSquared = dx * dx + dz * dz;
				if (horizontalDistanceSquared == 0 || horizontalDistanceSquared > advanced.unloadLandingSearchRadius * advanced.unloadLandingSearchRadius) continue;
				int x = point.x + dx;
				int z = point.z + dz;
				BlockPos position = closestSafeStandingPosition(x, z, preferredY, minY, maxY);
				if (position != null) {
					candidates.add(new UnloadFlow.Candidate(position, horizontalDistanceSquared, Math.abs(position.getY() - preferredY), player.distSqr(position)));
				}
			}
		}
		candidates.sort(Comparator.comparingInt(UnloadFlow.Candidate::horizontalDistanceSquared)
				.thenComparingInt(UnloadFlow.Candidate::yDifference)
				.thenComparing((first, second) -> Integer.compare(second.position().getY(), first.position().getY()))
				.thenComparingDouble(UnloadFlow.Candidate::playerDistanceSquared));
		return candidates;
	}

	private BlockPos closestSafeStandingPosition(int x, int z, int preferredY, int minY, int maxY) {
		int centerY = Math.max(minY, Math.min(maxY, preferredY));
		int maxDifference = Math.max(centerY - minY, maxY - centerY);
		for (int difference = 0; difference <= maxDifference; difference++) {
			BlockPos upper = new BlockPos(x, centerY + difference, z);
			if (upper.getY() <= maxY && isSafeStandingPosition(upper)) return upper;
			if (difference > 0) {
				BlockPos lower = new BlockPos(x, centerY - difference, z);
				if (lower.getY() >= minY && isSafeStandingPosition(lower)) return lower;
			}
		}
		return null;
	}

	private boolean isSafeStandingPosition(BlockPos position) {
		BlockPos floor = position.below();
		BlockPos head = position.above();
		if (!baritone.getPlayerContext().world().isLoaded(floor) || !baritone.getPlayerContext().world().isLoaded(head)) return false;
		var world = baritone.getPlayerContext().world();
		var floorState = world.getBlockState(floor);
		var feetState = world.getBlockState(position);
		var headState = world.getBlockState(head);
		return floorState.isFaceSturdy(world, floor, Direction.UP)
				&& !floorState.is(Blocks.MAGMA_BLOCK)
				&& !floorState.is(Blocks.CAMPFIRE)
				&& !floorState.is(Blocks.SOUL_CAMPFIRE)
				&& !feetState.is(Blocks.POWDER_SNOW)
				&& !(feetState.getBlock() instanceof BaseFireBlock)
				&& floorState.getFluidState().isEmpty()
				&& feetState.getFluidState().isEmpty()
				&& headState.getFluidState().isEmpty()
				&& feetState.getCollisionShape(world, position).isEmpty()
				&& headState.getCollisionShape(world, head).isEmpty();
	}

	private Vec3 unloadEdgePosition(BlockPos standingBlock, UnloadingPointConfig point) {
		double centerX = standingBlock.getX() + 0.5;
		double centerZ = standingBlock.getZ() + 0.5;
		double dx = point.x + 0.5 - centerX;
		double dz = point.z + 0.5 - centerZ;
		double distance = Math.sqrt(dx * dx + dz * dz);
		if (distance == 0.0) return new Vec3(centerX, standingBlock.getY(), centerZ);
		double unitX = dx / distance;
		double unitZ = dz / distance;
		double maxOffset = 0.5 + advanced.unloadEdgeInset;
		double xScale = Math.abs(unitX) < 1.0E-6 ? Double.POSITIVE_INFINITY : maxOffset / Math.abs(unitX);
		double zScale = Math.abs(unitZ) < 1.0E-6 ? Double.POSITIVE_INFINITY : maxOffset / Math.abs(unitZ);
		double scale = Math.min(xScale, zScale);
		return new Vec3(centerX + unitX * scale, standingBlock.getY(), centerZ + unitZ * scale);
	}

	private static long horizontalDistanceSquared(BetterBlockPos player, UnloadingPointConfig point) {
		long dx = (long) player.x - point.x;
		long dz = (long) player.z - point.z;
		return dx * dx + dz * dz;
	}

	private boolean hasUsableTool() {
		Inventory inventory = baritone.getPlayerContext().player().getInventory();
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.getItem().components().has(DataComponents.TOOL)
					&& (!stack.isDamageableItem() || remainingDurability(stack) > durabilityThreshold(stack))) return true;
		}
		return false;
	}

	private ResourceCheck inspectResources() {
		Inventory inventory = baritone.getPlayerContext().player().getInventory();
		List<StackSlot> tools = new ArrayList<>();
		List<StackSlot> elytras = new ArrayList<>();
		for (int slot = 0; slot < 36; slot++) classify(new StackSlot(inventory.getItem(slot), slot, menuSlot(slot), false, false), tools, elytras);
		classify(new StackSlot(inventory.getItem(OFFHAND_INVENTORY_SLOT), OFFHAND_INVENTORY_SLOT, OFFHAND_MENU_SLOT, true, false), tools, elytras);
		classify(new StackSlot(baritone.getPlayerContext().player().getItemBySlot(EquipmentSlot.CHEST), -1, CHEST_MENU_SLOT, false, true), tools, elytras);
		ResourceCheck toolsResult = inspectGroup(tools);
		if (toolsResult.repairRequired() || toolsResult.replacement() != null) return toolsResult;
		return inspectGroup(elytras);
	}

	private void classify(StackSlot slot, List<StackSlot> tools, List<StackSlot> elytras) {
		if (slot.stack().isEmpty() || !slot.stack().isDamageableItem()) return;
		if (slot.stack().is(Items.ELYTRA)) {
			if (usesElytraNavigation()) elytras.add(slot);
		}
		else if (slot.stack().getItem().components().has(DataComponents.TOOL)) tools.add(slot);
	}

	private ResourceCheck inspectGroup(List<StackSlot> group) {
		Replacement replacement = null;
		int selectedSlot = baritone.getPlayerContext().player().getInventory().getSelectedSlot();
		for (StackSlot low : group) {
			boolean isElytra = low.stack().is(Items.ELYTRA);
			int repairThreshold = durabilityThreshold(low.stack());
			int swapThreshold = isElytra ? advanced.elytraDurabilityThreshold : Math.max(0, advanced.toolDurabilityThreshold);
			boolean belowRepair = remainingDurability(low.stack()) <= repairThreshold;
			boolean belowAutoSwap = (isElytra || autoReplaceLowDurabilityToolEnabled) && remainingDurability(low.stack()) <= swapThreshold;
			if (!belowRepair && !belowAutoSwap) continue;

			int healthyThreshold = belowAutoSwap ? swapThreshold : repairThreshold;
			StackSlot healthy = findBestHealthyReplacement(group, low, healthyThreshold, !isElytra);
			if (healthy == null) {
				if (belowRepair) {
					return new ResourceCheck(true, BuiltInRegistries.ITEM.getKey(low.stack().getItem()).toString(), null);
				}
				continue;
			}
			if (!belowAutoSwap) continue;
			boolean isEquippedOrSelected = low.chest() || low.offhand() || low.inventorySlot() == selectedSlot;
			boolean isHotbarWithBackpackReplacement = low.inventorySlot() >= 0 && low.inventorySlot() < 9 && healthy.inventorySlot() >= 9;
			if (isEquippedOrSelected) {
				replacement = new Replacement(low, healthy);
			} else if (replacement == null && isHotbarWithBackpackReplacement) {
				replacement = new Replacement(low, healthy);
			}
		}
		return new ResourceCheck(false, "", replacement);
	}

	private static StackSlot findBestHealthyReplacement(List<StackSlot> group, StackSlot low, int threshold, boolean allowSameCategoryFallback) {
		StackSlot bestExact = group.stream()
				.filter(candidate -> candidate != low && candidate.stack().getItem() == low.stack().getItem())
				.filter(candidate -> remainingDurability(candidate.stack()) > threshold)
				.max((first, second) -> Integer.compare(remainingDurability(first.stack()), remainingDurability(second.stack())))
				.orElse(null);
		if (bestExact != null || !allowSameCategoryFallback) return bestExact;
		var lowCategory = BackpackToolManager.classifyCategory(low.stack());
		if (lowCategory == HotbarSwapPlanner.ToolCategory.NONE
				|| lowCategory == HotbarSwapPlanner.ToolCategory.OTHER_TOOL) {
			return null;
		}
		return group.stream()
				.filter(candidate -> candidate != low && BackpackToolManager.classifyCategory(candidate.stack()) == lowCategory)
				.filter(candidate -> remainingDurability(candidate.stack()) > threshold)
				.max((first, second) -> Integer.compare(remainingDurability(first.stack()), remainingDurability(second.stack())))
				.orElse(null);
	}

	private void performReplacement(Replacement replacement) {
		StackSlot low = replacement.low();
		StackSlot healthy = replacement.healthy();
		if (low.chest() || low.offhand()) {
			queuePickupSwap(healthy.menuSlot(), low.menuSlot());
			synchronize(state, Translations.DETAIL.message("replacing_item", BuiltInRegistries.ITEM.getKey(low.stack().getItem())));
			return;
		}
		Inventory inventory = baritone.getPlayerContext().player().getInventory();
		if (healthy.inventorySlot() >= 9 && low.inventorySlot() >= 0 && low.inventorySlot() < 9) {
			baritone.getPlayerContext().playerController().windowClick(
					baritone.getPlayerContext().player().inventoryMenu.containerId,
					healthy.menuSlot(), low.inventorySlot(), ContainerInput.SWAP,
					baritone.getPlayerContext().player());
		} else if (healthy.inventorySlot() < 9) {
			inventory.setSelectedSlot(healthy.inventorySlot());
		} else {
			baritone.getPlayerContext().playerController().windowClick(
					baritone.getPlayerContext().player().inventoryMenu.containerId,
					healthy.menuSlot(), inventory.getSelectedSlot(), ContainerInput.SWAP,
					baritone.getPlayerContext().player());
		}
		synchronize(state, Translations.DETAIL.message("replaced_item", BuiltInRegistries.ITEM.getKey(low.stack().getItem())));
	}

	private boolean beginEating() {
		if (tickCounter < eatingCooldownUntilTick) return false;
		var player = baritone.getPlayerContext().player();
		if (player == null) return false;
		Optional<HotbarSwapPlanner.FoodSwapPlan> planOpt = planFoodForEating(player);
		if (planOpt.isEmpty()) return false;
		HotbarSwapPlanner.FoodSwapPlan plan = planOpt.get();
		if (plan.fromBackpack() && !(player.containerMenu instanceof InventoryMenu)) {
			return false;
		}
		stateBeforeEating = state;
		eatingTicks = 0;
		eatingPausedCreativeMiner = false;
		eatingPreviousSelectedSlot = player.getInventory().getSelectedSlot();
		eatingSwappedBackpackSlot = -1;
		eatingSwappedHotbarSlot = -1;
		eatingSyncGraceTicks = 0;
		if (state == AutomationState.BUILDING) {
			schematicBuilder.suspendForInterruption(baritone, navigation);
		}
		if (state == AutomationState.MINING) {
			if (creativeMiner.isActive()) {
				creativeMiner.stop(baritone);
				eatingPausedCreativeMiner = true;
			} else if (miningProcess != null) {
				miningProcess.pause();
			}
		}
		if (state == AutomationState.COLLECTING_DROPS) navigation.stopWalking();
		if (watchdogNavigationTarget() != null) stopProcesses();
		applyFoodSwapPlan(player, plan);
		Minecraft.getInstance().options.keyUse.setDown(true);
		baritone.getPlayerContext().playerController().processRightClick(
				player, baritone.getPlayerContext().world(), InteractionHand.MAIN_HAND);
		transition(AutomationState.EATING, Translations.DETAIL.message("eating"));
		return true;
	}

	private Optional<HotbarSwapPlanner.FoodSwapPlan> planFoodForEating(net.minecraft.client.player.LocalPlayer player) {
		if (allowedFoods.isEmpty()) return Optional.empty();
		Set<String> allowedIds = new HashSet<>();
		for (Item item : allowedFoods) {
			allowedIds.add(BuiltInRegistries.ITEM.getKey(item).toString());
		}
		List<Item> protectedItems = new ArrayList<>(allowedFoods);
		for (Block block : sealingBlocks) {
			Item item = block.asItem();
			if (item != Items.AIR && !protectedItems.contains(item)) protectedItems.add(item);
		}
		int lowThreshold = Math.max(0, advanced.toolDurabilityThreshold);
		List<HotbarSwapPlanner.SlotInfo> snapshot = BackpackToolManager.snapshotInventory(player, null, lowThreshold, protectedItems);
		boolean allowBackpack = autoSwapBackpackFoodEnabled && (player.containerMenu instanceof InventoryMenu);
		return HotbarSwapPlanner.planBackpackFoodSwap(snapshot, allowedIds, player.getInventory().getSelectedSlot(), allowBackpack);
	}

	private void applyFoodSwapPlan(net.minecraft.client.player.LocalPlayer player, HotbarSwapPlanner.FoodSwapPlan plan) {
		Inventory inventory = player.getInventory();
		if (plan.fromBackpack()) {
			baritone.getPlayerContext().playerController().windowClick(
					player.inventoryMenu.containerId,
					menuSlot(plan.sourceSlot()), plan.hotbarSlot(), ContainerInput.SWAP,
					player);
			if (plan.displacedImportantItem() && eatingSwappedBackpackSlot < 0) {
				eatingSwappedBackpackSlot = plan.sourceSlot();
				eatingSwappedHotbarSlot = plan.hotbarSlot();
			}
			eatingSyncGraceTicks = 3;
		}
		inventory.setSelectedSlot(plan.hotbarSlot());
		baritone.getPlayerContext().playerController().syncHeldItem();
	}

	private void restoreAfterEating(net.minecraft.client.player.LocalPlayer player) {
		if (player != null) {
			if (eatingSwappedBackpackSlot >= 9 && eatingSwappedHotbarSlot >= 0 && eatingSwappedHotbarSlot < 9
					&& player.containerMenu instanceof InventoryMenu) {
				baritone.getPlayerContext().playerController().windowClick(
						player.inventoryMenu.containerId,
						menuSlot(eatingSwappedBackpackSlot), eatingSwappedHotbarSlot, ContainerInput.SWAP,
						player);
			}
			if (eatingPreviousSelectedSlot >= 0 && eatingPreviousSelectedSlot < 9) {
				player.getInventory().setSelectedSlot(eatingPreviousSelectedSlot);
				baritone.getPlayerContext().playerController().syncHeldItem();
			}
		}
		eatingPreviousSelectedSlot = -1;
		eatingSwappedBackpackSlot = -1;
		eatingSwappedHotbarSlot = -1;
		eatingSyncGraceTicks = 0;
	}

	private boolean shouldEat() {
		int foodLevel = baritone.getPlayerContext().player().getFoodData().getFoodLevel();
		return foodLevel <= advanced.foodLevelThreshold
				|| foodLevel < 20 && baritone.getPlayerContext().player().getHealth() <= advanced.healthEatingThreshold;
	}

	private void tickEating() {
		eatingTicks++;
		var mc = Minecraft.getInstance();
		var player = baritone.getPlayerContext().player();
		boolean holdingFood = player != null && allowedFoods.contains(player.getMainHandItem().getItem());
		boolean timedOut = eatingTicks >= EATING_TIMEOUT_TICKS;
		if (!holdingFood && !timedOut && shouldEat() && player != null) {
			if (eatingSyncGraceTicks > 0) {
				eatingSyncGraceTicks--;
				mc.options.keyUse.setDown(true);
				return;
			}
			// Finished the current food stack while still hungry -> pull another stack from hotbar/backpack.
			Optional<HotbarSwapPlanner.FoodSwapPlan> nextFood = planFoodForEating(player);
			if (nextFood.isPresent()) {
				applyFoodSwapPlan(player, nextFood.get());
				mc.options.keyUse.setDown(true);
				baritone.getPlayerContext().playerController().processRightClick(
						player, baritone.getPlayerContext().world(), InteractionHand.MAIN_HAND);
				return;
			}
		}
		if (!shouldEat() || !holdingFood || timedOut) {
			baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
			mc.options.keyUse.setDown(false);
			if (mc.gameMode != null && mc.player != null) {
				mc.gameMode.releaseUsingItem(mc.player);
			}
			if (timedOut) {
				eatingCooldownUntilTick = tickCounter + 60;
			}
			restoreAfterEating(player);
			if (stateBeforeEating == AutomationState.BUILDING) {
				schematicBuilder.resumeAfterInterruption();
				transition(AutomationState.BUILDING, LocalizedMessage.literal("进食完成，继续投影放置"));
			} else if (stateBeforeEating == AutomationState.MINING) {
				if (eatingPausedCreativeMiner) {
					eatingPausedCreativeMiner = false;
					resumeMiningCycle(false);
				} else if (miningProcess != null) {
					AreaMiningStatus miningStatus = miningProcess.getAreaMiningStatus();
					if (miningStatus.state() == AreaMiningStatus.State.CANCELLED
							|| miningStatus.state() == AreaMiningStatus.State.IDLE) {
						resumeMiningCycle(false);
					} else {
						miningProcess.resume();
					}
					transition(AutomationState.MINING, Translations.DETAIL.message("eating_complete_mining"));
				}
			} else if (stateBeforeEating == AutomationState.COLLECTING_DROPS) {
				transition(AutomationState.COLLECTING_DROPS, Translations.DETAIL.message("eating_complete_collecting"));
			} else if (isManagedNavigationState(stateBeforeEating)) {
				transition(stateBeforeEating, Translations.DETAIL.message("eating_complete_navigation"));
				watchdogStationaryScans = 0;
				navOscillationTracker.clearWindow();
				restartWatchedNavigation();
			} else transition(stateBeforeEating, Translations.DETAIL.message("eating_complete"));
			return;
		}
		if (eatingSyncGraceTicks > 0) {
			eatingSyncGraceTicks = 0;
			baritone.getPlayerContext().playerController().processRightClick(
					player, baritone.getPlayerContext().world(), InteractionHand.MAIN_HAND);
		}
		mc.options.keyUse.setDown(true);
	}

	private void executeInventoryClick() {
		InventoryClick click = inventoryClicks.poll();
		if (click == null) return;
		baritone.getPlayerContext().playerController().windowClick(
				baritone.getPlayerContext().player().containerMenu.containerId,
				click.slot(), click.button(), click.type(), baritone.getPlayerContext().player());
	}

	private void queuePickupSwap(int sourceMenuSlot, int targetMenuSlot) {
		inventoryClicks.add(new InventoryClick(sourceMenuSlot, 0, ContainerInput.PICKUP));
		inventoryClicks.add(new InventoryClick(targetMenuSlot, 0, ContainerInput.PICKUP));
		inventoryClicks.add(new InventoryClick(sourceMenuSlot, 0, ContainerInput.PICKUP));
	}

	private int emptyInventorySlots() {
		int empty = 0;
		Inventory inventory = baritone.getPlayerContext().player().getInventory();
		for (int slot = 0; slot < 36; slot++) if (inventory.getItem(slot).isEmpty()) empty++;
		return empty;
	}

	private int inventoryItemCount() {
		int count = 0;
		Inventory inventory = baritone.getPlayerContext().player().getInventory();
		for (int slot = 0; slot < 36; slot++) count += inventory.getItem(slot).getCount();
		return count;
	}

	private boolean isRetaliationEligible() {
		return RetaliationPolicy.active(state) || resourceScanner.isActive();
	}

	public void onDamage(net.minecraft.network.protocol.game.ClientboundDamageEventPacket packet) {
		retaliation.damage(packet, baritone != null && !deathRespawnPending && isRetaliationEligible());
	}
	/**
	 * Terminal states used to leave the task dead until a manual /perimeterdig start. Restart the same
	 * configuration automatically, bounded, and only while the user has not stopped or paused it.
	 */
	private boolean tickAutoRestart() {
		if (state != AutomationState.ERROR) return false;
		if (!autoRestartAllowed || originalTask == null) return false;
		long now = System.currentTimeMillis();
		if (now < nextAutoRestartAt) return false;
		if (autoRestarts >= AUTO_RESTART_LIMIT) {
			autoRestartAllowed = false;
			String finalReason = detail == null ? "" : detail.component().getString();
			if (!finalReason.isBlank()) {
				Minecraft.getInstance().gui.chatListener().handleSystemMessage(
						net.minecraft.network.chat.Component.literal("[Perimeter] 自动重启已达上限，停止原因：" + finalReason), false);
			}
			return false;
		}
		Runnable task = originalTask;
		if (task == null) return false;
		String reason = detail == null ? "" : detail.component().getString();
		autoRestarts++;
		nextAutoRestartAt = now + 5000L * autoRestarts;
		try {
			autoRestarting = true;
			task.run();
			var notice = Translations.DETAIL.tr("auto_restart", autoRestarts, AUTO_RESTART_LIMIT);
			if (!reason.isBlank()) notice = notice.append(net.minecraft.network.chat.Component.literal(" (" + reason + ")"));
			Minecraft.getInstance().gui.chatListener().handleSystemMessage(notice, false);
		} catch (RuntimeException exception) {
			PerimeterDigger.LOGGER.warn("Automatic mining restart failed", exception);
			nextAutoRestartAt = now + 15000L;
		}
		return true;
	}

	private void suspendForRetaliation() {
		suspendForInterruption(true);
	}

	/** Combat closes an open menu it cannot click through; a manual pause keeps the flow's container intact. */
	private void suspendForInterruption(boolean closeContainer) {
		if (closeContainer) {
			var mc = Minecraft.getInstance();
			var openPlayer = mc.player;
			if (openPlayer != null) {
				// A mob attack must not deadlock against an open chest/furnace/trade menu or F9/inventory screen.
				inventoryClicks.clear();
				if (!(openPlayer.containerMenu instanceof InventoryMenu)
						|| !openPlayer.containerMenu.getCarried().isEmpty()
						|| (mc.gui != null && mc.gui.screen() != null)) {
					openPlayer.closeContainer();
				}
				if (mc.gui != null && mc.gui.screen() != null) {
					mc.gui.setScreen(null);
				}
			}
		}
		if (schematicBuilder.isActive()) {
			if (closeContainer) {
				schematicBuilder.suspendForInterruption(baritone, navigation);
			} else {
				schematicBuilder.pause(baritone, navigation);
			}
		} else if (creativeMiner.isActive()) {
			AreaMiningStatus status = creativeMiner.status();
			if (interruptedMining == null && state == AutomationState.MINING && area != null) {
				var interruptedArea = area;
				long remaining = status.blockLimit() == Long.MAX_VALUE ? Long.MAX_VALUE
					: Math.max(0L, status.blockLimit() - status.minedBlocks());
				var options = new AreaMiningOptions(liquidPolicy, sealingBlocks, remaining, activeProtectedBlocks(), miningStrategy, miningBlacklistEnabled, activeTargetBlocks());
				interruptedMining = () -> startMiningMode(interruptedArea, options);
			}
			creativeMiner.stop(baritone);
		} else if (miningProcess != null) {
			AreaMiningStatus status = miningProcess.getAreaMiningStatus();
			if (interruptedMining == null && state == AutomationState.MINING && area != null) {
				// Cancellation and combat arbitration discard the builder schematic.
				var interruptedArea = area;
				long remaining = status.blockLimit() == Long.MAX_VALUE ? Long.MAX_VALUE
					: Math.max(0L, status.blockLimit() - status.minedBlocks());
				var options = new AreaMiningOptions(liquidPolicy, sealingBlocks, remaining, activeProtectedBlocks(), miningStrategy, miningBlacklistEnabled, activeTargetBlocks());
				interruptedMining = () -> miningProcess.mineArea(interruptedArea, options);
			}
			miningProcess.pause();
		}
		navigation.suspend();
		baritone.getPathingBehavior().forceCancel();
		baritone.getInputOverrideHandler().clearAllKeys();
		var mc = Minecraft.getInstance();
		mc.options.keyUse.setDown(false);
		mc.options.keyAttack.setDown(false);
		mc.options.keyUp.setDown(false);
		mc.options.keyDown.setDown(false);
		mc.options.keyLeft.setDown(false);
		mc.options.keyRight.setDown(false);
		mc.options.keyJump.setDown(false);
		if (mc.gameMode != null) mc.gameMode.releaseUsingItem(mc.player);
		baritone.getPlayerContext().playerController().resetBlockRemoving();
	}
	private void restoreAfterRetaliation() {
		resetNavigationWatchdog();
		Runnable restoreMining = interruptedMining;
		interruptedMining = null;
		if (restoreMining != null) {
			try {
				restoreMining.run();
				if (state != AutomationState.MINING || miningStallRecovery) {
					creativeMiner.stop(baritone);
					if (miningProcess != null) miningProcess.pause();
				}
			} catch (RuntimeException exception) {
				// Never leave combat recovery with a dead builder; fall back to a fresh cycle from configuration.
				if (state == AutomationState.MINING) resumeMiningCycle();
			}
		} else if (state == AutomationState.MINING && miningProcess != null && !miningStallRecovery) {
			if (navigation.canUseCreativeFlight()) {
				resumeMiningCycle();
			} else {
				AreaMiningStatus status = miningProcess.getAreaMiningStatus();
				if (status.state() == AreaMiningStatus.State.CANCELLED || status.state() == AreaMiningStatus.State.IDLE) resumeMiningCycle();
				else miningProcess.resume();
			}
		}
		if (state == AutomationState.BUILDING && schematicBuilder.isActive()) {
			schematicBuilder.resumeAfterInterruption();
		}
		navigation.resumeSuspended();
		if (state == AutomationState.EATING) {
			var mc = Minecraft.getInstance();
			mc.options.keyUse.setDown(true);
			baritone.getPlayerContext().playerController().processRightClick(mc.player, mc.level, InteractionHand.MAIN_HAND);
		}
	}

	private void stopProcesses() {
		interruptedMining = null;
		continuation.cancel();
		retaliation.cancel();
		pausedTask = null;
		deathRespawnPending = false;
		navigationBacktrack = false;
		navigationBacktrackTarget = null;
		navigationBacktrackTicks = 0;
		eatingPausedCreativeMiner = false;
		eatingPreviousSelectedSlot = -1;
		eatingSwappedBackpackSlot = -1;
		eatingSwappedHotbarSlot = -1;
		eatingSyncGraceTicks = 0;
		Minecraft.getInstance().options.keyUse.setDown(false);
		if (baritone != null) {
			baritone.getInputOverrideHandler().clearAllKeys();
		}
		creativeMiner.stop(baritone);
		schematicBuilder.stop(baritone, navigation);
		resourceScanner.stop(baritone, navigation);
		if (miningProcess != null) miningProcess.cancel();
		navigation.stop();
		if (baritone != null) {
			baritone.getInputOverrideHandler().clearAllKeys();
			if (Minecraft.getInstance().gameMode != null) {
				baritone.getPlayerContext().playerController().resetBlockRemoving();
			}
		}
	}

	private void emergencyCleanup() {
		interruptedMining = null;
		continuation.cancel();
		deathRespawnPending = false;
		retaliation.cancel();
		safeCleanup("release use key", () -> Minecraft.getInstance().options.keyUse.setDown(false));
		safeCleanup("release attack key", () -> Minecraft.getInstance().options.keyAttack.setDown(false));
		safeCleanup("release sneak key", () -> Minecraft.getInstance().options.keyShift.setDown(false));
		safeCleanup("clear Baritone inputs", () -> {
			if (baritone != null) baritone.getInputOverrideHandler().clearAllKeys();
		});
		safeCleanup("cancel creative flight mining", () -> {
			creativeMiner.stop(baritone);
		});
		safeCleanup("stop schematic builder", () -> {
			schematicBuilder.stop(baritone, navigation);
		});
		safeCleanup("stop schematic resource scanner", () -> {
			resourceScanner.stop(baritone, navigation);
		});
		safeCleanup("restore protected blocks setting", this::restoreProtectedBlocksSetting);
		safeCleanup("restore scaffold blocks setting", this::restoreScaffoldBlocksSetting);
		safeCleanup("cancel mining", () -> {
			if (miningProcess != null) miningProcess.cancel();
		});
		safeCleanup("cancel walking", () -> {
			navigation.stopWalking();
		});
		safeCleanup("cancel elytra flight", () -> {
			navigation.stopFlying();
		});
		safeCleanup("stop block breaking", () -> {
			if (baritone != null && Minecraft.getInstance().gameMode != null) {
				baritone.getPlayerContext().playerController().resetBlockRemoving();
			}
		});
		safeCleanup("close container", () -> {
			if (baritone != null && baritone.getPlayerContext().player() != null
					&& !(baritone.getPlayerContext().player().containerMenu instanceof InventoryMenu)) {
				baritone.getPlayerContext().player().closeContainer();
			}
		});
		safeCleanup("restore destination navigation settings", this::restoreAllowPlace);
		safeCleanup("restore elytra durability setting", this::restoreElytraMinimumDurability);
		safeCleanup("restore elytra firework setting", this::restoreElytraFireworkReserve);
		safeCleanup("restore repair restrictions", this::restoreRepairRestrictions);
		inventoryClicks.clear();
		resetNavigationWatchdog();
	}

	private static void safeCleanup(String action, Runnable cleanup) {
		try {
			cleanup.run();
		} catch (RuntimeException exception) {
			PerimeterDigger.LOGGER.error("Failed to {} after an automation error", action, exception);
		}
	}

	private void transition(AutomationState next, LocalizedMessage nextDetail) {
		if (next == AutomationState.ERROR) {
			restoreAllowPlace();
			if (repair.stage != null) {
				restoreRepairNavigationSettings();
				restoreRepairRestrictions();
			}
		}
		recordTransition(state, next, nextDetail);
		state = next;
		detail = nextDetail;
		changedAt = Instant.now();
	}

	private void synchronize(AutomationState next, LocalizedMessage nextDetail) {
		if (state != next) {
			recordTransition(state, next, nextDetail);
			changedAt = Instant.now();
		}
		state = next;
		detail = nextDetail;
	}

	private void recordTransition(AutomationState previous, AutomationState next, LocalizedMessage transitionDetail) {
		String dimension = "unavailable";
		String position = "unavailable";
		try {
			if (baritone != null && baritone.getPlayerContext().world() != null) {
				dimension = baritone.getPlayerContext().world().dimension().identifier().toString();
			}
			if (baritone != null && baritone.getPlayerContext().player() != null) {
				position = baritone.getPlayerContext().playerFeet().toShortString();
			}
		} catch (RuntimeException ignored) {
		}
		PerimeterDigger.LOGGER.info("Perimeter state {} -> {} ({}) at {}", previous, next, transitionDetail.component().getString(), position);
		stateHistory.addLast(new StateTransition(Instant.now(), previous, next, transitionDetail, dimension, position));
		while (stateHistory.size() > STATE_HISTORY_LIMIT) stateHistory.removeFirst();
	}

	private List<String> validate(PerimeterConfig config) {
		List<String> invalid = new ArrayList<>();
		if (config.detectedArea == null || config.detectedArea.scanlines == null || config.detectedArea.scanlines.isEmpty()) invalid.add("detected_area");
		if (config.diggingMinY == null) invalid.add("digging_min_y");
		if (config.diggingMaxY == null) invalid.add("digging_max_y");
		if (config.diggingMinY != null && config.diggingMaxY != null && config.diggingMinY > config.diggingMaxY) invalid.add("valid_digging_y_range");
		if (!List.of("avoid", "replace", "seal_boundary").contains(config.liquidPolicy)) invalid.add("liquid_policy");
		if (config.miningStrategy != null && !List.of("global_top_down", "local_top_down", "chunk_top_down").contains(config.miningStrategy)) invalid.add("mining_strategy");
		if (!List.of("repair_portal", "supply_point").contains(config.durabilityRecoveryMode)) invalid.add("durability_recovery_mode");
		if (config.functions != null && config.functions.miningBlacklist && (config.miningBlacklist == null || config.miningBlacklist.isEmpty())) invalid.add("mining_blacklist");
		return invalid;
	}

	private List<String> validateRepairConfiguration(PerimeterConfig config) {
		List<String> invalid = new ArrayList<>();
		boolean crossDimension = config.functions == null || config.functions.crossDimensionRepair;
		if (crossDimension && config.perimeterPortalOverworld == null) invalid.add("perimeter_portal_overworld");
		if (crossDimension && config.perimeterPortalNether == null) invalid.add("perimeter_portal_nether");
		if (crossDimension && config.repairPortalOverworld == null) invalid.add("repair_portal_overworld");
		if (crossDimension && config.repairPortalNether == null) invalid.add("repair_portal_nether");
		if (config.furnaceRowStart == null) invalid.add("furnace_row_start");
		if (config.furnaceRowEnd == null) invalid.add("furnace_row_end");
		if (config.furnaceRowStart != null && config.furnaceRowEnd != null) {
			int changedAxes = (config.furnaceRowStart.x == config.furnaceRowEnd.x ? 0 : 1)
					+ (config.furnaceRowStart.y == config.furnaceRowEnd.y ? 0 : 1)
					+ (config.furnaceRowStart.z == config.furnaceRowEnd.z ? 0 : 1);
			if (changedAxes > 1) invalid.add("axis_aligned_furnace_row");
		}
		return invalid;
	}

	private static AreaMiningLiquidPolicy policy(String value) {
		return switch (value) {
			case "avoid" -> AreaMiningLiquidPolicy.AVOID;
			case "replace" -> AreaMiningLiquidPolicy.REPLACE;
			case "seal_boundary" -> AreaMiningLiquidPolicy.SEAL_BOUNDARY;
			default -> throw new IllegalArgumentException("Unknown liquid policy: " + value);
		};
	}

	private static AreaMiningStrategy strategy(String value) {
		if (value == null || value.isBlank()) return AreaMiningStrategy.GLOBAL_TOP_DOWN;
		return switch (value) {
			case "global_top_down" -> AreaMiningStrategy.GLOBAL_TOP_DOWN;
			case "local_top_down" -> AreaMiningStrategy.LOCAL_TOP_DOWN;
			case "chunk_top_down" -> AreaMiningStrategy.CHUNK_TOP_DOWN;
			default -> throw new IllegalArgumentException("Unknown mining strategy: " + value);
		};
	}

	private static List<Block> sealingBlocks(List<String> values) {
		List<Block> blocks = new ArrayList<>();
		for (String value : values) {
			Identifier id = Identifier.tryParse(value);
			Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
			if (block == null) throw new IllegalArgumentException("Unknown sealing block: " + value);
			blocks.add(block);
		}
		return List.copyOf(blocks);
	}

	private static List<Block> miningWhitelistBlocks(List<String> values) {
		return resolveBlockList(values, "mining whitelist");
	}

	private static List<Block> miningBlacklistBlocks(List<String> values) {
		return resolveBlockList(values, "mining blacklist");
	}

	private static List<Block> resolveBlockList(List<String> values, String label) {
		if (values == null || values.isEmpty()) return List.of();
		List<Block> blocks = new ArrayList<>();
		for (String value : values) {
			Identifier id = Identifier.tryParse(value);
			Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
			if (block == null || block.defaultBlockState().isAir()) {
				throw new IllegalArgumentException("Unknown " + label + " block: " + value);
			}
			if (!blocks.contains(block)) blocks.add(block);
		}
		return List.copyOf(blocks);
	}

	private List<Block> activeProtectedBlocks() {
		return miningWhitelistEnabled ? miningWhitelistBlocks : List.of();
	}

	private List<Block> activeTargetBlocks() {
		return miningBlacklistEnabled ? miningBlacklistBlocks : List.of();
	}

	private void syncProtectedBlocksSetting() {
		List<Block> active = activeProtectedBlocks();
		if (active.isEmpty()) {
			restoreProtectedBlocksSetting();
			return;
		}
		if (savedBlocksToDisallowBreaking == null) {
			savedBlocksToDisallowBreaking = new ArrayList<>(BaritoneAPI.getSettings().blocksToDisallowBreaking.value);
		}
		List<Block> merged = new ArrayList<>(savedBlocksToDisallowBreaking);
		for (Block block : active) {
			if (!merged.contains(block)) merged.add(block);
		}
		BaritoneAPI.getSettings().blocksToDisallowBreaking.value = merged;
	}

	private void restoreProtectedBlocksSetting() {
		if (savedBlocksToDisallowBreaking != null) {
			BaritoneAPI.getSettings().blocksToDisallowBreaking.value = new ArrayList<>(savedBlocksToDisallowBreaking);
			savedBlocksToDisallowBreaking = null;
		}
	}

	private void syncScaffoldBlocksSetting() {
		if (savedAcceptableThrowawayItems == null) {
			savedAcceptableThrowawayItems = new ArrayList<>(BaritoneAPI.getSettings().acceptableThrowawayItems.value);
		}
		List<Item> throwaway = new ArrayList<>();
		if (!scaffoldBlocks.isEmpty()) {
			for (Block block : scaffoldBlocks) {
				Item item = block.asItem();
				if (item != Items.AIR && !throwaway.contains(item)) {
					throwaway.add(item);
				}
			}
		}
		if (throwaway.isEmpty()) {
			throwaway.addAll(savedAcceptableThrowawayItems);
			for (Block block : sealingBlocks) {
				Item item = block.asItem();
				if (item != Items.AIR && !throwaway.contains(item)) {
					throwaway.add(item);
				}
			}
		}
		BaritoneAPI.getSettings().acceptableThrowawayItems.value = throwaway;
	}

	private void restoreScaffoldBlocksSetting() {
		if (savedAcceptableThrowawayItems != null) {
			BaritoneAPI.getSettings().acceptableThrowawayItems.value = new ArrayList<>(savedAcceptableThrowawayItems);
			savedAcceptableThrowawayItems = null;
		}
	}

	private static Set<Item> foods(List<String> values) {
		return items(values);
	}

	private static Set<Item> items(List<String> values) {
		Set<Item> foods = new HashSet<>();
		for (String value : values) {
			Identifier id = Identifier.tryParse(value);
			Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
			if (item == null) throw new IllegalArgumentException("Unknown food item: " + value);
			foods.add(item);
		}
		return Set.copyOf(foods);
	}

	private static int remainingDurability(ItemStack stack) {
		return stack.getMaxDamage() - stack.getDamageValue();
	}

	public boolean isUsingCreativeFlight() {
		return creativeMiner.isActive() || schematicBuilder.isUsingCreativeFlight() || navigation.isCreativeFlying();
	}

	public String startSchematicResourceScan(PerimeterConfig config) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return "当前未进入世界，无法扫描资源点";
		}
		if (RetaliationPolicy.active(state)) {
			return "当前有运行中的自动化任务，请先暂停或停止后再扫描资源点";
		}
		bindBaritone();
		navigation.bind(baritone);
		updateFunctions(config.functions);
		navigation.disableBreakingAndPlacement();
		String err = resourceScanner.startScanAll(config, mc.level, mc.player);
		if (err != null) {
			restoreAllowPlace();
			return err;
		}
		detail = LocalizedMessage.literal(resourceScanner.statusDetail());
		return null;
	}

	private List<String> startSchematicBuild(PerimeterConfig config) {
		PerimeterConfig saved = copyConfig(config);
		transition(AutomationState.VALIDATING, LocalizedMessage.literal("正在检查投影与打印机配置..."));
		stopProcesses();
		restoreAllowPlace();
		restoreElytraMinimumDurability();
		restoreElytraFireworkReserve();
		restoreRepairRestrictions();
		resetMiningStall();
		resetNavigationWatchdog();
		stallTracker.reset();
		eatingCooldownUntilTick = 0;
		this.latestConfig = saved;
		this.originalTask = () -> start(this.latestConfig != null ? this.latestConfig : saved);
		this.baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
		this.miningProcess = baritone.getAreaMineProcess();
		navigation.bind(baritone);
		this.allowedFoods = foods(config.foods);
		this.sealingBlocks = sealingBlocks(config.sealingBlocks);
		this.scaffoldBlocks = sealingBlocks(config.scaffoldBlocks);
		this.miningWhitelistBlocks = miningWhitelistBlocks(config.miningWhitelist);
		updateFunctions(config.functions);
		updateAdvanced(config.advanced);
		applyMiningSettings();
		this.autoRestartAllowed = false;
		this.inventoryClicks.clear();
		String err = schematicBuilder.start(baritone, navigation, config);
		if (err != null) {
			transition(AutomationState.ERROR, LocalizedMessage.literal(err));
			return List.of("schematic_build");
		}
		transition(AutomationState.BUILDING, LocalizedMessage.literal(schematicBuilder.statusDetail()));
		return List.of();
	}

	/** Returns the active/paused schematic builder target Y level, or null if not currently building. */
	public Integer currentSchematicTargetY() {
		return schematicBuilder.currentTargetY();
	}

	private int effectiveToolDurabilityThreshold() {
		return durabilityProtectionEnabled ? advanced.toolDurabilityThreshold : -1;
	}

	private int durabilityThreshold(ItemStack stack) {
		return durabilityThreshold(stack.getItem());
	}

	private int durabilityThreshold(Item item) {
		return item == Items.ELYTRA ? advanced.elytraDurabilityThreshold : effectiveToolDurabilityThreshold();
	}

	private int dropStableScanLimit() {
		return monitorScans(advanced.dropCollectionStableSeconds);
	}

	private double navigationFlightDistanceSquared() {
		return (double) advanced.elytraNavigationMinDistance * advanced.elytraNavigationMinDistance;
	}

	private static int ticks(double seconds) {
		return Math.max(1, (int) Math.ceil(seconds * 20.0));
	}

	private static int monitorScans(double seconds) {
		return Math.max(1, (int) Math.ceil(seconds * 20.0 / MONITOR_INTERVAL_TICKS));
	}

	private static int menuSlot(int inventorySlot) {
		return inventorySlot < 9 ? inventorySlot + 36 : inventorySlot;
	}

	private static LocalizedMessage progress(AreaMiningStatus status) {
		Object remaining = status.knownRemainingBlocks() < 0L ? Translations.VALUE.tr("scanning") : status.knownRemainingBlocks();
		return Translations.DETAIL.message("mining_progress", status.minedBlocks(), status.blockLimit(), remaining, status.estimatedTotalBlocks());
	}

	private record StackSlot(ItemStack stack, int inventorySlot, int menuSlot, boolean offhand, boolean chest) {
	}

	private record Replacement(StackSlot low, StackSlot healthy) {
	}

	private record ResourceCheck(boolean repairRequired, String repairItem, Replacement replacement) {
	}

	private record InventoryClick(int slot, int button, ContainerInput type) {
	}

	private record ReturnRouteCandidate(ReturnWaypoint waypoint, double cost) {
	}

	private enum ReturnAction {
		NONE,
		AFTER_UNLOAD,
		AFTER_SUPPLY,
		AFTER_REPAIR,
		AFTER_SLEEP
	}

	private enum ReturnWaypoint {
		MINE("mining departure point"),
		REPAIR_OVERWORLD("Overworld repair portal"),
		REPAIR_NETHER("Nether repair portal"),
		PERIMETER_OVERWORLD("Overworld perimeter portal"),
		PERIMETER_NETHER("Nether perimeter portal");

		private final String displayName;

		ReturnWaypoint(String displayName) {
			this.displayName = displayName;
		}

		private ReturnWaypoint counterpart() {
			return switch (this) {
				case REPAIR_OVERWORLD -> REPAIR_NETHER;
				case REPAIR_NETHER -> REPAIR_OVERWORLD;
				case PERIMETER_OVERWORLD -> PERIMETER_NETHER;
				case PERIMETER_NETHER -> PERIMETER_OVERWORLD;
				case MINE -> throw new IllegalStateException("The mining point has no portal counterpart.");
			};
		}
	}

}