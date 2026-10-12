package com.aiciv.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Incremental A* over feet positions. Moves: walk (incl. diagonal), jump up one block,
 * drop down at most three. Never enters or stands on a dangerous block.
 */
public final class PathSearch {
    public enum State { RUNNING, FOUND, FAILED }

    public static final int MAX_NODES = 20_000;
    static final double WALK = 1.0, DIAGONAL = 1.4, JUMP = 2.0, DROP_BASE = 1.0, DROP_PER_BLOCK = 0.5;
    static final int MAX_DROP = 3;
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private static final class Node {
        final Pos pos;
        final double h;
        double g;
        Node parent;
        boolean closed;

        Node(Pos pos, double g, double h, Node parent) {
            this.pos = pos;
            this.g = g;
            this.h = h;
            this.parent = parent;
        }

        double f() {
            return g + h;
        }
    }

    private record Entry(Node node, double f) {}

    private final BlockView view;
    private final Goal goal;
    private final int maxNodes;
    private final PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::f));
    private final Map<Pos, Node> nodes = new HashMap<>();
    private int expanded;
    private State state = State.RUNNING;
    private List<Pos> path = List.of();
    private String failure = "";

    public PathSearch(BlockView view, Pos start, Goal goal, int maxNodes) {
        this.view = view;
        this.goal = goal;
        this.maxNodes = maxNodes;
        Node s = new Node(start, 0.0, goal.estimate(start), null);
        nodes.put(start, s);
        open.add(new Entry(s, s.f()));
    }

    /** Expands at most nodeBudget nodes, then returns the current state. */
    public State advance(int nodeBudget) {
        int budget = nodeBudget;
        while (state == State.RUNNING && budget-- > 0) {
            Entry e = open.poll();
            if (e == null) {
                fail("no path");
                break;
            }
            Node cur = e.node();
            if (cur.closed || e.f() > cur.f() + 1e-9) continue;
            cur.closed = true;
            if (goal.reached(cur.pos)) {
                path = build(cur);
                state = State.FOUND;
                break;
            }
            if (++expanded >= maxNodes) {
                fail("no path");
                break;
            }
            expand(cur);
        }
        return state;
    }

    public void cancel() {
        if (state == State.RUNNING) fail("cancelled");
    }

    public State state() { return state; }
    public List<Pos> path() { return path; }
    public String failure() { return failure; }
    public int expandedCount() { return expanded; }

    private void expand(Node cur) {
        Pos p = cur.pos;
        int y = p.y();
        for (int[] d : DIRECTIONS) {
            int dx = d[0], dz = d[1];
            int nx = p.x() + dx, nz = p.z() + dz;
            if (dx != 0 && dz != 0) {
                if (canStand(nx, y, nz) && clear(p.x() + dx, y, p.z()) && clear(p.x(), y, p.z() + dz)) {
                    relax(cur, new Pos(nx, y, nz), DIAGONAL);
                }
                continue;
            }
            if (canStand(nx, y, nz)) {
                relax(cur, new Pos(nx, y, nz), WALK);
                continue;
            }
            if (canStand(nx, y + 1, nz) && view.passable(p.x(), y + 2, p.z()) && !view.dangerous(p.x(), y + 2, p.z())) {
                relax(cur, new Pos(nx, y + 1, nz), JUMP);
                continue;
            }
            if (clear(nx, y, nz)) {
                for (int drop = 1; drop <= MAX_DROP; drop++) {
                    int ny = y - drop;
                    if (canStand(nx, ny, nz)) {
                        relax(cur, new Pos(nx, ny, nz), DROP_BASE + DROP_PER_BLOCK * drop);
                        break;
                    }
                    if (!view.passable(nx, ny, nz) || view.dangerous(nx, ny, nz)) break;
                }
            }
        }
    }

    private void relax(Node cur, Pos next, double cost) {
        double g = cur.g + cost;
        Node n = nodes.get(next);
        if (n == null) {
            n = new Node(next, g, goal.estimate(next), cur);
            nodes.put(next, n);
            open.add(new Entry(n, n.f()));
        } else if (!n.closed && g < n.g - 1e-9) {
            n.g = g;
            n.parent = cur;
            open.add(new Entry(n, n.f()));
        }
    }

    /** Body fits at (x, y, z): two passable, safe blocks. */
    private boolean clear(int x, int y, int z) {
        return view.passable(x, y, z) && view.passable(x, y + 1, z)
                && !view.dangerous(x, y, z) && !view.dangerous(x, y + 1, z);
    }

    private boolean canStand(int x, int y, int z) {
        return clear(x, y, z) && view.standable(x, y - 1, z) && !view.dangerous(x, y - 1, z);
    }

    private static List<Pos> build(Node end) {
        List<Pos> out = new ArrayList<>();
        for (Node n = end; n.parent != null; n = n.parent) out.add(n.pos);
        Collections.reverse(out);
        return out;
    }

    private void fail(String reason) {
        state = State.FAILED;
        failure = reason;
    }
}
