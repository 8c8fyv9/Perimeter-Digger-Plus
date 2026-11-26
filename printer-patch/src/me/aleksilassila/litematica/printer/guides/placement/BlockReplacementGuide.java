package me.aleksilassila.litematica.printer.guides.placement;

import java.util.HashMap;
import java.util.Optional;
import me.aleksilassila.litematica.printer.SchematicBlockState;
import me.aleksilassila.litematica.printer.guides.Guide;
import me.aleksilassila.litematica.printer.implementation.PrinterPlacementContext;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class BlockReplacementGuide extends PlacementGuide {
	private static final HashMap<IntegerProperty, Item> increasingProperties = new HashMap<>();
	private Integer currentLevel = null;
	private Integer targetLevel = null;
	private IntegerProperty increasingProperty = null;

	public BlockReplacementGuide(SchematicBlockState state) {
		super(state);
		for (IntegerProperty prop : increasingProperties.keySet()) {
			if (this.targetState.hasProperty(prop) && this.currentState.hasProperty(prop)) {
				this.currentLevel = this.currentState.getValue(prop);
				this.targetLevel = this.targetState.getValue(prop);
				this.increasingProperty = prop;
				break;
			}
		}
	}

	@Override
	protected boolean getUseShift(SchematicBlockState state) {
		return false;
	}

	@Override
	public PrinterPlacementContext getPlacementContext(LocalPlayer player) {
		Optional<ItemStack> requiredItem = this.getRequiredItem(player);
		int slot = this.getRequiredItemStackSlot(player);
		if (requiredItem.isEmpty() || slot == -1) {
			return null;
		}
		Direction clickedFace = Direction.UP;
		Vec3 hitVec = Vec3.atCenterOf(this.state.blockPos);
		if (this.currentState.hasProperty(SlabBlock.TYPE)) {
			SlabType currentSlabType = this.currentState.getValue(SlabBlock.TYPE);
			if (currentSlabType == SlabType.TOP) {
				// Completing a TOP slab into a DOUBLE slab requires clicking Direction.DOWN (or lower half).
				clickedFace = Direction.DOWN;
				hitVec = hitVec.add(0.0, -0.25, 0.0);
			} else if (currentSlabType == SlabType.BOTTOM) {
				clickedFace = Direction.UP;
				hitVec = hitVec.add(0.0, 0.25, 0.0);
			}
		}
		BlockHitResult hitResult = new BlockHitResult(
				hitVec,
				clickedFace,
				this.state.blockPos,
				false
		);
		return new PrinterPlacementContext(player, hitResult, requiredItem.get(), slot);
	}

	@Override
	public boolean canExecute(LocalPlayer player) {
		if (Guide.getProperty(this.targetState, SlabBlock.TYPE).orElse(null) == SlabType.DOUBLE
				&& this.currentState.getBlock() == this.targetState.getBlock()
				&& Guide.getProperty(this.currentState, SlabBlock.TYPE).orElse(SlabType.DOUBLE) != SlabType.DOUBLE) {
			return super.canExecute(player);
		}
		if (this.currentLevel == null || this.targetLevel == null || this.increasingProperty == null) {
			return false;
		}
		if (!this.statesEqualIgnoreProperties(this.currentState, this.targetState, CandleBlock.LIT, this.increasingProperty)) {
			return false;
		}
		if (this.currentLevel >= this.targetLevel) {
			return false;
		}
		return super.canExecute(player);
	}

	static {
		increasingProperties.put(SnowLayerBlock.LAYERS, null);
		increasingProperties.put(SeaPickleBlock.PICKLES, null);
		increasingProperties.put(CandleBlock.CANDLES, null);
	}
}
