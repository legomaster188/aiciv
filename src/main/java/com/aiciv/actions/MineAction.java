package com.aiciv.actions;

import com.aiciv.bot.AiPlayer;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import com.aiciv.nav.Pos;
import com.aiciv.perception.Perceiver;
import com.aiciv.world.BlockBreaker;
import com.aiciv.world.BlockNames;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.PlacedBlocks;
import com.aiciv.world.Sight;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Mines blocks matching a name until {@code count} items have been gained, then picks up the drops.
 * It navigates and breaks one after the other, never at the same time, and breaks with the context's
 * own breaker, so the navigator can never cancel a dig in progress.
 */
public final class MineAction implements Action {
    static final int SCAN_RADIUS = 24;
    static final double STAND_RADIUS = 3.5;
    static final double COLLECT_RADIUS = 6;
    static final int COLLECT_TICKS = 4 * 20;
    /** "block changed" fires for any state change (leaf distance, age, light); this many in a row means something is wrong. */
    static final int MAX_RETRIES = 10;
    /** Blocks the bot digs through to get a hidden target in sight before it gives that target up. */
    static final int MAX_OCCLUDERS = 6;
    /** How close the bot walks when it cannot see a target and cannot dig toward it. */
    static final double CLOSE_RADIUS = 1.5;

    private enum Phase { PICK, NAVIGATE, BREAK, DIG, COLLECT }

    private final String query;
    private final int count;
    private final Predicate<String> matcher;
    private final ItemGatherer gatherer;
    private final Set<BlockPos> skipped = new HashSet<>();
    /** Item type -> how many the bot held when we first saw that a target drops it, so only new ones count. */
    private final Map<Item, Integer> baselines = new HashMap<>();
    private final Set<String> dropNames = new LinkedHashSet<>();
    private Phase phase = Phase.PICK;
    private boolean everTargeted;
    private int untracked;
    private String lastProblem;
    private BlockPos target;
    private int retries;
    /** Blocks dug out of the way to the current target, and whether the bot already walked up close to it. */
    private int dug;
    private boolean closer;
    private BlockPos tracked;
    private BlockPos collectCentre;
    private int collectTicks;

    public MineAction(String blockQuery, int count) {
        this.query = blockQuery.trim();
        this.count = count;
        this.matcher = BlockNames.matcher(blockQuery);
        this.gatherer = new ItemGatherer(this::dropsNearby);
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        return switch (phase) {
            case PICK -> pick(ctx);
            case NAVIGATE -> navigate(ctx);
            case BREAK -> breakBlock(ctx);
            case DIG -> dig(ctx);
            case COLLECT -> collect(ctx);
        };
    }

    // ---------------------------------------------------------------- picking

    private ActionStatus pick(BotContext ctx) {
        AiPlayer player = ctx.player();
        ServerLevel level = player.level();
        String toolProblem = null;
        List<BlockPos> candidates = candidates(ctx);
        // The block under its feet comes last, and only with solid ground under it: digging it drops the bot into the hole.
        BlockPos below = player.blockPosition().below();
        if (candidates.remove(below) && solidUnder(level, below)) candidates.add(below);
        for (BlockPos pos : candidates) {
            BlockState state = level.getBlockState(pos);
            if (state.getDestroySpeed(level, pos) < 0) {
                skip(pos, "can't break " + blockId(state));
                continue;
            }
            if (nextToFluid(level, pos)) {
                skip(pos, "water or lava next to " + blockId(state));
                continue;
            }
            if (!canHarvest(player, state)) {
                if (toolProblem == null) toolProblem = "needs a " + tier(state) + " pickaxe or better for " + blockId(state);
                continue;
            }
            everTargeted = true;
            target = pos;
            retries = 0;
            dug = 0;
            closer = false;
            ctx.navigator().goTo(new NearGoal(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, STAND_RADIUS));
            phase = Phase.NAVIGATE;
            return ActionStatus.RUNNING;
        }
        if (toolProblem != null) return ActionStatus.failed(toolProblem);
        int got = gained(player);
        String none = everTargeted ? "no more " + query + " known nearby" : "no " + query + " known nearby";
        if (lastProblem != null) none += " (" + lastProblem + ")";
        return ActionStatus.failed((got > 0 ? "got " + got + " of " + count + ": " : "") + none + " — explore to find some"
                + (everTargeted ? " more" : ""));
    }

    /** Matching blocks the bot can see or remembers, nearest first, minus the ones given up on. */
    private List<BlockPos> candidates(BotContext ctx) {
        AiPlayer player = ctx.player();
        ServerLevel level = player.level();
        Vec3 here = player.position();
        Set<BlockPos> found = new LinkedHashSet<>();
        for (Map.Entry<Pos, String> e : Perceiver.exposedBlocks(level, player.blockPosition(), SCAN_RADIUS, matcher)) {
            found.add(new BlockPos(e.getKey().x(), e.getKey().y(), e.getKey().z()));
        }
        BlockPos feet = player.blockPosition();
        Pos from = new Pos(feet.getX(), feet.getY(), feet.getZ());
        for (Map.Entry<Pos, String> e : ctx.knownBlocks().nearestN(from, Integer.MAX_VALUE, 0)) {
            if (!matcher.test(e.getValue())) continue;
            BlockPos pos = new BlockPos(e.getKey().x(), e.getKey().y(), e.getKey().z());
            // A remembered block that has since turned into something else is gone for good.
            if (level.isLoaded(pos) && !matcher.test(blockId(level.getBlockState(pos)))) {
                ctx.knownBlocks().gone(e.getKey());
                continue;
            }
            found.add(pos);
        }
        PlacedBlocks placed = PlacedBlocks.of(level.getServer());
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos pos : found) if (!skipped.contains(pos) && !placed.isPlaced(level, pos)) out.add(pos);
        out.sort(Comparator.comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(here)));
        return out;
    }

    // ---------------------------------------------------------------- walking and breaking

    private ActionStatus navigate(BotContext ctx) {
        NavStatus s = ctx.navigator().status();
        switch (s.state()) {
            case RUNNING:
                return ActionStatus.RUNNING;
            case FAILED:
                skip(target, s.reason());
                phase = Phase.PICK;
                return ActionStatus.RUNNING;
            case IDLE:
                return ActionStatus.failed("navigation stopped");
            default:
                break;
        }
        // Arrived. A block too high to reach from here is caught by the breaker's own reach check and skipped.
        return approach(ctx);
    }

    /**
     * Starts breaking the target if the bot can see it. A target behind other blocks is never hit through them: the
     * bot digs the block in the way first, as a player tunnels toward an ore, or walks closer when it cannot.
     */
    private ActionStatus approach(BotContext ctx) {
        AiPlayer player = ctx.player();
        ServerLevel level = player.level();
        BlockState state = level.getBlockState(target);
        if (!matcher.test(blockId(state))) {
            forget(ctx, target);
            phase = Phase.PICK;
            return ActionStatus.RUNNING;
        }
        if (target.equals(player.blockPosition().below()) && !solidUnder(level, target)) {
            skip(target, "it is what I stand on");
            phase = Phase.PICK;
            return ActionStatus.RUNNING;
        }
        BlockPos blocker = Sight.occluder(player, target);
        if (blocker == null) {
            if (!target.equals(tracked)) {
                trackDrops(player, level, target, state);
                tracked = target;
            }
            ctx.breaker().start(target);
            phase = Phase.BREAK;
        } else if (dug >= MAX_OCCLUDERS) {
            skip(target, "too much rock in the way");
            phase = Phase.PICK;
        } else if (diggable(player, level, blocker)) {
            dug++;
            ctx.breaker().start(blocker);
            phase = Phase.DIG;
        } else {
            walkCloser(ctx, "can't see it");
        }
        return ActionStatus.RUNNING;
    }

    /** Walks right up to a target it cannot see from here, once; after that the target is given up. */
    private void walkCloser(BotContext ctx, String problem) {
        if (closer) {
            skip(target, problem);
            phase = Phase.PICK;
            return;
        }
        closer = true;
        ctx.navigator().goTo(new NearGoal(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, CLOSE_RADIUS));
        phase = Phase.NAVIGATE;
    }

    /** Digging out a block between the bot and its target; then it looks again from where it stands. */
    private ActionStatus dig(BotContext ctx) {
        BlockBreaker.Status s = ctx.breaker().tick();
        if (s.state() == BlockBreaker.Status.State.RUNNING) return ActionStatus.RUNNING;
        if (s.state() == BlockBreaker.Status.State.DONE || s.reason().equals("block changed")) return approach(ctx);
        walkCloser(ctx, s.reason());
        return ActionStatus.RUNNING;
    }

    /** A block a player would dig through: breakable, not what the bot stands on, no container, holding back no liquid. */
    private static boolean diggable(AiPlayer player, ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getDestroySpeed(level, pos) < 0 || !level.mayInteract(player, pos) || level.getBlockEntity(pos) != null) return false;
        if (PlacedBlocks.of(level.getServer()).isPlacedByOther(level, pos, player.getScoreboardName())) return false;
        if (pos.equals(player.blockPosition().below())) return false;
        return !nextToFluid(level, pos);
    }

    private static boolean nextToFluid(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (!level.getFluidState(pos.relative(d)).isEmpty()) return true;
        }
        return false;
    }

    /** Solid, dry ground under {@code pos}, so a bot standing on {@code pos} lands on it when {@code pos} is dug out. */
    private static boolean solidUnder(ServerLevel level, BlockPos pos) {
        BlockPos under = pos.below();
        BlockState state = level.getBlockState(under);
        return !state.isAir() && state.getFluidState().isEmpty() && state.isFaceSturdy(level, under, Direction.UP);
    }

    private ActionStatus breakBlock(BotContext ctx) {
        BlockBreaker.Status s = ctx.breaker().tick();
        switch (s.state()) {
            case RUNNING:
                return ActionStatus.RUNNING;
            case DONE:
                minedOut(ctx, target);
                return ActionStatus.RUNNING;
            default:
                break;
        }
        if (s.reason().equals("block changed")) {
            BlockState now = ctx.player().level().getBlockState(target);
            if (now.isAir()) {
                minedOut(ctx, target); // something else broke it; its drops are lying there
            } else if (!matcher.test(blockId(now))) {
                forget(ctx, target);
                phase = Phase.PICK;
            } else if (++retries <= MAX_RETRIES) {
                ctx.breaker().start(target); // the same kind of block in another state: break it again
            } else {
                skip(target, "the block kept changing");
                phase = Phase.PICK;
            }
            return ActionStatus.RUNNING;
        }
        if (s.reason().equals(BlockBreaker.NOT_IN_SIGHT)) return approach(ctx); // something got in the way
        skip(target, s.reason());
        phase = Phase.PICK;
        return ActionStatus.RUNNING;
    }

    private void minedOut(BotContext ctx, BlockPos pos) {
        forget(ctx, pos);
        collectCentre = pos;
        collectTicks = 0;
        phase = Phase.COLLECT;
    }

    private ActionStatus collect(BotContext ctx) {
        if (++collectTicks <= COLLECT_TICKS && gatherer.tick(ctx)) return ActionStatus.RUNNING;
        gatherer.stop(ctx);
        if (gained(ctx.player()) >= count) return ActionStatus.DONE;
        phase = Phase.PICK;
        return ActionStatus.RUNNING;
    }

    private List<ItemEntity> dropsNearby(BotContext ctx) {
        Vec3 centre = Vec3.atCenterOf(collectCentre);
        return ctx.player().level().getEntitiesOfClass(ItemEntity.class,
                new AABB(collectCentre).inflate(COLLECT_RADIUS), item -> item.position().distanceTo(centre) <= COLLECT_RADIUS);
    }

    // ---------------------------------------------------------------- counting

    private void trackDrops(AiPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
        List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), player,
                InventoryOps.bestToolFor(player, state));
        if (drops.isEmpty()) untracked++; // nothing to count in the inventory, so count the block itself
        for (ItemStack drop : drops) {
            Item item = drop.getItem();
            dropNames.add(BuiltInRegistries.ITEM.getKey(item).getPath());
            baselines.computeIfAbsent(item, i -> InventoryOps.count(player, s -> s.is(i)));
        }
    }

    private int gained(AiPlayer player) {
        int n = untracked;
        for (Map.Entry<Item, Integer> e : baselines.entrySet()) {
            Item item = e.getKey();
            n += Math.max(0, InventoryOps.count(player, s -> s.is(item)) - e.getValue());
        }
        return n;
    }

    // ---------------------------------------------------------------- helpers

    private void skip(BlockPos pos, String problem) {
        skipped.add(pos);
        lastProblem = problem;
    }

    private static void forget(BotContext ctx, BlockPos pos) {
        ctx.knownBlocks().gone(new Pos(pos.getX(), pos.getY(), pos.getZ()));
    }

    private static boolean canHarvest(AiPlayer player, BlockState state) {
        if (!state.requiresCorrectToolForDrops()) return true;
        for (ItemStack s : player.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && s.isCorrectToolForDrops(state)) return true;
        }
        return false;
    }

    static String tier(BlockState state) {
        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) return "diamond";
        if (state.is(BlockTags.NEEDS_IRON_TOOL)) return "iron";
        if (state.is(BlockTags.NEEDS_STONE_TOOL)) return "stone";
        return "wooden";
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    @Override
    public void interrupted(BotContext ctx) {
        ctx.breaker().cancel();
        gatherer.stop(ctx);
        // A dig or walk in progress is dropped; picking again re-plans from here. Collecting resumes as it was.
        if (phase == Phase.NAVIGATE || phase == Phase.BREAK || phase == Phase.DIG) phase = Phase.PICK;
    }

    @Override
    public void cancel(BotContext ctx) {
        ctx.breaker().cancel();
        gatherer.stop(ctx);
        ctx.navigator().stop();
    }

    @Override
    public String describe() {
        String what = dropNames.size() == 1 ? dropNames.iterator().next() : BlockNames.normalise(query);
        return "mine " + what + " x" + count;
    }

    @Override
    public int timeoutTicks() {
        return 5 * 60 * 20;
    }
}
