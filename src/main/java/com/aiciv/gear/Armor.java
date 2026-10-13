package com.aiciv.gear;

import com.aiciv.bot.AiPlayer;
import com.aiciv.world.ItemNames;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

/** Reads armor from the game: how good a piece is and what a bot wears. Server thread. */
public final class Armor {
    private Armor() {}

    /** Armor points plus toughness from the item's own attribute modifiers; -1 for an id that is not armor. */
    public static double score(String itemId) {
        Item item = ItemNames.item(itemId);
        if (item == null) return -1;
        ItemStack stack = new ItemStack(item);
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable == null || !equippable.slot().isArmor()) return -1;
        double[] total = {0};
        stack.forEachModifier(equippable.slot(), (attribute, modifier) -> {
            if (attribute.is(Attributes.ARMOR) || attribute.is(Attributes.ARMOR_TOUGHNESS)) total[0] += modifier.amount();
        });
        return total[0];
    }

    public static Map<GearPlan.Slot, String> worn(AiPlayer p) {
        Map<GearPlan.Slot, String> out = new EnumMap<>(GearPlan.Slot.class);
        put(out, GearPlan.Slot.HEAD, p.getItemBySlot(EquipmentSlot.HEAD));
        put(out, GearPlan.Slot.CHEST, p.getItemBySlot(EquipmentSlot.CHEST));
        put(out, GearPlan.Slot.LEGS, p.getItemBySlot(EquipmentSlot.LEGS));
        put(out, GearPlan.Slot.FEET, p.getItemBySlot(EquipmentSlot.FEET));
        return out;
    }

    private static void put(Map<GearPlan.Slot, String> into, GearPlan.Slot slot, ItemStack stack) {
        if (!stack.isEmpty()) into.put(slot, ItemNames.id(stack));
    }
}
