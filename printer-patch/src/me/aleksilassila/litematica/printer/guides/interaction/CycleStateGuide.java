package me.aleksilassila.litematica.printer.guides.interaction;

import java.util.Collections;
import java.util.List;
import me.aleksilassila.litematica.printer.SchematicBlockState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

public class CycleStateGuide extends InteractionGuide {
	private static final Property<?>[] propertiesToIgnore = new Property<?>[]{
			BlockStateProperties.POWERED,
			BlockStateProperties.LIT,
			BlockStateProperties.IN_WALL,
			BlockStateProperties.NOTEBLOCK_INSTRUMENT
	};

	public CycleStateGuide(SchematicBlockState state) {
		super(state);
	}

	@Override
	public boolean canExecute(LocalPlayer player) {
		if (!super.canExecute(player)) {
			return false;
		}
		if (this.targetState.getBlock() != this.currentState.getBlock()) {
			return false;
		}
		// Never cycle a block whose non-cycleable placement orientation (FACING, HALF, HINGE, ATTACH_FACE) differs!
		if (this.targetState.hasProperty(HorizontalDirectionalBlock.FACING)
				&& this.currentState.getValue(HorizontalDirectionalBlock.FACING) != this.targetState.getValue(HorizontalDirectionalBlock.FACING)) {
			return false;
		}
		if (this.targetState.hasProperty(BlockStateProperties.HALF)
				&& this.currentState.getValue(BlockStateProperties.HALF) != this.targetState.getValue(BlockStateProperties.HALF)) {
			return false;
		}
		if (this.targetState.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
				&& this.currentState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != this.targetState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
			return false;
		}
		if (this.targetState.hasProperty(BlockStateProperties.DOOR_HINGE)
				&& this.currentState.getValue(BlockStateProperties.DOOR_HINGE) != this.targetState.getValue(BlockStateProperties.DOOR_HINGE)) {
			return false;
		}
		if (this.targetState.hasProperty(BlockStateProperties.ATTACH_FACE)
				&& this.currentState.getValue(BlockStateProperties.ATTACH_FACE) != this.targetState.getValue(BlockStateProperties.ATTACH_FACE)) {
			return false;
		}
		return hasCycleableDifference(this.currentState, this.targetState);
	}

	private static boolean hasCycleableDifference(BlockState current, BlockState target) {
		if ((target.getBlock() instanceof DoorBlock || target.getBlock() instanceof FenceGateBlock || target.getBlock() instanceof TrapDoorBlock)
				&& target.hasProperty(BlockStateProperties.OPEN)) {
			return current.getValue(BlockStateProperties.OPEN) != target.getValue(BlockStateProperties.OPEN);
		}
		if (target.getBlock() instanceof LeverBlock && target.hasProperty(BlockStateProperties.POWERED)) {
			return current.getValue(BlockStateProperties.POWERED) != target.getValue(BlockStateProperties.POWERED);
		}
		if (target.getBlock() instanceof RepeaterBlock && target.hasProperty(RepeaterBlock.DELAY)) {
			return !current.getValue(RepeaterBlock.DELAY).equals(target.getValue(RepeaterBlock.DELAY));
		}
		if (target.getBlock() instanceof ComparatorBlock && target.hasProperty(ComparatorBlock.MODE)) {
			return current.getValue(ComparatorBlock.MODE) != target.getValue(ComparatorBlock.MODE);
		}
		if (target.getBlock() instanceof NoteBlock && target.hasProperty(NoteBlock.NOTE)) {
			return !current.getValue(NoteBlock.NOTE).equals(target.getValue(NoteBlock.NOTE));
		}
		return true;
	}

	@Override
	protected List<ItemStack> getRequiredItems() {
		return Collections.singletonList(ItemStack.EMPTY);
	}

	@Override
	protected boolean statesEqual(BlockState state1, BlockState state2) {
		if (state2.getBlock() instanceof LeverBlock) {
			return super.statesEqual(state1, state2);
		}
		return this.statesEqualIgnoreProperties(state1, state2, propertiesToIgnore);
	}
}
