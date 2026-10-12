package com.aiciv.testutil;

import com.aiciv.nav.BlockView;
import com.aiciv.nav.Pos;
import java.util.HashSet;
import java.util.Set;

/** A hand-built block world: everything is air unless marked solid or dangerous. */
public final class GridView implements BlockView {
    private final Set<Pos> solid = new HashSet<>();
    private final Set<Pos> danger = new HashSet<>();

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

    @Override public boolean passable(int x, int y, int z) { return !solid.contains(new Pos(x, y, z)); }
    @Override public boolean standable(int x, int y, int z) { return solid.contains(new Pos(x, y, z)); }
    @Override public boolean dangerous(int x, int y, int z) { return danger.contains(new Pos(x, y, z)); }
}
