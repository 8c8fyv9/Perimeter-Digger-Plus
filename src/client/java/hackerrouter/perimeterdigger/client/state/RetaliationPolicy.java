package hackerrouter.perimeterdigger.client.state;

/** Pure gates: damage-only acquisition and unlimited-range pursuit with an unreachable escape. */
public final class RetaliationPolicy {
    /** Ticks without closing distance before the attacker counts as unreachable (about 30 seconds). */
    public static final int UNREACHABLE_TICKS = 600;
    /** Ticks without closing distance OR landing a hit when pathing cannot reach the target (about 8 seconds). */
    public static final int PATH_FAIL_ABORT_COUNT = 3;
    public static final int NO_PROGRESS_ABORT_TICKS = 160;

    private RetaliationPolicy() {}

    /** No distance or time limit; only a target that keeps getting no closer ends the fight. */
    public static boolean pursue(int stalledTicks) {
        return stalledTicks < UNREACHABLE_TICKS;
    }

    public static boolean shouldAbortCombat(int stalledTicks, int pathFailures, int ticksSinceHit, boolean inAttackRange) {
        if (!pursue(stalledTicks)) {
            return true;
        }
        if (inAttackRange) {
            return false;
        }
        if (pathFailures >= PATH_FAIL_ABORT_COUNT) {
            return true;
        }
        return stalledTicks >= NO_PROGRESS_ABORT_TICKS && ticksSinceHit >= NO_PROGRESS_ABORT_TICKS;
    }

    public static boolean shouldSwitchTarget(boolean currentInRange, double currentDistSq, double newDistSq) {
        if (currentInRange) {
            return false;
        }
        return newDistSq < currentDistSq - 2.25;
    }

    public static boolean active(AutomationState state) {
        return switch (state) {
            case IDLE, COMPLETE, ERROR, PAUSED, VALIDATING -> false;
            default -> true;
        };
    }

    public static boolean acquire(boolean active, boolean localVictim, boolean hostileSource) {
        return active && localVictim && hostileSource;
    }

    public static boolean attack(boolean alive, boolean weapon, boolean range, boolean sight, float cooldown) {
        return alive && weapon && range && sight && cooldown >= 1.0F;
    }
}
