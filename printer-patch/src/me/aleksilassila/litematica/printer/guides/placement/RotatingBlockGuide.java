package me.aleksilassila.litematica.printer.guides.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import me.aleksilassila.litematica.printer.SchematicBlockState;
import me.aleksilassila.litematica.printer.actions.Action;
import me.aleksilassila.litematica.printer.actions.PrepareAction;
import me.aleksilassila.litematica.printer.implementation.PrinterPlacementContext;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public class RotatingBlockGuide extends GeneralPlacementGuide {
	public RotatingBlockGuide(SchematicBlockState state) {
		super(state);
	}

	@Override
	protected boolean getRequiresSupport() {
		return true;
	}

	@Override
	protected boolean getRequiresExplicitShift() {
		Block block = this.state.targetState.getBlock();
		if (block instanceof CeilingHangingSignBlock) {
			return getProperty(this.state.targetState, BlockStateProperties.ATTACHED).orElse(false);
		}
		return false;
	}

	@Override
	protected Optional<Direction> getLookDirection() {
		Block block = this.state.targetState.getBlock();
		if (block instanceof WallSkullBlock
				|| block instanceof WallSignBlock
				|| block instanceof WallBannerBlock
				|| block instanceof WallHangingSignBlock) {
			return getProperty(this.state.targetState, BlockStateProperties.HORIZONTAL_FACING)
					.map(Direction::getOpposite);
		}
		if (block instanceof CeilingHangingSignBlock) {
			return Optional.of(Direction.UP);
		}
		return Optional.of(Direction.DOWN);
	}

	@Override
	protected Optional<Block> getRequiredItemAsBlock(LocalPlayer player) {
		return Optional.of(this.state.targetState.getBlock());
	}

	@Override
	protected List<Direction> getPossibleSides() {
		Block block = this.state.targetState.getBlock();
		if (block instanceof WallSkullBlock || block instanceof WallSignBlock || block instanceof WallBannerBlock) {
			return getProperty(this.state.targetState, BlockStateProperties.HORIZONTAL_FACING)
					.map(Direction::getOpposite)
					.map(Collections::singletonList)
					.orElseGet(Collections::emptyList);
		}
		if (block instanceof WallHangingSignBlock) {
			Optional<Direction> facing = getProperty(this.state.targetState, BlockStateProperties.HORIZONTAL_FACING);
			if (facing.isPresent()) {
				Direction f = facing.get();
				return List.of(f.getClockWise(), f.getCounterClockWise(), Direction.UP, Direction.DOWN);
			}
			return Collections.emptyList();
		}
		if (block instanceof CeilingHangingSignBlock) {
			return Collections.singletonList(Direction.UP);
		}
		return Collections.singletonList(Direction.DOWN);
	}

	@Override
	public boolean skipOtherGuides() {
		return true;
	}

	@Override
	public List<Action> execute(LocalPlayer player) {
		PrinterPlacementContext ctx = this.getPlacementContext(player);
		if (ctx == null) {
			return new ArrayList<>();
		}
		Block block = this.targetState.getBlock();
		float yaw;
		float pitch;
		Optional<Direction> horizontalFacing = getProperty(this.state.targetState, BlockStateProperties.HORIZONTAL_FACING);
		if (horizontalFacing.isPresent()) {
			yaw = horizontalFacing.get().getOpposite().toYRot();
			pitch = 0.0F;
		} else {
			int rot = getProperty(this.state.targetState, BlockStateProperties.ROTATION_16).orElse(0);
			if (block instanceof BannerBlock
					|| block instanceof StandingSignBlock
					|| block instanceof CeilingHangingSignBlock) {
				yaw = Mth.wrapDegrees((float) rot * 22.5F - 180.0F);
			} else {
				yaw = Mth.wrapDegrees((float) rot * 22.5F);
			}
			pitch = (block instanceof CeilingHangingSignBlock) ? -90.0F : 90.0F;
		}
		List<Action> actions = super.execute(player);
		if (!actions.isEmpty()) {
			actions.set(0, new PrepareAction(ctx, yaw, pitch));
		}
		return actions;
	}
}
