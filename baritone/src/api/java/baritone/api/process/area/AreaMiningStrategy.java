/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.api.process.area;

/**
 * Ordering strategy for area mining excavation.
 */
public enum AreaMiningStrategy {
    /** Excavate from the highest remaining Y layer across the entire area downward. */
    GLOBAL_TOP_DOWN,
    /** Lock onto a local focus region around the nearest remaining peak/blocks, clear top-down, then move to the next region. */
    LOCAL_TOP_DOWN,
    /** Excavate the nearest non-empty 16x16 chunk inside the area from its top down until empty, then move to the next nearest chunk. */
    CHUNK_TOP_DOWN
}
