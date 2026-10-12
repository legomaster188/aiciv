package com.aiciv.bot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WarningPolicyTest {
    @Test
    void shortTransientWaitsStayOutOfChat() {
        assertFalse(WarningPolicy.shouldChat(false, 59_999));
    }

    @Test
    void longWaitsAreChatted() {
        assertTrue(WarningPolicy.shouldChat(false, 60_000));
    }

    @Test
    void disabledPrimaryIsChattedImmediately() {
        assertTrue(WarningPolicy.shouldChat(true, 0));
    }
}
