package com.aiciv.stations;

import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.MenuOps;
import java.util.function.Predicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/** Zooms out, copies or locks a held map at a cartography table. */
public final class CartographyAction extends StationAction<CartographyTableMenu> {
    public static final String ACTIONS = "zoom, copy or lock";

    private final String action;
    private final String extraName;

    /** {@code action} is zoom, copy or lock (the factory has checked it). */
    public CartographyAction(String action) {
        super("cartography_table", CartographyTableMenu.class);
        this.action = action;
        switch (action) {
            case "zoom" -> extraName = "paper";
            case "copy" -> extraName = "map";
            case "lock" -> extraName = "glass_pane";
            default -> throw new IllegalArgumentException("cartography action must be " + ACTIONS);
        }
    }

    /** Looked up late: items need the game registries, which unit tests do not have. */
    private Item extra() {
        return switch (action) {
            case "zoom" -> Items.PAPER;
            case "copy" -> Items.MAP;
            default -> Items.GLASS_PANE;
        };
    }

    @Override
    protected String missing(AiPlayer p) {
        String lacking = "";
        if (InventoryOps.count(p, s -> s.is(Items.FILLED_MAP)) == 0) lacking = "a filled map";
        if (InventoryOps.count(p, s -> s.is(extra())) == 0) lacking += (lacking.isEmpty() ? "" : " and ") + extraName;
        return lacking.isEmpty() ? null : "needs " + lacking + " to " + action;
    }

    @Override
    protected ActionStatus work(BotContext ctx, AiPlayer p, CartographyTableMenu menu) {
        ItemStack held = null;
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && s.is(Items.FILLED_MAP) && (held == null || better(p, s, held))) held = s;
        }
        MapId id = held.get(DataComponents.MAP_ID);
        MapItemSavedData data = MapItem.getSavedData(held, p.level());
        int scale = data == null ? 0 : data.scale;
        ItemStack chosen = held.copy();
        Predicate<ItemStack> sameMap = s -> s.is(Items.FILLED_MAP) && java.util.Objects.equals(s.get(DataComponents.MAP_ID), chosen.get(DataComponents.MAP_ID));
        if (MenuOps.putInto(p, menu, sameMap, CartographyTableMenu.MAP_SLOT, 1) == 0) return ActionStatus.failed("could not put the map in the cartography table");
        if (MenuOps.putInto(p, menu, s -> s.is(extra()), CartographyTableMenu.ADDITIONAL_SLOT, 1) == 0) return ActionStatus.failed("could not put the " + extraName + " in the cartography table");
        if (menu.getSlot(CartographyTableMenu.RESULT_SLOT).getItem().isEmpty()) {
            return ActionStatus.failed("the cartography table can't " + action + " this map (zoom stops at scale 4; locked maps can't change)");
        }
        if (MenuOps.takeResult(p, menu, CartographyTableMenu.RESULT_SLOT).isEmpty()) return ActionStatus.failed("could not take the map from the cartography table");
        String n = id == null ? "" : " #" + id.id();
        done = switch (action) {
            case "zoom" -> "zoomed map" + n + " out to scale " + Math.min(4, scale + 1);
            case "copy" -> "copied map" + n;
            default -> "locked map" + n;
        };
        return ActionStatus.DONE;
    }

    /** Prefers a map this action can still change: zoom wants scale below 4, lock wants an unlocked one. */
    private boolean better(AiPlayer p, ItemStack candidate, ItemStack current) {
        return usable(p, candidate) && !usable(p, current);
    }

    private boolean usable(AiPlayer p, ItemStack map) {
        MapItemSavedData d = MapItem.getSavedData(map, p.level());
        if (d == null) return true;
        return switch (action) {
            case "zoom" -> d.scale < 4 && !d.locked;
            case "lock" -> !d.locked;
            default -> true;
        };
    }

    @Override
    public String describe() {
        return done != null ? done : "cartography " + action;
    }
}
