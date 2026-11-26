package me.aleksilassila.litematica.printer.guides.interaction;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import me.aleksilassila.litematica.printer.SchematicBlockState;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.mixin.AxeItemAccessor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;

public class LogStrippingGuide extends InteractionGuide {
	static final Item[] AXE_ITEMS = new Item[]{
			Items.NETHERITE_AXE,
			Items.DIAMOND_AXE,
			Items.GOLDEN_AXE,
			Items.IRON_AXE,
			Items.STONE_AXE,
			Items.WOODEN_AXE
	};
	public static final Map<Block, Block> STRIPPED_BLOCKS = AxeItemAccessor.getStrippedBlocks();

	public LogStrippingGuide(SchematicBlockState state) {
		super(state);
	}

	@Override
	public boolean canExecute(LocalPlayer player) {
		if (!Configs.STRIP_LOGS.getBooleanValue()) {
			return false;
		}
		if (!super.canExecute(player)) {
			return false;
		}
		Block stripped = STRIPPED_BLOCKS.get(this.currentState.getBlock());
		if (stripped != this.targetState.getBlock()) {
			return false;
		}
		if (this.currentState.hasProperty(RotatedPillarBlock.AXIS) && this.targetState.hasProperty(RotatedPillarBlock.AXIS)) {
			if (this.currentState.getValue(RotatedPillarBlock.AXIS) != this.targetState.getValue(RotatedPillarBlock.AXIS)) {
				return false;
			}
		}
		return true;
	}

	@Override
	protected List<ItemStack> getRequiredItems() {
		return Arrays.stream(AXE_ITEMS).map(ItemStack::new).toList();
	}
}
