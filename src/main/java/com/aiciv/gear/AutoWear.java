package com.aiciv.gear;

import com.aiciv.actions.EquipAction;
import com.aiciv.bot.AiPlayer;
import com.aiciv.bot.Bot;
import com.aiciv.craft.CraftExecutor;
import com.aiciv.gear.GearPlan.Job;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;

/**
 * Puts on armor the bot carries when it beats what is worn, like a player glancing at their inventory. Looks every
 * {@value #INTERVAL_TICKS} ticks, wears at most one piece each time, and keeps its hands off while a menu is open, an
 * item is in use, the arm is swinging, or the brain's own gear_up is running. Ticked by the reflex controller only when
 * no reflex is active.
 */
public final class AutoWear {
    static final int INTERVAL_TICKS = 10;

    private static final String ELYTRA = "elytra";

    private int lastLook = Integer.MIN_VALUE / 2;

    public void tick(Bot b) {
        AiPlayer p = b.player;
        if (p.tickCount - lastLook < INTERVAL_TICKS) return;
        if (p.containerMenu != p.inventoryMenu || p.isUsingItem() || p.isSwinging() || b.executor.upNext(GearUpAction.class)) return;
        // Mid-glide nothing is swapped, and a worn elytra is never traded for a chestplate: the bot chose to fly.
        if (p.isFallFlying()) return;
        lastLook = p.tickCount;
        Map<String, Integer> inventory = CraftExecutor.inventoryCounts(p);
        Map<GearPlan.Slot, String> worn = Armor.worn(p);
        boolean elytra = p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
        if (elytra) worn.put(GearPlan.Slot.CHEST, ELYTRA);
        ToDoubleFunction<String> score = id -> id.equals(ELYTRA) ? Double.MAX_VALUE : Armor.score(id);
        Job job = GearPlan.next(worn, inventory, List.of(), id -> false, score, Set.of());
        String item = job != null ? job.item()
                : p.getOffhandItem().isEmpty() && inventory.getOrDefault(GearUpAction.SHIELD, 0) > 0 ? GearUpAction.SHIELD : null;
        if (item == null) return;
        new EquipAction(item).tick(b.context);
        b.context.event("put on " + item);
    }
}
