package com.aiciv.dimension;

import java.util.function.Predicate;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Dimension names and portal blocks, shared by every feature that crosses between dimensions. */
public final class Portals {
    /** Format for an action whose destination dimension is missing: {@code String.format(NO_LEVEL, "the_nether")}. */
    public static final String NO_LEVEL = "%s does not exist on this server";

    private Portals() {}

    /** The full dimension id, e.g. {@code minecraft:the_nether}; the key every per-dimension memory uses. */
    public static String key(Level level) {
        return level.dimension().identifier().toString();
    }

    /** {@code minecraft:the_nether} -> {@code the_nether}; a key without a namespace is returned as it is. */
    public static String shortName(String key) {
        int colon = key.indexOf(':');
        return colon < 0 ? key : key.substring(colon + 1);
    }

    public static boolean isPortal(BlockState s) {
        return s.is(Blocks.NETHER_PORTAL) || s.is(Blocks.END_PORTAL) || s.is(Blocks.END_GATEWAY);
    }

    /** Portal blocks of a kind: {@code nether}, {@code end} (end portal or gateway) or {@code any}; null for other kinds. */
    public static Predicate<BlockState> ofKind(String kind) {
        return switch (kind) {
            case "nether" -> s -> s.is(Blocks.NETHER_PORTAL);
            case "end" -> s -> s.is(Blocks.END_PORTAL) || s.is(Blocks.END_GATEWAY);
            case "any" -> Portals::isPortal;
            default -> null;
        };
    }
}
