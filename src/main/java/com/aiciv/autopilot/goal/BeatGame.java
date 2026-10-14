package com.aiciv.autopilot.goal;

import com.aiciv.autopilot.Situation;
import java.util.ArrayList;
import java.util.List;

/** Iron gear, food, then the endgame steps from {@code endgame.json}: portal, nether, eyes, stronghold, the End, the dragon. */
final class BeatGame implements Goal {
    private static final List<Step> STEPS = build();

    private static List<Step> build() {
        List<Step> out = new ArrayList<>(List.of(Steps.WOODEN_PICKAXE, Steps.STONE_PICKAXE, Steps.IRON_PICKAXE,
                Steps.craft("iron_sword", 1)));
        out.addAll(GetIronArmor.armorSteps(true));
        out.add(GetFood.STEP);
        for (EndgameMap.Entry e : EndgameMap.load()) out.add(EndgameMap.toStep(e));
        return List.copyOf(out);
    }

    @Override public String id() { return "beat_game"; }
    @Override public String summary() { return "beat the game"; }
    @Override public List<Step> steps() { return STEPS; }

    @Override
    public double need(Situation s) {
        return s.flag("dragon_dead") ? 0 : 1;
    }
}
