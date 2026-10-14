package com.aiciv.stronghold;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.nav.LevelBlockView;
import com.aiciv.world.BlockBreaker;
import com.aiciv.world.Finder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Digs down from where the bot stands in a stair that turns a quarter every few steps, so it stays near its column and
 * can climb back out, until stronghold bricks come into view. Each level breaks the two cells ahead and the one below
 * them, never next to lava or water, and never drops into a cave deeper than three blocks. (The navigator has no
 * move that digs downward, so this action breaks the blocks itself.)
 */
public final class DigToStrongholdAction implements Action {
    static final int SCAN_TICKS = 20;
    static final int SCAN_RADIUS = 16;
    /** Steps before the stair turns. */
    static final int TURN = 4;
    static final int STEP_TICKS = 40;
    static final int MAX_DROP = 3;
    private static final Direction[] HEADINGS = {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH};

    private enum Phase { PLAN, BREAK, STEP }

    private final int minY;
    private final boolean anyDimension;
    private boolean started;
    private Phase phase = Phase.PLAN;
    private int ticks;
    private int heading;
    private int headingTries;
    private int stepsDone;
    private int stepTicks;
    private List<BlockPos> toBreak = List.of();
    private BlockPos stand;
    private String summary;
    private String lastProblem = "";

    public DigToStrongholdAction(int minY) {
        this(minY, false);
    }

    public DigToStrongholdAction(int minY, boolean anyDimension) {
        this.minY = minY;
        this.anyDimension = anyDimension;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (!started) {
            String problem = Overworld.problem(ctx, "dig_to_stronghold", anyDimension);
            if (problem != null) return ActionStatus.failed(problem);
            started = true;
            ticks = SCAN_TICKS - 1; // look around at once
        }
        if (++ticks >= SCAN_TICKS) {
            ticks = 0;
            Finder.visibleStates(p, s -> isStronghold(s), SCAN_RADIUS).stream().findFirst().ifPresent(b -> reached(b));
            if (summary != null) return done(ctx, p);
        }
        BlockPos feet = p.blockPosition();
        if (feet.getY() <= minY) return ActionStatus.failed("dug to y " + feet.getY() + " without finding the stronghold");
        ActionStatus status = switch (phase) {
            case PLAN -> plan(p);
            case BREAK -> breakNext(ctx, p);
            case STEP -> step(p);
        };
        return summary != null ? done(ctx, p) : status;
    }

    private ActionStatus done(BotContext ctx, AiPlayer p) {
        ctx.breaker().cancel();
        p.stopMoving();
        return ActionStatus.DONE;
    }

    private void reached(BlockPos b) {
        summary = "reached the stronghold at " + b.getX() + " " + b.getY() + " " + b.getZ();
    }

    private static boolean isStronghold(BlockState s) {
        return StrongholdBlocks.is(BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath());
    }

    /** Picks the next stair: the cells ahead at head and feet height and the one below them, which becomes the new floor. */
    private ActionStatus plan(AiPlayer p) {
        ServerLevel level = p.level();
        LevelBlockView view = new LevelBlockView(level);
        BlockPos feet = p.blockPosition();
        Direction d = HEADINGS[Math.floorMod(heading, HEADINGS.length)];
        BlockPos ahead = feet.relative(d);
        List<BlockPos> cells = List.of(ahead.above(), ahead, ahead.below());
        String problem = null;
        for (BlockPos c : cells) {
            BlockState s = level.getBlockState(c);
            if (isStronghold(s)) {
                reached(c);
                return ActionStatus.RUNNING;
            }
            if (view.passable(c.getX(), c.getY(), c.getZ()) && !view.dangerous(c.getX(), c.getY(), c.getZ())) continue;
            if (view.dangerous(c.getX(), c.getY(), c.getZ()) || s.getDestroySpeed(level, c) < 0) problem = "cannot dig through " + name(s);
            else if (nextToHazard(level, view, c)) problem = "lava or water beside the dig at " + coords(c);
        }
        if (problem != null) return turnAway(p, problem);
        List<BlockPos> breaks = new ArrayList<>();
        for (BlockPos c : cells) if (!view.passable(c.getX(), c.getY(), c.getZ())) breaks.add(c);
        toBreak = breaks;
        stand = ahead.below();
        phase = Phase.BREAK;
        return ActionStatus.RUNNING;
    }

    private ActionStatus breakNext(BotContext ctx, AiPlayer p) {
        BlockBreaker breaker = ctx.breaker();
        if (toBreak.isEmpty()) {
            phase = landingOk(p.level()) ? Phase.STEP : Phase.PLAN;
            stepTicks = 0;
            if (phase == Phase.PLAN) return turnAway(p, lastProblem);
            return ActionStatus.RUNNING;
        }
        BlockPos target = toBreak.get(0);
        if (!breaker.busy()) breaker.start(target);
        BlockBreaker.Status s = breaker.tick();
        switch (s.state()) {
            case DONE -> toBreak = toBreak.subList(1, toBreak.size());
            case FAILED -> {
                breaker.cancel();
                return turnAway(p, "could not break " + coords(target) + ": " + s.reason());
            }
            case RUNNING -> { }
        }
        return ActionStatus.RUNNING;
    }

    /** The new floor must hold, with no fluid under it and no drop deeper than three blocks. */
    private boolean landingOk(ServerLevel level) {
        LevelBlockView view = new LevelBlockView(level);
        for (BlockPos c : new BlockPos[] {stand.above(), stand}) {
            if (!level.getFluidState(c).isEmpty() || view.dangerous(c.getX(), c.getY(), c.getZ())) {
                lastProblem = "lava or water in the dig at " + coords(c);
                return false;
            }
        }
        for (int drop = 1; drop <= MAX_DROP + 1; drop++) {
            BlockPos c = stand.below(drop);
            if (!level.getFluidState(c).isEmpty() || view.dangerous(c.getX(), c.getY(), c.getZ())) {
                lastProblem = "lava or water below the dig at " + coords(c);
                return false;
            }
            if (view.standable(c.getX(), c.getY(), c.getZ())) return true;
        }
        lastProblem = "a long drop below the dig at " + coords(stand);
        return false;
    }

    private ActionStatus step(AiPlayer p) {
        BlockPos feet = p.blockPosition();
        if (feet.equals(stand) && p.onGround()) {
            p.stopMoving();
            headingTries = 0;
            if (++stepsDone % TURN == 0) heading++;
            phase = Phase.PLAN;
            return ActionStatus.RUNNING;
        }
        if (++stepTicks > STEP_TICKS) {
            p.stopMoving();
            return ActionStatus.failed("could not step down to " + coords(stand));
        }
        p.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(stand.getX() + 0.5, p.getEyeY(), stand.getZ() + 0.5));
        p.zza = 1.0F;
        p.setJumping(p.horizontalCollision && p.onGround());
        return ActionStatus.RUNNING;
    }

    /** This heading is blocked: try the next one, and give up once all four have been tried at this depth. */
    private ActionStatus turnAway(AiPlayer p, String problem) {
        lastProblem = problem;
        heading++;
        phase = Phase.PLAN;
        if (++headingTries >= HEADINGS.length) return ActionStatus.failed("cannot dig on at y " + p.blockPosition().getY() + ": " + problem);
        return ActionStatus.RUNNING;
    }

    private static boolean nextToHazard(ServerLevel level, LevelBlockView view, BlockPos c) {
        for (Direction d : Direction.values()) {
            BlockPos n = c.relative(d);
            if (view.dangerous(n.getX(), n.getY(), n.getZ()) || !level.getFluidState(n).isEmpty()) return true;
        }
        return false;
    }

    private static String name(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
    }

    private static String coords(BlockPos b) {
        return b.getX() + " " + b.getY() + " " + b.getZ();
    }

    @Override
    public void interrupted(BotContext ctx) {
        ctx.breaker().cancel();
        phase = Phase.PLAN;
    }

    @Override
    public void cancel(BotContext ctx) {
        ctx.breaker().cancel();
        if (ctx.player() != null) ctx.player().stopMoving();
    }

    @Override
    public String describe() {
        return summary != null ? summary : "dig down to the stronghold";
    }

    @Override
    public int timeoutTicks() {
        return 6000;
    }
}
