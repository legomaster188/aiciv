package com.aiciv.stations;

import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemNames;
import com.aiciv.world.MenuOps;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Cuts a held block into another shape (stone into stone_bricks, planks into slabs...) at a stonecutter. */
public final class StonecutAction extends StationAction<StonecutterMenu> {
    private final String input;
    private final String output;
    private final int count;

    public StonecutAction(String input, String output, int count) {
        super("stonecutter", StonecutterMenu.class);
        this.input = input.trim();
        this.output = output.trim();
        this.count = Math.max(1, Math.min(64, count));
    }

    @Override
    protected String missing(AiPlayer p) {
        return InventoryOps.count(p, ItemNames.matcher(input)) == 0 ? "needs " + input + " (you have none)" : null;
    }

    @Override
    protected ActionStatus work(BotContext ctx, AiPlayer p, StonecutterMenu menu) {
        Item wanted = ItemNames.item(output);
        if (wanted == null) return ActionStatus.failed("no item called " + output + " (did you mean " + String.join(", ", ItemNames.suggestions(output, 3)) + ")");
        Predicate<ItemStack> in = ItemNames.matcher(input);
        int want = Math.min(count, InventoryOps.count(p, in));
        if (MenuOps.putInto(p, menu, in, StonecutterMenu.INPUT_SLOT, want) == 0) return ActionStatus.failed("could not put " + input + " in the stonecutter");
        int chosen = -1;
        Set<String> offers = new LinkedHashSet<>();
        for (int i = 0; i < menu.getNumberOfVisibleRecipes() && chosen < 0; i++) {
            menu.clickMenuButton(p, i);
            ItemStack shown = menu.getSlot(StonecutterMenu.RESULT_SLOT).getItem();
            if (shown.isEmpty()) continue;
            if (shown.is(wanted)) chosen = i;
            else offers.add(ItemNames.id(shown));
        }
        if (chosen < 0) {
            return ActionStatus.failed("the stonecutter can't make " + output + " from " + input + "; it offers: " + (offers.isEmpty() ? "nothing" : String.join(", ", offers)));
        }
        int made = 0;
        while (made < count) {
            ItemStack taken = MenuOps.takeResult(p, menu, StonecutterMenu.RESULT_SLOT);
            if (taken.isEmpty()) break; // the input ran out
            made += taken.getCount();
        }
        if (made == 0) return ActionStatus.failed("the stonecutter gave nothing");
        done = "cut " + made + " " + ItemNames.id(new ItemStack(wanted));
        return ActionStatus.DONE;
    }

    @Override
    public String describe() {
        return done != null ? done : "stonecut " + input + " into " + output + " x" + count;
    }

    @Override
    public int timeoutTicks() {
        return 1200;
    }
}
