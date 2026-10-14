package com.aiciv.autopilot.goal.world;

import com.aiciv.autopilot.Calls;
import com.aiciv.autopilot.Situation;
import com.aiciv.autopilot.goal.Goal;
import com.aiciv.autopilot.goal.Move;
import com.aiciv.autopilot.goal.Step;
import com.aiciv.autopilot.goal.Steps;
import java.util.List;
import java.util.Set;

/** Grow wheat: hoe, seeds, till near water, plant, harvest, bake bread and breed cows with the extra wheat. */
public final class Farm implements Goal {
    private static final String SEEDS = "wheat_seeds";
    private static final String PLANTED = "planted";
    private static final String BREED = "farm/breed";

    private static final Step HOE = Steps.craft("wooden_hoe", 1);

    private static final Step SEED_STEP = Steps.of("seeds", s -> s.count(SEEDS) >= 1 || s.flag(PLANTED),
            s -> Move.gather("find seeds", Calls.of("mine", "block", "short_grass", "count", 3)),
            Set.of("mine"), false, null);

    private static final Step TILL = Steps.of("till", s -> s.hasTag("farmland_free") || s.flag(PLANTED),
            s -> Move.act("till soil near water", Calls.of("till", "count", Math.max(1, Math.min(8, s.count(SEEDS))))),
            Set.of("till"), false, null);

    private static final Step PLANT = Steps.of("plant",
            s -> s.flag(PLANTED) && (!s.hasTag("farmland_free") || s.count(SEEDS) == 0),
            s -> Move.act("plant the seeds", Calls.of("plant", "seed", SEEDS, "count", Math.max(1, Math.min(8, s.count(SEEDS))))),
            Set.of("plant"), false, PLANTED);

    private static final Step HARVEST = Steps.of("harvest", s -> !s.hasTag("crop_ripe"),
            s -> Move.act("harvest the ripe wheat", Calls.of("harvest", "count", 32)),
            Set.of("harvest"), false, null);

    private static final Step BREAD = Steps.of("bread", s -> s.count("wheat") < 3,
            s -> Move.act("bake bread", Calls.of("craft", "item", "bread", "count", s.count("wheat") / 3)),
            Set.of("craft"), true, null);

    private static final Step BREED_STEP = Steps.of("breed",
            s -> s.mobCount("cow", 16) < 2 || s.count("wheat") < 2 || s.doneWithin(BREED, 6000),
            s -> Move.act("breed cows", Calls.of("breed", "animal", "cow")),
            Set.of("breed"), true, null);

    private static final List<Step> STEPS = List.of(HOE, SEED_STEP, TILL, PLANT, HARVEST, BREAD, BREED_STEP);

    @Override public String id() { return "farm"; }
    @Override public String summary() { return "farm wheat"; }
    @Override public List<Step> steps() { return STEPS; }

    @Override
    public double need(Situation s) {
        if (s.hasTag("crop_ripe")) return 1.0;
        if (!s.flag(PLANTED)) return 0.8;
        if (s.hasTag("farmland_free") && s.count(SEEDS) > 0) return 0.7;
        return 0.1;
    }
}
