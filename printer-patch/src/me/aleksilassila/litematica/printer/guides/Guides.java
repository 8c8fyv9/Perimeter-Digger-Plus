package me.aleksilassila.litematica.printer.guides;

import java.util.ArrayList;
import me.aleksilassila.litematica.printer.SchematicBlockState;
import me.aleksilassila.litematica.printer.guides.interaction.CampfireExtinguishGuide;
import me.aleksilassila.litematica.printer.guides.interaction.CycleStateGuide;
import me.aleksilassila.litematica.printer.guides.interaction.EnderEyeGuide;
import me.aleksilassila.litematica.printer.guides.interaction.FlowerPotFillGuide;
import me.aleksilassila.litematica.printer.guides.interaction.LightCandleGuide;
import me.aleksilassila.litematica.printer.guides.interaction.LogStrippingGuide;
import me.aleksilassila.litematica.printer.guides.interaction.TillingGuide;
import me.aleksilassila.litematica.printer.guides.placement.BlockIndifferentGuesserGuide;
import me.aleksilassila.litematica.printer.guides.placement.BlockReplacementGuide;
import me.aleksilassila.litematica.printer.guides.placement.ChestGuide;
import me.aleksilassila.litematica.printer.guides.placement.FallingBlockGuide;
import me.aleksilassila.litematica.printer.guides.placement.FarmlandGuide;
import me.aleksilassila.litematica.printer.guides.placement.FlowerPotGuide;
import me.aleksilassila.litematica.printer.guides.placement.GuesserGuide;
import me.aleksilassila.litematica.printer.guides.placement.LogGuide;
import me.aleksilassila.litematica.printer.guides.placement.PropertySpecificGuesserGuide;
import me.aleksilassila.litematica.printer.guides.placement.RailGuesserGuide;
import me.aleksilassila.litematica.printer.guides.placement.RotatingBlockGuide;
import me.aleksilassila.litematica.printer.guides.placement.SlabGuide;
import me.aleksilassila.litematica.printer.guides.placement.TorchGuide;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.AbstractCandleBlock;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.BigDripleafStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.TwistingVinesPlantBlock;
import net.minecraft.world.level.block.WallBlock;
import org.apache.commons.lang3.tuple.Pair;

public class Guides {
	protected static final ArrayList<Pair<Class<? extends Guide>, Class<? extends Block>[]>> guides = new ArrayList<>();

	@SafeVarargs
	protected static void registerGuide(Class<? extends Guide> guideClass, Class<? extends Block>... blockClasses) {
		guides.add(Pair.of(guideClass, blockClasses));
	}

	public ArrayList<Pair<Class<? extends Guide>, Class<? extends Block>[]>> getGuides() {
		return guides;
	}

	public Guide[] getInteractionGuides(SchematicBlockState state) {
		ArrayList<Pair<Class<? extends Guide>, Class<? extends Block>[]>> all = this.getGuides();
		ArrayList<Guide> matching = new ArrayList<>();
		for (Pair<Class<? extends Guide>, Class<? extends Block>[]> entry : all) {
			try {
				Class<? extends Block>[] targets = entry.getRight();
				if (targets.length == 0) {
					matching.add(entry.getLeft().getConstructor(SchematicBlockState.class).newInstance(state));
				} else {
					for (Class<? extends Block> blockClass : targets) {
						if (blockClass.isInstance(state.targetState.getBlock())) {
							matching.add(entry.getLeft().getConstructor(SchematicBlockState.class).newInstance(state));
						}
					}
				}
			} catch (Exception ignored) {
			}
		}
		return matching.toArray(Guide[]::new);
	}

	static {
		registerGuide(RotatingBlockGuide.class, AbstractSkullBlock.class, SignBlock.class, AbstractBannerBlock.class);
		registerGuide(SlabGuide.class, SlabBlock.class);
		// Register TorchGuide for BaseTorchBlock so both regular torches (TorchBlock/WallTorchBlock)
		// and redstone torches (RedstoneTorchBlock/RedstoneWallTorchBlock) use exact support face & look sync.
		registerGuide(TorchGuide.class, BaseTorchBlock.class);
		registerGuide(FarmlandGuide.class, FarmlandBlock.class);
		registerGuide(TillingGuide.class, FarmlandBlock.class);
		registerGuide(RailGuesserGuide.class, BaseRailBlock.class);
		registerGuide(ChestGuide.class, ChestBlock.class);
		registerGuide(FlowerPotGuide.class, FlowerPotBlock.class);
		registerGuide(FlowerPotFillGuide.class, FlowerPotBlock.class);
		registerGuide(
				PropertySpecificGuesserGuide.class,
				RepeaterBlock.class,
				ComparatorBlock.class,
				RedStoneWireBlock.class,
				BambooStalkBlock.class,
				CactusBlock.class,
				SaplingBlock.class,
				ScaffoldingBlock.class,
				PointedDripstoneBlock.class,
				CrossCollisionBlock.class,
				WallBlock.class,
				StairBlock.class,
				DoorBlock.class,
				TrapDoorBlock.class,
				FenceGateBlock.class,
				ChestBlock.class,
				SnowLayerBlock.class,
				SeaPickleBlock.class,
				CandleBlock.class,
				LeverBlock.class,
				EndPortalFrameBlock.class,
				NoteBlock.class,
				CampfireBlock.class,
				PoweredRailBlock.class,
				LeavesBlock.class,
				TripWireHookBlock.class
		);
		registerGuide(FallingBlockGuide.class, FallingBlock.class);
		registerGuide(
				BlockIndifferentGuesserGuide.class,
				BambooStalkBlock.class,
				BigDripleafStemBlock.class,
				BigDripleafBlock.class,
				TwistingVinesPlantBlock.class,
				TripWireBlock.class
		);
		registerGuide(CampfireExtinguishGuide.class, CampfireBlock.class);
		registerGuide(LightCandleGuide.class, AbstractCandleBlock.class);
		registerGuide(EnderEyeGuide.class, EndPortalFrameBlock.class);
		registerGuide(
				CycleStateGuide.class,
				DoorBlock.class,
				FenceGateBlock.class,
				TrapDoorBlock.class,
				LeverBlock.class,
				RepeaterBlock.class,
				ComparatorBlock.class,
				NoteBlock.class
		);
		registerGuide(
				BlockReplacementGuide.class,
				SnowLayerBlock.class,
				SeaPickleBlock.class,
				CandleBlock.class,
				SlabBlock.class
		);
		registerGuide(LogGuide.class);
		registerGuide(LogStrippingGuide.class);
		registerGuide(GuesserGuide.class);
	}
}
