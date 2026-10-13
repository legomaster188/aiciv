package com.aiciv.actions;

import com.aiciv.bot.AiPlayer;
import com.aiciv.world.InventoryOps;
import java.util.Locale;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

/** Wears armor (or a shield) in the right slot, or puts any other item in the selected hotbar slot. Takes one tick. */
public final class EquipAction implements Action {
    private final String item;

    public EquipAction(String item) {
        String q = item.trim().toLowerCase(Locale.ROOT);
        if (q.startsWith("minecraft:")) q = q.substring("minecraft:".length());
        this.item = q.replaceAll("\\s+", "_");
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer player = ctx.player();
        ItemStack found = find(player);
        if (found == null) {
            // Auto-wear may have put it on already; that is what was asked for.
            return alreadyWorn(player) ? ActionStatus.DONE : ActionStatus.failed("no " + item + " in inventory");
        }
        Equippable equippable = found.get(DataComponents.EQUIPPABLE);
        if (equippable != null && (equippable.slot().isArmor() || equippable.slot() == EquipmentSlot.OFFHAND)) {
            EquipmentSlot slot = equippable.slot();
            ItemStack one = found.split(1);
            ItemStack old = player.getItemBySlot(slot);
            player.setItemSlot(slot, one);
            if (!old.isEmpty()) InventoryOps.addOrDrop(player, old);
            return ActionStatus.DONE;
        }
        String wanted = idOf(found);
        InventoryOps.selectBest(player, s -> idOf(s).equals(wanted) ? 1 : 0);
        return ActionStatus.DONE;
    }

    private boolean alreadyWorn(AiPlayer player) {
        String singular = item.endsWith("s") ? item.substring(0, item.length() - 1) : item;
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND}) {
            ItemStack worn = player.getItemBySlot(slot);
            if (worn.isEmpty()) continue;
            String id = idOf(worn);
            if (id.equals(item) || id.equals(singular) || id.endsWith("_" + item)) return true;
        }
        return false;
    }

    /** A held stack the name refers to: exact id, a plural, or a family word ("pickaxe" for stone_pickaxe). */
    private ItemStack find(AiPlayer player) {
        String singular = item.endsWith("s") ? item.substring(0, item.length() - 1) : item;
        ItemStack family = null;
        for (ItemStack s : player.getInventory().getNonEquipmentItems()) {
            if (s.isEmpty()) continue;
            String id = idOf(s);
            if (id.equals(item) || id.equals(singular)) return s;
            if (family == null && id.endsWith("_" + item)) family = s;
        }
        return family;
    }

    private static String idOf(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    @Override
    public String describe() {
        return "equip " + item;
    }

    @Override
    public int timeoutTicks() {
        return 20;
    }
}
