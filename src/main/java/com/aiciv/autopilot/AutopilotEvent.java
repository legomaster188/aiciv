package com.aiciv.autopilot;

/** Things that happen to an autopilot bot; the chat layer turns some of them into lines. */
public sealed interface AutopilotEvent {
    record Spawned() implements AutopilotEvent {}

    /** {@code speakerIsAi}: any bot spoke; {@code speakerIsAutopilot}: an autopilot bot did (its lines come from templates). */
    record Heard(String speaker, String text, boolean mentioned, boolean speakerIsAi, boolean speakerIsAutopilot) implements AutopilotEvent {
        public Heard(String speaker, String text, boolean mentioned, boolean speakerIsAi) {
            this(speaker, text, mentioned, speakerIsAi, false);
        }
    }

    record Hurt(String attacker, int health) implements AutopilotEvent {}

    record ActionsEnded(String summary) implements AutopilotEvent {}

    record Died(String message) implements AutopilotEvent {}

    record OtherDied(String name, String message) implements AutopilotEvent {}

    record PlayerJoined(String name, boolean ai) implements AutopilotEvent {}

    record NightFell() implements AutopilotEvent {}

    record Received(String from, String text) implements AutopilotEvent {}

    record Milestone(String what) implements AutopilotEvent {}

    record GoalChanged(String goal, String summary) implements AutopilotEvent {}

    record StepBlocked(String goal, String step, String reason, int failures) implements AutopilotEvent {}
}
