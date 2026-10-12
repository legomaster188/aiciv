package com.aiciv.testutil;

import com.aiciv.nav.BlockView;
import com.aiciv.nav.Pos;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** A hand-built block world: everything is air unless marked solid or dangerous. */
public final class GridView implements BlockView {
    private final Set<Pos> solid = new HashSet<>();
    private final Set<Pos> danger = new HashSet<>();
    private final Set<Pos> water = new HashSet<>();
    private final Map<Pos, Double> breakSeconds = new HashMap<>();

    public GridView floor(int x0, int x1, int y, int z0, int z1) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) solid.add(new Pos(x, y, z));
        return this;
    }

    public GridView solid(int x, int y, int z) {
        solid.add(new Pos(x, y, z));
        return this;
    }

    /** A dangerous block you can stand on, like magma. */
    public GridView magma(int x, int y, int z) {
        solid.add(new Pos(x, y, z));
        danger.add(new Pos(x, y, z));
        return this;
    }

    /** A dangerous block with no collision, like lava. */
    public GridView lava(int x, int y, int z) {
        solid.remove(new Pos(x, y, z));
        danger.add(new Pos(x, y, z));
        return this;
    }

    /** A solid block that can be broken in the given number of seconds. */
    public GridView breakable(int x, int y, int z, double seconds) {
        solid.add(new Pos(x, y, z));
        breakSeconds.put(new Pos(x, y, z), seconds);
        return this;
    }

    /** Water: no collision, not dangerous. */
    public GridView addWater(int x, int y, int z) {
        solid.remove(new Pos(x, y, z));
        water.add(new Pos(x, y, z));
        return this;
    }

    @Override public double breakSeconds(int x, int y, int z) {
        return breakSeconds.getOrDefault(new Pos(x, y, z), Double.POSITIVE_INFINITY);
    }
    @Override public boolean water(int x, int y, int z) { return water.contains(new Pos(x, y, z)); }
    @Override public boolean passable(int x, int y, int z) { return !solid.contains(new Pos(x, y, z)); }
    @Override public boolean standable(int x, int y, int z) { return solid.contains(new Pos(x, y, z)); }
    @Override public boolean dangerous(int x, int y, int z) { return danger.contains(new Pos(x, y, z)); }
}
