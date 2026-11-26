/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.api.utils;

import baritone.api.pathing.movement.ActionCosts;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Temporary A* penalty registry for cells where path execution recently stalled, oscillated,
 * or timed out. Forces subsequent Baritone path calculations (especially after backtracking)
 * to route around the stuck zone instead of walking back down the exact same failing path.
 */
public final class StuckCellPenalty {
    private static final int MAX_ENTRIES = 1024;
    private static final ConcurrentHashMap<Long, Entry> ENTRIES = new ConcurrentHashMap<>();

    private record Entry(double penalty, int strikes, long expiresAtMillis) {}

    /** Zero-boxing immutable hash snapshot read by AStarPathFinder on worker threads. */
    public static final class Snapshot {
        private final long[] keys;
        private final double[] values;
        private final boolean[] occupied;
        private final int mask;

        private Snapshot(long[] rawKeys, double[] rawValues, int count) {
            int cap = 16;
            while (cap < count * 2) cap <<= 1;
            this.keys = new long[cap];
            this.values = new double[cap];
            this.occupied = new boolean[cap];
            this.mask = cap - 1;
            for (int i = 0; i < count; i++) {
                int idx = mix(rawKeys[i]) & mask;
                while (occupied[idx]) {
                    idx = (idx + 1) & mask;
                }
                occupied[idx] = true;
                keys[idx] = rawKeys[i];
                values[idx] = rawValues[i];
            }
        }

        public double get(long posHash) {
            int idx = mix(posHash) & mask;
            while (occupied[idx]) {
                if (keys[idx] == posHash) {
                    return values[idx];
                }
                idx = (idx + 1) & mask;
            }
            return 0.0;
        }

        private static int mix(long z) {
            z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
            z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
            return (int) (z ^ (z >>> 33));
        }
    }

    private StuckCellPenalty() {}

    /** Matches {@code BetterBlockPos.longHash(x, y, z)} without referencing Minecraft classes. */
    public static long posHash(int x, int y, int z) {
        long hash = 3241;
        hash = 3457689L * hash + x;
        hash = 8734625L * hash + y;
        hash = 2873465L * hash + z;
        return hash;
    }

    /** Penalizes a single cell (for example, a movement destination that just timed out). */
    public static void penalizeCell(int x, int y, int z, long ttlMillis) {
        penalizeCell(x, y, z, ttlMillis, System.currentTimeMillis());
    }

    static void penalizeCell(int x, int y, int z, long ttlMillis, long nowMillis) {
        if (ttlMillis <= 0L) return;
        pruneIfNeeded(nowMillis);
        long key = posHash(x, y, z);
        long expires = nowMillis + ttlMillis;
        ENTRIES.compute(key, (k, existing) -> {
            int strikes = (existing != null && existing.expiresAtMillis > nowMillis) ? existing.strikes + 1 : 1;
            double penalty = strikes == 1 ? 50.0 : (strikes == 2 ? 160.0 : ActionCosts.COST_INF);
            return new Entry(penalty, strikes, Math.max(expires, existing == null ? 0L : existing.expiresAtMillis));
        });
    }

    /**
     * Penalizes a 3D neighborhood around a stall position with escalating cost:
     * core (Chebyshev XZ <= 1): +60 -> +180 -> COST_INF; outer ring (XZ == 2): +25 -> +80 -> +200.
     */
    public static void penalizeNeighborhood(int cx, int cy, int cz, int radiusXZ, int radiusY, long ttlMillis) {
        penalizeNeighborhood(cx, cy, cz, radiusXZ, radiusY, ttlMillis, System.currentTimeMillis());
    }

    static void penalizeNeighborhood(int cx, int cy, int cz, int radiusXZ, int radiusY, long ttlMillis, long nowMillis) {
        if (ttlMillis <= 0L) return;
        pruneIfNeeded(nowMillis);
        long expires = nowMillis + ttlMillis;
        int rx = Math.max(0, Math.min(3, radiusXZ));
        int ry = Math.max(0, Math.min(2, radiusY));
        for (int dy = -ry; dy <= ry; dy++) {
            for (int dx = -rx; dx <= rx; dx++) {
                for (int dz = -rx; dz <= rx; dz++) {
                    int cheb = Math.max(Math.abs(dx), Math.abs(dz));
                    boolean core = cheb <= 1 && Math.abs(dy) <= 1;
                    long key = posHash(cx + dx, cy + dy, cz + dz);
                    ENTRIES.compute(key, (k, existing) -> {
                        int strikes = (existing != null && existing.expiresAtMillis > nowMillis) ? existing.strikes + 1 : 1;
                        double p;
                        if (core) {
                            p = strikes == 1 ? 60.0 : (strikes == 2 ? 180.0 : ActionCosts.COST_INF);
                        } else {
                            p = strikes == 1 ? 25.0 : (strikes == 2 ? 80.0 : 200.0);
                        }
                        double merged = (existing != null && existing.expiresAtMillis > nowMillis)
                                ? Math.max(existing.penalty, p)
                                : p;
                        return new Entry(merged, strikes, Math.max(expires, existing == null ? 0L : existing.expiresAtMillis));
                    });
                }
            }
        }
    }

    public static Snapshot snapshot() {
        return snapshot(System.currentTimeMillis());
    }

    static Snapshot snapshot(long nowMillis) {
        if (ENTRIES.isEmpty()) return null;
        int size = ENTRIES.size();
        long[] keys = new long[size];
        double[] values = new double[size];
        int count = 0;
        Iterator<Map.Entry<Long, Entry>> it = ENTRIES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Entry> e = it.next();
            Entry val = e.getValue();
            if (val.expiresAtMillis <= nowMillis) {
                it.remove();
                continue;
            }
            if (count == keys.length) {
                keys = java.util.Arrays.copyOf(keys, keys.length * 2);
                values = java.util.Arrays.copyOf(values, values.length * 2);
            }
            keys[count] = e.getKey();
            values[count] = val.penalty;
            count++;
        }
        return count == 0 ? null : new Snapshot(keys, values, count);
    }

    public static void clear() {
        ENTRIES.clear();
    }

    private static void pruneIfNeeded(long nowMillis) {
        if (ENTRIES.size() < MAX_ENTRIES) return;
        ENTRIES.entrySet().removeIf(e -> e.getValue().expiresAtMillis <= nowMillis);
        if (ENTRIES.size() >= MAX_ENTRIES) {
            ENTRIES.clear();
        }
    }
}
