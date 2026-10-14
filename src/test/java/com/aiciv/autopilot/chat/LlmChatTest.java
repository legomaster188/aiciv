package com.aiciv.autopilot.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.autopilot.personality.Personality;
import com.aiciv.autopilot.personality.Traits;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LlmChatTest {
    private static final Personality MINER = new Personality("miner", "Miner", "Happiest underground.", Traits.NEUTRAL, Map.of(), Map.of());

    @Test
    void cleanStripsQuotesNamePrefixAndExtraLines() {
        assertEquals(Optional.of("hello there!"), LlmChat.clean("\"Ada: hello there!\"\nsecond", "Ada"));
        assertEquals(Optional.of("hello there!"), LlmChat.clean("\n  \n<Ada> hello there!\nmore", "Ada"));
        assertEquals(Optional.of("hello there!"), LlmChat.clean("*'ada: hello there!'*", "Ada"));
        assertEquals(Optional.of("Adagio is nice"), LlmChat.clean("Adagio is nice", "Ada"));
    }

    @Test
    void cleanNeverLetsAChatLineBecomeACommand() {
        assertEquals(Optional.of("kill"), LlmChat.clean("/kill", "Ada"));
        assertEquals(Optional.of("op me"), LlmChat.clean("//op me", "Ada"));
    }

    @Test
    void cleanCutsLongRepliesAtAWord() {
        String raw = "word ".repeat(60).trim();
        String cut = LlmChat.clean(raw, "Ada").orElseThrow();
        assertTrue(cut.length() <= 120, "length " + cut.length());
        assertTrue(cut.endsWith("word"));
        assertTrue(cut.length() > 100);
        String noSpaces = LlmChat.clean("x".repeat(300), "Ada").orElseThrow();
        assertEquals(120, noSpaces.length());
    }

    @Test
    void cleanOfNothingIsEmpty() {
        assertEquals(Optional.empty(), LlmChat.clean(null, "Ada"));
        assertEquals(Optional.empty(), LlmChat.clean("  \n \n", "Ada"));
        assertEquals(Optional.empty(), LlmChat.clean("\"\"", "Ada"));
        assertEquals(Optional.empty(), LlmChat.clean("Ada:", "Ada"));
    }

    @Test
    void systemPromptNamesTheBotAndPersonality() {
        String s = LlmChat.system("Ada", MINER);
        assertEquals("You are Ada, a player in a Minecraft survival world. Personality: Miner: Happiest underground. "
                + "Write exactly one short in-character chat message (at most 100 characters). "
                + "No quotes, no emojis, no actions in asterisks.", s);
    }

    @Test
    void userPromptHasTheTemplateAndTheRecentChat() {
        String u = LlmChat.user("mention", Map.of("speaker", "Bob", "text", "Ada how are you"), null,
                List.of("Bob: Ada how are you", "Cy: hi"), "Yes Bob?");
        assertEquals("What just happened: Bob spoke to you: \"Ada how are you\".\n"
                + "You: health 20/20, food 20/20, day, doing: nothing.\n"
                + "Recent chat:\n- Bob: Ada how are you\n- Cy: hi\n"
                + "A line you might say: \"Yes Bob?\"\n"
                + "Reply with the chat message only.", u);
        String none = LlmChat.user("brag", Map.of("item", "iron pickaxe"), null, List.of(), "Got an iron pickaxe!");
        assertTrue(none.contains("you just got iron pickaxe"));
        assertTrue(none.contains("Recent chat: none\n"));
    }

    @Test
    void eventTexts() {
        assertEquals("you just arrived", LlmChat.eventText("greet", Map.of()));
        assertEquals("Bob joined the game", LlmChat.eventText("greet", Map.of("other", "Bob")));
        assertEquals("Bob (who runs on gemini-flash) is nearby", LlmChat.eventText("banter", Map.of("other", "Bob", "otherModel", "gemini-flash")));
        assertEquals("you died: fell from a high place", LlmChat.eventText("died", Map.of("message", "fell from a high place")));
        assertEquals("Bob died: drowned", LlmChat.eventText("other_died", Map.of("dead", "Bob", "message", "drowned")));
    }
}
