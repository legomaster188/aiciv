package com.aiciv.utility;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.Finder;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemNames;
import com.aiciv.world.MenuOps;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/** Enchants a held item at a table with lapis and experience levels, the way the screen does. */
public final class EnchantAction implements Action {
    static final int FIND_RADIUS = 16;
    static final double REACH = 4.0;

    private final String query;
    private final int option;
    private BlockPos table;
    private Approach approach;
    private String done;

    public EnchantAction(String item, int option) {
        this.query = item.trim();
        this.option = Math.max(1, Math.min(3, option));
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (approach == null) {
            ActionStatus problem = plan(ctx, p);
            if (problem != null) return problem;
            approach = Approach.block(table, REACH);
        }
        Approach.Status s = approach.tick(ctx);
        if (s.state() == Approach.State.RUNNING) return ActionStatus.RUNNING;
        if (s.state() == Approach.State.FAILED) return ActionStatus.failed(s.reason());
        return enchant(ctx, p);
    }

    /** Null when everything needed is at hand; otherwise the failure. */
    private ActionStatus plan(BotContext ctx, AiPlayer p) {
        Optional<BlockPos> found = Finder.nearestBlock(ctx, id -> id.equals("enchanting_table"), FIND_RADIUS);
        if (found.isEmpty()) return ActionStatus.failed("no enchanting table within " + FIND_RADIUS + " blocks");
        table = found.get();
        Predicate<ItemStack> match = ItemNames.matcher(query);
        boolean held = false;
        boolean enchantable = false;
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (s.isEmpty() || !match.test(s)) continue;
            held = true;
            if (s.isEnchantable()) enchantable = true;
        }
        if (!enchantable) return ActionStatus.failed(query + " can't be enchanted" + (held ? "" : " (you don't hold one)"));
        int lapis = InventoryOps.count(p, s -> s.is(Items.LAPIS_LAZULI));
        if (lapis < option) return ActionStatus.failed("needs " + option + " lapis_lazuli for option " + option + "; you have " + lapis);
        return null;
    }

    private ActionStatus enchant(BotContext ctx, AiPlayer p) {
        EnchantmentMenu menu = MenuOps.open(p, table, EnchantmentMenu.class);
        if (menu == null) return ActionStatus.failed("could not open the enchanting table");
        try {
            Predicate<ItemStack> match = ItemNames.matcher(query);
            if (!moveToSlot(p, menu, s -> match.test(s) && s.isEnchantable())) return ActionStatus.failed(query + " can't be enchanted");
            if (!moveToSlot(p, menu, s -> s.is(Items.LAPIS_LAZULI))) return ActionStatus.failed("could not put lapis_lazuli in the table");
            String itemId = ItemNames.id(menu.getSlot(0).getItem());
            ctx.event("enchant options: " + options(menu, p.level()));
            int cost = menu.costs[option - 1];
            if (cost == 0) return ActionStatus.failed("option " + option + " is not available (put bookshelves around the table for more)");
            if (p.experienceLevel < cost) return ActionStatus.failed("option " + option + " needs level " + cost + "; you have " + p.experienceLevel);
            int levelsBefore = p.experienceLevel;
            int lapisBefore = menu.getSlot(1).getItem().getCount() + InventoryOps.count(p, s -> s.is(Items.LAPIS_LAZULI));
            if (!menu.clickMenuButton(p, option - 1)) return ActionStatus.failed("the table refused option " + option);
            if (!EnchantmentHelper.hasAnyEnchantments(menu.getSlot(0).getItem())) return ActionStatus.failed("the enchantment did not take");
            int levels = levelsBefore - p.experienceLevel;
            int lapisUsed = lapisBefore - menu.getSlot(1).getItem().getCount() - InventoryOps.count(p, s -> s.is(Items.LAPIS_LAZULI));
            done = "enchanted " + itemId + " (cost " + levels + " level" + (levels == 1 ? "" : "s") + " and " + lapisUsed + " lapis)";
            return ActionStatus.DONE;
        } finally {
            // Anything still in the table goes back through the menu, then the screen closes.
            for (int slot = 0; slot <= 1; slot++) {
                if (!menu.getSlot(slot).getItem().isEmpty()) menu.clicked(slot, 0, ContainerInput.QUICK_MOVE, p);
            }
            MenuOps.close(p);
        }
    }

    /** Shift-clicks the first inventory stack that matches into the table. */
    private static boolean moveToSlot(AiPlayer p, EnchantmentMenu menu, Predicate<ItemStack> match) {
        List<ItemStack> inv = p.getInventory().getNonEquipmentItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || !match.test(s)) continue;
            OptionalInt slot = MenuOps.inventorySlot(menu, p, i);
            if (slot.isEmpty()) continue;
            int before = s.getCount();
            menu.clicked(slot.getAsInt(), 0, ContainerInput.QUICK_MOVE, p);
            ItemStack after = p.getInventory().getItem(i);
            if (after.isEmpty() || after.getCount() < before) return true;
        }
        return false;
    }

    /** "1: 3 levels, 2: 6 levels, 3: 9 levels", with the clue enchantment when the table shows one. */
    private static String options(EnchantmentMenu menu, ServerLevel level) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            if (i > 0) out.append(", ");
            out.append(i + 1).append(": ").append(menu.costs[i]).append(" levels");
            String clue = menu.enchantClue[i] >= 0 ? clueName(level, menu.enchantClue[i]) : null;
            if (clue != null) out.append(" (").append(clue).append(')');
        }
        return out.toString();
    }

    private static String clueName(ServerLevel level, int id) {
        try {
            Registry<Enchantment> registry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            Optional<Holder.Reference<Enchantment>> holder = registry.get(id);
            return holder.map(h -> h.value().description().getString()).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void cancel(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
        AiPlayer p = ctx.player();
        if (p != null && p.containerMenu instanceof EnchantmentMenu) MenuOps.close(p);
    }

    @Override
    public void interrupted(BotContext ctx) {
        if (approach != null && table != null) approach = Approach.block(table, REACH);
    }

    @Override
    public String describe() {
        return done != null ? done : "enchant " + query + " " + option;
    }

    @Override
    public int timeoutTicks() {
        return 60 * 20;
    }
}
