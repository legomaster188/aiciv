package com.aiciv.farm;

import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockUse;
import com.aiciv.world.Finder;
import com.aiciv.world.InventoryOps;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.Blocks;

/** Tills dirt and grass with a hoe, preferring blocks where vanilla farmland would stay moist. */
public final class TillAction extends BlockClickAction {
    static final int RADIUS = 8;

    private int nearWater;

    public TillAction(int count) {
        super(count);
    }

    @Override
    String start(AiPlayer p) {
        return InventoryOps.count(p, s -> s.is(ItemTags.HOES)) > 0 ? null : "needs a hoe";
    }

    @Override
    List<BlockPos> candidates(AiPlayer p) {
        ServerLevel level = p.level();
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos pos : Finder.visibleStates(p, s -> s.is(BlockTags.TURNS_INTO_FARMLAND), RADIUS)) {
            if (level.getBlockState(pos.above()).isAir()) out.add(pos);
        }
        out.sort(Comparator.comparing((BlockPos pos) -> !isNearWater(level, pos))); // stable: nearest stays first within a group
        return out;
    }

    @Override
    boolean click(AiPlayer p, BlockPos pos) {
        if (!InventoryOps.selectBest(p, s -> s.is(ItemTags.HOES) ? 1 : 0)) return false;
        BlockUse.clickFace(p, pos, Direction.UP);
        if (!p.level().getBlockState(pos).is(Blocks.FARMLAND)) return false;
        if (isNearWater(p.level(), pos)) nearWater++;
        return true;
    }

    /** Vanilla's moisture rule: water within 4 blocks sideways, on the farmland's level or one above. */
    static boolean isNearWater(ServerLevel level, BlockPos pos) {
        for (BlockPos q : BlockPos.betweenClosed(pos.offset(-4, 0, -4), pos.offset(4, 1, 4))) {
            if (level.getFluidState(q).is(FluidTags.WATER)) return true;
        }
        return false;
    }

    @Override
    String summary(BotContext ctx) {
        if (nearWater == 0) ctx.event("tilled soil has no water within 4 blocks and will dry out");
        return "tilled " + succeeded + " (" + nearWater + " near water)";
    }

    @Override
    String nothingToDo() {
        return "no dirt or grass with air above within " + RADIUS;
    }

    @Override
    String label() {
        return "till " + count;
    }
}
