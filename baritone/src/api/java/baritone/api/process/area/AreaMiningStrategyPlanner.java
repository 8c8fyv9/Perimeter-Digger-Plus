/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.api.process.area;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure coordinate/chunk window helpers shared by Baritone ground area mining and creative-flight mining.
 */
public final class AreaMiningStrategyPlanner {
    /** Half-width of the sticky local focus box (25x25 columns) for LOCAL_TOP_DOWN. */
    public static final int LOCAL_FOCUS_RADIUS = 12;

    public record ChunkCoord(int chunkX, int chunkZ) {
        public int minBlockX() {
            return chunkX << 4;
        }

        public int maxBlockX() {
            return (chunkX << 4) + 15;
        }

        public int minBlockZ() {
            return chunkZ << 4;
        }

        public int maxBlockZ() {
            return (chunkZ << 4) + 15;
        }

        public double centerX() {
            return (chunkX << 4) + 8.0;
        }

        public double centerZ() {
            return (chunkZ << 4) + 8.0;
        }
    }

    private AreaMiningStrategyPlanner() {
    }

    public static int chunkCoord(int blockCoord) {
        return blockCoord >> 4;
    }

    /**
     * Returns all 16x16 chunks intersecting [minX..maxX] x [minZ..maxZ], sorted by ascending
     * squared XZ distance from (playerX, playerZ) to the clamped point inside each chunk's overlap with the area.
     */
    public static List<ChunkCoord> chunksOrderedByDistance(int minX, int maxX, int minZ, int maxZ, int playerX, int playerZ) {
        int minCx = chunkCoord(minX);
        int maxCx = chunkCoord(maxX);
        int minCz = chunkCoord(minZ);
        int maxCz = chunkCoord(maxZ);
        List<ChunkCoord> chunks = new ArrayList<>();
        for (int cz = minCz; cz <= maxCz; cz++) {
            for (int cx = minCx; cx <= maxCx; cx++) {
                chunks.add(new ChunkCoord(cx, cz));
            }
        }
        chunks.sort(Comparator.comparingDouble(c -> chunkDistanceSq(c, minX, maxX, minZ, maxZ, playerX, playerZ)));
        return chunks;
    }

    public static double chunkDistanceSq(ChunkCoord chunk, int areaMinX, int areaMaxX, int areaMinZ, int areaMaxZ, int playerX, int playerZ) {
        int loX = Math.max(areaMinX, chunk.minBlockX());
        int hiX = Math.min(areaMaxX, chunk.maxBlockX());
        int loZ = Math.max(areaMinZ, chunk.minBlockZ());
        int hiZ = Math.min(areaMaxZ, chunk.maxBlockZ());
        // Primary distance to the nearest point inside this chunk's portion of the area (with tiny tie-breaker to center).
        int clampedX = Math.max(loX, Math.min(hiX, playerX));
        int clampedZ = Math.max(loZ, Math.min(hiZ, playerZ));
        double dx = clampedX - playerX;
        double dz = clampedZ - playerZ;
        double centerDx = (loX + hiX) * 0.5 - playerX;
        double centerDz = (loZ + hiZ) * 0.5 - playerZ;
        return dx * dx + dz * dz + (centerDx * centerDx + centerDz * centerDz) * 1.0E-4;
    }

    public static boolean isWithinLocalFocus(int x, int z, int anchorX, int anchorZ, int margin) {
        int r = LOCAL_FOCUS_RADIUS + margin;
        return Math.abs(x - anchorX) <= r && Math.abs(z - anchorZ) <= r;
    }

    public static boolean isWithinChunk(int x, int z, int chunkX, int chunkZ, int margin) {
        int minX = (chunkX << 4) - margin;
        int maxX = (chunkX << 4) + 15 + margin;
        int minZ = (chunkZ << 4) - margin;
        int maxZ = (chunkZ << 4) + 15 + margin;
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /**
     * Escalating deferral duration for repeatedly stalled area-mining targets so the bot does not
     * ping-pong between two stubborn spots every 45 seconds.
     *
     * @return deferral duration in milliseconds, or {@code -1L} when the target should be skipped
     *         for the rest of the current mining cycle.
     */
    public static long escalatingDeferralMillis(int failCount, long baseCooldownMillis) {
        long base = Math.max(1_000L, baseCooldownMillis);
        if (failCount <= 1) {
            return base;
        }
        if (failCount == 2) {
            return Math.max(base * 4L, 180_000L);
        }
        return -1L;
    }
}
