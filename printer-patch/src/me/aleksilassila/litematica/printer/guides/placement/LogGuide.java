package me.aleksilassila.litematica.printer.guides.placement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.aleksilassila.litematica.printer.SchematicBlockState;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.guides.interaction.LogStrippingGuide;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;

public class LogGuide extends GeneralPlacementGuide {
	public LogGuide(SchematicBlockState state) {
		super(state);
	}

	@Override
	public boolean skipOtherGuides() {
		return this.targetState.hasProperty(RotatedPillarBlock.AXIS);
	}

	@Override
	protected List<Direction> getPossibleSides() {
		if (this.targetState.hasProperty(RotatedPillarBlock.AXIS)) {
			Direction.Axis axis = this.targetState.getValue(RotatedPillarBlock.AXIS);
			return Arrays.stream(Direction.values())
					.filter(dir -> dir.getAxis() == axis)
					.toList();
		}
		return new ArrayList<>();
	}

	@Override
	protected List<ItemStack> getRequiredItems() {
		List<ItemStack> items = new ArrayList<>();
		// Always prefer the exact target block item first (e.g. stripped_oak_log if already in inventory!).
		items.add(this.getBlockItem(this.state.targetState));
		if (Configs.STRIP_LOGS.getBooleanValue()) {
			for (Block unstripped : LogStrippingGuide.STRIPPED_BLOCKS.keySet()) {
				if (this.targetState.getBlock() == LogStrippingGuide.STRIPPED_BLOCKS.get(unstripped)) {
					items.add(new ItemStack(unstripped));
					break;
				}
			}
		}
		return items;
	}

	@Override
	public boolean canExecute(LocalPlayer player) {
		if (!this.targetState.hasProperty(RotatedPillarBlock.AXIS)) {
			return false;
		}
		// Never overwrite an already-placed unstripped log that is waiting for LogStrippingGuide to strip it.
		if (Configs.STRIP_LOGS.getBooleanValue()) {
			Block strippedOfCurrent = LogStrippingGuide.STRIPPED_BLOCKS.get(this.currentState.getBlock());
			if (strippedOfCurrent == this.targetState.getBlock()
					&& this.currentState.hasProperty(RotatedPillarBlock.AXIS)
					&& this.currentState.getValue(RotatedPillarBlock.AXIS) == this.targetState.getValue(RotatedPillarBlock.AXIS)) {
				return false;
			}
		}
		return super.canExecute(player);
	}
}
