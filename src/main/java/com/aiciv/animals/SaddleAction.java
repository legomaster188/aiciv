package com.aiciv.animals;

import com.aiciv.bot.AiPlayer;
import com.aiciv.world.InventoryOps;
import java.util.List;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Puts a saddle on the nearest grown animal of one kind; horses, donkeys and mules have to be tame first. */
public final class SaddleAction extends InteractAction {
    public SaddleAction(String animal) {
        super(animal);
    }

    @Override
    protected String find(AiPlayer p) {
        EntityType<?> type = Animals.type(label);
        if (type == null) return "no animal called " + label;
        String kind = Animals.id(type);
        if (InventoryOps.count(p, s -> s.is(Items.SADDLE)) == 0) return "needs a saddle (you have none)";
        ItemStack saddle = new ItemStack(Items.SADDLE);
        List<Mob> grown = Animals.nearby(p, type, Mob.class, Animals.RADIUS, m -> !m.isBaby() && m.isEquippableInSlot(saddle, EquipmentSlot.SADDLE));
        if (grown.isEmpty()) return "no " + kind + " within 16 blocks that can wear a saddle";
        List<Mob> bare = grown.stream().filter(m -> !m.isSaddled()).toList();
        if (bare.isEmpty()) return "the " + kind + " is already saddled";
        target = bare.stream().filter(SaddleAction::mayBeSaddled).findFirst().orElse(null);
        if (target == null) return "tame the " + kind + " first";
        return null;
    }

    /** Camels and the other ridden animals take a saddle straight away; the horse family has to be tame. */
    private static boolean mayBeSaddled(Mob m) {
        return !(m instanceof AbstractHorse horse) || horse instanceof Camel || horse.isTamed();
    }

    @Override
    protected boolean hold(AiPlayer p) {
        return InventoryOps.selectBest(p, s -> s.is(Items.SADDLE) ? 1 : 0);
    }

    @Override
    protected String missing() {
        return "ran out of saddles";
    }

    @Override
    protected boolean done(AiPlayer p) {
        return ((Mob) target).isSaddled();
    }

    @Override
    protected String success(AiPlayer p) {
        return "saddled the " + Animals.id(target.getType());
    }

    @Override
    protected String verb() {
        return "saddle";
    }
}
