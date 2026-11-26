package me.aleksilassila.litematica.printer.guides.placement;

import me.aleksilassila.litematica.printer.SchematicBlockState;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.implementation.PrinterPlacementContext;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class GuesserGuide extends GeneralPlacementGuide {
	private PrinterPlacementContext contextCache = null;
	protected static Direction[] directionsToTry = new Direction[]{
			Direction.NORTH,
			Direction.SOUTH,
			Direction.EAST,
			Direction.WEST,
			Direction.UP,
			Direction.DOWN
	};
	protected static Vec3[] hitVecsToTry = new Vec3[]{
			new Vec3(0.0, 0.0, 0.0),
			new Vec3(0.0, -0.25, 0.0),
			new Vec3(0.0, 0.25, 0.0),
			new Vec3(-0.25, -0.25, -0.25),
			new Vec3(0.25, -0.25, -0.25),
			new Vec3(-0.25, 0.25, -0.25),
			new Vec3(-0.25, -0.25, 0.25),
			new Vec3(0.25, 0.25, -0.25),
			new Vec3(-0.25, 0.25, 0.25),
			new Vec3(0.25, -0.25, 0.25),
			new Vec3(0.25, 0.25, 0.25)
	};

	public GuesserGuide(SchematicBlockState state) {
		super(state);
	}

	@Override
	public PrinterPlacementContext getPlacementContext(LocalPlayer player) {
		if (this.contextCache != null && !Configs.PRINT_DEBUG.getBooleanValue()) {
			return this.contextCache;
		}
		ItemStack stack = this.getRequiredItem(player).orElse(ItemStack.EMPTY);
		int slot = this.getRequiredItemStackSlot(player);
		if (slot == -1) {
			return null;
		}
		Block block = this.getRequiredItemAsBlock(player).orElse(this.targetState.getBlock());

		// 1. Always prefer clicking a real clickable neighbor block first.
		for (Direction lookDir : directionsToTry) {
			for (Direction side : directionsToTry) {
				BlockPos neighborPos = this.state.blockPos.relative(side);
				BlockState neighborState = this.state.world.getBlockState(neighborPos);
				boolean shouldSneak = this.getRequiresExplicitShift() || this.isInteractive(neighborState.getBlock());
				if (!canBeClicked(this.state.world, neighborPos) || neighborState.canBeReplaced()) {
					continue;
				}
				Vec3 faceCenter = Vec3.atCenterOf(this.state.blockPos)
						.add(Vec3.atLowerCornerOf(side.getUnitVec3i()).scale(0.5));
				Vec3 sideUnit = Vec3.atLowerCornerOf(side.getUnitVec3i());
				Vec3 planeMask = new Vec3(
						sideUnit.x == 0.0 ? 1.0 : 0.0,
						sideUnit.y == 0.0 ? 1.0 : 0.0,
						sideUnit.z == 0.0 ? 1.0 : 0.0
				);
				for (Vec3 mod : hitVecsToTry) {
					BlockHitResult hitResult = new BlockHitResult(
							faceCenter.add(mod.multiply(planeMask)),
							side.getOpposite(),
							neighborPos,
							false
					);
					PrinterPlacementContext ctx = new PrinterPlacementContext(
							player, hitResult, stack, slot, lookDir, shouldSneak
					);
					if (!ctx.getClickedPos().equals(this.state.blockPos)) {
						continue;
					}
					BlockState placed = block.getStateForPlacement(ctx);
					if (placed != null && (this.statesEqual(placed, this.targetState)
							|| this.correctChestPlacement(this.targetState, placed))) {
						this.contextCache = ctx;
						return ctx;
					}
				}
			}
		}

		// 2. Fallback to PRINT_IN_AIR when no neighbor is available and support is not required.
		boolean printInAir = Configs.PRINT_IN_AIR.getBooleanValue();
		if (printInAir && !this.getRequiresSupport()) {
			Vec3 blockCenter = Vec3.atCenterOf(this.state.blockPos);
			for (Direction lookDir : directionsToTry) {
				for (Direction side : directionsToTry) {
					boolean shouldSneak = this.getRequiresExplicitShift()
							|| this.isInteractive(this.state.world.getBlockState(this.state.blockPos.relative(side)).getBlock());
					for (Vec3 mod : hitVecsToTry) {
						BlockHitResult hitResult = new BlockHitResult(
								blockCenter.add(mod),
								side.getOpposite(),
								this.state.blockPos,
								false
						);
						PrinterPlacementContext ctx = new PrinterPlacementContext(
								player, hitResult, stack, slot, lookDir, shouldSneak
						);
						if (!ctx.getClickedPos().equals(this.state.blockPos)) {
							continue;
						}
						BlockState placed = block.getStateForPlacement(ctx);
						if (placed != null && (this.statesEqual(placed, this.targetState)
								|| this.correctChestPlacement(this.targetState, placed))) {
							this.contextCache = ctx;
							return ctx;
						}
					}
				}
			}
		}
		return null;
	}

	@Override
	public boolean canExecute(LocalPlayer player) {
		if (this.targetState.getBlock() instanceof SlabBlock
				|| this.targetState.hasProperty(RotatedPillarBlock.AXIS)) {
			return false;
		}
		return super.canExecute(player);
	}

	private boolean correctChestPlacement(BlockState target, BlockState placed) {
		if (target.hasProperty(ChestBlock.TYPE)
				&& placed.hasProperty(ChestBlock.TYPE)
				&& placed.getValue(ChestBlock.FACING) == target.getValue(ChestBlock.FACING)) {
			ChestType targetType = target.getValue(ChestBlock.TYPE);
			ChestType placedType = placed.getValue(ChestBlock.TYPE);
			return targetType != ChestType.SINGLE && placedType == ChestType.SINGLE;
		}
		return false;
	}
}
