package com.aiciv.items;

import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.actions.ItemGatherer;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockUse;
import com.aiciv.world.Finder;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemUse;
import com.aiciv.world.PlacedBlocks;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** Carves a pumpkin with shears and picks up the seeds that fall out. */
public final class CarvePumpkinAction extends ReachAction {
    static final int RADIUS = 8;
    static final int GATHER_TICKS = 60;

    private final BlockPos given;
    private ItemGatherer gatherer;
    private int gathering;

    public CarvePumpkinAction(BlockPos orNull) {
        this.given = orNull;
    }

    @Override
    protected String prepare(AiPlayer p) {
        if (!InventoryOps.has(p, Items.SHEARS, 1)) return "needs shears";
        ServerLevel level = p.level();
        PlacedBlocks placed = PlacedBlocks.of(level.getServer());
        String me = p.getScoreboardName();
        if (given != null) {
            if (!level.getBlockState(given).is(Blocks.PUMPKIN)) return "no uncarved pumpkin at " + coords(given);
            target = given;
        } else {
            List<BlockPos> found = Finder.visibleStates(p, s -> s.is(Blocks.PUMPKIN), RADIUS);
            target = found.stream().filter(pos -> !placed.isPlacedByOther(level, pos, me)).findFirst().orElse(null);
            if (target == null) return "no pumpkin in sight within " + RADIUS + " blocks";
        }
        if (placed.isPlacedByOther(level, target, me)) {
            return "that pumpkin belongs to " + placed.at(level, target).map(pl -> pl.placer()).orElse("someone else");
        }
        return null;
    }

    @Override
    protected ActionStatus arrived(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (gatherer == null) {
            if (!ItemUse.select(p, s -> s.is(Items.SHEARS))) return ActionStatus.failed("needs shears");
            BlockUse.Result r = BlockUse.click(p, target);
            if (r == BlockUse.Result.OUT_OF_REACH) return ActionStatus.failed("too far from the pumpkin at " + coords(target));
            if (r == BlockUse.Result.NOT_VISIBLE) return ActionStatus.failed("can't see the pumpkin at " + coords(target));
            if (!p.level().getBlockState(target).is(Blocks.CARVED_PUMPKIN)) return ActionStatus.failed("the pumpkin was not carved");
            done = "carved the pumpkin at " + coords(target);
            gatherer = new ItemGatherer(c -> seeds(c, target));
            return ActionStatus.RUNNING;
        }
        if (++gathering > GATHER_TICKS || !gatherer.tick(ctx)) {
            ctx.navigator().stop();
            return ActionStatus.DONE;
        }
        return ActionStatus.RUNNING;
    }

    private static List<ItemEntity> seeds(BotContext ctx, BlockPos at) {
        return ctx.player().level().getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(6),
                e -> e.isAlive() && e.getItem().is(Items.PUMPKIN_SEEDS));
    }

    @Override
    protected String label() {
        return "carve_pumpkin";
    }

    @Override
    public int timeoutTicks() {
        return 400;
    }
}
