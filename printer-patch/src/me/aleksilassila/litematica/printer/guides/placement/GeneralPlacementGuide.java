package me.aleksilassila.litematica.printer.guides.placement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import me.aleksilassila.litematica.printer.Printer;
import me.aleksilassila.litematica.printer.SchematicBlockState;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.implementation.PrinterPlacementContext;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class GeneralPlacementGuide extends PlacementGuide {
	public GeneralPlacementGuide(SchematicBlockState state) {
		super(state);
	}

	protected List<Direction> getPossibleSides() {
		return Arrays.asList(Direction.values());
	}

	protected Optional<Direction> getLookDirection() {
		return Optional.empty();
	}

	protected boolean getRequiresSupport() {
		return false;
	}

	protected boolean getRequiresExplicitShift() {
		return false;
	}

	protected Vec3 getHitModifier(Direction side) {
		return new Vec3(0.0, 0.0, 0.0);
	}

	protected Optional<Direction> getSupportedSide(SchematicBlockState state) {
		List<Direction> possibleSides = this.getPossibleSides();
		if (possibleSides.isEmpty()) {
			return Optional.empty();
		}
		List<Direction> validSides = new ArrayList<>();
		for (Direction side : possibleSides) {
			SchematicBlockState neighbor = state.offset(side);
			if (getProperty(neighbor.currentState, SlabBlock.TYPE).orElse(null) == SlabType.DOUBLE) {
				validSides.add(side);
				continue;
			}
			if (canBeClicked(neighbor.world, neighbor.blockPos) && !neighbor.currentState.canBeReplaced()) {
				validSides.add(side);
			}
		}
		for (Direction side : validSides) {
			if (!this.isInteractive(state.offset(side).currentState.getBlock())) {
				return Optional.of(side);
			}
		}
		return validSides.isEmpty() ? Optional.empty() : Optional.of(validSides.getFirst());
	}

	private Optional<Direction> getValidSide(SchematicBlockState state) {
		Optional<Direction> supported = this.getSupportedSide(state);
		if (supported.isPresent()) {
			return supported;
		}
		boolean printInAir = Configs.PRINT_IN_AIR.getBooleanValue();
		List<Direction> possibleSides = this.getPossibleSides();
		if (possibleSides.isEmpty()) {
			return Optional.empty();
		}
		if (printInAir && !this.getRequiresSupport()) {
			return Optional.of(possibleSides.get(0));
		}
		return Optional.empty();
	}

	@Override
	protected boolean getUseShift(SchematicBlockState state) {
		if (this.getRequiresExplicitShift()) {
			return true;
		}
		Optional<Direction> supported = this.getSupportedSide(state);
		if (supported.isEmpty()) {
			return false;
		}
		return this.isInteractive(state.offset(supported.get()).currentState.getBlock());
	}

	private Optional<Vec3> getHitVector(SchematicBlockState state) {
		Optional<Direction> supported = this.getSupportedSide(state);
		if (supported.isPresent()) {
			Direction side = supported.get();
			return Optional.of(
					Vec3.atCenterOf(state.blockPos)
							.add(Vec3.atLowerCornerOf(side.getUnitVec3i()).scale(0.5))
							.add(this.getHitModifier(side))
			);
		}
		if (Configs.PRINT_IN_AIR.getBooleanValue() && !this.getRequiresSupport()) {
			Optional<Direction> validSide = this.getValidSide(state);
			Vec3 mod = validSide.map(this::getHitModifier).orElse(Vec3.ZERO);
			return Optional.of(Vec3.atCenterOf(state.blockPos).add(mod));
		}
		return Optional.empty();
	}

	@Override
	public PrinterPlacementContext getPlacementContext(LocalPlayer player) {
		try {
			Optional<Direction> supportedSide = this.getSupportedSide(this.state);
			Optional<Direction> validSide = supportedSide.isPresent() ? supportedSide : this.getValidSide(this.state);
			Optional<Vec3> hitVec = this.getHitVector(this.state);
			Optional<ItemStack> requiredItem = this.getRequiredItem(player);
			int slot = this.getRequiredItemStackSlot(player);
			if (validSide.isEmpty() || hitVec.isEmpty() || requiredItem.isEmpty() || slot == -1) {
				return null;
			}
			Optional<Direction> lookDirection = this.getLookDirection();
			boolean shouldSneak = this.getUseShift(this.state);
			BlockHitResult hitResult;
			if (supportedSide.isPresent()) {
				Direction side = supportedSide.get();
				hitResult = new BlockHitResult(
						hitVec.get(),
						side.getOpposite(),
						this.state.blockPos.relative(side),
						false
				);
			} else if (Configs.PRINT_IN_AIR.getBooleanValue() && !this.getRequiresSupport()) {
				Direction clickedFace = validSide.get().getOpposite();
				if (this.targetState.hasProperty(RotatedPillarBlock.AXIS)) {
					Direction.Axis axis = this.targetState.getValue(RotatedPillarBlock.AXIS);
					if (axis == Direction.Axis.Y) {
						clickedFace = Direction.DOWN;
					} else if (axis == Direction.Axis.X) {
						clickedFace = Direction.WEST;
					} else {
						clickedFace = Direction.NORTH;
					}
				} else if (this.targetState.hasProperty(SlabBlock.TYPE)) {
					SlabType targetSlabType = this.targetState.getValue(SlabBlock.TYPE);
					clickedFace = (targetSlabType == SlabType.TOP) ? Direction.DOWN : Direction.UP;
				}
				hitResult = new BlockHitResult(hitVec.get(), clickedFace, this.state.blockPos, false);
			} else {
				return null;
			}
			return new PrinterPlacementContext(
					player,
					hitResult,
					requiredItem.get(),
					slot,
					lookDirection.orElse(null),
					shouldSneak
			);
		} catch (Exception e) {
			Printer.logger.error("getPlacementContext(): Exception caught: {}", e.getMessage());
			return null;
		}
	}
}
