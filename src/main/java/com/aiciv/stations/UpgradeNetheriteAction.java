package com.aiciv.stations;

import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemNames;
import com.aiciv.world.MenuOps;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Upgrades a diamond tool or armor piece to netherite at a smithing table, enchantments and all. */
public final class UpgradeNetheriteAction extends StationAction<SmithingMenu> {
    static final String TEMPLATE = "netherite_upgrade_smithing_template";

    private final String diamond;
    private final String netherite;

    public UpgradeNetheriteAction(String item) {
        super("smithing_table", SmithingMenu.class);
        this.diamond = StationNames.normalise(item);
        this.netherite = StationNames.netheriteResult(item).orElseThrow(() -> new IllegalArgumentException(item + " has no netherite upgrade"));
    }

    private static Predicate<ItemStack> is(String id) {
        Item item = ItemNames.item(id);
        return s -> item != null && s.is(item);
    }

    @Override
    protected String missing(AiPlayer p) {
        List<String> lacking = new ArrayList<>();
        if (InventoryOps.count(p, is(diamond)) == 0) lacking.add(diamond);
        if (InventoryOps.count(p, is(TEMPLATE)) == 0) lacking.add(TEMPLATE);
        if (InventoryOps.count(p, s -> s.is(Items.NETHERITE_INGOT)) == 0) lacking.add("netherite_ingot");
        return lacking.isEmpty() ? null : "needs " + String.join(", ", lacking);
    }

    @Override
    protected ActionStatus work(BotContext ctx, AiPlayer p, SmithingMenu menu) {
        if (MenuOps.putInto(p, menu, is(TEMPLATE), SmithingMenu.TEMPLATE_SLOT, 1) == 0) return ActionStatus.failed("could not put the template in the smithing table");
        if (MenuOps.putInto(p, menu, is(diamond), SmithingMenu.BASE_SLOT, 1) == 0) return ActionStatus.failed("could not put " + diamond + " in the smithing table");
        if (MenuOps.putInto(p, menu, s -> s.is(Items.NETHERITE_INGOT), SmithingMenu.ADDITIONAL_SLOT, 1) == 0) return ActionStatus.failed("could not put the netherite_ingot in the smithing table");
        if (menu.getSlot(SmithingMenu.RESULT_SLOT).getItem().isEmpty()) return ActionStatus.failed("the smithing table shows no result");
        if (MenuOps.takeResult(p, menu, SmithingMenu.RESULT_SLOT).isEmpty()) return ActionStatus.failed("could not take the upgraded item");
        done = "upgraded " + diamond + " to " + netherite;
        return ActionStatus.DONE;
    }

    @Override
    public String describe() {
        return done != null ? done : "upgrade " + diamond + " to netherite";
    }
}
