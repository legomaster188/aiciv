package com.aiciv.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PlayerNamesTest {
    @Test
    void stripsTagsQuotesAtSignAndWhitespace() {
        assertEquals("Steve", PlayerNames.clean("Steve [human]"));
        assertEquals("Bob", PlayerNames.clean("Bob (AI)"));
        assertEquals("Steve", PlayerNames.clean("@Steve"));
        assertEquals("Steve", PlayerNames.clean("  \"Steve\" "));
        assertEquals("Steve", PlayerNames.clean("'@Steve [human]'"));
        assertEquals("Steve_1", PlayerNames.clean("Steve_1"));
        assertEquals("", PlayerNames.clean(null));
    }
}
