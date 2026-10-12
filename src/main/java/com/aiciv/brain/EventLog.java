package com.aiciv.brain;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** The newest events a bot remembers, sent with every request. */
public final class EventLog {
    private int capacity;
    private final Deque<String> lines = new ArrayDeque<>();

    public EventLog(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** Changes how many lines are kept; the newest survive a shrink. */
    public void setCapacity(int capacity) {
        this.capacity = Math.max(1, capacity);
        while (lines.size() > this.capacity) lines.removeFirst();
    }

    public void add(String stamp, String text) {
        lines.addLast("[" + stamp + "] " + text);
        while (lines.size() > capacity) lines.removeFirst();
    }

    public List<String> lines() {
        return List.copyOf(lines);
    }
}
