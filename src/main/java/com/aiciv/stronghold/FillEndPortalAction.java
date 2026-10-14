package com.aiciv.stronghold;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockBreaker;
import com.aiciv.world.BlockUse;
import com.aiciv.world.Finder;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemUse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Puts an eye of ender in every empty frame of the ring the bot is looking at. It checks it holds enough eyes before it
 * uses any, and breaks a silverfish spawner standing in the room first when it has a pickaxe.
 */
public final class FillEndPortalAction implements Action {
    static final int SIGHT = 24;
    /** Frames within this many blocks of the nearest one are the same ring (it is 5 wide). */
    static final int RING = 8;
    static final double REACH = 4.0;
    static final int CLICK_TRIES = 3;

    private enum Stage { SCAN, SPAWNER, FILL, CHECK }

    private Stage stage = Stage.SCAN;
    private List<BlockPos> ring = List.of();
    private BlockPos spawner;
    private BlockPos frame;
    private Approach approach;
    private boolean breaking;
    private int clicks;
    private int filled;
    private String summary;

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        return switch (stage) {
            case SCAN -> scan(p);
            case SPAWNER -> breakSpawner(ctx, p);
            case FILL -> fill(ctx, p);
            case CHECK -> check(p);
        };
    }

    private ActionStatus scan(AiPlayer p) {
        List<BlockPos> seen = Finder.visibleStates(p, s -> s.is(Blocks.END_PORTAL_FRAME), SIGHT);
        if (seen.isEmpty()) return ActionStatus.failed("no end portal frame in sight within " + SIGHT + " blocks");
        ring = ringAround(p.level(), seen.get(0));
        List<BlockPos> empty = emptyFrames(p.level());
        int eyes = InventoryOps.count(p, s -> s.is(Items.ENDER_EYE));
        if (!empty.isEmpty() && eyes < empty.size()) {
            return ActionStatus.failed("needs " + empty.size() + " ender_eye for " + empty.size() + " empty frames (you have " + eyes + ")");
        }
        List<BlockPos> spawners = Finder.visibleStates(p, s -> s.is(Blocks.SPAWNER), RING);
        boolean pickaxe = InventoryOps.count(p, s -> s.is(ItemTags.PICKAXES)) > 0;
        if (!spawners.isEmpty() && pickaxe && !empty.isEmpty()) {
            spawner = spawners.get(0);
            approach = Approach.block(spawner, REACH);
            stage = Stage.SPAWNER;
        } else {
            stage = Stage.FILL;
        }
        return ActionStatus.RUNNING;
    }

    /** Silverfish stop coming once the spawner is gone. */
    private ActionStatus breakSpawner(BotContext ctx, AiPlayer p) {
        if (!p.level().getBlockState(spawner).is(Blocks.SPAWNER)) {
            ctx.breaker().cancel();
            stage = Stage.FILL;
            return ActionStatus.RUNNING;
        }
        if (!breaking) {
            Approach.Status s = approach.tick(ctx);
            if (s.state() == Approach.State.RUNNING) return ActionStatus.RUNNING;
            if (s.state() == Approach.State.FAILED) {
                stage = Stage.FILL; // the eyes can still go in with the spawner running
                return ActionStatus.RUNNING;
            }
            breaking = true;
            ctx.breaker().start(spawner);
        }
        BlockBreaker.Status s = ctx.breaker().tick();
        if (s.state() == BlockBreaker.Status.State.FAILED) {
            ctx.breaker().cancel();
            stage = Stage.FILL;
        }
        return ActionStatus.RUNNING;
    }

    private ActionStatus fill(BotContext ctx, AiPlayer p) {
        if (frame == null) {
            List<BlockPos> empty = emptyFrames(p.level());
            if (empty.isEmpty()) {
                stage = Stage.CHECK;
                return ActionStatus.RUNNING;
            }
            BlockPos feet = p.blockPosition();
            frame = empty.stream().min(Comparator.comparingDouble(f -> f.distSqr(feet))).orElseThrow();
            approach = Approach.block(frame, REACH);
            clicks = 0;
        }
        if (isFilled(p.level().getBlockState(frame))) {
            filled++;
            frame = null;
            return ActionStatus.RUNNING;
        }
        Approach.Status s = approach.tick(ctx);
        if (s.state() == Approach.State.RUNNING) return ActionStatus.RUNNING;
        if (s.state() == Approach.State.FAILED) return ActionStatus.failed(s.reason());
        if (++clicks > CLICK_TRIES) return ActionStatus.failed("the frame at " + coords(frame) + " did not take the eye");
        if (!ItemUse.select(p, st -> st.is(Items.ENDER_EYE))) return ActionStatus.failed("ran out of ender_eye");
        // A look at a frame makes the eye's use pass, so this click is never a throw.
        BlockUse.Result r = BlockUse.clickFace(p, frame, Direction.UP);
        if (r == BlockUse.Result.NOT_VISIBLE) r = BlockUse.click(p, frame);
        if (r == BlockUse.Result.NOT_VISIBLE) return ActionStatus.failed("cannot see the frame at " + coords(frame));
        return ActionStatus.RUNNING;
    }

    private ActionStatus check(AiPlayer p) {
        ServerLevel level = p.level();
        BlockPos any = ring.get(0);
        boolean open = false;
        for (BlockPos c : BlockPos.betweenClosed(any.offset(-RING, -2, -RING), any.offset(RING, 2, RING))) {
            if (level.getBlockState(c).is(Blocks.END_PORTAL)) {
                open = true;
                break;
            }
        }
        if (!open) return ActionStatus.failed("all frames filled but no portal opened (the frames face the wrong way?)");
        summary = filled == 0 ? "the end portal is already open" : "filled " + filled + " frames; the end portal is open";
        return ActionStatus.DONE;
    }

    /** All end portal frames within 8 blocks of {@code start} (and one up or down), nearest the start first. */
    private static List<BlockPos> ringAround(ServerLevel level, BlockPos start) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos c : BlockPos.betweenClosed(start.offset(-RING, -1, -RING), start.offset(RING, 1, RING))) {
            if (level.getBlockState(c).is(Blocks.END_PORTAL_FRAME)) out.add(c.immutable());
        }
        out.sort(Comparator.comparingDouble(c -> c.distSqr(start)));
        return out;
    }

    private List<BlockPos> emptyFrames(ServerLevel level) {
        return ring.stream().filter(f -> level.getBlockState(f).is(Blocks.END_PORTAL_FRAME) && !isFilled(level.getBlockState(f))).toList();
    }

    private static boolean isFilled(BlockState s) {
        return s.is(Blocks.END_PORTAL_FRAME) && s.getValue(EndPortalFrameBlock.HAS_EYE);
    }

    private static String coords(BlockPos b) {
        return b.getX() + " " + b.getY() + " " + b.getZ();
    }

    @Override
    public void cancel(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
        ctx.breaker().cancel();
    }

    @Override
    public void interrupted(BotContext ctx) {
        ctx.breaker().cancel();
        breaking = false;
        if (stage == Stage.FILL && frame != null) approach = Approach.block(frame, REACH);
        else if (stage == Stage.SPAWNER && spawner != null) approach = Approach.block(spawner, REACH);
    }

    @Override
    public String describe() {
        return summary != null ? summary : "fill the end portal";
    }

    @Override
    public int timeoutTicks() {
        return 1200;
    }
}
