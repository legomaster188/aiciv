package com.aiciv.autopilot.goal;

import java.util.List;

/** A wooden pickaxe and sword. */
final class GetWoodTools implements Goal {
    private static final List<Step> STEPS = List.of(Steps.WOODEN_PICKAXE, Steps.craft("wooden_sword", 1));

    @Override public String id() { return "get_wood_tools"; }
    @Override public String summary() { return "get wood tools"; }
    @Override public List<Step> steps() { return STEPS; }
}
