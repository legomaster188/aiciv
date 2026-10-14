package com.aiciv.autopilot.goal;

import static com.aiciv.autopilot.Sit.sit;
import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.actions.ActionFactory;
import com.aiciv.actions.ToolCatalog;
import com.aiciv.autopilot.Situation;
import com.aiciv.llm.ToolCall;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.aiciv.modules.ToolModules;
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

    /** The action names Milestone 4 ships (branches aiciv-m4-nether, -end, -dragon and the dimension module). */
    @Test
    void milestoneFourActionNamesAreCandidates() {
        List<String> m4 = List.of("build_portal", "cast_portal", "go_to_nether", "enter_portal", "find_fortress", "get_blaze_rods",
                "hunt_blazes", "make_eyes", "leave_nether", "locate_stronghold", "go_to_stronghold", "fill_end_portal", "go_to_end",
                "fight_dragon");
        Set<String> all = new HashSet<>();
        for (EndgameMap.Entry e : EndgameMap.load()) for (EndgameMap.Candidate c : e.candidates()) all.add(c.action());
        assertTrue(all.containsAll(m4), "missing " + m4.stream().filter(n -> !all.contains(n)).toList());
    }

    @Test
    void anUnknownConditionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new EndgameMap.Candidate("x", new JsonObject(), "!NOT_A_CONDITION", List.of()));
    }

    @Test
    void coordinatesPlaceholdersBecomeNumbers() {
        Situation s = sit().at(12, 40, -7).build();
        EndgameMap.Entry enterEnd = EndgameMap.load().stream().filter(e -> e.id().equals("enter_end")).findFirst().orElseThrow();
        EndgameMap.Candidate activate = enterEnd.candidates().stream().filter(c -> c.action().equals("activate_end_portal")).findFirst().orElseThrow();
        JsonObject args = EndgameMap.fill(activate.args(), s);
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

    /**
     * Every call endgame.json can produce for a registered action must be one the factory builds: right name, argument
     * names the catalog declares, and values of the declared types. The older guessed aliases are not registered and are
     * skipped, but every step must have at least one registered way to be done.
     */
    @Test
    void everyRegisteredCandidateBuildsThroughTheFactory() {
        Situation s = sit().at(12, 40, -7).build();
        JsonObject declared = new JsonObject(); // normalised name -> declared properties
        for (JsonElement t : ToolCatalog.tools()) {
            JsonObject fn = t.getAsJsonObject().getAsJsonObject("function");
            JsonObject params = fn.has("parameters") ? fn.getAsJsonObject("parameters").getAsJsonObject("properties") : new JsonObject();
            declared.add(ToolModules.normalise(fn.get("name").getAsString()), params);
        }
        for (EndgameMap.Entry e : EndgameMap.load()) {
            int registered = 0;
            for (EndgameMap.Candidate c : e.candidates()) {
                String key = ToolModules.normalise(c.action());
                if (!ActionFactory.knownKeys().contains(key)) continue;
                registered++;
                String where = e.id() + "/" + c.action();
                JsonObject args = EndgameMap.fill(c.args(), s);
                assertNotNull(declared.get(key), where + " is not in the tool catalog");
                for (var arg : args.entrySet()) {
                    JsonObject prop = declared.getAsJsonObject(key).getAsJsonObject(arg.getKey());
                    assertNotNull(prop, where + " passes undeclared argument '" + arg.getKey() + "'");
                    String type = prop.get("type").getAsString();
                    var value = arg.getValue().getAsJsonPrimitive();
                    assertTrue(switch (type) {
                        case "integer" -> value.isNumber();
                        case "string" -> value.isString();
                        default -> true;
                    }, where + " argument '" + arg.getKey() + "' should be " + type + " but is " + value);
                }
                ActionFactory.Result r = ActionFactory.create(ToolCall.of(c.action(), args));
                assertInstanceOf(ActionFactory.Result.Ok.class, r, where + " rejected: " + (r instanceof ActionFactory.Result.Invalid i ? i.reason() : r));
            }
            assertTrue(registered > 0, e.id() + " has no candidate the build registers");
        }
    }
}
