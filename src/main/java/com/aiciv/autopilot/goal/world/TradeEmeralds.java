package com.aiciv.autopilot.goal.world;

import com.aiciv.autopilot.Calls;
import com.aiciv.autopilot.Situation;
import com.aiciv.autopilot.goal.Goal;
import com.aiciv.autopilot.goal.Move;
import com.aiciv.autopilot.goal.Step;
import com.aiciv.autopilot.goal.Steps;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Sell surplus to villagers for emeralds and buy food with them. Offers come from the remembered {@code offer:} tags. */
public final class TradeEmeralds implements Goal {
    private static final int EMERALD_TARGET = 16;
    private static final int FOOD_TARGET = 8;
    private static final int MAX_TIMES = 4;

    /** {@code offer:<profession>:<index>:<result>:<costA>:<countA>[:x:y:z]}. */
    private record Offer(String profession, int index, String result, String cost, int costCount) {}

    private static final Step LIST = Steps.of("list", s -> !s.tagsWithPrefix("offer:").isEmpty(),
            s -> Move.act("ask the villagers what they trade", Calls.of("trade_list")), Set.of("trade_list"), false, null);

    private static final Step SELL = Steps.of("sell", s -> s.count("emerald") >= EMERALD_TARGET || sellable(s).isEmpty(),
            s -> sellable(s).map(o -> Move.act("sell " + o.cost() + " to a " + o.profession(),
                            Calls.of("trade", "villager", o.profession(), "offer", o.index(), "times",
                                    Math.max(1, Math.min(s.count(o.cost()) / o.costCount(), MAX_TIMES)))))
                    .orElseGet(() -> Move.blocked("nothing to sell")),
            Set.of("trade"), false, null);

    private static final Step BUY_FOOD = Steps.of("buy_food", s -> s.foodItems() >= FOOD_TARGET || foodOffer(s).isEmpty(),
            s -> foodOffer(s).map(o -> Move.act("buy " + o.result() + " from a " + o.profession(),
                            Calls.of("trade", "villager", o.profession(), "offer", o.index(), "times", 1)))
                    .orElseGet(() -> Move.blocked("no food to buy")),
            Set.of("trade"), true, null);

    private static final List<Step> STEPS = List.of(LIST, SELL, BUY_FOOD);

    @Override public String id() { return "trade_emeralds"; }
    @Override public String summary() { return "trade with villagers"; }
    @Override public List<Step> steps() { return STEPS; }

    @Override
    public double need(Situation s) {
        if (s.tagsWithPrefix("villager:").isEmpty()) return 0;
        return sellable(s).isPresent() ? 1.0 : 0.5;
    }

    private static Optional<Offer> parse(String tag) {
        String[] p = tag.split(":");
        if (p.length < 6) return Optional.empty();
        try {
            return Optional.of(new Offer(p[1], Integer.parseInt(p[2]), p[3], p[4], Integer.parseInt(p[5])));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** The first offer that pays emeralds for something held in the required quantity. */
    private static Optional<Offer> sellable(Situation s) {
        for (String tag : s.tagsWithPrefix("offer:")) {
            Optional<Offer> o = parse(tag);
            if (o.isPresent() && o.get().result().equals("emerald") && o.get().costCount() > 0
                    && s.count(o.get().cost()) >= o.get().costCount()) return o;
        }
        return Optional.empty();
    }

    /** The first food offer paid in emeralds that the bot can afford. */
    private static Optional<Offer> foodOffer(Situation s) {
        for (String tag : s.tagsWithPrefix("offer:")) {
            Optional<Offer> o = parse(tag);
            if (o.isPresent() && Situation.FOODS.contains(o.get().result()) && o.get().cost().equals("emerald")
                    && s.count("emerald") >= o.get().costCount()) return o;
        }
        return Optional.empty();
    }
}
