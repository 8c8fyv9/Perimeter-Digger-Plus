package hackerrouter.perimeterdigger.client.state;

import hackerrouter.perimeterdigger.PerimeterDigger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

/** Server damage-event acquisition only. Native-navigation retaliation with weapon re-equip and unreachable abort. */
final class Retaliation {
    private static final int SWAP_SYNC_GRACE_TICKS = 6;
    private static final int PATH_REFRESH_INTERVAL_TICKS = 40;
    private static final int PATH_MOVE_REFRESH_MIN_TICKS = 15;

    private LivingEntity target;
    private int stalledTicks;
    private int ticksSinceHit;
    private int ticksSincePursue;
    private int pathFailures;
    private boolean pursueIssued;
    private BlockPos lastPursueTarget;
    private double bestDistanceSquared = Double.MAX_VALUE;
    private LocalPlayer owner;
    private hackerrouter.perimeterdigger.client.navigation.NavigationService navigation;
    private int selected = -1;
    private int swap = -1;
    private int swapGraceTicks;
    private boolean suspended;

    void damage(ClientboundDamageEventPacket packet, boolean active) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        var cause = packet.getSource(mc.level).getEntity();
        if (RetaliationPolicy.acquire(active, packet.entityId() == mc.player.getId(), cause instanceof Enemy)
                && cause instanceof LivingEntity living && living.isAlive()) {
            if (target != null) {
                if (target == living) return;
                boolean currentInRange = isWithinReach(mc.player, target) && mc.player.hasLineOfSight(target);
                if (!RetaliationPolicy.shouldSwitchTarget(currentInRange, mc.player.distanceToSqr(target), mc.player.distanceToSqr(living))) {
                    return;
                }
                PerimeterDigger.LOGGER.info("Perimeter retaliation switching to closer attacker {} at {}",
                        living.getType().toShortString(), living.blockPosition().toShortString());
                target = living;
                resetPursuitProgress();
                return;
            }
            PerimeterDigger.LOGGER.info("Perimeter retaliation acquired attacker {} at {}",
                    living.getType().toShortString(), living.blockPosition().toShortString());
            target = living;
            owner = mc.player;
            resetPursuitProgress();
        }
    }

    boolean tick(Runnable suspend, Runnable resume, hackerrouter.perimeterdigger.client.navigation.NavigationService navigation) {
        var mc = Minecraft.getInstance();
        this.navigation = navigation;
        var player = mc.player;
        if (target == null) return false;
        if (player == null || mc.level == null || target.level() != mc.level || !target.isAlive() || target.isRemoved() || player != owner) {
            finish(resume, "target_gone");
            return false;
        }
        boolean inRange = isWithinReach(player, target);
        boolean hasSight = player.hasLineOfSight(target);
        boolean canHitNow = inRange && hasSight;
        ticksSinceHit++;
        int stalled = trackDistance(player);
        if (RetaliationPolicy.shouldAbortCombat(stalled, pathFailures, ticksSinceHit, canHitNow)) {
            finish(resume, "unreachable(stalled=" + stalled + ",pathFails=" + pathFailures + ")");
            return false;
        }
        if (!suspended) {
            suspend.run();
            suspended = true;
        }
        // Always close any open container or interaction screen (including late server OpenScreen packets,
        // villager trade screens, chests/shulkers, survival inventory screen, or F9 config screens)
        // so combat movement, hotbar weapon equipping, and attacks never deadlock behind a GUI.
        if (!(player.containerMenu instanceof InventoryMenu)
                || !player.containerMenu.getCarried().isEmpty()
                || (mc.gui != null && mc.gui.screen() != null)) {
            player.closeContainer();
            if (mc.gui != null && mc.gui.screen() != null) {
                mc.gui.setScreen(null);
            }
            if (!(player.containerMenu instanceof InventoryMenu) || !player.containerMenu.getCarried().isEmpty()) {
                return true;
            }
        }

        // Ensure weapon is equipped on entry, whenever in melee range, or whenever not mid-pathing
        // (Baritone pathing may temporarily select a pickaxe/throwaway block to traverse obstacles).
        if (selected < 0 || canHitNow || !navigation.isWalking()) {
            WeaponState weapon = ensureWeaponEquipped(mc, player);
            if (weapon == WeaponState.NONE) {
                finish(resume, "no_weapon");
                return false;
            }
            if (weapon == WeaponState.EQUIPPING) {
                return true;
            }
        }

        if (!canHitNow) {
            ticksSincePursue++;
            BlockPos targetPos = target.blockPosition();
            boolean walking = navigation.isWalking();
            if (pursueIssued && !walking && ticksSincePursue >= 10) {
                pathFailures++;
                pursueIssued = false;
                if (RetaliationPolicy.shouldAbortCombat(stalled, pathFailures, ticksSinceHit, false)) {
                    finish(resume, "path_failed(" + pathFailures + ")");
                    return false;
                }
            }
            boolean targetMoved = lastPursueTarget == null || targetPos.distSqr(lastPursueTarget) >= 4.0;
            boolean shouldPursue = !walking
                    || (targetMoved && ticksSincePursue >= PATH_MOVE_REFRESH_MIN_TICKS)
                    || ticksSincePursue >= PATH_REFRESH_INTERVAL_TICKS;
            if (shouldPursue) {
                navigation.pursue(targetPos);
                lastPursueTarget = targetPos.immutable();
                ticksSincePursue = 0;
                pursueIssued = true;
            }
        } else {
            navigation.stopWalking();
            pursueIssued = false;
            pathFailures = 0;
            lastPursueTarget = null;
        }

        if (RetaliationPolicy.attack(target.isAlive(), isWeapon(player.getMainHandItem()),
                isWithinReach(player, target), hasSight, player.getAttackStrengthScale(0.0F))) {
            player.lookAt(Anchor.EYES, target.getEyePosition());
            if (mc.getConnection() != null) {
                mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                        player.getYRot(), player.getXRot(), player.onGround(), player.horizontalCollision));
            }
            mc.gameMode.attack(player, target);
            player.swing(InteractionHand.MAIN_HAND);
            stalledTicks = 0;
            ticksSinceHit = 0;
            bestDistanceSquared = player.distanceToSqr(target);
        }
        return true;
    }

    private enum WeaponState {
        READY,
        EQUIPPING,
        NONE
    }

    private WeaponState ensureWeaponEquipped(Minecraft mc, LocalPlayer player) {
        Inventory inventory = player.getInventory();
        if (selected < 0) {
            selected = inventory.getSelectedSlot();
        }
        if (isWeapon(player.getMainHandItem())) {
            swapGraceTicks = 0;
            return WeaponState.READY;
        }
        if (swapGraceTicks > 0) {
            swapGraceTicks--;
            return WeaponState.EQUIPPING;
        }
        int weaponSlot = findWeaponSlot(inventory);
        if (weaponSlot < 0) {
            return WeaponState.NONE;
        }
        if (weaponSlot < 9) {
            inventory.setSelectedSlot(weaponSlot);
            player.resetAttackStrengthTicker();
            return WeaponState.EQUIPPING;
        }
        if (mc.gameMode == null) {
            return WeaponState.NONE;
        }
        int targetHotbar = selected >= 0 && selected < 9 ? selected : inventory.getSelectedSlot();
        inventory.setSelectedSlot(targetHotbar);
        swap = weaponSlot;
        swapGraceTicks = SWAP_SYNC_GRACE_TICKS;
        mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, weaponSlot, targetHotbar, ContainerInput.SWAP, player);
        player.resetAttackStrengthTicker();
        return WeaponState.EQUIPPING;
    }

    private static int findWeaponSlot(Inventory inventory) {
        for (int i = 0; i < 9; i++) {
            if (inventory.getItem(i).is(ItemTags.SWORDS)) return i;
        }
        for (int i = 9; i < 36; i++) {
            if (inventory.getItem(i).is(ItemTags.SWORDS)) return i;
        }
        for (int i = 0; i < 9; i++) {
            if (inventory.getItem(i).is(ItemTags.AXES)) return i;
        }
        for (int i = 9; i < 36; i++) {
            if (inventory.getItem(i).is(ItemTags.AXES)) return i;
        }
        return -1;
    }

    private static boolean isWeapon(ItemStack stack) {
        return stack != null && !stack.isEmpty() && (stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES));
    }

    private static boolean isWithinReach(LocalPlayer player, LivingEntity target) {
        return player.isWithinAttackRange(player.getMainHandItem(), target.getBoundingBox(), 0.0);
    }

    private void finish(Runnable resume, String reason) {
        PerimeterDigger.LOGGER.info("Perimeter retaliation ended ({})", reason);
        boolean wasSuspended = suspended;
        cancel();
        if (wasSuspended) resume.run();
    }

    private void resetPursuitProgress() {
        stalledTicks = 0;
        ticksSinceHit = 0;
        ticksSincePursue = PATH_REFRESH_INTERVAL_TICKS;
        pathFailures = 0;
        pursueIssued = false;
        lastPursueTarget = null;
        bestDistanceSquared = Double.MAX_VALUE;
    }

    void cancel() {
        var mc = Minecraft.getInstance();
        if (navigation != null && suspended) navigation.stopWalking();
        if (mc.player == owner && mc.gameMode != null && mc.player != null && mc.player.isAlive()) {
            var player = mc.player;
            if (swap >= 0 && player.containerMenu instanceof InventoryMenu && player.containerMenu.getCarried().isEmpty())
                mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, swap, selected, ContainerInput.SWAP, player);
            if (selected >= 0) player.getInventory().setSelectedSlot(selected);
        }
        target = null;
        stalledTicks = 0;
        ticksSinceHit = 0;
        ticksSincePursue = 0;
        pathFailures = 0;
        pursueIssued = false;
        lastPursueTarget = null;
        bestDistanceSquared = Double.MAX_VALUE;
        selected = swap = -1;
        swapGraceTicks = 0;
        suspended = false;
        owner = null;
        navigation = null;
    }

    /** Counts ticks without meaningfully closing distance, so an unreachable or kiting attacker cannot trap mining. */
    private int trackDistance(LocalPlayer player) {
        double distanceSquared = player.distanceToSqr(target);
        if (distanceSquared < bestDistanceSquared - 1.0) {
            bestDistanceSquared = distanceSquared;
            stalledTicks = 0;
        } else stalledTicks++;
        return stalledTicks;
    }
}