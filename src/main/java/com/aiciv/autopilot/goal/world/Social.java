package com.aiciv.autopilot.goal.world;

import com.aiciv.autopilot.AutopilotMemory;
import com.aiciv.autopilot.Calls;
import com.aiciv.autopilot.ChatCue;
import com.aiciv.autopilot.Requests;
import com.aiciv.autopilot.Situation;
import com.aiciv.autopilot.goal.Goal;
import com.aiciv.autopilot.goal.Move;
import com.aiciv.autopilot.goal.Step;
import com.aiciv.autopilot.goal.Steps;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/** Answer the oldest pending request from another player: hand over an item, come over, or follow. Never finished while one waits. */
public final class Social implements Goal {
    private static final Step GIVE = request("request_give", Requests.GIVE, Set.of("give"), Social::give);
    private static final Step COME = request("request_come", Requests.COME, Set.of("gotoplayer"), (s, r) ->
            Move.act("go to " + r.from(), Calls.of("goToPlayer", "name", r.from()))
                    .withCues(new ChatCue("coming", Map.of("speaker", r.from()), true)));
    private static final Step FOLLOW = request("request_follow", Requests.FOLLOW, Set.of("follow"), (s, r) ->
            Move.act("follow " + r.from(), Calls.of("follow", "name", r.from(), "seconds", 120))
                    .withCues(new ChatCue("coming", Map.of("speaker", r.from()), true)));

    private static final List<Step> STEPS = List.of(GIVE, COME, FOLLOW);

    private static Step request(String id, String kind, Set<String> needs, BiFunction<Situation, AutopilotMemory.Request, Move> move) {
        return Steps.of(id, s -> oldest(s).map(r -> !r.kind().equals(kind)).orElse(true),
                s -> oldest(s).map(r -> move.apply(s, r)).orElseGet(() -> Move.blocked("no request")), needs, false, null);
    }

    private static Optional<AutopilotMemory.Request> oldest(Situation s) {
        return s.requests().isEmpty() ? Optional.empty() : Optional.of(s.requests().get(0));
    }

    /** Gives what is beyond the amount the bot keeps for itself; refuses (silently, with a cue) when nothing is spare. */
    private static Move give(Situation s, AutopilotMemory.Request r) {
        String item = bestMatch(s, r.item());
        int surplus = s.countLike(r.item()) - s.tuning().keepCount();
        if (surplus >= 1) {
            int n = Math.min(r.count(), surplus);
            return Move.act("give " + n + " " + item + " to " + r.from(), Calls.of("give", "name", r.from(), "item", item, "count", n))
                    .withCues(new ChatCue("give", Map.of("speaker", r.from(), "item", item, "count", String.valueOf(n)), true));
        }
        return Move.act("keep my " + item).withCues(new ChatCue("refuse", Map.of("speaker", r.from(), "item", item), true));
    }

    /** The held item id that matches the request: the exact id, else the matching id held in the largest number. */
    static String bestMatch(Situation s, String query) {
        if (s.inventory().containsKey(query)) return query;
        String q = query.endsWith("s") ? query.substring(0, query.length() - 1) : query;
        String best = query;
        int most = 0;
        for (Map.Entry<String, Integer> e : s.inventory().entrySet()) {
            if (!q.isEmpty() && e.getKey().contains(q) && e.getValue() > most) {
                best = e.getKey();
                most = e.getValue();
            }
        }
        return best;
    }

    @Override public String id() { return "social"; }
    @Override public String summary() { return "help other players"; }
    @Override public List<Step> steps() { return STEPS; }
    @Override public double need(Situation s) { return s.requests().isEmpty() ? 0 : 1; }
}
