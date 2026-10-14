package com.aiciv.autopilot.goal;

import java.util.List;

/** The survival goals (tools, armor, food, diamonds, the endgame), in library order. */
public final class CoreGoals {
    private CoreGoals() {}

    private static final List<Goal> GOALS = List.of(new GetWoodTools(), new GetStoneTools(), new GetIronTools(),
            new GetIronArmor(), new GetFood(), new MineDiamonds(), new BeatGame());

    public static List<Goal> list() {
        return GOALS;
    }
}
