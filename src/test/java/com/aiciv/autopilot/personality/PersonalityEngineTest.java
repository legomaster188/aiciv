package com.aiciv.autopilot.personality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aiciv.autopilot.AutopilotMemory;
import com.aiciv.autopilot.Decision;
import com.aiciv.autopilot.Tuning;
import com.aiciv.autopilot.goal.Goal;
import com.aiciv.autopilot.goal.StepPlanner;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PersonalityEngineTest {
    private static final Personalities SHIPPED = Personalities.defaults();

    private static Personality shipped(String id) {
        return SHIPPED.get(id).orElseThrow();
    }

    private static Personality custom(Traits t, Map<String, Double> goals) {
        return new Personality("custom", "Custom", "x", t, goals, Map.of());
    }

    private static PersonalityEngine engine(Goal... goals) {
        return new PersonalityEngine(() -> List.of(goals), new StepPlanner());
    }

    private static Decision decide(PersonalityEngine e, PSit s, AutopilotMemory mem, Personality p) {
        return e.decide(s.build(), mem, p, "IDLE", s.tick);
    }

    @Test
    void tuningComesFromTheTraits() {
        Tuning t = new PersonalityEngine(List::of, new StepPlanner()).tuning(shipped("guard"));
        assertEquals(Behaviour.tuning(shipped("guard").traits()), t);
    }

    @Test
    void speedrunnerPrefersIronOverBuilding() {
        PersonalityEngine e = engine(new FakeGoal("get_iron_tools", 1.0), new FakeGoal("build_base", 1.0));
        Decision d = decide(e, new PSit(), new AutopilotMemory(), shipped("speedrunner"));
        assertEquals("get_iron_tools", d.goal());
        assertEquals("s1", d.step());
        assertEquals(1, d.calls().size());
    }

    @Test
    void builderPrefersBuildingOverIron() {
        PersonalityEngine e = engine(new FakeGoal("get_iron_tools", 1.0), new FakeGoal("build_base", 1.0));
        assertEquals("build_base", decide(e, new PSit(), new AutopilotMemory(), shipped("builder")).goal());
    }

    @Test
    void guardDefendsWithAZombieAtFive() {
        PersonalityEngine e = engine(new FakeGoal("get_iron_tools", 1.0),
                new FakeGoal("defend", s -> s.nearestHostile(8).isPresent() ? 1.0 : 0.0, false));
        PSit s = new PSit().hostile("zombie", 5, "N");
        assertEquals("defend", decide(e, s, new AutopilotMemory(), shipped("guard")).goal());
        assertEquals("get_iron_tools", decide(e, new PSit(), new AutopilotMemory(), shipped("guard")).goal());
    }

    @Test
    void thoughtNamesGoalUtilityStepAndWhy() {
        PersonalityEngine e = engine(new FakeGoal("fake_iron", 1.0));
        Decision d = decide(e, new PSit(), new AutopilotMemory(), custom(Traits.NEUTRAL, Map.of("fake_iron", 1.0)));
        assertEquals("goal fake_iron (1.00): step s1: why", d.thought());
        assertEquals("fake_iron", d.goal());
    }

    // ---------------------------------------------------------------- retreat

    @Test
    void cautiousBotRetreatsToHome() {
        PersonalityEngine e = engine(new FakeGoal("get_iron_tools", 1.0));
        Personality cautious = custom(new Traits(-0.5, 0, 0, 0, 0), Map.of("get_iron_tools", 1.0));
        PSit s = new PSit().health(12).hostile("zombie", 6, "N").homeAt(30, 64, 0);
        Decision d = decide(e, s, new AutopilotMemory(), cautious);
        assertEquals("survive", d.goal());
        assertEquals("retreat", d.step());
        assertEquals("goTo", d.calls().get(0).name());
        assertEquals(30, d.calls().get(0).args().get("x").getAsInt());
        assertEquals("survive: retreat from zombie (health 12)", d.thought());
    }

    @Test
    void retreatWithoutHomeRunsTheOtherWay() {
        PersonalityEngine e = engine(new FakeGoal("get_iron_tools", 1.0));
        Personality cautious = custom(new Traits(-0.5, 0, 0, 0, 0), Map.of("get_iron_tools", 1.0));
        Decision d = decide(e, new PSit().health(12).hostile("zombie", 6, "NE"), new AutopilotMemory(), cautious);
        assertEquals("explore", d.calls().get(0).name());
        assertEquals("SW", d.calls().get(0).args().get("direction").getAsString());
        assertEquals(24, d.calls().get(0).args().get("distance").getAsInt());
    }

    @Test
    void retreatNearHomeRunsAway() {
        PersonalityEngine e = engine(new FakeGoal("get_iron_tools", 1.0));
        Personality cautious = custom(new Traits(-0.5, 0, 0, 0, 0), Map.of("get_iron_tools", 1.0));
        Decision d = decide(e, new PSit().health(12).hostile("zombie", 6, "W").homeAt(3, 64, 0), new AutopilotMemory(), cautious);
        assertEquals("explore", d.calls().get(0).name());
        assertEquals("E", d.calls().get(0).args().get("direction").getAsString());
    }

    @Test
    void healthyBotDoesNotRetreat() {
        PersonalityEngine e = engine(new FakeGoal("get_iron_tools", 1.0));
        Personality cautious = custom(new Traits(-0.5, 0, 0, 0, 0), Map.of("get_iron_tools", 1.0));
        assertEquals("get_iron_tools", decide(e, new PSit().health(13).hostile("zombie", 6, "N"), new AutopilotMemory(), cautious).goal());
        assertEquals("get_iron_tools", decide(e, new PSit().health(12).hostile("zombie", 9, "N"), new AutopilotMemory(), cautious).goal());
    }

    @Test
    void braveBotWithIronSwordStandsAndFights() {
        PersonalityEngine e = engine(new FakeGoal("get_iron_tools", 1.0));
        Personality brave = custom(new Traits(0.5, 0, 0, 0, 0), Map.of("get_iron_tools", 1.0));
        // retreatHealth is 8 for brave 0.5: with an iron sword at health 8 it stays, with a wooden one it leaves
        PSit armed = new PSit().health(8).hostile("zombie", 6, "N").inv("iron_sword", 1);
        assertEquals("get_iron_tools", decide(e, armed, new AutopilotMemory(), brave).goal());
        PSit weak = new PSit().health(8).hostile("zombie", 6, "N").inv("wooden_sword", 1);
        assertEquals("survive", decide(e, weak, new AutopilotMemory(), brave).goal());
        PSit nearlyDead = new PSit().health(6).hostile("zombie", 6, "N").inv("iron_sword", 1);
        assertEquals("survive", decide(e, nearlyDead, new AutopilotMemory(), brave).goal());
    }

    @Test
    void braveBotAtTwelveHealthKeepsWorking() {
        PersonalityEngine e = engine(new FakeGoal("get_iron_tools", 1.0));
        Personality brave = custom(new Traits(1.0, 0, 0, 0, 0), Map.of("get_iron_tools", 1.0));
        PSit s = new PSit().health(12).hostile("zombie", 6, "N").inv("iron_sword", 1);
        assertEquals("get_iron_tools", decide(e, s, new AutopilotMemory(), brave).goal());
    }

    // ---------------------------------------------------------------- hunger

    @Test
    void hungryWithoutFoodGetsFoodFirst() {
        PersonalityEngine e = engine(new FakeGoal("get_iron_tools", 1.0), new FakeGoal("get_food", 0.0));
        Personality p = custom(Traits.NEUTRAL, Map.of("get_iron_tools", 1.0, "get_food", 0.9));
        Decision d = decide(e, new PSit().food(6), new AutopilotMemory(), p);
        assertEquals("get_food", d.goal());
        assertEquals("get_iron_tools", decide(e, new PSit().food(6).inv("bread", 1), new AutopilotMemory(), p).goal());
        assertEquals("get_iron_tools", decide(e, new PSit().food(7), new AutopilotMemory(), p).goal());
    }

    // ---------------------------------------------------------------- night

    @Test
    void builderGoesHomeAtNight() {
        PersonalityEngine e = engine(new FakeGoal("build_base", 1.0));
        PSit s = new PSit().night().homeAt(20, 64, 0);
        Decision d = decide(e, s, new AutopilotMemory(), shipped("builder"));
        assertEquals("night", d.goal());
        assertEquals("go_home", d.step());
        assertEquals("goTo", d.calls().get(0).name());
        assertEquals(20, d.calls().get(0).args().get("x").getAsInt());
    }

    @Test
    void speedrunnerIgnoresTheNight() {
        PersonalityEngine e = engine(new FakeGoal("get_iron_tools", 1.0));
        PSit s = new PSit().night().homeAt(20, 64, 0);
        assertEquals("get_iron_tools", decide(e, s, new AutopilotMemory(), shipped("speedrunner")).goal());
    }

    @Test
    void atHomeAtNightItSleepsOnceThenWaits() {
        PersonalityEngine e = engine(new FakeGoal("build_base", 1.0));
        PSit s = new PSit().night().homeAt(2, 64, 0).inv("white_bed", 1);
        Decision d = decide(e, s, new AutopilotMemory(), shipped("builder"));
        assertEquals("night", d.goal());
        assertEquals("sleep", d.step());
        assertEquals("sleep", d.calls().get(0).name());

        PSit slept = new PSit().night().homeAt(2, 64, 0).inv("white_bed", 1).done("night/sleep", 500);
        Decision w = decide(e, slept, new AutopilotMemory(), shipped("builder"));
        assertEquals("night", w.goal());
        assertTrue(w.calls().isEmpty());
        assertEquals("night: staying home", w.thought());

        PSit noSleep = new PSit().night().homeAt(2, 64, 0).inv("white_bed", 1).actions("goto", "explore");
        assertTrue(decide(e, noSleep, new AutopilotMemory(), shipped("builder")).calls().isEmpty());
    }

    @Test
    void withoutABedItStaysHomeQuietly() {
        PersonalityEngine e = engine(new FakeGoal("build_base", 1.0));
        Decision d = decide(e, new PSit().night().homeAt(2, 64, 0), new AutopilotMemory(), shipped("builder"));
        assertEquals("stay", d.step());
        assertTrue(d.calls().isEmpty());
        Decision seen = decide(e, new PSit().night().homeAt(2, 64, 0).block("red_bed", 6), new AutopilotMemory(), shipped("builder"));
        assertEquals("sleep", seen.step(), "a bed in sight is enough");
    }

    @Test
    void aFailedSleepCountsForTheNight() {
        PersonalityEngine e = engine(new FakeGoal("build_base", 1.0));
        AutopilotMemory mem = new AutopilotMemory();
        PSit s = new PSit().night().homeAt(2, 64, 0).inv("white_bed", 1).tick(5000);
        assertEquals("sleep", decide(e, s, mem, shipped("builder")).step());
        mem.stepStarted("night", "sleep", null, 5000);
        mem.outcome("failed: you can only sleep at night or in a thunderstorm", 5001, 5);

        Decision after = decide(e, s.tick(5011), mem, shipped("builder"));
        assertEquals("stay", after.step());
        assertTrue(after.calls().isEmpty());
        assertTrue(decide(e, s.tick(5001 + 11999), mem, shipped("builder")).calls().isEmpty());
        assertEquals("sleep", decide(e, s.tick(5001 + 12001), mem, shipped("builder")).step(), "the next night it tries again");
    }

    @Test
    void goingHomeThatKeepsFailingCoolsDown() {
        PersonalityEngine e = engine(new FakeGoal("build_base", 1.0));
        Personality builder = shipped("builder");
        int retries = Behaviour.tuning(builder.traits()).maxRetries();
        AutopilotMemory mem = new AutopilotMemory();
        PSit s = new PSit().night().homeAt(40, 64, 0).tick(5000);
        for (int i = 0; i < retries; i++) {
            assertEquals("go_home", decide(e, s, mem, builder).step());
            mem.stepStarted("night", "go_home", null, 5000 + i);
            mem.outcome("failed: no path", 5000 + i, retries);
        }
        Decision d = decide(e, s.tick(5010), mem, builder);
        assertEquals("stay", d.step());
        assertTrue(d.calls().isEmpty());
        assertEquals("go_home", decide(e, s.tick(5000 + retries + 600), mem, builder).step(), "tried again after the backoff");
    }

    @Test
    void goingHomeThatFinishesShortOfHomeCoolsDown() {
        PersonalityEngine e = engine(new FakeGoal("build_base", 1.0));
        Personality impatient = custom(new Traits(0, 0, 0, 0.5, -1), Map.of("build_base", 1.0)); // maxRetries 1, home at night
        AutopilotMemory mem = new AutopilotMemory();
        PSit s = new PSit().night().homeAt(40, 64, 0).tick(5000);
        for (int i = 0; i < 3; i++) {
            assertEquals("go_home", decide(e, s, mem, impatient).step());
            mem.stepStarted("night", "go_home", null, 5000 + i);
            mem.outcome("finished: goTo", 5000 + i, 1);
        }
        Decision d = decide(e, s.tick(5010), mem, impatient);
        assertEquals("stay", d.step(), "three walks ending short of home count as a failure");
        assertTrue(d.calls().isEmpty());
    }

    @Test
    void aFailingRetreatCoolsDown() {
        PersonalityEngine e = engine(new FakeGoal("get_iron_tools", 1.0));
        Personality cautious = custom(new Traits(-0.5, 0, 0, 0, 0), Map.of("get_iron_tools", 1.0));
        int retries = Behaviour.tuning(cautious.traits()).maxRetries();
        AutopilotMemory mem = new AutopilotMemory();
        PSit s = new PSit().health(12).hostile("zombie", 6, "N").tick(5000);
        for (int i = 0; i < retries; i++) {
            assertEquals("retreat", decide(e, s, mem, cautious).step());
            mem.stepStarted("survive", "retreat", null, 5000 + i);
            mem.outcome("failed: explore: blocked", 5000 + i, retries);
        }
        assertEquals("get_iron_tools", decide(e, s.tick(5010), mem, cautious).goal());
    }

    @Test
    void aFailingWanderCoolsDown() {
        PersonalityEngine e = engine(FakeGoal.blocked("get_iron_tools", 1.0));
        Personality p = custom(Traits.NEUTRAL, Map.of("get_iron_tools", 1.0));
        AutopilotMemory mem = new AutopilotMemory();
        for (int i = 0; i < 3; i++) {
            Decision d = e.decide(new PSit().tick(5000 + i).build(), mem, p, "IDLE", 5000 + i);
            assertEquals("wander", d.step());
            mem.stepStarted("explore", "wander", null, 5000 + i);
            mem.outcome("failed: explore: stuck", 5000 + i, 3);
        }
        Decision d = e.decide(new PSit().tick(5010).build(), mem, p, "IDLE", 5010);
        assertTrue(d.calls().isEmpty(), d.thought());
    }

    @Test
    void noHomeNoNightRoutine() {
        PersonalityEngine e = engine(new FakeGoal("build_base", 1.0));
        assertEquals("build_base", decide(e, new PSit().night(), new AutopilotMemory(), shipped("builder")).goal());
    }

    @Test
    void hostileNearbyOverridesTheNightRoutine() {
        PersonalityEngine e = engine(new FakeGoal("build_base", 1.0));
        PSit s = new PSit().night().homeAt(20, 64, 0).hostile("zombie", 5, "N");
        assertEquals("build_base", decide(e, s, new AutopilotMemory(), shipped("builder")).goal());
    }

    // ---------------------------------------------------------------- blocked goals, fallback

    @Test
    void blockedGoalIsSkippedForAMinute() {
        PersonalityEngine e = engine(FakeGoal.blocked("get_iron_tools", 1.0), new FakeGoal("explore", 1.0));
        Personality p = custom(Traits.NEUTRAL, Map.of("get_iron_tools", 1.0, "explore", 0.5));
        AutopilotMemory mem = new AutopilotMemory();
        long t = 5000;

        Decision first = e.decide(new PSit().tick(t).build(), mem, p, "IDLE", t);
        assertEquals("explore", first.goal());
        assertTrue(mem.blocked("get_iron_tools", t + 1));
        assertEquals(1, first.cues().size());
        assertEquals("ask_help", first.cues().get(0).category());
        assertEquals("get_iron_tools", first.cues().get(0).vars().get("goal"));
        assertEquals("s1", first.cues().get(0).vars().get("step"));
        assertFalse(first.cues().get(0).priority());

        Decision second = e.decide(new PSit().tick(t + 1).build(), mem, p, "IDLE", t + 1);
        assertEquals("explore", second.goal());
        assertTrue(second.cues().isEmpty(), "the help cue is raised once per block");
        assertEquals("explore", e.decide(new PSit().tick(t + 1199).build(), mem, p, "IDLE", t + 1199).goal());

        // planned again (and blocked again, raising a new cue) at +1200
        Decision third = e.decide(new PSit().tick(t + 1200).build(), mem, p, "IDLE", t + 1200);
        assertEquals("explore", third.goal());
        assertEquals(1, third.cues().size());
        assertTrue(mem.blocked("get_iron_tools", t + 1201));
    }

    @Test
    void blockedOnlyGoalFallsBackToExploring() {
        PersonalityEngine e = engine(FakeGoal.blocked("get_iron_tools", 1.0));
        Personality p = custom(new Traits(1.0, 0, 0, 0, 0), Map.of("get_iron_tools", 1.0));
        Decision d = e.decide(new PSit().tick(2400).build(), new AutopilotMemory(), p, "IDLE", 2400);
        assertEquals("explore", d.goal());
        assertEquals("wander", d.step());
        assertEquals("explore", d.calls().get(0).name());
        assertEquals("E", d.calls().get(0).args().get("direction").getAsString());
        assertEquals(64, d.calls().get(0).args().get("distance").getAsInt());
        assertEquals("N", e.decide(new PSit().tick(0).build(), new AutopilotMemory(), p, "IDLE", 0).calls().get(0)
                .args().get("direction").getAsString());
        assertEquals("W", e.decide(new PSit().tick(7200).build(), new AutopilotMemory(), p, "IDLE", 7200).calls().get(0)
                .args().get("direction").getAsString());
    }

    @Test
    void nothingToDoAndNoExploreWaits() {
        PersonalityEngine e = engine();
        Decision d = decide(e, new PSit().actions("goto"), new AutopilotMemory(), shipped("default"));
        assertEquals("nothing to do", d.thought());
        assertTrue(d.calls().isEmpty());
        assertNull(d.goal());
    }

    @Test
    void achievedGoalsAreSkipped() {
        PersonalityEngine e = engine(new FakeGoal("get_iron_tools", 0.0), new FakeGoal("build_base", 0.5));
        assertEquals("build_base", decide(e, new PSit(), new AutopilotMemory(), shipped("builder")).goal());
    }
}
