package baritone.process;

import java.util.HashMap;
import java.util.Map;

/**
 * Stops the builder from re-aiming at one unreachable placement forever.
 * Single threaded (client tick only). Pure logic, no Minecraft classes, unit tested.
 */
final class PlacementBackoff {
    private final Map<Object, Long> cooldownUntil = new HashMap<>();
    private Object streakPos;
    private long streakStartMs;
    private long lastAttemptMs;
    private final int maxStreakMs;
    private final long cooldownMs;
    private static final long STREAK_GAP_MS = 1000;

    PlacementBackoff(int maxStreakMs, long cooldownMs) {
        this.maxStreakMs = maxStreakMs;
        this.cooldownMs = cooldownMs;
    }

    boolean shouldAttempt(Object pos, long now) {
        Long until = cooldownUntil.get(pos);
        return until == null || until <= now;
    }

    void recordAttempt(Object pos, long now) {
        if (streakPos == null || !streakPos.equals(pos) || now - lastAttemptMs > STREAK_GAP_MS) {
            streakPos = pos;
            streakStartMs = now;
        }
        lastAttemptMs = now;
        if (now - streakStartMs > maxStreakMs) {
            cooldownUntil.put(pos, now + cooldownMs);
            streakPos = null;
        }
    }

    void clear() {
        cooldownUntil.clear();
        streakPos = null;
    }
}