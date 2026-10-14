package com.aiciv.autopilot;

import java.util.Optional;

/** Reads requests ("give me 3 bread", "follow me") out of chat. Stub; Task 5 owns the body. */
public final class Requests {
    private Requests() {}

    public static Optional<AutopilotMemory.Request> parse(String text, String botName, boolean mentioned, long tick) {
        return Optional.empty();
    }
}
