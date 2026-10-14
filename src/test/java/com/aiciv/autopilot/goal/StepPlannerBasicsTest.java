package com.aiciv.autopilot.goal;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.autopilot.AutopilotMemory;
import com.aiciv.autopilot.Calls;
import com.aiciv.autopilot.Situation;
import com.aiciv.autopilot.Tuning;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class StepPlannerBasicsTest {
    private static Situation situation(String... actions) {
        return new Situation("Ada", "overworld", 0, 64, 0, 20, 20, false, 0, Map.of(), Set.of(), null, 30,
                List.of(), List.of(), List.of(), List.of(), List.of(), Set.of(), List.of(), Set.of(actions), null, false,
                Set.of(), Map.of(), List.of(), Tuning.DEFAULT, null);
    }

    private static Step step(String id, boolean done, Set<String> needs, boolean optional, Function<Situation, Move> next) {
        Predicate<Situation> isDone = s -> done;
        return new Step() {
            @Override public String id() { return id; }
            @Override public boolean done(Situation s) { return isDone.test(s); }
            @Override public Set<String> needs() { return needs; }
            @Override public boolean optional() { return optional; }
            @Override public Move next(Situation s) { return next.apply(s); }
        };
    }

    private static Goal goal(Step... steps) {
        return new Goal() {
            @Override public String id() { return "test_goal"; }
            @Override public String summary() { return "a test goal"; }
            @Override public List<Step> steps() { return List.of(steps); }
        };
    }

    private static final Function<Situation, Move> MINE = s -> Move.act("mine logs", Calls.of("mine", "block", "log", "count", 3));

    private static PlanResult plan(Goal g, Situation s) {
        return new StepPlanner().next(g, s, new AutopilotMemory(), Tuning.DEFAULT);
    }

    @Test
    void doneStepsAreSkipped() {
        Goal g = goal(step("a", true, Set.of(), false, MINE), step("b", false, Set.of("mine"), false, MINE));
        PlanResult r = plan(g, situation("mine"));
        PlanResult.Next next = assertInstanceOf(PlanResult.Next.class, r);
        assertEquals("b", next.step().id());
        assertEquals("mine", next.move().calls().get(0).name());
    }

    @Test
    void aMissingActionBlocksARequiredStep() {
        Goal g = goal(step("portal", false, Set.of("light_portal", "mine"), false, MINE));
        PlanResult.Blocked b = assertInstanceOf(PlanResult.Blocked.class, plan(g, situation("mine")));
        assertEquals("needs light_portal (not available yet)", b.reason());
        assertEquals("portal", b.step().id());
    }

    @Test
    void aMissingActionSkipsAnOptionalStep() {
        Goal g = goal(step("armor", false, Set.of("gear_up"), true, MINE), step("logs", false, Set.of("mine"), false, MINE));
        PlanResult.Next next = assertInstanceOf(PlanResult.Next.class, plan(g, situation("mine")));
        assertEquals("logs", next.step().id());
    }

    @Test
    void aBlockedMoveBlocksTheGoalWithItsReason() {
        Goal g = goal(step("iron", false, Set.of(), false, s -> Move.blocked("needs a stone pickaxe for iron")));
        PlanResult.Blocked b = assertInstanceOf(PlanResult.Blocked.class, plan(g, situation()));
        assertEquals("needs a stone pickaxe for iron", b.reason());
    }

    @Test
    void noStepLeftMeansAchieved() {
        Goal g = goal(step("a", true, Set.of(), false, MINE));
        assertInstanceOf(PlanResult.Achieved.class, plan(g, situation()));
        assertTrue(g.achieved(situation()));
        assertEquals(0, g.need(situation()));
    }

    @Test
    void movesCarryKindAndCues() {
        Move m = Move.gather("need logs", Calls.of("mine", "block", "log")).withCues(
                new com.aiciv.autopilot.ChatCue("ask_help", Map.of(), false));
        assertEquals(Move.Kind.GATHER, m.kind());
        assertEquals(1, m.cues().size());
        assertEquals(Move.Kind.BLOCKED, Move.blocked("x").kind());
        assertTrue(Move.blocked("x").calls().isEmpty());
    }
}
