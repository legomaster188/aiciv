package com.aiciv.autopilot;

import com.aiciv.actions.ActionFactory;
import com.aiciv.bot.AiPlayer;
import com.aiciv.perception.Perceiver;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Builds a {@link Situation} from a snapshot plus the inventory and the autopilot's memory. Server thread. */
public final class SituationCapture {
    private static final int HOSTILE_RANGE = 16;
    /** The module list never changes at run time, so the known actions are read once. */
    private static volatile Set<String> actions;

    private SituationCapture() {}

    public static Situation capture(AiPlayer p, SnapshotData data, boolean busy, String currentAction, AutopilotMemory mem,
                                    Tuning tuning, String pinnedGoal, long tick) {
        Map<String, Integer> inventory = new LinkedHashMap<>();
        int free = 0;
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (s.isEmpty()) {
                free++;
                continue;
            }
            inventory.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(), s.getCount(), Integer::sum);
        }
        String equipment = Perceiver.equipment(p);
        Set<String> worn = "none".equals(equipment) ? Set.of() : Set.of(equipment.split(", "));
        Set<String> tags = new HashSet<>();
        List<String> structures = List.of();
        for (SnapshotSection section : data.sections()) {
            if (section.tags() != null) tags.addAll(section.tags());
            if ("Structures".equals(section.label()) && section.items() != null) structures = section.items();
        }
        String holding = "nothing".equals(data.holding()) ? null : data.holding();
        return new Situation(data.name(), p.level().dimension().identifier().getPath(), data.x(), data.y(), data.z(),
                data.health(), data.food(), p.level().isDarkOutside(), tick, inventory, worn, holding, free,
                data.blocks(), data.players(), data.mobs(), Perceiver.hostileLabels(data.mobs(), HOSTILE_RANGE),
                data.remembered(), tags, structures, knownActions(), currentAction, busy,
                Set.copyOf(mem.flags()), new HashMap<>(mem.lastDone()), mem.requests(tick), tuning, pinnedGoal);
    }

    private static Set<String> knownActions() {
        Set<String> a = actions;
        if (a == null) actions = a = Set.copyOf(ActionFactory.knownKeys());
        return a;
    }
}
