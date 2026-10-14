package com.aiciv.nether;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockUse;
import com.aiciv.world.Finder;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemUse;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.PortalShape;

/** Lights a complete, empty obsidian frame by putting fire inside it, with flint and steel or a fire charge. */
public final class LightPortalAction implements Action {
    static final int SIGHT = 16;
    static final double REACH = 4.0;
    static final int FIRE_TICKS = 5;
    static final int MAX_CLICKS = 4;
    static final Predicate<ItemStack> IGNITER = s -> s.is(Items.FLINT_AND_STEEL) || s.is(Items.FIRE_CHARGE);

    private enum Stage { FIND, APPROACH, IGNITE, WAIT }

    private final BlockPos given;
    private Stage stage = Stage.FIND;
    private Approach approach;
    private BlockPos target;
    private int clicks;
    private int waited;
    private String summary;

    /** Lights the frame standing on the obsidian block {@code frameBlockOrNull}, or the nearest visible complete frame. */
    public LightPortalAction(BlockPos frameBlockOrNull) {
        this.given = frameBlockOrNull;
    }

    /** Whether {@code pos} is an obsidian block with air above it inside a complete, empty frame. */
    static boolean isFrameBase(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).is(Blocks.OBSIDIAN)) return false;
        BlockPos inside = pos.above();
        if (!level.getBlockState(inside).isAir()) return false;
        return PortalShape.findEmptyPortalShape(level, inside, Direction.Axis.X).isPresent()
                || PortalShape.findEmptyPortalShape(level, inside, Direction.Axis.Z).isPresent();
    }

    /** Frame base blocks the bot can see within {@code radius}, nearest first. */
    static List<BlockPos> framesInSight(AiPlayer p, int radius) {
        return Finder.visibleStates(p, s -> s.is(Blocks.OBSIDIAN), radius).stream()
                .filter(b -> isFrameBase(p.level(), b)).toList();
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        return switch (stage) {
            case FIND -> find(p);
            case APPROACH -> approach(ctx);
            case IGNITE -> ignite(ctx, p);
            case WAIT -> waitForPortal(p);
        };
    }

    private ActionStatus find(AiPlayer p) {
        if (InventoryOps.count(p, IGNITER) == 0) return ActionStatus.failed("needs flint_and_steel or a fire_charge");
        List<BlockPos> frames = given != null ? (isFrameBase(p.level(), given) ? List.of(given) : List.of()) : framesInSight(p, SIGHT);
        if (frames.isEmpty()) return ActionStatus.failed("no complete empty portal frame in sight (a frame is 4 wide and 5 tall)");
        target = frames.get(0);
        approach = Approach.block(target, REACH);
        stage = Stage.APPROACH;
        return ActionStatus.RUNNING;
    }

    private ActionStatus approach(BotContext ctx) {
        Approach.Status s = approach.tick(ctx);
        switch (s.state()) {
            case FAILED:
                return ActionStatus.failed(s.reason());
            case ARRIVED:
                stage = Stage.IGNITE;
                return ActionStatus.RUNNING;
            default:
                return ActionStatus.RUNNING;
        }
    }

    private ActionStatus ignite(BotContext ctx, AiPlayer p) {
        if (!ItemUse.select(p, IGNITER)) return ActionStatus.failed("needs flint_and_steel or a fire_charge");
        BlockUse.Result r = BlockUse.clickFace(p, target, Direction.UP);
        switch (r) {
            case USED:
                stage = Stage.WAIT;
                return waitForPortal(p);
            case NO_EFFECT:
                return ActionStatus.failed("the fire went out: the frame is not a complete portal");
            default:
                if (++clicks >= MAX_CLICKS) return ActionStatus.failed("could not reach the frame's base at " + coords(target));
                approach = Approach.block(target, REACH - 1);
                stage = Stage.APPROACH;
                return ActionStatus.RUNNING;
        }
    }

    private ActionStatus waitForPortal(AiPlayer p) {
        if (p.level().getBlockState(target.above()).is(Blocks.NETHER_PORTAL)) {
            summary = "lit the portal at " + coords(target.above());
            return ActionStatus.DONE;
        }
        if (++waited > FIRE_TICKS) return ActionStatus.failed("the fire went out: the frame is not a complete portal");
        return ActionStatus.RUNNING;
    }

    @Override
    public void interrupted(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
        approach = null;
        if (stage == Stage.APPROACH || stage == Stage.IGNITE) {
            approach = Approach.block(target, REACH);
            stage = Stage.APPROACH;
        }
    }

    @Override
    public void cancel(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
        ctx.navigator().stop();
    }

    @Override
    public String describe() {
        return summary != null ? summary : "light portal";
    }

    @Override
    public int timeoutTicks() {
        return 600;
    }

    static String coords(BlockPos b) {
        return b.getX() + " " + b.getY() + " " + b.getZ();
    }
}
