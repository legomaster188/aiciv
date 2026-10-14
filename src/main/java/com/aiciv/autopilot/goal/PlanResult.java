package com.aiciv.autopilot.goal;

/** The planner's answer for one goal: the next step and move, why it is stuck, or that it is achieved. */
public sealed interface PlanResult {
    record Next(Goal goal, Step step, Move move) implements PlanResult {}

    record Blocked(Goal goal, Step step, String reason) implements PlanResult {}

    record Achieved(Goal goal) implements PlanResult {}
}
