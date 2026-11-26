package hackerrouter.perimeterdigger.client.build;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.RotationUtils;
import hackerrouter.perimeterdigger.client.config.PerimeterConfig;
import hackerrouter.perimeterdigger.client.config.PositionConfig;
import hackerrouter.perimeterdigger.client.flight.CreativeFlightNavigator;
import hackerrouter.perimeterdigger.client.navigation.NavigationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handles visiting multiple configured resource containers (chests, barrels, placed shulker boxes)
 * and unpacking carried or chest-stored shulker box items for:
 * 1) Scanning all configured resource points to populate {@link SchematicResourceCache} and
 *    evaluate layer-by-layer build sufficiency;
 * 2) Automated resupply during schematic building (schematic blocks, water buckets, foods,
 *    healthy tools, and scaffold blocks).
 */
public final class SchematicResourceController {
	public enum Mode {
		IDLE,
		SCAN_ONLY,
		RESUPPLY
	}

	public enum StepOutcome {
		RUNNING,
		DONE_WITH_PROGRESS,
		DONE_NO_PROGRESS
	}

	private enum ChestPhase {
		MOVING,
		OPENING,
		SYNCING,
		TRANSFERRING,
		CLOSING,
		UNPACK_PLACE,
		UNPACK_OPEN,
		UNPACK_SYNC,
		UNPACK_TRANSFER,
		UNPACK_CLOSE,
		UNPACK_BREAK,
		UNPACK_COLLECT,
		RETURN_OPEN,
		RETURN_DEPOSIT,
		RETURN_CLOSE
	}

	private record ChestTarget(String name, BlockPos pos) {}

	private static final double CHEST_REACH = 4.2;
	private static final int MOVE_TIMEOUT_TICKS = 180;
	private static final int OPEN_TIMEOUT_TICKS = 60;
	private static final int SYNC_WAIT_TICKS = 5;
	private static final int CLOSE_WAIT_TICKS = 20;
	private static final int BREAK_TIMEOUT_TICKS = 120;
	private static final int COLLECT_TIMEOUT_TICKS = 65;

	private final CreativeFlightNavigator flightNav = new CreativeFlightNavigator();

	private Mode mode = Mode.IDLE;
	private ChestPhase phase = ChestPhase.MOVING;
	private final List<ChestTarget> pendingChests = new ArrayList<>();
	private ChestTarget activeChest;
	private PerimeterConfig activeConfig;

	private final Map<String, Integer> neededSchematicItems = new LinkedHashMap<>();
	private Set<String> foodIds = Set.of();
	private Set<String> scaffoldIds = Set.of();
	private int neededWaterBuckets;
	private int neededFoodCount;
	private int neededScaffoldCount;
	private boolean neededHealthyTool;
	private int toolDurabilityThreshold;

	// Temporary shulker box unpack tracking
	private BlockPos tempShulkerPos;
	private BlockPos shulkerReturnChestPos;
	private Item unpackingShulkerItem;
	private int shulkerCountBeforeBreak;
	private boolean breakingShulker;

	private boolean moveGoalDispatched;
	private boolean usingFarApproachGoal;
	private int moveGraceTicks;
	private int phaseTicks;
	private int moveHardLimitTicks = MOVE_TIMEOUT_TICKS;
	private double bestMoveDistSq = Double.MAX_VALUE;
	private int noProgressMoveTicks;
	private int totalWithdrawnCount;
	private int scannedChestsThisRun;
	private int totalChestsThisRun;
	private String statusDetail = "";

	public boolean isActive() {
		return mode != Mode.IDLE;
	}

	public Mode mode() {
		return mode;
	}

	public String statusDetail() {
		return statusDetail;
	}

	public void stop(IBaritone baritone, NavigationService navigation) {
		if (baritone != null) {
			flightNav.stop(baritone);
		}
		if (navigation != null) {
			navigation.stopWalking();
		}
		Minecraft mc = Minecraft.getInstance();
		if (breakingShulker && mc.gameMode != null) {
			mc.gameMode.stopDestroyBlock();
		}
		breakingShulker = false;
		LocalPlayer player = mc.player;
		if (player != null && !(player.containerMenu instanceof InventoryMenu)) {
			player.closeContainer();
		}
		mode = Mode.IDLE;
		pendingChests.clear();
		activeChest = null;
		tempShulkerPos = null;
		shulkerReturnChestPos = null;
		unpackingShulkerItem = null;
		neededSchematicItems.clear();
		moveGoalDispatched = false;
		usingFarApproachGoal = false;
		moveGraceTicks = 0;
		phaseTicks = 0;
		statusDetail = "";
	}

	/** Starts an automatic sweep of all configured resource containers to populate the cache. */
	public String startScanAll(PerimeterConfig config, Level level, LocalPlayer player) {
		if (config == null || config.schematicResourcePoints == null || config.schematicResourcePoints.isEmpty()) {
			return "尚未配置任何投影资源点（箱子/木桶/潜影盒）";
		}
		if (level == null || player == null) {
			return "当前未进入世界，无法扫描资源点";
		}
		List<ChestTarget> targets = deduplicateChests(level, config.schematicResourcePoints);
		if (targets.isEmpty()) {
			return "没有有效的资源点坐标";
		}
		sortNearest(targets, player.blockPosition());

		this.mode = Mode.SCAN_ONLY;
		this.activeConfig = config;
		this.pendingChests.clear();
		this.pendingChests.addAll(targets);
		this.activeChest = null;
		this.tempShulkerPos = null;
		this.shulkerReturnChestPos = null;
		this.unpackingShulkerItem = null;
		this.neededSchematicItems.clear();
		this.neededWaterBuckets = 0;
		this.neededFoodCount = 0;
		this.neededScaffoldCount = 0;
		this.neededHealthyTool = false;
		this.totalWithdrawnCount = 0;
		this.scannedChestsThisRun = 0;
		this.totalChestsThisRun = targets.size();
		this.statusDetail = "准备扫描 " + targets.size() + " 个投影资源点（含箱内潜影盒）...";
		chat("[Perimeter] 开始自动扫描 " + targets.size() + " 个投影资源点（支持箱子/木桶/潜影盒及箱内潜影盒）...");
		return null;
	}

	/**
	 * Starts automatic resupply from carried shulker boxes and/or configured resource containers
	 * (chests, barrels, placed shulker boxes, and shulker boxes inside chests).
	 */
	public boolean startResupply(
			PerimeterConfig config,
			Level level,
			LocalPlayer player,
			Map<String, Integer> missingSchematicItems,
			int needWaterBuckets,
			int needFoodCount,
			int needScaffoldCount,
			boolean needHealthyTool,
			int durabilityThreshold
	) {
		if (level == null || player == null) {
			return false;
		}

		this.neededSchematicItems.clear();
		if (missingSchematicItems != null) {
			for (Map.Entry<String, Integer> e : missingSchematicItems.entrySet()) {
				if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null && e.getValue() > 0) {
					this.neededSchematicItems.put(e.getKey(), e.getValue());
				}
			}
		}
		this.neededWaterBuckets = Math.max(0, needWaterBuckets);
		this.neededFoodCount = Math.max(0, needFoodCount);
		this.neededScaffoldCount = Math.max(0, needScaffoldCount);
		this.neededHealthyTool = needHealthyTool;
		this.toolDurabilityThreshold = durabilityThreshold;
		this.foodIds = (config == null || config.foods == null) ? Set.of() : new HashSet<>(config.foods);
		Set<String> scaffolds = new HashSet<>();
		if (config != null && config.scaffoldBlocks != null && !config.scaffoldBlocks.isEmpty()) {
			scaffolds.addAll(config.scaffoldBlocks);
		} else if (config != null && config.sealingBlocks != null) {
			scaffolds.addAll(config.sealingBlocks);
		}
		this.scaffoldIds = scaffolds;

		if (!hasRemainingNeeds()) {
			return false;
		}

		Set<String> allNeededIds = collectAllNeededIds();

		// Priority 0: Check if the player ALREADY carries a shulker box containing needed items!
		int carriedShulkerSlot = findMatchingCarriedShulkerSlot(player, allNeededIds);
		BlockPos localPlacePos = carriedShulkerSlot >= 0 ? findSafeShulkerPlacePos(level, player) : null;

		List<ChestTarget> prioritized = new ArrayList<>();
		if (config != null && config.schematicResourcePoints != null && !config.schematicResourcePoints.isEmpty()) {
			List<ChestTarget> allChests = deduplicateChests(level, config.schematicResourcePoints);
			for (ChestTarget t : allChests) {
				PositionConfig pc = new PositionConfig(t.pos().getX(), t.pos().getY(), t.pos().getZ());
				int prio = SchematicResourceCache.matchPriority(level, pc, allNeededIds, needHealthyTool);
				if (prio > 0) {
					prioritized.add(t);
				}
			}
			if (prioritized.isEmpty()) {
				long now = System.currentTimeMillis();
				for (ChestTarget t : allChests) {
					var snap = SchematicResourceCache.getSnapshot(level,
							new PositionConfig(t.pos().getX(), t.pos().getY(), t.pos().getZ()));
					if (snap == null || now - snap.scannedAtMs() > 20_000L) {
						prioritized.add(t);
					}
				}
			}
			BlockPos origin = player.blockPosition();
			prioritized.sort((a, b) -> {
				int pa = SchematicResourceCache.matchPriority(level,
						new PositionConfig(a.pos().getX(), a.pos().getY(), a.pos().getZ()), allNeededIds, needHealthyTool);
				int pb = SchematicResourceCache.matchPriority(level,
						new PositionConfig(b.pos().getX(), b.pos().getY(), b.pos().getZ()), allNeededIds, needHealthyTool);
				if (pa != pb) return Integer.compare(pb, pa);
				return Double.compare(a.pos().distSqr(origin), b.pos().distSqr(origin));
			});
		}

		if (localPlacePos == null && prioritized.isEmpty()) {
			return false;
		}

		this.mode = Mode.RESUPPLY;
		this.activeConfig = config;
		this.pendingChests.clear();
		this.pendingChests.addAll(prioritized);
		this.activeChest = null;
		this.tempShulkerPos = null;
		this.shulkerReturnChestPos = null;
		this.unpackingShulkerItem = null;
		this.totalWithdrawnCount = 0;
		this.scannedChestsThisRun = 0;
		this.totalChestsThisRun = prioritized.size();

		if (localPlacePos != null) {
			ItemStack shulkerStack = player.getInventory().getItem(carriedShulkerSlot);
			this.tempShulkerPos = localPlacePos;
			this.shulkerReturnChestPos = null;
			this.unpackingShulkerItem = shulkerStack.getItem();
			this.phase = ChestPhase.UNPACK_PLACE;
			this.phaseTicks = 0;
			this.statusDetail = "就地展开背包潜影盒取料...";
			chat("[Perimeter] 检测到随身潜影盒含所需材料，就地展开取料...");
			return true;
		}

		this.phase = ChestPhase.MOVING;
		this.statusDetail = "前往投影资源点补料（候选 " + prioritized.size() + " 个容器）...";
		chat("[Perimeter] 背包缺料，前往已配置的 " + prioritized.size() + " 个投影资源点自动取料...");
		return true;
	}

	public StepOutcome tick(IBaritone baritone, NavigationService navigation) {
		if (mode == Mode.IDLE) {
			return StepOutcome.DONE_NO_PROGRESS;
		}
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		Level level = mc.level;
		if (player == null || level == null) {
			return StepOutcome.RUNNING;
		}

		// Handle active temporary shulker box unpack/cleanup phases first (never abandon a placed shulker box!).
		if (isShulkerUnpackPhase()) {
			return tickShulkerUnpack(baritone, navigation, mc, player, level);
		}

		if (activeChest == null) {
			if (mode == Mode.RESUPPLY && (!hasRemainingNeeds() || !hasAnyInventorySpace(player))) {
				return finishRun(baritone, navigation, mc);
			}
			if (pendingChests.isEmpty()) {
				return finishRun(baritone, navigation, mc);
			}
			activeChest = popNextChest(player.blockPosition());
			phase = ChestPhase.MOVING;
			moveGoalDispatched = false;
			usingFarApproachGoal = false;
			moveGraceTicks = 0;
			phaseTicks = 0;
		}

		return switch (phase) {
			case MOVING -> tickMoving(baritone, navigation, player, level);
			case OPENING -> tickOpening(baritone, navigation, player, level);
			case SYNCING -> tickSyncing(player, level);
			case TRANSFERRING -> tickTransferring(baritone, player, level);
			case CLOSING -> tickClosing(player, level);
			default -> StepOutcome.RUNNING;
		};
	}

	private boolean isShulkerUnpackPhase() {
		return switch (phase) {
			case UNPACK_PLACE, UNPACK_OPEN, UNPACK_SYNC, UNPACK_TRANSFER, UNPACK_CLOSE,
					UNPACK_BREAK, UNPACK_COLLECT, RETURN_OPEN, RETURN_DEPOSIT, RETURN_CLOSE -> true;
			default -> false;
		};
	}

	private StepOutcome tickMoving(IBaritone baritone, NavigationService navigation, LocalPlayer player, Level level) {
		Vec3 chestCenter = Vec3.atCenterOf(activeChest.pos());
		double distSq = player.position().distanceToSqr(chestCenter);
		double dist = player.getEyePosition().distanceTo(chestCenter);
		statusDetail = (mode == Mode.SCAN_ONLY ? "扫描资源点" : "资源点补料")
				+ " [" + (scannedChestsThisRun + 1) + "/" + Math.max(1, totalChestsThisRun) + "] 前往 "
				+ activeChest.name() + " (" + activeChest.pos().toShortString() + ")";
		if (dist <= CHEST_REACH && level.isLoaded(activeChest.pos())) {
			stopMovement(baritone, navigation);
			if (!SchematicResourceCache.isResourceContainer(level.getBlockState(activeChest.pos()).getBlock())) {
				chat("[Perimeter] 资源点「" + activeChest.name() + "」(" + activeChest.pos().toShortString() + ") 不是箱子/木桶/潜影盒，已跳过。");
				activeChest = null;
				return StepOutcome.RUNNING;
			}
			phase = ChestPhase.OPENING;
			phaseTicks = 0;
			return StepOutcome.RUNNING;
		}
		if (!moveGoalDispatched) {
			bestMoveDistSq = distSq;
			noProgressMoveTicks = 0;
			phaseTicks = 0;
			moveHardLimitTicks = Math.max(200, (int) (Math.sqrt(distSq) * 14.0) + 140);
		} else {
			if (distSq < bestMoveDistSq - 0.75 * 0.75) {
				bestMoveDistSq = distSq;
				noProgressMoveTicks = 0;
			} else if (++noProgressMoveTicks > 90) {
				stopMovement(baritone, navigation);
				chat("[Perimeter] 前往资源点「" + activeChest.name() + "」停滞，切换下一个资源点。");
				activeChest = null;
				return StepOutcome.RUNNING;
			}
		}
		if (++phaseTicks > moveHardLimitTicks) {
			stopMovement(baritone, navigation);
			chat("[Perimeter] 无法到达资源点「" + activeChest.name() + "」，切换下一个资源点。");
			activeChest = null;
			return StepOutcome.RUNNING;
		}
		boolean canFly = navigation != null && navigation.canUseCreativeFlight();
		if (canFly) {
			Vec3 hover = Vec3.atCenterOf(activeChest.pos().above(2));
			if (!flightNav.isActive() || flightNav.target() == null || flightNav.target().distanceToSqr(hover) > 1.0) {
				flightNav.flyTo(hover, 0.85, 0.75, false);
			}
			flightNav.tick(baritone);
			moveGoalDispatched = true;
			return StepOutcome.RUNNING;
		}
		if (!moveGoalDispatched) {
			Goal goal = dist > 10.0
					? new GoalNear(activeChest.pos(), 3)
					: SchematicWaterPlacer.buildGroundVantageGoal(level, activeChest.pos(), null, player.position(), CHEST_REACH - 0.2);
			usingFarApproachGoal = dist > 10.0;
			if (navigation != null) {
				navigation.walk(goal);
			}
			moveGoalDispatched = true;
			moveGraceTicks = 0;
			return StepOutcome.RUNNING;
		}
		if (++moveGraceTicks > 2 && (navigation == null || !navigation.isWalking())) {
			if (usingFarApproachGoal && dist <= 10.0) {
				usingFarApproachGoal = false;
				Goal goal = SchematicWaterPlacer.buildGroundVantageGoal(level, activeChest.pos(), null, player.position(), CHEST_REACH - 0.2);
				if (navigation != null) {
					navigation.walk(goal);
				}
				moveGraceTicks = 0;
				return StepOutcome.RUNNING;
			}
			stopMovement(baritone, navigation);
			chat("[Perimeter] 资源点「" + activeChest.name() + "」暂时不可达，切换下一个。");
			activeChest = null;
		}
		return StepOutcome.RUNNING;
	}

	private StepOutcome tickOpening(IBaritone baritone, NavigationService navigation, LocalPlayer player, Level level) {
		if (!(player.containerMenu instanceof InventoryMenu)) {
			phase = ChestPhase.SYNCING;
			phaseTicks = 0;
			return StepOutcome.RUNNING;
		}
		if (++phaseTicks > OPEN_TIMEOUT_TICKS) {
			chat("[Perimeter] 打开资源点「" + activeChest.name() + "」超时，已跳过。");
			activeChest = null;
			return StepOutcome.RUNNING;
		}
		rightClickContainer(baritone, player, level, activeChest.pos());
		return StepOutcome.RUNNING;
	}

	private StepOutcome tickSyncing(LocalPlayer player, Level level) {
		if (player.containerMenu instanceof InventoryMenu) {
			activeChest = null;
			return StepOutcome.RUNNING;
		}
		if (++phaseTicks < SYNC_WAIT_TICKS) {
			return StepOutcome.RUNNING;
		}
		SchematicResourceCache.recordOpenContainer(level, activeChest.pos(), player.containerMenu, toolDurabilityThreshold);
		scannedChestsThisRun++;
		if (mode == Mode.SCAN_ONLY) {
			player.closeContainer();
			phase = ChestPhase.CLOSING;
			phaseTicks = 0;
		} else {
			phase = ChestPhase.TRANSFERRING;
			phaseTicks = 0;
		}
		return StepOutcome.RUNNING;
	}

	private StepOutcome tickTransferring(IBaritone baritone, LocalPlayer player, Level level) {
		AbstractContainerMenu menu = player.containerMenu;
		if (menu instanceof InventoryMenu) {
			activeChest = null;
			return StepOutcome.RUNNING;
		}
		int containerSlots = Math.max(0, menu.slots.size() - 36);
		if (containerSlots <= 0 || !hasRemainingNeeds()) {
			SchematicResourceCache.recordOpenContainer(level, activeChest.pos(), menu, toolDurabilityThreshold);
			player.closeContainer();
			phase = ChestPhase.CLOSING;
			phaseTicks = 0;
			return StepOutcome.RUNNING;
		}

		// First try taking loose matching items directly from the container.
		int slotToTake = findNextDesiredSlot(player, menu, containerSlots);
		if (slotToTake >= 0) {
			ItemStack stack = menu.getSlot(slotToTake).getItem();
			String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			int taken = stack.getCount();
			baritone.getPlayerContext().playerController().windowClick(
					menu.containerId, slotToTake, 0, ContainerInput.QUICK_MOVE, player);
			deductNeedAfterTake(stack, id, taken);
			totalWithdrawnCount += taken;
			statusDetail = "正在从资源点「" + activeChest.name() + "」取料（已取 " + totalWithdrawnCount + "）";
			return StepOutcome.RUNNING;
		}

		// No loose matches left: check if this chest holds a Shulker Box item containing needed items!
		if (hasAnyInventorySpace(player) && tempShulkerPos == null) {
			int shulkerSlot = findMatchingShulkerSlotInMenu(menu, containerSlots);
			BlockPos placePos = shulkerSlot >= 0 ? findSafeShulkerPlacePos(level, player) : null;
			if (shulkerSlot >= 0 && placePos != null) {
				ItemStack shulkerStack = menu.getSlot(shulkerSlot).getItem();
				unpackingShulkerItem = shulkerStack.getItem();
				tempShulkerPos = placePos;
				shulkerReturnChestPos = activeChest.pos();
				baritone.getPlayerContext().playerController().windowClick(
						menu.containerId, shulkerSlot, 0, ContainerInput.QUICK_MOVE, player);
				SchematicResourceCache.recordOpenContainer(level, activeChest.pos(), menu, toolDurabilityThreshold);
				player.closeContainer();
				phase = ChestPhase.CLOSING;
				phaseTicks = 0;
				statusDetail = "从资源点「" + activeChest.name() + "」取出潜影盒准备展开...";
				return StepOutcome.RUNNING;
			}
		}

		SchematicResourceCache.recordOpenContainer(level, activeChest.pos(), menu, toolDurabilityThreshold);
		player.closeContainer();
		phase = ChestPhase.CLOSING;
		phaseTicks = 0;
		return StepOutcome.RUNNING;
	}

	private StepOutcome tickClosing(LocalPlayer player, Level level) {
		if (player.containerMenu instanceof InventoryMenu || ++phaseTicks > CLOSE_WAIT_TICKS) {
			if (tempShulkerPos != null && unpackingShulkerItem != null) {
				phase = ChestPhase.UNPACK_PLACE;
				phaseTicks = 0;
				return StepOutcome.RUNNING;
			}
			activeChest = null;
			phase = ChestPhase.MOVING;
			phaseTicks = 0;
		}
		return StepOutcome.RUNNING;
	}

	/** Safe lifecycle for unpacking a shulker box item: Place -> Open -> Take -> Close -> Break -> Collect -> Return. */
	private StepOutcome tickShulkerUnpack(
			IBaritone baritone,
			NavigationService navigation,
			Minecraft mc,
			LocalPlayer player,
			Level level
	) {
		switch (phase) {
			case UNPACK_PLACE -> {
				statusDetail = "放置临时潜影盒取料 (" + tempShulkerPos.toShortString() + ")";
				if (level.getBlockState(tempShulkerPos).getBlock() instanceof ShulkerBoxBlock) {
					phase = ChestPhase.UNPACK_OPEN;
					phaseTicks = 0;
					return StepOutcome.RUNNING;
				}
				if (++phaseTicks > 40) {
					// Could not place shulker box; abort unpack cleanly (shulker stays safely in inventory).
					abortUnpackAndContinue();
					return StepOutcome.RUNNING;
				}
				int shulkerSlot = findShulkerItemSlotInInventory(player, unpackingShulkerItem, collectAllNeededIds());
				if (shulkerSlot < 0) {
					abortUnpackAndContinue();
					return StepOutcome.RUNNING;
				}
				if (!ensureSlotOnHotbar(baritone, player, shulkerSlot)) {
					return StepOutcome.RUNNING;
				}
				if (phaseTicks % 5 == 1) {
					BlockPos support = tempShulkerPos.below();
					Vec3 hit = new Vec3(support.getX() + 0.5, support.getY() + 1.0, support.getZ() + 0.5);
					baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(
							baritone.getPlayerContext().playerHead(), hit, baritone.getPlayerContext().playerRotations()), true);
					baritone.getPlayerContext().playerController().processRightClickBlock(
							player, level, InteractionHand.MAIN_HAND,
							new BlockHitResult(hit, Direction.UP, support, false));
				}
				return StepOutcome.RUNNING;
			}
			case UNPACK_OPEN -> {
				statusDetail = "打开潜影盒取料...";
				if (!(player.containerMenu instanceof InventoryMenu)) {
					phase = ChestPhase.UNPACK_SYNC;
					phaseTicks = 0;
					return StepOutcome.RUNNING;
				}
				if (++phaseTicks > OPEN_TIMEOUT_TICKS) {
					// Failed to open; go straight to breaking & recovering the shulker box!
					beginBreakTempShulker(player);
					return StepOutcome.RUNNING;
				}
				rightClickContainer(baritone, player, level, tempShulkerPos);
				return StepOutcome.RUNNING;
			}
			case UNPACK_SYNC -> {
				if (player.containerMenu instanceof InventoryMenu) {
					beginBreakTempShulker(player);
					return StepOutcome.RUNNING;
				}
				if (++phaseTicks >= SYNC_WAIT_TICKS) {
					phase = ChestPhase.UNPACK_TRANSFER;
					phaseTicks = 0;
				}
				return StepOutcome.RUNNING;
			}
			case UNPACK_TRANSFER -> {
				AbstractContainerMenu menu = player.containerMenu;
				if (menu instanceof InventoryMenu) {
					beginBreakTempShulker(player);
					return StepOutcome.RUNNING;
				}
				int containerSlots = Math.max(0, menu.slots.size() - 36);
				int slot = (!hasRemainingNeeds() || containerSlots <= 0)
						? -1
						: findNextDesiredSlot(player, menu, containerSlots);
				if (slot < 0) {
					player.closeContainer();
					phase = ChestPhase.UNPACK_CLOSE;
					phaseTicks = 0;
					return StepOutcome.RUNNING;
				}
				ItemStack stack = menu.getSlot(slot).getItem();
				String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
				int taken = stack.getCount();
				baritone.getPlayerContext().playerController().windowClick(
						menu.containerId, slot, 0, ContainerInput.QUICK_MOVE, player);
				deductNeedAfterTake(stack, id, taken);
				totalWithdrawnCount += taken;
				statusDetail = "正在从潜影盒取料（已取 " + totalWithdrawnCount + "）";
				return StepOutcome.RUNNING;
			}
			case UNPACK_CLOSE -> {
				if (player.containerMenu instanceof InventoryMenu || ++phaseTicks > CLOSE_WAIT_TICKS) {
					beginBreakTempShulker(player);
				}
				return StepOutcome.RUNNING;
			}
			case UNPACK_BREAK -> {
				statusDetail = "收回临时潜影盒...";
				BlockState st = level.getBlockState(tempShulkerPos);
				if (!(st.getBlock() instanceof ShulkerBoxBlock)) {
					if (breakingShulker && mc.gameMode != null) {
						mc.gameMode.stopDestroyBlock();
					}
					breakingShulker = false;
					phase = ChestPhase.UNPACK_COLLECT;
					phaseTicks = 0;
					return StepOutcome.RUNNING;
				}
				if (++phaseTicks > BREAK_TIMEOUT_TICKS || mc.gameMode == null) {
					if (breakingShulker && mc.gameMode != null) {
						mc.gameMode.stopDestroyBlock();
					}
					breakingShulker = false;
					abortUnpackAndContinue();
					return StepOutcome.RUNNING;
				}
				selectBestPickaxeForShulker(player, st);
				Vec3 center = Vec3.atCenterOf(tempShulkerPos);
				aimPlayerAt(player, center);
				Direction face = closestFace(tempShulkerPos, player.getEyePosition());
				if (!breakingShulker) {
					mc.gameMode.startDestroyBlock(tempShulkerPos, face);
					breakingShulker = true;
				} else {
					mc.gameMode.continueDestroyBlock(tempShulkerPos, face);
				}
				player.swing(InteractionHand.MAIN_HAND);
				return StepOutcome.RUNNING;
			}
			case UNPACK_COLLECT -> {
				statusDetail = "拾取潜影盒...";
				int nowCount = countShulkerBoxesInInventory(player);
				boolean pickedUp = nowCount > shulkerCountBeforeBreak || !hasNearbyShulkerDrop(level, tempShulkerPos);
				if ((pickedUp && phaseTicks >= 6) || ++phaseTicks > COLLECT_TIMEOUT_TICKS) {
					stopMovement(baritone, navigation);
					tempShulkerPos = null;
					if (shulkerReturnChestPos != null && level.isLoaded(shulkerReturnChestPos)
							&& player.getEyePosition().distanceTo(Vec3.atCenterOf(shulkerReturnChestPos)) <= CHEST_REACH + 1.5
							&& findShulkerItemSlotInInventory(player, unpackingShulkerItem, null) >= 0) {
						phase = ChestPhase.RETURN_OPEN;
						phaseTicks = 0;
						return StepOutcome.RUNNING;
					}
					abortUnpackAndContinue();
					return StepOutcome.RUNNING;
				}
				// Walk onto the shulker drop spot to ensure immediate pickup.
				if (navigation != null && !navigation.isWalking() && player.blockPosition().distSqr(tempShulkerPos) > 1.0) {
					navigation.walk(new GoalBlock(tempShulkerPos));
				}
				return StepOutcome.RUNNING;
			}
			case RETURN_OPEN -> {
				statusDetail = "将潜影盒放回资源箱...";
				if (!(player.containerMenu instanceof InventoryMenu)) {
					phase = ChestPhase.RETURN_DEPOSIT;
					phaseTicks = 0;
					return StepOutcome.RUNNING;
				}
				if (++phaseTicks > OPEN_TIMEOUT_TICKS) {
					abortUnpackAndContinue();
					return StepOutcome.RUNNING;
				}
				rightClickContainer(baritone, player, level, shulkerReturnChestPos);
				return StepOutcome.RUNNING;
			}
			case RETURN_DEPOSIT -> {
				AbstractContainerMenu menu = player.containerMenu;
				if (menu instanceof InventoryMenu) {
					abortUnpackAndContinue();
					return StepOutcome.RUNNING;
				}
				if (++phaseTicks < SYNC_WAIT_TICKS) {
					return StepOutcome.RUNNING;
				}
				int containerSlots = Math.max(0, menu.slots.size() - 36);
				for (int i = containerSlots; i < menu.slots.size(); i++) {
					ItemStack s = menu.getSlot(i).getItem();
					if (SchematicResourceCache.isShulkerBoxItem(s)
							&& (unpackingShulkerItem == null || s.is(unpackingShulkerItem))) {
						baritone.getPlayerContext().playerController().windowClick(
								menu.containerId, i, 0, ContainerInput.QUICK_MOVE, player);
						break;
					}
				}
				SchematicResourceCache.recordOpenContainer(level, shulkerReturnChestPos, menu, toolDurabilityThreshold);
				player.closeContainer();
				phase = ChestPhase.RETURN_CLOSE;
				phaseTicks = 0;
				return StepOutcome.RUNNING;
			}
			case RETURN_CLOSE -> {
				if (player.containerMenu instanceof InventoryMenu || ++phaseTicks > CLOSE_WAIT_TICKS) {
					abortUnpackAndContinue();
				}
				return StepOutcome.RUNNING;
			}
			default -> {
				abortUnpackAndContinue();
				return StepOutcome.RUNNING;
			}
		}
	}

	private void beginBreakTempShulker(LocalPlayer player) {
		shulkerCountBeforeBreak = countShulkerBoxesInInventory(player);
		breakingShulker = false;
		phase = ChestPhase.UNPACK_BREAK;
		phaseTicks = 0;
	}

	private void abortUnpackAndContinue() {
		tempShulkerPos = null;
		shulkerReturnChestPos = null;
		unpackingShulkerItem = null;
		activeChest = null;
		phase = ChestPhase.MOVING;
		moveGoalDispatched = false;
		usingFarApproachGoal = false;
		moveGraceTicks = 0;
		phaseTicks = 0;
	}

	private StepOutcome finishRun(IBaritone baritone, NavigationService navigation, Minecraft mc) {
		Mode finishedMode = this.mode;
		int withdrawn = this.totalWithdrawnCount;
		int scanned = this.scannedChestsThisRun;
		PerimeterConfig cfg = this.activeConfig;
		stop(baritone, navigation);

		if (finishedMode == Mode.SCAN_ONLY) {
			SchematicLayerMaterialCalculator.Report report = SchematicLayerMaterialCalculator.computeAllLayers(mc, cfg, true);
			if (report.ok() && report.sufficiency() != null) {
				chat("[Perimeter] 资源点扫描完成（已扫 " + scanned + " 个容器，含箱内潜影盒）："
						+ SchematicLayerMaterialCalculator.formatSufficiencyOneLine(report));
			} else {
				chat("[Perimeter] 资源点扫描完成（已扫 " + scanned + " 个容器），缓存已更新。");
			}
			return StepOutcome.DONE_WITH_PROGRESS;
		}

		if (withdrawn > 0) {
			chat("[Perimeter] 已从资源点/潜影盒补充 " + withdrawn + " 个物品，恢复逐层投影放置。");
			return StepOutcome.DONE_WITH_PROGRESS;
		}
		return StepOutcome.DONE_NO_PROGRESS;
	}

	private int findNextDesiredSlot(LocalPlayer player, AbstractContainerMenu menu, int containerSlots) {
		// Priority 1: Needed schematic blocks
		for (int i = 0; i < containerSlots; i++) {
			ItemStack s = menu.getSlot(i).getItem();
			if (s.isEmpty() || !canFitIntoInventory(player, s)) continue;
			String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
			if (neededSchematicItems.getOrDefault(id, 0) > 0) {
				return i;
			}
		}
		// Priority 2: Water buckets
		if (neededWaterBuckets > 0) {
			for (int i = 0; i < containerSlots; i++) {
				ItemStack s = menu.getSlot(i).getItem();
				if (s.is(Items.WATER_BUCKET) && canFitIntoInventory(player, s)) {
					return i;
				}
			}
		}
		// Priority 3: Configured foods
		if (neededFoodCount > 0 && !foodIds.isEmpty()) {
			for (int i = 0; i < containerSlots; i++) {
				ItemStack s = menu.getSlot(i).getItem();
				if (s.isEmpty() || !canFitIntoInventory(player, s)) continue;
				String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
				if (foodIds.contains(id)) {
					return i;
				}
			}
		}
		// Priority 4: Healthy tool
		if (neededHealthyTool) {
			for (int i = 0; i < containerSlots; i++) {
				ItemStack s = menu.getSlot(i).getItem();
				if (SchematicResourceCache.isHealthyTool(s, toolDurabilityThreshold) && canFitIntoInventory(player, s)) {
					return i;
				}
			}
		}
		// Priority 5: Scaffold blocks
		if (neededScaffoldCount > 0 && !scaffoldIds.isEmpty()) {
			for (int i = 0; i < containerSlots; i++) {
				ItemStack s = menu.getSlot(i).getItem();
				if (s.isEmpty() || !canFitIntoInventory(player, s)) continue;
				String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
				if (scaffoldIds.contains(id)) {
					return i;
				}
			}
		}
		return -1;
	}

	private int findMatchingShulkerSlotInMenu(AbstractContainerMenu menu, int containerSlots) {
		Set<String> ids = collectAllNeededIds();
		for (int i = 0; i < containerSlots; i++) {
			ItemStack s = menu.getSlot(i).getItem();
			if (SchematicResourceCache.shulkerContainsAny(s, ids, neededHealthyTool, toolDurabilityThreshold)) {
				return i;
			}
		}
		return -1;
	}

	private int findMatchingCarriedShulkerSlot(LocalPlayer player, Set<String> neededIds) {
		Inventory inv = player.getInventory();
		for (int i = 0; i < 36; i++) {
			if (SchematicResourceCache.shulkerContainsAny(inv.getItem(i), neededIds, neededHealthyTool, toolDurabilityThreshold)) {
				return i;
			}
		}
		return -1;
	}

	private int findShulkerItemSlotInInventory(LocalPlayer player, Item preferredItem, Set<String> neededIds) {
		Inventory inv = player.getInventory();
		int fallback = -1;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (!SchematicResourceCache.isShulkerBoxItem(s)) continue;
			if (neededIds != null && SchematicResourceCache.shulkerContainsAny(s, neededIds, neededHealthyTool, toolDurabilityThreshold)) {
				return i;
			}
			if (preferredItem != null && s.is(preferredItem) && fallback < 0) {
				fallback = i;
			}
		}
		return fallback;
	}

	private static BlockPos findSafeShulkerPlacePos(Level level, LocalPlayer player) {
		BlockPos feet = player.blockPosition();
		BlockPos head = feet.above();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (int dy = -1; dy <= 1; dy++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dx = -2; dx <= 2; dx++) {
					cursor.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
					if (cursor.equals(feet) || cursor.equals(head)) continue;
					if (!level.isLoaded(cursor) || !level.isLoaded(cursor.above()) || !level.isLoaded(cursor.below())) continue;
					if (!level.getBlockState(cursor).isAir()) continue;
					// Shulker lid requires 1 air/non-solid block above it to open!
					if (!level.getBlockState(cursor.above()).getCollisionShape(level, cursor.above()).isEmpty()) continue;
					BlockState below = level.getBlockState(cursor.below());
					if (!below.isSolid() || below.is(Blocks.CACTUS) || below.is(Blocks.MAGMA_BLOCK)) continue;
					if (hasHazardNeighbor(level, cursor)) continue;
					double d = player.getEyePosition().distanceToSqr(Vec3.atCenterOf(cursor));
					if (d > CHEST_REACH * CHEST_REACH) continue;
					if (d < bestDist) {
						bestDist = d;
						best = cursor.immutable();
					}
				}
			}
		}
		return best;
	}

	private static boolean hasHazardNeighbor(Level level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			BlockState s = level.getBlockState(pos.relative(d));
			if (s.is(Blocks.LAVA) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.CACTUS)) {
				return true;
			}
		}
		return false;
	}

	private static boolean ensureSlotOnHotbar(IBaritone baritone, LocalPlayer player, int invSlot) {
		Inventory inv = player.getInventory();
		if (invSlot < 9) {
			if (inv.getSelectedSlot() != invSlot) {
				inv.setSelectedSlot(invSlot);
				baritone.getPlayerContext().playerController().syncHeldItem();
			}
			return true;
		}
		if (!(player.containerMenu instanceof InventoryMenu)) return false;
		int hotbarSlot = inv.getSelectedSlot();
		for (int i = 0; i < 9; i++) {
			if (inv.getItem(i).isEmpty()) {
				hotbarSlot = i;
				break;
			}
		}
		baritone.getPlayerContext().playerController().windowClick(
				player.inventoryMenu.containerId, invSlot, hotbarSlot, ContainerInput.SWAP, player);
		inv.setSelectedSlot(hotbarSlot);
		baritone.getPlayerContext().playerController().syncHeldItem();
		return true;
	}

	private void rightClickContainer(IBaritone baritone, LocalPlayer player, Level level, BlockPos pos) {
		Direction face = closestFace(pos, player.getEyePosition());
		Vec3 hitLocation = Vec3.atCenterOf(pos).add(
				face.getStepX() * 0.48,
				face.getStepY() * 0.48,
				face.getStepZ() * 0.48
		);
		baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(
				baritone.getPlayerContext().playerHead(), hitLocation, baritone.getPlayerContext().playerRotations()), true);
		if (phaseTicks % 6 == 1) {
			baritone.getPlayerContext().playerController().processRightClickBlock(
					player, level, InteractionHand.MAIN_HAND,
					new BlockHitResult(hitLocation, face, pos, false));
		}
	}

	private static void selectBestPickaxeForShulker(LocalPlayer player, BlockState state) {
		Inventory inv = player.getInventory();
		int bestSlot = inv.getSelectedSlot();
		float bestSpeed = inv.getItem(bestSlot).isEmpty() ? 1.0F : inv.getItem(bestSlot).getDestroySpeed(state);
		for (int i = 0; i < 9; i++) {
			ItemStack s = inv.getItem(i);
			float spd = s.isEmpty() ? 1.0F : s.getDestroySpeed(state);
			if (spd > bestSpeed + 0.01F) {
				bestSpeed = spd;
				bestSlot = i;
			}
		}
		if (bestSlot != inv.getSelectedSlot()) {
			inv.setSelectedSlot(bestSlot);
		}
	}

	private static int countShulkerBoxesInInventory(LocalPlayer player) {
		int c = 0;
		Inventory inv = player.getInventory();
		for (int i = 0; i < 36; i++) {
			if (SchematicResourceCache.isShulkerBoxItem(inv.getItem(i))) c++;
		}
		return c;
	}

	private static boolean hasNearbyShulkerDrop(Level level, BlockPos pos) {
		AABB box = new AABB(pos).inflate(2.5);
		for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box)) {
			if (SchematicResourceCache.isShulkerBoxItem(item.getItem())) {
				return true;
			}
		}
		return false;
	}

	private void deductNeedAfterTake(ItemStack stack, String id, int count) {
		Integer rem = neededSchematicItems.get(id);
		if (rem != null && rem > 0) {
			int next = rem - count;
			if (next <= 0) neededSchematicItems.remove(id);
			else neededSchematicItems.put(id, next);
			return;
		}
		if (stack.is(Items.WATER_BUCKET) && neededWaterBuckets > 0) {
			neededWaterBuckets = Math.max(0, neededWaterBuckets - count);
			return;
		}
		if (foodIds.contains(id) && neededFoodCount > 0) {
			neededFoodCount = Math.max(0, neededFoodCount - count);
			return;
		}
		if (neededHealthyTool && SchematicResourceCache.isHealthyTool(stack, toolDurabilityThreshold)) {
			neededHealthyTool = false;
			return;
		}
		if (scaffoldIds.contains(id) && neededScaffoldCount > 0) {
			neededScaffoldCount = Math.max(0, neededScaffoldCount - count);
		}
	}

	private boolean hasRemainingNeeds() {
		return !neededSchematicItems.isEmpty()
				|| neededWaterBuckets > 0
				|| neededFoodCount > 0
				|| neededScaffoldCount > 0
				|| neededHealthyTool;
	}

	private Set<String> collectAllNeededIds() {
		Set<String> ids = new HashSet<>(neededSchematicItems.keySet());
		if (neededWaterBuckets > 0) ids.add("minecraft:water_bucket");
		if (neededFoodCount > 0) ids.addAll(foodIds);
		if (neededScaffoldCount > 0) ids.addAll(scaffoldIds);
		return ids;
	}

	private ChestTarget popNextChest(BlockPos origin) {
		if (mode == Mode.SCAN_ONLY) {
			int bestIdx = 0;
			double bestDist = pendingChests.getFirst().pos().distSqr(origin);
			for (int i = 1; i < pendingChests.size(); i++) {
				double d = pendingChests.get(i).pos().distSqr(origin);
				if (d < bestDist) {
					bestDist = d;
					bestIdx = i;
				}
			}
			return pendingChests.remove(bestIdx);
		}
		return pendingChests.removeFirst();
	}

	private static List<ChestTarget> deduplicateChests(Level level, Map<String, PositionConfig> points) {
		List<ChestTarget> list = new ArrayList<>();
		Set<String> seenKeys = new HashSet<>();
		for (Map.Entry<String, PositionConfig> e : points.entrySet()) {
			PositionConfig p = e.getValue();
			if (p == null) continue;
			BlockPos raw = new BlockPos(p.x, p.y, p.z);
			BlockPos canon = SchematicResourceCache.canonicalChestPos(level, raw);
			String key = SchematicResourceCache.cacheKey(level, canon);
			if (seenKeys.add(key)) {
				list.add(new ChestTarget(e.getKey(), canon));
			}
		}
		return list;
	}

	private static void sortNearest(List<ChestTarget> list, BlockPos origin) {
		list.sort(Comparator.comparingDouble(c -> c.pos().distSqr(origin)));
	}

	private static boolean canFitIntoInventory(LocalPlayer player, ItemStack stack) {
		Inventory inv = player.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack slot = inv.getItem(i);
			if (slot.isEmpty()) return true;
			if (ItemStack.isSameItemSameComponents(slot, stack) && slot.getCount() < slot.getMaxStackSize()) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasAnyInventorySpace(LocalPlayer player) {
		Inventory inv = player.getInventory();
		for (int i = 0; i < 36; i++) {
			if (inv.getItem(i).isEmpty()) return true;
		}
		return false;
	}

	private void stopMovement(IBaritone baritone, NavigationService navigation) {
		if (baritone != null) flightNav.stop(baritone);
		if (navigation != null) navigation.stopWalking();
	}

	private static void aimPlayerAt(LocalPlayer player, Vec3 target) {
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
