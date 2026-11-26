package me.aleksilassila.litematica.printer.implementation;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;

public class PrinterPlacementContext extends BlockPlaceContext {
	public final Direction lookDirection;
	public final boolean shouldSneak;
	public final BlockHitResult hitResult;
	public final int requiredItemSlot;

	public PrinterPlacementContext(Player player, BlockHitResult hitResult, ItemStack itemStack, int requiredItemSlot) {
		this(player, hitResult, itemStack, requiredItemSlot, null, false);
	}

	public PrinterPlacementContext(
			Player player,
			BlockHitResult hitResult,
			ItemStack itemStack,
			int requiredItemSlot,
			Direction lookDirection,
			boolean shouldSneak
	) {
		super(player, InteractionHand.MAIN_HAND, itemStack, hitResult);
		this.lookDirection = lookDirection;
		this.shouldSneak = shouldSneak;
		this.hitResult = hitResult;
		this.requiredItemSlot = requiredItemSlot;
	}

	@Override
	public Direction getNearestLookingDirection() {
		return this.lookDirection == null ? super.getNearestLookingDirection() : this.lookDirection;
	}

	@Override
	public Direction getNearestLookingVerticalDirection() {
		if (this.lookDirection != null && this.lookDirection.getAxis().isVertical()) {
			return this.lookDirection;
		}
		return super.getNearestLookingVerticalDirection();
	}

	@Override
	public Direction getHorizontalDirection() {
		if (this.lookDirection != null && this.lookDirection.getAxis().isHorizontal()) {
			return this.lookDirection;
		}
		return super.getHorizontalDirection();
	}

	@Override
	public Direction[] getNearestLookingDirections() {
		Direction[] base = super.getNearestLookingDirections();
		if (this.lookDirection == null) {
			return base;
		}
		List<Direction> ordered = new ArrayList<>(6);
		ordered.add(this.lookDirection);
		if (!this.replaceClicked) {
			Direction faceOpposite = this.getClickedFace().getOpposite();
			if (!ordered.contains(faceOpposite)) {
				ordered.add(faceOpposite);
			}
		}
		for (Direction dir : base) {
			if (dir != this.lookDirection.getOpposite() && !ordered.contains(dir)) {
				ordered.add(dir);
			}
		}
		if (!ordered.contains(this.lookDirection.getOpposite())) {
			ordered.add(this.lookDirection.getOpposite());
		}
		return ordered.toArray(new Direction[0]);
	}

	@Override
	public float getRotation() {
		if (this.lookDirection != null && this.lookDirection.getAxis().isHorizontal()) {
			return this.lookDirection.toYRot();
		}
		return super.getRotation();
	}

	@Override
	public boolean isSecondaryUseActive() {
		return this.shouldSneak;
	}

	@Override
	public String toString() {
		return "PrinterPlacementContext{lookDirection=" + String.valueOf(this.lookDirection)
				+ ", shouldSneak=" + this.shouldSneak
				+ ", hitPos=" + String.valueOf(this.hitResult.getBlockPos())
				+ ", side=" + String.valueOf(this.hitResult.getDirection()) + "}";
	}
}
