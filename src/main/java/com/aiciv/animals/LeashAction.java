package com.aiciv.animals;

import com.aiciv.bot.AiPlayer;
import com.aiciv.world.InventoryOps;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;

/** Puts a lead on the nearest animal of one kind, so the bot is leading it. */
public final class LeashAction extends InteractAction {
    public LeashAction(String animal) {
        super(animal);
    }

    @Override
    protected String find(AiPlayer p) {
        EntityType<?> type = Animals.type(label);
        if (type == null) return "no animal called " + label;
        String kind = Animals.id(type);
        if (InventoryOps.count(p, s -> s.is(Items.LEAD)) == 0) return "needs a lead (you have none)";
        target = Animals.nearest(p, type, Mob.class, Animals.RADIUS, m -> m.canBeLeashed() && !m.isLeashed()).orElse(null);
        return target == null ? "no " + kind + " within 16 blocks that can be put on a lead" : null;
    }

    @Override
    protected boolean hold(AiPlayer p) {
        return InventoryOps.selectBest(p, s -> s.is(Items.LEAD) ? 1 : 0);
    }

    @Override
    protected String missing() {
        return "ran out of leads";
    }

    @Override
    protected boolean done(AiPlayer p) {
        Leashable l = (Leashable) target;
        return l.isLeashed() && l.getLeashHolder() == p;
    }

    @Override
    protected String success(AiPlayer p) {
        return "put a lead on the " + Animals.id(target.getType());
    }

    @Override
    protected String verb() {
        return "leash";
    }
}
