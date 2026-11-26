/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.IAreaMineProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.process.area.AreaMiningLiquidPolicy;
import baritone.api.process.area.AreaMiningOptions;
import baritone.api.process.area.AreaMiningStatus;
import baritone.api.process.area.AreaMiningStrategy;
import baritone.api.process.area.AreaMiningStrategyPlanner;
import baritone.api.process.area.IColumnarArea;
import baritone.api.process.area.SealedPocketDetector;
import baritone.api.schematic.*;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.api.utils.*;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.PathingCommandContext;
import baritone.utils.schematic.MapArtSchematic;
import baritone.utils.schematic.SchematicSystem;
import baritone.utils.schematic.SelectionSchematic;
import baritone.utils.schematic.litematica.LitematicaHelper;
import baritone.utils.schematic.schematica.SchematicaHelper;
import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import baritone.api.utils.Tuple;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

public final class BuilderProcess extends BaritoneProcessHelper implements IBuilderProcess, IAreaMineProcess {

    private static final Set<Property<?>> ORIENTATION_PROPS =
            ImmutableSet.of(
                    RotatedPillarBlock.AXIS, HorizontalDirectionalBlock.FACING,
                    StairBlock.FACING, StairBlock.HALF, StairBlock.SHAPE,
                    PipeBlock.NORTH, PipeBlock.EAST, PipeBlock.SOUTH, PipeBlock.WEST, PipeBlock.UP,
                    TrapDoorBlock.OPEN, TrapDoorBlock.HALF
            );

    private HashSet<BetterBlockPos> incorrectPositions;
    private LongOpenHashSet observedCompleted; // positions that are completed even if they're out of render distance and we can't make sure right now
    private String name;
    private ISchematic realSchematic;
    private ISchematic schematic;
    private Vec3i origin;
    private int ticks;
    private boolean paused;
    private int layer;
    private int numRepeats;
    private List<BlockState> approxPlaceable;
    private IColumnarArea areaMiningArea;
    private AreaMiningOptions areaMiningOptions;
    private AreaMiningStatus.State areaMiningState = AreaMiningStatus.State.IDLE;
    private AreaMiningStatus.PauseReason areaMiningPauseReason = AreaMiningStatus.PauseReason.NONE;
    private long areaMiningKnownRemaining = -1L;
    private long areaMiningMinedBlocks;
    /** Unique positions mined this cycle; repeat-breaking the same blocks does not grow it. */
    private final java.util.Set<Long> areaMiningMinedPositions = new java.util.HashSet<>();
    /** Area positions observed solid this cycle (even outside active strategy window) so toBreakNearPlayer breaks are always counted. */
    private final LongOpenHashSet areaObservedSolid = new LongOpenHashSet();
    private boolean startingAreaMining;
    private boolean currentBuildIsAreaMining;
    /** Cooldowns unreachable placement targets so the builder cannot oscillate forever. */
    private final PlacementBackoff placementBackoff = new PlacementBackoff(3000, 5000);
    /** Protects throwaway pathing scaffolds from immediate re-mining and mined-progress inflation. */
    private static final long PATH_SCAFFOLD_COOLDOWN_MS = 30_000L;
    private final ScaffoldTracker scaffoldTracker = new ScaffoldTracker(PATH_SCAFFOLD_COOLDOWN_MS);
    private boolean builderPlacingThisTick;
    /** Unsealable liquid cells are parked briefly instead of freezing the whole area. */
    private final java.util.Map<Long, Long> deferredLiquids = new java.util.HashMap<>();
    /** Area-mining cells proven enclosed by protected whitelist blocks or repeatedly unreachable. */
    private final LongOpenHashSet areaUnreachablePositions = new LongOpenHashSet();
    private final java.util.Map<Long, Integer> areaPathFailCounts = new java.util.HashMap<>();
    private final java.util.Set<Block> areaProtectedSet = new java.util.HashSet<>();
    private boolean areaTargetBlocksOnly;
    private final java.util.Set<Block> areaTargetSet = new java.util.HashSet<>();
    private boolean areaHasActiveChunk;
    private int areaActiveChunkX;
    private int areaActiveChunkZ;
    private boolean areaHasLocalAnchor;
    private int areaLocalAnchorX;
    private int areaLocalAnchorZ;
    private int areaActiveGlobalTopY = Integer.MIN_VALUE;
    private long lastUnsealableLogMillis;
    public int stopAtHeight = 0;

    public BuilderProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void build(String name, ISchematic schematic, Vec3i origin) {
        if (!this.startingAreaMining && this.currentBuildIsAreaMining) {
            this.areaMiningState = AreaMiningStatus.State.CANCELLED;
            this.currentBuildIsAreaMining = false;
        }
        this.currentBuildIsAreaMining = this.startingAreaMining;
        this.name = name;
        this.schematic = schematic;
        this.realSchematic = null;
        boolean buildingSelectionSchematic = schematic instanceof SelectionSchematic;
        final boolean exactAreaMiningSchematic = schematic instanceof AreaMiningSchematic;
        if (!exactAreaMiningSchematic && !Baritone.settings().buildSubstitutes.value.isEmpty()) {
            this.schematic = new SubstituteSchematic(this.schematic, Baritone.settings().buildSubstitutes.value);
        }
        if (!exactAreaMiningSchematic && Baritone.settings().buildSchematicMirror.value != net.minecraft.world.level.block.Mirror.NONE) {
            this.schematic = new MirroredSchematic(this.schematic, Baritone.settings().buildSchematicMirror.value);
        }
        if (!exactAreaMiningSchematic && Baritone.settings().buildSchematicRotation.value != net.minecraft.world.level.block.Rotation.NONE) {
            this.schematic = new RotatedSchematic(this.schematic, Baritone.settings().buildSchematicRotation.value);
        }
        // TODO this preserves the old behavior, but maybe we should bake the setting value right here
        if (!exactAreaMiningSchematic) {
            this.schematic = new MaskSchematic(this.schematic) {
                @Override
                public boolean partOfMask(int x, int y, int z, BlockState current) {
                    // partOfMask is only called inside the schematic so desiredState is not null
                    return !Baritone.settings().buildSkipBlocks.value.contains(this.desiredState(x, y, z, current, Collections.emptyList()).getBlock());
                }
            };
        }
        int x = origin.getX();
        int y = origin.getY();
        int z = origin.getZ();
        if (!exactAreaMiningSchematic && Baritone.settings().schematicOrientationX.value) {
            x += schematic.widthX();
        }
        if (!exactAreaMiningSchematic && Baritone.settings().schematicOrientationY.value) {
            y += schematic.heightY();
        }
        if (!exactAreaMiningSchematic && Baritone.settings().schematicOrientationZ.value) {
            z += schematic.lengthZ();
        }
        this.origin = new Vec3i(x, y, z);
        this.paused = false;
        this.layer = Baritone.settings().startAtLayer.value;
        this.stopAtHeight = schematic.heightY();
        if (Baritone.settings().buildOnlySelection.value && buildingSelectionSchematic) {  // currently redundant but safer maybe
            if (baritone.getSelectionManager().getSelections().length == 0) {
                logDirect("Poor little kitten forgot to set a selection while BuildOnlySelection is true");
                this.stopAtHeight = 0;
            } else if (Baritone.settings().buildInLayers.value) {
                OptionalInt minim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.min().y).min();
                OptionalInt maxim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.max().y).max();
                if (minim.isPresent() && maxim.isPresent()) {
                    int startAtHeight = Baritone.settings().layerOrder.value ? y + schematic.heightY() - maxim.getAsInt() : minim.getAsInt() - y;
                    this.stopAtHeight = (Baritone.settings().layerOrder.value ? y + schematic.heightY() - minim.getAsInt() : maxim.getAsInt() - y) + 1;
                    this.layer = Math.max(this.layer, startAtHeight / Baritone.settings().layerHeight.value);  // startAtLayer or startAtHeight, whichever is highest
                    logDebug(String.format("Schematic starts at y=%s with height %s", y, schematic.heightY()));
                    logDebug(String.format("Selection starts at y=%s and ends at y=%s", minim.getAsInt(), maxim.getAsInt()));
                    logDebug(String.format("Considering relevant height %s - %s", startAtHeight, this.stopAtHeight));
                }
            }
        }

        this.numRepeats = 0;
        this.observedCompleted = new LongOpenHashSet();
        this.incorrectPositions = null;
    }

    public void resume() {
        paused = false;
        if (this.currentBuildIsAreaMining) {
            this.areaMiningState = AreaMiningStatus.State.RUNNING;
            this.areaMiningPauseReason = AreaMiningStatus.PauseReason.NONE;
        }
    }

    public void pause() {
        paused = true;
        if (this.currentBuildIsAreaMining) {
            this.areaMiningState = AreaMiningStatus.State.PAUSED;
            this.areaMiningPauseReason = AreaMiningStatus.PauseReason.MANUAL;
        }
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public boolean build(String name, File schematic, Vec3i origin) {
        Optional<ISchematicFormat> format = SchematicSystem.INSTANCE.getByFile(schematic);
        if (!format.isPresent()) {
            return false;
        }
        IStaticSchematic parsed;
        try {
            parsed = format.get().parse(new FileInputStream(schematic));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        ISchematic schem = applyMapArtAndSelection(origin, parsed);
        build(name, schem, origin);
        return true;
    }

    private ISchematic applyMapArtAndSelection(Vec3i origin, IStaticSchematic parsed) {
        ISchematic schematic = parsed;
        if (Baritone.settings().mapArtMode.value) {
            schematic = new MapArtSchematic(parsed);
        }
        if (Baritone.settings().buildOnlySelection.value) {
            schematic = new SelectionSchematic(schematic, origin, baritone.getSelectionManager().getSelections());
        }
        return schematic;
    }

    @Override
    public void buildOpenSchematic() {
        if (SchematicaHelper.isSchematicaPresent()) {
            Optional<Tuple<IStaticSchematic, BlockPos>> schematic = SchematicaHelper.getOpenSchematic();
            if (schematic.isPresent()) {
                IStaticSchematic raw = schematic.get().getA();
                BlockPos origin = schematic.get().getB();
                ISchematic schem = applyMapArtAndSelection(origin, raw);
                this.build(raw.toString(), schem, origin);
            } else {
                logDirect("No schematic currently open");
            }
        } else {
            logDirect("Schematica is not present");
        }
    }

    @Override
    public void buildOpenLitematic(int i) {
        if (LitematicaHelper.isLitematicaPresent()) {
            //if java.lang.NoSuchMethodError is thrown see comment in SchematicPlacementManager
            if (LitematicaHelper.hasLoadedSchematic(i)) {
                Tuple<IStaticSchematic, Vec3i> schematic = LitematicaHelper.getSchematic(i);
                Vec3i correctedOrigin = schematic.getB();
                ISchematic schematic2 = applyMapArtAndSelection(correctedOrigin, schematic.getA());
                build(schematic.getA().toString(), schematic2, correctedOrigin);
            } else {
                logDirect(String.format("List of placements has no entry %s", i + 1));
            }
        } else {
            logDirect("Litematica is not present");
        }
    }

    public void clearArea(BlockPos corner1, BlockPos corner2) {
        BlockPos origin = new BlockPos(Math.min(corner1.getX(), corner2.getX()), Math.min(corner1.getY(), corner2.getY()), Math.min(corner1.getZ(), corner2.getZ()));
        int widthX = Math.abs(corner1.getX() - corner2.getX()) + 1;
        int heightY = Math.abs(corner1.getY() - corner2.getY()) + 1;
        int lengthZ = Math.abs(corner1.getZ() - corner2.getZ()) + 1;
        build("clear area", new FillSchematic(widthX, heightY, lengthZ, Blocks.AIR.defaultBlockState()), origin);
    }

    @Override
    public void mineArea(IColumnarArea area, AreaMiningOptions options) {
        mineArea(area, options, false);
    }

    @Override
    public void mineArea(IColumnarArea area, AreaMiningOptions options, boolean preserveDeferrals) {
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(options, "options");
        if (ctx.world() == null) {
            throw new IllegalStateException("Cannot start area mining without a loaded world");
        }
        final int worldMinY = ctx.world().dimensionType().minY();
        final int worldMaxY = worldMinY + ctx.world().dimensionType().height() - 1;
        if (area.minY() < worldMinY || area.maxY() > worldMaxY) {
            throw new IllegalArgumentException("Area Y range must be within " + worldMinY + " and " + worldMaxY);
        }
        if (area.minX() > area.maxX() || area.minZ() > area.maxZ() || area.minY() > area.maxY()) {
            throw new IllegalArgumentException("Area bounds are inverted");
        }
        if (this.currentBuildIsAreaMining) {
            this.areaMiningState = AreaMiningStatus.State.CANCELLED;
        }
        this.areaMiningArea = area;
        this.areaMiningOptions = options;
        this.areaMiningState = AreaMiningStatus.State.RUNNING;
        this.areaMiningPauseReason = AreaMiningStatus.PauseReason.NONE;
        this.areaMiningKnownRemaining = -1L;
        this.areaMiningMinedBlocks = 0L;
        this.areaMiningMinedPositions.clear();
        this.areaObservedSolid.clear();
        if (!preserveDeferrals) {
            this.deferredLiquids.clear();
            this.areaUnreachablePositions.clear();
            this.areaPathFailCounts.clear();
            this.scaffoldTracker.clear();
        }
        this.areaProtectedSet.clear();
        this.areaProtectedSet.addAll(options.protectedBlocks());
        this.areaTargetBlocksOnly = options.targetBlocksOnly();
        this.areaTargetSet.clear();
        this.areaTargetSet.addAll(options.targetBlocks());
        this.areaHasActiveChunk = false;
        this.areaHasLocalAnchor = false;
        this.areaActiveGlobalTopY = Integer.MIN_VALUE;
        AreaMiningSchematic areaSchematic = new AreaMiningSchematic(area, options, Baritone.settings().blocksToDisallowBreaking.value);
        this.startingAreaMining = true;
        try {
            build("mine area", areaSchematic, areaSchematic.origin());
        } finally {
            this.startingAreaMining = false;
        }
    }

    @Override
    public void deferCurrentTargets(long cooldownMillis) {
        if (!this.currentBuildIsAreaMining || cooldownMillis <= 0L) {
            return;
        }
        long nowMillis = System.currentTimeMillis();
        if (this.incorrectPositions != null && !this.incorrectPositions.isEmpty()) {
            BetterBlockPos feet = ctx.player() == null ? null : ctx.playerFeet();
            boolean smallBatch = this.incorrectPositions.size() <= 12;
            for (BetterBlockPos pos : this.incorrectPositions) {
                long key = BetterBlockPos.longHash(pos);
                boolean nearStall = smallBatch || feet == null || pos.distSqr(feet) <= 196.0;
                if (nearStall && this.areaMiningArea != null && this.areaMiningArea.contains(pos)) {
                    int fails = this.areaPathFailCounts.merge(key, 1, Integer::sum);
                    long duration = AreaMiningStrategyPlanner.escalatingDeferralMillis(fails, cooldownMillis);
                    if (duration < 0L) {
                        this.areaUnreachablePositions.add(key);
                        this.deferredLiquids.remove(key);
                    } else {
                        this.deferredLiquids.merge(key, nowMillis + duration, Math::max);
                    }
                } else {
                    this.deferredLiquids.merge(key, nowMillis + cooldownMillis, Math::max);
                }
            }
            this.incorrectPositions = null;
        }
        this.areaHasLocalAnchor = false;
        this.areaHasActiveChunk = false;
    }

    @Override
    public AreaMiningStatus getAreaMiningStatus() {
        if (this.currentBuildIsAreaMining && this.incorrectPositions != null) {
            this.areaMiningKnownRemaining = this.incorrectPositions.stream()
                    .filter(this.areaMiningArea::contains)
                    .count();
        }
        final long total = this.areaMiningArea == null ? 0L : this.areaMiningArea.estimatedBlockCount();
        final long blockLimit = this.areaMiningOptions == null ? Long.MAX_VALUE : this.areaMiningOptions.blockLimit();
        return new AreaMiningStatus(this.areaMiningState, total, this.areaMiningKnownRemaining, this.areaMiningPauseReason,
                this.areaMiningMinedBlocks, blockLimit, this.areaMiningMinedPositions.size());
    }

    @Override
    public List<BlockState> getApproxPlaceable() {
        return new ArrayList<>(approxPlaceable);
    }

    @Override
    public boolean isActive() {
        return schematic != null;
    }

    public BlockState placeAt(int x, int y, int z, BlockState current) {
        if (!isActive()) {
            return null;
        }
        if (!schematic.inSchematic(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current)) {
            return null;
        }
        BlockState state = schematic.desiredState(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current, this.approxPlaceable);
        if (state.getBlock() instanceof AirBlock) {
            return null;
        }
        return state;
    }

    private Optional<Tuple<BetterBlockPos, Rotation>> toBreakNearPlayer(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        BetterBlockPos pathStart = baritone.getPathingBehavior().pathStart();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = Baritone.settings().breakFromAbove.value ? -1 : 0; dy <= 5; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    if (dy == -1 && x == pathStart.x && z == pathStart.z) {
                        continue; // dont mine what we're supported by, but not directly standing on
                    }
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        continue; // irrelevant
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    if (!(curr.getBlock() instanceof AirBlock) && !(curr.getBlock() == Blocks.WATER || curr.getBlock() == Blocks.LAVA) && !valid(curr, desired, false)) {
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        if (this.currentBuildIsAreaMining && isDeferred(pos)) {
                            continue;
                        }
                        if (this.currentBuildIsAreaMining && MovementHelper.avoidBreakingDueToLiquid(bcc.bsi, x, y, z)
                                && !isTemporaryBoundarySeal(pos, curr, bcc.bsi)) {
                            continue;
                        }
                        Optional<Rotation> rot = RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance());
                        if (rot.isPresent()) {
                            if (this.currentBuildIsAreaMining && this.areaMiningArea != null && this.areaMiningArea.contains(pos)) {
                                this.areaObservedSolid.add(BetterBlockPos.longHash(pos));
                            }
                            return Optional.of(new Tuple<>(pos, rot.get()));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    public static class Placement {

        private final int hotbarSelection;
        private final BlockPos placeAgainst;
        private final Direction side;
        private final Rotation rot;
        /** True when the click is synthesized without line of sight (printer-style through-wall fill). */
        private final boolean throughWall;

        public Placement(int hotbarSelection, BlockPos placeAgainst, Direction side, Rotation rot) {
            this(hotbarSelection, placeAgainst, side, rot, false);
        }

        public Placement(int hotbarSelection, BlockPos placeAgainst, Direction side, Rotation rot, boolean throughWall) {
            this.hotbarSelection = hotbarSelection;
            this.placeAgainst = placeAgainst;
            this.side = side;
            this.rot = rot;
            this.throughWall = throughWall;
        }
    }

    private Optional<Placement> searchForPlacables(BuilderCalculationContext bcc, List<BlockState> desirableOnHotbar) {
        BetterBlockPos center = ctx.playerFeet();
        long now = System.currentTimeMillis();
        if (this.currentBuildIsAreaMining && this.areaMiningOptions.liquidPolicy() == AreaMiningLiquidPolicy.SEAL_BOUNDARY) {
            for (int pass = 0; pass < 2; pass++) {
            boolean allowFlowing = pass == 1;
            for (int dx = -5; dx <= 5; dx++) {
                for (int dy = -5; dy <= 1; dy++) {
                    for (int dz = -5; dz <= 5; dz++) {
                        BetterBlockPos pos = new BetterBlockPos(center.x + dx, center.y + dy, center.z + dz);
                        if (!placementBackoff.shouldAttempt(pos, now)) {
                            continue;
                        }
                        BlockState current = bcc.bsi.get0(pos);
                        if (!isSideBoundaryLiquid(pos, current)) {
                            continue;
                        }
                        if (!allowFlowing && !current.getFluidState().isSource()) {
                            // Still sources are sealed first; a second pass allows flowing cells so an
                            // otherwise untargetable cell cannot freeze the whole area.
                            continue;
                        }
                        BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, current);
                        if (desired == null) {
                            continue;
                        }
                        desirableOnHotbar.add(desired);
                        Optional<Placement> placement = possibleToPlace(desired, pos.x, pos.y, pos.z, bcc.bsi);
                        if (placement.isPresent()) {
                            return placement;
                        }
                    }
                }
            }
            }
        }
        for (int pass = 0; pass < 2; pass++) {
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 1; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    if (!placementBackoff.shouldAttempt(new BetterBlockPos(x, y, z), now)) {
                        continue;
                    }
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        continue; // irrelevant
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    if (MovementHelper.isReplaceable(x, y, z, curr, bcc.bsi) && !valid(curr, desired, false)) {
                        if (dy == 1 && bcc.bsi.get0(x, y + 1, z).getBlock() instanceof AirBlock) {
                            continue;
                        }
                        // Printer-style creeping fill: prefer cells backed by at least two solid faces so
                        // large water bodies fill outward from walls instead of stranding isolated cells.
                        if (pass == 0 && solidNeighbourCount(x, y, z, bcc) < 2) {
                            continue;
                        }
                        // Flowing cells stay placeable here; the dedicated seal pass already prefers still sources.
                        desirableOnHotbar.add(desired);
                        Optional<Placement> opt = possibleToPlace(desired, x, y, z, bcc.bsi);
                        if (opt.isPresent()) {
                            return opt;
                        }
                        if (pass == 1) {
                            Optional<Placement> beyond = possibleToPlaceThroughWall(desired, x, y, z, bcc.bsi);
                            if (beyond.isPresent()) {
                                return beyond;
                            }
                        }
                    }
                }
            }
        }
        }
        return Optional.empty();
    }

    private int solidNeighbourCount(int x, int y, int z, BuilderCalculationContext bcc) {
        int count = 0;
        for (Direction direction : Direction.values()) {
            BetterBlockPos neighbour = new BetterBlockPos(x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ());
            if (!MovementHelper.isReplaceable(neighbour.x, neighbour.y, neighbour.z, bcc.bsi.get0(neighbour), bcc.bsi)) count++;
        }
        return count;
    }

    public boolean placementPlausible(BlockPos pos, BlockState state) {
        VoxelShape voxelshape = state.getCollisionShape(ctx.world(), pos);
        return voxelshape.isEmpty() || ctx.world().isUnobstructed(null, voxelshape.move(pos.getX(), pos.getY(), pos.getZ()));
    }

    private Optional<Placement> possibleToPlace(BlockState toPlace, int x, int y, int z, BlockStateInterface bsi) {
        for (Direction against : Direction.values()) {
            BetterBlockPos placeAgainstPos = new BetterBlockPos(x, y, z).relative(against);
            BlockState placeAgainstState = bsi.get0(placeAgainstPos);
            if (MovementHelper.isReplaceable(placeAgainstPos.x, placeAgainstPos.y, placeAgainstPos.z, placeAgainstState, bsi)) {
                continue;
            }
            if (!toPlace.canSurvive(ctx.world(), new BetterBlockPos(x, y, z))) {
                continue;
            }
            if (!placementPlausible(new BetterBlockPos(x, y, z), toPlace)) {
                continue;
            }
            VoxelShape shape = placeAgainstState.getShape(ctx.world(), placeAgainstPos);
            if (shape.isEmpty()) {
                continue;
            }
            AABB aabb = shape.bounds();
            for (Vec3 placementMultiplier : aabbSideMultipliers(against)) {
                double placeX = placeAgainstPos.x + aabb.minX * placementMultiplier.x + aabb.maxX * (1 - placementMultiplier.x);
                double placeY = placeAgainstPos.y + aabb.minY * placementMultiplier.y + aabb.maxY * (1 - placementMultiplier.y);
                double placeZ = placeAgainstPos.z + aabb.minZ * placementMultiplier.z + aabb.maxZ * (1 - placementMultiplier.z);
                Rotation rot = RotationUtils.calcRotationFromVec3d(RayTraceUtils.inferSneakingEyePosition(ctx.player()), new Vec3(placeX, placeY, placeZ), ctx.playerRotations());
                Rotation actualRot = baritone.getLookBehavior().getAimProcessor().peekRotation(rot);
                HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), actualRot, ctx.playerController().getBlockReachDistance(), true);
                if (result != null && result.getType() == HitResult.Type.BLOCK && ((BlockHitResult) result).getBlockPos().equals(placeAgainstPos) && ((BlockHitResult) result).getDirection() == against.getOpposite()) {
                    OptionalInt hotbar = hasAnyItemThatWouldPlace(toPlace, result, actualRot);
                    if (hotbar.isPresent()) {
                        return Optional.of(new Placement(hotbar.getAsInt(), placeAgainstPos, against.getOpposite(), rot));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Printer-style placement: the target can be filled while the supporting face is behind another
     * block, so large water bodies can be sealed from inside a wall instead of walking around it.
     */
    private Optional<Placement> possibleToPlaceThroughWall(BlockState toPlace, int x, int y, int z, BlockStateInterface bsi) {
        if (!this.currentBuildIsAreaMining) {
            return Optional.empty();
        }
        BetterBlockPos target = new BetterBlockPos(x, y, z);
        net.minecraft.world.phys.Vec3 eye = ctx.player().getEyePosition();
        double reach = 4.5;
        for (Direction against : Direction.values()) {
            BetterBlockPos support = target.relative(against);
            BlockState supportState = bsi.get0(support);
            if (MovementHelper.isReplaceable(support.x, support.y, support.z, supportState, bsi)) {
                continue;
            }
            Direction face = against.getOpposite();
            net.minecraft.world.phys.Vec3 hitVec = net.minecraft.world.phys.Vec3.atCenterOf(support)
                    .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            if (eye.distanceToSqr(hitVec) > reach * reach) {
                continue;
            }
            BlockHitResult hit = new BlockHitResult(hitVec, face, support, false);
            OptionalInt hotbar = hasAnyItemThatWouldPlace(toPlace, hit, new Rotation(ctx.player().getYRot(), 0.0F));
            if (hotbar.isPresent()) {
                return Optional.of(new Placement(hotbar.getAsInt(), support, face, new Rotation(ctx.player().getYRot(), 0.0F), true));
            }
        }
        return Optional.empty();
    }

    private OptionalInt hasAnyItemThatWouldPlace(BlockState desired, HitResult result, Rotation rot) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            float originalYaw = ctx.player().getYRot();
            float originalPitch = ctx.player().getXRot();
            // the state depends on the facing of the player sometimes
            ctx.player().setYRot(rot.getYaw());
            ctx.player().setXRot(rot.getPitch());
            BlockPlaceContext meme = new BlockPlaceContext(new UseOnContext(
                    ctx.world(),
                    ctx.player(),
                    InteractionHand.MAIN_HAND,
                    stack,
                    (BlockHitResult) result
            ) {}); // that {} gives us access to a protected constructor lmfao
            BlockState wouldBePlaced = ((BlockItem) stack.getItem()).getBlock().getStateForPlacement(meme);
            ctx.player().setYRot(originalYaw);
            ctx.player().setXRot(originalPitch);
            if (wouldBePlaced == null) {
                continue;
            }
            if (!meme.canPlace()) {
                continue;
            }
            if (valid(wouldBePlaced, desired, true)) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    private static Vec3[] aabbSideMultipliers(Direction side) {
        switch (side) {
            case UP:
                return new Vec3[]{new Vec3(0.5, 1, 0.5), new Vec3(0.1, 1, 0.5), new Vec3(0.9, 1, 0.5), new Vec3(0.5, 1, 0.1), new Vec3(0.5, 1, 0.9)};
            case DOWN:
                return new Vec3[]{new Vec3(0.5, 0, 0.5), new Vec3(0.1, 0, 0.5), new Vec3(0.9, 0, 0.5), new Vec3(0.5, 0, 0.1), new Vec3(0.5, 0, 0.9)};
            case NORTH:
            case SOUTH:
            case EAST:
            case WEST:
                double x = side.getStepX() == 0 ? 0.5 : (1 + side.getStepX()) / 2D;
                double z = side.getStepZ() == 0 ? 0.5 : (1 + side.getStepZ()) / 2D;
                return new Vec3[]{new Vec3(x, 0.25, z), new Vec3(x, 0.75, z)};
            default: // null
                throw new IllegalStateException("Unexpected side " + side);
        }
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        return onTick(calcFailed, isSafeToCancel, 0);
    }

    private PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel, int recursions) {
        if (recursions > 100) { // onTick calls itself, don't crash
            return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
        }
        if (recursions == 0) {
            this.builderPlacingThisTick = false;
            if (this.currentBuildIsAreaMining && baritone.getPathingBehavior().isPathing()) {
                refreshNearbyPathScaffolds(ctx.playerFeet());
            }
        }
        approxPlaceable = approxPlaceable(36);
        if (baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)) {
            ticks = 5;
        } else {
            ticks--;
        }
        baritone.getInputOverrideHandler().clearAllKeys();
        if (paused) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (Baritone.settings().buildInLayers.value && !this.currentBuildIsAreaMining) {
            if (realSchematic == null) {
                realSchematic = schematic;
            }
            ISchematic realSchematic = this.realSchematic; // wrap this properly, dont just have the inner class refer to the builderprocess.this
            int minYInclusive;
            int maxYInclusive;
            // layer = 0 should be nothing
            // layer = realSchematic.heightY() should be everything
            if (Baritone.settings().layerOrder.value) { // top to bottom
                maxYInclusive = realSchematic.heightY() - 1;
                minYInclusive = realSchematic.heightY() - layer * Baritone.settings().layerHeight.value;
            } else {
                maxYInclusive = layer * Baritone.settings().layerHeight.value - 1;
                minYInclusive = 0;
            }
            schematic = new ISchematic() {
                @Override
                public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
                    return realSchematic.desiredState(x, y, z, current, BuilderProcess.this.approxPlaceable);
                }

                @Override
                public boolean inSchematic(int x, int y, int z, BlockState currentState) {
                    return ISchematic.super.inSchematic(x, y, z, currentState) && y >= minYInclusive && y <= maxYInclusive && realSchematic.inSchematic(x, y, z, currentState);
                }

                @Override
                public void reset() {
                    realSchematic.reset();
                }

                @Override
                public int widthX() {
                    return realSchematic.widthX();
                }

                @Override
                public int heightY() {
                    return realSchematic.heightY();
                }

                @Override
                public int lengthZ() {
                    return realSchematic.lengthZ();
                }
            };
        }
        BuilderCalculationContext bcc = new BuilderCalculationContext();
        if (calcFailed && this.currentBuildIsAreaMining) {
            if (deferBlockedAreaTargets(bcc)) {
                return onTick(false, isSafeToCancel, recursions + 1);
            }
            this.paused = true;
            this.areaMiningState = AreaMiningStatus.State.PAUSED;
            this.areaMiningPauseReason = hasLiquidTargets(bcc) && !hasConfiguredSealingBlock()
                    ? AreaMiningStatus.PauseReason.NO_SEALING_BLOCKS
                    : AreaMiningStatus.PauseReason.PATHING_FAILED;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        final boolean hasWork = recalc(bcc);
        if (this.currentBuildIsAreaMining && hasWork && this.areaMiningMinedBlocks >= this.areaMiningOptions.blockLimit()) {
            this.paused = true;
            this.areaMiningState = AreaMiningStatus.State.PAUSED;
            this.areaMiningPauseReason = AreaMiningStatus.PauseReason.BLOCK_LIMIT_REACHED;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (!hasWork) {
            if (Baritone.settings().buildInLayers.value && !this.currentBuildIsAreaMining && layer * Baritone.settings().layerHeight.value < stopAtHeight) {
                logDirect("Starting layer " + layer);
                layer++;
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
            Vec3i repeat = this.currentBuildIsAreaMining ? new Vec3i(0, 0, 0) : Baritone.settings().buildRepeat.value;
            int max = this.currentBuildIsAreaMining ? 1 : Baritone.settings().buildRepeatCount.value;
            numRepeats++;
            if (repeat.equals(new Vec3i(0, 0, 0)) || (max != -1 && numRepeats >= max)) {
                logDirect("Done building");
                if (Baritone.settings().notificationOnBuildFinished.value) {
                    logNotification("Done building", false);
                }
                if (this.currentBuildIsAreaMining) {
                    this.areaMiningState = AreaMiningStatus.State.COMPLETE;
                    this.areaMiningPauseReason = AreaMiningStatus.PauseReason.NONE;
                    this.areaMiningKnownRemaining = 0L;
                    this.currentBuildIsAreaMining = false;
                }
                onLostControl();
                return null;
            }
            // build repeat time
            layer = 0;
            origin = new BlockPos(origin).offset(repeat);
            if (!Baritone.settings().buildRepeatSneaky.value) {
                schematic.reset();
            }
            logDirect("Repeating build in vector " + repeat + ", new origin is " + origin);
            return onTick(calcFailed, isSafeToCancel, recursions + 1);
        }
        if (Baritone.settings().distanceTrim.value && !this.currentBuildIsAreaMining) {
            trim();
        }

        Optional<Tuple<BetterBlockPos, Rotation>> toBreak = toBreakNearPlayer(bcc);
        if (toBreak.isPresent() && isSafeToCancel && (ctx.player().onGround() || ctx.player().getAbilities().flying)) {
            // we'd like to pause to break this block
            // only change look direction if it's safe (don't want to fuck up an in progress parkour for example
            Rotation rot = toBreak.get().getB();
            BetterBlockPos pos = toBreak.get().getA();
            baritone.getLookBehavior().updateTarget(rot, true);
            MovementHelper.switchToBestToolFor(ctx, bcc.get(pos));
            if (ctx.player().isCrouching()) {
                // really horrible bug where a block is visible for breaking while sneaking but not otherwise
                // so you can't see it, it goes to place something else, sneaks, then the next tick it tries to break
                // and is unable since it's unsneaked in the intermediary tick
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            }
            boolean selectedForbidden = ctx.getSelectedBlock().map(this::isBlockBreakForbidden).orElse(false);
            if (!selectedForbidden && (ctx.isLookingAt(pos) || ctx.playerRotations().isReallyCloseTo(rot))) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        List<BlockState> desirableOnHotbar = new ArrayList<>();
        Optional<Placement> toPlace = searchForPlacables(bcc, desirableOnHotbar);
        // Swimming placement is only enabled for area-mining liquid sealing: searchForPlacables
        // returns a placement only after a verified support-face ray hit, so a floating click
        // cannot misplace into the player's own cell.
        boolean canExecutePlacement = ctx.player().onGround()
                || ctx.player().getAbilities().flying
                || (this.currentBuildIsAreaMining
                    && this.areaMiningOptions.liquidPolicy() != AreaMiningLiquidPolicy.AVOID
                    && ctx.player().isInWater());
        if (toPlace.isPresent() && toPlace.get().throughWall && isSafeToCancel && canExecutePlacement && ticks <= 0) {
            Placement beyond = toPlace.get();
            placementBackoff.recordAttempt(
                    new BetterBlockPos(beyond.placeAgainst).relative(beyond.side),
                    System.currentTimeMillis());
            ctx.player().getInventory().setSelectedSlot(beyond.hotbarSelection);
            ctx.playerController().syncHeldItem();
            net.minecraft.world.phys.Vec3 hitVec = net.minecraft.world.phys.Vec3.atCenterOf(beyond.placeAgainst)
                    .add(beyond.side.getStepX() * 0.5, beyond.side.getStepY() * 0.5, beyond.side.getStepZ() * 0.5);
            ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(hitVec, beyond.side, beyond.placeAgainst, false));
            ticks = 0;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (toPlace.isPresent() && isSafeToCancel && canExecutePlacement && ticks <= 0) {
            placementBackoff.recordAttempt(
                    new BetterBlockPos(toPlace.get().placeAgainst).relative(toPlace.get().side),
                    System.currentTimeMillis());
            Rotation rot = toPlace.get().rot;
            baritone.getLookBehavior().updateTarget(rot, true);
            ctx.player().getInventory().setSelectedSlot(toPlace.get().hotbarSelection);
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            if ((ctx.isLookingAt(toPlace.get().placeAgainst) && ((BlockHitResult) ctx.objectMouseOver()).getDirection().equals(toPlace.get().side)) || ctx.playerRotations().isReallyCloseTo(rot)) {
                this.builderPlacingThisTick = true;
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (Baritone.settings().allowInventory.value) {
            ArrayList<Integer> usefulSlots = new ArrayList<>();
            List<BlockState> noValidHotbarOption = new ArrayList<>();
            outer:
            for (BlockState desired : desirableOnHotbar) {
                for (int i = 0; i < 9; i++) {
                    if (valid(approxPlaceable.get(i), desired, true)) {
                        usefulSlots.add(i);
                        continue outer;
                    }
                }
                noValidHotbarOption.add(desired);
            }

            outer:
            for (int i = 9; i < 36; i++) {
                for (BlockState desired : noValidHotbarOption) {
                    if (valid(approxPlaceable.get(i), desired, true)) {
                        if (!baritone.getInventoryBehavior().attemptToPutOnHotbar(i, usefulSlots::contains)) {
                            // awaiting inventory move, so pause
                            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                        }
                        break outer;
                    }
                }
            }
        }

        Goal goal = assemble(bcc, approxPlaceable.subList(0, 9));
        if (goal == null) {
            goal = assemble(bcc, approxPlaceable, true); // we're far away, so assume that we have our whole inventory to recalculate placeable properly
            if (goal == null && this.currentBuildIsAreaMining && recursions == 0) {
                // Local/chunk window or nearby scan may hold only unactionable liquids while dry blocks remain elsewhere.
                this.areaHasActiveChunk = false;
                this.areaHasLocalAnchor = false;
                this.areaActiveGlobalTopY = Integer.MIN_VALUE;
                this.incorrectPositions = null;
                if (recalc(bcc)) {
                    goal = assemble(bcc, approxPlaceable.subList(0, 9));
                    if (goal == null) {
                        goal = assemble(bcc, approxPlaceable, true);
                    }
                } else {
                    return onTick(false, isSafeToCancel, recursions + 1);
                }
            }
            if (goal == null) {
                if (Baritone.settings().skipFailedLayers.value && Baritone.settings().buildInLayers.value && !this.currentBuildIsAreaMining && layer * Baritone.settings().layerHeight.value < realSchematic.heightY()) {
                    logDirect("Skipping layer that I cannot construct! Layer #" + layer);
                    layer++;
                    return onTick(calcFailed, isSafeToCancel, recursions + 1);
                }
                logDirect("Unable to do it. Pausing. resume to resume, cancel to cancel");
                paused = true;
                if (this.currentBuildIsAreaMining) {
                    this.areaMiningState = AreaMiningStatus.State.PAUSED;
                    this.areaMiningPauseReason = hasLiquidTargets(bcc) && !hasConfiguredSealingBlock()
                            ? AreaMiningStatus.PauseReason.NO_SEALING_BLOCKS
                            : AreaMiningStatus.PauseReason.PATHING_FAILED;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        PathingCommandType commandType = this.currentBuildIsAreaMining
                ? PathingCommandType.REVALIDATE_GOAL_AND_PATH
                : PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH;
        return new PathingCommandContext(goal, commandType, bcc);
    }

    private boolean hasLiquidTargets(BuilderCalculationContext bcc) {
        return this.incorrectPositions != null && this.incorrectPositions.stream()
                .anyMatch(pos -> !bcc.bsi.get0(pos).getFluidState().isEmpty());
    }

    private boolean hasConfiguredSealingBlock() {
        return this.approxPlaceable.stream()
                .anyMatch(state -> this.areaMiningOptions.sealingBlocks().contains(state.getBlock()));
    }

    private boolean recalc(BuilderCalculationContext bcc) {
        if (incorrectPositions == null) {
            incorrectPositions = new HashSet<>();
            fullRecalc(bcc);
            if (incorrectPositions.isEmpty()) {
                return false;
            }
        }
        recalcNearby(bcc);
        if (incorrectPositions.isEmpty()) {
            fullRecalc(bcc);
        }
        return !incorrectPositions.isEmpty();
    }

    private void trim() {
        HashSet<BetterBlockPos> copy = new HashSet<>(incorrectPositions);
        copy.removeIf(pos -> pos.distSqr(ctx.player().blockPosition()) > 200);
        if (!copy.isEmpty()) {
            incorrectPositions = copy;
        }
    }

    private void recalcNearby(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        int radius = Baritone.settings().builderTickScanRadius.value;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState current = bcc.bsi.get0(x, y, z);
                    BlockState desired = bcc.getSchematic(x, y, z, current);
                    if (desired != null) {
                        // we care about this position
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        if (isDeferred(pos)) {
                            incorrectPositions.remove(pos);
                            continue;
                        }
                        boolean wasIncorrect = incorrectPositions.contains(pos);
                        long posKey = BetterBlockPos.longHash(pos);
                        if (valid(current, desired, false)) {
                            boolean wasScaffold = this.currentBuildIsAreaMining && this.scaffoldTracker.consumeCycleScaffold(posKey);
                            boolean wasKnownSolid = wasIncorrect || this.areaObservedSolid.remove(posKey);
                            if (this.currentBuildIsAreaMining && wasKnownSolid && !wasScaffold && this.areaMiningArea.contains(pos)
                                    && current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) {
                                this.areaMiningMinedBlocks++;
                                this.areaMiningMinedPositions.add(posKey);
                            }
                            incorrectPositions.remove(pos);
                            observedCompleted.add(posKey);
                        } else if (!this.currentBuildIsAreaMining || isWithinActiveStrategyWindow(pos)) {
                            if (this.currentBuildIsAreaMining && this.areaMiningArea.contains(pos)
                                    && !(current.getBlock() instanceof AirBlock) && !(current.getBlock() instanceof LiquidBlock)) {
                                this.areaObservedSolid.add(posKey);
                            }
                            incorrectPositions.add(pos);
                            observedCompleted.remove(posKey);
                        } else {
                            if (this.areaMiningArea.contains(pos)
                                    && !(current.getBlock() instanceof AirBlock) && !(current.getBlock() instanceof LiquidBlock)) {
                                this.areaObservedSolid.add(posKey);
                                observedCompleted.remove(posKey);
                            }
                            incorrectPositions.remove(pos);
                        }
                    } else {
                        incorrectPositions.remove(new BetterBlockPos(x, y, z));
                    }
                }
            }
        }
    }

    private void fullRecalc(BuilderCalculationContext bcc) {
        incorrectPositions = new HashSet<>();
        int maxIncorrect = Baritone.settings().incorrectSize.value;
        int yStart = this.currentBuildIsAreaMining ? schematic.heightY() - 1 : 0;
        if (this.currentBuildIsAreaMining) {
            fullRecalcAreaMining(bcc, maxIncorrect);
            return;
        }
        int yEnd = schematic.heightY();
        int yStep = 1;
        for (int y = yStart; y != yEnd; y += yStep) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    int blockX = x + origin.getX();
                    int blockY = y + origin.getY();
                    int blockZ = z + origin.getZ();
                    BlockState current = bcc.bsi.get0(blockX, blockY, blockZ);
                    if (!schematic.inSchematic(x, y, z, current)) {
                        continue;
                    }
                    if (bcc.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                        // we can directly observe this block, it is in render distance
                        if (valid(current, schematic.desiredState(x, y, z, current, this.approxPlaceable), false)) {
                            observedCompleted.add(BetterBlockPos.longHash(blockX, blockY, blockZ));
                        } else if (!isDeferred(new BetterBlockPos(blockX, blockY, blockZ))) {
                            incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
                            observedCompleted.remove(BetterBlockPos.longHash(blockX, blockY, blockZ));
                            if (incorrectPositions.size() > maxIncorrect) {
                                return;
                            }
                        }
                        continue;
                    }
                    // this is not in render distance
                    if (!observedCompleted.contains(BetterBlockPos.longHash(blockX, blockY, blockZ))) {
                        // and we've never seen this position be correct
                        // therefore mark as incorrect
                        incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
                        if (incorrectPositions.size() > maxIncorrect) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private void fullRecalcAreaMining(BuilderCalculationContext bcc, int maxIncorrect) {
        fullRecalcAreaMiningPass(bcc, maxIncorrect);
        if (incorrectPositions.isEmpty() && this.scaffoldTracker.releaseAllActive()) {
            this.areaHasActiveChunk = false;
            this.areaHasLocalAnchor = false;
            this.areaActiveGlobalTopY = Integer.MIN_VALUE;
            fullRecalcAreaMiningPass(bcc, maxIncorrect);
        }
    }

    private void fullRecalcAreaMiningPass(BuilderCalculationContext bcc, int maxIncorrect) {
        boolean hasSealing = hasConfiguredSealingBlock();
        BetterBlockPos playerFeet = ctx.playerFeet();
        List<BetterBlockPos> fallback = new ArrayList<>();
        AreaMiningStrategy strategy = this.areaMiningOptions.strategy();
        int margin = this.areaMiningOptions.liquidPolicy() == AreaMiningLiquidPolicy.SEAL_BOUNDARY ? 1 : 0;

        if (strategy == AreaMiningStrategy.CHUNK_TOP_DOWN) {
            if (this.areaHasActiveChunk) {
                if (scanAreaSubBox(bcc,
                        (this.areaActiveChunkX << 4) - margin, (this.areaActiveChunkX << 4) + 15 + margin,
                        (this.areaActiveChunkZ << 4) - margin, (this.areaActiveChunkZ << 4) + 15 + margin,
                        maxIncorrect, hasSealing, fallback, playerFeet)) {
                    return;
                }
                // Only fall back to unactionable cells (e.g. unsealable liquids) if no other chunk has actionable work.
                this.areaHasActiveChunk = false;
            }
            List<AreaMiningStrategyPlanner.ChunkCoord> chunks = AreaMiningStrategyPlanner.chunksOrderedByDistance(
                    this.areaMiningArea.minX(), this.areaMiningArea.maxX(),
                    this.areaMiningArea.minZ(), this.areaMiningArea.maxZ(),
                    playerFeet.x, playerFeet.z);
            for (AreaMiningStrategyPlanner.ChunkCoord chunk : chunks) {
                List<BetterBlockPos> chunkFallback = new ArrayList<>();
                if (scanAreaSubBox(bcc,
                        chunk.minBlockX() - margin, chunk.maxBlockX() + margin,
                        chunk.minBlockZ() - margin, chunk.maxBlockZ() + margin,
                        maxIncorrect, hasSealing, chunkFallback, playerFeet)) {
                    this.areaActiveChunkX = chunk.chunkX();
                    this.areaActiveChunkZ = chunk.chunkZ();
                    this.areaHasActiveChunk = true;
                    return;
                }
                if (!chunkFallback.isEmpty() && fallback.isEmpty()) {
                    fallback.addAll(chunkFallback);
                    this.areaActiveChunkX = chunk.chunkX();
                    this.areaActiveChunkZ = chunk.chunkZ();
                    this.areaHasActiveChunk = true;
                }
            }
            if (!fallback.isEmpty()) {
                applyFallbackTargets(fallback);
            }
            return;
        }

        if (strategy == AreaMiningStrategy.LOCAL_TOP_DOWN) {
            int radius = AreaMiningStrategyPlanner.LOCAL_FOCUS_RADIUS + margin;
            if (this.areaHasLocalAnchor) {
                if (scanAreaSubBox(bcc,
                        this.areaLocalAnchorX - radius, this.areaLocalAnchorX + radius,
                        this.areaLocalAnchorZ - radius, this.areaLocalAnchorZ + radius,
                        maxIncorrect, hasSealing, fallback, playerFeet)) {
                    return;
                }
                // Never lock the entire area onto a local fallback liquid while actionable dry blocks remain elsewhere.
                fallback.clear();
                this.areaHasLocalAnchor = false;
            }
            BetterBlockPos anchor = findNearestActionableColumn(bcc, hasSealing, playerFeet);
            if (anchor != null) {
                this.areaLocalAnchorX = anchor.x;
                this.areaLocalAnchorZ = anchor.z;
                this.areaHasLocalAnchor = true;
                if (scanAreaSubBox(bcc,
                        anchor.x - radius, anchor.x + radius,
                        anchor.z - radius, anchor.z + radius,
                        maxIncorrect, hasSealing, fallback, playerFeet)) {
                    return;
                }
                fallback.clear();
            }
            // Fall through to full scan if only fallback/unloaded targets remain.
        }

        this.areaActiveGlobalTopY = Integer.MIN_VALUE;
        scanAreaSubBox(bcc,
                origin.getX(), origin.getX() + schematic.widthX() - 1,
                origin.getZ(), origin.getZ() + schematic.lengthZ() - 1,
                maxIncorrect, hasSealing, fallback, playerFeet);
        if (incorrectPositions.isEmpty() && !fallback.isEmpty()) {
            applyFallbackTargets(fallback);
        }
    }

    private void applyFallbackTargets(List<BetterBlockPos> fallback) {
        for (BetterBlockPos pos : fallback) {
            incorrectPositions.add(pos);
            observedCompleted.remove(BetterBlockPos.longHash(pos.x, pos.y, pos.z));
        }
    }

    private boolean scanAreaSubBox(
            BuilderCalculationContext bcc,
            int worldMinX,
            int worldMaxX,
            int worldMinZ,
            int worldMaxZ,
            int maxIncorrect,
            boolean hasSealing,
            List<BetterBlockPos> fallback,
            BetterBlockPos playerFeet
    ) {
        int relMinX = Math.max(0, worldMinX - origin.getX());
        int relMaxX = Math.min(schematic.widthX() - 1, worldMaxX - origin.getX());
        int relMinZ = Math.max(0, worldMinZ - origin.getZ());
        int relMaxZ = Math.min(schematic.lengthZ() - 1, worldMaxZ - origin.getZ());
        if (relMinX > relMaxX || relMinZ > relMaxZ) {
            return false;
        }
        int highestY = Integer.MIN_VALUE;
        List<BetterBlockPos> layerTargets = new ArrayList<>();
        for (int y = schematic.heightY() - 1; y >= 0; y--) {
            layerTargets.clear();
            int blockY = y + origin.getY();
            for (int z = relMinZ; z <= relMaxZ; z++) {
                int blockZ = z + origin.getZ();
                for (int x = relMinX; x <= relMaxX; x++) {
                    int blockX = x + origin.getX();
                    BlockState current = bcc.bsi.get0(blockX, blockY, blockZ);
                    if (!schematic.inSchematic(x, y, z, current)) {
                        continue;
                    }
                    if (bcc.bsi.worldContainsLoadedChunk(blockX, blockZ)) {
                        if (valid(current, schematic.desiredState(x, y, z, current, this.approxPlaceable), false)) {
                            observedCompleted.add(BetterBlockPos.longHash(blockX, blockY, blockZ));
                        } else if (!isDeferred(new BetterBlockPos(blockX, blockY, blockZ))) {
                            BetterBlockPos pos = new BetterBlockPos(blockX, blockY, blockZ);
                            if (isAreaFilterActive()
                                    && this.areaMiningArea.contains(pos)
                                    && !(current.getBlock() instanceof AirBlock)
                                    && !(current.getBlock() instanceof LiquidBlock)
                                    && isSealedByProtectedBlocks(pos, bcc)) {
                                continue;
                            }
                            if (!isImmediatelyActionableAreaTarget(pos, current, bcc, hasSealing)) {
                                if (fallback.size() <= maxIncorrect) {
                                    fallback.add(pos);
                                }
                            } else {
                                layerTargets.add(pos);
                            }
                        }
                        continue;
                    }
                    if (!observedCompleted.contains(BetterBlockPos.longHash(blockX, blockY, blockZ))) {
                        layerTargets.add(new BetterBlockPos(blockX, blockY, blockZ));
                    }
                }
            }
            if (!layerTargets.isEmpty()) {
                if (highestY == Integer.MIN_VALUE) {
                    highestY = blockY;
                }
                layerTargets.sort((a, b) -> Double.compare(
                        distSqXZ(a.x, a.z, playerFeet.x, playerFeet.z),
                        distSqXZ(b.x, b.z, playerFeet.x, playerFeet.z)));
                for (BetterBlockPos pos : layerTargets) {
                    incorrectPositions.add(pos);
                    observedCompleted.remove(BetterBlockPos.longHash(pos.x, pos.y, pos.z));
                    if (incorrectPositions.size() > maxIncorrect) {
                        if (this.areaMiningOptions.strategy() == AreaMiningStrategy.GLOBAL_TOP_DOWN) {
                            this.areaActiveGlobalTopY = highestY;
                        }
                        return true;
                    }
                }
            }
        }
        if (highestY != Integer.MIN_VALUE && this.areaMiningOptions.strategy() == AreaMiningStrategy.GLOBAL_TOP_DOWN) {
            this.areaActiveGlobalTopY = highestY;
        }
        return !incorrectPositions.isEmpty();
    }

    private BetterBlockPos findNearestActionableColumn(BuilderCalculationContext bcc, boolean hasSealing, BetterBlockPos playerFeet) {
        BetterBlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int y = schematic.heightY() - 1; y >= 0; y--) {
            int blockY = y + origin.getY();
            for (int z = 0; z < schematic.lengthZ(); z++) {
                int blockZ = z + origin.getZ();
                for (int x = 0; x < schematic.widthX(); x++) {
                    int blockX = x + origin.getX();
                    double dSq = distSqXZ(blockX, blockZ, playerFeet.x, playerFeet.z);
                    if (dSq >= bestDistSq) continue;
                    if (!this.areaMiningArea.contains(blockX, blockY, blockZ)) continue;
                    BlockState current = bcc.bsi.get0(blockX, blockY, blockZ);
                    if (!schematic.inSchematic(x, y, z, current)) continue;
                    if (!bcc.bsi.worldContainsLoadedChunk(blockX, blockZ)) continue;
                    if (valid(current, schematic.desiredState(x, y, z, current, this.approxPlaceable), false)) continue;
                    BetterBlockPos pos = new BetterBlockPos(blockX, blockY, blockZ);
                    if (isDeferred(pos)) continue;
                    if (isAreaFilterActive()
                            && !(current.getBlock() instanceof AirBlock)
                            && !(current.getBlock() instanceof LiquidBlock)
                            && isSealedByProtectedBlocks(pos, bcc)) {
                        continue;
                    }
                    if (!isImmediatelyActionableAreaTarget(pos, current, bcc, hasSealing)) continue;
                    bestDistSq = dSq;
                    best = pos;
                }
            }
        }
        return best;
    }

    private boolean isWithinActiveStrategyWindow(BetterBlockPos pos) {
        if (this.areaMiningOptions == null) return true;
        int margin = this.areaMiningArea.contains(pos) ? 0 : 1;
        return switch (this.areaMiningOptions.strategy()) {
            case GLOBAL_TOP_DOWN -> this.areaActiveGlobalTopY == Integer.MIN_VALUE || pos.y >= this.areaActiveGlobalTopY - 1;
            case LOCAL_TOP_DOWN -> !this.areaHasLocalAnchor
                    || AreaMiningStrategyPlanner.isWithinLocalFocus(pos.x, pos.z, this.areaLocalAnchorX, this.areaLocalAnchorZ, margin);
            case CHUNK_TOP_DOWN -> !this.areaHasActiveChunk
                    || AreaMiningStrategyPlanner.isWithinChunk(pos.x, pos.z, this.areaActiveChunkX, this.areaActiveChunkZ, margin);
        };
    }

    private static double distSqXZ(int x, int z, int px, int pz) {
        double dx = x - px;
        double dz = z - pz;
        return dx * dx + dz * dz;
    }

    private boolean isImmediatelyActionableAreaTarget(BetterBlockPos pos, BlockState current, BuilderCalculationContext bcc, boolean hasSealing) {
        if (current.getBlock() instanceof LiquidBlock) {
            if (this.areaMiningOptions.liquidPolicy() == AreaMiningLiquidPolicy.AVOID || !hasSealing) {
                return false;
            }
            if (current.getFluidState().isSource()) {
                if (this.areaMiningArea.contains(pos) || pos.y < this.areaMiningArea.minY() || pos.y > this.areaMiningArea.maxY()) {
                    return true;
                }
                return hasBoundarySealSupport(pos, bcc.bsi)
                        && areaInteriorNeighbors(this.areaMiningArea, pos).stream()
                        .anyMatch(candidate -> isOpenBoundarySealStand(candidate, bcc.bsi));
            }
            return this.areaMiningOptions.liquidPolicy() == AreaMiningLiquidPolicy.SEAL_BOUNDARY
                    && this.areaMiningArea.contains(pos)
                    && hasBoundarySealSupport(pos, bcc.bsi);
        }
        if (current.getBlock() instanceof AirBlock) {
            return true;
        }
        return !MovementHelper.avoidBreakingDueToLiquid(bcc.bsi, pos.x, pos.y, pos.z)
                || isTemporaryBoundarySeal(pos, current, bcc.bsi);
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable) {
        return assemble(bcc, approxPlaceable, false);
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable, boolean logMissing) {
        List<BetterBlockPos> placeable = new ArrayList<>();
        List<BetterBlockPos> breakable = new ArrayList<>();
        List<BetterBlockPos> sourceLiquids = new ArrayList<>();
        List<BetterBlockPos> flowingLiquids = new ArrayList<>();
        Map<BlockState, Integer> missing = new HashMap<>();
        List<BetterBlockPos> outOfBounds = new ArrayList<>();
        incorrectPositions.forEach(pos -> {
            BlockState state = bcc.bsi.get0(pos);
            if (state.getBlock() instanceof AirBlock) {
                BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, state);
                if (desired == null) {
                    outOfBounds.add(pos);
                } else if (containsBlockState(approxPlaceable, desired)) {
                    placeable.add(pos);
                } else {
                    missing.put(desired, 1 + missing.getOrDefault(desired, 0));
                }
            } else {
                if (state.getBlock() instanceof LiquidBlock) {
                    // if the block itself is JUST a liquid (i.e. not just a waterlogged block), we CANNOT break it
                    // TODO for 1.13 make sure that this only matches pure water, not waterlogged blocks
                    if (this.currentBuildIsAreaMining && (this.areaMiningOptions.liquidPolicy() == AreaMiningLiquidPolicy.AVOID || !hasConfiguredSealingBlock())) {
                        flowingLiquids.add(pos);
                    } else if (this.currentBuildIsAreaMining
                            ? state.getFluidState().isSource() // fill still sources first; flowing cells drain once sources are sealed
                            : !MovementHelper.possiblyFlowing(state)) {
                        // if it's a source block then we want to replace it with a throwaway
                        sourceLiquids.add(pos);
                    } else {
                        flowingLiquids.add(pos);
                    }
                } else {
                    breakable.add(pos);
                }
            }
        });
        incorrectPositions.removeAll(outOfBounds);
        List<Goal> flowingSealGoals = new ArrayList<>();
        if (this.currentBuildIsAreaMining
                && this.areaMiningOptions.liquidPolicy() != AreaMiningLiquidPolicy.AVOID
                && hasConfiguredSealingBlock()) {
            long nowMillis = System.currentTimeMillis();
            boolean sealBoundaryOnly = this.areaMiningOptions.liquidPolicy() == AreaMiningLiquidPolicy.SEAL_BOUNDARY;
            List<BetterBlockPos> parked = new ArrayList<>();
            for (BetterBlockPos pos : flowingLiquids) {
                Goal sealGoal = flowingLiquidSealGoal(pos, bcc.bsi, sealBoundaryOnly);
                if (sealGoal != null) {
                    flowingSealGoals.add(sealGoal);
                } else {
                    parked.add(pos);
                }
            }
            for (BetterBlockPos pos : parked) {
                // Nothing to attach a sealing block to right now: defer briefly instead of pausing the whole volume.
                flowingLiquids.remove(pos);
                incorrectPositions.remove(pos);
                deferredLiquids.put(BetterBlockPos.longHash(pos), nowMillis + 30000L);
            }
        }
        List<Goal> toBreak = new ArrayList<>();
        breakable.forEach(pos -> {
            BlockState state = bcc.bsi.get0(pos);
            if (!this.currentBuildIsAreaMining || !MovementHelper.avoidBreakingDueToLiquid(bcc.bsi, pos.x, pos.y, pos.z)
                    || isTemporaryBoundarySeal(pos, state, bcc.bsi)) {
                toBreak.add(breakGoal(pos, bcc));
            }
        });
        List<Goal> toPlace = new ArrayList<>();
        placeable.forEach(pos -> {
            if (!placeable.contains(pos.below()) && !placeable.contains(pos.below(2))) {
                toPlace.add(placementGoal(pos, bcc));
            }
        });
        sourceLiquids.forEach(pos -> {
            if (!this.currentBuildIsAreaMining || this.areaMiningArea.contains(pos)
                    || pos.y < this.areaMiningArea.minY() || pos.y > this.areaMiningArea.maxY()) {
                toPlace.add(new GoalBlock(pos.above()));
                return;
            }
            List<BetterBlockPos> interior = areaInteriorNeighbors(this.areaMiningArea, pos).stream()
                    .filter(candidate -> isOpenBoundarySealStand(candidate, bcc.bsi))
                    .toList();
            if (interior.isEmpty() || !hasBoundarySealSupport(pos, bcc.bsi)) {
                return;
            }
            Goal[] goals = interior.stream().map(BoundarySealGoal::new).toArray(Goal[]::new);
            toPlace.add(goals.length == 1 ? goals[0] : new GoalComposite(goals));
        });

        Goal flowingGoal = flowingSealGoals.isEmpty() ? null : new GoalComposite(flowingSealGoals.toArray(new Goal[0]));
        if (!toPlace.isEmpty()) {
            Goal placementGoal = new GoalComposite(toPlace.toArray(new Goal[0]));
            if (toBreak.isEmpty()) {
                return placementGoal;
            }
            Goal base = new JankyGoalComposite(placementGoal, new GoalComposite(toBreak.toArray(new Goal[0])));
            return flowingGoal == null ? base : new JankyGoalComposite(base, flowingGoal);
        }
        if (toBreak.isEmpty()) {
            long nowMillis = System.currentTimeMillis();
            boolean allowLog = logMissing && nowMillis - lastUnsealableLogMillis > 5000L;
            if (allowLog && !missing.isEmpty()) {
                logDirect("Missing materials for at least:");
                logDirect(missing.entrySet().stream()
                        .map(e -> String.format("%sx %s", e.getValue(), e.getKey()))
                        .collect(Collectors.joining("\n")));
            }
            if (allowLog && !flowingLiquids.isEmpty()) {
                lastUnsealableLogMillis = nowMillis;
                logDirect("Unreplaceable liquids at at least:");
                logDirect(flowingLiquids.stream()
                        .map(p -> String.format("%s %s %s", p.x, p.y, p.z))
                        .collect(Collectors.joining("\n")));
            }
            return flowingGoal;
        }
        Goal breakGoal = new GoalComposite(toBreak.toArray(new Goal[0]));
        return flowingSealGoals.isEmpty() ? breakGoal : new JankyGoalComposite(breakGoal, new GoalComposite(flowingSealGoals.toArray(new Goal[0])));
    }

    static List<BetterBlockPos> areaInteriorNeighbors(IColumnarArea area, BetterBlockPos boundary) {
        List<BetterBlockPos> result = new ArrayList<>();
        for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)) {
            BetterBlockPos candidate = new BetterBlockPos(
                    boundary.x + direction.getStepX(),
                    boundary.y,
                    boundary.z + direction.getStepZ()
            );
            if (area.contains(candidate)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private boolean isOpenBoundarySealStand(BetterBlockPos position, BlockStateInterface bsi) {
        BlockState feet = bsi.get0(position);
        BlockState head = bsi.get0(position.above());
        BlockState floor = bsi.get0(position.below());
        return feet.getFluidState().isEmpty()
                && head.getFluidState().isEmpty()
                && MovementHelper.isReplaceable(position.x, position.y, position.z, feet, bsi)
                && MovementHelper.isReplaceable(position.x, position.y + 1, position.z, head, bsi)
                && !MovementHelper.isReplaceable(position.x, position.y - 1, position.z, floor, bsi);
    }

    private boolean isDeferred(BetterBlockPos position) {
        long key = BetterBlockPos.longHash(position);
        if (this.currentBuildIsAreaMining && this.areaUnreachablePositions.contains(key)) {
            return true;
        }
        long nowMillis = System.currentTimeMillis();
        if (this.currentBuildIsAreaMining && this.scaffoldTracker.isProtected(key, nowMillis)) {
            return true;
        }
        Long until = deferredLiquids.get(key);
        if (until == null) return false;
        if (until <= nowMillis) {
            deferredLiquids.remove(key);
            return false;
        }
        return true;
    }

    /**
     * Records a throwaway scaffold block placed by Baritone pathing inside the active area-mining
     * volume so the builder finishes its climb/bridge before later cleaning up the scaffold.
     */
    public void recordPathScaffold(BlockPos pos) {
        if (!this.currentBuildIsAreaMining || this.paused || this.builderPlacingThisTick
                || this.areaMiningArea == null || pos == null) {
            return;
        }
        if (!this.areaMiningArea.contains(pos)) {
            return;
        }
        long key = BetterBlockPos.longHash(pos.getX(), pos.getY(), pos.getZ());
        this.scaffoldTracker.recordScaffold(key, System.currentTimeMillis());
        if (this.incorrectPositions != null) {
            this.incorrectPositions.remove(new BetterBlockPos(pos));
        }
    }

    private void refreshNearbyPathScaffolds(BetterBlockPos feet) {
        if (feet == null) {
            return;
        }
        long nowMillis = System.currentTimeMillis();
        if (!this.scaffoldTracker.hasActiveScaffolds(nowMillis)) {
            return;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -5; dy <= 0; dy++) {
                    this.scaffoldTracker.refreshIfProtected(
                            BetterBlockPos.longHash(feet.x + dx, feet.y + dy, feet.z + dz),
                            nowMillis);
                }
            }
        }
    }

    private boolean deferBlockedAreaTargets(BuilderCalculationContext bcc) {
        if (this.incorrectPositions == null || this.incorrectPositions.isEmpty()) {
            return false;
        }
        long nowMillis = System.currentTimeMillis();
        boolean filterActive = isAreaFilterActive();
        int permanentThreshold = filterActive ? 2 : 4;
        boolean deferredAny = false;
        for (BetterBlockPos pos : this.incorrectPositions) {
            if (!this.areaMiningArea.contains(pos)) {
                continue;
            }
            long key = BetterBlockPos.longHash(pos);
            int fails = this.areaPathFailCounts.merge(key, 1, Integer::sum);
            if (fails >= permanentThreshold) {
                this.areaUnreachablePositions.add(key);
                if (filterActive && this.observedCompleted != null) {
                    this.observedCompleted.add(key);
                }
            } else {
                this.deferredLiquids.put(key, nowMillis + 30000L);
            }
            deferredAny = true;
        }
        if (!deferredAny) {
            return false;
        }
        this.areaHasLocalAnchor = false;
        this.incorrectPositions = null;
        return recalc(bcc) || (filterActive && !this.areaUnreachablePositions.isEmpty());
    }

    private boolean isSealedByProtectedBlocks(BetterBlockPos pos, BuilderCalculationContext bcc) {
        long startKey = BetterBlockPos.longHash(pos);
        if (this.areaUnreachablePositions.contains(startKey)) {
            return true;
        }
        if (pos.y == this.areaMiningArea.maxY() && bcc.bsi.get0(pos.x, pos.y + 1, pos.z).getBlock() instanceof AirBlock) {
            return false;
        }
        BetterBlockPos feet = ctx.playerFeet();
        List<Block> disallowed = Baritone.settings().blocksToDisallowBreaking.value;
        SealedPocketDetector.Result result = SealedPocketDetector.check(
                pos.x, pos.y, pos.z,
                feet.x, feet.y, feet.z,
                this.areaMiningArea.maxY(),
                (x, y, z) -> classifyAreaCell(x, y, z, bcc, disallowed),
                1024
        );
        if (!result.sealed()) {
            return false;
        }
        for (int i = 0; i < result.size(); i++) {
            long key = BetterBlockPos.longHash(result.x(i), result.y(i), result.z(i));
            this.areaUnreachablePositions.add(key);
            if (this.observedCompleted != null) {
                this.observedCompleted.add(key);
            }
        }
        return true;
    }

    private boolean isAreaFilterActive() {
        return !this.areaProtectedSet.isEmpty() || this.areaTargetBlocksOnly;
    }

    private SealedPocketDetector.CellType classifyAreaCell(int x, int y, int z, BuilderCalculationContext bcc, List<Block> disallowed) {
        if (!bcc.bsi.worldContainsLoadedChunk(x, z)) {
            return SealedPocketDetector.CellType.OPEN;
        }
        BlockState state = bcc.bsi.get0(x, y, z);
        Block block = state.getBlock();
        if (block instanceof AirBlock || state.getCollisionShape(ctx.world(), new BlockPos(x, y, z)).isEmpty() && state.getFluidState().isEmpty()) {
            return SealedPocketDetector.CellType.OPEN;
        }
        if (this.areaProtectedSet.contains(block) || disallowed.contains(block)) {
            return SealedPocketDetector.CellType.BARRIER;
        }
        if (this.areaTargetBlocksOnly && !this.areaTargetSet.contains(block)) {
            return SealedPocketDetector.CellType.BARRIER;
        }
        if (!this.areaMiningArea.contains(x, y, z)) {
            return SealedPocketDetector.CellType.BARRIER;
        }
        if (block instanceof LiquidBlock) {
            return this.areaMiningOptions.liquidPolicy() == AreaMiningLiquidPolicy.AVOID
                    ? SealedPocketDetector.CellType.BARRIER
                    : SealedPocketDetector.CellType.OPEN;
        }
        if (state.getDestroySpeed(ctx.world(), new BlockPos(x, y, z)) < 0.0F) {
            return SealedPocketDetector.CellType.BARRIER;
        }
        return SealedPocketDetector.CellType.BREAKABLE;
    }

    private Goal flowingLiquidSealGoal(BetterBlockPos pos, BlockStateInterface bsi, boolean sealBoundaryOnly) {
        if (this.areaMiningArea.contains(pos)) {
            boolean hasSupport = sealBoundaryOnly
                    ? hasBoundarySealSupport(pos, bsi)
                    : hasAnySolidNeighbor(pos, bsi);
            return hasSupport ? new GoalBlock(pos.above()) : null;
        }
        if (pos.y < this.areaMiningArea.minY() || pos.y > this.areaMiningArea.maxY()) {
            return hasAnySolidNeighbor(pos, bsi) ? new GoalBlock(pos.above()) : null;
        }
        if (!hasBoundarySealSupport(pos, bsi)) {
            return null;
        }
        List<BetterBlockPos> interior = areaInteriorNeighbors(this.areaMiningArea, pos).stream()
                .filter(candidate -> isOpenBoundarySealStand(candidate, bsi))
                .toList();
        if (interior.isEmpty()) {
            return null;
        }
        Goal[] goals = interior.stream().map(BoundarySealGoal::new).toArray(Goal[]::new);
        return goals.length == 1 ? goals[0] : new GoalComposite(goals);
    }

    private boolean hasAnySolidNeighbor(BetterBlockPos position, BlockStateInterface bsi) {
        for (Direction direction : Direction.values()) {
            BetterBlockPos support = position.relative(direction);
            BlockState state = bsi.get0(support);
            if (!MovementHelper.isReplaceable(support.x, support.y, support.z, state, bsi)
                    && !state.getShape(ctx.world(), support).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBoundarySealSupport(BetterBlockPos position, BlockStateInterface bsi) {
        for (Direction direction : Direction.values()) {
            BetterBlockPos support = position.relative(direction);
            if (this.areaMiningArea.contains(support)) {
                continue;
            }
            BlockState state = bsi.get0(support);
            if (!MovementHelper.isReplaceable(support.x, support.y, support.z, state, bsi)
                    && !state.getShape(ctx.world(), support).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean isSideBoundaryLiquid(BetterBlockPos position, BlockState state) {
        return position.y >= this.areaMiningArea.minY()
                && position.y <= this.areaMiningArea.maxY()
                && !this.areaMiningArea.contains(position)
                && !state.getFluidState().isEmpty()
                && !areaInteriorNeighbors(this.areaMiningArea, position).isEmpty();
    }

    private boolean isTemporaryBoundarySeal(BetterBlockPos position, BlockState state, BlockStateInterface bsi) {
        if (!this.areaMiningArea.contains(position) || !this.areaMiningOptions.sealingBlocks().contains(state.getBlock())) {
            return false;
        }
        for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)) {
            BetterBlockPos boundary = new BetterBlockPos(
                    position.x + direction.getStepX(),
                    position.y,
                    position.z + direction.getStepZ()
            );
            if (isSideBoundaryLiquid(boundary, bsi.get0(boundary)) && hasBoundarySealSupport(boundary, bsi)) {
                return true;
            }
        }
        return false;
    }

    public static class JankyGoalComposite implements Goal {

        private final Goal primary;
        private final Goal fallback;

        public JankyGoalComposite(Goal primary, Goal fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }


        @Override
        public boolean isInGoal(int x, int y, int z) {
            return primary.isInGoal(x, y, z) || fallback.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return primary.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            JankyGoalComposite goal = (JankyGoalComposite) o;
            return Objects.equals(primary, goal.primary)
                    && Objects.equals(fallback, goal.fallback);
        }

        @Override
        public int hashCode() {
            int hash = -1701079641;
            hash = hash * 1196141026 + primary.hashCode();
            hash = hash * -80327868 + fallback.hashCode();
            return hash;
        }

        @Override
        public String toString() {
            return "JankyComposite Primary: " + primary + " Fallback: " + fallback;
        }
    }

    public static class GoalBreak extends GoalGetToBlock {

        public GoalBreak(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            // can't stand right on top of a block, that might not work (what if it's unsupported, can't break then)
            if (y > this.y) {
                return false;
            }
            // but any other adjacent works for breaking, including inside or below
            return super.isInGoal(x, y, z);
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalBreak{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1636324008;
        }
    }

    private Goal placementGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (!(ctx.world().getBlockState(pos).getBlock() instanceof AirBlock)) {  // TODO can this even happen?
            return new GoalPlace(pos);
        }
        boolean allowSameLevel = !(ctx.world().getBlockState(pos.above()).getBlock() instanceof AirBlock);
        BlockState current = ctx.world().getBlockState(pos);
        for (Direction facing : Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP) {
            //noinspection ConstantConditions
            if (MovementHelper.canPlaceAgainst(ctx, pos.relative(facing)) && placementPlausible(pos, bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), current))) {
                return new GoalAdjacent(pos, pos.relative(facing), allowSameLevel);
            }
        }
        return new GoalPlace(pos);
    }

    private Goal breakGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (Baritone.settings().goalBreakFromAbove.value && bcc.bsi.get0(pos.above()).getBlock() instanceof AirBlock && bcc.bsi.get0(pos.above(2)).getBlock() instanceof AirBlock) { // TODO maybe possible without the up(2) check?
            return new JankyGoalComposite(new GoalBreak(pos), new GoalGetToBlock(pos.above()) {
                @Override
                public boolean isInGoal(int x, int y, int z) {
                    if (y > this.y || (x == this.x && y == this.y && z == this.z)) {
                        return false;
                    }
                    return super.isInGoal(x, y, z);
                }
            });
        }
        return new GoalBreak(pos);
    }

    public static class GoalAdjacent extends GoalGetToBlock {

        private boolean allowSameLevel;
        private BlockPos no;

        public GoalAdjacent(BlockPos pos, BlockPos no, boolean allowSameLevel) {
            super(pos);
            this.no = no;
            this.allowSameLevel = allowSameLevel;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (x == this.x && y == this.y && z == this.z) {
                return false;
            }
            if (x == no.getX() && y == no.getY() && z == no.getZ()) {
                return false;
            }
            if (!allowSameLevel && y == this.y - 1) {
                return false;
            }
            if (y < this.y - 1) {
                return false;
            }
            return super.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (!super.equals(o)) {
                return false;
            }

            GoalAdjacent goal = (GoalAdjacent) o;
            return allowSameLevel == goal.allowSameLevel
                    && Objects.equals(no, goal.no);
        }

        @Override
        public int hashCode() {
            int hash = 806368046;
            hash = hash * 1412661222 + super.hashCode();
            hash = hash * 1730799370 + (int) BetterBlockPos.longHash(no.getX(), no.getY(), no.getZ());
            hash = hash * 260592149 + (allowSameLevel ? -1314802005 : 1565710265);
            return hash;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalAdjacent{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    public static class GoalPlace extends GoalBlock {

        public GoalPlace(BlockPos placeAt) {
            super(placeAt.above());
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1910811835;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalPlace{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    static final class BoundarySealGoal extends GoalBlock {

        BoundarySealGoal(BlockPos standAt) {
            super(standAt);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return this.y * 100 + super.heuristic(x, y, z);
        }
    }

    @Override
    public void onLostControl() {
        if (this.currentBuildIsAreaMining) {
            this.areaMiningState = AreaMiningStatus.State.CANCELLED;
            this.currentBuildIsAreaMining = false;
        }
        this.areaObservedSolid.clear();
        incorrectPositions = null;
        name = null;
        schematic = null;
        realSchematic = null;
        layer = Baritone.settings().startAtLayer.value;
        numRepeats = 0;
        paused = false;
        observedCompleted = null;
    }

    @Override
    public String displayName0() {
        return paused ? "Builder Paused" : "Building " + name;
    }

    @Override
    public Optional<Integer> getMinLayer() {
        if (Baritone.settings().buildInLayers.value && !this.currentBuildIsAreaMining) {
            return Optional.of(this.layer);
        }
        return Optional.empty();
    }

    @Override
    public Optional<Integer> getMaxLayer() {
        if (Baritone.settings().buildInLayers.value && !this.currentBuildIsAreaMining) {
            return Optional.of(this.stopAtHeight);
        }
        return Optional.empty();
    }

    private List<BlockState> approxPlaceable(int size) {
        List<BlockState> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                result.add(Blocks.AIR.defaultBlockState());
                continue;
            }
            // <toxic cloud>
            BlockState itemState = ((BlockItem) stack.getItem())
                .getBlock()
                .getStateForPlacement(
                    new BlockPlaceContext(
                        new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {}
                    )
                );
            if (itemState != null) {
                result.add(itemState);
            } else {
                result.add(Blocks.AIR.defaultBlockState());
            }
            // </toxic cloud>
        }
        return result;
    }

    private static boolean sameBlockstate(BlockState first, BlockState second) {
        if (first.getBlock() != second.getBlock()) {
            return false;
        }
        boolean ignoreDirection = Baritone.settings().buildIgnoreDirection.value;
        List<String> ignoredProps = Baritone.settings().buildIgnoreProperties.value;
        if (!ignoreDirection && ignoredProps.isEmpty()) {
            return first.equals(second); // early return if no properties are being ignored
        }
        for (Property<?> prop : first.getProperties()) {
            if (first.getValue(prop) != second.getValue(prop)
                    && !(ignoreDirection && ORIENTATION_PROPS.contains(prop))
                    && !ignoredProps.contains(prop.getName())) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsBlockState(Collection<BlockState> states, BlockState state) {
        for (BlockState testee : states) {
            if (sameBlockstate(testee, state)) {
                return true;
            }
        }
        return false;
    }

    private static boolean valid(BlockState current, BlockState desired, boolean itemVerify) {
        if (desired == null) {
            return true;
        }
        if (current.getBlock() instanceof LiquidBlock && Baritone.settings().okIfWater.value) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && Baritone.settings().okIfAir.value.contains(desired.getBlock())) {
            return true;
        }
        if (desired.getBlock() instanceof AirBlock && Baritone.settings().buildIgnoreBlocks.value.contains(current.getBlock())) {
            return true;
        }
        if (!(current.getBlock() instanceof AirBlock) && Baritone.settings().buildIgnoreExisting.value && !itemVerify) {
            return true;
        }
        if (Baritone.settings().buildValidSubstitutes.value.getOrDefault(desired.getBlock(), Collections.emptyList()).contains(current.getBlock()) && !itemVerify) {
            return true;
        }
        if (current.equals(desired)) {
            return true;
        }
        return sameBlockstate(current, desired);
    }

    /**
     * Returns true when active area mining forbids breaking the block at {@code pos}:
     * anything below {@code area.minY()}, protected whitelist blocks, or non-target blocks
     * in explicit blacklist mode. Guards against path-execution click-through and overshoot.
     */
    public boolean isBlockBreakForbidden(BlockPos pos) {
        if (!this.currentBuildIsAreaMining || this.paused || this.areaMiningArea == null || pos == null) {
            return false;
        }
        if (pos.getY() < this.areaMiningArea.minY()) {
            return true;
        }
        if (ctx.world() == null) {
            return false;
        }
        Block block = ctx.world().getBlockState(pos).getBlock();
        if (this.areaProtectedSet.contains(block)) {
            return true;
        }
        return this.areaTargetBlocksOnly && !this.areaTargetSet.contains(block);
    }

    public class BuilderCalculationContext extends CalculationContext {

        private final List<BlockState> placeable;
        private final ISchematic schematic;
        private final int originX;
        private final int originY;
        private final int originZ;

        public BuilderCalculationContext() {
            super(BuilderProcess.this.baritone, true); // wew lad
            this.placeable = approxPlaceable(9);
            this.schematic = BuilderProcess.this.schematic;
            this.originX = origin.getX();
            this.originY = origin.getY();
            this.originZ = origin.getZ();

            this.jumpPenalty += 10;
            this.backtrackCostFavoringCoefficient = 1;
        }

        private BlockState getSchematic(int x, int y, int z, BlockState current) {
            if (schematic.inSchematic(x - originX, y - originY, z - originZ, current)) {
                return schematic.desiredState(x - originX, y - originY, z - originZ, current, BuilderProcess.this.approxPlaceable);
            } else {
                return null;
            }
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            if (isPossiblyProtected(x, y, z) || !worldBorder.canPlaceAt(x, z)) { // make calculation fail properly if we can't build
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                // TODO this can return true even when allowPlace is off.... is that an issue?
                if (sch.getBlock() instanceof AirBlock) {
                    // we want this to be air, but they're asking if they can place here
                    // this won't be a schematic block, this will be a throwaway
                    return placeBlockCost * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value; // we're going to have to break it eventually
                }
                if (placeable.contains(sch)) {
                    return 0; // thats right we gonna make it FREE to place a block where it should go in a structure
                    // no place block penalty at all 😎
                    // i'm such an idiot that i just tried to copy and paste the epic gamer moment emoji too
                    // get added to unicode when?
                }
                if (!hasThrowaway) {
                    return COST_INF;
                }
                // we want it to be something that we don't have
                // even more of a pain to place something wrong
                return placeBlockCost * 1.5 * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value;
            } else {
                if (hasThrowaway) {
                    return placeBlockCost;
                } else {
                    return COST_INF;
                }
            }
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            if ((!allowBreak && !allowBreakAnyway.contains(current.getBlock())) || isPossiblyProtected(x, y, z)) {
                return COST_INF;
            }
            if (BuilderProcess.this.currentBuildIsAreaMining && BuilderProcess.this.areaMiningArea != null) {
                if (y < BuilderProcess.this.areaMiningArea.minY()) {
                    return COST_INF;
                }
                if (BuilderProcess.this.areaProtectedSet.contains(current.getBlock())) {
                    return COST_INF;
                }
                if (BuilderProcess.this.areaTargetBlocksOnly
                        && !BuilderProcess.this.areaTargetSet.contains(current.getBlock())) {
                    return COST_INF;
                }
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                if (sch.getBlock() instanceof AirBlock) {
                    // it should be air
                    // regardless of current contents, we can break it
                    return 1;
                }
                // it should be a real block
                // is it already that block?
                if (valid(bsi.get0(x, y, z), sch, false)) {
                    return Baritone.settings().breakCorrectBlockPenaltyMultiplier.value;
                } else {
                    // can break if it's wrong
                    // would be great to return less than 1 here, but that would actually make the cost calculation messed up
                    // since we're breaking a block, if we underestimate the cost, then it'll fail when it really takes the correct amount of time
                    return 1;

                }
                // TODO do blocks in render distace only?
                // TODO allow breaking blocks that we have a tool to harvest and immediately place back?
            } else {
                return BuilderProcess.this.currentBuildIsAreaMining
                        ? Baritone.settings().breakCorrectBlockPenaltyMultiplier.value
                        : 1;
            }
        }
    }
}
