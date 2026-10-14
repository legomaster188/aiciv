package com.aiciv.autopilot;

import com.aiciv.autopilot.goal.PlanResult;
import com.aiciv.llm.ToolCall;
import java.util.List;

/**
 * One autopilot decision: the calls to start, why, which goal and step they serve, the flag to set when they finish, chat
 * cues, and whether the running action should simply be kept.
 */
public record Decision(List<ToolCall> calls, String thought, String goal, String step, String flag,
                       List<ChatCue> cues, boolean keepCurrent) {
    public Decision {
        calls = calls == null ? List.of() : List.copyOf(calls);
        cues = cues == null ? List.of() : List.copyOf(cues);
    }

    /** No calls; goal and step null. */
    public static Decision waiting(String thought) {
        return new Decision(List.of(), thought, null, null, null, List.of(), false);
    }

    /** Calls and cues from the move, flag from the step. */
    public static Decision of(PlanResult.Next next, String thought) {
        return new Decision(next.move().calls(), thought, next.goal().id(), next.step().id(), next.step().flag(),
                next.move().cues(), false);
    }

    /** The same decision, marked to keep the action already running. */
    public Decision kept() {
        return new Decision(calls, thought, goal, step, flag, cues, true);
    }
}
