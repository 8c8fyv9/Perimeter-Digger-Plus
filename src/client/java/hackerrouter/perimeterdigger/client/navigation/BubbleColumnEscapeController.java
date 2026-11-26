package hackerrouter.perimeterdigger.client.navigation;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.input.Input;
import hackerrouter.perimeterdigger.client.build.SchematicSignPlacer;
import hackerrouter.perimeterdigger.client.flight.CreativeFlightNavigator;
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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HangingSignItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SignItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.BaseCoralPlantTypeBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ConduitBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Detects when the player is trapped inside a downward bubble column ({@code BUBBLE_COLUMN[drag=true]}
 * above {@code MAGMA_BLOCK}) and executes a configurable escape strategy:
 * <ul>
 *   <li>{@code auto}: {@code /fly} lift -> zero-collision / bottom-slab magma capping -> empty-bucket scoop + pillar -> horizontal sneak walk</li>
 *   <li>{@code scaffold_first}: prioritizes capping the magma floor with a zero-collision block or adjacent bottom slab/trapdoor to convert the column to normal water and swim/step up</li>
 *   <li>{@code bucket_first}: prioritizes scooping the downward bubble column with a safe empty bucket ({@code Items.BUCKET}) to break downward drag + jumping/pillaring out</li>
 *   <li>{@code walk_or_fly}: never places blocks or scoops water; holds sneak on magma floor and exits via {@code /fly} or horizontal floor pathing</li>
 * </ul>
 */
public final class BubbleColumnEscapeController {
	public enum Mode {
		AUTO("auto"),
		SCAFFOLD_FIRST("scaffold_first"),
		BUCKET_FIRST("bucket_first"),
		WALK_OR_FLY("walk_or_fly");

		private final String id;

		Mode(String id) {
			this.id = id;
		}

		public String id() {
			return id;
		}

		public static Mode parse(String raw) {
			if (raw == null || raw.isBlank()) return AUTO;
			String norm = raw.trim().toLowerCase(Locale.ROOT);
			for (Mode m : values()) {
				if (m.id.equals(norm)) return m;
			}
			return AUTO;
		}
	}

	public enum CapBlockKind {
		NONE,
		/** Can be placed directly at {@code feet} without jumping and survives underwater, stopping {@code BubbleColumnBlock}. */
		IN_PLACE_ZERO_COLLISION,
		/** Height {@code <= 0.5} when placed on top of adjacent magma floor; caps magma and can be stepped onto without jumping ({@code maxUpStep = 0.6}). */
		ADJACENT_STEP_UP,
		/** Full solid block that can be placed under feet after scooping the bubble column and jumping. */
		SOLID_PILLAR
	}

	private enum Phase {
		IDLE,
		FLY_UP,
		STEP_AND_SWIM_UP,
		BUCKET_SCOOP_AND_JUMP,
		WALK_OUT
	}

	private static final int ESCAPE_TIMEOUT_TICKS = 90;
	private static final int STUCK_TRIGGER_TICKS = 16;

	private final CreativeFlightNavigator flightNav = new CreativeFlightNavigator();
	private Phase phase = Phase.IDLE;
	private int phaseTicks;
	private int totalEscapeTicks;
	private int trappedTicks;
	private BlockPos lastTrappedFeet;
	private BlockPos stepTargetPos;
	private BlockPos exitPlatformPos;
	private int targetTopY;
	private boolean sneakForced;
	private boolean jumpForced;
	private boolean forwardForced;
	private String statusText = "";

	public boolean isActive() {
		return phase != Phase.IDLE;
	}

	public String statusText() {
		return statusText;
	}

	public void reset(IBaritone baritone) {
		phase = Phase.IDLE;
		phaseTicks = 0;
		totalEscapeTicks = 0;
		stepTargetPos = null;
		exitPlatformPos = null;
		statusText = "";
		if (baritone != null) {
			flightNav.stop(baritone);
			clearForcedInputs(baritone);
		} else {
			sneakForced = false;
			jumpForced = false;
			forwardForced = false;
		}
	}

	private void clearForcedInputs(IBaritone baritone) {
		if (baritone == null) return;
		var ioh = baritone.getInputOverrideHandler();
		if (sneakForced) {
			ioh.setInputForceState(Input.SNEAK, false);
			sneakForced = false;
		}
		if (jumpForced) {
			ioh.setInputForceState(Input.JUMP, false);
			jumpForced = false;
		}
		if (forwardForced) {
			ioh.setInputForceState(Input.MOVE_FORWARD, false);
			forwardForced = false;
		}
	}

	public static boolean isDownwardBubbleColumn(BlockState state) {
		return state != null
				&& state.getBlock() instanceof BubbleColumnBlock
				&& state.hasProperty(BubbleColumnBlock.DRAG_DOWN)
				&& state.getValue(BubbleColumnBlock.DRAG_DOWN);
	}

	public static boolean isPlayerInDownwardBubble(Level level, LocalPlayer player) {
		if (level == null || player == null) return false;
		BlockPos feet = player.blockPosition();
		if (!level.isLoaded(feet)) return false;
		return isDownwardBubbleColumn(level.getBlockState(feet))
				|| isDownwardBubbleColumn(level.getBlockState(feet.above()));
	}

	public static boolean isStandingOnMagma(Level level, LocalPlayer player) {
		if (level == null || player == null) return false;
		BlockPos below = player.blockPosition().below();
		return level.isLoaded(below) && level.getBlockState(below).getBlock() instanceof MagmaBlock;
	}

	/**
	 * Pure classification helper for inventory block items usable to break/cap a downward bubble column.
	 */
	public static CapBlockKind classifyEscapeBlock(Block block, boolean hasHorizontalWallSupport) {
		if (block == null || block == Blocks.AIR || block instanceof MagmaBlock || block == Blocks.SOUL_SAND) {
			return CapBlockKind.NONE;
		}
		if (block instanceof ScaffoldingBlock
				|| block instanceof BaseRailBlock
				|| block instanceof SignBlock
				|| block instanceof SeaPickleBlock
				|| block instanceof ConduitBlock
				|| block instanceof BaseCoralPlantTypeBlock
				|| block instanceof AmethystClusterBlock) {
			return CapBlockKind.IN_PLACE_ZERO_COLLISION;
		}
		if (block instanceof LadderBlock) {
			return hasHorizontalWallSupport ? CapBlockKind.IN_PLACE_ZERO_COLLISION : CapBlockKind.NONE;
		}
		if (block instanceof SlabBlock
				|| block instanceof TrapDoorBlock
				|| block instanceof CampfireBlock
				|| block instanceof DaylightDetectorBlock
				|| block instanceof EnchantingTableBlock) {
			return CapBlockKind.ADJACENT_STEP_UP;
		}
		if (block.defaultBlockState().isSolid()) {
			return CapBlockKind.SOLID_PILLAR;
		}
		return CapBlockKind.NONE;
	}

	/**
	 * Pure decision helper returning the ordered list of escape techniques to try for the given
	 * {@link Mode} and available capabilities.
	 */
	public static List<String> planTechniqueOrder(
			Mode mode,
			boolean canFly,
			boolean hasInPlaceOrStepCap,
			boolean hasSafeEmptyBucket
	) {
		List<String> order = new ArrayList<>(4);
		if (canFly) {
			order.add("fly");
		}
		Mode effective = mode == null ? Mode.AUTO : mode;
		switch (effective) {
			case WALK_OR_FLY -> order.add("walk_out");
			case BUCKET_FIRST -> {
				if (hasSafeEmptyBucket) order.add("bucket_scoop");
				if (hasInPlaceOrStepCap) order.add("cap_magma");
				order.add("walk_out");
			}
			case SCAFFOLD_FIRST, AUTO -> {
				if (hasInPlaceOrStepCap) order.add("cap_magma");
				if (hasSafeEmptyBucket) order.add("bucket_scoop");
				order.add("walk_out");
			}
		}
		return order;
	}

	/**
	 * Maintains passive {@code Input.SNEAK} protection whenever the player's feet rest on a
	 * {@code MAGMA_BLOCK} inside a downward bubble column while not actively jumping/swimming up.
	 * Releases {@code Input.SNEAK} immediately as soon as the player leaves the magma block.
	 */
	public void maintainPassiveMagmaProtection(IBaritone baritone, Level level, LocalPlayer player) {
		if (baritone == null || level == null || player == null) return;
		if (isActive()) return;
		boolean needSneak = isPlayerInDownwardBubble(level, player)
				&& isStandingOnMagma(level, player)
				&& !player.getAbilities().flying;
		if (needSneak) {
			baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
			sneakForced = true;
		} else if (sneakForced) {
			baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
			sneakForced = false;
		}
	}

	/**
	 * Observes player progress while inside a downward bubble column and automatically triggers
	 * escape if trapped for {@code >= STUCK_TRIGGER_TICKS} while needing to ascend or stalled.
	 */
	public boolean observeAndMaybeTrigger(
			IBaritone baritone,
			NavigationService navigation,
			Minecraft mc,
			LocalPlayer player,
			Level level,
			int desiredY,
			String modeRaw
	) {
		if (isActive()) return true;
		if (!isPlayerInDownwardBubble(level, player)) {
			trappedTicks = 0;
			lastTrappedFeet = null;
			maintainPassiveMagmaProtection(baritone, level, player);
			return false;
		}
		maintainPassiveMagmaProtection(baritone, level, player);
		BlockPos feet = player.blockPosition();
		boolean needsAscent = desiredY > feet.getY();
		boolean walking = navigation != null && navigation.isWalking();
		if (lastTrappedFeet != null && lastTrappedFeet.equals(feet)) {
			trappedTicks++;
		} else {
			lastTrappedFeet = feet;
			trappedTicks = needsAscent && !walking ? (STUCK_TRIGGER_TICKS / 2) : 1;
		}
		if ((needsAscent && !walking && trappedTicks >= 6) || trappedTicks >= STUCK_TRIGGER_TICKS) {
			return tryStartEscape(baritone, navigation, mc, player, level, modeRaw);
		}
		return false;
	}

	/**
	 * Starts an active downward bubble column escape maneuver according to {@code modeRaw}.
	 */
	public boolean tryStartEscape(
			IBaritone baritone,
			NavigationService navigation,
			Minecraft mc,
			LocalPlayer player,
			Level level,
			String modeRaw
	) {
		if (baritone == null || player == null || level == null || !isPlayerInDownwardBubble(level, player)) {
			return false;
		}
		reset(baritone);
		if (navigation != null) {
			navigation.stopWalking();
		}
		Mode mode = Mode.parse(modeRaw);
		BlockPos feet = player.blockPosition();
		this.targetTopY = findBubbleColumnTopY(level, feet);
		this.exitPlatformPos = findNearestExitCell(level, feet, targetTopY);

		boolean canFly = navigation != null && navigation.canUseCreativeFlight();
		boolean hasHorizontalWall = findHorizontalSolidNeighborFace(level, feet) != null;
		int inPlaceSlot = findCapBlockSlot(player.getInventory(), CapBlockKind.IN_PLACE_ZERO_COLLISION, hasHorizontalWall);
		BlockPos existingStepNeighbor = findExistingAdjacentStepUpCell(level, feet);
		int stepUpSlot = findCapBlockSlot(player.getInventory(), CapBlockKind.ADJACENT_STEP_UP, false);
		BlockPos placeableStepNeighbor = stepUpSlot >= 0 ? findPlaceableAdjacentFloorCell(level, feet) : null;
		boolean hasCapOption = inPlaceSlot >= 0 || existingStepNeighbor != null || (stepUpSlot >= 0 && placeableStepNeighbor != null);
		boolean hasBucketOption = hasSafeEmptyBucket(player.getInventory());

		List<String> order = planTechniqueOrder(mode, canFly, hasCapOption, hasBucketOption);
		for (String tech : order) {
			switch (tech) {
				case "fly" -> {
					phase = Phase.FLY_UP;
					phaseTicks = 0;
					totalEscapeTicks = 0;
					statusText = "向下气泡柱脱困：启用 /fly 上浮脱离...";
					return true;
				}
				case "cap_magma" -> {
					if (startCapMagmaEscape(baritone, mc, player, level, feet, inPlaceSlot, existingStepNeighbor, stepUpSlot, placeableStepNeighbor)) {
						return true;
					}
				}
				case "bucket_scoop" -> {
					if (startBucketScoopEscape(baritone, mc, player, level, feet)) {
						return true;
					}
				}
				case "walk_out" -> {
					if (startWalkOutEscape(baritone, navigation, level, feet)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	public boolean tick(
			IBaritone baritone,
			NavigationService navigation,
			Minecraft mc,
			LocalPlayer player,
			Level level,
			String modeRaw
	) {
		if (!isActive()) return false;
		if (baritone == null || player == null || level == null || mc == null || mc.gameMode == null) {
			reset(baritone);
			return false;
		}
		SchematicSignPlacer.closeSignEditScreenIfOpen(mc);
		BlockPos feet = player.blockPosition();
		boolean stillInBubble = isPlayerInDownwardBubble(level, player);
		if (!stillInBubble && (player.getY() >= targetTopY + 0.45 || player.onGround())) {
			trappedTicks = 0;
			reset(baritone);
			return false;
		}
		if (++totalEscapeTicks > ESCAPE_TIMEOUT_TICKS) {
			reset(baritone);
			return false;
		}
		phaseTicks++;

		switch (phase) {
			case FLY_UP -> {
				clearForcedInputs(baritone);
				if (player.getAbilities().mayfly && !player.getAbilities().flying) {
					player.getAbilities().flying = true;
					player.onUpdateAbilities();
				}
				BlockPos hoverCell = exitPlatformPos != null ? exitPlatformPos : feet.atY(targetTopY + 1);
				Vec3 target = new Vec3(hoverCell.getX() + 0.5, Math.max(targetTopY + 1.3, hoverCell.getY() + 0.3), hoverCell.getZ() + 0.5);
				if (!flightNav.isActive() || flightNav.target() == null || flightNav.target().distanceToSqr(target) > 1.0) {
					flightNav.flyTo(target, 0.75, 0.65, false);
				}
				flightNav.tick(baritone);
				return true;
			}
			case STEP_AND_SWIM_UP -> {
				var ioh = baritone.getInputOverrideHandler();
				if (sneakForced) {
					ioh.setInputForceState(Input.SNEAK, false);
					sneakForced = false;
				}
				ioh.setInputForceState(Input.JUMP, true);
				jumpForced = true;

				BlockPos steerPos = stepTargetPos != null ? stepTargetPos : feet;
				if (player.getY() >= targetTopY - 0.15 && exitPlatformPos != null) {
					steerPos = exitPlatformPos;
				}
				double dx = (steerPos.getX() + 0.5) - player.getX();
				double dz = (steerPos.getZ() + 0.5) - player.getZ();
				if (dx * dx + dz * dz > 0.04) {
					float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
					player.setYRot(yaw);
					player.yHeadRot = yaw;
					ioh.setInputForceState(Input.MOVE_FORWARD, true);
					forwardForced = true;
				} else if (forwardForced) {
					ioh.setInputForceState(Input.MOVE_FORWARD, false);
					forwardForced = false;
				}
				if (phaseTicks > 45 && stillInBubble) {
					// If swimming up after capping stalled, fall back to bucket scoop or horizontal walk.
					Mode mode = Mode.parse(modeRaw);
					if (mode != Mode.WALK_OR_FLY && startBucketScoopEscape(baritone, mc, player, level, feet)) {
						return true;
					}
					if (startWalkOutEscape(baritone, navigation, level, feet)) {
						return true;
					}
					reset(baritone);
					return false;
				}
				return true;
			}
			case BUCKET_SCOOP_AND_JUMP -> {
				var ioh = baritone.getInputOverrideHandler();
				if (sneakForced) {
					ioh.setInputForceState(Input.SNEAK, false);
					sneakForced = false;
				}
				// If feet or feet.above() is still a downward bubble column and we have another safe empty bucket, scoop it immediately!
				if ((isDownwardBubbleColumn(level.getBlockState(feet.above())) || isDownwardBubbleColumn(level.getBlockState(feet)))
						&& phaseTicks <= 6
						&& ensureSafeEmptyBucketOnHotbar(baritone, player)) {
					scoopFluidAtFeet(mc, player, feet);
				}
				ioh.setInputForceState(Input.JUMP, true);
				jumpForced = true;

				// Once lifted >= 0.85 blocks above the magma floor (or when feet is no longer obstructed), place a pillar/cap block under feet!
				BlockPos floorTarget = lastTrappedFeet != null ? lastTrappedFeet : feet;
				if (player.getY() >= floorTarget.getY() + 0.85) {
					tryPlaceBlockOnFloor(baritone, mc, player, level, floorTarget);
				}
				if (exitPlatformPos != null) {
					double dx = (exitPlatformPos.getX() + 0.5) - player.getX();
					double dz = (exitPlatformPos.getZ() + 0.5) - player.getZ();
					if (dx * dx + dz * dz > 0.06) {
						float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
						player.setYRot(yaw);
						player.yHeadRot = yaw;
						ioh.setInputForceState(Input.MOVE_FORWARD, true);
						forwardForced = true;
					}
				}
				if (phaseTicks > 35 && stillInBubble) {
					if (startWalkOutEscape(baritone, navigation, level, feet)) {
						return true;
					}
					reset(baritone);
					return false;
				}
				return true;
			}
			case WALK_OUT -> {
				if (isStandingOnMagma(level, player)) {
					baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
					sneakForced = true;
				} else if (sneakForced) {
					baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
					sneakForced = false;
				}
				if (navigation == null || (!navigation.isWalking() && phaseTicks > 6)) {
					reset(baritone);
					return false;
				}
				return true;
			}
			default -> {
				reset(baritone);
				return false;
			}
		}
	}

	private boolean startCapMagmaEscape(
			IBaritone baritone,
			Minecraft mc,
			LocalPlayer player,
			Level level,
			BlockPos feet,
			int inPlaceSlot,
			BlockPos existingStepNeighbor,
			int stepUpSlot,
			BlockPos placeableStepNeighbor
	) {
		// 1) An adjacent neighbor already has a <=0.5 height step-up block capping its magma floor!
		if (existingStepNeighbor != null) {
			this.stepTargetPos = existingStepNeighbor;
			this.phase = Phase.STEP_AND_SWIM_UP;
			this.phaseTicks = 0;
			this.totalEscapeTicks = 0;
			this.statusText = "向下气泡柱脱困：踏上邻格半砖并上浮...";
			return true;
		}
		// 2) Place an in-place waterloggable zero-collision/climbable block (scaffolding, rail, sign, ladder) directly at feet!
		if (inPlaceSlot >= 0 && ensureInventorySlotOnHotbar(baritone, player, inPlaceSlot)) {
			ItemStack held = player.getMainHandItem();
			boolean placed = false;
			if (held.getItem() instanceof BlockItem bi && bi.getBlock() instanceof LadderBlock) {
				Direction wallDir = findHorizontalSolidNeighborFace(level, feet);
				if (wallDir != null) {
					BlockPos wallPos = feet.relative(wallDir);
					Direction hitFace = wallDir.getOpposite();
					Vec3 hitVec = Vec3.atCenterOf(wallPos).add(
							hitFace.getStepX() * 0.5, hitFace.getStepY() * 0.5, hitFace.getStepZ() * 0.5);
					placed = interactBlockFace(mc, player, wallPos, hitFace, hitVec);
				}
			} else {
				BlockPos below = feet.below();
				Vec3 hitVec = new Vec3(below.getX() + 0.5, below.getY() + 1.0, below.getZ() + 0.5);
				placed = interactBlockFace(mc, player, below, Direction.UP, hitVec);
			}
			SchematicSignPlacer.closeSignEditScreenIfOpen(mc);
			if (placed) {
				this.stepTargetPos = feet;
				this.phase = Phase.STEP_AND_SWIM_UP;
				this.phaseTicks = 0;
				this.totalEscapeTicks = 0;
				this.statusText = "向下气泡柱脱困：脚下放置方块阻断气泡柱并上浮...";
				return true;
			}
		}
		// 3) Place a bottom half-slab / trapdoor / campfire (<=0.5 height) on top of an adjacent magma block and step onto it without jumping!
		if (stepUpSlot >= 0 && placeableStepNeighbor != null && ensureInventorySlotOnHotbar(baritone, player, stepUpSlot)) {
			BlockPos belowNeighbor = placeableStepNeighbor.below();
			Vec3 hitVec = new Vec3(belowNeighbor.getX() + 0.5, belowNeighbor.getY() + 1.0, belowNeighbor.getZ() + 0.5);
			if (interactBlockFace(mc, player, belowNeighbor, Direction.UP, hitVec)) {
				this.stepTargetPos = placeableStepNeighbor;
				this.phase = Phase.STEP_AND_SWIM_UP;
				this.phaseTicks = 0;
				this.totalEscapeTicks = 0;
				this.statusText = "向下气泡柱脱困：邻格放置下半砖封住岩浆块并踏上上浮...";
				return true;
			}
		}
		return false;
	}

	private boolean startBucketScoopEscape(
			IBaritone baritone,
			Minecraft mc,
			LocalPlayer player,
			Level level,
			BlockPos feet
	) {
		if (!ensureSafeEmptyBucketOnHotbar(baritone, player)) {
			return false;
		}
		scoopFluidAtFeet(mc, player, feet);
		this.lastTrappedFeet = feet;
		this.phase = Phase.BUCKET_SCOOP_AND_JUMP;
		this.phaseTicks = 0;
		this.totalEscapeTicks = 0;
		this.statusText = "向下气泡柱脱困：空水桶舀断气泡柱并起跳垫高...";
		return true;
	}

	private boolean startWalkOutEscape(
			IBaritone baritone,
			NavigationService navigation,
			Level level,
			BlockPos feet
	) {
		if (navigation == null) return false;
		List<BlockPos> exits = findHorizontalFloorExits(level, feet, 8);
		if (exits.isEmpty()) return false;
		Goal[] goals = new Goal[Math.min(8, exits.size())];
		for (int i = 0; i < goals.length; i++) {
			goals[i] = new GoalBlock(exits.get(i));
		}
		navigation.walk(goals.length == 1 ? goals[0] : new GoalComposite(goals));
		this.phase = Phase.WALK_OUT;
		this.phaseTicks = 0;
		this.totalEscapeTicks = 0;
		this.statusText = "向下气泡柱脱困：保持潜行防烫伤并横向走出气泡柱...";
		return true;
	}

	private static void scoopFluidAtFeet(Minecraft mc, LocalPlayer player, BlockPos feet) {
		Vec3 target = new Vec3(feet.getX() + 0.5, feet.getY() + 0.35, feet.getZ() + 0.5);
		aimAndSendRotation(mc, player, target);
		mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
		player.swing(InteractionHand.MAIN_HAND);
	}

	private static void tryPlaceBlockOnFloor(
			IBaritone baritone,
			Minecraft mc,
			LocalPlayer player,
			Level level,
			BlockPos floorCell
	) {
		if (!level.isLoaded(floorCell)) return;
		BlockState cur = level.getBlockState(floorCell);
		if (!cur.isAir() && !(cur.getBlock() instanceof LiquidBlock) && !(cur.getBlock() instanceof BubbleColumnBlock)) {
			return;
		}
		boolean hasWall = findHorizontalSolidNeighborFace(level, floorCell) != null;
		int slot = findCapBlockSlot(player.getInventory(), CapBlockKind.IN_PLACE_ZERO_COLLISION, hasWall);
		if (slot < 0) {
			slot = findCapBlockSlot(player.getInventory(), CapBlockKind.ADJACENT_STEP_UP, false);
		}
		if (slot < 0) {
			slot = findCapBlockSlot(player.getInventory(), CapBlockKind.SOLID_PILLAR, false);
		}
		if (slot < 0 || !ensureInventorySlotOnHotbar(baritone, player, slot)) {
			return;
		}
		BlockPos below = floorCell.below();
		Vec3 hitVec = new Vec3(below.getX() + 0.5, below.getY() + 1.0, below.getZ() + 0.5);
		interactBlockFace(mc, player, below, Direction.UP, hitVec);
		SchematicSignPlacer.closeSignEditScreenIfOpen(mc);
	}

	private static boolean interactBlockFace(
			Minecraft mc,
			LocalPlayer player,
			BlockPos supportPos,
			Direction face,
			Vec3 hitVec
	) {
		if (mc == null || mc.gameMode == null || player == null) return false;
		aimAndSendRotation(mc, player, hitVec);
		BlockHitResult hit = new BlockHitResult(hitVec, face, supportPos, false);
		var result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
		player.swing(InteractionHand.MAIN_HAND);
		return result.consumesAction();
	}

	private static void aimAndSendRotation(Minecraft mc, LocalPlayer player, Vec3 target) {
		Vec3 eye = player.getEyePosition();
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		double horiz = Math.hypot(dx, dz);
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
		player.setYRot(yaw);
		player.setXRot(pitch);
		player.yHeadRot = yaw;
		if (mc.getConnection() != null) {
			mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
					yaw, pitch, player.onGround(), player.horizontalCollision));
		}
	}

	private static int findBubbleColumnTopY(Level level, BlockPos feet) {
		int y = feet.getY();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(feet.getX(), y, feet.getZ());
		for (int dy = 0; dy <= 16; dy++) {
			cursor.setY(feet.getY() + dy);
			if (!level.isLoaded(cursor)) break;
			BlockState s = level.getBlockState(cursor);
			if (isDownwardBubbleColumn(s) || s.getBlock() instanceof LiquidBlock) {
				y = cursor.getY();
			} else {
				break;
			}
		}
		return y;
	}

	private static BlockPos findNearestExitCell(Level level, BlockPos feet, int topY) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dy = 0; dy <= 1; dy++) {
			int y = topY + dy;
			for (int dz = -3; dz <= 3; dz++) {
				for (int dx = -3; dx <= 3; dx++) {
					if (dx == 0 && dz == 0) continue;
					cursor.set(feet.getX() + dx, y, feet.getZ() + dz);
					if (!level.isLoaded(cursor) || !level.isLoaded(cursor.above())) continue;
					BlockState s = level.getBlockState(cursor);
					BlockState above = level.getBlockState(cursor.above());
					BlockState below = level.getBlockState(cursor.below());
					if (!s.getCollisionShape(level, cursor).isEmpty()) continue;
					if (!above.getCollisionShape(level, cursor.above()).isEmpty()) continue;
					if (isDownwardBubbleColumn(s) || isDownwardBubbleColumn(below)) continue;
					if (!below.getCollisionShape(level, cursor.below()).isEmpty()) {
						double d = dx * dx + dz * dz + dy * 2.0;
						if (d < bestDist) {
							bestDist = d;
							best = cursor.immutable();
						}
					}
				}
			}
		}
		return best;
	}

	private static List<BlockPos> findHorizontalFloorExits(Level level, BlockPos feet, int radius) {
		List<BlockPos> exits = new ArrayList<>();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dy = 0; dy <= 1; dy++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dx = -radius; dx <= radius; dx++) {
					if (dx == 0 && dz == 0) continue;
					cursor.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
					if (!level.isLoaded(cursor) || !level.isLoaded(cursor.above())) continue;
					BlockState s = level.getBlockState(cursor);
					BlockState above = level.getBlockState(cursor.above());
					BlockState below = level.getBlockState(cursor.below());
					if (!s.getCollisionShape(level, cursor).isEmpty()) continue;
					if (!above.getCollisionShape(level, cursor.above()).isEmpty()) continue;
					if (isDownwardBubbleColumn(s) || isDownwardBubbleColumn(above) || isDownwardBubbleColumn(below)) continue;
					if (!below.getCollisionShape(level, cursor.below()).isEmpty()) {
						exits.add(cursor.immutable());
					}
				}
			}
		}
		exits.sort((a, b) -> Double.compare(a.distSqr(feet), b.distSqr(feet)));
		return exits;
	}

	private static Direction findHorizontalSolidNeighborFace(Level level, BlockPos feet) {
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos nb = feet.relative(dir);
			if (!level.isLoaded(nb)) continue;
			BlockState s = level.getBlockState(nb);
			if (s.isFaceSturdy(level, nb, dir.getOpposite())) {
				return dir;
			}
		}
		return null;
	}

	private static BlockPos findExistingAdjacentStepUpCell(Level level, BlockPos feet) {
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos nb = feet.relative(dir);
			if (!level.isLoaded(nb) || !level.isLoaded(nb.above(2))) continue;
			BlockState s = level.getBlockState(nb);
			if (isDownwardBubbleColumn(s)) continue;
			var shape = s.getCollisionShape(level, nb);
			if (shape.isEmpty()) continue;
			double maxY = shape.max(Direction.Axis.Y);
			if (maxY > 0.05 && maxY <= 0.6
					&& level.getBlockState(nb.above()).getCollisionShape(level, nb.above()).isEmpty()
					&& level.getBlockState(nb.above(2)).getCollisionShape(level, nb.above(2)).isEmpty()) {
				return nb;
			}
		}
		return null;
	}

	private static BlockPos findPlaceableAdjacentFloorCell(Level level, BlockPos feet) {
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos nb = feet.relative(dir);
			if (!level.isLoaded(nb) || !level.isLoaded(nb.above(2))) continue;
			BlockState s = level.getBlockState(nb);
			if (!s.isAir() && !(s.getBlock() instanceof LiquidBlock) && !(s.getBlock() instanceof BubbleColumnBlock)) {
				continue;
			}
			BlockState below = level.getBlockState(nb.below());
			if (below.getCollisionShape(level, nb.below()).isEmpty()) continue;
			if (!level.getBlockState(nb.above()).getCollisionShape(level, nb.above()).isEmpty()) continue;
			if (!level.getBlockState(nb.above(2)).getCollisionShape(level, nb.above(2)).isEmpty()) continue;
			return nb;
		}
		return null;
	}

	private static int findCapBlockSlot(Inventory inv, CapBlockKind targetKind, boolean hasHorizontalWall) {
		if (inv == null) return -1;
		// Check hotbar first, then backpack.
		for (int pass = 0; pass < 2; pass++) {
			int start = pass == 0 ? 0 : 9;
			int end = pass == 0 ? 9 : 36;
			for (int i = start; i < end; i++) {
				ItemStack stack = inv.getItem(i);
				if (stack.isEmpty()) continue;
				Item item = stack.getItem();
				if (item instanceof HangingSignItem) continue;
				if (item instanceof SignItem) {
					if (targetKind == CapBlockKind.IN_PLACE_ZERO_COLLISION) return i;
					continue;
				}
				if (item instanceof BlockItem bi) {
					if (classifyEscapeBlock(bi.getBlock(), hasHorizontalWall) == targetKind) {
						return i;
					}
				}
			}
		}
		return -1;
	}

	private static boolean hasSafeEmptyBucket(Inventory inv) {
		if (inv == null) return false;
		int emptySlots = 0;
		boolean hasSingle = false;
		boolean hasAny = false;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) {
				emptySlots++;
			} else if (s.is(Items.BUCKET)) {
				hasAny = true;
				if (s.getCount() == 1) hasSingle = true;
			}
		}
		return hasSingle || (hasAny && emptySlots > 0);
	}

	private static boolean ensureSafeEmptyBucketOnHotbar(IBaritone baritone, LocalPlayer player) {
		Inventory inv = player.getInventory();
		int emptySlots = 0;
		for (int i = 0; i < 36; i++) {
			if (inv.getItem(i).isEmpty()) emptySlots++;
		}
		int chosenSlot = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(Items.BUCKET) && s.getCount() == 1) {
				chosenSlot = i;
				break;
			}
		}
		if (chosenSlot < 0) {
			for (int i = 9; i < 36; i++) {
				ItemStack s = inv.getItem(i);
				if (s.is(Items.BUCKET) && s.getCount() == 1) {
					chosenSlot = i;
					break;
				}
			}
		}
		if (chosenSlot < 0 && emptySlots > 0) {
			for (int i = 0; i < 36; i++) {
				if (inv.getItem(i).is(Items.BUCKET)) {
					chosenSlot = i;
					break;
				}
			}
		}
		if (chosenSlot < 0) return false;
		if (!ensureInventorySlotOnHotbar(baritone, player, chosenSlot)) return false;
		ItemStack held = player.getMainHandItem();
		return held.is(Items.BUCKET) && (held.getCount() == 1 || emptySlots > 0);
	}

	private static boolean ensureInventorySlotOnHotbar(IBaritone baritone, LocalPlayer player, int slot) {
		Inventory inv = player.getInventory();
		if (slot < 0 || slot >= 36) return false;
		if (slot < 9) {
			if (inv.getSelectedSlot() != slot) {
				inv.setSelectedSlot(slot);
			}
			return true;
		}
		if (baritone == null || !(player.containerMenu instanceof InventoryMenu)) {
			return false;
		}
		int hotbarSlot = inv.getSelectedSlot();
		for (int i = 0; i < 9; i++) {
			if (inv.getItem(i).isEmpty()) {
				hotbarSlot = i;
				break;
			}
		}
		baritone.getPlayerContext().playerController().windowClick(
				player.inventoryMenu.containerId,
				slot,
				hotbarSlot,
				ContainerInput.SWAP,
				player
		);
		inv.setSelectedSlot(hotbarSlot);
		baritone.getPlayerContext().playerController().syncHeldItem();
		return true;
	}
}
