package com.aiciv.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Tracks whether a provider should get requests right now. */
public final class ProviderHealth {
    static final Duration DOWN_FOR = Duration.ofMinutes(5);
    static final int FAILURES_TO_DOWN = 3;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Clock clock;
    private int consecutiveFailures;
    private int consecutiveRateLimits;
    private Instant unavailableUntil = Instant.EPOCH;
    private boolean authDisabled;
    private String lastProblem = "";

    public ProviderHealth(Clock clock) {
        this.clock = clock;
    }

    public synchronized boolean available() {
        return !authDisabled && !clock.instant().isBefore(unavailableUntil);
    }

    public synchronized void onSuccess() {
        consecutiveFailures = 0;
        consecutiveRateLimits = 0;
        lastProblem = "";
    }

    public synchronized void onRateLimited(Duration retryAfter) {
        consecutiveRateLimits++;
        Duration wait = retryAfter != null ? retryAfter : backoff(consecutiveRateLimits);
        extendPause(wait);
        lastProblem = "rate limited for " + wait.toSeconds() + "s";
    }

    public synchronized void onFailure(String reason) {
        consecutiveFailures++;
        lastProblem = reason;
        if (consecutiveFailures >= FAILURES_TO_DOWN) {
            extendPause(DOWN_FOR);
            consecutiveFailures = 0;
        }
    }

    public synchronized void onAuthFailure(String reason) {
        authDisabled = true;
        lastProblem = reason;
    }

    /** True once a key was missing or rejected; only a reload clears it. */
    public synchronized boolean isAuthDisabled() {
        return authDisabled;
    }

    public synchronized String status() {
        if (authDisabled) return "disabled: " + lastProblem;
        if (clock.instant().isBefore(unavailableUntil)) {
            String until = TIME.format(unavailableUntil.atZone(ZoneId.systemDefault()));
            return "paused until " + until + " (" + lastProblem + ")";
        }
        return lastProblem.isEmpty() ? "ok" : "ok (last problem: " + lastProblem + ")";
    }

    /** Pauses requests for at least {@code wait}; a shorter pause never cuts a longer one short. */
    private void extendPause(Duration wait) {
        Instant until = clock.instant().plus(wait);
        if (until.isAfter(unavailableUntil)) unavailableUntil = until;
    }

    /** 5 s, 10 s, 20 s, ... capped at 120 s, for the n-th rate limit in a row. */
    static Duration backoff(int consecutive) {
        long seconds = 5L << Math.min(Math.max(consecutive, 1) - 1, 5);
        return Duration.ofSeconds(Math.min(seconds, 120));
    }
}
