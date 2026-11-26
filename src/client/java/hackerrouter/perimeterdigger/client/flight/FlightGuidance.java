package hackerrouter.perimeterdigger.client.flight;

/**
 * Pure 3D creative-flight steering guidance (blocks per client tick).
 * Uses velocity-aware braking and obstacle climb logic without directly mutating velocity.
 */
public final class FlightGuidance {
	public record Step(
			float yaw,
			boolean forward,
			boolean sprint,
			boolean ascend,
			boolean descend,
			boolean arrived
	) {}

	private FlightGuidance() {}

	public static Step step(
			double dx,
			double dy,
			double dz,
			double vx,
			double vy,
			double vz,
			double horizTol,
			double vertTol,
			boolean obstacleAhead,
			boolean groundImmediatelyBelow,
			boolean allowTouchGround,
			int tick
	) {
		double horizDist = Math.hypot(dx, dz);
		double horizSpeed = Math.hypot(vx, vz);
		if (horizDist <= horizTol && Math.abs(dy) <= vertTol && horizSpeed <= 0.14 && Math.abs(vy) <= 0.14) {
			return new Step(0.0F, false, false, false, false, true);
		}

		float yaw = horizDist > 0.1 ? (float) Math.toDegrees(Math.atan2(-dx, dz)) : 0.0F;

		// Vertical control: climb over obstacles ahead, otherwise track target Y with damping.
		boolean ascend = false;
		boolean descend = false;
		if (obstacleAhead) {
			ascend = true;
		} else if (dy > vertTol * 0.55) {
			ascend = vy * 4.5 < dy + 0.15;
		} else if (dy < -vertTol * 0.55) {
			if (!groundImmediatelyBelow || allowTouchGround) {
				descend = vy * 4.5 > dy - 0.15;
			}
		}

		// Horizontal control with airborne drag braking (drag ~0.91/tick -> stopping distance ~7*v).
		double towardSpeed = horizDist > 1.0E-4 ? (vx * dx + vz * dz) / horizDist : 0.0;
		double projectedRemaining = horizDist - Math.max(0.0, towardSpeed) * 7.0;
		boolean forward;
		boolean sprint;
		if (horizDist <= horizTol * 0.85) {
			forward = false;
			sprint = false;
		} else if (obstacleAhead) {
			// Focus on climbing over the obstacle while nudging gently forward.
			forward = (tick % 4) == 0;
			sprint = false;
		} else {
			boolean coast = towardSpeed > 0.06 && projectedRemaining <= Math.max(0.35, horizTol * 0.65);
			forward = !coast && (horizDist > 1.6 || (tick % 2) == 0);
			sprint = forward && horizDist > Math.max(5.5, horizSpeed * 11.0 + 1.8) && Math.abs(dy) < 8.0;
		}

		return new Step(yaw, forward, sprint, ascend, descend, false);
	}
}
