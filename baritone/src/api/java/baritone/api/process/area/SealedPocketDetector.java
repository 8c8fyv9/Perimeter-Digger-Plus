/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.api.process.area;

import java.util.HashSet;
import java.util.Set;

/**
 * Bounded 3D reverse-BFS detector for pockets of breakable/open cells that are completely
 * enclosed by protected/unbreakable barriers and therefore cannot be reached from the player
 * or open exterior without breaking protected blocks.
 */
public final class SealedPocketDetector {

    public enum CellType {
        /** Protected whitelist block, unbreakable block, or solid out-of-area wall. */
        BARRIER,
        /** Non-protected breakable block inside the mining area. */
        BREAKABLE,
        /** Open air / passable cell that the player can stand, reach, or fly through. */
        OPEN
    }

    @FunctionalInterface
    public interface CellClassifier {
        CellType classify(int x, int y, int z);
    }

    public static final class Result {
        public static final Result REACHABLE = new Result(false, new int[0], new int[0], new int[0], 0);

        private final boolean sealed;
        private final int[] xs;
        private final int[] ys;
        private final int[] zs;
        private final int size;

        private Result(boolean sealed, int[] xs, int[] ys, int[] zs, int size) {
            this.sealed = sealed;
            this.xs = xs;
            this.ys = ys;
            this.zs = zs;
            this.size = size;
        }

        public boolean sealed() {
            return sealed;
        }

        public int size() {
            return size;
        }

        public int x(int index) {
            return xs[index];
        }

        public int y(int index) {
            return ys[index];
        }

        public int z(int index) {
            return zs[index];
        }
    }

    /** Check +Y first so normal exposed/unblocked blocks escape upward rapidly. */
    private static final int[][] DIRS = {
            {0, 1, 0},
            {1, 0, 0},
            {-1, 0, 0},
            {0, 0, 1},
            {0, 0, -1},
            {0, -1, 0}
    };

    private SealedPocketDetector() {
    }

    /**
     * Checks whether {@code (startX, startY, startZ)} is trapped inside a bounded sealed pocket
     * surrounded by {@link CellType#BARRIER} cells.
     *
     * <p>Escapes immediately as {@link Result#REACHABLE} if BFS reaches the player's feet/head,
     * reaches an {@link CellType#OPEN} cell strictly above {@code areaMaxY}, or expands beyond
     * {@code maxVisit} cells.</p>
     */
    public static Result check(
            int startX,
            int startY,
            int startZ,
            int playerX,
            int playerY,
            int playerZ,
            int areaMaxY,
            CellClassifier classifier,
            int maxVisit
    ) {
        int limit = Math.max(1, maxVisit);
        if (classifier.classify(startX, startY, startZ) != CellType.BREAKABLE) {
            return Result.REACHABLE;
        }
        int[] qx = new int[limit];
        int[] qy = new int[limit];
        int[] qz = new int[limit];
        Set<Long> visited = new HashSet<>(Math.min(limit * 2, 4096));

        int head = 0;
        int tail = 0;
        qx[tail] = startX;
        qy[tail] = startY;
        qz[tail] = startZ;
        tail++;
        visited.add(pack(startX, startY, startZ));

        while (head < tail) {
            int cx = qx[head];
            int cy = qy[head];
            int cz = qz[head];
            head++;

            for (int[] d : DIRS) {
                int nx = cx + d[0];
                int ny = cy + d[1];
                int nz = cz + d[2];
                if (nx == playerX && nz == playerZ && (ny == playerY || ny == playerY + 1)) {
                    return Result.REACHABLE;
                }
                long key = pack(nx, ny, nz);
                if (!visited.add(key)) {
                    continue;
                }
                CellType type = classifier.classify(nx, ny, nz);
                if (type == CellType.BARRIER) {
                    continue;
                }
                if (type == CellType.OPEN && ny > areaMaxY) {
                    return Result.REACHABLE;
                }
                if (tail >= limit) {
                    return Result.REACHABLE;
                }
                qx[tail] = nx;
                qy[tail] = ny;
                qz[tail] = nz;
                tail++;
            }
        }
        return new Result(true, qx, qy, qz, tail);
    }

    private static long pack(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((long) y & 0xFFFL);
    }
}
