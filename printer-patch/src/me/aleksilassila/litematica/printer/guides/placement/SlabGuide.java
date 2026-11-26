package me.aleksilassila.litematica.printer.guides.placement;

import java.util.ArrayList;
import java.util.List;
import me.aleksilassila.litematica.printer.SchematicBlockState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

public class SlabGuide extends GeneralPlacementGuide {
	public SlabGuide(SchematicBlockState state) {
		super(state);
	}

	@Override
	public boolean skipOtherGuides() {
		return false;
	}

	@Override
	public boolean canExecute(LocalPlayer player) {
		// If currentState is already a half-slab of the same block, SlabGuide must NOT run:
		// - If targetState is DOUBLE, BlockReplacementGuide completes the second half.
		// - If targetState is the opposite half (e.g. TOP vs BOTTOM), it must be broken first, not merged into DOUBLE!
		if (this.currentState.getBlock() == this.targetState.getBlock()
				&& this.currentState.hasProperty(SlabBlock.TYPE)) {
			return false;
		}
		return super.canExecute(player);
	}

	private SlabType getDesiredHalfType() {
		SlabType targetType = getProperty(this.state.targetState, SlabBlock.TYPE).orElse(SlabType.DOUBLE);
		return targetType == SlabType.TOP ? SlabType.TOP : SlabType.BOTTOM;
	}

	@Override
	protected List<Direction> getPossibleSides() {
		List<Direction> sides = new ArrayList<>();
		SlabType desiredHalf = this.getDesiredHalfType();
		Direction verticalDir = (desiredHalf == SlabType.TOP) ? Direction.UP : Direction.DOWN;
		BlockState verticalNeighbor = this.state.offset(verticalDir).currentState;
		SlabType verticalNeighborType = getProperty(verticalNeighbor, SlabBlock.TYPE).orElse(SlabType.DOUBLE);
		boolean verticalWouldMergeIntoNeighbor = verticalNeighbor.getBlock() == this.targetState.getBlock()
				&& verticalNeighborType == desiredHalf;
		if (!verticalWouldMergeIntoNeighbor) {
			sides.add(verticalDir);
		}

		Direction[] horizontals = new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
		for (Direction dir : horizontals) {
			BlockState neighborState = this.state.offset(dir).currentState;
			SlabType neighborType = getProperty(neighborState, SlabBlock.TYPE).orElse(SlabType.DOUBLE);
			// Clicking a horizontal face at desiredHalf's Y offset only merges into neighborPos
			// if neighborPos is the EXACT same slab block AND is a half-slab of the OPPOSITE half!
			boolean horizontalWouldMergeIntoNeighbor = neighborState.getBlock() == this.targetState.getBlock()
					&& neighborType != SlabType.DOUBLE
					&& neighborType != desiredHalf;
			if (!horizontalWouldMergeIntoNeighbor) {
				sides.add(dir);
			}
		}
		return sides;
	}

	@Override
	protected Vec3 getHitModifier(Direction side) {
		Direction requiredHalf = this.getRequiredHalf(this.state);
		return new Vec3(0.0, (double) requiredHalf.getStepY() * 0.25, 0.0);
	}

	private Direction getRequiredHalf(SchematicBlockState state) {
		BlockState target = state.targetState;
		BlockState current = state.currentState;
		if (!current.hasProperty(SlabBlock.TYPE)) {
			return target.getValue(SlabBlock.TYPE) == SlabType.TOP ? Direction.UP : Direction.DOWN;
		}
		if (current.getValue(SlabBlock.TYPE) != target.getValue(SlabBlock.TYPE)) {
			return current.getValue(SlabBlock.TYPE) == SlabType.TOP ? Direction.DOWN : Direction.UP;
		}
		return Direction.DOWN;
	}
}
