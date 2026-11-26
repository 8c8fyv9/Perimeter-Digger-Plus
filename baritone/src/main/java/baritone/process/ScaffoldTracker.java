package baritone.process;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tracks throwaway scaffold blocks placed by Baritone pathing movements inside an active
 * area-mining volume so the builder does not immediately mine its own pillar/bridge mid-path
 * or count self-placed scaffolds toward mined-block progress.
 * Pure logic, no Minecraft classes, unit tested.
 */
final class ScaffoldTracker {
    private final long cooldownMs;
    private final Map<Long, Long> activeCooldowns = new HashMap<>();
    private final Set<Long> cycleScaffolds = new HashSet<>();

    ScaffoldTracker(long cooldownMs) {
        if (cooldownMs < 0L) {
            throw new IllegalArgumentException("cooldownMs must be >= 0");
        }
        this.cooldownMs = cooldownMs;
    }

    void recordScaffold(long posKey, long nowMs) {
        activeCooldowns.put(posKey, nowMs + cooldownMs);
        cycleScaffolds.add(posKey);
    }

    boolean isProtected(long posKey, long nowMs) {
        Long until = activeCooldowns.get(posKey);
        if (until == null) {
            return false;
        }
        if (until <= nowMs) {
            activeCooldowns.remove(posKey);
            return false;
        }
        return true;
    }

    void refreshIfProtected(long posKey, long nowMs) {
        if (isProtected(posKey, nowMs)) {
            activeCooldowns.put(posKey, nowMs + cooldownMs);
        }
    }

    boolean hasActiveScaffolds(long nowMs) {
        activeCooldowns.entrySet().removeIf(entry -> entry.getValue() <= nowMs);
        return !activeCooldowns.isEmpty();
    }

    /**
     * Releases all active scaffold cooldowns so remaining scaffolds can be mined during final
     * area cleanup. Returns true if any active scaffolds were released.
     */
    boolean releaseAllActive() {
        if (activeCooldowns.isEmpty()) {
            return false;
        }
        activeCooldowns.clear();
        return true;
    }

    /**
     * Consumes and returns true when {@code posKey} was placed as a pathing scaffold during the
     * current cycle, preventing self-placed scaffold removal from inflating mined progress.
     */
    boolean consumeCycleScaffold(long posKey) {
        activeCooldowns.remove(posKey);
        return cycleScaffolds.remove(posKey);
    }

    boolean isCycleScaffold(long posKey) {
        return cycleScaffolds.contains(posKey);
    }

    void clear() {
        activeCooldowns.clear();
        cycleScaffolds.clear();
    }
}
