package com.aiciv.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SignLinesTest {
    @Test
    void wrapsOnWordBoundaries() {
        assertEquals(List.of("Welcome to Ada", "town"), SignLines.wrap("Welcome to Ada town"));
    }

    @Test
    void splitsAWordLongerThanALine() {
        assertEquals(List.of("abcdefghijklmno", "pqrst"), SignLines.wrap("abcdefghijklmnopqrst"));
    }

    @Test
    void cutsTextOverSixtyCharactersWithoutAnEllipsis() {
        List<String> lines = SignLines.wrap("x".repeat(80));
        assertEquals(4, lines.size());
        assertEquals(60, lines.stream().mapToInt(String::length).sum());
        assertTrue(lines.stream().noneMatch(l -> l.contains("...")));
    }

    @Test
    void neverReturnsMoreThanFourLinesOfFifteen() {
        List<String> lines = SignLines.wrap("one two three four five six seven eight nine ten eleven twelve");
        assertTrue(lines.size() <= 4);
        assertTrue(lines.stream().allMatch(l -> l.length() <= 15));
    }

    @Test
    void blankTextGivesNoLines() {
        assertTrue(SignLines.wrap("   ").isEmpty());
    }
}
