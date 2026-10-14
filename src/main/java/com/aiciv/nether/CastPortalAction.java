package com.aiciv.nether;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.actions.ItemGatherer;
import com.aiciv.bot.AiPlayer;
import com.aiciv.building.BuildAction;
import com.aiciv.building.Facing;
import com.aiciv.building.StructureRegistry;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import com.aiciv.nav.Pos;
import com.aiciv.world.BlockBreaker;
import com.aiciv.world.BlockUse;
import com.aiciv.world.Buckets;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.PlacedBlocks;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Makes the obsidian frame the way speed-runners do without a diamond pickaxe: lava poured into each frame cell, then
 * water on it. Fluids can only be poured against a solid face, and a frame in open ground has none, so the bot first
 * builds a wall behind it. The water is taken back at once, before it can spread, so the bucket is ready for the next
 * cell; at the end the wall comes down again and its blocks are picked up.
 */
public final class CastPortalAction implements Action {
    static final int SIGHT = 16;
    static final double REACH = 3.5;
    /**
     * Lava is scooped from the rim: the bucket ray runs to the source centre, and a pool level with the floor hides
     * that centre from anywhere but the rim. 3.0 is also the smallest radius the navigator can stand within.
     */
    static final double LAVA_REACH = 3.0;
    static final int LAVA_CANDIDATES = 6;
    private static final double SURFACE = 0.92;
    static final int MAX_TRIES = 4;
    static final int SETTLE_TICKS = 5;
    /** Blocks the bot may pillar up on when the wall face it needs is out of sight from the ground. */
    static final int CLIMB_BLOCKS = 3;
    static final int GATHER_TICKS = 200;
    /** Water goes above the lava, else beside it (never below: water under lava does nothing). */
    private static final Direction[] WATER_SIDES = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    private enum Phase {
        START, WALL, NEXT, LAVA_FIND, LAVA_BACK, LAVA_SPOT, LAVA_FILL, CELL_SPOT, POUR_LAVA, POUR_WATER, WATER_SPOT, TAKE_WATER,
        TAKE_SPOT, UNWALL, GATHER, LIGHT
    }

    private final BlockPos givenOrigin;
    private final Facing givenFacing;
    private Phase phase = Phase.START;
    private BlockPos origin;
    private Facing facing;
    private Block wallBlock;
    private String wallName;
    private BuildAction wall;
    private final List<BlockPos> wallCells = new ArrayList<>();
    private final List<BlockPos> cells = new ArrayList<>();
    private final Set<BlockPos> site = new HashSet<>();
    private int index;
    private Approach approach;
    private BlockPos approachTarget;
    private double approachRadius;
    private BlockPos lavaAt;
    private BlockPos lastLava;
    private final Set<BlockPos> skipped = new HashSet<>();
    private boolean wentBack;
    private BlockPos waterAt;
    private int tries;
    private int settle;
    private BlockPos spot;
    private BlockPos spotAround;
    private Predicate<Vec3> spotRule;
    private Phase spotAfter;
    private int spotFailures;
    private final Set<BlockPos> badSpots = new HashSet<>();
    private int unwallIndex;
    private boolean breaking;
    private int gatherTicks;
    private ItemGatherer gatherer;
    private LightPortalAction light;
    private String summary;
    /** Every cell this cast poured a fluid into; on a cancel or failure each one still a source is scooped back. */
    private final Set<BlockPos> poured = new LinkedHashSet<>();

    public CastPortalAction(BlockPos originOrNull, Facing facingOrNull) {
        this.givenOrigin = originOrNull;
        this.givenFacing = facingOrNull;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        ActionStatus s = step(ctx);
        if (s.state() != ActionStatus.State.FAILED) return s;
        String left = cleanUp(ctx.player());
        return left == null ? s : ActionStatus.failed(s.reason() + "; " + left);
    }

    private ActionStatus step(BotContext ctx) {
        AiPlayer p = ctx.player();
        ServerLevel level = p.level();
        if (phase == Phase.START) {
            ActionStatus problem = start(p, level);
            if (problem != null) return problem;
        }
        return switch (phase) {
            case WALL -> building(ctx);
            case NEXT -> next(p);
            case LAVA_FIND -> lavaFind(p);
            case LAVA_BACK -> walk(ctx, Phase.LAVA_FIND);
            case LAVA_FILL -> lavaFill(p);
            case LAVA_SPOT, CELL_SPOT, WATER_SPOT, TAKE_SPOT -> walkToSpot(ctx, p);
            case POUR_LAVA -> pourLava(p);
            case POUR_WATER -> pourWater(p, level);
            case TAKE_WATER -> takeWater(p, level);
            case UNWALL -> unwall(ctx, level);
            case GATHER -> gather(ctx, p);
            case LIGHT -> lighting(ctx);
            case START -> ActionStatus.RUNNING;
        };
    }

    // ---------------------------------------------------------------- the checks before anything is touched

    private ActionStatus start(AiPlayer p, ServerLevel level) {
        Map<String, Integer> inventory = BuildPortalAction.inventory(p);
        boolean water = inventory.getOrDefault("water_bucket", 0) > 0;
        boolean lava = inventory.getOrDefault("lava_bucket", 0) > 0;
        boolean empty = inventory.getOrDefault("bucket", 0) > 0;
        if (!water || (!lava && !empty)) {
            return ActionStatus.failed("needs a water_bucket, and a lava_bucket or an empty bucket (you have " + have(inventory) + ")");
        }
        String block = PortalFrame.wallBlock(inventory);
        if (block == null) {
            return ActionStatus.failed("needs " + PortalFrame.WALL_BLOCKS + " cobblestone, cobbled_deepslate, dirt or netherrack for a wall to pour against (you have "
                    + InventoryOps.throwawayCount(p) + ")");
        }
        wallBlock = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(block));
        facing = givenFacing != null ? givenFacing : Facing.of(p.getDirection());
        origin = givenOrigin != null ? givenOrigin : BuildPortalAction.defaultOrigin(p, facing);
        Pos bottomLeft = new Pos(origin.getX(), origin.getY(), origin.getZ());
        List<Pos> frame = PortalFrame.frameCells(bottomLeft, facing);
        List<Pos> all = new ArrayList<>(frame);
        all.addAll(PortalFrame.corners(bottomLeft, facing));
        all.addAll(PortalFrame.interior(bottomLeft, facing));
        for (Pos s : frame) cells.add(block(s));
        for (Pos s : PortalFrame.wallCells(bottomLeft, facing)) wallCells.add(block(s));
        for (Pos s : all) site.add(block(s));
        List<BlockPos> toCheck = new ArrayList<>(site);
        toCheck.addAll(wallCells);
        for (BlockPos b : toCheck) {
            if (!level.isLoaded(b)) return ActionStatus.failed("the portal site is not loaded at " + LightPortalAction.coords(b));
            BlockState state = level.getBlockState(b);
            if (!(state.isAir() || (state.canBeReplaced() && state.getFluidState().isEmpty()))) {
                return ActionStatus.failed("the portal site is not clear at " + LightPortalAction.coords(b));
            }
        }
        for (Pos s : frame.subList(0, 2)) {
            BlockPos under = block(s).below();
            if (level.getBlockState(under).getCollisionShape(level, under).isEmpty()) {
                return ActionStatus.failed("the portal site has no ground under " + LightPortalAction.coords(block(s)));
            }
        }
        PlacedBlocks placed = PlacedBlocks.of(level.getServer());
        for (BlockPos cell : cells) {
            Optional<BlockPos> near = placed.placedByOtherNear(level, cell, PlacedBlocks.LAVA_KEEP_AWAY, p.getScoreboardName(), s -> true);
            if (near.isPresent()) {
                return ActionStatus.failed("won't pour lava at " + LightPortalAction.coords(cell) + ": " + placed.describe(level, near.get())
                        + " is within " + PlacedBlocks.LAVA_KEEP_AWAY + " blocks");
            }
        }
        if (!lava) {
            List<BlockPos> sources = lavaSources(p, skipped);
            if (sources.isEmpty()) return ActionStatus.failed("needs a lava_bucket or lava within " + SIGHT + " blocks");
            lastLava = sources.get(0);
        }
        wallName = "portal_wall_" + BuildPortalAction.coordinate(origin.getX()) + "_" + BuildPortalAction.coordinate(origin.getZ());
        BlockPos wallOrigin = block(new Pos(origin.getX() + facing.forwardX(), origin.getY(), origin.getZ() + facing.forwardZ()));
        wall = new BuildAction(wallName, PortalFrame.wallBlueprint(block), wallOrigin, facing, "portal_wall");
        phase = Phase.WALL;
        return null;
    }

    private static String have(Map<String, Integer> inventory) {
        List<String> parts = new ArrayList<>();
        for (String id : List.of("water_bucket", "lava_bucket", "bucket")) {
            int n = inventory.getOrDefault(id, 0);
            if (n > 0) parts.add(n + " " + id);
        }
        return parts.isEmpty() ? "no buckets" : String.join(", ", parts);
    }

    /** Lava sources open to the air that the bot can see the surface of within {@link #SIGHT} blocks, nearest first. */
    private static List<BlockPos> lavaSources(AiPlayer p, Set<BlockPos> skip) {
        ServerLevel level = p.level();
        Vec3 eyes = p.getEyePosition();
        BlockPos at = BlockPos.containing(eyes);
        List<BlockPos> out = new ArrayList<>();
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        for (int dx = -SIGHT; dx <= SIGHT; dx++) {
            for (int dy = -SIGHT; dy <= SIGHT; dy++) {
                for (int dz = -SIGHT; dz <= SIGHT; dz++) {
                    if (dx * dx + dy * dy + dz * dz > SIGHT * SIGHT) continue;
                    c.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    if (!level.isLoaded(c) || !level.getFluidState(c).isSourceOfType(Fluids.LAVA)) continue;
                    BlockPos pos = c.immutable();
                    if (skip.contains(pos) || !level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                            || level.getFluidState(pos.above()).isSource()) {
                        continue;
                    }
                    // A pool level with the floor is seen by its surface; the ray may pass through fluid but not stone.
                    Vec3 surface = new Vec3(pos.getX() + 0.5, pos.getY() + SURFACE, pos.getZ() + 0.5);
                    BlockHitResult hit = level.clip(new ClipContext(eyes, surface, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
                    if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos)) out.add(pos);
                }
            }
        }
        out.sort(Comparator.comparingDouble(b -> Vec3.atCenterOf(b).distanceToSqr(eyes)));
        return out;
    }

    // ---------------------------------------------------------------- the wall

    private ActionStatus building(BotContext ctx) {
        ActionStatus s = wall.tick(ctx);
        switch (s.state()) {
            case DONE:
                phase = Phase.NEXT;
                return ActionStatus.RUNNING;
            case FAILED:
                return ActionStatus.failed("could not build the wall to pour against: " + s.reason());
            default:
                return ActionStatus.RUNNING;
        }
    }

    // ---------------------------------------------------------------- one cell after another

    private ActionStatus next(AiPlayer p) {
        tries = 0;
        badSpots.clear();
        if (index >= cells.size()) {
            // Cobblestone only drops for a pickaxe: without one, breaking the wall would only lose the blocks.
            BlockState wallState = wallBlock.defaultBlockState();
            boolean drops = !wallState.requiresCorrectToolForDrops() || InventoryOps.bestToolFor(p, wallState).isCorrectToolForDrops(wallState);
            if (drops) {
                phase = Phase.UNWALL;
                return ActionStatus.RUNNING;
            }
            return finishCast(p);
        }
        if (!InventoryOps.has(p, Items.WATER_BUCKET, 1)) {
            return ActionStatus.failed("lost the water_bucket before the cell at " + LightPortalAction.coords(cells.get(index)));
        }
        if (!InventoryOps.has(p, Items.LAVA_BUCKET, 1)) {
            phase = Phase.LAVA_FIND;
            return ActionStatus.RUNNING;
        }
        return toLavaPour(p, cells.get(index));
    }

    private ActionStatus lavaFind(AiPlayer p) {
        List<BlockPos> sources = lavaSources(p, skipped);
        Set<BlockPos> keepOut = keepOut();
        for (BlockPos source : sources.subList(0, Math.min(LAVA_CANDIDATES, sources.size()))) {
            if (StandingSpot.find(p, source, keepOut, badSpots, 0, eyes -> canFillFrom(p, eyes, source)).isPresent()) {
                lavaAt = source;
                return toSpot(p, source, eyes -> canFillFrom(p, eyes, source), Phase.LAVA_SPOT, Phase.LAVA_FILL);
            }
        }
        if (lastLava != null && !wentBack) {
            // Out of sight from here: go back to where the lava was.
            wentBack = true;
            skipped.clear();
            startApproach(lastLava, LAVA_REACH + 1);
            phase = Phase.LAVA_BACK;
            return ActionStatus.RUNNING;
        }
        return ActionStatus.failed("ran out of lava: no lava source within " + SIGHT + " blocks");
    }

    private ActionStatus lavaFill(AiPlayer p) {
        BlockUse.Result r = Buckets.fill(p, lavaAt);
        if (r == BlockUse.Result.USED) {
            lastLava = lavaAt;
            wentBack = false;
            tries = 0;
            skipped.clear();
            return toLavaPour(p, cells.get(index));
        }
        // Gone, or not reachable from here: try another source.
        skipped.add(lavaAt);
        if (++tries >= MAX_TRIES * 2) return ActionStatus.failed("could not scoop the lava at " + LightPortalAction.coords(lavaAt) + " (" + r + ")");
        phase = Phase.LAVA_FIND;
        return ActionStatus.RUNNING;
    }

    private ActionStatus toLavaPour(AiPlayer p, BlockPos cell) {
        return toSpot(p, cell, eyes -> Buckets.canPourFrom(p, eyes, cell), Phase.CELL_SPOT, Phase.POUR_LAVA);
    }

    private ActionStatus pourLava(AiPlayer p) {
        BlockPos cell = cells.get(index);
        BlockUse.Result r = Buckets.pour(p, cell, Items.LAVA_BUCKET);
        if (p.level().getFluidState(cell).isSourceOfType(Fluids.LAVA)) poured.add(cell);
        if (r == BlockUse.Result.USED) {
            tries = 0;
            phase = Phase.POUR_WATER;
            return ActionStatus.RUNNING;
        }
        // NO_EFFECT is a pour that missed (a bad angle), like one out of reach: try again from another spot.
        if (++tries >= MAX_TRIES) return ActionStatus.failed("could not pour lava at " + LightPortalAction.coords(cell) + " (" + r + ")");
        badSpots.add(p.blockPosition());
        return toLavaPour(p, cell);
    }

    /** Cells next to the lava that could take the water: above it first, then beside it. */
    private static List<BlockPos> waterCells(ServerLevel level, BlockPos lava) {
        List<BlockPos> out = new ArrayList<>();
        for (Direction side : WATER_SIDES) {
            BlockPos at = lava.relative(side);
            if (level.getBlockState(at).canBeReplaced()) out.add(at);
        }
        return out;
    }

    private ActionStatus pourWater(AiPlayer p, ServerLevel level) {
        BlockPos cell = cells.get(index);
        List<BlockPos> candidates = waterCells(level, cell);
        for (BlockPos at : candidates) {
            BlockUse.Result r = Buckets.pour(p, at, Items.WATER_BUCKET);
            if (level.getFluidState(at).isSourceOfType(Fluids.WATER)) poured.add(at);
            if (r == BlockUse.Result.USED) {
                waterAt = at;
                settle = 0;
                tries = 0;
                phase = Phase.TAKE_WATER;
                return ActionStatus.RUNNING;
            }
        }
        if (++tries >= MAX_TRIES) return ActionStatus.failed("could not pour water onto the lava at " + LightPortalAction.coords(cell));
        badSpots.add(p.blockPosition());
        return toSpot(p, cell, eyes -> candidates.stream().anyMatch(c -> Buckets.canPourFrom(p, eyes, c)), Phase.WATER_SPOT, Phase.POUR_WATER);
    }

    private ActionStatus takeWater(AiPlayer p, ServerLevel level) {
        BlockPos cell = cells.get(index);
        BlockState state = level.getBlockState(cell);
        if (state.is(Blocks.COBBLESTONE)) {
            return ActionStatus.failed("the cast went wrong at " + LightPortalAction.coords(cell) + " (cobblestone)");
        }
        if (!state.is(Blocks.OBSIDIAN)) {
            if (++settle > SETTLE_TICKS) {
                return ActionStatus.failed("the cast went wrong at " + LightPortalAction.coords(cell) + " ("
                        + BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath() + ")");
            }
            return ActionStatus.RUNNING;
        }
        poured.remove(cell); // obsidian now
        BlockUse.Result r = Buckets.fill(p, waterAt);
        if (r != BlockUse.Result.USED && level.getFluidState(waterAt).isSource()) {
            if (++tries >= MAX_TRIES) return ActionStatus.failed("could not take the water back from " + LightPortalAction.coords(waterAt) + " (" + r + ")");
            badSpots.add(p.blockPosition());
            BlockPos water = waterAt;
            return toSpot(p, water, eyes -> canFillFrom(p, eyes, water), Phase.TAKE_SPOT, Phase.TAKE_WATER);
        }
        poured.remove(waterAt);
        tries = 0;
        index++;
        phase = Phase.NEXT;
        return ActionStatus.RUNNING;
    }

    /**
     * Scoops back, from where the bot stands, every poured cell that is still a source: lava that has not turned to
     * obsidian and water. Null when none is left; otherwise what stays and why, for the failure text.
     */
    private String cleanUp(AiPlayer p) {
        if (p == null || poured.isEmpty()) return null;
        ServerLevel level = p.level();
        List<String> left = new ArrayList<>();
        for (BlockPos cell : List.copyOf(poured)) {
            boolean lava = level.getFluidState(cell).isSourceOfType(Fluids.LAVA);
            if (!lava && !level.getFluidState(cell).isSourceOfType(Fluids.WATER)) {
                poured.remove(cell);
                continue;
            }
            BlockUse.Result r = InventoryOps.has(p, Items.BUCKET, 1) ? Buckets.fill(p, cell) : null;
            if (r == BlockUse.Result.USED) {
                poured.remove(cell);
            } else {
                left.add((lava ? "lava" : "water") + " at " + LightPortalAction.coords(cell)
                        + (r == null ? " (no empty bucket to scoop it)" : " (" + r.name().toLowerCase(Locale.ROOT).replace('_', ' ') + ")"));
            }
        }
        return left.isEmpty() ? null : "left " + String.join(", ", left);
    }

    /** The bucket own test: a source within reach that is the first one on the ray from the eyes to its centre. */
    private static boolean canFillFrom(AiPlayer p, Vec3 eyes, BlockPos source) {
        Vec3 centre = Vec3.atCenterOf(source);
        double reach = p.blockInteractionRange();
        if (eyes.distanceToSqr(centre) > reach * reach) return false;
        BlockHitResult hit = p.level().clip(new ClipContext(eyes, centre, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, p));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(source);
    }

    // ---------------------------------------------------------------- taking the wall down

    /** Breaks the wall blocks, highest first; one that cannot be reached or broken is left standing. */
    private ActionStatus unwall(BotContext ctx, ServerLevel level) {
        while (unwallIndex < wallCells.size() && !level.getBlockState(wallCells.get(unwallIndex)).is(wallBlock)) unwallIndex++;
        if (unwallIndex >= wallCells.size()) return endWall(level);
        BlockPos target = wallCells.get(unwallIndex);
        if (!breaking) {
            if (approach == null) startApproach(target, REACH);
            Approach.Status s = approach.tick(ctx);
            if (s.state() == Approach.State.RUNNING) return ActionStatus.RUNNING;
            approach = null;
            if (s.state() == Approach.State.FAILED) {
                unwallIndex++;
                return ActionStatus.RUNNING;
            }
            ctx.breaker().start(target);
            breaking = true;
        }
        BlockBreaker.Status s = ctx.breaker().tick();
        switch (s.state()) {
            case RUNNING:
                return ActionStatus.RUNNING;
            case DONE:
                breaking = false;
                unwallIndex++;
                return ActionStatus.RUNNING;
            default:
                breaking = false;
                if (++tries >= MAX_TRIES) {
                    tries = 0;
                    unwallIndex++;
                }
                return ActionStatus.RUNNING;
        }
    }

    private ActionStatus endWall(ServerLevel level) {
        boolean gone = wallCells.stream().noneMatch(b -> level.getBlockState(b).is(wallBlock));
        if (gone) StructureRegistry.of(level.getServer()).store().remove(wallName);
        AABB box = new AABB(wallCells.get(0)).minmax(new AABB(wallCells.get(wallCells.size() - 1))).inflate(CLIMB_BLOCKS + 3);
        gatherer = new ItemGatherer(c -> c.player().level().getEntitiesOfClass(ItemEntity.class, box, e -> InventoryOps.isThrowaway(e.getItem())));
        gatherTicks = 0;
        phase = Phase.GATHER;
        return ActionStatus.RUNNING;
    }

    private ActionStatus gather(BotContext ctx, AiPlayer p) {
        if (++gatherTicks <= GATHER_TICKS && gatherer.tick(ctx)) return ActionStatus.RUNNING;
        gatherer.stop(ctx);
        return finishCast(p);
    }

    private ActionStatus finishCast(AiPlayer p) {
        phase = Phase.LIGHT;
        if (InventoryOps.count(p, LightPortalAction.IGNITER) == 0) {
            summary = "cast a nether portal at " + LightPortalAction.coords(origin) + " (not lit)";
            return ActionStatus.DONE;
        }
        Pos base = PortalFrame.frameCells(new Pos(origin.getX(), origin.getY(), origin.getZ()), facing).get(0);
        light = new LightPortalAction(block(base));
        return ActionStatus.RUNNING;
    }

    private ActionStatus lighting(BotContext ctx) {
        ActionStatus s = light.tick(ctx);
        if (s.state() == ActionStatus.State.DONE) {
            summary = "cast a nether portal at " + LightPortalAction.coords(origin) + " (lit)";
        }
        return s;
    }

    // ---------------------------------------------------------------- walking

    private void startApproach(BlockPos target, double radius) {
        approachTarget = target;
        approachRadius = Math.max(1.5, radius);
        approach = Approach.block(target, approachRadius);
    }

    /** Ticks the walk; null once it has arrived, so the caller carries on in the same tick. */
    private ActionStatus walkOnly(BotContext ctx) {
        if (approach == null) approach = Approach.block(approachTarget, approachRadius);
        Approach.Status s = approach.tick(ctx);
        switch (s.state()) {
            case ARRIVED:
                approach = null;
                return null;
            case FAILED:
                return ActionStatus.failed(s.reason());
            default:
                return ActionStatus.RUNNING;
        }
    }

    private ActionStatus walk(BotContext ctx, Phase after) {
        ActionStatus walking = walkOnly(ctx);
        if (walking != null) return walking;
        phase = after;
        return ActionStatus.RUNNING;
    }

    /** Cells the bot may not stand in: the frame, its corners and interior, and the wall. */
    private Set<BlockPos> keepOut() {
        Set<BlockPos> out = new HashSet<>(site);
        out.addAll(wallCells);
        return out;
    }

    /** Throwaway blocks the bot can climb on beside the frame. */
    private static int spare(AiPlayer p) {
        return Math.min(CLIMB_BLOCKS, InventoryOps.throwawayCount(p));
    }

    /**
     * Goes to the nearest cell near {@code around} whose eyes pass {@code rule} (a clear look at the face the bucket needs);
     * straight on to {@code after} when the bot already stands on one.
     */
    private ActionStatus toSpot(AiPlayer p, BlockPos around, Predicate<Vec3> rule, Phase walking, Phase after) {
        spot = null;
        spotAround = around;
        spotRule = rule;
        spotAfter = after;
        spotFailures = 0;
        phase = rule.test(p.getEyePosition()) ? after : walking;
        return ActionStatus.RUNNING;
    }

    private ActionStatus walkToSpot(BotContext ctx, AiPlayer p) {
        if (spot == null) {
            Optional<BlockPos> found = StandingSpot.find(p, spotAround, keepOut(), badSpots, spare(p), spotRule);
            if (found.isEmpty()) {
                return ActionStatus.failed("nowhere to stand within reach of " + LightPortalAction.coords(spotAround));
            }
            spot = found.get();
            go(ctx);
            return ActionStatus.RUNNING;
        }
        NavStatus nav = ctx.navigator().status();
        switch (nav.state()) {
            case ARRIVED:
                spot = null;
                phase = spotAfter;
                break;
            case FAILED:
                badSpots.add(spot);
                spot = null;
                if (++spotFailures >= MAX_TRIES) return ActionStatus.failed("could not get to a place to work on " + LightPortalAction.coords(spotAround));
                break;
            case IDLE:
                go(ctx);
                break;
            default:
                break;
        }
        return ActionStatus.RUNNING;
    }

    private void go(BotContext ctx) {
        ctx.navigator().goTo(new NearGoal(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0.5));
    }

    private static BlockPos block(Pos p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    @Override
    public void interrupted(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
        approach = null; // walks again from wherever the reflex left the bot
        if (spot != null) {
            spot = null;
            ctx.navigator().stop();
        }
        if (breaking) {
            ctx.breaker().cancel();
            breaking = false;
        }
        if (wall != null && phase == Phase.WALL) wall.interrupted(ctx);
        if (light != null) light.interrupted(ctx);
    }

    @Override
    public void cancel(BotContext ctx) {
        cleanUp(ctx.player());
        if (approach != null) approach.cancel(ctx);
        if (breaking) ctx.breaker().cancel();
        if (wall != null && phase == Phase.WALL) wall.cancel(ctx);
        if (gatherer != null) gatherer.stop(ctx);
        if (light != null) light.cancel(ctx);
        ctx.navigator().stop();
    }

    @Override
    public String describe() {
        return summary != null ? summary : "cast portal";
    }

    @Override
    public int timeoutTicks() {
        return 9600;
    }
}
