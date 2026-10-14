package com.aiciv.autopilot.goal;

import com.aiciv.autopilot.Calls;
import com.aiciv.autopilot.Situation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Four pieces of iron armor, worn. */
final class GetIronArmor implements Goal {
    private static final Map<String, Integer> INGOTS = Map.of("helmet", 5, "chestplate", 8, "leggings", 7, "boots", 4);
    private static final List<String> ORDER = List.of("chestplate", "helmet", "leggings", "boots");

    private static final List<Step> STEPS = withPickaxes();

    /** The four armor steps; {@code optional} for the endgame goal, where armor is nice to have. */
    static List<Step> armorSteps(boolean optional) {
        List<Step> out = new ArrayList<>();
        for (String piece : ORDER) out.add(armorStep(piece, optional));
        return out;
    }

    private static List<Step> withPickaxes() {
        List<Step> out = new ArrayList<>(List.of(Steps.WOODEN_PICKAXE, Steps.STONE_PICKAXE));
        out.addAll(armorSteps(false));
        return List.copyOf(out);
    }

    private static Step armorStep(String piece, boolean optional) {
        String item = "iron_" + piece;
        int cost = INGOTS.get(piece);
        return Steps.of(item, s -> s.wornTier(piece) >= 3, s -> {
            int have = Costs.have("iron_ingot", s);
            if (s.count(item) > 0 || have >= cost) {
                return Move.act("put on iron armor", Calls.of("gear_up", "material", "iron", "shield", false));
            }
            return Gather.move("iron_ingot", cost - have, s);
        }, Set.of("gear_up", "mine", "smelt"), optional, null);
    }

    @Override public String id() { return "get_iron_armor"; }
    @Override public String summary() { return "get iron armor"; }
    @Override public List<Step> steps() { return STEPS; }

    @Override
    public double need(Situation s) {
        return 1 - s.wornAtLeast(3) / 4.0;
    }
}
