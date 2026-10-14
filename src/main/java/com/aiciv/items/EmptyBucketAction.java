package com.aiciv.items;

import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockUse;
import com.aiciv.world.Buckets;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.PlacedBlocks;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Pours a water or lava bucket into the cell at x y z. */
public final class EmptyBucketAction extends ReachAction {
    private static final double[] RADII = {REACH, 3.0, 2.0};

    private final int x, y, z;
    private Item bucket;
    private int attempt;

    public EmptyBucketAction(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    protected String prepare(AiPlayer p) {
        boolean water = InventoryOps.has(p, Items.WATER_BUCKET, 1);
        boolean lava = InventoryOps.has(p, Items.LAVA_BUCKET, 1);
        if (!water && !lava) return "needs a water_bucket or lava_bucket";
        bucket = water ? Items.WATER_BUCKET : Items.LAVA_BUCKET;
        target = new BlockPos(x, y, z);
        if (bucket == Items.LAVA_BUCKET) {
            ServerLevel level = p.level();
            PlacedBlocks placed = PlacedBlocks.of(level.getServer());
            Optional<BlockPos> near = placed.placedByOtherNear(level, target, PlacedBlocks.LAVA_KEEP_AWAY, p.getScoreboardName(), s -> true);
            if (near.isPresent()) {
                return "won't pour lava at " + coords(target) + ": " + placed.describe(level, near.get()) + " is within "
                        + PlacedBlocks.LAVA_KEEP_AWAY + " blocks";
            }
        }
        return null;
    }

    @Override
    protected ActionStatus arrived(BotContext ctx) {
        BlockUse.Result r = Buckets.pour(ctx.player(), target, bucket);
        if (r == BlockUse.Result.USED) {
            done = "poured " + (bucket == Items.WATER_BUCKET ? "water" : "lava") + " at " + coords(target);
            return ActionStatus.DONE;
        }
        if ((r == BlockUse.Result.NOT_VISIBLE || r == BlockUse.Result.OUT_OF_REACH) && ++attempt < RADII.length) {
            return retryCloser(RADII[attempt]);
        }
        return ActionStatus.failed(switch (r) {
            case OUT_OF_REACH -> "too far from " + coords(target) + " to pour";
            case NOT_VISIBLE -> "can't see a solid face next to " + coords(target) + " to pour against";
            default -> "can't pour at " + coords(target) + ": it needs a free cell next to a solid block";
        });
    }

    @Override
    protected String label() {
        return "empty_bucket " + x + " " + y + " " + z;
    }

    @Override
    public int timeoutTicks() {
        return 600;
    }
}
