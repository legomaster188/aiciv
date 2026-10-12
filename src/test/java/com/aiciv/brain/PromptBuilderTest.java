package com.aiciv.brain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PromptBuilderTest {
    @Test
    void systemPromptCarriesIdentityAndRules() {
        String s = PromptBuilder.system("Ada", "a cheerful explorer", "build a lighthouse",
                List.of("Bob [AI]", "Steve [human]"), 6, "TOOLS GO HERE");
        assertTrue(s.contains("You are Ada"));
        assertTrue(s.contains("one of 3 people"));
        assertTrue(s.contains("Bob [AI], Steve [human]"));
        assertTrue(s.contains("a cheerful explorer"));
        assertTrue(s.contains("build a lighthouse"));
        assertTrue(s.contains("up to 6"));
        assertTrue(s.contains("TOOLS GO HERE"));
    }

    @Test
    void blankPersonaAndGoalGetDefaults() {
        String s = PromptBuilder.system("Ada", "", "", List.of(), 3, "");
        assertTrue(s.contains("nobody else yet"));
        assertTrue(s.contains("friendly and curious"));
        assertTrue(s.contains("help the settlement survive and grow"));
    }

    @Test
    void userPromptListsNotesEventsAndSnapshot() {
        String u = PromptBuilder.user(List.of(), List.of("[day 1, 06:00] Steve said: \"hi\""), "SNAPSHOT");
        assertTrue(u.contains("Your notes: none"));
        assertTrue(u.contains("- [day 1, 06:00] Steve said: \"hi\""));
        assertTrue(u.contains("SNAPSHOT"));
        assertTrue(u.endsWith("Decide what to do next."));
        assertTrue(PromptBuilder.user(List.of(), List.of(), "S").contains("Recent events: none"));
    }
}
