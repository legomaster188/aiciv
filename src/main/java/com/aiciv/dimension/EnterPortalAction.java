package com.aiciv.dimension;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.nav.LevelBlockView;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import com.aiciv.nav.Pos;
import com.aiciv.world.Finder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Walks into a visible portal (or one remembered as a waypoint) and waits for it to take the bot across, with the
 * portal's real timer. Paths never enter a portal, so this action walks the last step itself.
 */
public final class EnterPortalAction implements Action {
    static final int SIGHT = 16;
    static final int WAYPOINT_RANGE = 128;
    static final double WAYPOINT_APPROACH = 4.0;
    static final int STEP_TICKS = 40;
    static final int WAIT_TICKS = 300;
    static final int MAX_STEPS = 3;
    static final int MAX_NAV_FAILURES = 3;
    /** A jump farther than this between two ticks in the same dimension is an end gateway taking the bot. */
    static final double GATEWAY_JUMP = 16;
    private static final int MAX_GROUP = 512;

    private enum Stage { FIND, APPROACH, WALK, STEP_IN, WAIT, STEP_OUT }

    private final String kind;
    private final Predicate<BlockState> match;
    private Stage stage = Stage.FIND;
    private String from;
    private Approach approach;
    private boolean approached;
    private BlockPos portal;
    private BlockPos stand;
    private int navFailures;
    private boolean failureCounted;
    private int stageTicks;
    private int steps;
    private int stoodTicks;
    private float exitYaw;
    private Vec3 lastPos;
    private String summary;

    public EnterPortalAction(String kind) {
        this.kind = kind;
        this.match = Portals.ofKind(kind);
        if (match == null) throw new IllegalArgumentException("kind must be nether, end or any");
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (from == null) from = Portals.key(p.level());
        String now = Portals.key(p.level());
        boolean jumped = lastPos != null && (stage == Stage.STEP_IN || stage == Stage.WAIT)
                && p.position().distanceTo(lastPos) > GATEWAY_JUMP;
        lastPos = p.position();
        if (stage != Stage.STEP_OUT && (!now.equals(from) || jumped)) arrive(ctx, p, now);
        return switch (stage) {
            case FIND -> find(ctx, p);
            case APPROACH -> approach(ctx);
            case WALK -> walk(ctx);
            case STEP_IN -> stepIn(p);
            case WAIT -> waitInPortal(p);
            case STEP_OUT -> stepOut(p);
        };
    }

    private ActionStatus find(BotContext ctx, AiPlayer p) {
        List<BlockPos> seen = Finder.visibleStates(p, match, SIGHT);
        if (seen.isEmpty()) {
            Optional<BlockPos> remembered = approached ? Optional.empty() : rememberedPortal(ctx, p);
            if (remembered.isEmpty()) return ActionStatus.failed("no " + kindWord() + "portal in sight within " + SIGHT + " blocks");
            approach = Approach.block(remembered.get(), WAYPOINT_APPROACH);
            stage = Stage.APPROACH;
            return ActionStatus.RUNNING;
        }
        portal = seen.get(0);
        BlockState state = p.level().getBlockState(portal);
        ResourceKey<Level> destination = destination(p.level(), state);
        if (destination != null && p.level().getServer().getLevel(destination) == null) {
            return ActionStatus.failed(String.format(Locale.ROOT, Portals.NO_LEVEL, destination.identifier().getPath()));
        }
        Optional<BlockPos[]> cell = standCell(p, portal, state.getBlock());
        if (cell.isEmpty()) return ActionStatus.failed("there is nowhere to stand beside the portal at " + coords(portal));
        stand = cell.get()[0];
        portal = cell.get()[1];
        stage = Stage.WALK;
        walkTo(ctx);
        return ActionStatus.RUNNING;
    }

    private Optional<BlockPos> rememberedPortal(BotContext ctx, AiPlayer p) {
        if (ctx.attachments() == null) return Optional.empty();
        Waypoints waypoints = ctx.attachments().get(Waypoints.class, Waypoints::new);
        BlockPos feet = p.blockPosition();
        Pos here = new Pos(feet.getX(), feet.getY(), feet.getZ());
        List<String> kinds = kind.equals("nether") ? List.of(Waypoints.PORTAL)
                : List.of(Waypoints.PORTAL, Waypoints.EXIT_PORTAL, Waypoints.END_PORTAL);
        return kinds.stream()
                .map(k -> waypoints.nearest(k, from, here))
                .flatMap(Optional::stream)
                .filter(w -> distance(w.pos(), here) <= WAYPOINT_RANGE)
                .min(Comparator.comparingDouble(w -> distance(w.pos(), here)))
                .map(w -> new BlockPos(w.pos().x(), w.pos().y(), w.pos().z()));
    }

    private ActionStatus approach(BotContext ctx) {
        Approach.Status s = approach.tick(ctx);
        return switch (s.state()) {
            case RUNNING -> ActionStatus.RUNNING;
            case FAILED -> ActionStatus.failed(s.reason());
            case ARRIVED -> {
                approached = true;
                stage = Stage.FIND;
                yield ActionStatus.RUNNING;
            }
        };
    }

    private ActionStatus walk(BotContext ctx) {
        NavStatus nav = ctx.navigator().status();
        switch (nav.state()) {
            case ARRIVED -> {
                stage = Stage.STEP_IN;
                stageTicks = 0;
            }
            case FAILED -> {
                if (!failureCounted) {
                    failureCounted = true;
                    if (++navFailures >= MAX_NAV_FAILURES) return ActionStatus.failed(nav.reason());
                }
                walkTo(ctx);
            }
            case IDLE -> walkTo(ctx);
            case RUNNING -> { }
        }
        return ActionStatus.RUNNING;
    }

    private void walkTo(BotContext ctx) {
        failureCounted = false;
        ctx.navigator().goTo(new NearGoal(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0.8));
    }

    private ActionStatus stepIn(AiPlayer p) {
        if (inPortal(p)) {
            p.stopMoving();
            stage = Stage.WAIT;
            return ActionStatus.RUNNING;
        }
        if (++stageTicks > STEP_TICKS) {
            p.stopMoving();
            return ActionStatus.failed("could not step into the portal at " + coords(portal));
        }
        p.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(portal));
        p.zza = 1.0F;
        p.setJumping(p.horizontalCollision && p.onGround());
        return ActionStatus.RUNNING;
    }

    private ActionStatus waitInPortal(AiPlayer p) {
        p.stopMoving();
        if (++stoodTicks >= WAIT_TICKS) return ActionStatus.failed("the portal did not take you (stood in it " + stoodTicks + " ticks)");
        if (inPortal(p)) return ActionStatus.RUNNING;
        // Pushed out (a mob, the portal's edge): step back in.
        if (++steps >= MAX_STEPS) return ActionStatus.failed("the portal did not take you (stood in it " + stoodTicks + " ticks)");
        stage = Stage.STEP_IN;
        stageTicks = 0;
        return ActionStatus.RUNNING;
    }

    private ActionStatus stepOut(AiPlayer p) {
        if (!Portals.isPortal(p.level().getBlockState(p.blockPosition())) || ++stageTicks > STEP_TICKS) {
            p.stopMoving();
            return ActionStatus.DONE;
        }
        p.setYRot(exitYaw);
        p.setYHeadRot(exitYaw);
        p.zza = 1.0F;
        p.setJumping(p.horizontalCollision && p.onGround());
        return ActionStatus.RUNNING;
    }

    /** Marks where the bot came from and where it arrived, then steps out of the arrival portal. */
    private void arrive(BotContext ctx, AiPlayer p, String now) {
        ctx.navigator().stop();
        p.stopMoving();
        String oldShort = Portals.shortName(from);
        String newShort = Portals.shortName(now);
        long tick = p.level().getGameTime();
        if (ctx.attachments() != null) {
            Waypoints waypoints = ctx.attachments().get(Waypoints.class, Waypoints::new);
            if (portal != null) {
                boolean endExit = from.equals("minecraft:the_end") && !now.equals(from);
                waypoints.mark(endExit ? Waypoints.EXIT_PORTAL : Waypoints.PORTAL, from, pos(portal),
                        "to " + (endExit ? "overworld" : newShort), tick);
            }
            BlockPos landed = nearestPortalBlock(p, 4);
            if (landed != null) waypoints.mark(Waypoints.PORTAL, now, pos(landed), "to " + oldShort, tick);
            else waypoints.mark(Waypoints.ARRIVAL, now, pos(p.blockPosition()), "from " + oldShort, tick);
        }
        BlockPos at = p.blockPosition();
        summary = "went through to " + newShort + "; you are at " + coords(at);
        exitYaw = exitYaw(p);
        stage = Stage.STEP_OUT;
        stageTicks = 0;
    }

    /** Along the bot's facing, or for a nether portal the way out of its plane closest to that facing. */
    private static float exitYaw(AiPlayer p) {
        BlockState feet = p.level().getBlockState(p.blockPosition());
        if (!feet.hasProperty(NetherPortalBlock.AXIS)) return p.getYRot();
        Direction.Axis axis = feet.getValue(NetherPortalBlock.AXIS);
        Direction a = axis == Direction.Axis.X ? Direction.SOUTH : Direction.EAST;
        Direction b = a.getOpposite();
        boolean aOpen = open(p, a), bOpen = open(p, b);
        if (aOpen != bOpen) return (aOpen ? a : b).toYRot();
        float yaw = p.getYRot();
        return Math.abs(Mth.wrapDegrees(a.toYRot() - yaw)) <= Math.abs(Mth.wrapDegrees(b.toYRot() - yaw)) ? a.toYRot() : b.toYRot();
    }

    private static boolean open(AiPlayer p, Direction d) {
        LevelBlockView view = new LevelBlockView(p.level());
        BlockPos c = p.blockPosition().relative(d);
        return view.passable(c.getX(), c.getY(), c.getZ()) && view.passable(c.getX(), c.getY() + 1, c.getZ())
                && !Portals.isPortal(p.level().getBlockState(c));
    }

    private static boolean inPortal(AiPlayer p) {
        BlockPos feet = p.blockPosition();
        return Portals.isPortal(p.level().getBlockState(feet)) || Portals.isPortal(p.level().getBlockState(feet.above()));
    }

    private static BlockPos nearestPortalBlock(AiPlayer p, int r) {
        BlockPos feet = p.blockPosition();
        BlockPos best = null;
        for (BlockPos c : BlockPos.betweenClosed(feet.offset(-r, -r, -r), feet.offset(r, r, r))) {
            if (Portals.isPortal(p.level().getBlockState(c)) && (best == null || c.distSqr(feet) < best.distSqr(feet))) best = c.immutable();
        }
        return best;
    }

    /** Where a portal block of this state sends a player from {@code level}; null when it stays in the dimension (a gateway). */
    static ResourceKey<Level> destination(ServerLevel level, BlockState state) {
        if (state.is(Blocks.NETHER_PORTAL)) return level.dimension() == Level.NETHER ? Level.OVERWORLD : Level.NETHER;
        if (state.is(Blocks.END_PORTAL)) return level.dimension() == Level.END ? Level.OVERWORLD : Level.END;
        return null;
    }

    /**
     * The cell to start the step from, and the portal block it faces: horizontally next to a block of the portal's
     * connected group, at its height or one lower (in front of a frame standing on the ground; the step jumps onto it),
     * or for an end portal one higher (on the frame's rim); with room to stand; nearest first.
     */
    private static Optional<BlockPos[]> standCell(AiPlayer p, BlockPos start, Block block) {
        ServerLevel level = p.level();
        LevelBlockView view = new LevelBlockView(level);
        int[] lifts = block == Blocks.END_PORTAL ? new int[] {1} : new int[] {0, -1};
        Set<BlockPos> group = new HashSet<>();
        Deque<BlockPos> todo = new ArrayDeque<>(List.of(start));
        while (!todo.isEmpty() && group.size() < MAX_GROUP) {
            BlockPos b = todo.poll();
            if (!group.add(b)) continue;
            for (Direction d : Direction.values()) {
                BlockPos n = b.relative(d);
                if (!group.contains(n) && level.isLoaded(n) && level.getBlockState(n).is(block)) todo.add(n);
            }
        }
        BlockPos feet = p.blockPosition();
        List<BlockPos[]> cells = new ArrayList<>();
        for (BlockPos b : group) {
            for (Direction d : Direction.Plane.HORIZONTAL) for (int lift : lifts) {
                BlockPos c = b.relative(d).above(lift);
                if (group.contains(c) || Portals.isPortal(level.getBlockState(c))) continue;
                int x = c.getX(), y = c.getY(), z = c.getZ();
                if (view.passable(x, y, z) && !view.dangerous(x, y, z) && view.passable(x, y + 1, z) && !view.dangerous(x, y + 1, z)
                        && view.standable(x, y - 1, z) && !view.dangerous(x, y - 1, z)) {
                    cells.add(new BlockPos[] {c, b});
                }
            }
        }
        return cells.stream().min(Comparator.comparingDouble(c -> c[0].distSqr(feet)));
    }

    @Override
    public void cancel(BotContext ctx) {
        stop(ctx);
    }

    @Override
    public void interrupted(BotContext ctx) {
        stop(ctx);
        // The reflex may have moved the bot anywhere: look for the portal again, unless it already went through.
        if (stage != Stage.STEP_OUT) stage = Stage.FIND;
        approach = null;
    }

    private void stop(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
        ctx.navigator().stop();
        if (ctx.player() != null) ctx.player().stopMoving();
    }

    @Override
    public boolean survivesDimensionChange() {
        return true;
    }

    @Override
    public String describe() {
        return summary != null ? summary : "enter " + kind + " portal";
    }

    @Override
    public int timeoutTicks() {
        return 1200;
    }

    private String kindWord() {
        return kind.equals("any") ? "" : kind + " ";
    }

    private static Pos pos(BlockPos b) {
        return new Pos(b.getX(), b.getY(), b.getZ());
    }

    private static String coords(BlockPos b) {
        return b.getX() + " " + b.getY() + " " + b.getZ();
    }

    private static double distance(Pos a, Pos b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
