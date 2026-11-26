package baritone.process;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlacementBackoffTest {
    private static final Object POS = "19062,62,-11623";
    private static final Object OTHER = "19062,61,-11622";

    @Test void firstAttemptIsAllowed() {
        var backoff = new PlacementBackoff(3000, 5000);
        assertTrue(backoff.shouldAttempt(POS, 1000));
    }

    @Test void shortStreakIsStillAllowed() {
        var backoff = new PlacementBackoff(3000, 5000);
        for (long t = 1000; t < 4000; t += 100) {
            assertTrue(backoff.shouldAttempt(POS, t), "blocked too early at " + t);
            backoff.recordAttempt(POS, t);
        }
    }

    @Test void stuckTargetCooldownsThenExpires() {
        var backoff = new PlacementBackoff(3000, 5000);
        long t = 1000;
        while (t < 4200) { backoff.recordAttempt(POS, t); t += 100; }
        assertFalse(backoff.shouldAttempt(POS, 4200), "streak over 3s must cooldown");
        assertFalse(backoff.shouldAttempt(POS, 9000), "cooldown is 5s");
        assertTrue(backoff.shouldAttempt(POS, 9100), "cooldown must expire");
    }

    @Test void differentTargetResetsStreak() {
        var backoff = new PlacementBackoff(3000, 5000);
        long t = 1000;
        while (t < 4000) { backoff.recordAttempt(POS, t); t += 100; }
        backoff.recordAttempt(OTHER, 4000);
        assertTrue(backoff.shouldAttempt(OTHER, 4000));
        for (int i = 1; i <= 32; i++) { backoff.recordAttempt(OTHER, 4000 + i * 100L); }
        assertFalse(backoff.shouldAttempt(OTHER, 7300), "second target also cooldowns when stuck");
        assertTrue(backoff.shouldAttempt(POS, 7300), "cooldown is per target");
    }

    @Test void idleGapResetsStreakStart() {
        var backoff = new PlacementBackoff(3000, 5000);
        backoff.recordAttempt(POS, 1000);
        backoff.recordAttempt(POS, 1000 + 5000);
        backoff.recordAttempt(POS, 1000 + 7900);
        assertTrue(backoff.shouldAttempt(POS, 8000), "gap over 1s restarts streak");
    }
}