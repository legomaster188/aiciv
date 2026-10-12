package com.aiciv.nav;

public record NavStatus(State state, String reason) {
    public enum State { IDLE, RUNNING, ARRIVED, FAILED }

    public static final NavStatus IDLE = new NavStatus(State.IDLE, "");
    public static final NavStatus RUNNING = new NavStatus(State.RUNNING, "");
    public static final NavStatus ARRIVED = new NavStatus(State.ARRIVED, "");

    public static NavStatus failed(String reason) {
        return new NavStatus(State.FAILED, reason);
    }
}
