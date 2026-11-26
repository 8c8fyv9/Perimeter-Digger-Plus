package baritone.api.process.area;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AreaMiningStrategyPlannerTest {
    @Test
    void ordersFourChunksByNearestDistanceToPlayer() {
        // 2x2 chunk area: X=[0..31], Z=[0..31] -> chunks (0,0), (1,0), (0,1), (1,1)
        List<AreaMiningStrategyPlanner.ChunkCoord> ordered = AreaMiningStrategyPlanner.chunksOrderedByDistance(
                0, 31, 0, 31, 28, 29);
        assertEquals(4, ordered.size());
        assertEquals(new AreaMiningStrategyPlanner.ChunkCoord(1, 1), ordered.get(0));
        assertEquals(new AreaMiningStrategyPlanner.ChunkCoord(0, 0), ordered.get(3));
    }

    @Test
    void handlesNegativeAndLargeCoordinatesInChunkCalculation() {
        assertEquals(-1, AreaMiningStrategyPlanner.chunkCoord(-1));
        assertEquals(-1, AreaMiningStrategyPlanner.chunkCoord(-16));
        assertEquals(-2, AreaMiningStrategyPlanner.chunkCoord(-17));
        assertEquals(678245, AreaMiningStrategyPlanner.chunkCoord(10851931));
        assertTrue(AreaMiningStrategyPlanner.isWithinChunk(10851931, 10165118,
                AreaMiningStrategyPlanner.chunkCoord(10851931), AreaMiningStrategyPlanner.chunkCoord(10165118), 0));
    }

    @Test
    void localFocusWindowSeparatesDistantTowers() {
        int r = AreaMiningStrategyPlanner.LOCAL_FOCUS_RADIUS;
        // Tower A at (0, 0), Tower B at (30, 0)
        assertTrue(AreaMiningStrategyPlanner.isWithinLocalFocus(r, 0, 0, 0, 0));
        assertFalse(AreaMiningStrategyPlanner.isWithinLocalFocus(30, 0, 0, 0, 0));
        assertTrue(AreaMiningStrategyPlanner.isWithinLocalFocus(30, 0, 30, 0, 0));
    }

    @Test
    void escalatingDeferralGrowsThenSkipsForCycle() {
        assertEquals(45_000L, AreaMiningStrategyPlanner.escalatingDeferralMillis(1, 45_000L));
        assertEquals(180_000L, AreaMiningStrategyPlanner.escalatingDeferralMillis(2, 45_000L));
        assertEquals(-1L, AreaMiningStrategyPlanner.escalatingDeferralMillis(3, 45_000L));
        assertEquals(-1L, AreaMiningStrategyPlanner.escalatingDeferralMillis(5, 45_000L));
    }
}
