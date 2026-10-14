package com.aiciv.autopilot.goal.world;

import com.aiciv.autopilot.Calls;
import com.aiciv.autopilot.Situation;
import com.aiciv.autopilot.goal.Costs;
import com.aiciv.autopilot.goal.Gather;
import com.aiciv.autopilot.goal.Goal;
import com.aiciv.autopilot.goal.Move;
import com.aiciv.autopilot.goal.Step;
import com.aiciv.autopilot.goal.Steps;
import com.aiciv.llm.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** A five-block cobblestone hut, a chest inside it and, if wool is easy to find, a bed. */
public final class BuildBase implements Goal {
    private static final int COBBLESTONE = 71;
    private static final Step CRAFT_CHEST = Steps.craft("chest", 1);
    private static final Step CRAFT_BED = Steps.craft("white_bed", 1);

    private static final Step MATERIALS = Steps.of("materials", s -> s.home().isPresent() || Costs.have("cobblestone", s) >= COBBLESTONE,
            s -> Gather.move("cobblestone", Math.min(64, COBBLESTONE - Costs.have("cobblestone", s)), s),
            Set.of("mine"), false, null);

    private static final Step HUT = Steps.of("hut", s -> s.home().isPresent(),
            s -> Move.act("build a home", Calls.of("build_template", "template", "hut", "size", 5, "material", "cobblestone",
                    "name", Situation.homeName(s.name()))),
            Set.of("build_template"), false, "home_built");

    /** Crafting is its own step: a step raises its flag when any of its moves finishes, even a log-gathering one. */
    private static final Step CHEST_ITEM = Steps.of("chest_item", s -> s.count("chest") > 0 || s.flag("home_chest"),
            CRAFT_CHEST::next, Set.of("craft", "mine"), false, null);

    private static final Step CHEST = Steps.of("chest", s -> s.flag("home_chest"), s -> {
        if (s.count("chest") == 0) return Move.blocked("no chest to place");
        List<ToolCall> calls = new ArrayList<>();
        s.home().filter(h -> s.distanceTo(h) > 4).ifPresent(h -> calls.add(Calls.of("goTo", "x", h[0], "y", h[1], "z", h[2])));
        calls.add(Calls.of("place_chest"));
        return Move.act("put a chest in my home", calls.toArray(new ToolCall[0]));
    }, Set.of("place_chest"), false, "home_chest");

    private static final Step BED = Steps.of("bed", s -> hasBed(s) || s.flag("home_bed"), CRAFT_BED::next,
            Set.of("craft", "mine"), true, null);

    private static final List<Step> STEPS = List.of(Steps.WOODEN_PICKAXE, MATERIALS, HUT, CHEST_ITEM, CHEST, BED);

    private static boolean hasBed(Situation s) {
        return s.inventory().entrySet().stream().anyMatch(e -> e.getKey().endsWith("_bed") && e.getValue() > 0);
    }

    @Override public String id() { return "build_base"; }
    @Override public String summary() { return "build a home"; }
    @Override public List<Step> steps() { return STEPS; }

    @Override
    public double need(Situation s) {
        return achieved(s) ? 0 : 1;
    }
}
