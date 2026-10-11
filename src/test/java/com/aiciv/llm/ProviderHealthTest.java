package com.aiciv.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.testutil.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProviderHealthTest {
    MutableClock clock = new MutableClock(Instant.parse("2026-10-11T12:00:00Z"));
    ProviderHealth h = new ProviderHealth(clock);

    @Test
    void threeFailuresTakeItDownForFiveMinutes() {
        h.onFailure("HTTP 500");
        h.onFailure("HTTP 500");
        assertTrue(h.available());
        h.onFailure("HTTP 500");
        assertFalse(h.available());
        assertTrue(h.status().contains("HTTP 500"), h.status());
        clock.advance(Duration.ofMinutes(5));
        assertTrue(h.available());
    }

    @Test
    void successResetsTheFailureCount() {
        h.onFailure("x");
        h.onFailure("x");
        h.onSuccess();
        h.onFailure("x");
        h.onFailure("x");
        assertTrue(h.available());
    }

    @Test
    void retryAfterIsHonoured() {
        h.onRateLimited(Duration.ofSeconds(7));
        assertFalse(h.available());
        clock.advance(Duration.ofSeconds(7));
        assertTrue(h.available());
    }

    @Test
    void backoffDoublesUpTo120Seconds() {
        assertEquals(Duration.ofSeconds(5), ProviderHealth.backoff(1));
        assertEquals(Duration.ofSeconds(10), ProviderHealth.backoff(2));
        assertEquals(Duration.ofSeconds(20), ProviderHealth.backoff(3));
        assertEquals(Duration.ofSeconds(80), ProviderHealth.backoff(5));
        assertEquals(Duration.ofSeconds(120), ProviderHealth.backoff(6));
        assertEquals(Duration.ofSeconds(120), ProviderHealth.backoff(40));
    }

    @Test
    void rateLimitWithoutRetryAfterBacksOff() {
        h.onRateLimited(null);
        clock.advance(Duration.ofSeconds(5));
        assertTrue(h.available());
        h.onRateLimited(null);
        clock.advance(Duration.ofSeconds(5));
        assertFalse(h.available());
        clock.advance(Duration.ofSeconds(5));
        assertTrue(h.available());
    }

    @Test
    void aShortRateLimitDoesNotShortenAFailurePause() {
        h.onFailure("HTTP 500");
        h.onFailure("HTTP 500");
        h.onFailure("HTTP 500");
        h.onRateLimited(Duration.ofSeconds(5));
        clock.advance(Duration.ofSeconds(10));
        assertFalse(h.available());
        clock.advance(Duration.ofMinutes(5).minusSeconds(10));
        assertTrue(h.available());
    }

    @Test
    void lateFailuresDoNotShortenALongRetryAfter() {
        h.onRateLimited(Duration.ofMinutes(10));
        h.onFailure("HTTP 500");
        h.onFailure("HTTP 500");
        h.onFailure("HTTP 500");
        clock.advance(Duration.ofMinutes(5));
        assertFalse(h.available());
        clock.advance(Duration.ofMinutes(5));
        assertTrue(h.available());
    }

    @Test
    void authFailureDisablesForGood() {
        h.onAuthFailure("HTTP 401 (key rejected)");
        clock.advance(Duration.ofDays(1));
        assertFalse(h.available());
        assertTrue(h.status().startsWith("disabled"), h.status());
        assertTrue(h.isAuthDisabled());
    }
}
