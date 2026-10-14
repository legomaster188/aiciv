package com.aiciv.stations;

import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.Finder;
import com.aiciv.world.MenuOps;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * Finds the nearest station block (a smithing table, a loom...) within 16 blocks, walks up to it until the bot can see
 * it, and opens its menu with a right-click, like milestone 3's chest target. DONE means {@link #menu()} is open; the
 * action then does its clicks and calls {@link #close} before reporting.
 */
final class StationTarget<M extends AbstractContainerMenu> {
    static final int RADIUS = 16;
    private static final double REACH = 4.0;

    private final String blockId;
    private final Class<M> type;
    private BlockPos pos;
    private Approach approach;
    private M menu;

    StationTarget(String blockId, Class<M> type) {
        this.blockId = blockId;
        this.type = type;
    }

    /** RUNNING while walking, DONE once the menu is open, FAILED with a reason otherwise. */
    ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (menu != null) return p.containerMenu == menu ? ActionStatus.DONE : closedFailure(ctx);
        if (pos == null) {
            pos = Finder.nearestBlock(ctx, id -> id.equals(blockId), RADIUS).orElse(null);
            if (pos == null) return ActionStatus.failed("no " + blockId + " within " + RADIUS + " blocks");
            approach = Approach.block(pos, REACH);
        }
        if (approach != null) {
            Approach.Status s = approach.tick(ctx);
            if (s.state() == Approach.State.RUNNING) return ActionStatus.RUNNING;
            approach = null;
            if (s.state() == Approach.State.FAILED) return ActionStatus.failed(s.reason());
        }
        menu = MenuOps.open(p, pos, type);
        if (menu == null) return ActionStatus.failed("the " + blockId + " won't open");
        return ActionStatus.DONE;
    }

    private ActionStatus closedFailure(BotContext ctx) {
        close(ctx);
        return ActionStatus.failed("the " + blockId + " closed");
    }

    /** The open menu; null until {@link #tick} returned DONE. */
    M menu() {
        return menu;
    }

    /** Stops walking and closes the menu, handing back anything on the cursor. Safe to call at any time. */
    void close(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
        approach = null;
        menu = null;
        if (ctx.player() != null) MenuOps.close(ctx.player());
    }

    /** Forget the walk (a reflex moved the bot); the next tick finds and approaches the station again. */
    void reset(BotContext ctx) {
        close(ctx);
        pos = null;
    }
}
