package com.aiciv.building;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.ItemGatherer;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import com.aiciv.nav.Pos;
import com.aiciv.world.BlockBreaker;
import com.aiciv.world.BlockPlacer;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemNames;
import com.aiciv.world.PlacedBlocks;
import com.aiciv.world.PlacementStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Builds a blueprint cell by cell the way a player would: checks everything it can before touching the world, clears
 * natural blocks, places from the bottom up (the navigator pillars when a cell is high), takes its own scaffolding down
 * again and registers the structure. At most one placement or break every {@value #PACE} ticks so it is watchable.
 */
public final class BuildAction implements Action {
    static final int PACE = 4;
    static final int MAX_DISTANCE = 48;
    static final double REACH = 4.0;
    static final double CLOSE = 2.5;
    static final int REFUSED_TICKS = 20;
    static final int SCAFFOLD_MARGIN = 4;

    private enum Mode { START, CHOOSE, APPROACH, WORK, MOVE }

    private enum Job { CLEAR, PLACE, SCAFFOLD }

    private final String name;
    private final Blueprint blueprint;
    private final BlockPos givenOrigin;
    private final Facing givenFacing;
    private final String kind;
    private final int timeout;

    private Mode mode = Mode.START;
    private BuildPlan plan;
    private BlockPos origin;
    private long startTick;
    private AABB scaffoldBox;
    /** Cells of the plan: the blocks to place, the cells that must be air, and every cell the plan touches, top-down. */
    private final Map<BlockPos, Block> wanted = new HashMap<>();
    private final List<BlockPos> clearOrder = new ArrayList<>();
    private final Set<BlockPos> planCells = new HashSet<>();
    private final Set<Item> materialItems = new HashSet<>();
    /** Block states of the cells that have any, with relative facings already made absolute. */
    private final Map<BlockPos, Map<String, String>> wantedProps = new HashMap<>();
    private OrientedPlacer oriented;

    private Job job;
    private BlockPos target;
    private Approach approach;
    private int reachTries;
    private int views;
    private final Set<BlockPos> tried = new HashSet<>();
    private int refused;
    private int cooldown;
    private boolean breaking;
    private final Set<BlockPos> deferred = new HashSet<>();
    private final Set<BlockPos> giveUp = new HashSet<>();
    /** Picks the scaffolding back up: the break leaves the drops where the block stood, often out of the bot's reach. */
    private final ItemGatherer gatherer = new ItemGatherer(this::scaffoldDrops);

    public BuildAction(String name, Blueprint blueprint, BlockPos originOrNull, Facing facingOrNull, String kind) {
        this.name = name;
        this.blueprint = blueprint;
        this.givenOrigin = originOrNull;
        this.givenFacing = facingOrNull;
        this.kind = kind;
        this.timeout = Math.min(24000, 600 + 40 * blueprint.placeCount());
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        ServerLevel level = p.level();
        if (mode == Mode.START) {
            ActionStatus problem = start(p, level);
            if (problem != null) return problem;
            mode = Mode.CHOOSE;
        }
        return switch (mode) {
            case CHOOSE -> choose(ctx, p, level);
            case APPROACH -> approach(ctx, p, level);
            case WORK -> work(ctx, p, level);
            case MOVE -> move(ctx, p, level);
            default -> ActionStatus.RUNNING;
        };
    }

    // ---------------------------------------------------------------- the checks before anything is touched

    private ActionStatus start(AiPlayer p, ServerLevel level) {
        Facing facing = givenFacing != null ? givenFacing : Facing.of(p.getDirection());
        if (givenOrigin != null) {
            origin = givenOrigin;
        } else {
            BlockPos feet = p.blockPosition();
            int half = blueprint.width() / 2;
            origin = new BlockPos(feet.getX() + 2 * facing.forwardX() - half * facing.rightX(), feet.getY(),
                    feet.getZ() + 2 * facing.forwardZ() - half * facing.rightZ());
        }
        plan = BuildPlan.of(blueprint, new Pos(origin.getX(), origin.getY(), origin.getZ()), facing);
        Pos min = plan.min(), max = plan.max();
        if (min.y() < level.getMinY() || max.y() > level.getMaxY()) return fail("it would not fit between the bottom and the top of the world");
        Vec3 here = p.position();
        for (int x : new int[] {min.x(), max.x()}) {
            for (int y : new int[] {min.y(), max.y()}) {
                for (int z : new int[] {min.z(), max.z()}) {
                    if (here.distanceTo(new Vec3(x + 0.5, y + 0.5, z + 0.5)) > MAX_DISTANCE) return fail("too far: walk within " + MAX_DISTANCE + " blocks first");
                }
            }
        }
        Map<String, Block> blocks = new LinkedHashMap<>();
        for (BuildPlan.Cell c : plan.places()) {
            if (blocks.containsKey(c.block())) continue;
            String problem = resolve(c.block(), blocks);
            if (problem != null) return fail(problem);
        }
        for (BuildPlan.Cell c : plan.places()) {
            String problem = stateProblem(c.block(), blocks.get(c.block()), c.props());
            if (problem != null) return fail(problem);
        }
        String door = BuildPlan.doorProblem(blueprint);
        if (door != null) return fail(door);

        for (BuildPlan.Cell c : plan.places()) {
            BlockPos pos = toBlockPos(c.pos());
            wanted.put(pos, blocks.get(c.block()));
            if (!c.props().isEmpty()) wantedProps.put(pos, c.props());
            planCells.add(pos);
            clearOrder.add(pos);
            materialItems.add(blocks.get(c.block()).asItem());
        }
        for (Pos a : plan.mustBeAir()) {
            BlockPos pos = toBlockPos(a);
            planCells.add(pos);
            clearOrder.add(pos);
        }
        BlockPos feet = p.blockPosition();
        clearOrder.sort(Comparator.comparingInt((BlockPos b) -> -b.getY()).thenComparingDouble(b -> b.distSqr(feet)));

        List<String> blocked = new ArrayList<>();
        for (BlockPos pos : clearOrder) {
            if (!needsClear(level, pos)) continue;
            String problem = clearProblem(level, p, pos);
            if (problem != null) blocked.add(problem);
        }
        if (!blocked.isEmpty()) return fail("can't build here: " + String.join("; ", blocked.subList(0, Math.min(3, blocked.size()))) + "; pick another spot");

        List<BuildPlan.Cell> floating = plan.floating(pos -> {
            BlockPos b = toBlockPos(pos);
            if (planCells.contains(b)) return false;
            BlockState s = level.getBlockState(b);
            return !s.getCollisionShape(level, b).isEmpty();
        });
        if (!floating.isEmpty()) {
            Pos f = floating.get(0).pos();
            return fail("cell " + f.x() + " " + f.y() + " " + f.z() + " would float; give it support in the blueprint");
        }
        if (StructureRegistry.of(level.getServer()).store().get(name).isPresent()) return fail("a structure named " + name + " exists");

        Map<String, Integer> needed = plan.materials(pos -> blockId(level.getBlockState(toBlockPos(pos))));
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Integer> e : needed.entrySet()) {
            Item item = blocks.get(e.getKey()).asItem();
            int lacking = e.getValue() - InventoryOps.count(p, s -> s.is(item));
            if (lacking > 0) missing.add(lacking + " " + e.getKey());
        }
        if (!missing.isEmpty()) return fail("missing materials: " + String.join(", ", missing));

        startTick = level.getGameTime();
        scaffoldBox = new AABB(min.x(), min.y(), min.z(), max.x() + 1, max.y() + 1, max.z() + 1).inflate(SCAFFOLD_MARGIN);
        return null;
    }

    /** Looks the legend id up; a message when a player could not place it by hand. */
    private static String resolve(String id, Map<String, Block> into) {
        Identifier key = Identifier.tryParse("minecraft:" + id);
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
            List<String> close = ItemNames.suggestions(id, 3);
            return id + " can't be placed by hand (there is no block with that id; did you mean " + String.join(", ", close) + "?)";
        }
        Block block = BuiltInRegistries.BLOCK.getValue(key);
        if (block.asItem() == Items.AIR) return id + " can't be placed by hand";
        if (id.endsWith("_bed")) return id + " can't be built from a blueprint; use place for beds";
        into.put(id, block);
        return null;
    }

    /** Properties a player can choose by placing; the rest are the game's. */
    static final List<String> HAND_PROPERTIES = List.of("facing", "delay", "mode", "face", "axis", "powered");

    /** Why these block states can't be built by hand, or null. */
    static String stateProblem(String id, Block block, Map<String, String> props) {
        for (Map.Entry<String, String> e : props.entrySet()) {
            String name = e.getKey();
            if (!HAND_PROPERTIES.contains(name) || (name.equals("powered") && !id.equals("lever"))) {
                return id + ": property \"" + name + "\" can't be chosen by hand (supported: " + String.join(", ", HAND_PROPERTIES) + ")";
            }
            net.minecraft.world.level.block.state.properties.Property<?> property = block.getStateDefinition().getProperty(name);
            if (property == null || property.getValue(e.getValue()).isEmpty()) return id + " has no " + name + "=" + e.getValue();
        }
        return null;
    }

    private ActionStatus fail(String reason) {
        return ActionStatus.failed(reason);
    }

    // ---------------------------------------------------------------- choosing the next job

    private ActionStatus choose(BotContext ctx, AiPlayer p, ServerLevel level) {
        if (cooldown > 0) {
            cooldown--;
            return ActionStatus.RUNNING;
        }
        BlockPos clear = nextClear(level);
        if (clear != null) {
            return begin(ctx, p, level, Job.CLEAR, clear);
        }
        List<BuildPlan.Cell> pending = new ArrayList<>();
        for (BuildPlan.Cell c : plan.places()) if (!level.getBlockState(toBlockPos(c.pos())).is(wanted.get(toBlockPos(c.pos())))) pending.add(c);
        if (!pending.isEmpty()) {
            BuildPlan.Cell next = nextPlace(level, pending);
            if (next == null) {
                Pos f = pending.get(0).pos();
                return fail("nothing to build against at " + f.x() + " " + f.y() + " " + f.z());
            }
            return begin(ctx, p, level, Job.PLACE, toBlockPos(next.pos()));
        }
        BlockPos scaffold = nextScaffold(level, p);
        if (scaffold != null) return begin(ctx, p, level, Job.SCAFFOLD, scaffold);
        if (gatherer.tick(ctx)) return ActionStatus.RUNNING;
        return finish(ctx, p, level);
    }

    private List<ItemEntity> scaffoldDrops(BotContext ctx) {
        return ctx.player().level().getEntitiesOfClass(ItemEntity.class, scaffoldBox, item -> InventoryOps.isThrowaway(item.getItem()));
    }

    private ActionStatus begin(BotContext ctx, AiPlayer p, ServerLevel level, Job next, BlockPos at) {
        job = next;
        target = at;
        reachTries = 0;
        refused = 0;
        breaking = false;
        views = 0;
        tried.clear();
        // The gatherer's walk to a drop may still be running (and pillaring); a job's own approach and break need the navigator and the bot still.
        gatherer.stop(ctx);
        if (next == Job.PLACE) {
            // A block is placed from wherever the bot can already see and reach a face to lean on; it only walks when it cannot.
            mode = Mode.WORK;
            return work(ctx, p, level);
        }
        startApproach(ctx, p, REACH);
        return approach(ctx, p, level);
    }

    /** The first planned cell, top-down, that holds something in the way. */
    private BlockPos nextClear(ServerLevel level) {
        for (BlockPos pos : clearOrder) if (needsClear(level, pos)) return pos;
        return null;
    }

    private boolean needsClear(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block want = wanted.get(pos);
        if (want == null) return !state.isAir() && !isWater(state);
        return !state.is(want) && !state.canBeReplaced();
    }

    private static boolean isWater(BlockState state) {
        return state.getBlock() instanceof LiquidBlock && state.getFluidState().is(FluidTags.WATER);
    }

    /** Lowest unfinished layer first; the first cell in plan order that has something solid to lean on right now. */
    private BuildPlan.Cell nextPlace(ServerLevel level, List<BuildPlan.Cell> pending) {
        int lowest = Integer.MAX_VALUE;
        for (BuildPlan.Cell c : pending) lowest = Math.min(lowest, c.pos().y());
        for (boolean onlyLowest : new boolean[] {true, false}) {
            for (BuildPlan.Cell c : pending) {
                BlockPos pos = toBlockPos(c.pos());
                if (onlyLowest && c.pos().y() != lowest) continue;
                if (deferred.contains(pos) || !level.getBlockState(pos).canBeReplaced()) continue;
                for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                    if (!level.getBlockState(pos.relative(d)).canBeReplaced()) return c;
                }
            }
        }
        return null;
    }

    /** Throwaway blocks this build placed itself, outside the finished structure, highest first. */
    private BlockPos nextScaffold(ServerLevel level, AiPlayer p) {
        BlockPos best = null;
        for (BlockPos pos : PlacedBlocks.of(level.getServer()).placedSince(level, p.getScoreboardName(), startTick)) {
            if (giveUp.contains(pos) || !isScaffold(level, pos)) continue;
            if (best == null || pos.getY() > best.getY() || (pos.getY() == best.getY() && pos.distSqr(p.blockPosition()) < best.distSqr(p.blockPosition()))) {
                best = pos;
            }
        }
        return best;
    }

    private boolean isScaffold(ServerLevel level, BlockPos pos) {
        if (!scaffoldBox.contains(Vec3.atCenterOf(pos))) return false;
        BlockState state = level.getBlockState(pos);
        Block want = wanted.get(pos);
        if (want != null && state.is(want)) return false;
        return InventoryOps.isThrowaway(new ItemStack(state.getBlock().asItem()));
    }

    // ---------------------------------------------------------------- walking to the job

    private void startApproach(BotContext ctx, AiPlayer p, double radius) {
        approach = Approach.block(target, radius);
        mode = Mode.APPROACH;
    }

    private ActionStatus approach(BotContext ctx, AiPlayer p, ServerLevel level) {
        Approach.Status s = approach.tick(ctx);
        switch (s.state()) {
            case RUNNING:
                return ActionStatus.RUNNING;
            case FAILED:
                return reachFailed(ctx, p, s.reason());
            default:
                mode = Mode.WORK;
                return work(ctx, p, level);
        }
    }

    /** The first failure retries from closer; a second gives the cell up (scaffold) or ends the build. */
    private ActionStatus reachFailed(BotContext ctx, AiPlayer p, String reason) {
        breaking = false;
        ctx.breaker().cancel();
        if (job == Job.SCAFFOLD) {
            giveUp.add(target);
            mode = Mode.CHOOSE;
            return ActionStatus.RUNNING;
        }
        if (job == Job.PLACE) return toViewpoint(ctx, p, p.level(), reason);
        if (++reachTries >= 2) return fail("could not reach " + coords(target) + " (" + reason + ")");
        startApproach(ctx, p, CLOSE);
        return approach(ctx, p, p.level());
    }

    /**
     * The navigator pillars with the first throwaway block it finds. Move one that is not building material in front of
     * the material, so the structure's blocks are not spent on scaffolding while spare ones exist.
     */
    private void keepMaterialsOutOfTheScaffold(AiPlayer p) {
        Inventory inv = p.getInventory();
        NonNullList<ItemStack> main = inv.getNonEquipmentItems();
        int spare = -1, material = -1;
        for (int i = 0; i < main.size(); i++) {
            ItemStack s = main.get(i);
            if (!InventoryOps.isThrowaway(s)) continue;
            if (materialItems.contains(s.getItem())) {
                if (material < 0) material = i;
            } else if (spare < 0) {
                spare = i;
            }
        }
        if (spare >= 0 && material >= 0 && material < spare) {
            ItemStack a = main.get(spare), b = main.get(material);
            inv.setItem(material, a);
            inv.setItem(spare, b);
        }
    }

    // ---------------------------------------------------------------- the work itself

    private ActionStatus work(BotContext ctx, AiPlayer p, ServerLevel level) {
        return switch (job) {
            case PLACE -> place(ctx, p, level);
            case CLEAR -> clear(ctx, p, level, false);
            case SCAFFOLD -> clear(ctx, p, level, true);
        };
    }

    private ActionStatus place(BotContext ctx, AiPlayer p, ServerLevel level) {
        Block want = wanted.get(target);
        BlockState now = level.getBlockState(target);
        // A block being set (placed, delay still to be clicked) is the placer's until it reports.
        if (oriented == null && (now.is(want) || !now.canBeReplaced())) {
            mode = Mode.CHOOSE;
            return ActionStatus.RUNNING;
        }
        Map<String, String> props = wantedProps.get(target);
        if (props != null) return placeOriented(ctx, level, want, props);
        if (p.getBoundingBox().intersects(new AABB(target))) return toViewpoint(ctx, p, level, "standing in the way");
        Item item = want.asItem();
        switch (BlockPlacer.place(p, target, s -> s.is(item))) {
            case PLACED:
                cooldown = PACE - 1;
                deferred.clear();
                mode = Mode.CHOOSE;
                return ActionStatus.RUNNING;
            case NO_ITEM:
                return fail("ran out of " + blockId(want.defaultBlockState()));
            case OCCUPIED:
                mode = Mode.CHOOSE;
                return ActionStatus.RUNNING;
            case NO_SUPPORT:
                deferred.add(target);
                mode = Mode.CHOOSE;
                return ActionStatus.RUNNING;
            case OUT_OF_REACH:
                return toViewpoint(ctx, p, level, "out of reach");
            case NOT_VISIBLE:
                return toViewpoint(ctx, p, level, "can't see a face to place against");
            default:
                // Usually an entity standing in the cell; give it a moment to move.
                if (++refused >= REFUSED_TICKS) {
                    return fail("could not place " + blockId(want.defaultBlockState()) + " at " + coords(target) + " - something is in the way");
                }
                return ActionStatus.RUNNING;
        }
    }

    /** A block whose facing comes from where the bot looks: see {@link OrientedPlacer}. */
    private ActionStatus placeOriented(BotContext ctx, ServerLevel level, Block want, Map<String, String> props) {
        if (oriented == null) oriented = new OrientedPlacer(target, want, blockId(want.defaultBlockState()), props, planCells);
        OrientedPlacer.Status s = oriented.tick(ctx);
        switch (s.state()) {
            case RUNNING:
                return ActionStatus.RUNNING;
            case DONE:
                oriented = null;
                cooldown = PACE - 1;
                deferred.clear();
                mode = Mode.CHOOSE;
                return ActionStatus.RUNNING;
            default:
                oriented = null;
                return fail(s.reason());
        }
    }

    /** Breaks the target: a natural block in the way, or a piece of this build's own scaffolding. */
    private ActionStatus clear(BotContext ctx, AiPlayer p, ServerLevel level, boolean scaffold) {
        boolean stillThere = scaffold ? isScaffold(level, target) : needsClear(level, target);
        if (!stillThere) {
            breaking = false;
            ctx.breaker().cancel();
            mode = Mode.CHOOSE;
            return ActionStatus.RUNNING;
        }
        if (!scaffold) {
            String problem = clearProblem(level, p, target);
            if (problem != null) return fail("can't build here: " + problem + "; pick another spot");
        }
        if (!breaking) {
            ctx.breaker().start(target);
            breaking = true;
        }
        BlockBreaker.Status s = ctx.breaker().tick();
        switch (s.state()) {
            case RUNNING:
                return ActionStatus.RUNNING;
            case DONE:
                breaking = false;
                cooldown = PACE - 1;
                mode = Mode.CHOOSE;
                return ActionStatus.RUNNING;
            default:
                breaking = false;
                if (s.reason().equals("block changed")) {
                    mode = Mode.CHOOSE;
                    return ActionStatus.RUNNING;
                }
                if (s.reason().equals(BlockBreaker.NOT_IN_SIGHT) || s.reason().equals("out of reach")) return reachFailed(ctx, p, s.reason());
                if (scaffold) {
                    giveUp.add(target);
                    mode = Mode.CHOOSE;
                    return ActionStatus.RUNNING;
                }
                return fail("could not clear " + coords(target) + ": " + s.reason());
        }
    }

    /** Why this block may not be broken to make room, or null when it may (natural, or the bot's own). */
    private String clearProblem(ServerLevel level, AiPlayer p, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Optional<PlacementStore.Placement> placed = PlacedBlocks.of(level.getServer()).at(level, pos);
        if (placed.isPresent() && !placed.get().placer().equals(p.getScoreboardName())) {
            return coords(pos) + " (" + blockId(state) + " placed by " + placed.get().placer() + ")";
        }
        boolean harmful = !state.getFluidState().isEmpty() && !state.getFluidState().is(FluidTags.WATER);
        if (level.getBlockEntity(pos) != null || state.getDestroySpeed(level, pos) < 0 || harmful || !level.mayInteract(p, pos)) {
            return coords(pos) + " (" + blockId(state) + ")";
        }
        return null;
    }

    // ---------------------------------------------------------------- finding a place to stand

    static final int MAX_VIEWS = 4;
    static final int VIEW_RADIUS = 4;
    /** A little under the placer's own 4.5, so a spot that just passes here still passes there. */
    static final double VIEW_REACH = 4.3;

    /**
     * The bot cannot place the target from where it stands: it walks to the nearest spot from which a face next to
     * the target is both in reach and in plain sight. The navigator pillars up when the spot is in mid-air.
     */
    private ActionStatus toViewpoint(BotContext ctx, AiPlayer p, ServerLevel level, String why) {
        if (views == 0) tried.add(p.blockPosition());
        BlockPos spot = ++views > MAX_VIEWS ? null : viewpoint(level, p);
        if (spot == null) return fail("could not reach " + coords(target) + " (" + why + ")");
        tried.add(spot);
        keepMaterialsOutOfTheScaffold(p);
        ctx.navigator().goTo(new NearGoal(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0.8));
        mode = Mode.MOVE;
        return ActionStatus.RUNNING;
    }

    private ActionStatus move(BotContext ctx, AiPlayer p, ServerLevel level) {
        NavStatus nav = ctx.navigator().status();
        if (nav.state() == NavStatus.State.RUNNING) return ActionStatus.RUNNING;
        if (nav.state() == NavStatus.State.FAILED) return toViewpoint(ctx, p, level, nav.reason());
        mode = Mode.WORK;
        return work(ctx, p, level);
    }

    /** Feet cell near the target with a clear view of a supporting face; walkable spots before mid-air ones, nearest first. */
    private BlockPos viewpoint(ServerLevel level, AiPlayer p) {
        Vec3 here = p.position();
        BlockPos best = null;
        double bestCost = Double.MAX_VALUE;
        for (int dx = -VIEW_RADIUS; dx <= VIEW_RADIUS; dx++) {
            for (int dz = -VIEW_RADIUS; dz <= VIEW_RADIUS; dz++) {
                for (int dy = -VIEW_RADIUS; dy <= 1; dy++) {
                    BlockPos feet = target.offset(dx, dy, dz);
                    if (tried.contains(feet) || feet.equals(target) || feet.above().equals(target)) continue;
                    // Standing in a neighbouring cell the body may still reach into the target's.
                    if (dy >= -1 && dy <= 0 && Math.max(Math.abs(dx), Math.abs(dz)) <= 1) continue;
                    if (wanted.containsKey(feet) || wanted.containsKey(feet.above())) continue;
                    if (!roomToStand(level, feet)) continue;
                    boolean footing = level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP);
                    double cost = Vec3.atBottomCenterOf(feet).distanceTo(here) + (footing ? 0 : 6);
                    if (cost >= bestCost || !seesAFace(level, p, feet)) continue;
                    best = feet;
                    bestCost = cost;
                }
            }
        }
        return best;
    }

    private static boolean roomToStand(ServerLevel level, BlockPos feet) {
        for (BlockPos cell : new BlockPos[] {feet, feet.above()}) {
            BlockState s = level.getBlockState(cell);
            if (!s.getCollisionShape(level, cell).isEmpty() || !s.getFluidState().isEmpty()) return false;
        }
        return true;
    }

    /** The placer's own test, from a hypothetical standing spot: a leaned-on face of the target in reach and in sight. */
    private boolean seesAFace(ServerLevel level, AiPlayer p, BlockPos feet) {
        Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
        Vec3 centre = Vec3.atCenterOf(target);
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            BlockPos neighbour = target.relative(d);
            if (level.getBlockState(neighbour).canBeReplaced()) continue;
            Vec3 face = centre.add(d.getStepX() * 0.5, d.getStepY() * 0.5, d.getStepZ() * 0.5);
            if (eye.distanceTo(face) > VIEW_REACH) continue;
            Vec3 aim = centre.add(d.getStepX() * 0.55, d.getStepY() * 0.55, d.getStepZ() * 0.55);
            BlockHitResult ray = level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
            if (ray.getType() == HitResult.Type.BLOCK && (ray.getBlockPos().equals(neighbour) || ray.getBlockPos().equals(target))) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- done

    private ActionStatus finish(BotContext ctx, AiPlayer p, ServerLevel level) {
        BlockPos spot = origin;
        Structure structure = new Structure(name, p.getScoreboardName(), level.dimension().identifier().toString(),
                new Pos(spot.getX(), spot.getY(), spot.getZ()), plan.min(), plan.max(), kind, level.getGameTime());
        boolean added = StructureRegistry.of(level.getServer()).store().add(structure);
        int cells = plan.places().size();
        ctx.event("built " + name + ": " + cells + " blocks at " + coords(spot)
                + (added ? "" : " (another structure took the name meanwhile, so it is not listed)"));
        return ActionStatus.DONE;
    }

    // ---------------------------------------------------------------- helpers

    @Override
    public void interrupted(BotContext ctx) {
        ctx.breaker().cancel();
        // A reflex moved the bot: forget the walk and the dig and choose again from where it stands.
        breaking = false;
        approach = null;
        // Finish a block that is half set (placed, delay still to be clicked) where it stands.
        if (mode != Mode.START) mode = oriented != null && job == Job.PLACE ? Mode.WORK : Mode.CHOOSE;
        cooldown = 0;
    }

    @Override
    public void cancel(BotContext ctx) {
        ctx.breaker().cancel();
        if (approach != null) approach.cancel(ctx);
        if (oriented != null) oriented.cancel(ctx);
        ctx.navigator().stop();
    }

    @Override
    public String describe() {
        return "build " + name;
    }

    @Override
    public int timeoutTicks() {
        return timeout;
    }

    private static BlockPos toBlockPos(Pos p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    private static String coords(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }
}
