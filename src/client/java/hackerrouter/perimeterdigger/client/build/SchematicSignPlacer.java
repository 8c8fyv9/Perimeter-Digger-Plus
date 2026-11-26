package hackerrouter.perimeterdigger.client.build;

import baritone.api.IBaritone;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Exact sign placer for schematic building.
 *
 * <p>Works around upstream litematica-printer's {@code RotatingBlockGuide} bug (which forces
 * {@code yaw = 0} for all {@link WallSignBlock}s even though vanilla {@code WallSignBlock} uses
 * {@code ctx.getNearestLookingDirections()} to choose wall facing, and fails whenever
 * {@code Configs.ROTATE} is disabled or Baritone overrides camera yaw during walking).
 *
 * <p>Handles all 4 vanilla sign variants ({@link StandingSignBlock}, {@link WallSignBlock},
 * {@link CeilingHangingSignBlock}, {@link WallHangingSignBlock}) with exact yaw/pitch sync,
 * sneaking on interactable support blocks, and auto-closing the sign edit screen.
 */
public final class SchematicSignPlacer {
	public record PlacementPlan(
			BlockPos supportPos,
			Direction clickedFace,
			Vec3 hitPoint,
			float yaw,
			float pitch,
			boolean sneak
	) {}

	private static final double PLACE_REACH = 4.3;

	private BlockPos preparedPos;
	private int prepareTicks;
	private boolean holdingSneak;

	public static boolean isSignTarget(BlockState state) {
		return state != null && state.getBlock() instanceof SignBlock;
	}

	/**
	 * Pure yaw computation for {@link StandingSignBlock} and {@link CeilingHangingSignBlock}:
	 * vanilla computes {@code RotationSegment.convertToSegment(playerYaw + 180.0F)}, so the exact
	 * player yaw for {@code rotation} (0..15) is {@code convertToDegrees((rotation + 8) & 15)}.
	 */
	public static float computeStandingSignYaw(int rotation) {
		return RotationSegment.convertToDegrees((rotation + 8) & 15);
	}

	/**
	 * Pure yaw computation for {@link WallSignBlock} and {@link WallHangingSignBlock}:
	 * vanilla checks {@code ctx.getNearestLookingDirections()} and picks {@code dir.getOpposite()},
	 * so looking directly toward {@code facing.getOpposite()} with pitch 0 guarantees exact facing.
	 */
	public static float computeWallSignYaw(Direction facing) {
		if (facing == null || !facing.getAxis().isHorizontal()) {
			return 0.0F;
		}
		return facing.getOpposite().toYRot();
	}

	/** Computes the exact support block, face, yaw/pitch, and sneak state for placing a sign. */
	public static PlacementPlan planPlacement(Level level, BlockPos targetPos, BlockState targetState) {
		if (level == null || targetPos == null || targetState == null) {
			return null;
		}
		Block block = targetState.getBlock();
		if (block instanceof WallSignBlock) {
			Direction facing = targetState.getValue(WallSignBlock.FACING);
			BlockPos wallPos = targetPos.relative(facing.getOpposite());
			if (!level.isLoaded(wallPos) || !level.getBlockState(wallPos).isSolid()) {
				return null;
			}
			Vec3 hit = faceCenter(wallPos, facing);
			boolean sneak = isInteractableSupport(level.getBlockState(wallPos).getBlock());
			return new PlacementPlan(wallPos, facing, hit, computeWallSignYaw(facing), 0.0F, sneak);
		}
		if (block instanceof StandingSignBlock) {
			BlockPos floorPos = targetPos.below();
			if (!level.isLoaded(floorPos) || !level.getBlockState(floorPos).isSolid()) {
				return null;
			}
			int rot = targetState.getValue(StandingSignBlock.ROTATION);
			Vec3 hit = faceCenter(floorPos, Direction.UP);
			boolean sneak = isInteractableSupport(level.getBlockState(floorPos).getBlock());
			return new PlacementPlan(floorPos, Direction.UP, hit, computeStandingSignYaw(rot), 90.0F, sneak);
		}
		if (block instanceof CeilingHangingSignBlock) {
			BlockPos ceilPos = targetPos.above();
			if (!level.isLoaded(ceilPos) || !targetState.canSurvive(level, targetPos)) {
				return null;
			}
			int rot = targetState.getValue(CeilingHangingSignBlock.ROTATION);
			boolean attached = targetState.getValue(CeilingHangingSignBlock.ATTACHED);
			Vec3 hit = faceCenter(ceilPos, Direction.DOWN);
			boolean sneak = attached || isInteractableSupport(level.getBlockState(ceilPos).getBlock());
			return new PlacementPlan(ceilPos, Direction.DOWN, hit, computeStandingSignYaw(rot), -90.0F, sneak);
		}
		if (block instanceof WallHangingSignBlock wallHanging) {
			Direction facing = targetState.getValue(WallHangingSignBlock.FACING);
			Direction cw = facing.getClockWise();
			Direction ccw = facing.getCounterClockWise();
			BlockPos supportPos = null;
			Direction clickedFace = null;
			if (level.isLoaded(targetPos.relative(cw))
					&& wallHanging.canAttachTo(level, targetState, targetPos.relative(cw), ccw)) {
				supportPos = targetPos.relative(cw);
				clickedFace = ccw;
			} else if (level.isLoaded(targetPos.relative(ccw))
					&& wallHanging.canAttachTo(level, targetState, targetPos.relative(ccw), cw)) {
				supportPos = targetPos.relative(ccw);
				clickedFace = cw;
			}
			if (supportPos == null) {
				return null;
			}
			Vec3 hit = faceCenter(supportPos, clickedFace);
			boolean sneak = isInteractableSupport(level.getBlockState(supportPos).getBlock());
			return new PlacementPlan(supportPos, clickedFace, hit, computeWallSignYaw(facing), 0.0F, sneak);
		}
		return null;
	}

	/** Closes vanilla's sign text editor popup immediately when opened during automated building. */
	public static void closeSignEditScreenIfOpen(Minecraft mc) {
		if (mc != null && mc.gui != null && mc.gui.screen() instanceof AbstractSignEditScreen editScreen) {
			editScreen.onClose();
		}
	}

	public void reset(IBaritone baritone) {
		preparedPos = null;
		prepareTicks = 0;
		releaseSneak(baritone);
	}

	/**
	 * Attempts one tick of exact sign placement for {@code targetPos} once the player is within reach.
	 * Tick 1 equips the sign item, sets exact player yaw/pitch (and sends {@link ServerboundMovePlayerPacket.Rot}),
	 * and applies sneak if needed; Tick 2 sends {@code useItemOn} so server yaw/sneak are guaranteed synced.
	 *
	 * @return true if placement is in progress or was just executed; false if impossible right now
	 *         (missing support, out of reach, or missing sign item).
	 */
	public boolean tickPlace(
			IBaritone baritone,
			Minecraft mc,
			LocalPlayer player,
			Level level,
			BlockPos targetPos,
			BlockState targetState
	) {
		if (mc == null || mc.gameMode == null || player == null || level == null) {
			reset(baritone);
			return false;
		}
		PlacementPlan plan = planPlacement(level, targetPos, targetState);
		if (plan == null) {
			reset(baritone);
			return false;
		}
		if (player.getEyePosition().distanceTo(plan.hitPoint()) > PLACE_REACH) {
			reset(baritone);
			return false;
		}
		Item signItem = targetState.getBlock().asItem();
		if (signItem == Items.AIR || !ensureSignOnHotbar(baritone, player, signItem)) {
			reset(baritone);
			return false;
		}

		// Apply exact yaw & pitch every tick so server's getNearestLookingDirections / getRotation matches!
		player.setYRot(plan.yaw());
		player.setXRot(plan.pitch());
		player.yHeadRot = plan.yaw();
		if (mc.getConnection() != null) {
			mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
					plan.yaw(), plan.pitch(), player.onGround(), player.horizontalCollision));
		}

		if (plan.sneak()) {
			setSneak(baritone, true);
		} else {
			releaseSneak(baritone);
		}

		// Wait 1 tick after preparing rotation/sneak/slot so multiplayer server state is synchronized.
		if (!targetPos.equals(preparedPos)) {
			preparedPos = targetPos.immutable();
			prepareTicks = 1;
			return true;
		}
		if (prepareTicks < 2 && plan.sneak() && !player.isShiftKeyDown()) {
			prepareTicks++;
			return true;
		}

		BlockHitResult hit = new BlockHitResult(plan.hitPoint(), plan.clickedFace(), plan.supportPos(), false);
		if (plan.sneak() && player.input != null) {
			net.minecraft.world.entity.player.Input cur = player.input.keyPresses;
			player.input.keyPresses = new net.minecraft.world.entity.player.Input(
					cur.forward(), cur.backward(), cur.left(), cur.right(), cur.jump(), true, cur.sprint());
			if (mc.getConnection() != null) {
				mc.getConnection().send(new ServerboundPlayerInputPacket(player.input.keyPresses));
			}
		}
		mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
		player.swing(InteractionHand.MAIN_HAND);
		if (plan.sneak() && player.input != null) {
			net.minecraft.world.entity.player.Input cur = player.input.keyPresses;
			player.input.keyPresses = new net.minecraft.world.entity.player.Input(
					cur.forward(), cur.backward(), cur.left(), cur.right(), cur.jump(), false, cur.sprint());
			if (mc.getConnection() != null) {
				mc.getConnection().send(new ServerboundPlayerInputPacket(player.input.keyPresses));
			}
		}
		closeSignEditScreenIfOpen(mc);
		preparedPos = null;
		prepareTicks = 0;
		releaseSneak(baritone);
		return true;
	}

	/** Checks whether a nearby unplaced sign on {@code layerY} is currently placeable and owned in inventory. */
	public static BlockPos findNearbyPlaceableSign(
			Level level,
			Level schematicWorld,
			LocalPlayer player,
			int layerY,
			double radius,
			java.util.Set<Long> layerSkipped,
			boolean replaceFluids,
			SchematicLayerPlanner.WrongBlockPolicy wrongPolicy
	) {
		if (level == null || schematicWorld == null || player == null) return null;
		int r = (int) Math.ceil(radius);
		BlockPos origin = player.blockPosition();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dz = -r; dz <= r; dz++) {
			for (int dx = -r; dx <= r; dx++) {
				cursor.set(origin.getX() + dx, layerY, origin.getZ() + dz);
				if (layerSkipped != null && layerSkipped.contains(cursor.asLong())) continue;
				if (!level.isLoaded(cursor) || !schematicWorld.isLoaded(cursor)) continue;
				BlockState tgt = schematicWorld.getBlockState(cursor);
				if (!isSignTarget(tgt)) continue;
				SchematicLayerPlanner.CellKind kind = SchematicBuildController.classifyCell(
						level, schematicWorld, cursor, replaceFluids);
				if (kind != SchematicLayerPlanner.CellKind.MISSING) continue;
				Item signItem = tgt.getBlock().asItem();
				if (signItem == Items.AIR || !hasItemInInventory(player, signItem)) continue;
				PlacementPlan plan = planPlacement(level, cursor, tgt);
				if (plan == null) continue;
				double d = player.getEyePosition().distanceToSqr(plan.hitPoint());
				if (d <= PLACE_REACH * PLACE_REACH && d < bestDist) {
					bestDist = d;
					best = cursor.immutable();
				}
			}
		}
		return best;
	}

	private void setSneak(IBaritone baritone, boolean sneak) {
		if (baritone != null) {
			baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, sneak);
		}
		holdingSneak = sneak;
	}

	private void releaseSneak(IBaritone baritone) {
		if (holdingSneak && baritone != null) {
			baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
		}
		holdingSneak = false;
	}

	private static Vec3 faceCenter(BlockPos pos, Direction face) {
		return Vec3.atCenterOf(pos).add(
				face.getStepX() * 0.5,
				face.getStepY() * 0.5,
				face.getStepZ() * 0.5
		);
	}

	private static boolean isInteractableSupport(Block block) {
		return block instanceof SignBlock
				|| block instanceof ChestBlock
				|| block instanceof BarrelBlock
				|| block instanceof ShulkerBoxBlock
				|| block instanceof HopperBlock
				|| block instanceof DispenserBlock
				|| block instanceof CraftingTableBlock
				|| block instanceof DoorBlock
				|| block instanceof TrapDoorBlock
				|| block instanceof FenceGateBlock
				|| block instanceof ButtonBlock
				|| block instanceof LeverBlock
				|| block instanceof NoteBlock
				|| block instanceof RepeaterBlock
				|| block instanceof ComparatorBlock
				|| block instanceof DiodeBlock
				|| block instanceof CampfireBlock;
	}

	private static boolean hasItemInInventory(LocalPlayer player, Item item) {
		Inventory inv = player.getInventory();
		for (int i = 0; i < 36; i++) {
			if (inv.getItem(i).is(item)) return true;
		}
		return false;
	}

	private static boolean ensureSignOnHotbar(IBaritone baritone, LocalPlayer player, Item item) {
		Inventory inv = player.getInventory();
		for (int i = 0; i < 9; i++) {
			if (inv.getItem(i).is(item)) {
				if (inv.getSelectedSlot() != i) {
					inv.setSelectedSlot(i);
				}
				return true;
			}
		}
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
		int hotbarSlot = pickHotbarSlot(inv);
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

	private static int pickHotbarSlot(Inventory inv) {
		for (int i = 0; i < 9; i++) {
			if (inv.getItem(i).isEmpty()) return i;
		}
		for (int i = 0; i < 9; i++) {
			ItemStack s = inv.getItem(i);
			String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
			if (!id.endsWith("_pickaxe") && !id.endsWith("_axe") && !id.endsWith("_shovel") && !id.endsWith("_sword")) {
				return i;
			}
		}
		return inv.getSelectedSlot();
	}
}
