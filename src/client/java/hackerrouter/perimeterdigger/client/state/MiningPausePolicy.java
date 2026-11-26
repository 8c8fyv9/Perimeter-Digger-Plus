package hackerrouter.perimeterdigger.client.state;

/** Pure gates for recovering from Baritone area-mining pathing pauses. */
public final class MiningPausePolicy {
    private MiningPausePolicy() {}

    /** A failed path calculation is the only pause that can be retried without user action. */
    public static boolean recoverable(boolean pathingFailed) {
        return pathingFailed;
    }

    public static boolean exhausted(int attempts, int limit) {
        return attempts >= limit;
    }

    /** Progress in the current cycle clears the retry budget; a zero-progress cycle keeps counting. */
    public static int resetOnProgress(int attempts, long minedBlocks) {
        return minedBlocks > 0L ? 0 : attempts;
    }

    public static int recoveryDistance(int configured) {
        return Math.max(6, configured);
    }

    /**
     * Without a configured unload point, letting blockLimit collapse to 1 when empty slots drop to
     * the reserved threshold causes a 1-block dig/collect loop (or repeated unload errors).
     */
    public static long miningBlockLimit(boolean collectDropsEnabled, boolean hasConfiguredUnload,
            int emptySlots, int reservedSlots, int blocksPerEmptySlot) {
        if (!collectDropsEnabled || emptySlots <= 0) return Long.MAX_VALUE;
        int usableSlots = emptySlots - reservedSlots;
        if (!hasConfiguredUnload && usableSlots <= 0) return Long.MAX_VALUE;
        return Math.max(1L, (long) Math.max(0, usableSlots) * Math.max(1, blocksPerEmptySlot));
    }
}
