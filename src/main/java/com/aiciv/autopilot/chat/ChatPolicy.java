package com.aiciv.autopilot.chat;

import com.aiciv.autopilot.ChatPort;
import com.aiciv.autopilot.Tuning;
import com.aiciv.config.AiCivConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/** When one autopilot may speak: cooldowns, the loner rule and the banter limits. Pure; times in milliseconds. */
public final class ChatPolicy {
    public enum Kind { PRIORITY, REPLY, OPTIONAL }

    /** A loner (social at or below this) only answers, dies and gives. */
    public static final double LONER = -0.7;
    static final Set<String> LONER_CATEGORIES = Set.of("mention", "died", "give", "refuse", "coming", "busy", "thanks");
    private static final Set<String> PRIORITY = Set.of("brag", "died");
    private static final Set<String> REPLY = Set.of("mention", "give", "refuse", "coming", "busy", "thanks");
    static final long REPLY_GAP_MILLIS = 5_000;
    static final long CUE_MAX_AGE_MILLIS = 10_000;
    static final long BANTER_CONSIDER_MILLIS = 1200 * 50L;
    static final long BANTER_SPOKEN_MILLIS = 6000 * 50L;

    private final double social;
    private final Tuning tuning;
    private final AiCivConfig.AutopilotConfig cfg;
    private long lastSpoke = Long.MIN_VALUE / 4;
    private long lastReply = Long.MIN_VALUE / 4;
    private long lastLlm = Long.MIN_VALUE / 4;
    private final Map<String, Long> bantersConsidered = new HashMap<>();
    private final Map<String, Long> bantersSpoken = new HashMap<>();

    public ChatPolicy(double social, Tuning tuning, AiCivConfig.AutopilotConfig cfg) {
        this.social = social;
        this.tuning = tuning;
        this.cfg = cfg;
    }

    public static Kind kind(String category, boolean priorityCue) {
        if (priorityCue || PRIORITY.contains(category)) return Kind.PRIORITY;
        return REPLY.contains(category) ? Kind.REPLY : Kind.OPTIONAL;
    }

    public long personalCooldown() {
        return Math.round(cfg.chatCooldownSeconds * 1000.0 * tuning.chatCooldownFactor());
    }

    /** Longer for quiet bots, never shorter than the configured gap: a chatty bot does not spend more LLM calls. */
    public long llmCooldown() {
        long base = cfg.chatLlmCooldownSeconds * 1000L;
        return Math.max(base, Math.round(base * tuning.chatCooldownFactor()));
    }

    public boolean loner() {
        return social <= LONER;
    }

    /** Whether this bot ever says lines of the category. */
    public boolean categoryAllowed(String category) {
        return !loner() || LONER_CATEGORIES.contains(category);
    }

    /** The dice roll an optional line has to pass: always for social 1, a coin flip at 0, half the time at -1. */
    public boolean optionalSpeaks(RandomGenerator r) {
        return r.nextDouble() < 0.5 + 0.5 * social;
    }

    /** Whether a line of this kind may be said now (the category was already checked with {@link #categoryAllowed}). */
    public boolean mayNowSpeak(Kind kind, long now, ChatPort port) {
        if (!port.globalGapOk(now)) return false;
        return switch (kind) {
            case PRIORITY -> true;
            case REPLY -> now - lastReply >= REPLY_GAP_MILLIS;
            case OPTIONAL -> now - lastSpoke >= personalCooldown();
        };
    }

    public void spoke(Kind kind, long now) {
        lastSpoke = now;
        if (kind == Kind.REPLY) lastReply = now;
    }

    public boolean llmCooledDown(long now) {
        return now - lastLlm >= llmCooldown();
    }

    public void llmRequested(long now) {
        lastLlm = now;
    }

    /** True at most once per 1200 ticks for the pair and never within 6000 ticks of a spoken banter; records the look. */
    public boolean considerBanter(String pair, long now) {
        Long looked = bantersConsidered.get(pair);
        if (looked != null && now - looked < BANTER_CONSIDER_MILLIS) return false;
        Long spoken = bantersSpoken.get(pair);
        if (spoken != null && now - spoken < BANTER_SPOKEN_MILLIS) return false;
        bantersConsidered.put(pair, now);
        return true;
    }

    public void banterSpoken(String pair, long now) {
        bantersSpoken.put(pair, now);
    }

    /** Takes over the throttles of the policy this one replaces (a reload), so a reload cannot open the floodgates. */
    void carryOver(ChatPolicy old) {
        lastSpoke = old.lastSpoke;
        lastReply = old.lastReply;
        lastLlm = old.lastLlm;
        bantersConsidered.putAll(old.bantersConsidered);
        bantersSpoken.putAll(old.bantersSpoken);
    }
}
