package me.aleksilassila.litematica.printer.implementation;

import me.aleksilassila.litematica.printer.BlockHelper;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.FenceBlock;

public class BlockHelperImpl extends BlockHelper {
	static {
		interactiveBlocks.add(ButtonBlock.class);
		if (!interactiveBlocks.contains(FenceBlock.class)) {
			interactiveBlocks.add(FenceBlock.class);
		}
	}
}
