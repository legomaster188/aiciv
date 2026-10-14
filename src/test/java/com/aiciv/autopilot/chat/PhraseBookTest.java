package com.aiciv.autopilot.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.autopilot.personality.Personality;
import com.aiciv.autopilot.personality.Traits;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PhraseBookTest {
    private static final List<String> SHIPPED = List.of("default", "miner", "builder", "farmer", "trader", "guard", "explorer",
            "speedrunner", "hermit", "chatty");

    private static Personality person(String id, Map<String, List<String>> phrases) {
        return new Personality(id, id, "test", Traits.NEUTRAL, Map.of(), phrases);
    }

    @Test
    void everyCategoryHasAtLeastThreeBaseLines() {
        PhraseBook base = PhraseBook.forPersonality(person("nobody_special", Map.of()));
        for (String category : PhraseBook.CATEGORIES.keySet()) {
            assertTrue(base.lines(category).size() >= 3, category + " has " + base.lines(category));
        }
    }

    @Test
    void laterLevelsReplaceEarlierOnes() {
        PhraseBook base = PhraseBook.forPersonality(person("nobody_special", Map.of()));
        PhraseBook miner = PhraseBook.forPersonality(person("miner", Map.of()));
        assertNotEquals(base.lines("banter"), miner.lines("banter"), "phrases.json[miner] replaces base");
        assertEquals(base.lines("thanks"), miner.lines("thanks"), "a category miner does not define stays from base");

        PhraseBook own = PhraseBook.forPersonality(person("miner", Map.of("banter", List.of("Mine, all mine."))));
        assertEquals(List.of("Mine, all mine."), own.lines("banter"));
        assertEquals(miner.lines("greet"), own.lines("greet"));
        assertEquals(List.of(), own.lines("not_a_category"));
    }

    @Test
    void everyShippedPersonalityHasItsOwnCoreLines() {
        Set<String> ids = PhraseBook.shippedIds();
        for (String id : SHIPPED) {
            assertTrue(ids.contains(id), "phrases.json has no section for " + id);
            for (String category : List.of("greet", "brag", "banter", "mention")) {
                assertTrue(PhraseBook.ownLines(id, category).size() >= 2, id + "/" + category);
            }
            // a spawn greeting has no other player to name
            assertTrue(PhraseBook.ownLines(id, "greet").stream().anyMatch(l -> !Templates.placeholders(l).contains("other")),
                    id + " has no greeting without {other}");
        }
        assertTrue(PhraseBook.forPersonality(person("hermit", Map.of())).lines("mention").contains("What, {speaker}."));
        assertTrue(PhraseBook.forPersonality(person("chatty", Map.of())).lines("mention").contains("Yes {speaker}? I'm all ears!"));
    }

    @Test
    void helpRequestsHaveALineWithoutAnItem() {
        // the cue only knows an item when the step is an item id
        PhraseBook base = PhraseBook.forPersonality(person("nobody_special", Map.of()));
        assertTrue(base.lines("ask_help").stream().anyMatch(l -> !Templates.placeholders(l).contains("item")));
    }

    @Test
    void noShippedTemplateHasUnknownPlaceholders() {
        Map<String, String> sixteen = new HashMap<>();
        for (String name : Templates.ALLOWED) sixteen.put(name, "abcdefghijklmnop");
        Set<String> ids = new HashSet<>(PhraseBook.shippedIds());
        ids.add("nobody_special"); // base only
        for (String id : ids) {
            PhraseBook book = PhraseBook.forPersonality(person(id, Map.of()));
            for (String category : PhraseBook.CATEGORIES.keySet()) {
                Set<String> supplied = new HashSet<>(PhraseBook.CATEGORIES.get(category));
                supplied.add("me");
                supplied.add("personality");
                for (String line : book.lines(category)) {
                    String where = id + "/" + category + ": " + line;
                    assertTrue(Templates.ALLOWED.containsAll(Templates.placeholders(line)), "unknown placeholder in " + where);
                    assertTrue(supplied.containsAll(Templates.placeholders(line)), "a " + category + " cue never supplies the placeholder in " + where);
                    String rendered = Templates.render(line, sixteen).orElseThrow(() -> new AssertionError("does not render: " + where));
                    assertTrue(rendered.length() <= 100, rendered.length() + " characters in " + where);
                    assertFalse(rendered.contains("{"), where);
                }
            }
        }
    }

    @Test
    void shippedFileHasNoUnknownCategories() {
        for (String id : PhraseBook.shippedIds()) {
            PhraseBook book = PhraseBook.forPersonality(person(id, Map.of()));
            for (String category : PhraseBook.CATEGORIES.keySet()) assertFalse(book.lines(category).isEmpty(), id + "/" + category);
        }
    }

    /** Lines bots say to each other must not be taken as requests; the open ask for help is the one meant as one. */
    @Test
    void onlyTheOpenAskForHelpParsesAsARequest() {
        Map<String, String> samples = new HashMap<>(Map.of("me", "Ada", "other", "Rex", "otherModel", "gpt", "speaker", "Rex",
                "item", "bread", "count", "3", "goal", "get iron tools", "step", "iron_pickaxe", "attacker", "zombie", "dead", "Rex"));
        samples.put("message", "Rex fell from a high place");
        samples.put("personality", "Miner");
        Set<String> parsed = new java.util.TreeSet<>();
        for (Personality p : com.aiciv.autopilot.personality.Personalities.defaults().all()) {
            PhraseBook book = PhraseBook.forPersonality(p);
            for (String category : PhraseBook.CATEGORIES.keySet()) {
                for (String line : book.lines(category)) {
                    String text = Templates.render(line, samples).orElse(null);
                    if (text == null) continue;
                    com.aiciv.autopilot.Requests.parse(text, "Rex", text.contains("Rex"), 0)
                            .ifPresent(r -> parsed.add(category + ": " + line + " -> " + r.kind() + " " + r.item()));
                }
            }
        }
        assertEquals(Set.of("ask_help: Anyone got a spare {item}? -> GIVE bread"), parsed);
    }
}
