package baritone.api.process.area;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SealedPocketDetectorTest {
    @Test
    void detectsSingleBlockCompletelyEnclosedByProtectedShell() {
        // Target at (5, 5, 5), surrounded on all 6 faces by BARRIER; player outside at (0, 10, 0)
        SealedPocketDetector.Result result = SealedPocketDetector.check(
                5, 5, 5, 0, 10, 0, 10,
                (x, y, z) -> (x == 5 && y == 5 && z == 5)
                        ? SealedPocketDetector.CellType.BREAKABLE
                        : SealedPocketDetector.CellType.BARRIER,
                512
        );
        assertTrue(result.sealed());
        assertEquals(1, result.size());
        assertEquals(5, result.x(0));
        assertEquals(5, result.y(0));
        assertEquals(5, result.z(0));
    }

    @Test
    void detectsMultiBlockPocketIncludingInternalAirCavity() {
        // 3x3x3 interior [1..3, 1..3, 1..3] (stone + air cavity) wrapped by protected shell at 0 and 4
        SealedPocketDetector.Result result = SealedPocketDetector.check(
                2, 2, 2, 10, 10, 10, 10,
                (x, y, z) -> {
                    if (x >= 1 && x <= 3 && y >= 1 && y <= 3 && z >= 1 && z <= 3) {
                        return (x == 2 && y == 3 && z == 2)
                                ? SealedPocketDetector.CellType.OPEN
                                : SealedPocketDetector.CellType.BREAKABLE;
                    }
                    return SealedPocketDetector.CellType.BARRIER;
                },
                512
        );
        assertTrue(result.sealed());
        assertEquals(27, result.size());
    }

    @Test
    void treatsBlockUnderProtectedOverhangAsReachableWhenSideOpensToSky() {
        // Protected block at (5, 6, 5), breakable stone underneath at (5, 5, 5), air everywhere else
        SealedPocketDetector.Result result = SealedPocketDetector.check(
                5, 5, 5, 0, 6, 0, 6,
                (x, y, z) -> {
                    if (x == 5 && y == 6 && z == 5) return SealedPocketDetector.CellType.BARRIER;
                    if (x == 5 && y == 5 && z == 5) return SealedPocketDetector.CellType.BREAKABLE;
                    return SealedPocketDetector.CellType.OPEN;
                },
                512
        );
        assertFalse(result.sealed());
    }

    @Test
    void treatsPocketWithPlayerInsideAsReachable() {
        // Sealed 3x3x3 room, but player is standing inside at (2, 2, 2)
        SealedPocketDetector.Result result = SealedPocketDetector.check(
                1, 1, 1, 2, 2, 2, 10,
                (x, y, z) -> (x >= 1 && x <= 3 && y >= 1 && y <= 3 && z >= 1 && z <= 3)
                        ? SealedPocketDetector.CellType.BREAKABLE
                        : SealedPocketDetector.CellType.BARRIER,
                512
        );
        assertFalse(result.sealed());
    }

    @Test
    void treatsLargeUnboundedRegionExceedingMaxVisitAsReachable() {
        SealedPocketDetector.Result result = SealedPocketDetector.check(
                0, 0, 0, 100, 100, 100, 50,
                (x, y, z) -> SealedPocketDetector.CellType.BREAKABLE,
                64
        );
        assertFalse(result.sealed());
    }
}
