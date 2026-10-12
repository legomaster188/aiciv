package com.aiciv.brain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HearingTest {
    @Test
    void mentionsAreWholeWordsIgnoringCase() {
        assertTrue(Hearing.mentions("hey ada!", "Ada"));
        assertTrue(Hearing.mentions("ADA, come here", "Ada"));
        assertTrue(Hearing.mentions("@Ada_2 hi", "Ada_2"));
        assertFalse(Hearing.mentions("adam is here", "Ada"));
        assertFalse(Hearing.mentions("canada", "Ada"));
        assertFalse(Hearing.mentions("Ada_2 hi", "Ada"));
    }

    @Test
    void hearingRangeOrMention() {
        assertTrue(Hearing.hears(64 * 64, 64, false));
        assertFalse(Hearing.hears(64 * 64 + 1, 64, false));
        assertTrue(Hearing.hears(Double.MAX_VALUE, 64, true));
    }
}
