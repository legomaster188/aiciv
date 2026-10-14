package com.aiciv.autopilot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What one autopilot remembers between decisions: the step in flight, failures and cooldowns per step, blocked goals,
 * flags, when steps last finished and pending requests from other players. Pure; server thread only.
 */
public final class AutopilotMemory {
    public record InFlight(String goal, String step, String flag, long tick) {}

    /** kind GIVE, COME or FOLLOW. */
    public record Request(String kind, String from, String item, int count, long tick) {}

    static final long REQUEST_TTL_TICKS = 2400;
    static final int MAX_REQUESTS = 4;

    private String goal;
    private String step;
    private int decisions;
    private InFlight inFlight;
    /** The last step started, kept after its outcome so a different next step can reset its finish count. */
    private String lastStarted;
    private final Map<String, Integer> failures = new HashMap<>();
    private final Map<String, Long> retryAt = new HashMap<>();
    private final Map<String, Integer> finishes = new HashMap<>();
    /** Times a step finished three times without becoming done; unlike failures, a finish does not clear them. */
    private final Map<String, Integer> stalls = new HashMap<>();
    private final Map<String, Long> blockedUntil = new HashMap<>();
    private final Set<String> flags = new HashSet<>();
    private final Map<String, Long> lastDone = new HashMap<>();
    private final Set<String> bragged = new HashSet<>();
    private final List<Request> requests = new ArrayList<>();

    private static String key(String goal, String step) {
        return goal + "/" + step;
    }

    public String goal() { return goal; }
    public String step() { return step; }
    public int decisions() { return decisions; }

    void decided() {
        decisions++;
    }

    /** The goal and step the bot is pursuing now (the panel and goal hysteresis read them). */
    void current(String goal, String step) {
        this.goal = goal;
        this.step = step;
    }

    /** A different step resets the old step's finish count. */
    public void stepStarted(String goal, String step, String flag, long tick) {
        String k = key(goal, step);
        if (lastStarted != null && !lastStarted.equals(k)) {
            finishes.remove(lastStarted);
            stalls.remove(lastStarted);
        }
        lastStarted = k;
        current(goal, step);
        inFlight = new InFlight(goal, step, flag, tick);
    }

    public Optional<InFlight> inFlight() {
        return Optional.ofNullable(inFlight);
    }

    /**
     * "finished..." → failures and cooldown of the in-flight step cleared, lastDone[goal/step] = tick, flag set, finishes++.
     * "failed..." → {@link #fail}. Either way the in-flight step is cleared.
     */
    public void outcome(String summary, long tick, int maxRetries) {
        InFlight f = inFlight;
        inFlight = null;
        if (f == null || summary == null) return;
        String k = key(f.goal(), f.step());
        if (summary.startsWith("finished")) {
            failures.remove(k);
            retryAt.remove(k);
            lastDone.put(k, tick);
            if (f.flag() != null) flags.add(f.flag());
            finishes.merge(k, 1, Integer::sum);
        } else if (summary.startsWith("failed")) {
            fail(f.goal(), f.step(), tick, maxRetries);
        }
    }

    /** Counts a failure; from the {@code maxRetries}-th on, the step cools down for {@link #backoffTicks} of the overflow. */
    public void fail(String goal, String step, long tick, int maxRetries) {
        String k = key(goal, step);
        int count = failures.merge(k, 1, Integer::sum);
        if (count >= maxRetries) retryAt.put(k, tick + backoffTicks(count - maxRetries + 1));
    }

    /**
     * A step finished again and again without becoming done: counts one failure that finishing cannot clear, and cools the
     * step down like {@link #fail} once there have been {@code maxRetries} of them.
     */
    public void stall(String goal, String step, long tick, int maxRetries) {
        String k = key(goal, step);
        int count = stalls.merge(k, 1, Integer::sum);
        failures.put(k, Math.max(failures.getOrDefault(k, 0) + 1, count));
        if (count >= maxRetries) retryAt.put(k, tick + backoffTicks(count - maxRetries + 1));
    }

    public int failures(String goal, String step) {
        return failures.getOrDefault(key(goal, step), 0);
    }

    public boolean coolingDown(String goal, String step, long tick) {
        return tick < retryAt(goal, step);
    }

    /** When the step may be tried again; 0 when it never cooled down. */
    public long retryAt(String goal, String step) {
        return retryAt.getOrDefault(key(goal, step), 0L);
    }

    public int finishesWithoutDone(String goal, String step) {
        return finishes.getOrDefault(key(goal, step), 0);
    }

    public void resetFinishes(String goal, String step) {
        finishes.remove(key(goal, step));
    }

    public void block(String goal, long untilTick) {
        blockedUntil.put(goal, untilTick);
    }

    public boolean blocked(String goal, long tick) {
        Long until = blockedUntil.get(goal);
        return until != null && tick < until;
    }

    /** Live set: flags raised by finished steps. */
    public Set<String> flags() { return flags; }

    /** Live map: "goal/step" → tick it last finished. */
    public Map<String, Long> lastDone() { return lastDone; }

    /** Live set: milestones already announced (or reached before the first decision). */
    public Set<String> bragged() { return bragged; }

    /** One request per speaker (the newer replaces the older), at most {@value #MAX_REQUESTS} (the oldest dropped). */
    public void heard(Request r) {
        requests.removeIf(old -> old.from().equalsIgnoreCase(r.from()));
        requests.add(r);
        while (requests.size() > MAX_REQUESTS) requests.remove(0);
    }

    /** Pending requests, oldest first; drops those older than {@value #REQUEST_TTL_TICKS} ticks. */
    public List<Request> requests(long tick) {
        requests.removeIf(r -> tick - r.tick() > REQUEST_TTL_TICKS);
        return List.copyOf(requests);
    }

    public void handled(Request r) {
        requests.remove(r);
    }

    public static long backoffTicks(int n) {
        return Math.min(6000L, 600L << Math.min(Math.max(n, 1) - 1, 10));
    }
}
