/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.api.process.area;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

public final class AreaMiningOptions {

    public static final AreaMiningOptions DEFAULT = new AreaMiningOptions(
            AreaMiningLiquidPolicy.SEAL_BOUNDARY,
            List.of(Blocks.NETHERRACK),
            Long.MAX_VALUE
    );

    private final AreaMiningLiquidPolicy liquidPolicy;
    private final List<Block> sealingBlocks;
    private final long blockLimit;
    private final List<Block> protectedBlocks;
    private final AreaMiningStrategy strategy;
    private final boolean targetBlocksOnly;
    private final List<Block> targetBlocks;

    public AreaMiningOptions(AreaMiningLiquidPolicy liquidPolicy) {
        this(liquidPolicy, List.of(Blocks.NETHERRACK), Long.MAX_VALUE, List.of(), AreaMiningStrategy.GLOBAL_TOP_DOWN, false, List.of());
    }

    public AreaMiningOptions(AreaMiningLiquidPolicy liquidPolicy, Collection<Block> sealingBlocks) {
        this(liquidPolicy, sealingBlocks, Long.MAX_VALUE, List.of(), AreaMiningStrategy.GLOBAL_TOP_DOWN, false, List.of());
    }

    public AreaMiningOptions(AreaMiningLiquidPolicy liquidPolicy, Collection<Block> sealingBlocks, long blockLimit) {
        this(liquidPolicy, sealingBlocks, blockLimit, List.of(), AreaMiningStrategy.GLOBAL_TOP_DOWN, false, List.of());
    }

    public AreaMiningOptions(AreaMiningLiquidPolicy liquidPolicy, Collection<Block> sealingBlocks, long blockLimit, Collection<Block> protectedBlocks) {
        this(liquidPolicy, sealingBlocks, blockLimit, protectedBlocks, AreaMiningStrategy.GLOBAL_TOP_DOWN, false, List.of());
    }

    public AreaMiningOptions(
            AreaMiningLiquidPolicy liquidPolicy,
            Collection<Block> sealingBlocks,
            long blockLimit,
            Collection<Block> protectedBlocks,
            AreaMiningStrategy strategy
    ) {
        this(liquidPolicy, sealingBlocks, blockLimit, protectedBlocks, strategy, false, List.of());
    }

    public AreaMiningOptions(
            AreaMiningLiquidPolicy liquidPolicy,
            Collection<Block> sealingBlocks,
            long blockLimit,
            Collection<Block> protectedBlocks,
            AreaMiningStrategy strategy,
            boolean targetBlocksOnly,
            Collection<Block> targetBlocks
    ) {
        this.liquidPolicy = Objects.requireNonNull(liquidPolicy, "liquidPolicy");
        Objects.requireNonNull(sealingBlocks, "sealingBlocks");
        Objects.requireNonNull(protectedBlocks, "protectedBlocks");
        Objects.requireNonNull(targetBlocks, "targetBlocks");
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        if (sealingBlocks.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Sealing blocks cannot contain null");
        }
        if (protectedBlocks.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Protected blocks cannot contain null");
        }
        if (targetBlocks.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Target blocks cannot contain null");
        }
        if (blockLimit < 0L) {
            throw new IllegalArgumentException("Block limit cannot be negative");
        }
        this.sealingBlocks = List.copyOf(sealingBlocks);
        this.blockLimit = blockLimit;
        this.protectedBlocks = List.copyOf(protectedBlocks);
        this.targetBlocksOnly = targetBlocksOnly;
        this.targetBlocks = List.copyOf(targetBlocks);
    }

    public AreaMiningLiquidPolicy liquidPolicy() {
        return liquidPolicy;
    }

    public List<Block> sealingBlocks() {
        return sealingBlocks;
    }

    public long blockLimit() {
        return blockLimit;
    }

    public List<Block> protectedBlocks() {
        return protectedBlocks;
    }

    public AreaMiningStrategy strategy() {
        return strategy;
    }

    public boolean targetBlocksOnly() {
        return targetBlocksOnly;
    }

    public List<Block> targetBlocks() {
        return targetBlocks;
    }
}
