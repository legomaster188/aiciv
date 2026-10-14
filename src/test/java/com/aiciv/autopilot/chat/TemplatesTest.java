package com.aiciv.autopilot.chat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TemplatesTest {
    @Test
    void fillsPlaceholders() {
        assertEquals(Optional.of("Hi Bob"), Templates.render("Hi {other}", Map.of("other", "Bob")));
        assertEquals(Optional.of("Bob, Bob!"), Templates.render("{other}, {other}!", Map.of("other", "Bob")));
    }

    @Test
    void missingOrBlankValueGivesNothing() {
        assertEquals(Optional.empty(), Templates.render("Hi {other}", Map.of()));
        assertEquals(Optional.empty(), Templates.render("Hi {other}", Map.of("other", "  ")));
        assertEquals(Optional.empty(), Templates.render("Hi {other} and {item}", Map.of("other", "Bob")));
    }

    @Test
    void resultIsSanitizedAndValuesAreNotExpandedAgain() {
        assertEquals(Optional.of("Hi Bob"), Templates.render("Hi {other}", Map.of("other", "Bob\n§")));
        assertEquals(Optional.of("a {item} b"), Templates.render("a {other} b", Map.of("other", "{item}")));
    }

    @Test
    void extractsPlaceholderNames() {
        assertEquals(Set.of("other", "item"), Templates.placeholders("{other} got {item}, {other}"));
        assertEquals(Set.of(), Templates.placeholders("no placeholders"));
    }

    @Test
    void emptyTemplateRendersNothing() {
        assertEquals(Optional.empty(), Templates.render("   ", Map.of()));
    }
}
