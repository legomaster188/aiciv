package com.aiciv.stations;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * An action done at one station block: checks what it needs before walking anywhere, walks up and opens the menu,
 * lets {@link #work} click, and closes the menu again whatever happened.
 */
abstract class StationAction<M extends AbstractContainerMenu> implements Action {
    private final StationTarget<M> target;
    private boolean checked;
    /** What describe() says once the work succeeded. */
    protected String done;

    StationAction(String blockId, Class<M> menu) {
        this.target = new StationTarget<>(blockId, menu);
    }

    /** What the bot lacks ("needs ..."), or null when it has everything. Runs once, before any walking. */
    protected String missing(AiPlayer p) {
        return null;
    }

    /** The clicks, with the menu open. Sets {@link #done} and returns DONE, or returns a failure. */
    protected abstract ActionStatus work(BotContext ctx, AiPlayer p, M menu);

    @Override
    public final ActionStatus tick(BotContext ctx) {
        if (!checked) {
            checked = true;
            String problem = missing(ctx.player());
            if (problem != null) return ActionStatus.failed(problem);
        }
        ActionStatus s = target.tick(ctx);
        if (s.state() != ActionStatus.State.DONE) return s;
        try {
            return work(ctx, ctx.player(), target.menu());
        } finally {
            target.close(ctx);
        }
    }

    @Override
    public void cancel(BotContext ctx) {
        target.close(ctx);
    }

    @Override
    public void interrupted(BotContext ctx) {
        target.reset(ctx);
    }

    @Override
    public int timeoutTicks() {
        return 600;
    }
}
