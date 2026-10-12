package com.aiciv.brain;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/** Bots waiting for an LLM call, most urgent first, then longest waiting. Server thread only. */
public final class ThinkQueue {
    public record Ticket(String bot, WakeReason reason, long since) {}

    private static final Comparator<Ticket> ORDER =
            Comparator.comparingInt((Ticket t) -> t.reason().ordinal()).thenComparingLong(Ticket::since);

    private final Map<String, Ticket> tickets = new LinkedHashMap<>();

    public void offer(String bot, WakeReason reason, long since) {
        tickets.merge(bot, new Ticket(bot, reason, since), (old, neu) -> new Ticket(bot,
                neu.reason().ordinal() < old.reason().ordinal() ? neu.reason() : old.reason(),
                Math.min(old.since(), neu.since())));
    }

    /** Removes and returns the best ticket whose bot can be dispatched now. */
    public Optional<Ticket> pollFirst(Predicate<String> dispatchable) {
        Optional<Ticket> best = tickets.values().stream().filter(t -> dispatchable.test(t.bot())).min(ORDER);
        best.ifPresent(t -> tickets.remove(t.bot()));
        return best;
    }

    public void remove(String bot) {
        tickets.remove(bot);
    }

    public void clear() {
        tickets.clear();
    }

    public int size() {
        return tickets.size();
    }

    public int positionOf(String bot) {
        List<Ticket> sorted = tickets.values().stream().sorted(ORDER).toList();
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).bot().equals(bot)) return i + 1;
        }
        return -1;
    }
}
