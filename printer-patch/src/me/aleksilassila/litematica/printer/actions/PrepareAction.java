package me.aleksilassila.litematica.printer.actions;

import fi.dy.masa.litematica.util.InventoryUtils;
import me.aleksilassila.litematica.printer.Printer;
import me.aleksilassila.litematica.printer.implementation.PrinterPlacementContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShovelItem;

public class PrepareAction extends Action {
	public final PrinterPlacementContext context;
	public boolean modifyYaw = true;
	public boolean modifyPitch = true;
	public float yaw = 0.0F;
	public float pitch = 0.0F;

	public PrepareAction(PrinterPlacementContext context) {
		this.context = context;
		Direction lookDirection = context.lookDirection;
		if (lookDirection != null && lookDirection.getAxis().isHorizontal()) {
			this.yaw = lookDirection.toYRot();
		} else {
			this.modifyYaw = false;
		}
		if (lookDirection == Direction.UP) {
			this.pitch = -90.0F;
		} else if (lookDirection == Direction.DOWN) {
			this.pitch = 90.0F;
		} else if (lookDirection != null) {
			this.pitch = 0.0F;
		} else {
			this.modifyPitch = false;
		}
	}

	public PrepareAction(PrinterPlacementContext context, float yaw, float pitch) {
		this.context = context;
		this.yaw = yaw;
		this.pitch = pitch;
	}

	@Override
	public void send(Minecraft client, LocalPlayer player) {
		ItemStack itemStack = this.context.getItemInHand();
		int slot = this.context.requiredItemSlot;
		if (client.gameMode != null) {
			Inventory inventory = player.getInventory();
			if (itemStack != null && !itemStack.isEmpty()) {
				Printer.printDebug("PrepareAction#send(): slot [{}] // itemStack [{}]", slot, itemStack.toString());
				if (player.getAbilities().instabuild) {
					this.addPickBlock(inventory, itemStack);
					client.gameMode.handleCreativeModeItemAdd(
							player.getItemInHand(InteractionHand.MAIN_HAND),
							36 + inventory.getSelectedSlot());
				} else {
					if (slot < 0 || slot >= inventory.getNonEquipmentItems().size()
							|| !ItemStack.isSameItem(inventory.getNonEquipmentItems().get(slot), itemStack)) {
						slot = inventory.findSlotMatchingItem(itemStack);
					}
					if (slot != -1) {
						if (Inventory.isHotbarSlot(slot)) {
							inventory.setSelectedSlot(slot);
						} else {
							InventoryUtils.setPickedItemToHand(slot, itemStack, client);
						}
					}
				}
			} else {
				// Empty hand requested (e.g. CycleStateGuide): ensure we do not hold an axe, shovel, hoe, bucket, or fire tool.
				if (isDangerousToolOrBucket(player.getMainHandItem())) {
					int safeHotbar = findSafeInteractionHotbarSlot(inventory);
					if (safeHotbar != -1) {
						inventory.setSelectedSlot(safeHotbar);
					}
				}
			}
		}

		if (this.modifyPitch || this.modifyYaw) {
			float targetYaw = this.modifyYaw ? this.yaw : player.getYRot();
			float targetPitch = this.modifyPitch ? this.pitch : player.getXRot();
			ServerboundMovePlayerPacket.PosRot packet = new ServerboundMovePlayerPacket.PosRot(
					player.getX(),
					player.getY(),
					player.getZ(),
					targetYaw,
					targetPitch,
					player.onGround(),
					player.horizontalCollision
			);
			player.connection.send(packet);
		}

		Input current = player.input.keyPresses;
		player.input.keyPresses = new Input(
				current.forward(),
				current.backward(),
				current.left(),
				current.right(),
				current.jump(),
				this.context.shouldSneak,
				current.sprint()
		);
		player.connection.send(new ServerboundPlayerInputPacket(player.input.keyPresses));
	}

	private static boolean isDangerousToolOrBucket(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		Item item = stack.getItem();
		return item instanceof AxeItem
				|| item instanceof ShovelItem
				|| item instanceof HoeItem
				|| item instanceof BucketItem
				|| item instanceof FlintAndSteelItem
				|| item instanceof FireChargeItem;
	}

	private static int findSafeInteractionHotbarSlot(Inventory inventory) {
		int fallbackNonDangerous = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack s = inventory.getItem(i);
			if (s.isEmpty()) {
				return i;
			}
			if (!isDangerousToolOrBucket(s)) {
				if (!(s.getItem() instanceof BlockItem)) {
					return i;
				}
				if (fallbackNonDangerous == -1) {
					fallbackNonDangerous = i;
				}
			}
		}
		return fallbackNonDangerous;
	}

	private void addPickBlock(Inventory inventory, ItemStack itemToPick) {
		int slot = inventory.findSlotMatchingItem(itemToPick);
		if (Inventory.isHotbarSlot(slot)) {
			inventory.setSelectedSlot(slot);
		} else if (slot == -1) {
			inventory.setSelectedSlot(inventory.getSuitableHotbarSlot());
			if (!inventory.getNonEquipmentItems().get(inventory.getSelectedSlot()).isEmpty()) {
				int freeSlot = inventory.getFreeSlot();
				if (freeSlot != -1) {
					inventory.getNonEquipmentItems().set(freeSlot, inventory.getNonEquipmentItems().get(inventory.getSelectedSlot()));
				}
			}
			inventory.getNonEquipmentItems().set(inventory.getSelectedSlot(), itemToPick);
		} else {
			inventory.pickSlot(slot);
		}
	}

	@Override
	public String toString() {
		return "PrepareAction{context=" + String.valueOf(this.context) + "}";
	}
}
