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
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.block.Blocks;

/** Points a compass at a lodestone by using the compass on it. */
public final class LinkCompassAction extends ReachAction {
    static final int RADIUS = 8;

    private final BlockPos given;

    public LinkCompassAction(BlockPos lodestoneOrNull) {
        this.given = lodestoneOrNull;
    }

    private static boolean plain(ItemStack s) {
        return s.is(Items.COMPASS) && !s.has(DataComponents.LODESTONE_TRACKER);
    }

    @Override
    protected String prepare(AiPlayer p) {
        if (InventoryOps.count(p, s -> s.is(Items.COMPASS)) == 0) return "needs a compass";
        if (given != null) {
            if (!p.level().getBlockState(given).is(Blocks.LODESTONE)) return "no lodestone at " + coords(given);
            target = given;
            return null;
        }
        List<BlockPos> found = Finder.visibleStates(p, s -> s.is(Blocks.LODESTONE), RADIUS);
        if (found.isEmpty()) return "no lodestone in sight within " + RADIUS + " blocks";
        target = found.get(0);
        return null;
    }

    @Override
    protected ActionStatus arrived(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (!ItemUse.select(p, LinkCompassAction::plain) && !ItemUse.select(p, s -> s.is(Items.COMPASS))) {
            return ActionStatus.failed("needs a compass");
        }
        BlockUse.Result r = BlockUse.click(p, target);
        if (r == BlockUse.Result.OUT_OF_REACH) return ActionStatus.failed("too far from the lodestone at " + coords(target));
        if (r == BlockUse.Result.NOT_VISIBLE) return ActionStatus.failed("can't see the lodestone at " + coords(target));
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            LodestoneTracker t = s.is(Items.COMPASS) ? s.get(DataComponents.LODESTONE_TRACKER) : null;
            if (t != null && t.target().isPresent() && t.target().get().pos().equals(target)) {
                done = "linked a compass to the lodestone at " + coords(target);
                return ActionStatus.DONE;
            }
        }
        return ActionStatus.failed("the compass did not link to the lodestone");
    }

    @Override
    protected String label() {
        return "link_compass";
    }

    @Override
    public int timeoutTicks() {
        return 400;
    }
}
