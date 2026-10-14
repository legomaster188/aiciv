package com.aiciv.autopilot.goal;

import static com.aiciv.autopilot.Sit.sit;
import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.autopilot.AutopilotMemory;
import com.aiciv.autopilot.Calls;
import com.aiciv.autopilot.Situation;
import com.aiciv.autopilot.Tuning;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StepPlannerTest {
    private final StepPlanner planner = new StepPlanner();

    private static Step step(String id, boolean optional, Move move) {
        return Steps.of(id, s -> false, s -> move, Set.of(), optional, null);
    }

    private static Goal goal(Step... steps) {
        return new Goal() {
            @Override public String id() { return "test_goal"; }
            @Override public String summary() { return "a test goal"; }
            @Override public List<Step> steps() { return List.of(steps); }
        };
    }

    private static final Move GATHER = Move.gather("need logs", Calls.of("mine", "block", "log", "count", 3));

    @Test
    void twoFailuresOnAGatherStepExplore() {
        Goal g = goal(step("logs", false, GATHER));
        AutopilotMemory mem = new AutopilotMemory();
        Situation s = sit().build();
        mem.fail("test_goal", "logs", 0, 3);
        PlanResult.Next first = assertInstanceOf(PlanResult.Next.class, planner.next(g, s, mem, Tuning.DEFAULT));
        assertEquals("mine", first.move().calls().get(0).name());
        mem.fail("test_goal", "logs", 0, 3);
        PlanResult.Next second = assertInstanceOf(PlanResult.Next.class, planner.next(g, s, mem, Tuning.DEFAULT));
        assertEquals(Move.Kind.GATHER, second.move().kind());
        assertEquals("explore", second.move().calls().get(0).name());
        assertEquals(Tuning.DEFAULT.exploreDistance(), second.move().calls().get(0).args().get("distance").getAsInt());
        assertTrue(second.move().why().contains("exploring"), second.move().why());
    }

    @Test
    void anActMoveNeverBecomesAnExplore() {
        Goal g = goal(step("smelt", false, Move.act("smelt 3 raw_iron", Calls.of("smelt", "item", "raw_iron", "count", 3))));
        AutopilotMemory mem = new AutopilotMemory();
        mem.fail("test_goal", "smelt", 0, 5);
        mem.fail("test_goal", "smelt", 0, 5);
        PlanResult.Next n = assertInstanceOf(PlanResult.Next.class, planner.next(g, sit().build(), mem, new Tuning(5, 48, 8, 0.15, false, 16, true, 1.0, 0)));
        assertEquals("smelt", n.move().calls().get(0).name());
    }

    @Test
    void threeFailuresCoolTheStepDownThenItIsTriedAgain() {
        Goal g = goal(step("logs", false, GATHER));
        AutopilotMemory mem = new AutopilotMemory();
        for (int i = 0; i < 3; i++) mem.fail("test_goal", "logs", 1000, 3);
        PlanResult.Blocked b = assertInstanceOf(PlanResult.Blocked.class, planner.next(g, sit().tick(1000).build(), mem, Tuning.DEFAULT));
        assertTrue(b.reason().contains("failed 3 times; retry in 30 s"), b.reason());
        long retryAt = mem.retryAt("test_goal", "logs");
        assertEquals(1600, retryAt);
        assertInstanceOf(PlanResult.Blocked.class, planner.next(g, sit().tick(retryAt - 1).build(), mem, Tuning.DEFAULT));
        assertInstanceOf(PlanResult.Next.class, planner.next(g, sit().tick(retryAt).build(), mem, Tuning.DEFAULT));
    }

    @Test
    void aCoolingOptionalStepIsSkipped() {
        Goal g = goal(step("armor", true, GATHER), step("food", false, Move.act("eat", Calls.of("eat"))));
        AutopilotMemory mem = new AutopilotMemory();
        for (int i = 0; i < 3; i++) mem.fail("test_goal", "armor", 1000, 3);
        PlanResult.Next n = assertInstanceOf(PlanResult.Next.class, planner.next(g, sit().tick(1000).build(), mem, Tuning.DEFAULT));
        assertEquals("food", n.step().id());
    }

    @Test
    void stepThatFinishesWithoutProgressBacksOff() {
        Goal g = goal(step("collect", false, GATHER));
        AutopilotMemory mem = new AutopilotMemory();
        long tick = 1000;
        // the step "finishes" every time but its done-check stays false (the drops were out of reach)
        for (int i = 0; i < 9; i++) {
            assertInstanceOf(PlanResult.Next.class, planner.next(g, sit().tick(tick).build(), mem, Tuning.DEFAULT), "finish " + i);
            mem.stepStarted("test_goal", "collect", null, tick);
            mem.outcome("finished: mined 3 log", tick + 10, 3);
            tick += 20;
        }
        PlanResult r = planner.next(g, sit().tick(tick).build(), mem, Tuning.DEFAULT);
        PlanResult.Blocked b = assertInstanceOf(PlanResult.Blocked.class, r);
        assertTrue(b.reason().contains("failed 3 times; retry in"), b.reason());
        assertTrue(mem.coolingDown("test_goal", "collect", tick));
    }

    @Test
    void threeFinishesWithoutProgressCountAsOneFailure() {
        Goal g = goal(step("collect", false, GATHER));
        AutopilotMemory mem = new AutopilotMemory();
        for (int i = 0; i < 3; i++) {
            mem.stepStarted("test_goal", "collect", null, 100);
            mem.outcome("finished", 100, 3);
        }
        assertEquals(0, mem.failures("test_goal", "collect"));
        planner.next(g, sit().tick(200).build(), mem, Tuning.DEFAULT);
        assertEquals(1, mem.failures("test_goal", "collect"));
        assertEquals(0, mem.finishesWithoutDone("test_goal", "collect"));
    }

    @Test
    void invalidMappedCallCountsAsFailure() {
        Goal g = goal(step("nether_portal", false, Move.act("portal", Calls.of("build_portal"))));
        AutopilotMemory mem = new AutopilotMemory();
        mem.stepStarted("test_goal", "nether_portal", null, 100);
        mem.outcome("failed: invalid: unknown action 'build_portal'. Available: say, goTo", 120, 3);
        assertEquals(1, mem.failures("test_goal", "nether_portal"));
        assertInstanceOf(PlanResult.Next.class, planner.next(g, sit().tick(130).build(), mem, Tuning.DEFAULT));
        for (int i = 0; i < 2; i++) {
            mem.stepStarted("test_goal", "nether_portal", null, 140);
            mem.outcome("failed: invalid: unknown action 'build_portal'", 150, 3);
        }
        assertEquals(3, mem.failures("test_goal", "nether_portal"));
        assertInstanceOf(PlanResult.Blocked.class, planner.next(g, sit().tick(160).build(), mem, Tuning.DEFAULT));
    }
}
