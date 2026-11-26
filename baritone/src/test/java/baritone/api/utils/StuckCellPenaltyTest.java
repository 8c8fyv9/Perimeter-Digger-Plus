package baritone.api.utils;

import baritone.api.pathing.movement.ActionCosts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StuckCellPenaltyTest {
    @AfterEach
    void cleanup() {
        StuckCellPenalty.clear();
    }

    @Test
    void escalatesPenaltyAcrossRepeatedStallsAndExpiresAfterTtl() {
        long t0 = 10_000L;
        long coreHash = StuckCellPenalty.posHash(100, 62, 200);
        long outerHash = StuckCellPenalty.posHash(102, 62, 200);
        long farHash = StuckCellPenalty.posHash(110, 62, 200);

        StuckCellPenalty.penalizeNeighborhood(100, 62, 200, 2, 1, 60_000L, t0);
        StuckCellPenalty.Snapshot s1 = StuckCellPenalty.snapshot(t0 + 100);
        assertNotNull(s1);
        assertEquals(60.0, s1.get(coreHash), 1e-6);
        assertEquals(25.0, s1.get(outerHash), 1e-6);
        assertEquals(0.0, s1.get(farHash), 1e-6);

        // 2nd stall escalates penalty sharply
        StuckCellPenalty.penalizeNeighborhood(100, 62, 200, 2, 1, 60_000L, t0 + 5_000);
        StuckCellPenalty.Snapshot s2 = StuckCellPenalty.snapshot(t0 + 5_100);
        assertEquals(180.0, s2.get(coreHash), 1e-6);
        assertEquals(80.0, s2.get(outerHash), 1e-6);

        // 3rd stall hard-bans core cells for the TTL window
        StuckCellPenalty.penalizeNeighborhood(100, 62, 200, 2, 1, 60_000L, t0 + 10_000);
        StuckCellPenalty.Snapshot s3 = StuckCellPenalty.snapshot(t0 + 10_100);
        assertTrue(s3.get(coreHash) >= ActionCosts.COST_INF);

        // Expired after TTL
        assertNull(StuckCellPenalty.snapshot(t0 + 80_000));
    }

    @Test
    void singleCellPenaltyEscalatesOnRepeatedTimeouts() {
        long t0 = 5_000L;
        long key = StuckCellPenalty.posHash(10, 50, -20);
        StuckCellPenalty.penalizeCell(10, 50, -20, 30_000L, t0);
        assertEquals(50.0, StuckCellPenalty.snapshot(t0 + 1).get(key), 1e-6);
        StuckCellPenalty.penalizeCell(10, 50, -20, 30_000L, t0 + 1000);
        assertEquals(160.0, StuckCellPenalty.snapshot(t0 + 1001).get(key), 1e-6);
        StuckCellPenalty.penalizeCell(10, 50, -20, 30_000L, t0 + 2000);
        assertTrue(StuckCellPenalty.snapshot(t0 + 2001).get(key) >= ActionCosts.COST_INF);
    }
}
