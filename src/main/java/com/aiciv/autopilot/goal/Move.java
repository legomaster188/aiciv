package com.aiciv.autopilot.goal;

import com.aiciv.autopilot.ChatCue;
import com.aiciv.llm.ToolCall;
import java.util.ArrayList;
import java.util.List;

/** What a step wants done now: calls to run (ACT), calls that look for materials (GATHER), or a reason it cannot go on (BLOCKED). */
public record Move(List<ToolCall> calls, String why, Kind kind, List<ChatCue> cues) {
    public enum Kind { ACT, GATHER, BLOCKED }

    public Move {
        calls = calls == null ? List.of() : List.copyOf(calls);
        cues = cues == null ? List.of() : List.copyOf(cues);
    }

    public static Move act(String why, ToolCall... calls) {
        return new Move(List.of(calls), why, Kind.ACT, List.of());
    }

    public static Move gather(String why, ToolCall... calls) {
        return new Move(List.of(calls), why, Kind.GATHER, List.of());
    }

    public static Move blocked(String why) {
        return new Move(List.of(), why, Kind.BLOCKED, List.of());
    }

    /** The same move with these cues added. */
    public Move withCues(ChatCue... more) {
        List<ChatCue> all = new ArrayList<>(cues);
        all.addAll(List.of(more));
        return new Move(calls, why, kind, all);
    }
}
