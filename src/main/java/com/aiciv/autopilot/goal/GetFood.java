package com.aiciv.autopilot.goal;

import com.aiciv.autopilot.Calls;
import com.aiciv.autopilot.Situation;
import java.util.List;
import java.util.Set;

/** Eight food items in the pack: smelt raw meat, harvest crops, hunt, fish, bake bread or look around. */
final class GetFood implements Goal {
    private static final int TARGET = 8;
    private static final List<String> ANIMALS = List.of("cow", "pig", "sheep", "chicken", "rabbit");
    private static final List<String> DROPS = List.of("beef", "porkchop", "mutton", "chicken", "rabbit");

    /** The shared food step; the endgame goal reuses it. */
    static final Step STEP = Steps.of("food", s -> s.foodItems() >= TARGET, GetFood::move, Set.of(), false, null);
    private static final List<Step> STEPS = List.of(STEP);

    private static Move move(Situation s) {
        boolean fuel = s.count("coal") + s.count("charcoal") >= 1 || s.logs() >= 1;
        if (fuel && s.hasAction("smelt")) {
            for (String raw : Situation.RAW_FOODS) {
                int held = s.count(raw);
                if (held > 0) return Move.act("cook " + raw, Calls.of("smelt", "item", raw, "count", Math.min(held, 8)));
            }
        }
        if (s.hasTag("crop_ripe") && s.hasAction("harvest")) {
            return Move.act("harvest the ripe crops", Calls.of("harvest", "count", 32));
        }
        for (int i = 0; i < ANIMALS.size(); i++) {
            String animal = ANIMALS.get(i);
            if (s.mobCount(animal, 24) >= 2) {
                return Move.act("hunt a " + animal, Calls.of("attack", "target", animal), Calls.of("collect", "item", DROPS.get(i)));
            }
        }
        if (s.count("fishing_rod") > 0 && s.sees("water", 16) && s.hasAction("fish")) {
            return Move.act("fish for food", Calls.of("fish", "catches", 3));
        }
        if (s.count("wheat") >= 3) {
            return Move.act("bake bread", Calls.of("craft", "item", "bread", "count", s.count("wheat") / 3));
        }
        if (!s.hasAction("explore")) return Move.blocked("needs explore (not available yet)");
        return Move.gather("look for food", Calls.of("explore", "direction", Gather.direction(s), "distance", s.tuning().exploreDistance()));
    }

    @Override public String id() { return "get_food"; }
    @Override public String summary() { return "get food"; }
    @Override public List<Step> steps() { return STEPS; }

    @Override
    public double need(Situation s) {
        int items = s.foodItems();
        if (items >= TARGET) return 0;
        if (s.food() <= 6 && items == 0) return 1.0;
        return (TARGET - items) / (double) TARGET * (s.food() <= 14 ? 1.0 : 0.5);
    }
}
