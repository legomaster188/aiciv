package com.aiciv.items;

import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockUse;
import com.aiciv.world.Finder;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemUse;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Spends bone meal on a growing crop, a sapling or a grass block until the crop is ripe or the tree has grown. */
public final class BoneMealAction extends ReachAction {
    static final int RADIUS = 8;
    static final int GAP_TICKS = 4;

    private final BlockPos given;
    private final int count;
    private String block = "block";
    private int used;
    private int failures;
    private long nextUse;

    public BoneMealAction(BlockPos targetOrNull, int count) {
        this.given = targetOrNull;
        this.count = count;
    }

    @Override
    protected String prepare(AiPlayer p) {
        if (!InventoryOps.has(p, Items.BONE_MEAL, 1)) return "no bone_meal";
        if (given != null) {
            if (!usable(p.level().getBlockState(given))) {
                return "bone_meal does nothing on " + blockId(p, given) + " at " + coords(given);
            }
            target = given;
        } else {
            List<BlockPos> found = Finder.visibleStates(p, BoneMealAction::growing, RADIUS);
            if (found.isEmpty()) found = Finder.visibleStates(p, s -> s.is(Blocks.GRASS_BLOCK), RADIUS);
            if (found.isEmpty()) {
                return "no growing crop, sapling or grass in sight within " + RADIUS + " blocks";
            }
            target = found.get(0);
        }
        block = blockId(p, target);
        nextUse = 0;
        return null;
    }

    /** A crop that is not ripe yet, or a sapling. */
    private static boolean growing(BlockState s) {
        if (s.getBlock() instanceof CropBlock crop) return !crop.isMaxAge(s);
        return s.is(BlockTags.SAPLINGS);
    }

    private static boolean usable(BlockState s) {
        if (s.getBlock() instanceof CropBlock crop) return !crop.isMaxAge(s);
        return s.getBlock() instanceof BonemealableBlock;
    }

    @Override
    protected ActionStatus arrived(BotContext ctx) {
        AiPlayer p = ctx.player();
        BlockState now = p.level().getBlockState(target);
        if (used >= count || finished(now)) return finish(now);
        if (p.level().getGameTime() < nextUse) return ActionStatus.RUNNING;
        if (!ItemUse.select(p, s -> s.is(Items.BONE_MEAL))) {
            if (used > 0) return finish(now);
            return ActionStatus.failed("no bone_meal");
        }
        BlockUse.Result r = BlockUse.click(p, target);
        nextUse = p.level().getGameTime() + GAP_TICKS;
        switch (r) {
            case USED:
                used++;
                return ActionStatus.RUNNING;
            case OUT_OF_REACH:
                return ActionStatus.failed("too far from the " + block + " at " + coords(target));
            case NOT_VISIBLE:
                return ActionStatus.failed("can't see the " + block + " at " + coords(target));
            default:
                if (++failures < 2) return ActionStatus.RUNNING;
                if (used > 0) return finish(now);
                return ActionStatus.failed("bone_meal had no effect on the " + block + " at " + coords(target));
        }
    }

    private boolean finished(BlockState s) {
        if (s.getBlock() instanceof CropBlock crop) return crop.isMaxAge(s);
        return block.endsWith("sapling") && !s.is(BlockTags.SAPLINGS);
    }

    private ActionStatus finish(BlockState s) {
        String outcome = "";
        if (s.getBlock() instanceof CropBlock crop && crop.isMaxAge(s)) outcome = " (now ripe)";
        else if (block.endsWith("sapling") && !s.is(BlockTags.SAPLINGS)) outcome = " (grew into a tree)";
        done = "used " + used + " bone_meal on " + block + outcome;
        return ActionStatus.DONE;
    }

    @Override
    protected String label() {
        return "bone_meal " + count;
    }

    @Override
    public int timeoutTicks() {
        return 600;
    }
}
