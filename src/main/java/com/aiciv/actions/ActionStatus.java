package com.aiciv.actions;

public record ActionStatus(State state, String reason) {
    public enum State { RUNNING, DONE, FAILED }

    public static final ActionStatus RUNNING = new ActionStatus(State.RUNNING, "");
    public static final ActionStatus DONE = new ActionStatus(State.DONE, "");

    public static ActionStatus failed(String reason) {
        return new ActionStatus(State.FAILED, reason);
    }
}
