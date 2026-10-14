package com.aiciv.autopilot.personality;

import com.aiciv.autopilot.Calls;
import com.aiciv.autopilot.Situation;
import com.aiciv.autopilot.goal.Goal;
import com.aiciv.autopilot.goal.Move;
import com.aiciv.autopilot.goal.Step;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** A goal with a fixed (or computed) need and one step "s1" that either acts or is blocked. */
final class FakeGoal implements Goal {
    private final String id;
    private final ToDoubleFunction<Situation> need;
    private final boolean blocked;

    FakeGoal(String id, double need) {
        this(id, s -> need, false);
    }

    FakeGoal(String id, ToDoubleFunction<Situation> need, boolean blocked) {
        this.id = id;
        this.need = need;
        this.blocked = blocked;
    }

    static FakeGoal blocked(String id, double need) {
        return new FakeGoal(id, s -> need, true);
    }

    @Override public String id() { return id; }

    @Override public String summary() { return "fake " + id; }

    @Override public double need(Situation s) { return need.applyAsDouble(s); }

    @Override public List<Step> steps() {
        return List.of(new Step() {
            @Override public String id() { return "s1"; }
            @Override public boolean done(Situation s) { return false; }
            @Override public Move next(Situation s) {
                return blocked ? Move.blocked("no way") : Move.act("why", Calls.of("mine", "block", id));
            }
        });
    }
}
