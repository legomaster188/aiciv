package com.aiciv.autopilot.goal;

import static com.aiciv.autopilot.Sit.sit;
import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.autopilot.Situation;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EndgameMapTest {
    @Test
    void theResourceLoadsInOrder() {
        List<EndgameMap.Entry> entries = EndgameMap.load();
        assertEquals(List.of("nether_portal", "enter_nether", "find_fortress", "blaze_rods", "ender_pearls", "ender_eyes",
                "return_overworld", "find_stronghold", "enter_end", "kill_dragon"), entries.stream().map(EndgameMap.Entry::id).toList());
        assertSame(entries, EndgameMap.load());
    }

    @Test
    void idsAreUniqueAndEveryCandidateHasObjectArgs() {
        Set<String> ids = new HashSet<>();
        for (EndgameMap.Entry e : EndgameMap.load()) {
            assertTrue(ids.add(e.id()), "duplicate " + e.id());
            assertFalse(e.candidates().isEmpty(), e.id());
            for (EndgameMap.Candidate c : e.candidates()) {
                assertNotNull(c.args(), e.id() + "/" + c.action());
                assertFalse(c.action().isBlank());
            }
        }
    }

    @Test
    void theOriginalCandidateNamesAreStillThere() {
        List<String> required = List.of("build_portal", "nether_portal", "make_portal", "enter_portal", "use_portal", "find_fortress",
                "locate_fortress", "hunt_blazes", "barter", "hunt_endermen", "find_stronghold", "locate_stronghold", "throw_eye",
                "activate_end_portal", "fill_end_portal", "fight_dragon");
        Set<String> all = new HashSet<>();
        for (EndgameMap.Entry e : EndgameMap.load()) for (EndgameMap.Candidate c : e.candidates()) all.add(c.action());
        assertTrue(all.containsAll(required), "missing " + required.stream().filter(r -> !all.contains(r)).toList());
    }

    @Test
    void coordinatesPlaceholdersBecomeNumbers() {
        Situation s = sit().at(12, 40, -7).build();
        EndgameMap.Entry enterEnd = EndgameMap.load().stream().filter(e -> e.id().equals("enter_end")).findFirst().orElseThrow();
        JsonObject args = EndgameMap.fill(enterEnd.candidates().get(0).args(), s);
        assertEquals(12, args.get("x").getAsInt());
        assertTrue(args.get("x").getAsJsonPrimitive().isNumber());
        assertEquals(40, args.get("y").getAsInt());
        assertEquals(-7, args.get("z").getAsInt());
    }

    @Test
    void conditionsFollowTheTable() {
        Situation overworld = sit().build();
        assertFalse(EndgameCondition.PORTAL_READY.test(overworld));
        assertTrue(EndgameCondition.PORTAL_READY.test(sit().block("nether_portal", 10).build()));
        assertTrue(EndgameCondition.PORTAL_READY.test(sit().dim("the_nether").build()));
        assertTrue(EndgameCondition.PORTAL_READY.test(sit().inv("blaze_rod", 6).inv("ender_pearl", 12).build()));
        assertFalse(EndgameCondition.RODS_READY.test(sit().inv("blaze_rod", 5).build()));
        assertTrue(EndgameCondition.RODS_READY.test(sit().inv("blaze_rod", 3).inv("blaze_powder", 6).build()));
        assertTrue(EndgameCondition.PEARLS_READY.test(sit().inv("ender_eye", 12).build()));
        assertTrue(EndgameCondition.EYES_READY.test(sit().dim("the_end").build()));
        assertTrue(EndgameCondition.FORTRESS_FOUND.test(sit().block("nether_bricks", 20).build()));
        assertFalse(EndgameCondition.FORTRESS_FOUND.test(sit().block("nether_bricks", 30).build()));
        assertTrue(EndgameCondition.STRONGHOLD_FOUND.test(sit().block("end_portal_frame", 24).build()));
        assertTrue(EndgameCondition.IN_OVERWORLD_OR_END.test(overworld));
        assertFalse(EndgameCondition.IN_OVERWORLD_OR_END.test(sit().dim("the_nether").build()));
        assertTrue(EndgameCondition.DRAGON_DEAD.test(sit().flag("dragon_dead").build()));
        assertFalse(EndgameCondition.IN_END.test(overworld));
    }

    @Test
    void aStepWithNoKnownCandidateIsBlockedWithAReadableReason() {
        EndgameMap.Entry fight = EndgameMap.load().stream().filter(e -> e.id().equals("kill_dragon")).findFirst().orElseThrow();
        Move m = EndgameMap.toStep(fight).next(sit().actions("say").build());
        assertEquals(Move.Kind.BLOCKED, m.kind());
        assertEquals("needs one of fight_dragon, attack (not available yet)", m.why());
    }
}
