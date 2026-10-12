package com.aiciv.brain;

/**
 * Decides when one bot should think. Pure state: callers pass the time in milliseconds.
 * Not thread-safe; use it from the server thread only.
 */
public final class BotBrain {
    /** One hurt wake per this long, so damage over time does not cost a call per reply. */
    public static final long HURT_COOLDOWN_MILLIS = 10_000;
    /** Actions that end at once (already there, nothing to mine) must not turn into several requests a second. */
    public static final long DEFAULT_ACTION_ENDED_GAP_MILLIS = 3_000;
    private long actionEndedGapMillis = DEFAULT_ACTION_ENDED_GAP_MILLIS;

    private final String name;
    private String persona;
    private String goal;
    private String modelKey;
    private boolean paused;
    private final EventLog events;
    private long idleMillis;
    private long mentionCooldownMillis;
    private WakeReason pending;
    private long lastDecisionMillis;
    private long lastMentionWakeMillis = Long.MIN_VALUE / 2;
    private long lastHurtWakeMillis = Long.MIN_VALUE / 2;
    private boolean mentionWaiting;
    private boolean awaitingReply;
    private String lastDecision = "nothing yet";

    public BotBrain(String name, String persona, String goal, String modelKey, int eventWindow,
                    long idleMillis, long mentionCooldownMillis, long nowMillis) {
        this.name = name;
        this.persona = persona;
        this.goal = goal;
        this.modelKey = modelKey;
        this.events = new EventLog(eventWindow);
        this.idleMillis = idleMillis;
        this.mentionCooldownMillis = mentionCooldownMillis;
        this.lastDecisionMillis = nowMillis - idleMillis; // think right after joining
    }

    public void hearChat(String stamp, String speaker, String text, boolean mentioned, long now) {
        events.add(stamp, speaker + " said: \"" + text + "\"");
        if (mentioned) mentionWaiting = true;
    }

    public void hurt(String stamp, String description, long now) {
        if (now - lastHurtWakeMillis >= HURT_COOLDOWN_MILLIS) {
            events.add(stamp, description);
            lastHurtWakeMillis = now;
            raise(WakeReason.HURT);
        }
    }

    public void actionsEnded(String stamp, String summary) {
        events.add(stamp, summary);
        raise(WakeReason.ACTION_ENDED);
    }

    /** Records an event without waking the bot. */
    public void note(String stamp, String text) {
        events.add(stamp, text);
    }

    public void setActionEndedGap(long millis) {
        actionEndedGapMillis = Math.max(0, millis);
    }

    /** Why the bot should think now, or null. Clears the trigger it returns. */
    public WakeReason pollWake(long now, boolean busy) {
        if (mentionWaiting && now - lastMentionWakeMillis >= mentionCooldownMillis) {
            mentionWaiting = false;
            lastMentionWakeMillis = now;
            raise(WakeReason.MENTIONED);
        }
        if (paused || awaitingReply) return null;
        if (pending == WakeReason.ACTION_ENDED && now - lastDecisionMillis < actionEndedGapMillis) return null;
        if (pending != null) {
            WakeReason r = pending;
            pending = null;
            return r;
        }
        if (!busy && now - lastDecisionMillis >= idleMillis) return WakeReason.IDLE;
        return null;
    }

    public void dispatched(long now) {
        awaitingReply = true;
        lastDecisionMillis = now;
    }

    public void replied(String summary) {
        awaitingReply = false;
        lastDecision = summary;
    }

    /** The request failed; ask again for the same reason once a provider is free. */
    public void failed(WakeReason reason) {
        awaitingReply = false;
        raise(reason);
    }

    private void raise(WakeReason r) {
        if (pending == null || r.ordinal() < pending.ordinal()) pending = r;
    }

    public String name() { return name; }
    public String persona() { return persona; }
    public String goal() { return goal; }
    public String modelKey() { return modelKey; }
    public boolean paused() { return paused; }
    public boolean awaitingReply() { return awaitingReply; }
    public String lastDecision() { return lastDecision; }
    public EventLog events() { return events; }

    public void setPersona(String persona) { this.persona = persona; }
    public void setGoal(String goal) { this.goal = goal; }
    public void setModelKey(String modelKey) { this.modelKey = modelKey; }
    public void setPaused(boolean paused) { this.paused = paused; }

    /** Applies reloaded config to a running bot. */
    public void setTiming(long idleMillis, long mentionCooldownMillis, int eventWindow) {
        this.idleMillis = idleMillis;
        this.mentionCooldownMillis = mentionCooldownMillis;
        events.setCapacity(eventWindow);
    }
}
