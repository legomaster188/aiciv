package com.aiciv.dragon;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.combat.BowShot;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import com.aiciv.world.BlockBreaker;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.PlacedBlocks;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Destroys a caged end crystal: climbs beside its tower to the crystal's height, breaks the iron bars on the line to
 * it, bridges 9 blocks away from the cage (the explosion is close otherwise), shoots through the gap, takes its bridge
 * up behind it on the way back and climbs down again by breaking its own blocks from the top.
 */
public final class BreakCageAction implements Action {
    static final int MIN_THROWAWAYS = 24;
    static final int OUTSIDE = 3;
    static final int BRIDGE = 9;
    static final int MAX_ARROWS = 4;
    static final int MAX_BARS = 3;
    static final int MAX_NAV_FAILURES = 3;
    static final int CHARGE_TICKS = 20;

    private enum Stage { START, CLIMB, BREAK, RETREAT, SHOOT, DESCEND, DOWN }

    private final boolean anyDimension;
    private Stage stage = Stage.START;
    private EndCrystal crystal;
    private BlockPos crystalPos;
    private BlockPos ground;
    private Direction side;
    private BlockPos stand;
    private BlockPos far;
    private ActionStatus outcome;
    private boolean navigating;
    private int navFailures;
    private int barsBroken;
    private BowShot shot;
    private int shots;
    private int waitTicks;
    private long startTick;
    /** The own block being broken on the way back, and own blocks that could not be broken (left standing). */
    private BlockPos clearing;
    private final Set<BlockPos> leftStanding = new HashSet<>();
    private String summary;

    public BreakCageAction() {
        this(false);
    }

    /** {@code anyDimension} lets game tests run it in the overworld. */
    public BreakCageAction(boolean anyDimension) {
        this.anyDimension = anyDimension;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        return switch (stage) {
            case START -> start(ctx, p);
            case CLIMB -> walk(ctx, stand, Stage.BREAK, "climb to the cage of the crystal at " + coords(crystalPos));
            case BREAK -> breakBars(ctx, p);
            case RETREAT -> walk(ctx, far, Stage.SHOOT, "bridge away from the crystal at " + coords(crystalPos));
            case SHOOT -> shoot(p);
            case DESCEND -> descend(ctx, p);
            case DOWN -> down(ctx, p);
        };
    }

    private ActionStatus start(BotContext ctx, AiPlayer p) {
        String problem = TheEnd.problem(ctx, "break_cage", anyDimension);
        if (problem != null) return ActionStatus.failed(problem);
        if (!InventoryOps.has(p, Items.BOW, 1)) return ActionStatus.failed("needs a bow");
        if (InventoryOps.count(p, s -> s.is(ItemTags.ARROWS)) == 0) return ActionStatus.failed("needs arrows (you have 0)");
        if (InventoryOps.count(p, s -> s.is(ItemTags.PICKAXES)) == 0) return ActionStatus.failed("needs a pickaxe to break the bars");
        int blocks = InventoryOps.throwawayCount(p);
        if (blocks < MIN_THROWAWAYS) {
            return ActionStatus.failed("needs " + MIN_THROWAWAYS + " blocks to build with (you have " + blocks + ")");
        }
        Optional<EndCrystal> nearest = DragonView.crystals(p).stream().filter(c -> DragonView.caged(p.level(), c))
                .min(Comparator.comparingDouble(c -> c.distanceToSqr(p)));
        if (nearest.isEmpty()) return ActionStatus.failed("no caged end crystal within " + (int) DragonView.CRYSTAL_RANGE + " blocks");
        crystal = nearest.get();
        crystalPos = crystal.blockPosition();
        startTick = p.level().getGameTime();
        ground = p.blockPosition();
        side = Direction.getApproximateNearest(p.getX() - crystal.getX(), 0, p.getZ() - crystal.getZ());
        if (side.getAxis() == Direction.Axis.Y) side = Direction.NORTH;
        stand = crystalPos.relative(side, OUTSIDE);
        far = crystalPos.relative(side, OUTSIDE + BRIDGE);
        stage = Stage.CLIMB;
        return ActionStatus.RUNNING;
    }

    /** Walks (climbing and bridging as the navigator does) to a cell; on arrival moves to {@code next}. */
    private ActionStatus walk(BotContext ctx, BlockPos cell, Stage next, String what) {
        NavStatus nav = ctx.navigator().status();
        if (!navigating || nav.state() == NavStatus.State.IDLE) {
            navigating = true;
            ctx.navigator().goTo(new NearGoal(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5, 0.9));
            return ActionStatus.RUNNING;
        }
        switch (nav.state()) {
            case ARRIVED -> {
                navigating = false;
                navFailures = 0;
                stage = next;
            }
            case FAILED -> {
                navigating = false;
                if (++navFailures >= MAX_NAV_FAILURES) return failWith("could not " + what + ": " + nav.reason());
            }
            default -> { }
        }
        return ActionStatus.RUNNING;
    }

    private ActionStatus breakBars(BotContext ctx, AiPlayer p) {
        if (crystal.isRemoved()) return succeed();
        BlockBreaker breaker = ctx.breaker();
        if (breaker.busy()) {
            BlockBreaker.Status s = breaker.tick();
            if (s.state() == BlockBreaker.Status.State.FAILED) return failWith("can't break the bars of the cage: " + s.reason());
            return ActionStatus.RUNNING;
        }
        BlockHitResult hit = lineToCrystal(p);
        if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(crystalPos)) {
            stage = Stage.RETREAT;
            return ActionStatus.RUNNING;
        }
        BlockState blocker = p.level().getBlockState(hit.getBlockPos());
        if (!blocker.is(Blocks.IRON_BARS) || ++barsBroken > MAX_BARS) {
            return failWith("can't see the caged crystal at " + coords(crystalPos) + " through the gap (" + name(blocker) + " in the way)");
        }
        breaker.start(hit.getBlockPos());
        return ActionStatus.RUNNING;
    }

    private ActionStatus shoot(AiPlayer p) {
        if (crystal.isRemoved()) return succeed();
        if (waitTicks > 0) {
            if (--waitTicks == 0 && shots >= MAX_ARROWS) return failWith("the caged crystal at " + coords(crystalPos) + " survived " + shots + " arrows");
            return ActionStatus.RUNNING;
        }
        if (shot == null) {
            if (!DragonView.visible(p, crystal)) {
                return failWith("can't see the caged crystal at " + coords(crystalPos) + " through the gap");
            }
            shot = new BowShot(() -> centre(), CHARGE_TICKS);
        }
        BowShot.State state = shot.tick(p);
        if (state == BowShot.State.RELEASED) {
            shot = null;
            shots++;
            waitTicks = 10 + (int) (p.distanceTo(crystal) / 2);
        } else if (state == BowShot.State.FAILED) {
            String reason = shot.reason();
            shot = null;
            return failWith("can't shoot the caged crystal at " + coords(crystalPos) + ": " + reason);
        }
        return ActionStatus.RUNNING;
    }

    private ActionStatus succeed() {
        summary = "broke the cage and destroyed the crystal at " + coords(crystalPos);
        return finishWith(ActionStatus.DONE);
    }

    private ActionStatus failWith(String reason) {
        return finishWith(ActionStatus.failed(reason));
    }

    /** Ends with {@code result}, climbing down first when the bot is high up on its own tower. */
    private ActionStatus finishWith(ActionStatus result) {
        outcome = result;
        navigating = false;
        stage = Stage.DESCEND;
        return ActionStatus.RUNNING;
    }

    private ActionStatus descend(BotContext ctx, AiPlayer p) {
        if (p.blockPosition().getY() <= ground.getY()) return outcome;
        stage = Stage.DOWN;
        if (shot != null) shot.cancel(p);
        shot = null;
        ctx.navigator().stop();
        return ActionStatus.RUNNING;
    }

    /**
     * Back along the bridge to the pillar, breaking the bridge behind it as it goes, then down the pillar: break the block
     * under the feet, drop one, repeat.
     */
    private ActionStatus down(BotContext ctx, AiPlayer p) {
        BlockBreaker breaker = ctx.breaker();
        if (breaker.busy()) {
            BlockBreaker.Status s = breaker.tick();
            if (s.state() == BlockBreaker.Status.State.FAILED) {
                if (clearing == null) return outcomeWith("could not climb down: " + s.reason());
                leftStanding.add(clearing); // best effort: the bridge block stays
            }
            if (s.state() != BlockBreaker.Status.State.RUNNING) clearing = null;
            return ActionStatus.RUNNING;
        }
        if (p.blockPosition().getY() <= ground.getY()) return outcome;
        if (!p.onGround()) return ActionStatus.RUNNING;
        BlockPos own = ownBlockBehind(p);
        if (own != null) {
            ctx.navigator().stop();
            clearing = own;
            breaker.start(own);
            return ActionStatus.RUNNING;
        }
        if (!onPillar(p)) {
            // Over the bridge rather than the pillar: walk back to where the climb began.
            NavStatus nav = ctx.navigator().status();
            if (nav.state() == NavStatus.State.RUNNING) return ActionStatus.RUNNING;
            if (nav.state() == NavStatus.State.FAILED && ++navFailures >= MAX_NAV_FAILURES) {
                return outcomeWith("could not get back to the pillar to climb down");
            }
            ctx.navigator().goTo(new NearGoal(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0.9));
            return ActionStatus.RUNNING;
        }
        BlockPos below = p.blockPosition().below();
        if (!isOwnBlock(p.level().getBlockState(below))) {
            return outcomeWith("can't climb down: standing on " + name(p.level().getBlockState(below)) + " at y " + below.getY());
        }
        breaker.start(below);
        return ActionStatus.RUNNING;
    }

    /**
     * A block the bot placed during this action, off the pillar, within reach and farther from the pillar than the bot
     * (so never the one it stands on or still has to walk over); the farthest first. Null when there is none.
     */
    private BlockPos ownBlockBehind(AiPlayer p) {
        ServerLevel level = p.level();
        BlockPos feet = p.blockPosition();
        double mine = Math.hypot(p.getX() - (stand.getX() + 0.5), p.getZ() - (stand.getZ() + 0.5));
        double reach = p.blockInteractionRange();
        BlockPos best = null;
        double bestAway = mine + 0.5;
        for (BlockPos b : PlacedBlocks.of(level.getServer()).placedSince(level, p.getScoreboardName(), startTick)) {
            if ((b.getX() == stand.getX() && b.getZ() == stand.getZ()) || b.equals(feet.below()) || leftStanding.contains(b)) continue;
            if (p.getEyePosition().distanceTo(Vec3.atCenterOf(b)) > reach) continue;
            double away = Math.hypot(b.getX() - stand.getX(), b.getZ() - stand.getZ());
            if (away > bestAway) {
                best = b;
                bestAway = away;
            }
        }
        return best;
    }

    private boolean onPillar(AiPlayer p) {
        BlockPos feet = p.blockPosition();
        return feet.getX() == stand.getX() && feet.getZ() == stand.getZ();
    }

    private ActionStatus outcomeWith(String extra) {
        if (outcome.state() == ActionStatus.State.DONE) {
            // The crystal is gone; only the way down failed.
            return ActionStatus.failed("destroyed the crystal at " + coords(crystalPos) + " but " + extra);
        }
        return ActionStatus.failed(outcome.reason() + "; " + extra);
    }

    /** What the navigator builds with. */
    private static boolean isOwnBlock(BlockState s) {
        return s.is(Blocks.DIRT) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.COBBLED_DEEPSLATE) || s.is(Blocks.NETHERRACK)
                || s.is(BlockTags.PLANKS);
    }

    private BlockHitResult lineToCrystal(AiPlayer p) {
        return p.level().clip(new ClipContext(p.getEyePosition(), centre(), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
    }

    private Vec3 centre() {
        return crystal.position().add(0, 1, 0);
    }

    private static String name(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
    }

    private static String coords(BlockPos b) {
        return b.getX() + " " + b.getY() + " " + b.getZ();
    }

    @Override
    public void cancel(BotContext ctx) {
        if (ctx.player() != null && shot != null) shot.cancel(ctx.player());
        shot = null;
        ctx.breaker().cancel();
        ctx.navigator().stop();
    }

    @Override
    public void interrupted(BotContext ctx) {
        if (ctx.player() != null && shot != null) shot.cancel(ctx.player());
        shot = null;
        ctx.breaker().cancel();
        navigating = false;
        waitTicks = 0;
    }

    @Override
    public String describe() {
        return summary != null ? summary : "break a cage";
    }

    @Override
    public int timeoutTicks() {
        return 6000;
    }
}
