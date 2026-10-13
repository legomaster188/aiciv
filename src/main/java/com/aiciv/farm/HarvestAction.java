package com.aiciv.farm;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.actions.ItemGatherer;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockBreaker;
import com.aiciv.world.BlockUse;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemNames;
import com.aiciv.world.PlacedBlocks;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Breaks ripe crops in sight, picks up what they drop and replants each one with the matching seed it holds. */
public final class HarvestAction implements Action {
    static final int RADIUS = 16;
    static final double STAND_RADIUS = 3.5;
    static final double DROP_RADIUS = 4.0;
    static final int GATHER_TICKS = 60;

    private enum Phase { PICK, GO, BREAK, GATHER, REPLANT }

    private final int count;
    private final ItemGatherer gatherer = new ItemGatherer(this::drops);
    private final Set<BlockPos> skipped = new HashSet<>();
    private final Map<String, Integer> harvested = new LinkedHashMap<>();
    private int total;
    private int replanted;
    private Phase phase = Phase.PICK;
    private BlockPos crop;
    private String cropId;
    private Approach approach;
    private int ticks;
    private String result;

    public HarvestAction(int count) {
        this.count = count;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        return switch (phase) {
            case PICK -> pick(ctx);
            case GO -> go(ctx);
            case BREAK -> breakCrop(ctx);
            case GATHER -> gather(ctx);
            case REPLANT -> replant(ctx);
        };
    }

    private ActionStatus pick(BotContext ctx) {
        if (total >= count) return finish();
        AiPlayer p = ctx.player();
        PlacedBlocks placed = PlacedBlocks.of(p.level().getServer());
        for (BlockPos pos : FarmScan.visible(p, HarvestAction::ripe, RADIUS)) {
            if (skipped.contains(pos)) continue;
            if (placed.isPlacedByOther(p.level(), pos, p.getScoreboardName())) continue; // someone else's farm: wild or own crops only
            crop = pos;
            cropId = BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(pos).getBlock()).getPath();
            approach = Approach.block(pos, STAND_RADIUS);
            phase = Phase.GO;
            return ActionStatus.RUNNING;
        }
        if (total > 0) return finish();
        return ActionStatus.failed("no ripe crops in sight");
    }

    private ActionStatus go(BotContext ctx) {
        Approach.Status s = approach.tick(ctx);
        switch (s.state()) {
            case RUNNING:
                break;
            case FAILED:
                skipped.add(crop);
                phase = Phase.PICK;
                break;
            default:
                ctx.breaker().start(crop);
                phase = Phase.BREAK;
        }
        return ActionStatus.RUNNING;
    }

    private ActionStatus breakCrop(BotContext ctx) {
        BlockBreaker.Status s = ctx.breaker().tick();
        switch (s.state()) {
            case RUNNING:
                break;
            case FAILED:
                skipped.add(crop);
                phase = Phase.PICK;
                break;
            default:
                harvested.merge(cropId, 1, Integer::sum);
                total++;
                ticks = 0;
                phase = Phase.GATHER;
        }
        return ActionStatus.RUNNING;
    }

    private ActionStatus gather(BotContext ctx) {
        if (++ticks <= GATHER_TICKS && gatherer.tick(ctx)) return ActionStatus.RUNNING;
        gatherer.stop(ctx);
        Item seed = Crops.seedFor(cropId).map(ItemNames::item).orElse(null);
        if (seed == null || InventoryOps.count(ctx.player(), s -> s.is(seed)) == 0) {
            phase = Phase.PICK;
        } else {
            approach = Approach.block(crop.below(), STAND_RADIUS);
            phase = Phase.REPLANT;
        }
        return ActionStatus.RUNNING;
    }

    private ActionStatus replant(BotContext ctx) {
        Approach.Status s = approach.tick(ctx);
        if (s.state() == Approach.State.RUNNING) return ActionStatus.RUNNING;
        phase = Phase.PICK;
        if (s.state() == Approach.State.FAILED) return ActionStatus.RUNNING;
        AiPlayer p = ctx.player();
        Item seed = Crops.seedFor(cropId).map(ItemNames::item).orElseThrow();
        if (InventoryOps.selectBest(p, st -> st.is(seed) ? 1 : 0)) {
            BlockUse.clickFace(p, crop.below(), Direction.UP);
            if (p.level().getBlockState(crop).getBlock() instanceof CropBlock) replanted++;
        }
        return ActionStatus.RUNNING;
    }

    private ActionStatus finish() {
        StringBuilder sb = new StringBuilder("harvested ");
        int i = 0;
        for (Map.Entry<String, Integer> e : harvested.entrySet()) {
            if (i++ > 0) sb.append(", ");
            sb.append(e.getValue()).append(' ').append(e.getKey());
        }
        result = sb.append(" (replanted ").append(replanted).append(')').toString();
        return ActionStatus.DONE;
    }

    private static boolean ripe(BlockState s) {
        return s.getBlock() instanceof CropBlock c && c.isMaxAge(s);
    }

    private List<ItemEntity> drops(BotContext ctx) {
        BlockPos at = crop != null ? crop : ctx.player().blockPosition();
        return ctx.player().level().getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(DROP_RADIUS), ItemEntity::isAlive);
    }

    @Override
    public void cancel(BotContext ctx) {
        ctx.breaker().cancel();
        gatherer.stop(ctx);
        if (approach != null) approach.cancel(ctx);
    }

    @Override
    public void interrupted(BotContext ctx) {
        ctx.breaker().cancel();
        approach = null;
        if (phase == Phase.GO || phase == Phase.BREAK || phase == Phase.REPLANT) phase = Phase.PICK;
    }

    @Override
    public String describe() {
        return result != null ? result : "harvest";
    }

    @Override
    public int timeoutTicks() {
        return 3 * 60 * 20;
    }
}
