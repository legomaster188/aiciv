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
 * drop down at most three, swim, and with {@link SearchOptions} also dig, bridge and pillar.
 * Never enters or stands on a dangerous block.
 */
public final class PathSearch {
    public enum State { RUNNING, FOUND, FAILED }

    public static final int MAX_NODES = 20_000;
    static final double WALK = 1.0, DIAGONAL = 1.4, JUMP = 2.0, DROP_BASE = 1.0, DROP_PER_BLOCK = 0.5;
    static final double SWIM = 2.0, BRIDGE = 3.0, PILLAR = 3.0, BREAK_PER_SECOND = 4.0;
    static final int MAX_DROP = 3;
    private static final double INF = Double.POSITIVE_INFINITY;
    private static final int[][] NEIGHBOURS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private static final class Node {
        final Pos pos;
        final double h;
        double g;
        Node parent;
        boolean closed;
        Move move = Move.WALK;
        List<Pos> breaks = List.of();
        Pos place;
        Pos open;

        Node(Pos pos, double g, double h, Node parent) {
            this.pos = pos;
            this.g = g;
            this.h = h;
            this.parent = parent;
        }

        void set(Move move, List<Pos> breaks, Pos place, Pos open) {
            this.move = move;
            this.breaks = breaks;
            this.place = place;
            this.open = open;
        }

        double f() {
            return g + h;
        }
    }

    private record Entry(Node node, double f) {}

    private final BlockView view;
    private final Goal goal;
    private final int maxNodes;
    private final SearchOptions options;
    private final PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::f));
    private final Map<Pos, Node> nodes = new HashMap<>();
    private int expanded;
    private State state = State.RUNNING;
    private List<PathStep> steps = List.of();
    private String failure = "";

    public PathSearch(BlockView view, Pos start, Goal goal, int maxNodes) {
        this(view, start, goal, maxNodes, SearchOptions.WALK_ONLY);
    }

    public PathSearch(BlockView view, Pos start, Goal goal, int maxNodes, SearchOptions options) {
        this.view = view;
        this.options = options;
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
                steps = build(cur);
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
    public List<Pos> path() { return steps.stream().map(PathStep::to).toList(); }
    public List<PathStep> steps() { return steps; }
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
                    relax(cur, new Pos(nx, y, nz), DIAGONAL, Move.DIAGONAL);
                }
                continue;
            }
            if (canStand(nx, y, nz)) {
                relax(cur, new Pos(nx, y, nz), WALK, Move.WALK);
                continue;
            }
            if (doorWalk(cur, nx, y, nz)) continue;
            if (canStand(nx, y + 1, nz) && view.passable(p.x(), y + 2, p.z()) && !view.dangerous(p.x(), y + 2, p.z())) {
                relax(cur, new Pos(nx, y + 1, nz), JUMP, Move.JUMP);
                continue;
            }
            if (options.canBreak()) {
                digWalk(cur, nx, y, nz);
                digJump(cur, nx, y, nz);
            }
            if (clear(nx, y, nz)) {
                for (int drop = 1; drop <= MAX_DROP; drop++) {
                    int ny = y - drop;
                    if (canStand(nx, ny, nz)) {
                        relax(cur, new Pos(nx, ny, nz), DROP_BASE + DROP_PER_BLOCK * drop, Move.DROP);
                        break;
                    }
                    if (!view.passable(nx, ny, nz) || view.dangerous(nx, ny, nz)) break;
                }
                if (options.canPlace() && !view.standable(nx, y - 1, nz) && view.passable(nx, y - 1, nz)
                        && !view.dangerous(nx, y - 1, nz) && !nextToLava(nx, y - 1, nz)) {
                    relax(cur, new Pos(nx, y, nz), BRIDGE, Move.BRIDGE, List.of(), new Pos(nx, y - 1, nz));
                }
            }
        }
        swim(cur);
        if (options.canPlace()) pillar(cur);
    }

    /** Walk through a closed door or fence gate: the feet cell is openable, the head cell is open air or the upper half. */
    private boolean doorWalk(Node cur, int nx, int y, int nz) {
        if (!view.openable(nx, y, nz) || !floorOk(nx, y - 1, nz)) return false;
        if (!view.openable(nx, y + 1, nz) && !view.passable(nx, y + 1, nz)) return false;
        if (view.dangerous(nx, y + 1, nz)) return false;
        relax(cur, new Pos(nx, y, nz), WALK + 1.0, Move.WALK, List.of(), null, new Pos(nx, y, nz));
        return true;
    }

    /** Walk into a cell by breaking its feet and head blocks. */
    private void digWalk(Node cur, int nx, int y, int nz) {
        if (!floorOk(nx, y - 1, nz)) return;
        List<Pos> breaks = breaksOf(nx, y, nz, nx, y + 1, nz);
        double cost = breakCost(nx, y, nz, nx, y + 1, nz);
        if (!breaks.isEmpty() && cost < INF) relax(cur, new Pos(nx, y, nz), WALK + cost, Move.WALK, breaks, null);
    }

    /** Jump up one block, breaking the head-room above the start and the target's feet and head. */
    private void digJump(Node cur, int nx, int y, int nz) {
        Pos p = cur.pos;
        if (!floorOk(nx, y, nz)) return;
        List<Pos> breaks = breaksOf(p.x(), y + 2, p.z(), nx, y + 1, nz, nx, y + 2, nz);
        double cost = breakCost(p.x(), y + 2, p.z(), nx, y + 1, nz, nx, y + 2, nz);
        if (!breaks.isEmpty() && cost < INF) relax(cur, new Pos(nx, y + 1, nz), JUMP + cost, Move.JUMP, breaks, null);
    }

    /** Swimming needs no tools: into water from anywhere, along and out of the surface from the water. */
    private void swim(Node cur) {
        Pos p = cur.pos;
        boolean swimmer = view.water(p.x(), p.y(), p.z()) || view.water(p.x(), p.y() - 1, p.z());
        for (int[] d : NEIGHBOURS) {
            int nx = p.x() + d[0], ny = p.y() + d[1], nz = p.z() + d[2];
            if (!clear(nx, ny, nz)) continue;
            boolean entering = view.water(nx, ny, nz);
            if (entering || (swimmer && view.water(nx, ny - 1, nz))) {
                relax(cur, new Pos(nx, ny, nz), SWIM, Move.SWIM);
            }
        }
    }

    /** Jump and place the block you were standing in. */
    private void pillar(Node cur) {
        Pos p = cur.pos;
        int x = p.x(), y = p.y(), z = p.z();
        if (cellCost(x, y + 1, z) != 0 || nextToLava(x, y, z)) return;
        double cost = cellCost(x, y + 2, z);
        if (cost == INF) return;
        relax(cur, new Pos(x, y + 1, z), PILLAR + cost, Move.PILLAR, breaksOf(x, y + 2, z), p);
    }

    private void relax(Node cur, Pos next, double cost, Move move) {
        relax(cur, next, cost, move, List.of(), null);
    }

    private void relax(Node cur, Pos next, double cost, Move move, List<Pos> breaks, Pos place) {
        relax(cur, next, cost, move, breaks, place, null);
    }

    private void relax(Node cur, Pos next, double cost, Move move, List<Pos> breaks, Pos place, Pos opens) {
        double g = cur.g + cost;
        Node n = nodes.get(next);
        if (n == null) {
            n = new Node(next, g, goal.estimate(next), cur);
            n.set(move, breaks, place, opens);
            nodes.put(next, n);
            open.add(new Entry(n, n.f()));
        } else if (!n.closed && g < n.g - 1e-9) {
            n.g = g;
            n.parent = cur;
            n.set(move, breaks, place, opens);
            open.add(new Entry(n, n.f()));
        }
    }

    /** Cost to make this cell passable: 0 if it already is, +inf if it cannot or must not be broken. */
    private double cellCost(int x, int y, int z) {
        if (view.dangerous(x, y, z)) return INF;
        if (view.passable(x, y, z)) return 0;
        if (!options.canBreak()) return INF;
        double seconds = view.breakSeconds(x, y, z);
        if (seconds == INF || nextToLava(x, y, z)) return INF;
        return 1 + BREAK_PER_SECOND * seconds;
    }

    private double breakCost(int... xyz) {
        double sum = 0;
        for (int i = 0; i < xyz.length; i += 3) sum += cellCost(xyz[i], xyz[i + 1], xyz[i + 2]);
        return sum;
    }

    private List<Pos> breaksOf(int... xyz) {
        List<Pos> out = new ArrayList<>();
        for (int i = 0; i < xyz.length; i += 3) {
            if (!view.passable(xyz[i], xyz[i + 1], xyz[i + 2])) out.add(new Pos(xyz[i], xyz[i + 1], xyz[i + 2]));
        }
        return out;
    }

    private boolean floorOk(int x, int y, int z) {
        return view.standable(x, y, z) && !view.dangerous(x, y, z);
    }

    /** Breaking or placing beside a fluid hazard could open a lava pocket. */
    private boolean nextToLava(int x, int y, int z) {
        for (int[] d : NEIGHBOURS) {
            int nx = x + d[0], ny = y + d[1], nz = z + d[2];
            if (view.dangerous(nx, ny, nz) && view.passable(nx, ny, nz)
                    && view.breakSeconds(nx, ny, nz) == INF) return true;
        }
        return false;
    }

    /** Body fits at (x, y, z): two passable, safe blocks. */
    private boolean clear(int x, int y, int z) {
        return view.passable(x, y, z) && view.passable(x, y + 1, z)
                && !view.dangerous(x, y, z) && !view.dangerous(x, y + 1, z);
    }

    private boolean canStand(int x, int y, int z) {
        return clear(x, y, z) && view.standable(x, y - 1, z) && !view.dangerous(x, y - 1, z);
    }

    private static List<PathStep> build(Node end) {
        List<PathStep> out = new ArrayList<>();
        for (Node n = end; n.parent != null; n = n.parent) out.add(new PathStep(n.pos, n.move, n.breaks, n.place, n.open));
        Collections.reverse(out);
        return out;
    }

    private void fail(String reason) {
        state = State.FAILED;
        failure = reason;
    }
}
