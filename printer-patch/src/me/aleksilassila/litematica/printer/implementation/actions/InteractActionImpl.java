package me.aleksilassila.litematica.printer.implementation.actions;

import me.aleksilassila.litematica.printer.LitematicaMixinMod;
import me.aleksilassila.litematica.printer.actions.InteractAction;
import me.aleksilassila.litematica.printer.actions.PrepareAction;
import me.aleksilassila.litematica.printer.implementation.PrinterPlacementContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.phys.BlockHitResult;

public class InteractActionImpl extends InteractAction {
	public InteractActionImpl(PrinterPlacementContext context) {
		super(context);
	}

	@Override
	protected void interact(Minecraft mc, LocalPlayer player, InteractionHand hand, BlockHitResult hitResult) {
		if (mc.gameMode == null || player == null || hitResult == null) {
			return;
		}
		ItemStack required = this.context.getItemInHand();
		ItemStack held = player.getItemInHand(hand);
		if (required != null && !required.isEmpty()) {
			if (held.isEmpty() || !ItemStack.isSameItem(held, required)) {
				return;
			}
		} else {
			Item heldItem = held.getItem();
			if (heldItem instanceof AxeItem
					|| heldItem instanceof ShovelItem
					|| heldItem instanceof HoeItem
					|| heldItem instanceof BucketItem
					|| heldItem instanceof FlintAndSteelItem
					|| heldItem instanceof FireChargeItem) {
				return;
			}
		}

		PrepareAction look = (LitematicaMixinMod.printer != null && LitematicaMixinMod.printer.actionHandler != null)
				? LitematicaMixinMod.printer.actionHandler.lookAction
				: null;
		float prevYaw = player.getYRot();
		float prevPitch = player.getXRot();
		float prevHeadYaw = player.yHeadRot;
		boolean restoreRot = false;
		if (look != null && (look.modifyYaw || look.modifyPitch)) {
			float targetYaw = look.modifyYaw ? look.yaw : prevYaw;
			float targetPitch = look.modifyPitch ? look.pitch : prevPitch;
			player.setYRot(targetYaw);
			player.setXRot(targetPitch);
			player.yHeadRot = targetYaw;
			restoreRot = true;
		}
		try {
			mc.gameMode.useItemOn(player, hand, hitResult);
		} finally {
			if (restoreRot) {
				player.setYRot(prevYaw);
				player.setXRot(prevPitch);
				player.yHeadRot = prevHeadYaw;
			}
		}
	}
}
