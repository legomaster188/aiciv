package com.aiciv.items;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemUse;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/** Uses an empty map to map the area around the bot. */
public final class MakeMapAction implements Action {
    private String done;

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (!InventoryOps.has(p, Items.MAP, 1)) return ActionStatus.failed("needs an empty map");
        Set<Integer> before = mapIds(p);
        ItemUse.select(p, s -> s.is(Items.MAP));
        p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            MapId id = s.is(Items.FILLED_MAP) ? s.get(DataComponents.MAP_ID) : null;
            if (id == null || before.contains(id.id())) continue;
            MapItemSavedData data = MapItem.getSavedData(s, p.level());
            if (data == null) continue;
            done = "made map #" + id.id() + " of " + data.centerX + " " + data.centerZ + " (scale " + data.scale + ")";
            return ActionStatus.DONE;
        }
        return ActionStatus.failed("the map was not filled");
    }

    private static Set<Integer> mapIds(AiPlayer p) {
        Set<Integer> out = new HashSet<>();
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            MapId id = s.is(Items.FILLED_MAP) ? s.get(DataComponents.MAP_ID) : null;
            if (id != null) out.add(id.id());
        }
        return out;
    }

    @Override
    public String describe() {
        return done != null ? done : "make_map";
    }

    @Override
    public int timeoutTicks() {
        return 20;
    }
}
