package com.aiciv.stations;

import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.ItemNames;
import com.aiciv.world.MenuOps;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Takes the enchantments off one held item, or joins two worn copies of a tool into one, at a grindstone. */
public final class GrindstoneAction extends StationAction<GrindstoneMenu> {
    private final String query;
    private final boolean repair;
    private Item repairItem;

    public GrindstoneAction(String item, boolean repair) {
        super("grindstone", GrindstoneMenu.class);
        this.query = item.trim();
        this.repair = repair;
    }

    private static boolean enchanted(ItemStack s) {
        if (s.isEnchanted()) return true;
        var stored = s.get(DataComponents.STORED_ENCHANTMENTS);
        return stored != null && !stored.isEmpty();
    }

    @Override
    protected String missing(AiPlayer p) {
        Predicate<ItemStack> named = ItemNames.matcher(query);
        if (!repair) {
            for (ItemStack s : p.getInventory().getNonEquipmentItems()) if (!s.isEmpty() && named.test(s) && enchanted(s)) return null;
            return "you hold no enchanted " + query;
        }
        Map<Item, Integer> damaged = new HashMap<>();
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && named.test(s) && s.isDamaged()) damaged.merge(s.getItem(), 1, Integer::sum);
        }
        for (Map.Entry<Item, Integer> e : damaged.entrySet()) {
            if (e.getValue() >= 2) {
                repairItem = e.getKey();
                return null;
            }
        }
        return "repair needs two " + query + " (damaged); you have " + damaged.values().stream().mapToInt(Integer::intValue).sum();
    }

    @Override
    protected ActionStatus work(BotContext ctx, AiPlayer p, GrindstoneMenu menu) {
        if (repair) return repair(p, menu);
        Predicate<ItemStack> named = ItemNames.matcher(query);
        if (MenuOps.putInto(p, menu, s -> named.test(s) && enchanted(s), GrindstoneMenu.INPUT_SLOT, 1) == 0) {
            return ActionStatus.failed("could not put the " + query + " in the grindstone");
        }
        if (menu.getSlot(GrindstoneMenu.RESULT_SLOT).getItem().isEmpty()) return ActionStatus.failed("the grindstone shows no result");
        ItemStack taken = MenuOps.takeResult(p, menu, GrindstoneMenu.RESULT_SLOT); // taking it drops the experience orbs
        if (taken.isEmpty()) return ActionStatus.failed("could not take the item from the grindstone");
        done = "removed enchantments from " + ItemNames.id(taken);
        return ActionStatus.DONE;
    }

    private ActionStatus repair(AiPlayer p, GrindstoneMenu menu) {
        Predicate<ItemStack> worn = s -> s.is(repairItem) && s.isDamaged();
        if (MenuOps.putInto(p, menu, worn, GrindstoneMenu.INPUT_SLOT, 1) == 0
                || MenuOps.putInto(p, menu, worn, GrindstoneMenu.ADDITIONAL_SLOT, 1) == 0) {
            return ActionStatus.failed("could not put two " + query + " in the grindstone");
        }
        if (menu.getSlot(GrindstoneMenu.RESULT_SLOT).getItem().isEmpty()) return ActionStatus.failed("the grindstone shows no result");
        ItemStack taken = MenuOps.takeResult(p, menu, GrindstoneMenu.RESULT_SLOT);
        if (taken.isEmpty()) return ActionStatus.failed("could not take the item from the grindstone");
        int percent = taken.getMaxDamage() == 0 ? 100 : 100 * (taken.getMaxDamage() - taken.getDamageValue()) / taken.getMaxDamage();
        done = "repaired two " + ItemNames.id(taken) + " into one (" + percent + "% durability)";
        return ActionStatus.DONE;
    }

    @Override
    public String describe() {
        return done != null ? done : (repair ? "repair " : "disenchant ") + query;
    }
}
