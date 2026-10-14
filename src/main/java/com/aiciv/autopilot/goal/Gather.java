package com.aiciv.autopilot.goal;

import com.aiciv.autopilot.Calls;
import com.aiciv.autopilot.Situation;
import java.util.List;
import java.util.Locale;

/** The move that gets a raw material (or the next stage toward it: mine, smelt, hunt). Pure. */
public final class Gather {
    private Gather() {}

    private static final List<String> DIRECTIONS = List.of("N", "E", "S", "W");

    /** A direction that changes every half minute, so a search does not keep walking the same way. */
    public static String direction(Situation s) {
        return DIRECTIONS.get((int) Math.floorMod(s.tick() / 600 + s.name().hashCode(), 4L));
    }

    public static Move move(String material, int n, Situation s) {
        return switch (material) {
            case "log" -> Move.gather("need " + n + " more log", Calls.of("mine", "block", "log", "count", Math.max(n, 3)));
            case "cobblestone" -> s.toolTier("pickaxe") < 1 ? Move.blocked("needs a pickaxe for cobblestone")
                    : Move.gather("need " + n + " more cobblestone", Calls.of("mine", "block", "stone", "count", n));
            case "coal" -> s.toolTier("pickaxe") < 1 ? Move.blocked("needs a pickaxe for coal")
                    : Move.gather("need " + n + " more coal", Calls.of("mine", "block", "coal_ore", "count", n));
            case "raw_iron" -> s.toolTier("pickaxe") < 2 ? Move.blocked("needs a stone pickaxe for iron")
                    : Move.gather("need " + n + " more raw iron", Calls.of("mine", "block", "iron_ore", "count", n));
            case "iron_ingot" -> ingots(n, s);
            case "diamond" -> diamonds(n, s);
            case "blaze_rod" -> Move.act("get " + n + " blaze rods", Calls.of("attack", "target", "blaze"), Calls.of("collect", "item", "blaze_rod"));
            case "ender_pearl" -> Move.act("get " + n + " ender pearls", Calls.of("attack", "target", "enderman"), Calls.of("collect", "item", "ender_pearl"));
            case "wool" -> s.mobCount("sheep", 24) >= 1
                    ? Move.act("kill a sheep for wool", Calls.of("attack", "target", "sheep"), Calls.of("collect", "item", "white_wool"))
                    : Move.gather("look for sheep", Calls.of("explore", "direction", direction(s), "distance", s.tuning().exploreDistance()));
            default -> Move.blocked("don't know how to get " + material.toLowerCase(Locale.ROOT));
        };
    }

    private static Move ingots(int n, Situation s) {
        int raw = s.count("raw_iron");
        if (raw < n) return move("raw_iron", n - raw, s);
        if (!s.furnaceNear() && Costs.have("cobblestone", s) < 8) return move("cobblestone", 8 - Costs.have("cobblestone", s), s);
        int fuelNeeded = (int) Math.ceil(n / 8.0);
        boolean fuel = s.count("coal") + s.count("charcoal") >= fuelNeeded || s.logs() >= n;
        if (!fuel) return move("coal", fuelNeeded, s);
        return Move.act("smelt " + n + " raw_iron", Calls.of("smelt", "item", "raw_iron", "count", n));
    }

    private static Move diamonds(int n, Situation s) {
        if (s.toolTier("pickaxe") < 3) return Move.blocked("needs an iron pickaxe for diamonds");
        if (!s.knows("diamond_ore") && s.y() > 16) {
            return Move.gather("dig down toward diamond level", Calls.of("goTo", "x", s.x() + 8, "y", Math.max(-54, s.y() - 40), "z", s.z()));
        }
        return Move.gather("need " + n + " more diamonds", Calls.of("mine", "block", "diamond_ore", "count", n));
    }
}
