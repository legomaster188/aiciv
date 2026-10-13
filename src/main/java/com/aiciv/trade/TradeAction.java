package com.aiciv.trade;

import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemNames;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;

/**
 * Buys (or sells) through the villager's real trading menu: select the offer, let the menu move the payment in, take
 * the result slot, once per trade, one trade every {@value #TRADE_GAP} ticks. Prices, stock and demand are the game's.
 */
public final class TradeAction extends VillagerAction {
    static final int TRADE_GAP = 10;
    private static final int RESULT_SLOT = 2;

    private final String want;
    private final Integer offer;
    private final int times;
    private int index = -1;
    private int wait;
    private int trades;
    private int paidA, paidB, received;
    private String costAId = "", costBId = "", resultId = "";
    private String summary;
    private final String expect;
    private final String pay;

    public TradeAction(String villagerOrNull, String wantOrNull, Integer offerOrNull, int times) {
        this(villagerOrNull, wantOrNull, offerOrNull, times, null, null, null);
    }

    /**
     * @param seenAtOrNull where the villager was seen; the trade is then made with that villager or not at all
     * @param expectOrNull the item the chosen offer must give, @param payOrNull the item it must cost (first cost):
     *     an offer number means nothing on another villager, or after the villager's trades changed
     */
    public TradeAction(String villagerOrNull, String wantOrNull, Integer offerOrNull, int times, BlockPos seenAtOrNull,
            String expectOrNull, String payOrNull) {
        super(villagerOrNull, seenAtOrNull);
        this.want = wantOrNull;
        this.offer = offerOrNull;
        this.times = times;
        this.expect = blankToNull(expectOrNull);
        this.pay = blankToNull(payOrNull);
    }

    /** An item id as offers show it (no namespace, lower case); null for nothing given. */
    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) return null;
        String id = s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    @Override
    ActionStatus withMenu(BotContext ctx, AiPlayer p) {
        if (p.containerMenu != menu) {
            menu = null; // closed under us (the villager moved off, a reflex, death)
            return finish(p, "the trade window closed");
        }
        if (index < 0) {
            ActionStatus problem = choose(ctx, p);
            if (problem != null) return problem;
        }
        if (trades >= times) return finish(p, null);
        if (wait-- > 0) return ActionStatus.RUNNING;
        List<MerchantOffer> offers = menu.getOffers();
        if (index >= offers.size()) return finish(p, "the offer is gone");
        MerchantOffer o = offers.get(index);
        if (o.isOutOfStock()) return finish(p, "sold out");

        ItemStack costA = o.getCostA();
        ItemStack costB = o.getCostB();
        int haveA = held(p, costA), haveB = held(p, costB);
        menu.setSelectionHint(index);
        menu.tryMoveItems(index);
        ItemStack result = menu.getSlot(RESULT_SLOT).getItem();
        if (result.isEmpty()) return finish(p, cantAfford(costA, costB, haveA, haveB));
        Inventory inv = p.getInventory();
        if (inv.getFreeSlot() < 0 && inv.getSlotWithRemainingSpace(result) < 0) return finish(p, "inventory full");

        costAId = idOf(costA);
        costBId = costB.isEmpty() ? "" : idOf(costB);
        resultId = idOf(result);
        int paidNowA = costA.getCount(), paidNowB = costB.isEmpty() ? 0 : costB.getCount();
        // One click on the result slot is one trade; QUICK_MOVE would repeat it until the payment ran out.
        menu.clicked(RESULT_SLOT, 0, ContainerInput.PICKUP, p);
        ItemStack carried = menu.getCarried();
        menu.setCarried(ItemStack.EMPTY);
        if (carried.isEmpty()) return finish(p, "the villager would not hand over the " + resultId);
        int got = carried.getCount();
        InventoryOps.addOrDrop(p, carried);
        paidA += paidNowA;
        paidB += paidNowB;
        received += got;
        trades++;
        wait = TRADE_GAP - 1;
        return ActionStatus.RUNNING;
    }

    /** Resolves the offer to buy. Returns null when it is chosen, else the failure (the menu is closed). */
    private ActionStatus choose(BotContext ctx, AiPlayer p) {
        List<OfferView> views = readOffers(ctx);
        if (offer != null) {
            if (offer >= views.size()) {
                ctx.event(where() + " offers: " + joined(views, views.size()));
                return finish(p, "the " + profession + " has no offer " + offer + "; offers: " + joined(views, 4));
            }
            OfferView chosen = views.get(offer);
            if ((expect != null && !expect.equals(chosen.result())) || (pay != null && !pay.equals(chosen.costA()))) {
                ctx.event(where() + " offers: " + joined(views, views.size()));
                return finish(p, "that villager's offer changed: offer " + offer + " is now " + chosen.text());
            }
            index = offer;
        } else {
            index = OfferView.find(views, want);
            if (index < 0) {
                ctx.event(where() + " offers: " + joined(views, views.size()));
                boolean soldOut = OfferView.find(views.stream().map(TradeAction::inStock).toList(), want) >= 0;
                return finish(p, soldOut
                        ? "the " + profession + " is sold out of " + want
                        : "the " + profession + " doesn't sell " + want + "; offers: " + joined(views, 4));
            }
        }
        return null;
    }

    private static OfferView inStock(OfferView o) {
        return new OfferView(o.index(), o.costA(), o.countA(), o.costB(), o.countB(), o.result(), o.resultCount(), 0, 1);
    }

    private static String cantAfford(ItemStack costA, ItemStack costB, int haveA, int haveB) {
        StringBuilder s = new StringBuilder("can't afford: needs ").append(costA.getCount()).append(' ').append(idOf(costA));
        if (!costB.isEmpty()) s.append(" and ").append(costB.getCount()).append(' ').append(idOf(costB));
        boolean shortA = haveA < costA.getCount();
        return s.append(" (have ").append(shortA || costB.isEmpty() ? haveA : haveB).append(')').toString();
    }

    private static int held(AiPlayer p, ItemStack cost) {
        return cost.isEmpty() ? 0 : InventoryOps.count(p, s -> ItemStack.isSameItem(s, cost));
    }

    private static String idOf(ItemStack s) {
        return ItemNames.id(s);
    }

    /** Closes the menu and ends: done when something was traded, else failed with {@code stopReason}. */
    private ActionStatus finish(AiPlayer p, String stopReason) {
        closeMenu(p);
        if (trades == 0) return ActionStatus.failed(stopReason == null ? "nothing was traded" : stopReason);
        String cost = paidA + " " + costAId + (paidB > 0 ? " + " + paidB + " " + costBId : "");
        summary = "traded " + trades + "x: " + cost + " -> " + received + " " + resultId
                + (stopReason != null && trades < times ? " (stopped: " + stopReason + ")" : "");
        return ActionStatus.DONE;
    }

    @Override
    public String describe() {
        if (summary != null) return summary;
        return "trade " + (offer != null ? "offer " + offer : want) + (profession == null ? "" : " with the " + profession);
    }

    @Override
    public int timeoutTicks() {
        return 120 * 20;
    }
}
