package me.aleksilassila.litematica.printer.guides.placement;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import me.aleksilassila.litematica.printer.SchematicBlockState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public class TorchGuide extends GeneralPlacementGuide {
	public TorchGuide(SchematicBlockState state) {
		super(state);
	}

	@Override
	public boolean skipOtherGuides() {
		return true;
	}

	@Override
	protected boolean getRequiresSupport() {
		return true;
	}

	@Override
	protected Optional<Direction> getLookDirection() {
		Optional<Direction> facing = getProperty(this.targetState, HorizontalDirectionalBlock.FACING);
		if (facing.isPresent()) {
			return Optional.of(facing.get().getOpposite());
		}
		return Optional.of(Direction.DOWN);
	}

	@Override
	protected List<Direction> getPossibleSides() {
		Optional<Direction> facing = getProperty(this.targetState, HorizontalDirectionalBlock.FACING);
		return facing
				.map(Direction::getOpposite)
				.map(Collections::singletonList)
				.orElseGet(() -> Collections.singletonList(Direction.DOWN));
	}

	@Override
	protected Optional<Block> getRequiredItemAsBlock(LocalPlayer player) {
		return Optional.of(this.state.targetState.getBlock());
	}

	@Override
	protected boolean statesEqual(BlockState state1, BlockState state2) {
		return this.statesEqualIgnoreProperties(state1, state2, BlockStateProperties.LIT);
	}
}
