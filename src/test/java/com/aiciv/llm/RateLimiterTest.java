package com.aiciv.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.testutil.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RateLimiterTest {
    MutableClock clock = new MutableClock(Instant.parse("2026-10-11T12:00:00Z"));

    @Test
    void perMinuteTokensRefill() {
        RateLimiter l = new RateLimiter(2, 0, 10, ZoneOffset.UTC, clock);
        assertTrue(l.tryAcquire());
        assertTrue(l.tryAcquire());
        assertFalse(l.tryAcquire());
        clock.advance(Duration.ofSeconds(30));
        assertTrue(l.tryAcquire());
        assertFalse(l.tryAcquire());
    }

    @Test
    void inFlightCapUntilReleased() {
        RateLimiter l = new RateLimiter(0, 0, 1, ZoneOffset.UTC, clock);
        assertTrue(l.tryAcquire());
        assertFalse(l.tryAcquire());
        l.release();
        assertTrue(l.tryAcquire());
    }

    @Test
    void dailyCapResetsAtMidnightInTheResetZone() {
        // 06:59 UTC is 23:59 the previous day in Los Angeles (PDT).
        MutableClock la = new MutableClock(Instant.parse("2026-10-11T06:59:00Z"));
        RateLimiter l = new RateLimiter(0, 2, 10, ZoneId.of("America/Los_Angeles"), la);
        assertTrue(l.tryAcquire());
        assertTrue(l.tryAcquire());
        assertFalse(l.tryAcquire());
        assertEquals(0, l.remainingToday());
        la.advance(Duration.ofMinutes(2));
        assertEquals(2, l.remainingToday());
        assertTrue(l.tryAcquire());
        assertEquals(1, l.usedToday());
    }

    @Test
    void zeroMeansUnlimited() {
        RateLimiter l = new RateLimiter(0, 0, 1000, ZoneOffset.UTC, clock);
        for (int i = 0; i < 500; i++) assertTrue(l.tryAcquire());
        assertEquals(-1, l.remainingToday());
    }

    @Test
    void wouldAllowDoesNotConsume() {
        RateLimiter l = new RateLimiter(1, 0, 10, ZoneOffset.UTC, clock);
        assertTrue(l.wouldAllow());
        assertTrue(l.wouldAllow());
        assertTrue(l.tryAcquire());
        assertFalse(l.wouldAllow());
    }
}
