package com.aiciv.autopilot.goal;

import java.util.List;
import java.util.Set;

/** Three diamonds, with the pickaxes it takes to get them. */
final class MineDiamonds implements Goal {
    private static final List<Step> STEPS = List.of(Steps.WOODEN_PICKAXE, Steps.STONE_PICKAXE, Steps.IRON_PICKAXE,
            Steps.of("diamonds", s -> s.count("diamond") >= 3, s -> Gather.move("diamond", 3 - s.count("diamond"), s),
                    Set.of("mine", "goTo"), false, null));

    @Override public String id() { return "mine_diamonds"; }
    @Override public String summary() { return "mine diamonds"; }
    @Override public List<Step> steps() { return STEPS; }
}
