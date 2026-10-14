package com.aiciv.autopilot.goal;

import static com.aiciv.autopilot.Sit.sit;
import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.actions.ActionFactory;
import com.aiciv.autopilot.AutopilotMemory;
import com.aiciv.autopilot.Calls;
import com.aiciv.autopilot.Sit;
import com.aiciv.autopilot.Tuning;
import org.junit.jupiter.api.Test;

class BeatGameTest {
    private static final Goal BEAT = CoreGoals.list().stream().filter(g -> g.id().equals("beat_game")).findFirst().orElseThrow();

    /**
     * What the build could do before Milestone 4: the core actions and gear_up, none of the endgame tools. The tests add
     * the endgame actions they mean to have, so they do not depend on what is registered today.
     */
    private static Sit base() {
        return sit().actions(ActionFactory.coreKeys().toArray(String[]::new)).withActions("gear_up");
    }

    /** Iron tools, all four armor pieces worn and eight bread: only the endgame is left. */
    private static Sit geared() {
        return base().inv("iron_pickaxe", 1).inv("iron_sword", 1).inv("bread", 8)
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

        PlanResult.Next next = assertInstanceOf(PlanResult.Next.class, plan(geared().inv("obsidian", 10).withActions("build_portal")));
        assertEquals("nether_portal", next.step().id());
        assertEquals(Calls.of("build_portal"), next.move().calls().get(0));
        assertEquals(1, next.move().calls().size());
    }

    @Test
    void withoutObsidianThePortalIsCastElseObsidianIsGathered() {
        Sit both = geared().withActions("build_portal", "cast_portal");
        assertEquals(Calls.of("cast_portal"), assertInstanceOf(PlanResult.Next.class, plan(both)).move().calls().get(0));
        assertEquals(Calls.of("mine", "block", "obsidian", "count", 10),
                assertInstanceOf(PlanResult.Next.class, plan(geared().inv("obsidian", 4).withActions("build_portal", "cast_portal")))
                        .move().calls().get(0), "some obsidian: gather the rest");
        assertEquals("mine", assertInstanceOf(PlanResult.Next.class, plan(geared().withActions("build_portal"))).move().calls().get(0).name(),
                "no way to cast one: gather obsidian");
    }

    @Test
    void theStrongholdIsLocatedThenVisitedNeverFoundByThrowingEyeAfterEye() {
        Sit eyes = geared().inv("ender_eye", 12).withActions("locate_stronghold", "go_to_stronghold", "throw_eye");
        PlanResult.Next locate = assertInstanceOf(PlanResult.Next.class, plan(eyes));
        assertEquals("find_stronghold", locate.step().id());
        assertEquals("locate_stronghold", locate.move().calls().get(0).name());
        assertEquals("go_to_stronghold", assertInstanceOf(PlanResult.Next.class, plan(eyes.tag("stronghold_known"))).move().calls().get(0).name());

        Sit onlyEyes = geared().inv("ender_eye", 12).withActions("throw_eye").tag("stronghold_known");
        PlanResult.Blocked b = assertInstanceOf(PlanResult.Blocked.class, plan(onlyEyes));
        assertEquals("find_stronghold", b.step().id(), "a known stronghold is not found by throwing more eyes");
        assertTrue(b.reason().contains("go_to_stronghold"), b.reason());
        assertInstanceOf(PlanResult.Next.class, plan(geared().inv("ender_eye", 12).tag("portal_room_known").withActions("go_to_end")));
    }

    @Test
    void milestoneFourNamesDriveTheWholeRun() {
        Sit nether = geared().dim("the_nether").withActions("go_to_nether", "find_fortress", "get_blaze_rods", "leave_nether");
        assertEquals("find_fortress", assertInstanceOf(PlanResult.Next.class, plan(nether)).move().calls().get(0).name());
        assertEquals(Calls.of("get_blaze_rods", "count", 6),
                assertInstanceOf(PlanResult.Next.class, plan(nether.tag("fortress_known"))).move().calls().get(0));
        Sit back = geared().dim("the_nether").inv("ender_eye", 12).withActions("leave_nether");
        assertEquals("leave_nether", assertInstanceOf(PlanResult.Next.class, plan(back)).move().calls().get(0).name());
        Sit portal = geared().inv("obsidian", 10).block("nether_portal", 5).withActions("go_to_nether", "build_portal");
        assertEquals("go_to_nether", assertInstanceOf(PlanResult.Next.class, plan(portal)).move().calls().get(0).name());
        Sit room = geared().inv("ender_eye", 12).block("end_portal_frame", 5).withActions("go_to_end", "fill_end_portal");
        assertEquals("go_to_end", assertInstanceOf(PlanResult.Next.class, plan(room)).move().calls().get(0).name());
    }

    @Test
    void aLaterCandidateIsUsedWhenTheFirstIsMissing() {
        PlanResult.Next next = assertInstanceOf(PlanResult.Next.class, plan(geared().withActions("make_portal")));
        assertEquals("make_portal", next.move().calls().get(0).name());

        Sit portal = geared().block("nether_portal", 5);
        PlanResult.Next viaEnterPortal = assertInstanceOf(PlanResult.Next.class, plan(portal.withActions("enter_portal")));
        assertEquals("enter_nether", viaEnterPortal.step().id());
        assertEquals(Calls.of("enter_portal", "kind", "nether"), viaEnterPortal.move().calls().get(0), "go_to_nether is missing");
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
        Sit bare = base().inv("iron_pickaxe", 1).inv("iron_sword", 1).inv("bread", 8).without("gear_up");
        PlanResult.Blocked b = assertInstanceOf(PlanResult.Blocked.class, plan(bare));
        assertEquals("nether_portal", b.step().id());
    }

    @Test
    void armorIsGatheredFirstWhenGearUpExists() {
        Sit bare = base().inv("iron_pickaxe", 1).inv("iron_sword", 1).inv("bread", 8);
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
