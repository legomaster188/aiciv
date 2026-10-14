package com.aiciv.autopilot.personality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aiciv.autopilot.AutopilotMemory;
import com.aiciv.autopilot.Tuning;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoalChooserTest {
    private static Personality weights(Map<String, Double> goals) {
        return new Personality("t", "T", "t", Traits.NEUTRAL, goals, Map.of());
    }

    private static List<GoalChooser.Scored> rank(List<com.aiciv.autopilot.goal.Goal> goals, Personality p, AutopilotMemory mem, Tuning t) {
        return GoalChooser.rank(goals, p, new PSit().build(), mem, t, 1000);
    }

    private static List<String> ids(List<GoalChooser.Scored> r) {
        return r.stream().map(x -> x.goal().id()).toList();
    }

    @Test
    void weightTimesNeedOrders() {
        Personality p = weights(Map.of("get_wood_tools", 0.9, "get_food", 0.5, "farm", 1.0));
        var r = rank(List.of(new FakeGoal("get_wood_tools", 1.0), new FakeGoal("get_food", 1.0), new FakeGoal("farm", 0.5)),
                p, new AutopilotMemory(), Tuning.DEFAULT);
        assertEquals(List.of("get_wood_tools", "get_food", "farm"), ids(r));
        assertEquals(0.9, r.get(0).utility(), 1e-9);
        assertEquals(0.5, r.get(1).utility(), 1e-9);
        assertEquals(0.5, r.get(2).utility(), 1e-9);
    }

    @Test
    void lowUtilityIsDropped() {
        Personality p = weights(Map.of("farm", 1.0, "explore", 0.04, "social", 1.0));
        var r = rank(List.of(new FakeGoal("farm", 0.04), new FakeGoal("explore", 1.0), new FakeGoal("social", 0.05)),
                p, new AutopilotMemory(), Tuning.DEFAULT);
        assertEquals(List.of("social"), ids(r));
    }

    @Test
    void missingWeightIsZero() {
        var r = rank(List.of(new FakeGoal("farm", 1.0)), weights(Map.of()), new AutopilotMemory(), Tuning.DEFAULT);
        assertTrue(r.isEmpty());
    }

    @Test
    void hysteresisKeepsTheCurrentGoal() {
        Personality p = weights(Map.of("farm", 0.85, "explore", 0.9));
        AutopilotMemory mem = new AutopilotMemory();
        mem.stepStarted("farm", "s1", null, 900);
        Tuning patient = Behaviour.tuning(new Traits(0, 0, 0, 0, 1));
        var r = rank(List.of(new FakeGoal("farm", 1.0), new FakeGoal("explore", 1.0)), p, mem, patient);
        assertEquals(List.of("farm", "explore"), ids(r));
        assertEquals(0.85 + 0.30, r.get(0).utility(), 1e-9);

        // an impatient bot (hysteresis 0) switches
        Tuning impatient = Behaviour.tuning(new Traits(0, 0, 0, 0, -1));
        assertEquals(List.of("explore", "farm"), ids(rank(List.of(new FakeGoal("farm", 1.0), new FakeGoal("explore", 1.0)), p, mem, impatient)));
    }

    @Test
    void noHysteresisBonusWithoutNeed() {
        Personality p = weights(Map.of("farm", 0.85));
        AutopilotMemory mem = new AutopilotMemory();
        mem.stepStarted("farm", "s1", null, 900);
        assertTrue(rank(List.of(new FakeGoal("farm", 0.0)), p, mem, Tuning.DEFAULT).isEmpty());
    }

    @Test
    void blockedGoalIsExcluded() {
        Personality p = weights(Map.of("farm", 1.0, "explore", 0.5));
        AutopilotMemory mem = new AutopilotMemory();
        mem.block("farm", 2200);
        var r = rank(List.of(new FakeGoal("farm", 1.0), new FakeGoal("explore", 1.0)), p, mem, Tuning.DEFAULT);
        assertEquals(List.of("explore"), ids(r));
        assertEquals(List.of("farm", "explore"), ids(GoalChooser.rank(List.of(new FakeGoal("farm", 1.0), new FakeGoal("explore", 1.0)),
                p, new PSit().build(), mem, Tuning.DEFAULT, 2200)));
    }

    @Test
    void tiesFollowLibraryOrder() {
        Personality p = weights(Map.of("farm", 0.5, "explore", 0.5, "get_food", 0.5));
        var r = rank(List.of(new FakeGoal("get_food", 1.0), new FakeGoal("farm", 1.0), new FakeGoal("explore", 1.0)),
                p, new AutopilotMemory(), Tuning.DEFAULT);
        assertEquals(List.of("get_food", "farm", "explore"), ids(r));
    }

    @Test
    void pinnedGoalWins() {
        Personality p = weights(Map.of("get_food", 1.0, "farm", 0.1));
        var r = GoalChooser.rank(List.of(new FakeGoal("get_food", 1.0), new FakeGoal("farm", 1.0)), p,
                new PSit().pin("farm").build(), new AutopilotMemory(), Tuning.DEFAULT, 1000);
        assertEquals(List.of("farm", "get_food"), ids(r));
        assertEquals(1.1, r.get(0).utility(), 1e-9);
    }

    @Test
    void socialNeedsHelpfulness() {
        Personality p = weights(Map.of("social", 1.0, "farm", 0.2));
        Tuning loner = Behaviour.tuning(new Traits(0, 0, -1, 0, 0));
        var r = rank(List.of(new FakeGoal("social", 1.0), new FakeGoal("farm", 1.0)), p, new AutopilotMemory(), loner);
        assertEquals(List.of("farm"), ids(r));
    }

    @Test
    void storeItemsNeedsTidiness() {
        Personality p = weights(Map.of("store_items", 1.0));
        Tuning messy = Behaviour.tuning(new Traits(0, 0, 0, -1, 0));
        assertTrue(rank(List.of(new FakeGoal("store_items", 1.0)), p, new AutopilotMemory(), messy).isEmpty());
        Tuning tidy = Behaviour.tuning(new Traits(0, 0, 0, 0.5, 0));
        assertEquals(1, rank(List.of(new FakeGoal("store_items", 1.0)), p, new AutopilotMemory(), tidy).size());
    }
}
