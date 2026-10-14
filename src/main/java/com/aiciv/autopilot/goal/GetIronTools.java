package com.aiciv.autopilot.goal;

import java.util.List;

/** An iron pickaxe and sword. */
final class GetIronTools implements Goal {
    private static final List<Step> STEPS = List.of(Steps.WOODEN_PICKAXE, Steps.STONE_PICKAXE, Steps.IRON_PICKAXE,
            Steps.craft("iron_sword", 1));

    @Override public String id() { return "get_iron_tools"; }
    @Override public String summary() { return "get iron tools"; }
    @Override public List<Step> steps() { return STEPS; }
}
