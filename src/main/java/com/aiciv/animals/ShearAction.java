package com.aiciv.animals;

import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.InventoryOps;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Shears the nearest sheep, mooshroom or snow golem that is ready, then picks up the wool, mushrooms or pumpkin. */
public final class ShearAction extends InteractAction {
    static final List<String> SHEARABLE = List.of("sheep", "mooshroom", "snow_golem");
    static final double COLLECT_RADIUS = 6;
    static final int COLLECT_TICKS = 80;

    private Map<String, Integer> before;
    private DropCollector collector;
    private AiPlayer bot;

    public ShearAction(String animalOrNull) {
        super(animalOrNull == null || animalOrNull.isBlank() ? "sheep" : animalOrNull.trim());
    }

    @Override
    protected String find(AiPlayer p) {
        EntityType<?> type = Animals.type(label);
        String kind = type == null ? label : Animals.id(type);
        if (type == null || !SHEARABLE.contains(kind)) return "cannot shear a " + label + " (shear " + String.join(", ", SHEARABLE) + ")";
        if (InventoryOps.count(p, s -> s.is(Items.SHEARS)) == 0) return "needs shears (you have none)";
        target = Animals.nearest(p, type, Entity.class, Animals.RADIUS, e -> e instanceof Shearable s && s.readyForShearing()).orElse(null);
        if (target == null) return "no " + kind + " ready for shearing within 16 blocks";
        bot = p;
        before = inventory(p);
        return null;
    }

    @Override
    protected boolean hold(AiPlayer p) {
        return InventoryOps.selectBest(p, s -> s.is(Items.SHEARS) ? 1 : 0);
    }

    @Override
    protected String missing() {
        return "the shears are gone";
    }

    @Override
    protected boolean done(AiPlayer p) {
        return !((Shearable) target).readyForShearing();
    }

    @Override
    protected String success(AiPlayer p) {
        return "sheared the " + Animals.id(target.getType());
    }

    @Override
    protected void onDone(BotContext ctx) {
        collector = new DropCollector(s -> true, COLLECT_RADIUS, COLLECT_TICKS);
    }

    @Override
    protected boolean afterwards(BotContext ctx) {
        return collector.tick(ctx);
    }

    @Override
    protected void stopAfterwards(BotContext ctx) {
        if (collector != null) collector.stop(ctx);
    }

    @Override
    protected String summary() {
        Map<String, Integer> now = inventory(bot);
        List<String> got = new ArrayList<>();
        for (Map.Entry<String, Integer> e : now.entrySet()) {
            int gained = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (gained > 0) got.add(gained + " " + e.getKey());
        }
        return super.summary() + (got.isEmpty() ? "" : " (got " + String.join(", ", got) + ")");
    }

    private static Map<String, Integer> inventory(AiPlayer p) {
        Map<String, Integer> out = new HashMap<>();
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty()) out.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(), s.getCount(), Integer::sum);
        }
        return out;
    }

    @Override
    protected String verb() {
        return "shear";
    }
}
