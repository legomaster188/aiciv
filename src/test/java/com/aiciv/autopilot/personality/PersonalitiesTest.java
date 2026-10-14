package com.aiciv.autopilot.personality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aiciv.autopilot.GoalIds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersonalitiesTest {
    private static final Set<String> SHIPPED = Set.of("default", "miner", "builder", "farmer", "trader", "guard",
            "explorer", "speedrunner", "hermit", "chatty");

    @TempDir Path dir;

    private Personalities withUserFile(String json) throws IOException {
        Files.writeString(dir.resolve("personalities.json"), json);
        return Personalities.load(dir);
    }

    @Test
    void shippedIdsAreExactlyTheTen() {
        Personalities p = Personalities.defaults();
        assertEquals(SHIPPED, p.ids());
        assertEquals(List.of(), p.problems());
    }

    @Test
    void traitsInRangeAndGoalsKnown() {
        for (Personality p : Personalities.defaults().all()) {
            for (double v : p.traits().asMap().values()) assertTrue(v >= -1 && v <= 1, p.id());
            assertTrue(GoalIds.ALL.containsAll(p.goals().keySet()), p.id() + " " + p.goals().keySet());
            assertEquals(GoalIds.ALL.size(), p.goals().size(), p.id() + " has every goal weight");
        }
    }

    @Test
    void speedrunnerWeights() {
        Personality s = Personalities.defaults().get("speedrunner").orElseThrow();
        assertEquals(1.0, s.goals().get("beat_game"));
        assertEquals(0.0, s.goals().get("farm"));
        assertEquals(0.9, s.goals().get("get_food"));
        assertEquals(0.7, s.traits().brave());
        assertEquals(-0.9, s.traits().patient());
    }

    @Test
    void defaultIsBaseAndNeutral() {
        Personalities all = Personalities.defaults();
        Personality d = all.get("default").orElseThrow();
        assertEquals(Traits.NEUTRAL, d.traits());
        assertEquals(0.9, d.goals().get("get_wood_tools"));
        assertEquals(0.0, d.goals().get("beat_game"));
        assertEquals(d, all.fallback());
    }

    @Test
    void userFileAddsAndMerges() throws IOException {
        Personalities p = withUserFile("""
                {"personalities": {
                  "pirate": {"name": "Pirate", "description": "Arr.", "traits": {"brave": 0.8}, "goals": {"explore": 1}},
                  "miner": {"traits": {"brave": 0.9}, "phrases": {"greet": ["Ahoy"]}}
                }}""");
        assertEquals(List.of(), p.problems());
        assertEquals(11, p.ids().size());
        Personality pirate = p.get("pirate").orElseThrow();
        assertEquals("Pirate", pirate.name());
        assertEquals(0.8, pirate.traits().brave());
        assertEquals(0.0, pirate.traits().greedy());
        assertEquals(1.0, pirate.goals().get("explore"));
        assertEquals(0.9, pirate.goals().get("get_wood_tools"), "a new id inherits base");
        Personality miner = p.get("miner").orElseThrow();
        Personality shipped = Personalities.defaults().get("miner").orElseThrow();
        assertEquals(0.9, miner.traits().brave());
        assertEquals(shipped.traits().greedy(), miner.traits().greedy());
        assertEquals(shipped.traits().patient(), miner.traits().patient());
        assertEquals(shipped.goals(), miner.goals());
        assertEquals(List.of("Ahoy"), miner.phrases().get("greet"));
        assertEquals("Miner", miner.name());
    }

    @Test
    void userBaseMergesIntoShippedBase() throws IOException {
        Personalities p = withUserFile("{\"base\": {\"goals\": {\"explore\": 0.7}}}");
        assertEquals(List.of(), p.problems());
        assertEquals(0.7, p.get("default").orElseThrow().goals().get("explore"));
        assertEquals(0.7, p.get("speedrunner").orElseThrow().goals().get("explore"));
        assertEquals(0.3, p.get("miner").orElseThrow().goals().get("explore"), "its own weight still wins");
        assertEquals(0.9, p.get("default").orElseThrow().goals().get("get_food"));
    }

    @Test
    void badUserFileKeepsDefaults() throws IOException {
        Personalities p = withUserFile("{not json");
        assertEquals(SHIPPED, p.ids());
        assertEquals(1, p.problems().size());
        assertTrue(p.problems().get(0).startsWith("personalities.json:"), p.problems().get(0));
    }

    @Test
    void missingUserFileIsFine() {
        Personalities p = Personalities.load(dir);
        assertEquals(SHIPPED, p.ids());
        assertEquals(List.of(), p.problems());
    }

    @Test
    void unknownGoalIdIsReported() throws IOException {
        Personalities p = withUserFile("{\"personalities\": {\"pirate\": {\"goals\": {\"fly\": 1, \"farm\": 0.5}}}}");
        assertTrue(p.problems().contains("personality 'pirate': unknown goal 'fly'"), p.problems().toString());
        Personality pirate = p.get("pirate").orElseThrow();
        assertFalse(pirate.goals().containsKey("fly"));
        assertEquals(0.5, pirate.goals().get("farm"));
    }

    @Test
    void outOfRangeTraitIsClampedAndReported() throws IOException {
        Personalities p = withUserFile("{\"personalities\": {\"pirate\": {\"traits\": {\"brave\": 3.0, \"cunning\": 0.5}}}}");
        assertTrue(p.problems().contains("personality 'pirate': trait 'brave' is 3.0; clamped to 1.0"), p.problems().toString());
        assertTrue(p.problems().contains("personality 'pirate': unknown trait 'cunning'"), p.problems().toString());
        assertEquals(1.0, p.get("pirate").orElseThrow().traits().brave());
    }

    @Test
    void badIdIsSkipped() throws IOException {
        Personalities p = withUserFile("{\"personalities\": {\"Bad Id\": {\"name\": \"X\"}, \"ok_1\": {\"name\": \"Ok\"}}}");
        assertTrue(p.problems().contains("personality 'Bad Id': id must match [a-z0-9_]{1,24}"), p.problems().toString());
        assertFalse(p.ids().contains("Bad Id"));
        assertTrue(p.ids().contains("ok_1"));
    }

    @Test
    void wrongShapedEntriesAreReportedNotThrown() throws IOException {
        Personalities p = withUserFile("{\"personalities\": {\"pirate\": {\"traits\": {\"brave\": \"very\"}}, \"x\": 5}}");
        assertTrue(p.ids().containsAll(SHIPPED));
        assertFalse(p.problems().isEmpty());
    }

    @Test
    void ofKeepsExactlyTheGivenSet() {
        Personality only = new Personality("nobody", "Nobody", "x", Traits.NEUTRAL, Map.of(), Map.of());
        Personalities p = Personalities.of(List.of(only));
        assertEquals(Set.of("nobody"), p.ids());
        assertEquals("default", p.fallback().id());
    }
}
