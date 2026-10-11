package com.aiciv.llm;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** Per-provider request budget: requests per minute, per day, and concurrently in flight. */
public final class RateLimiter {
    private final int rpm;
    private final int rpd;
    private final int maxInFlight;
    private final ZoneId zone;
    private final Clock clock;
    private double tokens;
    private long lastRefillMillis;
    private LocalDate day;
    private int usedToday;
    private int inFlight;

    public RateLimiter(int rpm, int rpd, int maxInFlight, ZoneId zone, Clock clock) {
        this.rpm = Math.max(0, rpm);
        this.rpd = Math.max(0, rpd);
        this.maxInFlight = Math.max(1, maxInFlight);
        this.zone = zone;
        this.clock = clock;
        this.tokens = this.rpm;
        this.lastRefillMillis = clock.millis();
        this.day = today();
    }

    public synchronized boolean tryAcquire() {
        if (!wouldAllow()) return false;
        if (rpm > 0) tokens -= 1.0;
        usedToday++;
        inFlight++;
        return true;
    }

    public synchronized boolean wouldAllow() {
        refill();
        rollDay();
        if (inFlight >= maxInFlight) return false;
        if (rpd > 0 && usedToday >= rpd) return false;
        return rpm == 0 || tokens >= 1.0;
    }

    public synchronized void release() {
        if (inFlight > 0) inFlight--;
    }

    public synchronized int usedToday() {
        rollDay();
        return usedToday;
    }

    /** Requests left today, or -1 when there is no daily limit. */
    public synchronized int remainingToday() {
        rollDay();
        return rpd == 0 ? -1 : Math.max(0, rpd - usedToday);
    }

    private void refill() {
        if (rpm == 0) return;
        long now = clock.millis();
        tokens = Math.min(rpm, tokens + (now - lastRefillMillis) * rpm / 60_000.0);
        lastRefillMillis = now;
    }

    private void rollDay() {
        LocalDate today = today();
        if (!today.equals(day)) {
            day = today;
            usedToday = 0;
        }
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }
}
