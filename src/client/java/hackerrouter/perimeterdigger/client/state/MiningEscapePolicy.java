package hackerrouter.perimeterdigger.client.state;

/**
 * Pure progression rules for escaping a mining loop. The watchdog looks at mined-block progress,
 * not at the player position, so walking around without breaking anything still counts as stuck.
 */
public final class MiningEscapePolicy {
    /** Escalation steps before the task pauses for a human. */
    public static final int ESCAPE_LIMIT = 5;
    /** Idle time in seconds before a cycle without any mined block counts as stalled. */
    public static final double PROGRESS_TIMEOUT_SECONDS = 20.0;
    /** Faster trigger when blocks are being mined but no new position is: the place/dig loop. */
    public static final double LOOP_TIMEOUT_SECONDS = 8.0;
    /** Extra monitor scans (0.5s each) allowed for genuine cross-area walking before idle counting resumes. */
    public static final int MAX_TRANSIT_CREDITS = 30;
    /** Minimum squared displacement per 0.5s scan to count as active walking across cleared terrain (~1.5 blocks). */
    public static final double TRANSIT_STEP_MIN_DIST_SQ = 2.25;
    /** Cap on retained idle scans right after a stall-recovery walk finishes so the next transit gets >=15s grace. */
    public static final int POST_STALL_RECOVERY_IDLE_CAP = 10;

    private MiningEscapePolicy() {}

    /** A fresh Baritone cycle starts at 0 mined blocks; zero must not count as progress. */
    public static long initialBaseline() {
        return 0L;
    }

    /** Only a strictly higher progress score resets idle/escape counters. */
    public static boolean hasProgressed(long score, long baseline) {
        return score > baseline;
    }

    /** Bounds a single stall/escape walk so an unreachable target cannot hang recovery forever. */
    public static int recoveryWalkTimeoutTicks(double configuredNavigationTimeoutSeconds) {
        return Math.max(100, (int) Math.ceil(Math.max(0.0, configuredNavigationTimeoutSeconds) * 20.0));
    }

    /** The controller monitors mining status twice per second. */
    public static boolean stalled(int idleScans) {
        return idleScans >= (int) Math.ceil(PROGRESS_TIMEOUT_SECONDS * 2.0);
    }

    /** Same-block churn: raw mining continues while no new block position is cleared. */
    public static boolean looping(int idleScans) {
        return idleScans >= (int) Math.ceil(LOOP_TIMEOUT_SECONDS * 2.0);
    }

    /** Chooses the timeout that matches the symptom: churn over the same blocks, or plain idling. */
    public static boolean shouldEscape(int idleScans, boolean rawStillGrowing) {
        return rawStillGrowing ? looping(idleScans) : stalled(idleScans);
    }

    /** Escalating relocation distance in blocks; the first attempt simply rebuilds the cycle. */
    public static int escapeDistance(int attempt) {
        return switch (attempt) {
            case 1 -> 0;
            case 2 -> 8;
            case 3 -> 16;
            case 4 -> 28;
            default -> 40;
        };
    }

    /** Rotation index so repeated escapes do not walk the same direction every time. */
    public static int directionIndex(int attempt) {
        return Math.floorMod(attempt - 1, 4);
    }

    public static boolean giveUp(int attempt) {
        return attempt >= ESCAPE_LIMIT;
    }

    /**
     * Returns true when the player is actively walking across cleared terrain toward distant targets
     * (not shuffling in place and not churning the same blocks), pausing idle countdown up to a bounded budget.
     */
    public static boolean isActiveTransit(
            double movedDistSq,
            boolean pathing,
            boolean oscillating,
            boolean rawStillGrowing,
            int usedTransitCredits
    ) {
        return pathing
                && !oscillating
                && !rawStillGrowing
                && usedTransitCredits < MAX_TRANSIT_CREDITS
                && movedDistSq >= TRANSIT_STEP_MIN_DIST_SQ;
    }

    /** Retains partial idle pressure after stall recovery while giving the fresh cycle room to reach new targets. */
    public static int idleScansAfterStallRecovery(int currentIdleScans) {
        return Math.max(0, Math.min(currentIdleScans, POST_STALL_RECOVERY_IDLE_CAP));
    }
}
