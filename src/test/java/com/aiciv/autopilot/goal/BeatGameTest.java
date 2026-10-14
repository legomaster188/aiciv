package com.aiciv.autopilot.goal;

import static com.aiciv.autopilot.Sit.sit;
import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.autopilot.AutopilotMemory;
import com.aiciv.autopilot.Calls;
import com.aiciv.autopilot.Sit;
import com.aiciv.autopilot.Tuning;
import org.junit.jupiter.api.Test;

class BeatGameTest {
    private static final Goal BEAT = CoreGoals.list().stream().filter(g -> g.id().equals("beat_game")).findFirst().orElseThrow();

    /** Iron tools, all four armor pieces worn and eight bread: only the endgame is left. */
    private static Sit geared() {
        return sit().inv("iron_pickaxe", 1).inv("iron_sword", 1).inv("bread", 8)
                .wear("iron_helmet").wear("iron_chestplate").wear("iron_leggings").wear("iron_boots");
    }

    private static PlanResult plan(Sit s) {
        return new StepPlanner().next(BEAT, s.build(), new AutopilotMemory(), Tuning.DEFAULT);
    }

    @Test
    void beatGameStopsAtFirstUnavailableStep() {
        PlanResult.Blocked b = assertInstanceOf(PlanResult.Blocked.class, plan(geared()));
        assertEquals("nether_portal", b.step().id());
        assertTrue(b.reason().contains("build_portal"), b.reason());
        assertTrue(b.reason().contains("not available yet"), b.reason());

        PlanResult.Next next = assertInstanceOf(PlanResult.Next.class, plan(geared().withActions("build_portal")));
        assertEquals("nether_portal", next.step().id());
        assertEquals(Calls.of("build_portal"), next.move().calls().get(0));
        assertEquals(1, next.move().calls().size());
    }

    @Test
    void aLaterCandidateIsUsedWhenTheFirstIsMissing() {
        PlanResult.Next next = assertInstanceOf(PlanResult.Next.class, plan(geared().withActions("make_portal")));
        assertEquals("make_portal", next.move().calls().get(0).name());
    }

    @Test
    void inTheNetherWithRodsAndPearlsTheEyesAreNext() {
        PlanResult.Next next = assertInstanceOf(PlanResult.Next.class,
                plan(geared().dim("the_nether").inv("blaze_rod", 6).inv("ender_pearl", 12)));
        assertEquals("ender_eyes", next.step().id());
        assertEquals(Calls.of("craft", "item", "ender_eye", "count", 12), next.move().calls().get(0));
    }

    @Test
    void makeEyesIsPreferredWhenItExists() {
        PlanResult.Next next = assertInstanceOf(PlanResult.Next.class,
                plan(geared().dim("the_nether").inv("blaze_rod", 6).inv("ender_pearl", 12).withActions("make_eyes")));
        assertEquals("make_eyes", next.move().calls().get(0).name());
    }

    @Test
    void armorStepsAreSkippedWhenGearUpIsMissing() {
        Sit bare = sit().inv("iron_pickaxe", 1).inv("iron_sword", 1).inv("bread", 8).without("gear_up");
        PlanResult.Blocked b = assertInstanceOf(PlanResult.Blocked.class, plan(bare));
        assertEquals("nether_portal", b.step().id());
    }

    @Test
    void armorIsGatheredFirstWhenGearUpExists() {
        Sit bare = sit().inv("iron_pickaxe", 1).inv("iron_sword", 1).inv("bread", 8);
        PlanResult.Next n = assertInstanceOf(PlanResult.Next.class, plan(bare));
        assertEquals("iron_chestplate", n.step().id());
    }

    @Test
    void theEndStepArgsCarryThePosition() {
        Sit s = geared().dim("overworld").at(12, 40, -7).inv("ender_eye", 12).withActions("activate_end_portal")
                .block("end_portal_frame", 5);
        PlanResult.Next n = assertInstanceOf(PlanResult.Next.class, plan(s));
        assertEquals("enter_end", n.step().id());
        assertEquals(12, n.move().calls().get(0).args().get("x").getAsInt());
        assertEquals(-7, n.move().calls().get(0).args().get("z").getAsInt());
    }

    @Test
    void killingTheDragonRaisesTheFlagAndEndsTheGoal() {
        Sit s = geared().dim("the_end").inv("ender_eye", 12).withActions("fight_dragon");
        PlanResult.Next n = assertInstanceOf(PlanResult.Next.class, plan(s));
        assertEquals("kill_dragon", n.step().id());
        assertEquals("dragon_dead", n.step().flag());
        assertEquals(1, BEAT.need(s.build()));
        Sit done = geared().dim("the_end").inv("ender_eye", 12).flag("dragon_dead");
        assertInstanceOf(PlanResult.Achieved.class, plan(done));
        assertEquals(0, BEAT.need(done.build()));
    }
}
