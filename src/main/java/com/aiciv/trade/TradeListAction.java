package com.aiciv.trade;

import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import java.util.List;

/** Opens a villager's trading menu, writes its offers into the bot's events and memory, and closes it again. */
public final class TradeListAction extends VillagerAction {
    public TradeListAction(String villagerOrNull) {
        super(villagerOrNull);
    }

    @Override
    ActionStatus withMenu(BotContext ctx, AiPlayer p) {
        List<OfferView> offers = readOffers(ctx);
        ctx.event(where() + " offers: " + joined(offers, offers.size()));
        closeMenu(p);
        return ActionStatus.DONE;
    }

    @Override
    public String describe() {
        return profession == null ? "trade_list" : "trade_list " + profession;
    }

    @Override
    public int timeoutTicks() {
        return 60 * 20;
    }
}
