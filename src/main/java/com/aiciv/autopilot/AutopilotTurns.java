package com.aiciv.autopilot;

import com.aiciv.brain.WakeReason;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Round robin of autopilot turns: bots wanting to decide, oldest first, a few per tick. Pure. */
public final class AutopilotTurns {
    /** trigger: URGENT, HURT, MENTIONED, ACTION_ENDED or IDLE; detail is the urgent reason. */
    public record Turn(String bot, String trigger, String detail) {}

    private final Map<String, Turn> waiting = new LinkedHashMap<>();

    /** A waiting bot keeps its place; the more urgent trigger wins (URGENT first, then {@link WakeReason} order). */
    public void offer(String bot, String trigger, String detail) {
        Turn old = waiting.get(bot);
        if (old == null || rank(trigger) < rank(old.trigger())) waiting.put(bot, new Turn(bot, trigger, detail));
    }

    /** Up to {@code max} turns, oldest first; taken bots leave the queue. */
    public List<Turn> take(int max) {
        List<Turn> out = new ArrayList<>();
        Iterator<Turn> it = waiting.values().iterator();
        while (out.size() < max && it.hasNext()) {
            out.add(it.next());
            it.remove();
        }
        return out;
    }

    public void remove(String bot) {
        waiting.remove(bot);
    }

    public int size() {
        return waiting.size();
    }

    private static int rank(String trigger) {
        if ("URGENT".equals(trigger)) return -1;
        for (WakeReason r : WakeReason.values()) if (r.name().equals(trigger)) return r.ordinal();
        return Integer.MAX_VALUE;
    }
}
