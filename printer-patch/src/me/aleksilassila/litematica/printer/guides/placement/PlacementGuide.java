package me.aleksilassila.litematica.printer.guides.placement;

import fi.dy.masa.litematica.util.ItemUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import me.aleksilassila.litematica.printer.Printer;
import me.aleksilassila.litematica.printer.SchematicBlockState;
import me.aleksilassila.litematica.printer.actions.Action;
import me.aleksilassila.litematica.printer.actions.PrepareAction;
import me.aleksilassila.litematica.printer.actions.ReleaseShiftAction;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.guides.Guide;
import me.aleksilassila.litematica.printer.implementation.PrinterPlacementContext;
import me.aleksilassila.litematica.printer.implementation.actions.InteractActionImpl;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public abstract class PlacementGuide extends Guide {
	public PlacementGuide(SchematicBlockState state) {
		super(state);
	}

	protected ItemStack getBlockItem(BlockState state) {
		return ItemUtils.getItemForBlock(this.state.world, this.state.blockPos, state, true);
	}

	protected Optional<Block> getRequiredItemAsBlock(LocalPlayer player) {
		Optional<ItemStack> requiredItem = this.getRequiredItem(player);
		if (requiredItem.isEmpty()) {
			return Optional.empty();
		}
		ItemStack stack = requiredItem.get();
		if (stack.getItem() instanceof StandingAndWallBlockItem) {
			return Optional.of(this.state.targetState.getBlock());
		}
		if (stack.getItem() instanceof BlockItem blockItem) {
			return Optional.of(blockItem.getBlock());
		}
		return Optional.empty();
	}

	@Override
	protected List<ItemStack> getRequiredItems() {
		Printer.printDebug("PlacementGuide#getRequiredItems() - target state [{}]", this.state.targetState.toString());
		return Collections.singletonList(this.getBlockItem(this.state.targetState));
	}

	protected abstract boolean getUseShift(SchematicBlockState state);

	public abstract PrinterPlacementContext getPlacementContext(LocalPlayer player);

	@Override
	public boolean canExecute(LocalPlayer player) {
		if (!super.canExecute(player)) {
			return false;
		}
		List<ItemStack> requiredItems = this.getRequiredItems();
		if (requiredItems.isEmpty() || requiredItems.stream().allMatch(s -> s.is(Items.AIR))) {
			return false;
		}
		PrinterPlacementContext ctx = this.getPlacementContext(player);
		if (ctx == null || !ctx.canPlace() || !ctx.getClickedPos().equals(this.state.blockPos)) {
			return false;
		}
		if (!Configs.REPLACE_FLUIDS_SOURCE_BLOCKS.getBooleanValue()
				&& getProperty(this.state.currentState, LiquidBlock.LEVEL).orElse(1) == 0) {
			return false;
		}
		Block block = this.getRequiredItemAsBlock(player).orElse(this.targetState.getBlock());
		BlockState stateForPlacement = block.getStateForPlacement(ctx);
		if (stateForPlacement == null) {
			return false;
		}
		if (!stateForPlacement.canSurvive(this.state.world, this.state.blockPos)) {
			return false;
		}
		// Hard guard: never execute a slab placement that produces the wrong SlabType,
		// and never execute a pillar/log placement that produces the wrong AXIS.
		if (this.targetState.hasProperty(SlabBlock.TYPE) && stateForPlacement.hasProperty(SlabBlock.TYPE)) {
			SlabType targetType = this.targetState.getValue(SlabBlock.TYPE);
			if (targetType != SlabType.DOUBLE && stateForPlacement.getValue(SlabBlock.TYPE) != targetType) {
				return false;
			}
		}
		if (this.targetState.hasProperty(RotatedPillarBlock.AXIS) && stateForPlacement.hasProperty(RotatedPillarBlock.AXIS)) {
			if (stateForPlacement.getValue(RotatedPillarBlock.AXIS) != this.targetState.getValue(RotatedPillarBlock.AXIS)) {
				return false;
			}
		}
		if (this.currentState.getBlock() instanceof LiquidBlock && !this.canPlaceInWater(stateForPlacement)) {
			return false;
		}
		return true;
	}

	@Override
	public List<Action> execute(LocalPlayer player) {
		List<Action> actions = new ArrayList<>();
		PrinterPlacementContext ctx = this.getPlacementContext(player);
		if (ctx == null) {
			return actions;
		}
		actions.add(new PrepareAction(ctx));
		actions.add(new InteractActionImpl(ctx));
		if (ctx.shouldSneak) {
			actions.add(new ReleaseShiftAction());
		}
		return actions;
	}

	protected static boolean canBeClicked(Level world, BlockPos pos) {
		return getOutlineShape(world, pos) != Shapes.empty();
	}

	private static VoxelShape getOutlineShape(Level world, BlockPos pos) {
		return world.getBlockState(pos).getShape(world, pos);
	}

	public boolean isInteractive(Block block) {
		for (Class<?> cls : interactiveBlocks) {
			if (cls.isInstance(block)) {
				return true;
			}
		}
		return false;
	}

	private boolean canPlaceInWater(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof LiquidBlockContainer) {
			return true;
		}
		return (block instanceof DoorBlock)
				|| (block instanceof SignBlock)
				|| state.is(Blocks.LADDER)
				|| state.is(Blocks.SUGAR_CANE)
				|| state.is(Blocks.BUBBLE_COLUMN)
				|| state.blocksMotion();
	}
}
