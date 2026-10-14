package com.aiciv.autopilot.goal.world;

import com.aiciv.autopilot.Calls;
import com.aiciv.autopilot.Situation;
import com.aiciv.autopilot.goal.Goal;
import com.aiciv.autopilot.goal.Move;
import com.aiciv.autopilot.goal.Step;
import com.aiciv.autopilot.goal.Steps;
import com.aiciv.llm.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Empty the pack into the home chest when it is nearly full. */
public final class StoreItems implements Goal {
    private static final Step DEPOSIT = Steps.of("deposit", s -> s.freeSlots() > s.tuning().depositWhenFree(), s -> {
        List<ToolCall> calls = new ArrayList<>();
        s.home().filter(h -> s.distanceTo(h) > 4).ifPresent(h -> calls.add(Calls.of("goTo", "x", h[0], "y", h[1], "z", h[2])));
        calls.add(Calls.of("deposit", "items", "all_except_tools"));
        return Move.act("put my things in the chest", calls.toArray(new ToolCall[0]));
    }, Set.of("deposit"), false, null);

    private static final List<Step> STEPS = List.of(DEPOSIT);

    @Override public String id() { return "store_items"; }
    @Override public String summary() { return "store my things"; }
    @Override public List<Step> steps() { return STEPS; }

    @Override
    public double need(Situation s) {
        return s.home().isPresent() && s.flag("home_chest") && s.freeSlots() <= s.tuning().depositWhenFree() ? 1 : 0;
    }
}
