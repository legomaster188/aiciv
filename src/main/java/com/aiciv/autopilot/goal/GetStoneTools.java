package com.aiciv.autopilot.goal;

import java.util.List;

/** A stone pickaxe and sword. */
final class GetStoneTools implements Goal {
    private static final List<Step> STEPS = List.of(Steps.WOODEN_PICKAXE, Steps.STONE_PICKAXE, Steps.craft("stone_sword", 1));

    @Override public String id() { return "get_stone_tools"; }
    @Override public String summary() { return "get stone tools"; }
    @Override public List<Step> steps() { return STEPS; }
}
