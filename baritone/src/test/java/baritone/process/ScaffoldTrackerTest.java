package baritone.process;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScaffoldTrackerTest {
    private static final long POS_A = 101L;
    private static final long POS_B = 202L;

    @Test
    void recordedScaffoldIsProtectedUntilCooldownExpires() {
        var tracker = new ScaffoldTracker(30_000L);
        tracker.recordScaffold(POS_A, 1_000L);
        assertTrue(tracker.isProtected(POS_A, 1_000L));
        assertTrue(tracker.isProtected(POS_A, 30_999L));
        assertTrue(tracker.hasActiveScaffolds(30_999L));
        assertFalse(tracker.isProtected(POS_A, 31_000L));
        assertFalse(tracker.hasActiveScaffolds(31_000L));
        assertTrue(tracker.isCycleScaffold(POS_A), "expired cooldown still prevents mined-progress inflation");
    }

    @Test
    void refreshExtendsActiveProtectionWithoutRevivingExpired() {
        var tracker = new ScaffoldTracker(30_000L);
        tracker.recordScaffold(POS_A, 1_000L);
        tracker.refreshIfProtected(POS_A, 25_000L);
        assertTrue(tracker.isProtected(POS_A, 54_999L));
        assertFalse(tracker.isProtected(POS_A, 55_000L));
        tracker.refreshIfProtected(POS_A, 55_000L);
        assertFalse(tracker.isProtected(POS_A, 55_000L));
        tracker.refreshIfProtected(POS_B, 25_000L);
        assertFalse(tracker.isProtected(POS_B, 25_000L));
    }

    @Test
    void releaseAllActiveUnblocksCleanupWhilePreservingCycleScaffoldMark() {
        var tracker = new ScaffoldTracker(30_000L);
        tracker.recordScaffold(POS_A, 1_000L);
        tracker.recordScaffold(POS_B, 2_000L);
        assertTrue(tracker.releaseAllActive());
        assertFalse(tracker.releaseAllActive());
        assertFalse(tracker.isProtected(POS_A, 2_000L));
        assertFalse(tracker.isProtected(POS_B, 2_000L));
        assertTrue(tracker.isCycleScaffold(POS_A));
        assertTrue(tracker.isCycleScaffold(POS_B));
    }

    @Test
    void consumeCycleScaffoldReportsOnceThenResetsForFutureBlocks() {
        var tracker = new ScaffoldTracker(30_000L);
        tracker.recordScaffold(POS_A, 1_000L);
        assertTrue(tracker.consumeCycleScaffold(POS_A));
        assertFalse(tracker.isProtected(POS_A, 1_500L));
        assertFalse(tracker.consumeCycleScaffold(POS_A), "subsequent real block at same pos can count");
        assertFalse(tracker.consumeCycleScaffold(POS_B));
    }

    @Test
    void clearResetsBothProtectionAndCycleHistory() {
        var tracker = new ScaffoldTracker(30_000L);
        tracker.recordScaffold(POS_A, 1_000L);
        tracker.clear();
        assertFalse(tracker.isProtected(POS_A, 1_000L));
        assertFalse(tracker.hasActiveScaffolds(1_000L));
        assertFalse(tracker.isCycleScaffold(POS_A));
    }
}
