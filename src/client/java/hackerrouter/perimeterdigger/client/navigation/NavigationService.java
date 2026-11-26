/*
 * This file is part of the Perimeter Digger project, licensed under the
 * GNU General Public License v3.0 or later.
 *
 * Copyright (C) 2026  HackerRouter
 *
 * Perimeter Digger is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Perimeter Digger is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Perimeter Digger. If not, see <https://www.gnu.org/licenses/>.
 */

package hackerrouter.perimeterdigger.client.navigation;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.IElytraProcess;
import hackerrouter.perimeterdigger.client.flight.CreativeFlightNavigator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class NavigationService {
	private final CreativeFlightNavigator creativeFlight = new CreativeFlightNavigator();
	private IBaritone baritone;
	private ICustomGoalProcess walking;
	private IElytraProcess flight;
	private boolean flightMovementEnabled = true;
	private Boolean savedAllowBreak;
	private Goal lastGoal;
	private BlockPos lastFlight;
	private Runnable suspended;

	public void bind(IBaritone baritone) {
		if (walking != null || flight != null || creativeFlight.isActive()) {
			stop();
			restoreDestinationSettings();
		}
		this.baritone = baritone;
		walking = baritone.getCustomGoalProcess();
		flight = baritone.getElytraProcess();
	}

	public void unbind() {
		stop();
		restoreDestinationSettings();
		baritone = null;
		walking = null;
		flight = null;
	}

	public void setFlightMovementEnabled(boolean enabled) {
		this.flightMovementEnabled = enabled;
		if (!enabled && creativeFlight.isActive()) {
			creativeFlight.stop(baritone);
		}
	}

	public boolean canUseCreativeFlight() {
		return flightMovementEnabled && CreativeFlightNavigator.hasFlyPermission();
	}

	public boolean isCreativeFlying() {
		return creativeFlight.isActive();
	}

	public void tickCreativeFlight() {
		if (!creativeFlight.isActive()) return;
		if (!canUseCreativeFlight()) {
			creativeFlight.stop(baritone);
			return;
		}
		creativeFlight.tick(baritone);
	}

	public void walk(Goal goal) {
		lastGoal = goal;
		if (canUseCreativeFlight()) {
			BlockPos target = extractTargetPos(goal);
			if (target != null) {
				if (walking != null) walking.onLostControl();
				if (flight != null && flight.isActive()) flight.onLostControl();
				boolean exactBlock = goal instanceof GoalBlock || hasExactBlockChild(goal);
				if (exactBlock) {
					if (!creativeFlight.isActive() || !target.equals(creativeFlight.exactBlockTarget())) {
						creativeFlight.flyToBlock(target);
					}
				} else {
					Vec3 desired = new Vec3(target.getX() + 0.5, target.getY() + 0.2, target.getZ() + 0.5);
					if (!creativeFlight.isActive() || creativeFlight.target() == null
							|| creativeFlight.target().distanceToSqr(desired) > 0.75 * 0.75) {
						creativeFlight.flyTo(desired, 0.75, 0.75, true);
					}
				}
				return;
			}
		}
		creativeFlight.stop(baritone);
		requireWalking().setGoalAndPath(goal);
	}

	/** Temporary combat goal must not overwrite the suspended task destination. */
	public void pursue(BlockPos target) {
		if (canUseCreativeFlight()) {
			if (walking != null) walking.onLostControl();
			creativeFlight.updateTarget(new Vec3(target.getX() + 0.5, target.getY() + 0.3, target.getZ() + 0.5), 1.2, 1.0, true);
			return;
		}
		creativeFlight.stop(baritone);
		requireWalking().setGoalAndPath(new GoalNear(target, 1));
	}

	public void fly(BlockPos target) {
		lastFlight = target;
		if (canUseCreativeFlight()) {
			lastGoal = new GoalBlock(target);
			if (walking != null) walking.onLostControl();
			if (flight != null && flight.isActive()) flight.onLostControl();
			creativeFlight.flyTo(target);
			return;
		}
		creativeFlight.stop(baritone);
		requireFlight().pathTo(target);
	}

	public boolean isWalking() {
		return creativeFlight.isActive() || (walking != null && walking.isActive());
	}

	public boolean isFlying() {
		return flight != null && flight.isActive();
	}

	public boolean isFlightLoaded() {
		return flight != null && flight.isLoaded();
	}

	public void stopWalking() {
		creativeFlight.stop(baritone);
		if (walking != null) walking.onLostControl();
	}

	public void stopFlying() {
		if (flight != null && flight.isActive()) flight.onLostControl();
	}

	private static boolean hasExactBlockChild(Goal goal) {
		if (goal instanceof GoalComposite gc) {
			for (Goal child : gc.goals()) {
				if (child instanceof GoalBlock) return true;
			}
		}
		return false;
	}

	private static BlockPos extractTargetPos(Goal goal) {
		if (goal instanceof GoalBlock gb) return gb.getGoalPos();
		if (goal instanceof GoalNear gn) return gn.getGoalPos();
		if (goal instanceof GoalComposite gc) {
			var player = Minecraft.getInstance().player;
			Vec3 origin = player == null ? Vec3.ZERO : player.position();
			BlockPos best = null;
			double bestDistSq = Double.MAX_VALUE;
			for (Goal child : gc.goals()) {
				BlockPos pos = extractTargetPos(child);
				if (pos == null) continue;
				double d = origin.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
				if (d < bestDistSq) {
					bestDistSq = d;
					best = pos;
				}
			}
			return best;
		}
		return null;
	}

	public void suspend() {
		if (suspended != null) return;
		Goal goal = lastGoal;
		BlockPos target = lastFlight;
		Runnable restore = isFlying() && target != null ? () -> fly(target)
			: isWalking() && goal != null ? () -> walk(goal) : null;
		stop();
		suspended = restore;
	}

	public void resumeSuspended() {
		Runnable restore = suspended;
		suspended = null;
		stopWalking();
		if (restore != null) restore.run();
	}

	public void stop() {
		suspended = null;
		stopWalking();
		stopFlying();
	}

	/** Navigation permits placement (bridging with throwaway materials); kept as no-op for call-site stability. */
	public void disablePlacement() {
	}

	public void disableBreakingAndPlacement() {
		if (savedAllowBreak == null) savedAllowBreak = BaritoneAPI.getSettings().allowBreak.value;
		BaritoneAPI.getSettings().allowBreak.value = false;
	}

	public void enableBreakingWithoutPlacement() {
		if (savedAllowBreak == null) savedAllowBreak = BaritoneAPI.getSettings().allowBreak.value;
		BaritoneAPI.getSettings().allowBreak.value = true;
	}

	public void restoreDestinationSettings() {
		if (savedAllowBreak != null) BaritoneAPI.getSettings().allowBreak.value = savedAllowBreak;
		savedAllowBreak = null;
	}

	private ICustomGoalProcess requireWalking() {
		if (walking == null) throw new IllegalStateException("Navigation service is not bound to Baritone.");
		return walking;
	}

	private IElytraProcess requireFlight() {
		if (flight == null) throw new IllegalStateException("Navigation service is not bound to Baritone.");
		return flight;
	}
}
