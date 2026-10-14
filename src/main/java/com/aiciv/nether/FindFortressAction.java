package com.aiciv.nether;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.dimension.Portals;
import com.aiciv.dimension.Waypoints;
import com.aiciv.nav.ColumnGoal;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.Pos;
import com.aiciv.perception.Perceiver;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;

/**
 * Walks a square spiral through the nether until nether bricks come into sight (or one is remembered nearby), then goes
 * up to them and remembers the place as a fortress.
 */
public final class FindFortressAction implements Action {
    static final int SCAN_RADIUS = 24;
    static final int SCAN_EVERY = 20;
    static final int KNOWN_RANGE = 64;
    static final int LEG_LENGTH = 64;
    static final double LEG_RADIUS = 6;
    static final double APPROACH_RADIUS = 6;
    static final int AHEAD = 16;
    static final int MAX_LEG_FAILURES = 2;
    private static final String[] DIRECTIONS = {"N", "E", "S", "W"};
    private static final int[][] STEP = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    private final String direction;
    private final int distance;
    private final boolean anyDimension;
    private boolean started;
    private List<int[]> legs;
    private int leg;
    private boolean legStarted;
    private int legFailures;
    private boolean failureCounted;
    private int ticks;
    private Approach approach;
    private BlockPos hit;
    private final Set<BlockPos> ignored = new HashSet<>();
    private String summary;

    public FindFortressAction(String directionOrNull, int distance) {
        this(directionOrNull, distance, false);
    }

    /** {@code anyDimension} lets game tests run it in the overworld. */
    public FindFortressAction(String directionOrNull, int distance, boolean anyDimension) {
        this.direction = directionOrNull == null || directionOrNull.isBlank() ? null : directionOrNull.trim().toUpperCase(Locale.ROOT);
        this.distance = distance;
        this.anyDimension = anyDimension;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (!started) {
            started = true;
            String wrong = anyDimension ? null : Refusal.notInNether(ctx, "find_fortress");
            if (wrong != null) return ActionStatus.failed(wrong);
            BlockPos feet = p.blockPosition();
            legs = FortressSearch.spiral(feet.getX(), feet.getZ(), direction != null ? direction : bestDirection(p), LEG_LENGTH, distance);
        }
        if (hit != null) return approach(ctx);
        if (ticks++ % SCAN_EVERY == 0) {
            Optional<BlockPos> found = scan(ctx, p);
            if (found.isPresent()) {
                hit = found.get();
                ctx.navigator().stop();
                legStarted = false;
                approach = Approach.block(hit, APPROACH_RADIUS);
                return approach(ctx);
            }
        }
        return walk(ctx);
    }

    private Optional<BlockPos> scan(BotContext ctx, AiPlayer p) {
        ServerLevel level = p.level();
        BlockPos feet = p.blockPosition();
        for (Map.Entry<Pos, String> e : Perceiver.exposedBlocks(level, feet, p.getEyePosition(), SCAN_RADIUS, FortressSearch::isFortressBlock)) {
            BlockPos pos = new BlockPos(e.getKey().x(), e.getKey().y(), e.getKey().z());
            if (!ignored.contains(pos)) return Optional.of(pos);
        }
        if (ctx.knownBlocks() != null) {
            Optional<Pos> known = ctx.knownBlocks().nearest(label -> label.equals("nether_bricks"), new Pos(feet.getX(), feet.getY(), feet.getZ()));
            if (known.isPresent()) {
                BlockPos pos = new BlockPos(known.get().x(), known.get().y(), known.get().z());
                if (!ignored.contains(pos) && pos.distSqr(feet) <= (double) KNOWN_RANGE * KNOWN_RANGE && level.isLoaded(pos)
                        && FortressSearch.isFortressBlock(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath())) {
                    return Optional.of(pos);
                }
            }
        }
        return Optional.empty();
    }

    private ActionStatus approach(BotContext ctx) {
        if (approach == null) approach = Approach.block(hit, APPROACH_RADIUS);
        Approach.Status s = approach.tick(ctx);
        switch (s.state()) {
            case RUNNING:
                return ActionStatus.RUNNING;
            case FAILED:
                // Seen across a gap, or only remembered: keep looking along the spiral.
                ignored.add(hit);
                hit = null;
                approach = null;
                return ActionStatus.RUNNING;
            default:
                if (ctx.attachments() != null) {
                    ctx.attachments().get(Waypoints.class, Waypoints::new).mark(Waypoints.FORTRESS, Portals.key(ctx.player().level()),
                            new Pos(hit.getX(), hit.getY(), hit.getZ()), "", ctx.player().level().getGameTime());
                }
                summary = "found a nether fortress at " + hit.getX() + " " + hit.getY() + " " + hit.getZ();
                return ActionStatus.DONE;
        }
    }

    private ActionStatus walk(BotContext ctx) {
        if (leg >= legs.size()) return ActionStatus.failed("no fortress within " + distance + " blocks; try another direction");
        if (!legStarted) {
            int[] to = legs.get(leg);
            ctx.navigator().goTo(new ColumnGoal(to[0] + 0.5, to[1] + 0.5, LEG_RADIUS));
            legStarted = true;
            failureCounted = false;
            return ActionStatus.RUNNING;
        }
        NavStatus nav = ctx.navigator().status();
        switch (nav.state()) {
            case ARRIVED:
                nextLeg();
                break;
            case FAILED:
                if (!failureCounted) {
                    failureCounted = true;
                    if (++legFailures >= MAX_LEG_FAILURES) nextLeg();
                    else legStarted = false;
                }
                break;
            case IDLE:
                legStarted = false;
                break;
            default:
                break;
        }
        return ActionStatus.RUNNING;
    }

    private void nextLeg() {
        leg++;
        legStarted = false;
        legFailures = 0;
    }

    /** The compass direction with the most open cells in the 16 blocks ahead at eye height. */
    private static String bestDirection(AiPlayer p) {
        ServerLevel level = p.level();
        BlockPos eyes = BlockPos.containing(p.getEyePosition());
        int best = -1, bestIndex = 0;
        for (int d = 0; d < DIRECTIONS.length; d++) {
            int open = 0;
            for (int i = 1; i <= AHEAD; i++) {
                BlockPos c = eyes.offset(STEP[d][0] * i, 0, STEP[d][1] * i);
                if (level.isLoaded(c) && level.getBlockState(c).getCollisionShape(level, c).isEmpty()) open++;
            }
            if (open > best) {
                best = open;
                bestIndex = d;
            }
        }
        return DIRECTIONS[bestIndex];
    }

    @Override
    public void interrupted(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
        approach = null;
        legStarted = false;
    }

    @Override
    public void cancel(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
        ctx.navigator().stop();
    }

    @Override
    public String describe() {
        return summary != null ? summary : "find fortress";
    }

    @Override
    public int timeoutTicks() {
        return Math.min(24000, 40 * distance + 600);
    }
}
