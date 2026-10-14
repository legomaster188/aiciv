package com.aiciv.autopilot;

/** One autopilot's voice: it hears events and cues, and on its tick says at most one line. */
public interface AutopilotChat {
    /** Ignores everything and never speaks. */
    AutopilotChat NONE = new AutopilotChat() {
        @Override
        public void observe(AutopilotEvent event, long nowMillis) {}

        @Override
        public void cue(ChatCue cue, long nowMillis) {}

        @Override
        public void tick(ChatPort port, long nowMillis) {}
    };

    void observe(AutopilotEvent event, long nowMillis);

    void cue(ChatCue cue, long nowMillis);

    /** Server thread, every tick; says at most one line. */
    void tick(ChatPort port, long nowMillis);

    /** Called on the chat that replaces {@code previous} (a reload): keep its cooldowns and what was said lately. */
    default void carryOver(AutopilotChat previous) {}
}
