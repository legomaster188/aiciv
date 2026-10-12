package com.aiciv.utility;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockPlacer;
import com.aiciv.world.BlockUse;
import com.aiciv.world.Finder;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Sleeps in the nearest free bed (placing a carried one if there is none) and stays in it until the bot wakes. */
public final class SleepAction implements Action {
    static final int FIND_RADIUS = 16;
    static final double REACH = 2.5;
    static final int TIMEOUT = 12 * 60 * 20;

    private enum Phase { FIND, WALK, SLEEP }

    private Phase phase = Phase.FIND;
    private BlockPos bed;
    private Approach approach;
    private String done;

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        ServerLevel level = p.level();
        switch (phase) {
            case FIND: {
                if (!level.isDarkOutside() && !level.isThundering()) return ActionStatus.failed("you can only sleep at night or in a thunderstorm");
                Optional<BlockPos> found = freeBed(ctx, p);
                if (found.isEmpty()) {
                    if (p.getInventory().getNonEquipmentItems().stream().noneMatch(s -> s.is(ItemTags.BEDS))) {
                        return ActionStatus.failed("no bed nearby and none in your inventory");
                    }
                    BlockPos placed = BlockPlacer.placeNearby(p, s -> s.is(ItemTags.BEDS));
                    if (placed == null) return ActionStatus.failed("no free spot next to me to place a bed");
                    found = Optional.of(placed);
                }
                bed = found.get();
                approach = Approach.block(bed, REACH);
                phase = Phase.WALK;
                return ActionStatus.RUNNING;
            }
            case WALK: {
                Approach.Status s = approach.tick(ctx);
                if (s.state() == Approach.State.RUNNING) return ActionStatus.RUNNING;
                if (s.state() == Approach.State.FAILED) return ActionStatus.failed(s.reason());
                BlockUse.click(p, bed);
                if (!p.isSleeping()) return ActionStatus.failed(whyNot(level, p));
                phase = Phase.SLEEP;
                return ActionStatus.RUNNING;
            }
            default: {
                if (p.isSleeping()) return ActionStatus.RUNNING;
                done = level.isDarkOutside() ? "woke up" : "slept until morning";
                return ActionStatus.DONE;
            }
        }
    }

    /** A bed nobody is in: one in sight first, else one remembered. */
    private static Optional<BlockPos> freeBed(BotContext ctx, AiPlayer p) {
        ServerLevel level = p.level();
        List<BlockPos> seen = Finder.visibleStates(p, s -> s.getBlock() instanceof AbstractBedBlock && !s.getValue(AbstractBedBlock.OCCUPIED), FIND_RADIUS);
        if (!seen.isEmpty()) return Optional.of(seen.get(0));
        Optional<BlockPos> known = Finder.nearestBlock(ctx, id -> id.endsWith("_bed"), FIND_RADIUS);
        if (known.isPresent()) {
            BlockState s = level.getBlockState(known.get());
            if (s.getBlock() instanceof AbstractBedBlock && !s.getValue(AbstractBedBlock.OCCUPIED)) return known;
        }
        return Optional.empty();
    }

    /** The first rule that stopped the bot from lying down. */
    private String whyNot(ServerLevel level, AiPlayer p) {
        if (!level.isDarkOutside() && !level.isThundering()) return "you can only sleep at night or in a thunderstorm";
        boolean monsters = !level.getEntitiesOfClass(Monster.class, new AABB(bed).inflate(8, 5, 8), m -> m.isPreventingPlayerRest(level, p)).isEmpty();
        return monsters ? "monsters are nearby" : "the bed is blocked or too far";
    }

    @Override
    public void cancel(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
        AiPlayer p = ctx.player();
        if (p != null && p.isSleeping()) p.stopSleepInBed(true, true);
    }

    @Override
    public void interrupted(BotContext ctx) {
        if (phase == Phase.WALK) {
            approach = Approach.block(bed, REACH);
        }
    }

    @Override
    public String describe() {
        return done != null ? done : "sleep";
    }

    @Override
    public int timeoutTicks() {
        return TIMEOUT;
    }
}
