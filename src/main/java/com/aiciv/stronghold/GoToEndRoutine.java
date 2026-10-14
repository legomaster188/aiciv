package com.aiciv.stronghold;

import com.aiciv.actions.Action;
import com.aiciv.actions.BotContext;
import com.aiciv.actions.Routine;
import com.aiciv.bot.AiPlayer;
import com.aiciv.dimension.EnterPortalAction;
import com.aiciv.dimension.Portals;
import com.aiciv.world.Finder;
import net.minecraft.world.level.block.Blocks;

/** From a stronghold to the End: find the portal room, fill the frames and walk in. Each stage is skipped when it is already done. */
public final class GoToEndRoutine extends Routine {
    static final int SIGHT = 16;

    private final boolean anyDimension;
    private int step;
    private String summary;

    public GoToEndRoutine() {
        this(false);
    }

    public GoToEndRoutine(boolean anyDimension) {
        this.anyDimension = anyDimension;
    }

    @Override
    protected Action nextStage(BotContext ctx) {
        AiPlayer p = ctx.player();
        while (true) {
            switch (step++) {
                case 0 -> {
                    if (Finder.visibleStates(p, s -> s.is(Blocks.END_PORTAL_FRAME), SIGHT).isEmpty()) {
                        return new FindPortalRoomAction(64, anyDimension);
                    }
                }
                case 1 -> {
                    if (Finder.visibleStates(p, s -> s.is(Blocks.END_PORTAL), SIGHT).isEmpty()) return new FillEndPortalAction();
                }
                case 2 -> {
                    return new EnterPortalAction("end");
                }
                default -> {
                    summary = "went through the end portal to " + Portals.shortName(ctx.dimension());
                    return null;
                }
            }
        }
    }

    @Override
    protected String name() {
        return "go to the End";
    }

    @Override
    protected String summary() {
        return summary;
    }

    @Override
    public int timeoutTicks() {
        return 12000;
    }
}
