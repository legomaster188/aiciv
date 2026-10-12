package com.aiciv.storage;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.nav.Pos;
import com.aiciv.world.BlockPlacer;
import com.aiciv.world.InventoryOps;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;

/** Puts a held chest on a free floor cell next to the bot. */
public final class PlaceChestAction implements Action {
    private static final int TRIES = 10;

    private int tries;
    private String doneSummary;

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (InventoryOps.count(p, s -> s.is(Items.CHEST)) < 1) return ActionStatus.failed("no chest in inventory");
        BlockPos cell = BlockPlacer.placeNearby(p, s -> s.is(Items.CHEST));
        if (cell == null) {
            if (++tries >= TRIES) return ActionStatus.failed("no room to place a chest here");
            return ActionStatus.RUNNING;
        }
        long tick = p.level().getGameTime();
        Pos at = new Pos(cell.getX(), cell.getY(), cell.getZ());
        if (ctx.knownBlocks() != null) ctx.knownBlocks().see("chest", at, tick);
        if (ctx.attachments() != null) ctx.attachments().get(ChestMemory.class, ChestMemory::new).seen(at, Map.of(), tick);
        doneSummary = "placed a chest at " + cell.getX() + " " + cell.getY() + " " + cell.getZ();
        return ActionStatus.DONE;
    }

    @Override
    public String describe() {
        return doneSummary != null ? doneSummary : "place a chest";
    }

    @Override
    public int timeoutTicks() {
        return 30 * 20;
    }
}
