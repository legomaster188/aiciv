package com.aiciv.storage;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.Finder;
import com.aiciv.world.ItemNames;
import com.aiciv.world.MenuOps;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/** Names one item at an anvil, paying the experience levels the anvil asks for. */
public final class RenameAction implements Action {
    static final int RADIUS = 16;
    private static final double REACH = 4.0;

    private enum Phase { FIND, WALK, OPENED, WORK }

    private final String item;
    private final String name;
    private Phase phase = Phase.FIND;
    private BlockPos anvil;
    private Approach approach;
    private AnvilMenu menu;
    private String doneSummary;

    public RenameAction(String item, String name) {
        this.item = item;
        this.name = name;
    }

    static boolean isAnvil(String blockId) {
        return blockId.equals("anvil") || blockId.equals("chipped_anvil") || blockId.equals("damaged_anvil");
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        switch (phase) {
            case FIND: {
                if (p.getInventory().getNonEquipmentItems().stream().noneMatch(s -> !s.isEmpty() && ItemNames.matcher(item).test(s))) {
                    return ActionStatus.failed("no " + item + " in inventory");
                }
                anvil = Finder.nearestBlock(ctx, RenameAction::isAnvil, RADIUS).orElse(null);
                if (anvil == null) return ActionStatus.failed("no anvil within " + RADIUS + " blocks");
                approach = Approach.block(anvil, REACH);
                phase = Phase.WALK;
                return ActionStatus.RUNNING;
            }
            case WALK: {
                Approach.Status s = approach.tick(ctx);
                if (s.state() == Approach.State.RUNNING) return ActionStatus.RUNNING;
                approach = null;
                if (s.state() == Approach.State.FAILED) return ActionStatus.failed(s.reason());
                menu = MenuOps.open(p, anvil, AnvilMenu.class);
                if (menu == null) return ActionStatus.failed("the anvil won't open");
                phase = Phase.OPENED;
                return ActionStatus.RUNNING;
            }
            case OPENED: {
                if (p.containerMenu != menu) return fail(ctx, "the anvil closed");
                phase = Phase.WORK;
                return ActionStatus.RUNNING;
            }
            default:
                return work(ctx, p);
        }
    }

    private ActionStatus work(BotContext ctx, AiPlayer p) {
        if (p.containerMenu != menu || !menu.stillValid(p)) return fail(ctx, "the anvil closed");
        Predicate<ItemStack> match = s -> ItemNames.matcher(item).test(s) && !hasExactName(s);
        if (MenuOps.moveIn(p, menu, match, 1) < 1) return fail(ctx, "no " + item + " that is not already called \"" + name + "\"");
        ItemStack input = menu.getSlot(AnvilMenu.INPUT_SLOT).getItem();
        String id = ItemNames.id(input);
        menu.setItemName(name);
        int cost = menu.getCost();
        if (!menu.getSlot(AnvilMenu.RESULT_SLOT).hasItem()) {
            return fail(ctx, cost >= 40 ? "renaming is too expensive (" + cost + " levels)" : "the anvil gave no result for " + id);
        }
        if (p.experienceLevel < cost) {
            return fail(ctx, "renaming costs " + levels(cost) + "; you have " + levels(p.experienceLevel));
        }
        int before = p.experienceLevel;
        menu.clicked(AnvilMenu.RESULT_SLOT, 0, ContainerInput.QUICK_MOVE, p);
        boolean named = p.getInventory().getNonEquipmentItems().stream().anyMatch(s -> !s.isEmpty() && hasExactName(s));
        MenuOps.close(p);
        if (!named) return ActionStatus.failed("could not take the renamed " + id + " (is the inventory full?)");
        doneSummary = "renamed " + id + " to \"" + name + "\" for " + levels(before - p.experienceLevel);
        return ActionStatus.DONE;
    }

    private boolean hasExactName(ItemStack s) {
        Component custom = s.get(DataComponents.CUSTOM_NAME);
        return custom != null && custom.getString().equals(name) && ItemNames.matcher(item).test(s);
    }

    private static String levels(int n) {
        return n + (n == 1 ? " level" : " levels");
    }

    private ActionStatus fail(BotContext ctx, String reason) {
        close(ctx);
        return ActionStatus.failed(reason);
    }

    private void close(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
        approach = null;
        menu = null;
        if (ctx.player() != null) MenuOps.close(ctx.player());
    }

    @Override
    public void cancel(BotContext ctx) {
        close(ctx);
    }

    @Override
    public void interrupted(BotContext ctx) {
        close(ctx);
        phase = Phase.FIND;
    }

    @Override
    public String describe() {
        return doneSummary != null ? doneSummary : "rename " + item + " to \"" + name + "\"";
    }

    @Override
    public int timeoutTicks() {
        return 60 * 20;
    }
}
