package com.aiciv.stronghold;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.dimension.Waypoints;
import com.aiciv.nav.LevelBlockView;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import com.aiciv.nav.Pos;
import com.aiciv.world.Finder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Wanders through a stronghold, always toward the nearest part it has not been in, until it sees the ring of end portal
 * frames (or the silverfish spawner that stands in the same room).
 */
public final class FindPortalRoomAction implements Action {
    static final int SCAN_TICKS = 20;
    static final int SIGHT = 24;
    static final int CANDIDATE_RADIUS = 16;
    static final double APPROACH = 5.0;
    static final int BUCKET = 4;

    private final int radius;
    private final boolean anyDimension;
    private final Frontier frontier = new Frontier(BUCKET);
    private boolean started;
    private Pos start;
    private int ticks;
    private Approach approach;
    private BlockPos room;
    private Pos walkingTo;
    private String summary;

    public FindPortalRoomAction(int radius) {
        this(radius, false);
    }

    public FindPortalRoomAction(int radius, boolean anyDimension) {
        this.radius = radius;
        this.anyDimension = anyDimension;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (!started) {
            String problem = Overworld.problem(ctx, "find_portal_room", anyDimension);
            if (problem != null) return ActionStatus.failed(problem);
            started = true;
            BlockPos feet = p.blockPosition();
            start = new Pos(feet.getX(), feet.getY(), feet.getZ());
            ticks = SCAN_TICKS - 1;
        }
        if (approach != null) return approaching(ctx, p);
        if (++ticks < SCAN_TICKS) return ActionStatus.RUNNING;
        ticks = 0;
        BlockPos frame = nearest(Finder.visibleStates(p, s -> s.is(Blocks.END_PORTAL_FRAME), SIGHT));
        if (frame == null) frame = silverfishSpawner(p);
        if (frame != null) {
            room = frame;
            approach = Approach.block(frame, APPROACH);
            return ActionStatus.RUNNING;
        }
        return explore(ctx, p);
    }

    private ActionStatus approaching(BotContext ctx, AiPlayer p) {
        Approach.Status s = approach.tick(ctx);
        if (s.state() == Approach.State.RUNNING) return ActionStatus.RUNNING;
        if (s.state() == Approach.State.FAILED) return ActionStatus.failed(s.reason());
        List<BlockPos> frames = Finder.visibleStates(p, st -> st.is(Blocks.END_PORTAL_FRAME), SIGHT);
        BlockPos centre = frames.isEmpty() ? room : mean(frames);
        if (ctx.attachments() != null) {
            ctx.attachments().get(Waypoints.class, Waypoints::new).mark(Waypoints.END_PORTAL, ctx.dimension(),
                    new Pos(centre.getX(), centre.getY(), centre.getZ()),
                    frames.isEmpty() ? "silverfish spawner" : frames.size() + " frames seen", p.level().getGameTime());
        }
        summary = "found the end portal room at " + centre.getX() + " " + centre.getY() + " " + centre.getZ();
        return ActionStatus.DONE;
    }

    private ActionStatus explore(BotContext ctx, AiPlayer p) {
        BlockPos feet = p.blockPosition();
        Pos here = new Pos(feet.getX(), feet.getY(), feet.getZ());
        frontier.visit(here);
        if (walkingTo != null && ctx.navigator().status().state() == NavStatus.State.RUNNING) return ActionStatus.RUNNING;
        Optional<Pos> next = frontier.next(here, openAir(p));
        if (next.isEmpty()) return ActionStatus.failed("explored the stronghold nearby and found no portal room");
        walkingTo = next.get();
        frontier.visit(walkingTo); // claimed: a goal next to the bot must not be picked again once it is reached
        ctx.navigator().goTo(new NearGoal(walkingTo.x() + 0.5, walkingTo.y(), walkingTo.z() + 0.5, 1.5));
        return ActionStatus.RUNNING;
    }

    /** Floor cells beside the stronghold blocks in sight, within the search radius of where the bot began. */
    private List<Pos> openAir(AiPlayer p) {
        ServerLevel level = p.level();
        LevelBlockView view = new LevelBlockView(level);
        Set<Pos> cells = new LinkedHashSet<>();
        Vec3 eyes = p.getEyePosition();
        List<BlockPos> bricks = Finder.visibleStates(p, s -> StrongholdBlocks.is(BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath()),
                CANDIDATE_RADIUS);
        for (BlockPos b : bricks) {
            List<BlockPos> around = new ArrayList<>();
            around.add(b.above());
            for (Direction d : Direction.Plane.HORIZONTAL) around.add(b.relative(d));
            for (BlockPos c : around) {
                int x = c.getX(), y = c.getY(), z = c.getZ();
                if (!view.passable(x, y, z) || !view.passable(x, y + 1, z) || view.dangerous(x, y, z) || !view.standable(x, y - 1, z)) continue;
                if (Math.abs(x - start.x()) > radius || Math.abs(y - start.y()) > radius || Math.abs(z - start.z()) > radius) continue;
                if (inSight(level, eyes, c)) cells.add(new Pos(x, y, z));
            }
        }
        return new ArrayList<>(cells);
    }

    /** A cell on the far side of a wall is not somewhere the bot has seen, so it is not a place to walk to. */
    private static boolean inSight(ServerLevel level, Vec3 eyes, BlockPos cell) {
        Vec3 centre = Vec3.atCenterOf(cell);
        return level.clip(new ClipContext(eyes, centre, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()))
                .getType() == HitResult.Type.MISS;
    }

    private BlockPos silverfishSpawner(AiPlayer p) {
        ServerLevel level = p.level();
        for (BlockPos pos : Finder.visibleStates(p, s -> s.is(Blocks.SPAWNER), SIGHT)) {
            if (level.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner) {
                var shown = spawner.getSpawner().getOrCreateDisplayEntity(level, pos);
                if (shown != null && shown.getType() == EntityTypes.SILVERFISH) return pos;
            }
        }
        return null;
    }

    private static BlockPos nearest(List<BlockPos> sorted) {
        return sorted.isEmpty() ? null : sorted.get(0);
    }

    private static BlockPos mean(List<BlockPos> blocks) {
        long x = 0, y = 0, z = 0;
        for (BlockPos b : blocks) {
            x += b.getX();
            y += b.getY();
            z += b.getZ();
        }
        int n = blocks.size();
        return new BlockPos(Math.round((float) x / n), Math.round((float) y / n), Math.round((float) z / n));
    }

    @Override
    public void interrupted(BotContext ctx) {
        walkingTo = null;
        if (approach != null) approach.cancel(ctx);
        approach = null;
        ticks = SCAN_TICKS - 1;
    }

    @Override
    public void cancel(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
        ctx.navigator().stop();
    }

    @Override
    public String describe() {
        return summary != null ? summary : "find the end portal room";
    }

    @Override
    public int timeoutTicks() {
        return 6000;
    }
}
