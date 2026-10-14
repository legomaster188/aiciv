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
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Puts an armor trim on a held armor piece at a smithing table: a trim template, the armor and a trim material. */
public final class TrimArmorAction extends StationAction<SmithingMenu> {
    private final String query;
    private final String template;
    private final String material;

    /** {@code template} and {@code material} are item ids (see {@link StationNames}); {@code item} names the armor. */
    public TrimArmorAction(String item, String template, String material) {
        super("smithing_table", SmithingMenu.class);
        this.query = item.trim();
        this.template = template;
        this.material = material;
    }

    private Predicate<ItemStack> armor() {
        Predicate<ItemStack> named = ItemNames.matcher(query);
        return s -> named.test(s) && s.is(ItemTags.TRIMMABLE_ARMOR);
    }

    private static Predicate<ItemStack> is(String id) {
        Item item = ItemNames.item(id);
        return s -> item != null && s.is(item);
    }

    @Override
    protected String missing(AiPlayer p) {
        List<String> lacking = new ArrayList<>();
        if (InventoryOps.count(p, armor()) == 0) lacking.add(query + " (armor in your inventory, not worn)");
        if (InventoryOps.count(p, is(template)) == 0) lacking.add(template);
        if (InventoryOps.count(p, is(material)) == 0) lacking.add(material);
        return lacking.isEmpty() ? null : "needs " + String.join(", ", lacking);
    }

    @Override
    protected ActionStatus work(BotContext ctx, AiPlayer p, SmithingMenu menu) {
        if (MenuOps.putInto(p, menu, is(template), SmithingMenu.TEMPLATE_SLOT, 1) == 0) return ActionStatus.failed("could not put " + template + " in the smithing table");
        if (MenuOps.putInto(p, menu, armor(), SmithingMenu.BASE_SLOT, 1) == 0) return ActionStatus.failed("could not put " + query + " in the smithing table");
        if (MenuOps.putInto(p, menu, is(material), SmithingMenu.ADDITIONAL_SLOT, 1) == 0) return ActionStatus.failed("could not put " + material + " in the smithing table");
        if (menu.getSlot(SmithingMenu.RESULT_SLOT).getItem().isEmpty()) return ActionStatus.failed("the smithing table shows no result (is it already trimmed that way?)");
        ItemStack taken = MenuOps.takeResult(p, menu, SmithingMenu.RESULT_SLOT);
        if (taken.isEmpty() || !taken.has(DataComponents.TRIM)) return ActionStatus.failed("the smithing table gave no trim");
        done = "trimmed " + ItemNames.id(taken) + " with " + template.replace("_armor_trim_smithing_template", "") + " and " + material;
        return ActionStatus.DONE;
    }

    @Override
    public String describe() {
        return done != null ? done : "trim " + query + " with " + template + " and " + material;
    }
}
