package hackerrouter.perimeterdigger.client.flight;

import baritone.api.IBaritone;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Native 3D creative-flight (/fly) point-to-point navigator.
 * Drives movement through Baritone's InputOverrideHandler so manual keyboard control is cleanly
 * restored on stop/pause without competing with Baritone pathing.
 */
public final class CreativeFlightNavigator {
	private Vec3 target;
	private BlockPos exactBlockTarget;
	private double horizTol = 0.75;
	private double vertTol = 0.65;
	private boolean allowTouchGround;
	private int ticks;
	private int liftTicks;
	private int stallTicks;
	private double bestDist = Double.MAX_VALUE;
	private double cruiseBoostY;
	private int detourTicks;
	private int detourSign = 1;
	private boolean detourDescend;
	private boolean detourBack;

	public static boolean hasFlyPermission() {
		LocalPlayer player = Minecraft.getInstance().player;
		return player != null && player.getAbilities().mayfly && !player.isSpectator();
	}

	public boolean isActive() {
		return target != null;
	}

	public boolean isBlockedOrStalled() {
		return detourTicks > 0 || stallTicks >= 12;
	}

	public Vec3 target() {
		return target;
	}

	public BlockPos exactBlockTarget() {
		return exactBlockTarget;
	}

	public void flyTo(BlockPos blockTarget) {
		flyToBlock(blockTarget);
	}

	/** Flies tightly into the exact target BlockPos so exact blockPosition() checks succeed on arrival. */
	public void flyToBlock(BlockPos blockTarget) {
		flyTo(new Vec3(blockTarget.getX() + 0.5, blockTarget.getY() + 0.15, blockTarget.getZ() + 0.5), 0.32, 0.25, true);
		this.exactBlockTarget = blockTarget.immutable();
	}

	public void flyTo(Vec3 exactTarget, double horizTolerance, double vertTolerance, boolean allowTouchGround) {
		this.target = exactTarget;
		this.exactBlockTarget = null;
		this.horizTol = Math.max(0.20, horizTolerance);
		this.vertTol = Math.max(0.20, vertTolerance);
		this.allowTouchGround = allowTouchGround;
		this.ticks = 0;
		this.liftTicks = 0;
		this.stallTicks = 0;
		this.bestDist = Double.MAX_VALUE;
		this.cruiseBoostY = 0.0;
		this.detourTicks = 0;
		this.detourDescend = false;
		this.detourBack = false;
	}

	/** Updates a moving target (such as combat pursuit) without resetting obstacle-climb / detour state. */
	public void updateTarget(Vec3 exactTarget, double horizTolerance, double vertTolerance, boolean allowTouchGround) {
		if (this.target == null) {
			flyTo(exactTarget, horizTolerance, vertTolerance, allowTouchGround);
			return;
		}
		if (this.target.distanceToSqr(exactTarget) > 4.0) {
			this.bestDist = Double.MAX_VALUE;
		}
		this.target = exactTarget;
		this.exactBlockTarget = null;
		this.horizTol = Math.max(0.20, horizTolerance);
		this.vertTol = Math.max(0.20, vertTolerance);
		this.allowTouchGround = allowTouchGround;
	}

	public void stop(IBaritone baritone) {
		target = null;
		exactBlockTarget = null;
		ticks = 0;
		liftTicks = 0;
		stallTicks = 0;
		cruiseBoostY = 0.0;
		detourTicks = 0;
		detourDescend = false;
		detourBack = false;
		clearFlightInputs(baritone);
	}

	public static void clearFlightInputs(IBaritone baritone) {
		if (baritone == null) return;
		var handler = baritone.getInputOverrideHandler();
		handler.setInputForceState(Input.MOVE_FORWARD, false);
		handler.setInputForceState(Input.MOVE_BACK, false);
		handler.setInputForceState(Input.MOVE_LEFT, false);
		handler.setInputForceState(Input.MOVE_RIGHT, false);
		handler.setInputForceState(Input.SPRINT, false);
		handler.setInputForceState(Input.JUMP, false);
		handler.setInputForceState(Input.SNEAK, false);
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) player.setSprinting(false);
	}

	/** Ensures /fly is engaged when the player has permission. Returns false if permission is absent. */
	public static boolean ensureFlying(LocalPlayer player) {
		if (player == null || !player.getAbilities().mayfly || player.isSpectator()) return false;
		if (!player.getAbilities().flying) {
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
		}
		return true;
	}

	/**
	 * Advances one client tick of 3D flight.
	 * @return true while still flying toward target, false when arrived or aborted.
	 */
	public boolean tick(IBaritone baritone) {
		if (target == null || baritone == null) return false;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		Level level = mc.level;
		if (player == null || level == null || !ensureFlying(player)) {
			stop(baritone);
			return false;
		}
		ticks++;

		// If currently resting on solid ground while trying to fly, lift off first so ground friction/SNEAK cannot cancel flight.
		if (player.onGround() && !allowTouchGround) {
			liftTicks = 3;
		}
		if (liftTicks > 0) {
			liftTicks--;
			clearFlightInputs(baritone);
			baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
			return true;
		}

		Vec3 pos = player.position();
		Vec3 vel = player.getDeltaMovement();
		double dx = target.x - pos.x;
		double dz = target.z - pos.z;
		double horizDist = Math.hypot(dx, dz);

		// Compute effective target Y: add climb boost when stalled horizontally on obstacles.
		double effectiveTargetY = horizDist > 2.0 ? target.y + cruiseBoostY : target.y;
		double dy = effectiveTargetY - pos.y;

		boolean obstacleAhead = horizDist > 0.6 && (player.horizontalCollision || isBlockedAhead(level, pos, dx, dz));
		boolean ceilingBlocked = isSolid(level, BlockPos.containing(pos.x, pos.y + 2.1, pos.z));
		boolean groundBelow = isSolid(level, BlockPos.containing(pos.x, pos.y - 0.25, pos.z));

		if (ceilingBlocked && cruiseBoostY > 0.0) {
			cruiseBoostY = 0.0;
		}
		if (detourTicks > 0) {
			detourTicks--;
			if (horizDist > 0.1) {
				player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
			}
			var handler = baritone.getInputOverrideHandler();
			handler.setInputForceState(Input.MOVE_FORWARD, false);
			handler.setInputForceState(Input.SPRINT, false);
			handler.setInputForceState(Input.MOVE_BACK, detourBack && detourTicks >= 10);
			handler.setInputForceState(Input.MOVE_LEFT, detourSign < 0);
			handler.setInputForceState(Input.MOVE_RIGHT, detourSign > 0);
			handler.setInputForceState(Input.JUMP, false);
			handler.setInputForceState(Input.SNEAK, (detourDescend || ceilingBlocked) && (!groundBelow || allowTouchGround));
			player.setSprinting(false);
			return true;
		}

		double totalDist = Math.sqrt(dx * dx + (target.y - pos.y) * (target.y - pos.y) + dz * dz);
		if (totalDist < bestDist - 0.35) {
			bestDist = totalDist;
			stallTicks = 0;
			if (horizDist <= 2.0) cruiseBoostY = 0.0;
		} else if (++stallTicks >= 25 && horizDist > 2.0) {
			stallTicks = 0;
			if (ceilingBlocked || cruiseBoostY >= 12.0) {
				// Solid ceiling or high climb failed -> drop below overhang and strafe toward open space.
				cruiseBoostY = 0.0;
				startDetour(level, pos, dx, dz, ceilingBlocked, target.y < pos.y - 0.5);
				return true;
			}
			cruiseBoostY = Math.min(24.0, cruiseBoostY + 3.0);
		}
		if (obstacleAhead && ceilingBlocked) {
			startDetour(level, pos, dx, dz, true, target.y < pos.y - 0.5);
			return true;
		}

		FlightGuidance.Step step = FlightGuidance.step(
				dx, dy, dz,
				vel.x, vel.y, vel.z,
				horizTol, vertTol,
				obstacleAhead && !ceilingBlocked, groundBelow, allowTouchGround,
				ticks
		);

		boolean withinExact = exactBlockTarget == null || player.blockPosition().equals(exactBlockTarget);
		if (step.arrived() && Math.abs(target.y - pos.y) <= vertTol + 0.15 && withinExact) {
			stop(baritone);
			return false;
		}
		if (step.arrived() && cruiseBoostY > 0.0) {
			cruiseBoostY = 0.0;
		}

		boolean ascend = step.ascend() && !ceilingBlocked;
		boolean descend = step.descend();
		boolean forward = step.forward();
		if (step.arrived() && !withinExact && exactBlockTarget != null) {
			if (pos.y < exactBlockTarget.getY()) {
				ascend = !ceilingBlocked;
				descend = false;
			} else if (pos.y >= exactBlockTarget.getY() + 1.0) {
				descend = !groundBelow || allowTouchGround;
				ascend = false;
			}
			if (horizDist > 0.25) {
				forward = (ticks % 2) == 0;
			}
		}

		if (horizDist > 0.1) {
			player.setYRot(step.yaw());
		}
		var handler = baritone.getInputOverrideHandler();
		handler.setInputForceState(Input.MOVE_BACK, false);
		handler.setInputForceState(Input.MOVE_LEFT, false);
		handler.setInputForceState(Input.MOVE_RIGHT, false);
		handler.setInputForceState(Input.MOVE_FORWARD, forward);
		handler.setInputForceState(Input.SPRINT, step.sprint());
		handler.setInputForceState(Input.JUMP, ascend);
		handler.setInputForceState(Input.SNEAK, descend);
		if (!step.sprint()) {
			player.setSprinting(false);
		}
		return true;
	}

	private static boolean isBlockedAhead(Level level, Vec3 pos, double dx, double dz) {
		double len = Math.hypot(dx, dz);
		if (len < 1.0E-4) return false;
		double ux = dx / len;
		double uz = dz / len;
		for (double dist = 0.9; dist <= 1.8; dist += 0.9) {
			double cx = pos.x + ux * dist;
			double cz = pos.z + uz * dist;
			BlockPos feet = BlockPos.containing(cx, pos.y + 0.2, cz);
			BlockPos head = BlockPos.containing(cx, pos.y + 1.5, cz);
			if (isSolid(level, feet) || isSolid(level, head)) return true;
		}
		return false;
	}

	private void startDetour(Level level, Vec3 pos, double dx, double dz, boolean ceilingBlocked, boolean targetBelow) {
		double len = Math.hypot(dx, dz);
		if (len > 1.0E-4) {
			double ux = dx / len;
			double uz = dz / len;
			boolean leftOpen = isDirectionOpen(level, pos, uz, -ux);
			boolean rightOpen = isDirectionOpen(level, pos, -uz, ux);
			boolean backOpen = isDirectionOpen(level, pos, -ux, -uz);
			if (leftOpen && !rightOpen) {
				detourSign = -1;
			} else if (rightOpen && !leftOpen) {
				detourSign = 1;
			} else {
				detourSign = -detourSign;
			}
			detourBack = backOpen;
		} else {
			detourSign = -detourSign;
			detourBack = true;
		}
		detourDescend = ceilingBlocked || targetBelow;
		detourTicks = 22;
	}

	private static boolean isDirectionOpen(Level level, Vec3 pos, double dirX, double dirZ) {
		double cx = pos.x + dirX * 1.1;
		double cz = pos.z + dirZ * 1.1;
		BlockPos feet = BlockPos.containing(cx, pos.y + 0.2, cz);
		BlockPos head = BlockPos.containing(cx, pos.y + 1.5, cz);
		return !isSolid(level, feet) && !isSolid(level, head);
	}

	private static boolean isSolid(Level level, BlockPos pos) {
		if (!level.isLoaded(pos)) return false;
		return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}
}
